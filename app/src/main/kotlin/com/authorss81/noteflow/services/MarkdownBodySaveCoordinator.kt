package com.authorss81.noteflow.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * B2-UI-5 (phase-74): serializes + makes latest-wins EVERY markdown/text note-body
 * save for a page, so a slow older write can never land AFTER a newer one (the
 * DB-modern analog of the finding's torn `File.writeText`/`readText` race), and
 * lets a reader await the settle of any in-flight save before reading the body
 * back.
 *
 * The Android-binding ([com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel]) drives the
 * actual encrypted-column write through this decision + serialization primitive;
 * the class itself is pure JVM (only kotlinx.coroutines primitives) and is fully
 * unit-testable on the CI runner.
 *
 * Rules:
 *  1. [issue] registers a save in the CALLER's issue order and stamps it with a
 *     monotonically increasing sequence number — the later a request is issued,
 *     the higher its seq.
 *  2. [commitLatest] runs [write] under a per-page [Mutex] AND only when this
 *     request is still the newest issued for its page (by [latestSeqByPage]). A
 *     superseded request commits NOTHING (returns false); a newer request (higher
 *     seq) holds the mutex next and commits its body — so the newest issued save
 *     is always the last one to touch the store.
 *  3. Every request settles exactly once when its critical section exits. With
 *     the bounded [awaitSettled] a reader waits out whatever save was issued
 *     before it started, then reads the store — whose update committed inside
 *     the same critical section — so the read can never observe a partial value.
 *
 * The write inside [commitLatest] is the enclosing SQLCipher/DAO update, which is
 * itself a transactional atomic write — no partial/truncated state is ever
 * observable. Note-body FILES (the finding's original `File.writeText` target) no
 * longer exist on this path since phase-44 (B1-DB-4); the remaining data-loss
 * vector this closes is purely save-ordering + stale-snapshot-read.
 */
class MarkdownBodySaveCoordinator(
    /** Test seam: default matches the production constant. */
    private val settleTimeoutMs: Long = SETTLE_AWAIT_TIMEOUT_MS
) {

    /**
     * A single save request. Immutable after [issue] — [seq] is the caller-order
     * sequence number used to decide whether this request is still the winner.
     */
    class SaveRequest(
        val pageId: String,
        val body: String,
        val legacySourceFilePath: String?,
        val legacySourceFileType: String?,
        val seq: Long
    )

    companion object {
        /**
         * Upper bound a reader waits for a page's in-flight save to settle before
         * reading the body back. Saves are fast (one column update); the bound
         * only guards against a stuck/cancelled coroutine so the read can never
         * hang the editor.
         */
        const val SETTLE_AWAIT_TIMEOUT_MS = 3000L
    }

    private val seqCounter = AtomicLong(0L)
    private val latestSeqByPage = ConcurrentHashMap<String, Long>()
    private val mutexByPage = ConcurrentHashMap<String, Mutex>()
    private val settleBySeq = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()

    /**
     * Registers a body save in caller order and returns its request token. MUST
     * be called on the UI thread (or in the exact order saves must commit).
     * Calling issue again for the same page supersedes the previous token — the
     * previous one must then [commitLatest] no-op.
     */
    fun issue(
        pageId: String,
        body: String,
        legacySourceFilePath: String?,
        legacySourceFileType: String?
    ): SaveRequest {
        val seq = seqCounter.incrementAndGet()
        latestSeqByPage[pageId] = seq
        settleBySeq.getOrPut(seq) { CompletableDeferred() }
        return SaveRequest(pageId, body, legacySourceFilePath, legacySourceFileType, seq)
    }

    /**
     * Serializes [write] for this request's page and runs it ONLY if [request]
     * is still the newest issued for that page. Returns true when [write] ran
     * (and therefore the store now holds [request.body]); false when the request
     * was superseded and must not touch the store.
     *
     * Even when [write] throws, the request settles (so a reader waiting on it
     * does not hang); replacing the content after a failure is the caller's
     * responsibility (a newer request, or a deferred re-flush after unlock).
     */
    suspend fun commitLatest(request: SaveRequest, write: suspend () -> Unit): Boolean {
        val mutex = mutexByPage.getOrPut(request.pageId) { Mutex() }
        return mutex.withLock {
            if (latestSeqByPage[request.pageId] != request.seq) {
                settle(request.seq)
                false
            } else {
                try {
                    write()
                } finally {
                    settle(request.seq)
                }
                true
            }
        }
    }

    /**
     * True when every save issued for [pageId] before this call has settled
     * (committed fully, or been superseded — in both cases the store no longer
     * moves for it), including chasing any request issued WHILE this function was
     * awaiting. Bounded by [settleTimeoutMs]: on timeout returns false — callers
     * then read the current store state, which is never a partial write.
     */
    suspend fun awaitSettled(pageId: String): Boolean {
        val first = latestSeqByPage[pageId] ?: return true
        val deadline = System.currentTimeMillis() + settleTimeoutMs
        var seq = first
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return false
            val settled = settleBySeq[seq]
            if (settled == null) {
                // Last-known request already settled (its deferral was removed):
                // unless a NEWER request arrived while we were looking, done.
                val latest = latestSeqByPage[pageId] ?: return true
                if (latest == seq) return true else seq = latest
            } else {
                if (withTimeoutOrNull(remaining) { settled.await() } == null) return false
                val latest = latestSeqByPage[pageId] ?: return true
                if (latest == seq) return true else seq = latest
            }
        }
    }

    /** Completes + forgets the settle signal for [seq] (idempotent). */
    private fun settle(seq: Long) {
        settleBySeq.remove(seq)?.complete(Unit)
    }
}