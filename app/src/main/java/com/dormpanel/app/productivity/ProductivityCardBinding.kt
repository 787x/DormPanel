package com.dormpanel.app.productivity

import android.app.AlertDialog
import android.content.Context
import com.dormpanel.app.schedule.*

internal class ProductivityCardBinding(
    private val context: Context,
    private val data: AndroidProductivity,
    private val bindings: ProductivityBindings,
    private val owner: String,
    private val kind: ProductivityKind,
    private val expected: String?,
    private val active: () -> Boolean,
    private val track: (AlertDialog) -> Unit,
    private val pending: (Boolean) -> Unit,
    private val changed: () -> Unit
) {
    private val store get() = data.shared
    private fun show(builder: AlertDialog.Builder) {
        if (active()) track(builder.setNegativeButton("取消", null).show())
    }

    private fun error(error: Throwable) {
        if (active()) track(context.productivityMessage("数据来源未切换", error.message.orEmpty()))
    }

    private fun <T> accept(result: Result<T>, next: (T) -> Unit) {
        if (active()) result.fold(next, ::error)
    }

    private fun persist(value: String?) {
        bindings.setIf(owner, expected, value)
    }

    fun open() {
        val options = if (expected == null) arrayOf("保持本地", "连接已有共享${kind.label}", "导入这张卡片的本地内容")
        else arrayOf("切回本地", "连接已有共享${kind.label}", "导入这张卡片的本地内容")
        show(AlertDialog.Builder(context).setTitle("数据来源").setItems(options) { _, index ->
            when (index) {
                0 -> if (expected != null) {
                    pending(true)
                    AndroidProductivity.work(::saved) { persist(null) }
                }
                1 -> existing()
                2 -> preview()
            }
        })
    }

    private fun saved(result: Result<Unit>) {
        pending(false)
        accept(result) { changed() }
    }

    private fun existing(preferred: String? = null, difference: String? = null) {
        data.load(null) { read -> accept(read) { view ->
            val version = view.version
            if (version == null || view.writeBlock != null) {
                error(IllegalStateException(view.writeBlock ?: "请先启用 DPV3/4"))
                return@accept
            }
            val records = kind.records(view.content)
            val match = records.firstOrNull { it.id == preferred }
            if (match != null) {
                connect(version, match, view.content, difference)
            } else if (records.isEmpty()) {
                error(IllegalStateException("没有可连接的共享${kind.label}"))
            } else {
                show(AlertDialog.Builder(context).setTitle("选择共享${kind.label}")
                    .setItems(productivityChoices(records)) { _, index ->
                        connect(version, records[index], view.content, difference)
                    })
            }
        } }
    }

    private fun connect(version: ProductivityVersion, record: ProductivityRecord, content: SharedProductivity.Content,
        difference: String?) {
        val target = ProductivityTarget(version.datasetId, record.id)
        show(AlertDialog.Builder(context).setTitle("连接「${record.title}」")
            .setMessage(listOfNotNull(difference, record.summary(content)).joinToString("\n\n"))
            .setPositiveButton("连接已有内容") { _, _ ->
                pending(true)
                AndroidProductivity.work(::saved) {
                    requireNotNull(store).withProductivityBinding(version) { persist(target.encode()) }
                }
            })
    }

    private fun preview() {
        val targetStore = store ?: return error(IllegalStateException("没有活动共享 Store"))
        val key = LegacyProductivityImport.Key(owner, if (kind == ProductivityKind.TODO)
            LegacyProductivityImport.Kind.TODO else LegacyProductivityImport.Kind.MEMO)
        LegacyProductivityImport.scan(context, key) { scanned -> accept(scanned) { scan ->
            val preview = LegacyProductivityImport.preview(scan) { "${kind.label} · ${it.owner.take(8)}" }
            val item = preview.items.firstOrNull { it.key == key }
            if (item == null || item.error != null) {
                error(IllegalStateException(item?.error ?: "这张卡片没有可导入的内容"))
                return@accept
            }
            val content = SharedProductivity.project(item.records)
            val record = kind.records(content).single()
            data.load(null) { loaded -> accept(loaded) read@{ view ->
                val version = view.version
                if (version == null || view.writeBlock != null) {
                    error(IllegalStateException(view.writeBlock ?: "请先启用 DPV3/4"))
                    return@read
                }
                val current = kind.records(view.content).firstOrNull { it.id == record.id }
                val equal = current == record && (kind == ProductivityKind.MEMO ||
                    view.content.tasks(record.id) == content.tasks(record.id))
                val summary = "本地导入预览 · ${record.title}\n${record.summary(content)}"
                if (current != null && !equal) {
                    existing(record.id, "$summary\n\n共享端已有同身份的不同内容。下方是当前共享内容。")
                    return@read
                }
                LegacyProductivityImport.check(targetStore, version, preview, setOf(key)) { checked ->
                    accept(checked) { ticket ->
                        val identity = if (equal) "共享端已有相同内容" else "共享端没有同身份内容"
                        show(AlertDialog.Builder(context).setTitle("导入这张卡片")
                            .setMessage("$summary\n\n$identity\n保留原本机内容。")
                            .setPositiveButton("确认导入并绑定") { _, _ ->
                                pending(true)
                                LegacyProductivityImport.confirm(targetStore, ticket) { confirmation ->
                                    if (confirmation.isFailure || !active()) {
                                        pending(false)
                                        confirmation.exceptionOrNull()?.let(::error)
                                        return@confirm
                                    }
                                    confirmation.onSuccess { evidence ->
                                        LegacyProductivityImport.validateBinding(targetStore, evidence, { outcome ->
                                            pending(false)
                                            accept(outcome) { validation ->
                                                when (validation) {
                                                    is LegacyProductivityImport.BindingValidation.Verified -> changed()
                                                    is LegacyProductivityImport.BindingValidation.TargetChanged ->
                                                        existing(record.id, "$summary\n\n共享内容已变化。下方是当前共享内容。")
                                                    is LegacyProductivityImport.BindingValidation.TargetMissing ->
                                                        error(IllegalStateException("共享目标已删除"))
                                                    is LegacyProductivityImport.BindingValidation.DatasetChanged ->
                                                        error(IllegalStateException("共享数据集已变化"))
                                                    LegacyProductivityImport.BindingValidation.SourceChanged ->
                                                        error(IllegalStateException("本机内容已变化，请重新预览"))
                                                    is LegacyProductivityImport.BindingValidation.SourceUnavailable -> error(validation.cause)
                                                    is LegacyProductivityImport.BindingValidation.TargetUnavailable -> error(validation.cause)
                                                }
                                            }
                                        }) { verified ->
                                            targetStore.withProductivityBinding(verified) {
                                                persist(ProductivityTarget(verified.datasetId, record.id).encode())
                                            }
                                        }
                                    }
                                }
                            })
                    }
                }
            } }
        } }
    }
}
