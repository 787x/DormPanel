package com.dormpanel.phone

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.dormpanel.app.schedule.*
import java.util.concurrent.ConcurrentHashMap

class TodoWidgetProvider : ProductivityWidgetProvider(ProductivityKind.TODO)
class MemoWidgetProvider : ProductivityWidgetProvider(ProductivityKind.MEMO)

abstract class ProductivityWidgetProvider(private val kind: ProductivityKind) : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ProductivityWidgets.ROW -> {
                val pending = goAsync()
                ProductivityWidgets.rowAction(context, kind, intent) { pending.finish() }
            }
            ProductivityWidgets.REFRESH -> {
                val pending = goAsync()
                ProductivityWidgets.reload(context, ProductivityWidgets.id(intent)) { pending.finish() }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> refresh(context)
            else -> super.onReceive(context, intent)
        }
    }

    private fun refresh(context: Context) {
        val pending = goAsync()
        ProductivityWidgets.request(context) { pending.finish() }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        refresh(context)

    override fun onDeleted(context: Context, ids: IntArray) {
        val pending = goAsync()
        ProductivityWidgets.delete(context, kind, ids) { pending.finish() }
    }

    override fun onDisabled(context: Context) = ProductivityWidgets.request(context)
}

internal object ProductivityWidgets {
    const val ROW = "com.dormpanel.phone.productivity.ROW"
    const val REFRESH = "com.dormpanel.phone.productivity.REFRESH"
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private val renderCompletions = mutableListOf<() -> Unit>()
    private var localSubscription: AutoCloseable? = null
    private var sharedSubscription: AutoCloseable? = null
    private var syncSubscription: AutoCloseable? = null
    private var localResult: Result<ProductivityView>? = null
    private var sharedResult: Result<ProductivityView>? = null
    private var facts: SharedSchedulePresentation? = null
    private val errors = mutableMapOf<Int, String>()
    private val bindings get() = PhoneProductivity.bindings
    private val data get() = PhoneProductivity.data

    private data class Bound(val target: ProductivityTarget, val generation: String) {
        fun encode() = "${target.encode()}/$generation"
    }

    internal data class Rows(val binding: String, val version: ProductivityVersion,
        val tasks: List<ProductivityRecord.Task>, val writable: Boolean)

    // System IDs identify allocated instances. Fence callbacks even while the host still lists a deleted ID.
    private val deleted = ConcurrentHashMap.newKeySet<Pair<ProductivityKind, Int>>()
    private val rows = ConcurrentHashMap<Int, Rows>()
    fun rows(id: Int) = rows[id]

    private fun key(id: Int) = "widget-$id"
    private fun provider(context: Context, kind: ProductivityKind) = ComponentName(context,
        if (kind == ProductivityKind.TODO) TodoWidgetProvider::class.java else MemoWidgetProvider::class.java)

    fun kind(context: Context, id: Int): ProductivityKind? =
        providerKind(context, id)?.takeUnless { (it to id) in deleted }

    private fun providerKind(context: Context, id: Int): ProductivityKind? {
        val component = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider ?: return null
        return ProductivityKind.entries.find { provider(context, it) == component }
    }

    private fun binding(id: Int): Bound? {
        val value = bindings.get(key(id)) ?: return null
        val target = ProductivityTarget.decode(value.substringBeforeLast('/')) ?: error("小组件绑定无效")
        return Bound(target, value.substringAfterLast('/'))
    }

