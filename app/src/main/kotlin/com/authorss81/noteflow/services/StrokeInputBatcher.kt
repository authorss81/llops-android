package com.authorss81.noteflow.services

/**
 * Phase 214 (Stroke Smoothing v2): lock-free single-producer/single-consumer
 * queue for coalesced MotionEvent history.
 *
 * Why: on batching digitizers each delivered `MotionEvent` can carry several
 * past physical pen/touch samples (`historySize`, readable via
 * `getHistoricalX/Y/Pressure/getHistoricalAxisValue`). Pre-214 the canvas read
 * ONLY the newest sample per event, so 120-240 Hz hardware was silently
 * downsampled to the app's dispatch rate — temporal resolution was thrown away
 * before any smoothing ever ran.
 *
 * Producer: the passive `pointerInteropFilter` bridge, which pushes every
 * historical sample followed by the current sample of each ACTION_MOVE
 * (`offer`). It never blocks and never allocates after construction (parallel
 * primitive ring arrays). When `historySize == 0` exactly ONE current sample is
 * offered, so non-batching devices keep the pre-214 "one event → one sample"
 * behaviour (pinned by `HistoryBatchTest`).
 *
 * Consumer: the drag handler drains the queue FIFO immediately BEFORE the EWMA
 * runs (`drain`), so historical samples flow through the exact same pipeline
 * (page-bounds gate → pressure/tilt low-pass → stabilizer) as live ones.
 *
 * Coordinates are stored in the RAW window space the MotionEvent reports;
 * mapping into canvas world space happens at CONSUMPTION time using the
 * current pan/zoom/box-offset (same transform the predicted-tail path uses),
 * never at capture time.
 *
 * Thread-safety: SPSC ring with @Volatile indices — safe even if the producer
 * and consumer ever ran on different threads, though today both run on the UI
 * thread. Overflow overwrites the OLDEST samples (a stroke that outruns the
 * consumer keeps its freshest geometry, never stalls).
 */
class RawInputSample(
    /** Window-space X (host view coordinates, as reported by the MotionEvent). */
    val x: Float,
    /** Window-space Y (host view coordinates). */
    val y: Float,
    /** Raw pointer pressure in [0..1], UNREMAPPED (smoothing precedes remap). */
    val pressure: Float,
    /** Raw AXIS_TILT value in RADIANS (converted to degrees at consumption). */
    val tiltRad: Float,
    /** Event uptime millis (`eventTime` / `historicalEventTime(h)` clock). */
    val timestampMs: Long
)

class StrokeInputBatcher(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity >= 4) { "capacity must be >= 4" }
    }

    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val ps = FloatArray(capacity)
    private val ts = FloatArray(capacity)
    private val stamps = LongArray(capacity)

    /** Written by the producer only. */
    @Volatile
    private var tail = 0

    /** Written by the consumer only. */
    @Volatile
    private var head = 0

    val size: Int get() = tail - head

    val isNotEmpty: Boolean get() = size > 0

    /** Offers one raw sample (producer side). Overwrites oldest when full. */
    fun offer(sample: RawInputSample) {
        if (size == capacity) {
            // Drop the oldest to make room: freshest geometry wins.
            head++
        }
        val t = tail % capacity
        xs[t] = sample.x
        ys[t] = sample.y
        ps[t] = sample.pressure
        ts[t] = sample.tiltRad
        stamps[t] = sample.timestampMs
        tail++
    }

    /**
     * Drains every queued sample FIFO order into [sink] (cleared first).
     * Returns the number of drained samples. Consumer side only.
     */
    fun drainInto(sink: MutableList<RawInputSample>): Int {
        sink.clear()
        while (head < tail) {
            val h = head % capacity
            sink.add(RawInputSample(xs[h], ys[h], ps[h], ts[h], stamps[h]))
            head++
        }
        return sink.size
    }

    /** Discards everything (stroke start/end boundaries). */
    fun clear() {
        head = tail
    }

    companion object {
        /**
         * Room for ~2 frames of a worst-case 240 Hz batching stylus on a 60 Hz
         * display (4 samples/event) plus slack. Bounded by design; overflow
         * sheds oldest-first.
         */
        const val DEFAULT_CAPACITY = 64
    }
}

/**
 * Decision table around batch ingestion (pure JVM, unit-testable).
 */
object StrokeBatchPolicy {

    /**
     * Monotonic gate: a sample whose timestamp is not strictly newer than the
     * last ACCEPTED sample is dropped. Protects against replayed/duplicate
     * events (e.g. the same physical move surfacing twice) without trusting
     * wall clocks — timestamps are the MotionEvent uptime clock only.
     */
    fun isStale(sampleTimestampMs: Long, lastAcceptedTimestampMs: Long?): Boolean =
        lastAcceptedTimestampMs != null && sampleTimestampMs <= lastAcceptedTimestampMs

    /**
     * Historical sample count for a given [historySize]. Identity here is
     * deliberate and PINNED: a non-batching event (historySize == 0) must
     * yield exactly ZERO extra reads plus the single current-sample offer,
     * i.e. one consumed point per event exactly like pre-214.
     */
    fun historicalCount(historySize: Int): Int = historySize.coerceAtLeast(0)
}
