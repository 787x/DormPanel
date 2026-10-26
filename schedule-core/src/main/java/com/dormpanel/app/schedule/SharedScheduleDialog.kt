package com.dormpanel.app.schedule

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.*
import java.time.Instant

/** Settings surface only; transport, validation and persistence belong to the controller/core. */
object SharedScheduleDialog {
    fun show(context: Context, source: ScheduleSource, sync: SharedScheduleController, publisher: Boolean): AlertDialog {
        var active = true
        var previewDialog: AlertDialog? = null
        var folderDialog: AlertDialog? = null
        val fields = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        fun label(text: String) = TextView(context).apply { this.text = text; textSize = 16f; fields.addView(this) }
        fun input(hint: String, value: String, secret: Boolean = false) = EditText(context).apply {
            this.hint = hint; contentDescription = hint; setText(value); setSingleLine()
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            fields.addView(this)
        }
        label(if (publisher) "X08E 与手机双端可编辑。保存后自动同步，离线修改保留在本地。\n使用已配置的 WebDAV 账号；浏览选择已有共享父目录。未绑定时可创建新课表，或预览并绑定已有课表（要求本地为空）。" else
            "绑定后支持双端编辑与自动同步。真正冲突会保留双方数据并等待处理。首次绑定仍要求空课表，已有 v1 绑定请明确切换到 v2。")
        val public = sync.accounts.publicAccount()
        val url = if (!publisher) input("WebDAV 服务地址", public.first) else null
        val user = if (!publisher) input("用户名", public.second) else null
        val password = if (!publisher) input("密码（留空保留已保存密码）", "", true) else null
        val folder = input("共享目录完整 URL", sync.settings.folder)
        fun bound() = source.syncStore?.isV3() == true || source.state.sync != null || sync.settings.publishedRevision != null || sync.settings.pendingRevision != null
        var confirmedFolder=folder.text.toString().takeIf { it.isNotBlank() && runCatching { WebDavClient().url(it) }.isSuccess }.orEmpty()
        folder.visibility=android.view.View.GONE
        fun locationText(value:String)=android.net.Uri.parse(value).let { (it.host ?: "")+android.net.Uri.decode(it.encodedPath.orEmpty()) }
        fun folderText()=if(folder.text.isNullOrBlank()) "尚未选择共享父目录，请浏览选择或在高级入口确认" else
            "共享位置："+locationText(folder.text.toString())+if(folder.text.toString()!=confirmedFolder)"（待确认）" else ""
        val folderLabel=label(folderText())
        folder.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { folderLabel.text=folderText() }
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        val confirmFolder=Button(context).apply { text="确认使用此共享目录";visibility=android.view.View.GONE }
        fields.addView(Button(context).apply { text="高级：手动输入完整共享 URL";setOnClickListener {
            folder.visibility=if(folder.visibility==android.view.View.VISIBLE)android.view.View.GONE else android.view.View.VISIBLE
            confirmFolder.visibility=if(!bound())folder.visibility else android.view.View.GONE
        } })
        fields.addView(confirmFolder)
        folder.isEnabled = !bound()
        val status = label("")
        fun draftAccount():WebDavAccount {
            val old=sync.accounts.account()
            return if(publisher) requireNotNull(old) { "请先配置 WebDAV 账号" } else {
                val server=url!!.text.toString();val username=user!!.text.toString()
                WebDavCredentials.candidate(old,server,username,password!!.text.toString(),WebDavCredentials.inheritedCertificate(old,server,username))
            }
        }
        confirmFolder.setOnClickListener {
            if(!bound())runCatching { SharedScheduleTransport().folder(draftAccount(),folder.text.toString()) }
                .onSuccess { folder.setText(it);confirmedFolder=it;folderLabel.text=folderText();status.text="已确认共享位置；仅更新草稿，后续操作时保存" }
                .onFailure { status.text=it.message ?: "共享目录无效" }
        }
        var publishButton: Button? = null
        var synchronizeButton: Button? = null
        var bindButton: Button? = null
        var inspectButton: Button? = null
        var conflictButton: Button? = null
        var recoveryButton:Button?=null
        fun refresh() {
            if(source.syncStore?.isV3()==true)runCatching { source.syncStore!!.v3EndpointFolder() }.onSuccess { persisted->confirmedFolder=persisted;if(folder.text.toString()!=persisted)folder.setText(persisted) }
            val time = sync.settings.lastSuccess
            status.text = sync.status + (if (time > 0) "\n上次同步检查（本机）：${Instant.ofEpochMilli(time)}" else "") +
                if (time > 0 && System.currentTimeMillis() - time > 24 * 60 * 60 * 1000L) "\n数据可能过期（超过 24 小时未验证）" else ""
            if (source.syncStore?.isV3() == true) {
                val control=source.syncStore?.v3Control();val b=control?.optJSONObject("binding")
                status.append(if(control?.optString("mode")=="RECOVERY_REQUIRED") "\n本地事务确认中断，请重开活动 Store；不会清库或重新绑定" else "\nSync v3 · ${b?.optString("datasetId") ?: "创建 / 加入恢复中"}\nDescriptor: ${b?.optString("descriptorHash") ?: "等待精确回读"}\n旧 Epoch 巡检未完成；未知设备及服务端停写情况未确认")
            }
            publishButton?.text = if (!bound()) "创建新的共享课表" else "立即同步"
            for(view in listOfNotNull(url,user,password))view.isEnabled=!bound() && !sync.busy
            confirmFolder.isEnabled=!bound() && !sync.busy
            folder.isEnabled = !sync.busy && !source.applyingSnapshot && !source.error && !bound()
            publishButton?.isEnabled = !sync.busy && source.ready
            bindButton?.isEnabled = !sync.busy && source.ready && !bound()
            bindButton?.visibility = if (bound()) android.view.View.GONE else android.view.View.VISIBLE
            inspectButton?.isEnabled = !sync.busy && source.ready
            if (bound()) previewDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility = android.view.View.GONE
            synchronizeButton?.isEnabled = !sync.busy && source.state.sync != null
            conflictButton?.isEnabled = !sync.busy
            recoveryButton?.isEnabled = !sync.busy && source.syncStore?.isV3()==true
        }
        refresh()
        fun save(): Boolean = runCatching {
            check(!sync.busy && !source.applyingSnapshot && !source.error) { "正在检查本地状态，请稍候" }
            if(!bound()) {
                check(confirmedFolder.isNotBlank() && confirmedFolder==folder.text.toString()) { "请先浏览选择共享父目录，或在高级入口点击确认使用此共享目录" }
                val account=draftAccount()
                val target=SharedScheduleTransport().folder(account,confirmedFolder)
                if(!publisher)sync.accounts.saveAccount(account)
                sync.settings.folder=target
            }
        }.onFailure { status.text = it.message ?: "设置无效，请检查地址与凭据。" }.isSuccess
        fun button(text: String, saveRequired: Boolean = true, action: () -> Unit): Button {
            return Button(context).apply { this.text = text; isAllCaps = false; minHeight = (48 * context.resources.displayMetrics.density).toInt()
                setOnClickListener { if (!saveRequired || save()) action() }
                fields.addView(this)
            }
        }
        button("安全更换 v3 访问端点 / 继续预检",false) {
            if(source.syncStore?.isV3()!=true)status.text="需要已绑定的活动 v3 数据集"
            else {
                val form=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12) }
                fun entry(hint:String,value:String="",secret:Boolean=false)=EditText(context).apply {
                    this.hint=hint;contentDescription=hint;setText(value);if(secret)inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;form.addView(this)
                }
                val currentFolder=runCatching { source.syncStore!!.v3EndpointFolder() }.getOrElse { status.text="无法读取活动 v3 持久共享目录；保留绑定，请重开 Store 后恢复，不猜测目录";return@button }
                val old=runCatching { source.syncStore!!.v3Account(sync.accounts) }.getOrElse { status.text="无法读取当前 v3 访问账号；保留绑定，请恢复凭据";return@button }
                form.addView(TextView(context).apply { text="只读预检不改绑定、不上传。预检在创建后 24 小时固定到期。确认后仅本 v3 课表使用独立加密账号，全局 WebDAV 导入账号保持原样。旧端点新变化、未知 Writer 和其他设备接收情况未能证明。" })
                val target=entry("新 WebDAV 服务地址",old?.baseUrl.orEmpty())
                val name=entry("新用户名",old?.username.orEmpty())
                val secret=entry("新密码（仅同一访问身份可留空）",secret=true)
                val dir=entry("新共享父目录完整 URL",currentFolder).apply { visibility=android.view.View.GONE }
                val targetLocation=TextView(context).apply { text="候选目录："+locationText(currentFolder);form.addView(this) }
                dir.addTextChangedListener(object:android.text.TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { targetLocation.text="候选目录："+locationText(s.toString()) }
                    override fun afterTextChanged(s:android.text.Editable?) {}
                })
                form.addView(Button(context).apply { text="高级：手动输入候选完整 URL";setOnClickListener { dir.visibility=if(dir.visibility==android.view.View.VISIBLE)android.view.View.GONE else android.view.View.VISIBLE } })
                form.addView(Button(context).apply { text="按新服务器地址保留当前共享目录路径";setOnClickListener {
                    runCatching { V3EndpointForm.retarget(currentFolder,target.text.toString()) }.onSuccess { dir.setText(it) }.onFailure { status.text="服务器地址无效；共享目录未改变" }
                } })
                val cert=entry("经独立核对的单证书 PEM；留空使用系统 CA",old?.certificatePem.orEmpty()).apply { minLines=3 }
                fun candidateAccount()=WebDavCredentials.candidate(old,target.text.toString(),name.text.toString(),secret.text.toString(),cert.text.toString().trim().ifBlank { null })
                val candidateError=TextView(context).also { form.addView(it) }
                var endpointOpen=true;var candidateFolder:AlertDialog?=null
                val candidateBrowse=Button(context).apply { text="浏览候选服务器文件夹";form.addView(this) }
                candidateBrowse.setOnClickListener {
                    candidateError.text=""
                    runCatching {
                        val candidate=candidateAccount()
                        val browse={ candidateFolder?.dismiss();candidateFolder=WebDavFolderDialog.show(context,candidate,initialFolder=dir.text.toString()) { if(endpointOpen)dir.setText(it) }; Unit }
                        if(candidate.certificatePem!=null) AlertDialog.Builder(context).setTitle("独立核对证书指纹")
                            .setMessage("SHA-256:\n${WebDavTls.fingerprint(candidate.certificatePem)}\n请核对可信渠道证书后浏览。")
                            .setNegativeButton("取消",null).setPositiveButton("已独立核对，浏览") { _,_->browse() }.show()
                        else browse()
                    }.onFailure { candidateError.text=it.message ?: "候选地址、凭据或证书无效；原绑定保留" }
                }
                val output=TextView(context).also { form.addView(it) };var job=runCatching { source.syncStore!!.latestV3Endpoint() }.getOrElse {
                    org.json.JSONObject().put("status","CLEANUP_BLOCKED").put("failure","候选私有材料无法安全清理；绑定和证据保留，请检查恢复状态")
                }
                var continueAction:Button?=null;var cancelAction:Button?=null;var confirmAction:Button?=null
                fun display(value:org.json.JSONObject) {
                    job=value
                    continueAction?.isEnabled=value.optString("status") in listOf("VALIDATING","READY")
                    cancelAction?.isEnabled=value.optString("status") in listOf("VALIDATING","READY","EXPIRED","STALE")
                    confirmAction?.isEnabled=value.optString("status")=="READY"
                    val text=when(value.optString("status")) { "READY"->"已知历史验证完成，等待确认";"COMMITTED"->"本机端点已切换；本地数据保留";"CANCELLED"->"预检已取消，原绑定保留";"EXPIRED","STALE"->"预检已到期或失效，请开始新预检";"CLEANUP_BLOCKED","OPERATION_FAILED"->"操作已安全停止；请关闭后重新查询或恢复 Store，原绑定和证据保留";else->"预检进度已保存，可继续" }
                    output.text="$text\n网络 / TLS / 认证：${if(value.optBoolean("networkAndAuthenticationVerified"))"已验证" else "未确认"}\nDescriptor 精确匹配：${if(value.optBoolean("descriptorExact"))"是" else "未确认"}\n已知历史回读：${if(value.optBoolean("knownHistoryComplete"))"完成" else "尚未完成"}\n${value.optJSONObject("binding")?.optString("datasetId").orEmpty()}\n旧端点新变化与其他设备接收未知。"+
                        if(value.has("failure"))"\n验证暂停：${value.optString("failure")}" else ""
                }
                fun action(label:String,call:()->Unit):Button=Button(context).apply { text=label;setOnClickListener { if(!sync.busy)call() };form.addView(this) }
                action("开始独立只读预检") {
                    candidateError.text=""
                    runCatching {
                        val candidate=candidateAccount()
                        candidate.certificatePem?.let { WebDavTls.fingerprint(it) }
                        val inspect={ sync.endpointPreview(candidate,dir.text.toString(),::display) }
                        if(candidate.certificatePem!=null) AlertDialog.Builder(context).setTitle("独立核对证书指纹")
                            .setMessage("SHA-256:\n${WebDavTls.fingerprint(candidate.certificatePem)}\n请确认来自可信渠道；未知证书不能作为身份验证。")
                            .setNegativeButton("取消",null).setPositiveButton("已独立核对，预检") { _,_->inspect() }.show()
                        else inspect()
                    }.onFailure { output.text=it.message ?: "候选地址、密码或证书无效；原绑定保留" }
                }
                continueAction=action("继续上次预检") { job?.optString("token")?.let { sync.endpointContinue(it,::display) } }
                cancelAction=action("取消尚未提交的预检") { job?.optString("token")?.let { sync.endpointCancel(it,::display) } }
                confirmAction=action("确认更换同一数据集访问端点") {
                    val p=job
                    if(p?.optString("status")!="READY")output.text="请先继续预检，直到已知历史验证完成"
                    else AlertDialog.Builder(context).setTitle("确认本机端点切换")
                        .setMessage("数据集：${p.getJSONObject("binding").getString("datasetId")}\nDescriptor SHA-256：${p.getJSONObject("binding").getString("descriptorHash")}\n\n保留 Writer、本地编辑、冲突与回执。仅 v3 使用独立账号，不改变全局导入账号。最终确认会复查身份和前沿；新历史或本地编辑会使预览过期。")
                        .setNegativeButton("取消",null).setPositiveButton("确认切换") { _,_->sync.endpointConfirm(p.getString("token"),::display) }.show()
                }
                continueAction.isEnabled=false;cancelAction.isEnabled=false;confirmAction.isEnabled=false;job?.let(::display)
                AlertDialog.Builder(context).setTitle("安全端点更换与恢复").setView(ScrollView(context).apply { addView(form) }).setNegativeButton("关闭（保留预检）",null).create().also { it.setOnDismissListener { endpointOpen=false;candidateFolder?.dismiss() };it.show() }
            }
        }
        button("HTTPS 自签名证书（仅此连接）",false) {
            val old=sync.accounts.account()
            if(source.syncStore?.isV3()==true) { status.text="活动 v3 请使用安全端点更换入口";return@button }
            if(old==null) status.text="请先保存 HTTPS WebDAV 帐号，再配置证书"
            else {
                val pem=EditText(context).apply { hint="从可信渠道取得的单个 PEM 公钥证书";contentDescription=hint;setText(old.certificatePem.orEmpty());minLines=4 }
                AlertDialog.Builder(context).setTitle("单独信任服务器证书")
                    .setMessage("仅适用于此帐号的 HTTPS 连接。请从 NAS 管理员或可信 SSH 渠道取得证书并核对指纹；不会自动信任网络返回的陌生证书。证书变化会停止同步。活动 v3 不支持隐式更换信任身份。")
                    .setView(ScrollView(context).apply { addView(pem) }).setNegativeButton("取消",null)
                    .setNeutralButton("恢复系统 CA") { _,_->
                        AlertDialog.Builder(context).setTitle("确认此目标的密码复用").setMessage("服务器：${locationText(old.baseUrl)}\n用户名：${old.username}\n将已保存密码用于此目标的系统 CA 信任连接？")
                            .setNegativeButton("取消",null).setPositiveButton("使用系统 CA 并复用密码") { _,_->sync.accounts.saveAccount(WebDavAccount(old.baseUrl,old.username,old.password));status.text="已恢复此连接的系统 CA 校验" }.show()
                    }
                    .setPositiveButton("核对指纹") { _,_->runCatching {
                        require(WebDavClient().url(old.baseUrl).isHttps) { "请先保存 HTTPS 地址" }
                        val value=pem.text.toString().trim();val fingerprint=WebDavTls.fingerprint(value)
                        AlertDialog.Builder(context).setTitle("确认精确证书信任").setMessage("SHA-256:\n$fingerprint\n\n服务器：${locationText(old.baseUrl)}\n用户名：${old.username}\n仅匹配此公钥证书，仍验证有效期和 TLS 握手。将已保存密码用于此目标的新证书信任连接；不会安装全局 CA。")
                            .setNegativeButton("取消",null).setPositiveButton("信任此证书并复用密码") { _,_->
                                sync.accounts.saveAccount(WebDavAccount(old.baseUrl,old.username,old.password,value));status.text="此连接已保存精确证书信任；可检查并同步"
                            }.show()
                    }.onFailure { status.text="证书无效、已过期或地址不是 HTTPS；原设置保留" } }.show()
            }
        }
        button("创建 Sync v3 数据集（保留本地课程）") {
            if (bound()) status.text = "已有活动绑定；请使用同步或显式迁移入口"
            else AlertDialog.Builder(context).setTitle("创建独立 v3 数据集")
                .setMessage("共享父目录：${locationText(sync.settings.folder)}\n\n使用当前完整本地课表创建。Descriptor 与 Genesis 经远端精确回读后才确认发布；失败后继续恢复同一候选。旧 Epoch 巡检与其他设备接收是不同事实。")
                .setNegativeButton("取消", null).setPositiveButton("创建") { _, _ -> sync.createV3() }.show()
        }
        button("v2→v3 安全迁移预览 / 继续恢复") {
            if (source.syncStore?.isV3() == true) sync.sync()
            else sync.previewV3Migration { preview ->
                if (active) {
                    val v = preview.merged
                    AlertDialog.Builder(context).setTitle("v2→v3 迁移预览")
                        .setMessage("源 ${preview.frame.baseline?.datasetId}\n已验证 ${preview.hashes.size} 个提交，${preview.tips.size} 个前沿。\n课程 ${v.entries.size} · 事件 ${v.events.size} · 来源 ${v.sources.size} · 导入实例 ${v.imported.size} · 覆盖 ${v.classOverrides.size} · 日期调整 ${v.dayAdjustments.size} · 学期 ${v.profiles.size} · 自定义身份 ${v.customProfileKeys.size}\n业务 SHA-256: ${SyncV3Codec.businessHash(v)}\n\n保留未发布本地修改和原 v2 基线，不新增 v2 提交。确认后本机源 Writer 持久暂停，重新验证源，精确回读 v3 Genesis 并核验等价后才激活。\n\n原 v2 历史仍保留在远端；这不是独立回读验证的备份。请先停止其他已知旧客户端。普通 WebDAV 无法发现未知设备或禁止它们写入，旧目录未被服务端禁止写入。后续设备应加入此目标，不要再次迁移创建另一个目标。")
                        .setNegativeButton("取消", null).setPositiveButton("已停止已知旧客户端，迁移") { _, _ -> if (active) sync.migrateV3(preview) }.show()
                }
            }
        }
        button("检查迁移后的旧 v2 源", false) { sync.auditV3MigrationSource() }
        recoveryButton=button("重开活动 Store（保留同一数据库和候选）", false) { sync.reopenV3Store() }
        button("后续 v2 设备加入既有 v3 目标（只读预检）") {
            if(source.syncStore?.isV3()==true) status.text="本机已有活动 v3 绑定，禁止隐式换库"
            else sync.discoverV3 { candidates ->
                if(active) AlertDialog.Builder(context).setTitle("选择首台设备迁移出的目标")
                    .setItems(candidates.map { "${it.descriptor.datasetId}\n${it.hash}" }.toTypedArray()) { _,index ->
                        sync.previewExistingV3(candidates[index].bytes) { preview ->
                            if(active) AlertDialog.Builder(context).setTitle("加入已迁移目标")
                                .setMessage("目标 ${preview.target.identity.datasetId}\n源与目标八类 RAW 已逐字段核验等价。确认时重新检查源及目标，再原子暂停本机 v2 Writer 并加入，不创建新的 Descriptor 或 Genesis。保留原 v2 基线、本地快照和历史证据。\n\n请停止其他已知旧客户端。未知设备无法自动发现；旧目录未由服务端禁止写入，远端保留历史不是独立备份。")
                                .setNegativeButton("取消",null).setPositiveButton("已停止旧客户端，加入") { _,_ -> sync.joinMigratedV3(preview) }.show()
                        }
                    }.setNegativeButton("关闭",null).show()
            }
        }
        button("取消尚未创建目标的 v3 迁移", false) {
            AlertDialog.Builder(context).setTitle("取消迁移准备")
                .setMessage("仅在尚未产生 v3 Descriptor/Genesis 候选时允许恢复原 v2 修改与基线。目标已有候选、已发布或已接受 v3 编辑时会拒绝；不会删除两端历史。")
                .setNegativeButton("保留", null).setPositiveButton("检查并取消") { _, _ -> sync.cancelUnpublishedV3Migration() }.show()
        }
        fun verifyJoin(selected:SharedScheduleController.V3Candidate) {
            sync.previewJoinV3(selected.bytes) { preview ->
                if(active) {
                    if(preview==null) AlertDialog.Builder(context).setTitle("只读预检尚未完成")
                        .setMessage("进度已持久保存，当前业务库尚未切换。可继续同一目标的有界预检；不会创建新数据集。")
                        .setNegativeButton("稍后",null).setPositiveButton("继续预检") { _,_->verifyJoin(selected) }.show()
                    else {
                        val v=preview.target.value.state!!
                        AlertDialog.Builder(context).setTitle("确认加入 ${selected.descriptor.datasetId}")
                            .setMessage("已验证 Descriptor、观察到的 DAG 和前沿快照。\n课程 ${v.entries.size} · 事件 ${v.events.size} · 来源 ${v.sources.size} · 实例 ${v.imported.size} · 覆盖 ${v.classOverrides.size} · 日期调整 ${v.dayAdjustments.size} · 学期 ${v.profiles.size} · 自定义 ${v.customProfileKeys.size}\nRAW SHA-256: ${SyncV3Codec.hash(SyncV3Codec.stateBytes(v))}\n\n确认时重新检查目标，再原子加入。本地非空、已有绑定或恢复状态会阻止覆盖；普通 WebDAV 不能证明未知客户端已停写。")
                            .setNegativeButton("取消",null).setPositiveButton("加入并继续验证") { _,_->if(active && !bound())sync.joinV3(preview) }.show()
                    }
                }
            }
        }
        button("发现并加入 Sync v3 数据集") {
            if (bound()) status.text = "已有活动绑定，禁止隐式换库"
            else sync.discoverV3 { candidates ->
                if (active) {
                    if (candidates.isEmpty()) status.text = "未发现可验证的 v3 Descriptor；尚未绑定"
                    else AlertDialog.Builder(context).setTitle("选择 v3 数据集（Descriptor 已验证）")
                        .setItems(candidates.map { "${it.descriptor.datasetId}\nDescriptor SHA-256: ${it.hash}" }.toTypedArray()) { _, index ->
                            val selected = candidates[index]
                            verifyJoin(selected)
                        }.show()
                }
            }
        }
        fun inspectAndConfirm() {
            sync.inspect { remote ->
                if (!active) return@inspect
                val canBind = !bound()
                val v = remote.snapshot.state
                val details = buildString {
                    append("Dataset: ${remote.head.datasetId}\nCommit: ${remote.head.revision}\n协议：Append-only v2")
                    append("\n本地课程 ${v.entries.size} · 日历事件 ${v.events.size}\n来源 ${v.sources.size} · 导入实例 ${v.imported.size}")
                    append("\n日期调整 ${v.dayAdjustments.size} · 课程编辑 ${v.classOverrides.size}")
                    append("\n远端历史：${remote.witness.size} 个已验证提交 · 单一分支 · 祖先闭包完整")
                    append("\n暂不可验证文件 ${remote.unverifiable.size}（不计为完成同步）")
                    append("\n\n导入来源：")
                    v.sources.forEach { append("\n${it.displayName.take(160)} · ${it.filename.take(160)} · ${it.kind.take(80)} · ${it.occurrenceCount} 项") }
                    append("\n\n学期配置 ${v.profiles.size}（自定义 ${v.customProfileKeys.size}）：")
                    v.profiles.toSortedMap().forEach { (key, profile) ->
                        append("\n${key.take(160)} · ${profile.timezone} · 首周 ${profile.week1Monday} · ${profile.phases.size} 个时段阶段")
                    }
                    append(if (canBind) "\n\n确认前将重新检查完整版本图。绑定仅接收已有课程，不创建远端根提交。首次绑定要求空课表；已有本地数据请先保留，已有 v1 绑定请使用迁移入口。"
                        else "\n\n本机已绑定；本次仅检查远端，不下载覆盖本地课程、不重新绑定，也不发布远端提交。")
                }
                val content = TextView(context).apply { text = details; textSize = 16f; setPadding(24, 12, 24, 12); setTextIsSelectable(true) }
                val preview = AlertDialog.Builder(context).setTitle("已验证的远端课表")
                    .setView(ScrollView(context).apply { addView(content) })
                    .setNegativeButton(if (canBind) "取消" else "关闭预览", null)
                if (canBind) preview.setPositiveButton("确认下载并绑定") { _, _ ->
                    if (active && !bound()) sync.bind(remote)
                }
                previewDialog = preview.show()
            }
        }
        val browseButton=button("浏览共享目录", false) {
            if (bound()) { status.text = "已绑定，请使用安全换库入口" }
            else runCatching {
                val account=draftAccount()
                folderDialog?.dismiss()
                folderDialog=WebDavFolderDialog.show(context, account, initialFolder=folder.text.toString()) { if (active && !bound()) { folder.setText(it);confirmedFolder=it;folderLabel.text=folderText();status.text="已确认共享位置；仅更新草稿，后续操作时保存" } }
            }.onFailure { status.text = it.message ?: "请检查服务器地址与凭据" }
        }
        fields.removeView(browseButton);fields.addView(browseButton,fields.indexOfChild(folderLabel)+1)
        if (publisher) {
            publishButton = button("创建新的共享课表") { sync.publish() }
            bindButton = button("绑定已有共享课表（预览）") { inspectAndConfirm() }
            button("诊断 v2（在独立随机测试目录写入合成提交）") { sync.diagnose() }
        } else {
            inspectButton = button("测试连接 / 检查远端快照") { inspectAndConfirm() }
            synchronizeButton = button("立即同步") { sync.sync() }
        }
        if (!publisher) {
            button("创建新的 v2 数据集（使用本地课程）") { sync.publish() }
            button("诊断 v2（独立随机测试目录）") { sync.diagnose() }
        }
        button("将已有 v1 绑定切换到 v2") {
            AlertDialog.Builder(context).setTitle("切换同步协议")
                .setMessage("必须先停止所有设备上的 v1 自动同步，之后所有编辑端均使用 v2。旧客户端无法看到 v2 修改，禁止继续写入旧目录。迁移仅读取旧远端文件，保留课程身份、账号及历史。不能证明共同基线或存在待确认上传时会停止。确认所有旧客户端已停止？")
                .setNegativeButton("取消", null).setPositiveButton("旧客户端已停止，切换") { _, _ -> sync.migrate() }.show()
        }
        button("更换共享课表数据集 / 恢复换库", false) {
            fun showJournal(j: ScheduleDatasetMigration) {
                val v = j.target.snapshot.state
                val summary = "阶段：${j.phase}\n旧库：${j.source.head.datasetId}\n${j.oldFolder}\n新库：${j.target.head.datasetId}\n${j.newFolder}\nWriter：${j.writerId}\n\n已逐字段验证前后等价：课程 ${v.entries.size}、事件 ${v.events.size}、来源 ${v.sources.size}、实例 ${v.imported.size}、覆盖 ${v.classOverrides.size}、日期调整 ${v.dayAdjustments.size}、学期 ${v.profiles.size}（自定义 ${v.customProfileKeys.size}）。\n旧图 ${j.source.witness.size} 个提交 → 新图 1 个根。原课程与恢复证据保存在本机，旧库未删除。\n\n原自动同步与重试已暂停。请停止全部独立客户端写入；本机无法证明全部 Writer 已停写。确认将再次完整读取两库，离线或版本变化会拒绝。"
                val content = TextView(context).apply { text = summary; textSize = 16f; setPadding(24, 12, 24, 12); setTextIsSelectable(true) }
                val dialog = AlertDialog.Builder(context).setTitle("安全换库 · 本机验收")
                    .setView(ScrollView(context).apply { addView(content) }).setNegativeButton("保持暂停", null)
                if (j.phase == "prepared") {
                    dialog.setNeutralButton("取消准备") { _, _ -> sync.finishDatasetSwitch(j.id, true) }
                    dialog.setPositiveButton("确认切换（继续停写）") { _, _ -> sync.commitDatasetSwitch(j.id) }
                } else {
                    dialog.setNeutralButton("恢复目标目录设置") { _, _ -> sync.commitDatasetSwitch(j.id) }
                    dialog.setPositiveButton("管理员已允许新库写入，完成验收") { _, _ -> sync.finishDatasetSwitch(j.id) }
                }
                dialog.show()
            }
            sync.inspectDatasetSwitch { journal, inspection, lastResult ->
                if (!active) return@inspectDatasetSwitch
                if (journal != null) showJournal(journal) else if (inspection != null) {
                    AlertDialog.Builder(context).setTitle("换库预检中断 · 同步已暂停")
                        .setMessage("旧目录：${inspection[0]}\n新目录：${inspection[1]}\n目标：${inspection[2]}\n未切换基线。恢复网络后重试，或取消预检；原数据和已观察异常保留。")
                        .setNegativeButton("保持暂停", null).setNeutralButton("取消预检") { _, _ -> sync.cancelDatasetInspection() }
                        .setPositiveButton("重新验证") { _, _ -> sync.prepareDatasetSwitch(inspection[1], inspection[2]) { if (active) showJournal(it) } }.show()
                } else {
                    val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
                    if (lastResult != null) {
                        val r = org.json.JSONObject(lastResult)
                        form.addView(TextView(context).apply { text = "上次本机结果：${if (r.getString("phase") == "cancelled") "已取消准备" else "已验证完成换库"}\n检查时间：${r.getString("checkedAt")}\n旧库：${r.getString("sourceDatasetId")}（${r.getInt("sourceCount")} 个提交）\n新库：${r.getString("targetDatasetId")}\nWriter：${r.getString("writerId")}\n新根：${r.getString("targetRoot")}\nSHA-256：${r.getString("targetSha256")}\n原本地数据和恢复清单仍保留；可向 FPK 管理员登记此结果。"; textSize = 16f; setTextIsSelectable(true) })
                    }
                    val target = EditText(context).apply { hint = "新共享目录完整 URL（使用当前 WebDAV 账号）"; setSingleLine(); form.addView(this) }
                    if (!publisher) form.addView(Button(context).apply {
                        text = "浏览新共享目录"
                        setOnClickListener { sync.accounts.account()?.let { account ->
                            WebDavFolderDialog.show(context, account) { if (active) target.setText(it) }
                        } }
                    })
                    val dataset = EditText(context).apply { hint = "FPK 显示的目标 datasetId"; setSingleLine(); form.addView(this) }
                    form.addView(TextView(context).apply { text = "当前旧库目录：${sync.settings.folder}\n先在 FPK 停写并验证完整归档及候选。准备成功即持久暂停同步，关闭界面或重启后可从此入口继续。" })
                    AlertDialog.Builder(context).setTitle("更换共享课表数据集")
                        .setView(ScrollView(context).apply { addView(form) }).setNegativeButton("取消", null)
                        .setPositiveButton("验证并保存迁移预览") { _, _ -> sync.prepareDatasetSwitch(target.text.toString(), dataset.text.toString()) { if (active) showJournal(it) } }.show()
                }
            }
        }
        conflictButton = button("查看 / 处理同步冲突", false) {
            sync.loadConflicts { items ->
                if (items.isEmpty()) {
                    AlertDialog.Builder(context).setMessage("当前没有待处理冲突。可立即同步检查远端。")
                        .setPositiveButton("关闭", null).show()
                } else {
                    val explanation = "\n\n多个远端分支时，远端栏展示待确认方案；选择后才会发布。选择仅处理冲突及必要关联记录，独立修改仍自动合并。保留本地采用本机当前值或删除；采用远端采用已展示的方案。处理后会再次检查远端。"
                    val builder = AlertDialog.Builder(context).setTitle("同步冲突 · ${items.size} 项")
                        .setPositiveButton(if (items.size > 4) "全部保留本地" else "保留本地") { _, _ -> sync.resolve(ScheduleMerge.Choice.LOCAL) }
                        .setNeutralButton(if (items.size > 4) "全部采用远端" else "采用远端") { _, _ -> sync.resolve(ScheduleMerge.Choice.REMOTE) }
                        .setNegativeButton("暂不处理", null)
                    fun summary(item: ScheduleMerge.Conflict) = item.description.take(12000) +
                        if (item.description.length > 12000) "\n摘要较长，完整冲突输入仍已保存；可暂不处理。" else ""
                    if (items.size <= 4) builder.setMessage(items.joinToString("\n\n", transform = ::summary) + explanation)
                    else {
                        val choices = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
                        choices.addView(TextView(context).apply { text = "选择一项查看；下方操作将处理全部 ${items.size} 项。"; textSize = 16f })
                        val list = ListView(context).apply {
                            adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1,
                                items.mapIndexed { index, item -> "${index + 1}. ${item.description.lineSequence().first().take(120)}" })
                            setOnItemClickListener { _, _, index, _ ->
                                AlertDialog.Builder(context).setTitle("冲突 ${index + 1} / ${items.size}")
                                    .setMessage(summary(items[index]) + explanation).setPositiveButton("返回列表", null).show()
                            }
                        }
                        choices.addView(list, LinearLayout.LayoutParams(-1, (240 * context.resources.displayMetrics.density).toInt()))
                        builder.setView(choices)
                    }
                    builder.show()
                }
            }
        }
        refresh()
        val previous = sync.changed
        val listener: () -> Unit = { previous?.invoke(); refresh() }
        sync.changed = listener
        val dialog = AlertDialog.Builder(context).setTitle(if (publisher) "共享课表 · WebDAV" else "共享课表 · WebDAV")
            .setView(ScrollView(context).apply { addView(fields) }).setNegativeButton("关闭", null).create()
        dialog.setOnDismissListener { active = false; folderDialog?.dismiss(); previewDialog?.dismiss(); if (sync.changed === listener) sync.changed = previous; password?.text?.clear() }
        dialog.show()
        return dialog
    }
}