    fun bind(context: Context, id: Int, kind: ProductivityKind, target: ProductivityTarget, version: ProductivityVersion,
        callback: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        val previous = runCatching { bindings.get(key(id)) }.getOrElse {
            callback(Result.failure(it))
            return
        }
        data.load(PhoneProductivity.dataset(target.datasetId)) { loaded ->
            val checked = runCatching {
                val view = loaded.getOrThrow()
                check(view.version == version && kind.records(view.content).any { it.id == target.recordId }) { "目标已变化" }
                check(view.writeBlock == null) { view.writeBlock.orEmpty() }
            }
            if (checked.isFailure) { callback(checked); return@load }
            AndroidProductivity.work({ result: Result<Unit> ->
                if ((kind to id) !in deleted) errors.remove(id)
                request(app)
                callback(result)
            }) {
                val persist = {
                    check(kind(app, id) == kind) { "小组件已移除" }
                    bindings.setIf(key(id), previous, Bound(target, AndroidProductivity.id()).encode())
                }
                if (target.datasetId == PhoneProductivity.LOCAL) {
                    PhoneProductivity.local.withVersion(version, persist)
                } else requireNotNull(data.shared).withProductivityBinding(version, persist)
            }
        }
    }

    fun delete(context: Context, kind: ProductivityKind, ids: IntArray, done: () -> Unit) {
        val app = context.applicationContext
        val owned = ids.distinct().filter { id ->
            val provider = providerKind(app, id)
            provider == null || provider == kind
        }
        owned.forEach {
            deleted.add(kind to it)
            rows.remove(it)
            errors.remove(it)
        }
        request(app)
        // The same worker serializes persistence with any bind already admitted before deletion.
        AndroidProductivity.work({ result: Result<Unit> ->
            result.exceptionOrNull()?.let { android.util.Log.e("ProductivityWidgets", "Widget binding cleanup failed", it) }
            request(app)
            done()
        }) {
            owned.forEach {
                if (bindings.get(key(it)) != null) bindings.set(key(it), null)
            }
        }
    }

    fun reload(context: Context, id: Int?, done: () -> Unit) {
        val app = context.applicationContext
        val target = id?.takeIf { kind(app, it) != null }?.let { runCatching { binding(it) }.getOrNull()?.target }
        if (target == null) { request(app, done); return }
        errors.remove(id)
        if (target.datasetId == PhoneProductivity.LOCAL) {
            localResult = null
            data.load(null) { localResult = it; request(app) }
        } else {
            sharedResult = null
            val store = data.shared
            if (store != null) store.loadProductivity { sharedResult = it; request(app) }
            else sharedResult = Result.failure(IllegalStateException("共享 Store 不可用"))
            ScheduleSyncJobs.schedule(app, delay = 0)
        }
        request(app, done)
    }

    fun request(context: Context, done: (() -> Unit)? = null) {
        if (done != null) {
            renderCompletions += done
            handler.postDelayed({ if (renderCompletions.remove(done)) done() }, 6500)
        }
        val app = context.applicationContext
        if (pending) return
        pending = true
        handler.post {
            pending = false
            refresh(app)
        }
    }

    private fun refresh(app: Context) {
        val manager = AppWidgetManager.getInstance(app)
        val widgets = ProductivityKind.entries.flatMap { kind ->
            manager.getAppWidgetIds(provider(app, kind)).filter { (kind to it) !in deleted }.map { id ->
                Triple(kind, id, runCatching { binding(id) })
            }
        }
        val local = widgets.any { it.third.getOrNull()?.target?.datasetId == PhoneProductivity.LOCAL }
        val shared = widgets.any { it.third.getOrNull()?.target?.datasetId?.let { id -> id != PhoneProductivity.LOCAL } == true }
        if (local && localSubscription == null) localSubscription = data.observe(null) { localResult = it; request(app) }
        if (!local) { localSubscription?.close(); localSubscription = null; localResult = null }
        if (shared && sharedSubscription == null) {
            fun readFacts() = data.syncFacts { facts = it.getOrNull(); request(app) }
            sharedSubscription = data.shared?.observeProductivity { sharedResult = it; readFacts(); request(app) }
                ?: AutoCloseable {}.also { sharedResult = Result.failure(IllegalStateException("共享 Store 不可用")) }
            syncSubscription = data.observeSync { readFacts() }
        }
        if (!shared) {
            sharedSubscription?.close(); sharedSubscription = null; sharedResult = null
            syncSubscription?.close(); syncSubscription = null; facts = null
        }
        val currentIds = widgets.map { it.second }.toSet()
        rows.keys.removeAll { it !in currentIds }
        widgets.forEach { (kind, id, bound) ->
            render(app, manager, kind, id, bound)
        }
        if ((!local || localResult != null) && (!shared || sharedResult != null)) {
            val completions = renderCompletions.toList()
            renderCompletions.clear()
            completions.forEach { it() }
        }
    }

