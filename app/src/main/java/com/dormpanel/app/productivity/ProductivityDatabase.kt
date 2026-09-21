package com.dormpanel.app.productivity

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.room.*
import java.util.concurrent.Executors

@Entity(tableName = "owners")
data class ProductivityEntity(@PrimaryKey val owner: String, val memo: String, val duration: Long,
    val remaining: Long, val status: String, val wallDeadline: Long)
@Entity(tableName = "todos", primaryKeys = ["owner", "id"])
data class TodoEntity(val owner: String, val id: String, val text: String, val completed: Boolean, val position: Int)
@Dao
interface ProductivityDao {
    @Query("SELECT * FROM owners") fun owners(): List<ProductivityEntity>
    @Query("SELECT * FROM todos ORDER BY position, id") fun todos(): List<TodoEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putOwner(owner: ProductivityEntity)
    @Query("DELETE FROM todos WHERE owner = :owner") fun deleteTodos(owner: String)
    @Insert fun putTodos(todos: List<TodoEntity>)
}
@Database(entities = [ProductivityEntity::class, TodoEntity::class], version = 1, exportSchema = false)
abstract class ProductivityDatabase : RoomDatabase() { abstract fun dao(): ProductivityDao }

object ProductivityStores {
    @Volatile var overrideFactory: ((Context) -> ProductivityStore)? = null
    fun create(context: Context): ProductivityStore = overrideFactory?.invoke(context) ?: RoomProductivityStore(
        Room.databaseBuilder(context.applicationContext, ProductivityDatabase::class.java, "productivity.db").build())
}
object AndroidProductivityClock : ProductivityClock {
    override fun wallMillis() = System.currentTimeMillis()
    override fun elapsedMillis() = SystemClock.elapsedRealtime()
}
/** Admission and close share a lock; queued writes drain before the database is closed.
 * Posted callbacks recheck closed, so they cannot resurrect a disposed source. */
class RoomProductivityStore(private val database: ProductivityDatabase,
    private val executor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor(),
    private val closeDatabase: Boolean = true) : ProductivityStore {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Synchronized private fun <T> submit(callback: (Result<T>) -> Unit, operation: () -> T) {
        if (closed) return
        executor.execute {
            val result = runCatching(operation)
            handler.post { if (!closed) callback(result) }
        }
    }
    override fun load(callback: (Result<Map<String, ProductivityState>>) -> Unit) = submit(callback) {
        database.runInTransaction<Map<String, ProductivityState>> {
            val dao = database.dao()
            val todos = dao.todos().groupBy { it.owner }
            dao.owners().associate { entity -> entity.owner to ProductivityState(
                todos[entity.owner].orEmpty().map { TodoItem(it.id, it.text, it.completed) }, entity.memo,
                TimerState(entity.duration, entity.remaining, TimerStatus.valueOf(entity.status), entity.wallDeadline)) }
        }
    }
    override fun save(owner: String, state: ProductivityState, callback: (Result<Unit>) -> Unit) = submit(callback) {
        database.runInTransaction {
            val dao = database.dao(); val timer = state.timer
            dao.putOwner(ProductivityEntity(owner, state.memo, timer.duration, timer.remaining, timer.status.name, timer.wallDeadline))
            dao.deleteTodos(owner)
            dao.putTodos(state.todos.mapIndexed { index, item -> TodoEntity(owner, item.id, item.text, item.completed, index) })
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        executor.execute { if (closeDatabase) database.close() }
        executor.shutdown()
    }
}
