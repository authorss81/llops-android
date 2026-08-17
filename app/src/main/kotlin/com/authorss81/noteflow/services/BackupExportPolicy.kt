package com.authorss81.noteflow.services

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipOutputStream

/**
 * B2-DOS-07 (phase-83): single pure-JVM decision table + bounded streamers for
 * BACKUP EXPORT.
 *
 * Pre-fix, `ImportExportService.exportBackup` built the ENTIRE vault archive in
 * heap and then made one more full-size copy of it:
 *  - the zip (whole DB copy + every imports file + every encrypted voice blob)
 *    was accumulated in a `ByteArrayOutputStream` and materialized by
 *    `baos.toByteArray()` — one full archive in heap;
 *  - the v2 password path then fed that byte array to `cipher.doFinal(...)` —
 *    a SECOND full copy (the GCM ciphertext) plus the header write;
 *  - the device-keyed path Base64-encoded it (`EncryptionService.encrypt` →
 *    ~1.37x amplification) before writing the text file.
 * A vault whose DB+imports reach a few hundred MB made every "Create backup" a
 * ~600 MB+ peak-allocation on the IO thread → OOM crash, recurring on every
 * attempt (the backup feature itself became an interactive DoS).
 *
 * The fix (mirroring the restore-side `copyWithLimit` / B1-DB-5 philosophy,
 * but for WRITES): never hold the archive in heap.
 *  - [zipVaultEntriesToStream] streams the zip straight into a staging file via
 *    `ZipOutputStream(FileOutputStream)` — no `ByteArrayOutputStream`;
 *  - [encryptStreamGcm] encrypts the staged zip file-to-file (AES-GCM, the v2
 *    `backup/payload` domain + the exact header as AAD) with a bounded
 *    [ENCRYPT_CHUNK_BYTES] loop, writing header, then each `Cipher.update`
 *    chunk, then the `doFinal` tail+tag — the JCE contract guarantees the
 *    concatenation is byte-identical to a single `doFinal` over the whole
 *    payload, so the on-disk v2 format is unchanged;
 *  - [encryptStreamDeviceKeyedBase64] writes the legacy device-keyed format
 *    (Base64 of `[version][iv][ciphertext+tag]`) through a streaming
 *    `java.util.Base64.Encoder`, so the file stays byte-identical to the
 *    pre-fix `EncryptionService.encrypt` output without a full array.
 *
 * Memory bound: one [ENCRYPT_CHUNK_BYTES] input buffer + whatever a single
 * `Cipher.update` returns (~one chunk; GCM streams its keystream), never the
 * whole archive. Both streamers own and close the caller-provided streams.
 *
 * Pure JVM (`java.io` + `javax.crypto` + `java.util.Base64` — Base64 landed in
 * the API-26 floor, the app's minSdk) so the whole path is unit-testable
 * without Android and needs no API-gated fallback.
 */
object BackupExportPolicy {

    /** Read/crypt buffer for every bounded loop. Big enough to keep syscall count
     *  low, small enough that peak heap never scales with the vault size. */
    const val ENCRYPT_CHUNK_BYTES: Int = 64 * 1024

    /** Suffix of the transient plaintext zip stage; deleted after encryption. */
    const val STAGING_SUFFIX: String = ".zip-staging"

    private const val IDLE_READ_LIMIT: Int = 16

    /** A staged zip file name derived from the encrypted backup's public name. */
    fun stagingFileName(backupName: String): String = "$backupName$STAGING_SUFFIX"

    /**
     * Streams the vault archive into [dest] via `ZipOutputStream(dest)` — each
     * entry is written incrementally and the zip NEVER materializes in memory.
     * [writeEntries] receives the live ZipOutputStream and must
     * `putNextEntry`/`closeEntry` each entry, copying each source FileInputStream
     * into `zos`. Owns and closes [dest] (ZipOutputStream close closes the
     * underlying stream).
     */
    fun zipVaultEntriesToStream(dest: OutputStream, writeEntries: (ZipOutputStream) -> Unit) {
        ZipOutputStream(dest).use { zos -> writeEntries(zos) }
    }