    private fun render(app: Context, manager: AppWidgetManager, kind: ProductivityKind, id: Int, bound: Result<Bound?>) {
        val binding = bound.getOrNull()
        val target = binding?.target
        val local = target?.datasetId == PhoneProductivity.LOCAL
        val result = if (local) localResult else sharedResult
        val view = result?.getOrNull()
        val version = view?.version
        val valid = target != null && version?.datasetId == target.datasetId
        val record = if (valid) kind.records(requireNotNull(view).content).firstOrNull { it.id == target?.recordId } else null
        val options = manager.getAppWidgetOptions(id)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150)
        val layout = if (kind == ProductivityKind.TODO) R.layout.productivity_todo_widget else R.layout.productivity_memo_widget
        val remote = RemoteViews(app.packageName, layout)
        val previousRows = rows[id]
        var legacyTodoAdapter = false
        val status = errors[id] ?: when {
            bound.isFailure -> "本机绑定读取失败"
            target == null -> "选择显示内容"
            result == null -> "正在读取…"
            result.isFailure -> "读取失败 · 点击刷新"
            !valid -> "共享数据集已变化"
            record == null -> "内容已删除"
            else -> {
                val status = view!!.status(local, facts?.takeIf { it.bindingId == target.datasetId })
                if (width >= 230 && height >= 150) status else status
                    .replace("本机已保存，有修改待发布", "待发布")
                    .replace("服务器回读已确认，待保存本机回执", "已回读，待记回执")
                    .replace("服务器回读已确认", "服务器已回读")
            }
        }
        remote.setTextViewText(R.id.productivity_title, record?.title ?: kind.label)
        val tasks = if (record is ProductivityRecord.TodoList) view!!.content.tasks(record.id) else emptyList()
        if (record is ProductivityRecord.TodoList) {
            remote.setTextViewText(R.id.productivity_status, "剩余 ${tasks.count { !it.completed }} 项 · $status")
        } else remote.setTextViewText(R.id.productivity_status, status)
        remote.setOnClickPendingIntent(R.id.productivity_title, if (target == null || record == null) configure(app, id)
            else open(app, kind, id, target, editMemo = kind == ProductivityKind.MEMO))
        remote.setOnClickPendingIntent(R.id.productivity_refresh, PendingIntent.getBroadcast(app, id,
            Intent(REFRESH).setComponent(provider(app, kind)).setData(uri("refresh", id)), immutable))
        when (kind) {
            ProductivityKind.MEMO -> {
                val text = (record as? ProductivityRecord.Memo)?.body.orEmpty()
                val lines = ((height - 65) / 24).coerceIn(1, 30)
                remote.setInt(R.id.productivity_body, "setMaxLines", lines)
                remote.setTextViewText(R.id.productivity_body, text.take((lines * (width / 6)).coerceIn(40, 2800)))
                remote.setOnClickPendingIntent(R.id.productivity_body, if (target == null || record == null) configure(app, id)
                    else open(app, kind, id, target, editMemo = true))
            }
            ProductivityKind.TODO -> {
                val ordered = tasks.sortedBy { it.completed }
                val snapshot = if (record != null && version != null && binding != null) {
                    Rows(binding.encode(), version, ordered, view.writeBlock == null)
                } else null
                if (snapshot != null) {
                    rows[id] = snapshot
                } else rows.remove(id)
                remote.setViewVisibility(R.id.productivity_tasks, if (record == null) View.GONE else View.VISIBLE)
                // RemoteViews sends this Activity PendingIntent with the host's user-interaction grant.
                val template = Intent(app, ProductivityWidgetClickActivity::class.java).setAction(ROW)
                    .setData(uri("row", id)).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                remote.setPendingIntentTemplate(R.id.productivity_tasks, PendingIntent.getActivity(app, id, template,
                    PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                // Publish the header and rows in one RemoteViews on modern hosts. Keep
                // large lists on the lazy adapter to bound the direct Binder payload.
                if (Build.VERSION.SDK_INT >= 31 && ordered.size <= 128 &&
                    ordered.sumOf { it.text.length } <= 32_768) {
                    val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1)
                    if (snapshot != null) ordered.forEachIndexed { index, task ->
                        items.addItem(index.toLong(), taskRow(app, snapshot, task))
                    }
                    remote.setRemoteAdapter(R.id.productivity_tasks, items.build())
                } else {
                    legacyTodoAdapter = true
                    remote.setRemoteAdapter(R.id.productivity_tasks,
                        Intent(app, ProductivityTaskService::class.java).setData(uri("tasks", id)))
                }
            }
        }
        manager.updateAppWidget(id, remote)
        if (legacyTodoAdapter && previousRows != rows[id]) {
            manager.notifyAppWidgetViewDataChanged(id, R.id.productivity_tasks)
        }
    }

