package dev.retrobit.assistant.chat

/** 3 kegagalan beruntun → online dilewati 60 detik. 429 → dilewati selama retryDelay. */
class CircuitBreaker(private val now: () -> Long = { System.currentTimeMillis() }) {
    private var failures = 0
    private var openUntil = 0L

    val isOpen: Boolean get() = now() < openUntil
    val secondsLeft: Long get() = ((openUntil - now()) / 1000).coerceAtLeast(0)

    fun success() {
        failures = 0
        openUntil = 0
    }

    fun failure() {
        failures++
        if (failures >= 3) {
            openUntil = now() + 60_000
            failures = 0
        }
    }

    fun openFor(ms: Long) {
        openUntil = now() + ms
        failures = 0
    }
}
