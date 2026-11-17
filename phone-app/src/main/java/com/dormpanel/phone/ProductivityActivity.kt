package com.dormpanel.phone

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dormpanel.app.schedule.*

internal object PhoneProductivity {
    const val LOCAL = "local"
    const val MODE = "mode"
    private lateinit var app: Context
    lateinit var local: LocalProductivity
        private set
    lateinit var bindings: ProductivityBindings
        private set
    val data by lazy { AndroidProductivity(app, ScheduleStores.create(app) as? RoomScheduleStore, local) }

    fun initialize(context: Context) {
        app = context.applicationContext
        local = LocalProductivity(app)
        bindings = ProductivityBindings(app, "phone-productivity-bindings")
    }

    fun mode() = bindings.get(MODE) ?: LOCAL
    fun dataset(mode: String) = mode.takeUnless { it == LOCAL }
}

class ProductivityActivity : AppCompatActivity() {
    private var panel: ProductivityPanel? = null
    private val data get() = PhoneProductivity.data
    private val kind get() = if (intent.getBooleanExtra("memo", false)) ProductivityKind.MEMO else ProductivityKind.TODO
    private var busy = false
    private var target: ProductivityTarget? = null
    private var task: String? = null
    private var mode: String = PhoneProductivity.LOCAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = ProductivityTarget.decode(intent.getStringExtra("target"))
        task = intent.getStringExtra("task").takeIf { savedInstanceState == null }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = kind.label
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        open()
    }

    override fun onDestroy() {
        panel?.close()
        panel = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun open() {
        if (isFinishing || isDestroyed) return
        val binding = intent.getStringExtra("widgetBinding")
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (binding != null && !ProductivityWidgets.isTodoBindingCurrent(this, widgetId, binding)) {
            finish()
            return
        }
        panel?.close()
        mode = runCatching { target?.datasetId ?: PhoneProductivity.mode() }.getOrElse {
            setContentView(TextView(this).apply { text = "本机数据来源读取失败 · ${it.message}" })
            return
        }
        val editor = ProductivityPanel(this, data, kind, PhoneProductivity.dataset(mode), target?.recordId, task,
            editMemo = intent.getBooleanExtra("editMemo", false),
            local = mode == PhoneProductivity.LOCAL, sourceAction = ::sources,
            validate = when {
                binding != null -> ({
                    check(ProductivityWidgets.isTodoBindingCurrent(applicationContext, widgetId, binding)) { "小组件绑定已变化" }
                })
                target != null -> ({})
                else -> guardMode(mode)
            })
        task = null
        intent.removeExtra("editMemo")
        panel = editor
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(android.widget.Button(this@ProductivityActivity).apply {
                text = "返回 · ${kind.label}"
                isAllCaps = false
                setOnClickListener { finish() }
            })
            addView(editor.view, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
        setContentView(root)
        val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = light
        editor.start()
    }

    private fun guardMode(expected: String): () -> Unit = {
        check(PhoneProductivity.mode() == expected) { "管理页面数据来源已变化，请重新打开" }
    }

    private fun sources() {
        if (busy) return
        val origin = PhoneProductivity.bindings.get(PhoneProductivity.MODE)
        val labels = arrayOf("使用仅本机数据", "使用现有共享数据", "预览并导入本机 Todo 和备忘")
        AlertDialog.Builder(this).setTitle("数据来源").setItems(labels) { _, index ->
            if (index == 0) changeMode(PhoneProductivity.LOCAL, origin)
            else sharedPreview(index == 2, origin)
        }.setNegativeButton("取消", null).show()
    }

    private fun changeMode(value: String, origin: String?, version: ProductivityVersion? = null) {
        if (busy) return
        busy = true
        AndroidProductivity.work(::changed) {
            if (version == null) PhoneProductivity.bindings.setIf(PhoneProductivity.MODE, origin, value)
            else requireNotNull(data.shared).withProductivityBinding(version) {
                PhoneProductivity.bindings.setIf(PhoneProductivity.MODE, origin, value)
            }
        }
    }

    private fun changed(result: Result<Unit>) {
        busy = false
        if (isFinishing || isDestroyed) return
        result.fold({
            target = null
            open()
        }, ::error)
    }

    private fun error(error: Throwable) {
        if (!isFinishing && !isDestroyed) productivityMessage("数据来源未切换", error.message.orEmpty())
    }

    private fun sharedPreview(importLocal: Boolean, origin: String?) {
        val store = data.shared ?: return error(IllegalStateException("共享 Store 不可用"))
        store.loadProductivity { outcome ->
            if (isFinishing || isDestroyed) return@loadProductivity
            outcome.fold({ view ->
                val version = view.version
                if (version == null || view.writeBlock != null) {
                    error(IllegalStateException(if (version == null)
                        "请在 WebDAV 共享管理中创建或加入已验证的 DPV3/4 数据集。现有 DPV3/3 绑定继续使用原协议。"
                    else view.writeBlock))
                    return@fold
                }
                if (!importLocal) {
                    AlertDialog.Builder(this).setTitle("使用现有共享数据")
                        .setMessage("共享数据集 ${version.datasetId}\n${view.content.lists.size} 张清单、${view.content.memos.size} 条备忘。原本机内容会保留。")
                        .setPositiveButton("使用共享数据") { _, _ -> changeMode(version.datasetId, origin, version) }
                        .setNegativeButton("取消", null).show()
                    return@fold
                }
                PhoneProductivity.local.load { local -> local.fold({ preview ->
                    val source = requireNotNull(preview.version)
                    val lists = preview.content.lists
                    val memos = preview.content.memos
                    if (lists.isEmpty() && memos.isEmpty()) {
                        error(IllegalStateException("没有可导入的本机内容"))
                    } else PhoneProductivity.local.transfer(store, source, version, false, {}) { checked ->
                        checked.fold(checked@{
                            if (isFinishing || isDestroyed) return@checked
                            AlertDialog.Builder(this).setTitle("导入本机 Todo 和备忘")
                                .setMessage("${preview.content.importPreview()}\n\n原本机内容会保留。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("确认导入并切换") { _, _ ->
                                    if (!busy) {
                                        busy = true
                                        PhoneProductivity.local.transfer(store, source, version, true, {
                                            PhoneProductivity.bindings.setIf(PhoneProductivity.MODE, origin, version.datasetId)
                                        }, ::changed)
                                    }
                                }.show()
                        }, ::error)
                    }
                }, ::error) }
            }, ::error)
        }
    }

    companion object {
        internal fun intent(context: Context, kind: ProductivityKind, target: ProductivityTarget? = null, task: String? = null) =
            Intent(context, ProductivityActivity::class.java).putExtra("memo", kind == ProductivityKind.MEMO)
                .putExtra("target", target?.encode()).putExtra("task", task)
    }
}

/** Launcher configuration is the only exported efficiency Activity. */
class ProductivityWidgetConfiguration : AppCompatActivity() {
    private var pending = false
    private val dialogs = mutableListOf<AlertDialog>()

    override fun onDestroy() {
        dialogs.forEach { it.dismiss() }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)
        val kind = ProductivityWidgets.kind(this, id)
        if (kind == null) { finish(); return }
        val preferred = runCatching { PhoneProductivity.mode() }.getOrElse {
            productivityMessage("读取失败", it.message.orEmpty()).setOnDismissListener { finish() }
            return
        }
        val options = arrayOf(preferred, if (preferred == PhoneProductivity.LOCAL) "shared" else PhoneProductivity.LOCAL)
        dialogs += AlertDialog.Builder(this).setTitle("选择小组件数据来源").setItems(options.map {
            if (it == PhoneProductivity.LOCAL) "仅本机" else "共享"
        }.toTypedArray()) { _, choice -> choose(id, kind, options[choice], result) }
            .setOnCancelListener { finish() }.setNegativeButton("取消") { _, _ -> finish() }.show()
    }

    private fun choose(id: Int, kind: ProductivityKind, mode: String, result: Intent) {
        val callback: (Result<ProductivityView>) -> Unit = { read ->
            if (!isFinishing && !isDestroyed) {
                val view = read.getOrNull()
                val version = view?.version
                val items = view?.let { kind.records(it.content) }.orEmpty()
                if (version == null || items.isEmpty()) {
                    productivityMessage("没有可选内容", read.exceptionOrNull()?.message ?: view?.writeBlock
                        ?: "请先在 DormPanel 的管理菜单创建${kind.label}。").setOnDismissListener { finish() }
                } else dialogs += AlertDialog.Builder(this).setTitle("选择${kind.label}")
                    .setItems(productivityChoices(items)) { _, index ->
                        if (!pending) {
                            pending = true
                            val target = ProductivityTarget(version.datasetId, items[index].id)
                            ProductivityWidgets.bind(this, id, kind, target, version) { saved ->
                                if (!isDestroyed) {
                                    saved.fold({
                                        setResult(RESULT_OK, result)
                                        finish()
                                    }, {
                                        productivityMessage("绑定失败", it.message.orEmpty()).setOnDismissListener { finish() }
                                    })
                                }
                            }
                        }
                    }.setOnCancelListener { finish() }.setNegativeButton("取消") { _, _ -> finish() }.show()
            }
        }
        if (mode == "shared") {
            dataShared(callback)
        } else PhoneProductivity.data.load(PhoneProductivity.dataset(mode), callback)
    }

    private fun dataShared(callback: (Result<ProductivityView>) -> Unit) {
        val store = PhoneProductivity.data.shared
        if (store == null) callback(Result.failure(IllegalStateException("共享 Store 不可用")))
        else store.loadProductivity(callback)
    }
}
