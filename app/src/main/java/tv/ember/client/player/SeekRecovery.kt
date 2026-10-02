package tv.ember.client.player

/** Watch the playback clock and rendered frames after a seek, independently of cache progress. */
class SeekRecovery {
    private var armed = false
    private var lastPosition = 0L
    private var stableStartMs = 0L
    private var stuckSince: Long? = null
    private var recovered = false
    private var lastFrames: Int? = null

    fun begin(positionMs: Long, nowMs: Long) {
        armed = true; recovered = false; lastPosition = positionMs
        stableStartMs = nowMs; stuckSince = null; lastFrames = null
    }
    fun clear() { armed = false; stuckSince = null }
    fun shouldRecover(positionMs: Long, nowMs: Long, expectedToAdvance: Boolean, canAdvance: Boolean, renderedFrames: Int? = null): Boolean {
        if (!armed || recovered) return false
        if (!expectedToAdvance) {
            lastPosition = positionMs; stableStartMs = nowMs; stuckSince = null; lastFrames = renderedFrames
            return false
        }
        val clockMoved = kotlin.math.abs(positionMs - lastPosition) >= 250
        val framesMoved = renderedFrames == null || lastFrames == null || renderedFrames != lastFrames
        lastFrames = renderedFrames
        if (clockMoved && framesMoved) {
            lastPosition = positionMs; stuckSince = null
            // Keep watching the seek until playback has actually progressed for a few seconds.
            if (nowMs - stableStartMs >= 5000) clear()
            return false
        }
        if (clockMoved) lastPosition = positionMs
        if (!canAdvance) { stuckSince = null; stableStartMs = nowMs; return false }
        stableStartMs = nowMs
        if (stuckSince == null) stuckSince = nowMs
        if (nowMs - stuckSince!! < 7000) return false
        recovered = true
        return true
    }
}