    fun taskRow(app: Context, rows: Rows, task: ProductivityRecord.Task): RemoteViews {
        val action = Intent().putExtra("binding", rows.binding).putExtra("task", task.id)
            .putExtra("revision", rows.version.revision).putExtra("hash", rows.version.businessHash)
        return RemoteViews(app.packageName, R.layout.productivity_task_widget_row).apply {
            setTextViewText(R.id.productivity_task, task.text)
            setTextViewText(R.id.productivity_check, if (task.completed) "☑" else "☐")
            setContentDescription(R.id.productivity_check, if (task.completed) "取消完成" else "标为完成")
            if (rows.writable) setOnClickFillInIntent(R.id.productivity_check,
                Intent(action).putExtra("completed", !task.completed))
            setOnClickFillInIntent(R.id.productivity_task, action)
        }
    }

    private fun configure(app: Context, id: Int) = PendingIntent.getActivity(app, id,
        Intent(app, ProductivityWidgetConfiguration::class.java).setData(uri("configure", id))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), immutable)

    private fun open(app: Context, kind: ProductivityKind, id: Int, target: ProductivityTarget, editMemo: Boolean = false) =
        PendingIntent.getActivity(app, id, ProductivityActivity.intent(app, kind, target)
            .putExtra("editMemo", editMemo).setData(uri(if (editMemo) "memo-edit" else "open", id)), immutable)

    private val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    private fun uri(action: String, id: Int) = Uri.parse("dormpanel-productivity://$action/$id")
    fun id(intent: Intent) = intent.data?.lastPathSegment?.toIntOrNull()

    fun isTodoBindingCurrent(context: Context, id: Int, encoded: String?): Boolean =
        encoded != null && kind(context, id) == ProductivityKind.TODO &&
            runCatching { binding(id)?.encode() == encoded }.getOrDefault(false)

    fun openTask(context: Context, click: Intent, done: (Intent?) -> Unit) {
        val id = id(click)
        val encoded = click.getStringExtra("binding")
        val task = click.getStringExtra("task")
        val bound = id?.let { runCatching { binding(it) }.getOrNull() }
        if (id == null || task == null || bound == null || encoded != bound.encode() ||
            kind(context, id) != ProductivityKind.TODO) {
            done(null)
            return
        }
        val target = bound.target
        data.load(PhoneProductivity.dataset(target.datasetId)) { result ->
            val view = result.getOrNull()
            val exists = view?.version?.datasetId == target.datasetId &&
                view.content.tasks(target.recordId).any { it.id == task }
            done(if (exists && isTodoBindingCurrent(context, id, encoded)) {
                ProductivityActivity.intent(context, ProductivityKind.TODO, target, task)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("widgetBinding", encoded)
                    // Use the app task for the editor; the transient click task must be empty after dispatch.
                    .setData(uri("task-edit", id).buildUpon().appendPath(AndroidProductivity.id()).build())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else null)
        }
    }

    fun rowAction(context: Context, kind: ProductivityKind, intent: Intent, done: () -> Unit) {
        val app = context.applicationContext
        val id = id(intent)
        val bound = id?.let { runCatching { binding(it) }.getOrNull() }
        val taskId = intent.getStringExtra("task")
        if (!intent.hasExtra("completed") || id == null || kind != ProductivityKind.TODO || kind(app, id) != kind || bound == null ||
            bound.encode() != intent.getStringExtra("binding") || taskId == null) { done(); return }
        val target = bound.target
        val desired = intent.getBooleanExtra("completed", false)
        data.load(PhoneProductivity.dataset(target.datasetId)) { result ->
            val view = result.getOrNull()
            val task = view?.content?.tasks(target.recordId)?.firstOrNull { it.id == taskId }
            val version = view?.version
            fun bindingStillCurrent() = kind(app, id) == kind && binding(id) == bound
            fun finish(message: String? = null) {
                if (isTodoBindingCurrent(app, id, bound.encode())) {
                    if (message == null) errors.remove(id) else errors[id] = message
                    request(app)
                }
                done()
            }
            if (!runCatching { bindingStillCurrent() }.getOrDefault(false)) { done(); return@load }
            if (task == null || version == null) { finish("任务已变化 · 请刷新"); return@load }
            if (task.completed == desired) { finish(); return@load }
            if (view.writeBlock != null || version.revision != intent.getLongExtra("revision", -1) ||
                version.businessHash != intent.getStringExtra("hash")) {
                finish("内容已更新 · 请重新选择")
                return@load
            }
            data.save(version, listOf(ProductivityEdit.SetTaskCompleted(taskId, desired)), {
                finish(it.exceptionOrNull()?.message)
            }) { check(bindingStillCurrent()) { "小组件绑定已变化" } }
        }
    }
}

