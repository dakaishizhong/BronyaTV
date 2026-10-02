package tv.ember.client.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.common.C
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

@UnstableApi
class TvLoadControl private constructor(
    val policy: BufferPolicy,
    private val allocator: DefaultAllocator,
    private val delegate: DefaultLoadControl
) : LoadControl {
    constructor(policy: BufferPolicy) : this(policy, DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
    private constructor(policy: BufferPolicy, allocator: DefaultAllocator) : this(policy, allocator,
        DefaultLoadControl.Builder().setAllocator(allocator)
            .setBufferDurationsMs(policy.minMs, policy.maxMs, policy.startMs, policy.rebufferMs)
            .setTargetBufferBytes(policy.targetBytes).setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(policy.backBufferMs, policy.backBufferMs>0).build())

    @Volatile private var seeking = false
    fun markSeek() { seeking = true }

    // Explicit forwarding is essential: Kotlin delegation does not forward Java interface defaults.
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId) = delegate.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId) = policy.backBufferMs*1000L
    override fun retainBackBufferFromKeyframe(playerId: PlayerId) = policy.backBufferMs>0

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (allocator.totalBytesAllocated >= policy.targetBytes) return false
        return delegate.shouldContinueLoading(parameters)
    }
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        // A short buffer at the safe byte ceiling must start draining, even if 60 s cannot fit.
        val enoughForSeek = seeking && parameters.bufferedDurationUs >= minOf(policy.startMs,1200)*1000L*parameters.playbackSpeed
        val start = enoughForSeek || (parameters.bufferedDurationUs > 0 && allocator.totalBytesAllocated >= policy.targetBytes) || delegate.shouldStartPlayback(parameters)
        if(start) seeking=false
        return start
    }
    val allocatedBytes get() = allocator.totalBytesAllocated
}