    /**
     * v2 password-backed export: AES-GCM file-to-file with a bounded chunk loop.
     *
     * Writes `header` first, then the streamed ciphertext (each chunk's
     * `Cipher.update` output), then the `doFinal` tail + 128-bit tag appended by
     * the JCE provider — the exact on-disk layout `[magic][salt][iv][wrappedDek]`
     * ‖ `AES-GCM-CT+tag` written by the pre-fix single-shot path, so existing
     * restores ([decryptBackupPayload]) read it unchanged. Both AAD inputs
     * ([payloadAad] and the header bytes) are supplied BEFORE any plaintext byte
     * is streamed. Peak heap: one chunk + one `update` output + the tag.
     *
     * The returned ciphertext is byte-identical to one `Cipher.doFinal` over the
     * whole file: GCM is a stream mode (CTR keystream + incremental GHASH), and
     * the JCE contract guarantees `update(...)...update(...) + doFinal()` == a
     * single `doFinal()` for the same cipher state — pinned by the byte-equality
     * test.
     */
    fun encryptStreamGcm(
        plain: InputStream,
        dest: OutputStream,
        kek: ByteArray,
        payloadIv: ByteArray,
        header: ByteArray,
        payloadAad: ByteArray
    ) {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(kek, "AES"),
            javax.crypto.spec.GCMParameterSpec(EncryptionService.GCM_TAG_LENGTH, payloadIv)
        )
        cipher.updateAAD(payloadAad)
        cipher.updateAAD(header)
        dest.use { out ->
            out.write(header)
            plain.use { ins ->
                val buffer = ByteArray(ENCRYPT_CHUNK_BYTES)
                var idleReads = 0
                while (true) {
                    val n = ins.read(buffer)
                    if (n < 0) break
                    if (n == 0) {
                        // A stream returning 0 for a non-empty read is out of
                        // contract — fail loudly instead of busy-spinning (mirrors
                        // AttachmentIngestPolicy.boundedReadBytes).
                        if (++idleReads > IDLE_READ_LIMIT) {
                            throw IOException("Encrypt stream made no progress; aborting backup encryption")
                        }
                        continue
                    }
                    idleReads = 0
                    out.write(cipher.update(buffer, 0, n))
                }
            }
            out.write(cipher.doFinal())
        }
    }

    /**
     * Legacy device-keyed export: stream GCM encrypt (the app's FIELD_AAD domain)
     * and Base64-wrap to [dest] with a streaming `java.util.Base64.Encoder`, so
     * the on-disk file is the UTF-8/ASCII Base64 of
     * `[PAYLOAD_VERSION][12-byte IV][ciphertext+tag]` — byte-identical to the
     * pre-fix `EncryptionService.encrypt(...)` String write — without ever holding
     * the archive or its ~1.37x Base64 expansion in one array. Peak heap: one
     * chunk + one `update` output + the encoder's internal buffer.
     *
     * A fresh random IV is drawn here (the pre-fix `EncryptionService.encrypt`
     * also drew a random IV per call), so the output differs across runs just as
     * it did before.
     */
    fun encryptStreamDeviceKeyedBase64(
        plain: InputStream,
        dest: OutputStream,
        key: ByteArray
    ) {
        val iv = EncryptionService.newIv()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(EncryptionService.GCM_TAG_LENGTH, iv)
        )
        cipher.updateAAD(EncryptionService.FIELD_AAD)
        dest.use { out ->
            // wrap() returns an OutputStream whose close() != the encoder's own
            // finish(): it flushes the tail + padding but ALSO closes the wrapped
            // stream. Route through a non-closing sink so `dest` ownership stays
            // with `use` (a failed encryption never half-closes a shared sink).
            val encoder = Base64.getEncoder().wrap(NonClosingSink(out))
            try {
                encoder.write(byteArrayOf(EncryptionService.PAYLOAD_VERSION))
                encoder.write(iv)
                plain.use { ins ->
                    val buffer = ByteArray(ENCRYPT_CHUNK_BYTES)
                    var idleReads = 0
                    while (true) {
                        val n = ins.read(buffer)
                        if (n < 0) break
                        if (n == 0) {
                            if (++idleReads > IDLE_READ_LIMIT) {
                                throw IOException("Encrypt stream made no progress; aborting backup encryption")
                            }
                            continue
                        }
                        idleReads = 0
                        encoder.write(cipher.update(buffer, 0, n))
                    }
                }
                encoder.write(cipher.doFinal())
            } finally {
                // finish() encodes the residual bytes + padding into the sink.
                runCatching { encoder.close() }
            }
        }
    }

    /** Forwards writes but never closes the wrapped stream (the caller's `use`
     *  owns [OutputStream.close]). */
    private class NonClosingSink(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() {
            // no-op: the encoder's finish() on close() emits the residual bytes.
        }

        override fun close() {
            // deliberately does NOT close `out`
        }
    }
}