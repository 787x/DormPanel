package com.dormpanel.app.productivity

import java.util.UUID

interface ProductivityClock { fun wallMillis(): Long; fun elapsedMillis(): Long }
data class TodoItem(val id: String, val text: String, val completed: Boolean = false)
enum class TimerStatus { READY, RUNNING, PAUSED, FINISHED }
data class TimerState(val duration: Long = 300_000, val remaining: Long = duration,
    val status: TimerStatus = TimerStatus.READY, val wallDeadline: Long = 0)
data class ProductivityState(val todos: List<TodoItem> = emptyList(), val memo: String = "", val timer: TimerState = TimerState())
interface ProductivityStore {
    fun load(callback: (Result<Map<String, ProductivityState>>) -> Unit)
    fun save(owner: String, state: ProductivityState, callback: (Result<Unit>) -> Unit)
    fun close()
}

/** Main-thread confined commands and listeners. Store completions must use that same thread.
 * No scheduler: only a visible card requests countdown display updates. */
class ProductivitySource(private val store: ProductivityStore, private val clock: ProductivityClock) {
    private val states = mutableMapOf<String, ProductivityState>()
    private val deadlines = mutableMapOf<String, Long>()
    private val listeners = mutableMapOf<String, MutableSet<() -> Unit>>()
    private var closed = false
    var ready = false; private set
    var error = false; private set
    init {
        store.load { result ->
            if (!closed) {
                result.onSuccess { loaded ->
                    states.putAll(loaded)
                    loaded.forEach { (id, state) ->
                        if (state.timer.status == TimerStatus.RUNNING) deadlines[id] = clock.elapsedMillis() +
                            (state.timer.wallDeadline - clock.wallMillis()).coerceIn(0, state.timer.duration)
                    }
                    ready = true
                }.onFailure { error = true }
                listeners.values.flatMap { it.toList() }.forEach { it() }
            }
        }
    }
    fun state(owner: String): ProductivityState {
        val state = states[owner] ?: ProductivityState()
        if (state.timer.status != TimerStatus.RUNNING) return state
        val remaining = ((deadlines[owner] ?: clock.elapsedMillis()) - clock.elapsedMillis()).coerceAtLeast(0)
        return state.copy(timer = state.timer.copy(remaining = remaining,
            status = if (remaining == 0L) TimerStatus.FINISHED else TimerStatus.RUNNING))
    }
    fun subscribe(owner: String, listener: () -> Unit) { if (!closed) listeners.getOrPut(owner) { linkedSetOf() }.add(listener) }
    fun unsubscribe(owner: String, listener: () -> Unit) { listeners[owner]?.let { it.remove(listener); if (it.isEmpty()) listeners.remove(owner) } }
    private fun update(owner: String, change: (ProductivityState) -> ProductivityState) {
        if (closed || !ready) return
        val next = change(state(owner))
        states[owner] = next
        store.save(owner, next) { result ->
            if (!closed && result.isFailure) { error = true; notify(owner) }
        }
        notify(owner)
    }
    private fun notify(owner: String) { listeners[owner]?.toList()?.forEach { it() } }
    fun addTodo(owner: String, text: String) { if (text.isNotBlank()) update(owner) { it.copy(todos = it.todos + TodoItem(UUID.randomUUID().toString(), text.trim())) } }
    fun editTodo(owner: String, id: String, text: String) { if (text.isNotBlank()) update(owner) { it.copy(todos = it.todos.map { task -> if (task.id == id) task.copy(text = text.trim()) else task }) } }
    fun toggleTodo(owner: String, id: String) = update(owner) { it.copy(todos = it.todos.map { task -> if (task.id == id) task.copy(completed = !task.completed) else task }) }
    fun deleteTodo(owner: String, id: String) = update(owner) { it.copy(todos = it.todos.filterNot { task -> task.id == id }) }
    fun saveMemo(owner: String, text: String) = update(owner) { it.copy(memo = text) }
    fun configureTimer(owner: String, duration: Long) {
        if (duration !in 1_000..86_400_000) return
        update(owner) { deadlines.remove(owner); it.copy(timer = TimerState(duration)) }
    }
    fun startTimer(owner: String) = update(owner) {
        if (it.timer.status == TimerStatus.RUNNING) it else {
            val remaining = if (it.timer.status == TimerStatus.PAUSED) it.timer.remaining else it.timer.duration
            deadlines[owner] = clock.elapsedMillis() + remaining
            it.copy(timer = it.timer.copy(remaining = remaining, status = TimerStatus.RUNNING, wallDeadline = clock.wallMillis() + remaining))
        }
    }
    fun pauseTimer(owner: String) = update(owner) {
        if (it.timer.status != TimerStatus.RUNNING) it else {
            deadlines.remove(owner); it.copy(timer = it.timer.copy(status = TimerStatus.PAUSED, wallDeadline = 0))
        }
    }
    fun resetTimer(owner: String) = update(owner) { deadlines.remove(owner); it.copy(timer = TimerState(it.timer.duration)) }
    fun close() { if (!closed) { closed = true; listeners.clear(); store.close() } }
}
