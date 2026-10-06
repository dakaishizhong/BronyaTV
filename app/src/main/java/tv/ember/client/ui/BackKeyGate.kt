package tv.ember.client.ui

/** A physical Back press belongs to one window, including its repeats and release. */
class BackKeyGate {
    companion object { val shared=BackKeyGate() }
    private var handledDownTime:Long?=null
    private var pressedDownTime:Long?=null
    private var pressedOwner:Any?=null
    @Synchronized fun begin(downTime:Long,owner:Any) {
        if(pressedDownTime!=downTime && handledDownTime!=downTime) { pressedDownTime=downTime;pressedOwner=owner }
    }
    @Synchronized fun accept(downTime:Long,canceled:Boolean,owner:Any?=null):Boolean {
        if(handledDownTime==downTime) return false
        if(owner!=null && (pressedDownTime!=downTime || pressedOwner!==owner)) return false
        handledDownTime=downTime;pressedOwner=null
        return !canceled
    }
}