/** A collection has one template. This transparent entry handles both row actions. */
class ProductivityWidgetClickActivity : Activity() {
    private var dispatched = false

    override fun onPostResume() {
        super.onPostResume()
        if (dispatched) return
        dispatched = true
        if (intent.action != ProductivityWidgets.ROW) {
            finish()
        } else if (intent.hasExtra("completed")) {
            ProductivityWidgets.rowAction(applicationContext, ProductivityKind.TODO, intent) { finish() }
        } else {
            ProductivityWidgets.openTask(applicationContext, intent) { editor ->
                if (!isFinishing && !isDestroyed) {
                    if (editor != null) startActivity(editor)
                    finish()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish()
    }
}

class ProductivityTaskService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val app = applicationContext
        val id = ProductivityWidgets.id(intent) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        return object : RemoteViewsFactory {
            private var state: ProductivityWidgets.Rows? = null
            override fun onCreate() = Unit
            override fun onDataSetChanged() { state = ProductivityWidgets.rows(id) }
            override fun onDestroy() { state = null }
            override fun getCount() = state?.tasks?.size ?: 0
            override fun getLoadingView(): RemoteViews? = null
            override fun getViewTypeCount() = 1
            override fun getItemId(position: Int) = position.toLong()
            override fun hasStableIds() = false

            override fun getViewAt(position: Int): RemoteViews? {
                val rows = state ?: return null
                val task = rows.tasks.getOrNull(position) ?: return null
                return ProductivityWidgets.taskRow(app, rows, task)
            }
        }
    }
}
