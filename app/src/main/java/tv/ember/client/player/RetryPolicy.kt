package tv.ember.client.player

object RetryPolicy {
    fun delayMs(attempt: Int): Long = minOf(30_000L, 1000L shl attempt.coerceIn(0, 5))
    fun retryableHttp(code: Int) = code == 408 || code == 429 || code in 500..599
}
