package tv.ember.client.ui

/** Shared across activities so a held BACK key cannot unwind multiple pages. */
class BackKeyGate {
    private var handledDownTime: Long? = null
    fun accept(downTime: Long, canceled: Boolean): Boolean {
        if (canceled || handledDownTime == downTime) return false
        handledDownTime = downTime
        return true
    }
}
