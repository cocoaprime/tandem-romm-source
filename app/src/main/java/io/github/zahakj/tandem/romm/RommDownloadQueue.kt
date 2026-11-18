package io.github.zahakj.tandem.romm

/** FIFO scheduling for user-initiated requests, confined to the main thread. */
internal class RommDownloadQueue<K : Any, T : Any>(private val parallelism: Int, private val waitingLimit: Int) {
    private val waiting = linkedMapOf<K, T>()
    private val running = mutableMapOf<K, T>()
    private val destinations = mutableMapOf<K, Set<String>>()
    val runningCount: Int get() = running.size
    val waitingCount: Int get() = waiting.size
    val hasWork: Boolean get() = running.isNotEmpty() || waiting.isNotEmpty()
    val active: Collection<T> get() = running.values

    operator fun get(key: K): T? = running[key] ?: waiting[key]

    fun enqueue(key: K, task: T): Boolean {
        if (get(key) != null || waiting.size >= waitingLimit) return false
        waiting[key] = task
        return true
    }

    fun startNext(): T? {
        if (running.size >= parallelism) return null
        val (key, task) = waiting.entries.firstOrNull() ?: return null
        waiting.remove(key)
        running[key] = task
        return task
    }

    fun reserve(key: K, vararg resources: String): Boolean {
        check(key in running && key !in destinations)
        if (destinations.values.any { reserved -> resources.any { it in reserved } }) return false
        destinations[key] = resources.toSet()
        return true
    }

    fun finish(key: K) {
        destinations.remove(key)
        running.remove(key)
    }

    fun removeWaiting(key: K): T? = waiting.remove(key)

    fun drainWaiting(): List<T> = waiting.values.toList().also { waiting.clear() }
}
