import {installCourseManagement} from './course-management.js';
import { scheduleStatus } from './sync-status.js';
import { notifyUser, confirmAction, requestText } from './feedback.js';
const $ = (id) => document.getElementById(id),
  base = "/app/dormpanel-schedule-manager";
const now = () =>
  new Intl.DateTimeFormat("en-CA", {
    timeZone: $("zone").value,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
const add = (d, n) => {
  const t = new Date(d + "T12:00:00Z");
  t.setUTCDate(t.getUTCDate() + n);
  return t.toISOString().slice(0, 10);
};
function el(tag, text, cls) {
  const e = document.createElement(tag);
  if (text != null) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}
function paragraphs(id, rows) {
  $(id).replaceChildren(...rows.map((s) => el("p", s)));
}
const disabledBeforeJob=new WeakMap();
function lockControls(panel) {
  const buttons=[...$(panel).querySelectorAll('button')];
  for(const b of buttons){if(!disabledBeforeJob.has(b))disabledBeforeJob.set(b,b.disabled);b.disabled=true;}
  return buttons;
}
function unlockControls(buttons) {
  for(const b of buttons)if(disabledBeforeJob.has(b)){b.disabled=disabledBeforeJob.get(b);disabledBeforeJob.delete(b);}
}
let historyJobId=0, backupJobId=0;
let activeProtocol = 'v2', viewEpoch = 0, currentPage = 'dashboard';
let historyPreview = null, historyScope = null, historyEligible = false, historyBusy = false, backupBusy = false;
let historyReadEpoch = 0, historyOutputEpoch = 0;
class StaleResponse extends Error {}
// Stops the old view's continuation only after its write result has been retained and reconciled.
class DetachedWriteResult extends Error {}
const obsoleteView=error=>error instanceof StaleResponse || error instanceof DetachedWriteResult;
let editingContext=null, localEditorContext=null, currentEditorForm=null;
const localActions=['newCourse','newEvent','managerCourse','managerEvent','newProfile','adjustDate','importFile'];
function isolateEditing() {
  editingContext=null;local=null;
  $('records').replaceChildren();$('records').inert=true;$('week').inert=true;
  for(const id of localActions)$(id).disabled=true;
  for(const c of $('week').querySelectorAll('[role=button]')){c.tabIndex=-1;c.setAttribute('aria-disabled','true');}
  if(localEditorContext && $('editor').open){
    currentEditorForm?.invalidate('草稿已保留，但原提交资格已失效。请先复制需要的内容，关闭窗口后重新读取当前课程表并核对。');
    $('editingState').hidden=false;$('editingStatus').textContent='编辑资格已失效，草稿保留在原表单。关闭后可重新读取并核对。';
  }
}
function requireEditing(context=editingContext) {
  if(!context || context!==editingContext || context.workspaceId!==workspaceId || context.version!==version || context.epoch!==loadEpoch)
    throw Error('编辑数据尚未就绪或已失效，请重新读取当前课程表');
  return context;
}
function businessDialog(...args) {
  let context;
  try {context=requireEditing();}
  catch(error){isolateEditing();$('editingState').hidden=false;$('editingStatus').textContent=error.message;return;}
  dialog(...args);localEditorContext=context;
}

function invalidateHistory() {
  historyPreview = null; historyEligible = false; ++historyReadEpoch; ++historyOutputEpoch;
  $('historyConfirm').disabled = true; $('historyPreview').disabled = true;
  $('historyConfirm').parentElement.hidden=true;
}
function navigate(page) {
  ++viewEpoch; ++historyJobId; ++backupJobId; historyBusy=false;backupBusy=false;
  unlockControls([...$('history').querySelectorAll('button'),...$('backups').querySelectorAll('button')]);
  invalidateHistory();invalidateBackup();
  currentPage = page;
  for (const id of ['dashboard','dataSources','history','backups']) $(id).hidden = id !== page;
  $('dashboardActions').hidden = page !== 'dashboard';
  $('pageTitle').textContent = {dashboard:'课程表',dataSources:'课程表与共享',history:'历史版本',backups:'备份与维护'}[page];
  for (const [id,target] of [['schedulePage','dashboard'],['sourcesPage','dataSources'],['historyPage','history'],['backupsPage','backups']]) {
    $(id).setAttribute('aria-current', target === page ? 'page' : 'false');
  }
  window.scrollTo(0,0);
}
const tableLabels = {entries:'本地课程',events:'日历事件',sources:'课程来源',imported:'导入原始实例',classOverrides:'全周与指定周课程覆盖',dayAdjustments:'停课与补课',profiles:'学期与作息',customProfileKeys:'自定义身份信息'};
const capacity = n => n == null ? '未测量' : `${new Intl.NumberFormat('zh-CN',{maximumFractionDigits:2}).format(n / (n>=1048576?1048576:n>=1024?1024:1))} ${n>=1048576?'MiB':n>=1024?'KiB':'B'}（${n} 字节）`;

let workspaceId, catalogVersion, management, switching = false, loadEpoch = 0, managementEpoch = 0;
let loadedVersion;
let token,
  version,
  local,
  profiles = [];
async function load() {
  const epoch = ++loadEpoch;
  isolateEditing(); $('editingStatus').textContent='正在读取当前课程表的编辑数据…';$('editingState').hidden=false;
  try {
    const r = await fetch(
      `${base}/api/schedule?${workspaceId ? "workspaceId=" + encodeURIComponent(workspaceId) + "&" : ""}date=${encodeURIComponent($("date").value)}&zone=${encodeURIComponent($("zone").value)}&origin=${encodeURIComponent(location.origin)}`,
    );
    const s = await r.json();
    if (!r.ok) throw Error(s.error);
    if (epoch !== loadEpoch) return;
    if (workspaceId !== s.workspaceId || loadedVersion !== s.version) { invalidateHistory(); invalidateBackup(); }
    workspaceId = s.workspaceId;
    activeProtocol = s.protocol ?? 'v2';
    catalogVersion = s.catalogVersion;
    token = s.csrf;
    version = s.version; loadedVersion=s.version;
    $("status").textContent = scheduleStatus(s);
    $('managerStatus').textContent = $('status').textContent;
    $('currentContext').textContent = `${s.workspaceName ?? management?.workspaces.find(w=>w.id===workspaceId)?.name ?? '当前课程表'} · ${scheduleStatus(s)}`;
    $('welcome').dataset.unbound = String(s.status === 'unbound');
    $('welcome').hidden = s.status !== 'unbound' || !$('manager').hidden;
    $("status").dataset.error = ["incomplete", "conflict", "CONFLICT", "RECOVERY_REQUIRED", "error"].includes(
      s.status,
    );
    $("range").textContent = `${s.first} — ${add(s.first, 6)}`;
    profiles = s.profiles;
    const selectedTerm = $("term").value;
    $("term").replaceChildren(
      ...profiles.map((p) => {
        const option = el("option", p.termKey);
        option.value = p.termKey;
        return option;
      }),
    );
    if (profiles.some((p) => p.termKey === selectedTerm))
      $("term").value = selectedTerm;
    $("jump").disabled = !profiles.length;
    const active = profiles.find((p) => p.termKey === $("term").value);
    if (active)
      $("teachingWeek").value =
        Math.floor(
          (Date.parse(s.first) - Date.parse(active.week1Monday)) / 604800000,
        ) + 1;
    $("week").replaceChildren(
      ...Array.from({ length: 7 }, (_, i) => {
        const d = add(s.first, i),
          col = el("section", null, "day");
        col.append(
          el("h2", ["周一", "周二", "周三", "周四", "周五", "周六", "周日"][i]),
          el("small", d),
        );
        const dateOf = (ms) =>
          new Intl.DateTimeFormat("en-CA", {
            timeZone: s.zone,
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
          }).format(new Date(ms));
        const courses = s.courses.filter(
          (c) => dateOf(c.start) <= d && dateOf(c.end - 1) >= d,
        );
        for (const c of courses) {
          const card = el("article", null, "course"),
            fmt = (ms) =>
              new Intl.DateTimeFormat("zh-CN", {
                timeZone: s.zone,
                hour: "2-digit",
                minute: "2-digit",
                hourCycle: "h23",
              }).format(new Date(ms));
          if(Number.isInteger(c.sharedRgb))card.style.borderLeft='6px solid #'+c.sharedRgb.toString(16).padStart(6,'0');
          card.tabIndex = 0;
          card.setAttribute("role", "button");
          card.setAttribute("aria-label", "编辑 " + c.title);
          const openCourse=()=>{if(editingContext?.epoch===epoch && editingContext.workspaceId===s.workspaceId && editingContext.version===s.version && editingContext.version===version)editOccurrence(c);};
          card.onclick = openCourse;
          card.onkeydown = (e) => {
            if (e.key === "Enter") openCourse();
          };
          card.append(
            el("small", `${fmt(c.start)}–${fmt(c.end)}`),
            el("strong", c.title),
            el("span", c.location || "未设置教室"),
          );
          if (dateOf(c.start) !== dateOf(c.end - 1))
            card.append(
              el("small", `跨日 ${dateOf(c.start)} → ${dateOf(c.end - 1)}`),
            );
          for (const t of [
            c.periodLabel,
            c.teacher,
            c.note,
            c.description,
            c.copiedFrom && `调自 ${c.copiedFrom}`,
            c.allWeeksEdited && "已应用全周编辑",
            c.thisWeekEdited && "已应用指定周编辑",
          ])
            if (t) card.append(el("small", t));
          col.append(card);
        }
        if (!courses.length) col.append(el("p", "—"));
        return col;
      }),
    );
    paragraphs("details", [
      `数据集：${s.datasetId ?? "未绑定"}`,
      `Writer：${s.writerId}`,
      ...(s.published ? [`已发布提交：${(s.published.path ?? s.published.commitId)}`] : []),
      `历史：${s.historyCount ?? 'v3 分页查看'} · 分支：${s.tips.length} · 异常：${s.deferred.length}`,
      `检查时间：${s.checkedAt ? new Intl.DateTimeFormat("zh-CN", { timeZone: s.zone, dateStyle: "short", timeStyle: "medium" }).format(new Date(s.checkedAt)) : "尚未检查"}`,
      ...(s.error ? [`错误：${s.error}`] : []),
      ...s.tips.map((id) => `tip ${id}`),
    ]);
    paragraphs(
      "profiles",
      s.profiles.length
        ? s.profiles.map((p) => {
            const week =
              Math.floor(
                (Date.parse(s.first) - Date.parse(p.week1Monday)) / 604800000,
              ) + 1;
            return (
              `${p.termKey} · 第 ${week} 教学周\n${p.timezone} · 第一周 ${p.week1Monday}\n` +
              p.phases
                .map(
                  (ph) =>
                    `${ph.effectiveFrom}：${ph.periods.map((t) => `${t.periodNumber}节 ${t.start}–${t.end}`).join(" / ")}`,
                )
                .join("\n")
            );
          })
        : ["暂无可显示配置"],
    );
    paragraphs(
      "sources",
      s.sources.map(
        (r) =>
          `${r.displayName} · ${r.filename}\n${r.occurrenceCount} 个实例 · ${r.kind}`,
      ),
    );
    paragraphs(
      "events",
      s.events.map((e) => `${e.title} · ${e.note}`),
    );
    try {
      const state = await get('/api/state',{pageSensitive:false});
      if(epoch!==loadEpoch) return;
      if(state.workspaceId!==workspaceId || state.workspaceId!==s.workspaceId || !Number.isSafeInteger(state.version) || state.version!==s.version || state.version!==version)
        throw Error('课程表身份或版本已变化，请重新读取');
      local=state.local;loadedVersion=state.version;
      editingContext=Object.freeze({workspaceId,version,epoch});
      renderRecords();$('records').inert=false;$('week').inert=false;
      for(const id of localActions)$(id).disabled=false;
      for(const c of $('week').querySelectorAll('[role=button]')){c.tabIndex=0;c.setAttribute('aria-disabled','false');}
      $('editingState').hidden=true;
    } catch (e) {
      if(epoch!==loadEpoch || e instanceof StaleResponse)return;
      isolateEditing();$('editingStatus').textContent='周课表已读取；编辑数据暂不可用：'+e.message+'。可安全重试。';
      $('managerStatus').textContent=$('editingStatus').textContent;
    }
    await loadManagement(epoch).catch(() => {});
  } catch (e) {
    if (epoch !== loadEpoch || e instanceof StaleResponse) return;
    $("status").textContent = e.message;
    $("status").dataset.error = true;
    $("managementStatus").textContent = '页面初始化失败：' + e.message + '。请点击“刷新状态”重试。';
  }
}
$("date").value = now();
$("date").onchange = load;
$("zone").onchange = load;
$("jump").onclick = () => {
  const p = profiles.find((p) => p.termKey === $("term").value),
    n = Number($("teachingWeek").value);
  if (p && Number.isInteger(n) && n >= 1 && n <= 1000) {
    $("date").value = add(p.week1Monday, (n - 1) * 7);
    load();
  }
};
$("prev").onclick = () => {
  $("date").value = add($("date").value, -7);
  load();
};
$("next").onclick = () => {
  $("date").value = add($("date").value, 7);
  load();
};
$("today").onclick = () => {
  $("date").value = now();
  load();
};
$("refresh").onclick = () => runSync({});

async function get(path,{pageSensitive=true}={}) {
  const scope = workspaceId, pageTicket = viewEpoch;
  const r = await fetch(base + path + (path.includes("?") ? "&" : "?") + "workspaceId=" + encodeURIComponent(scope));
  const v = await r.json();
  if (scope !== workspaceId || pageSensitive && pageTicket !== viewEpoch) throw new StaleResponse('页面或课程表已切换');
  if (!r.ok) throw Error(v.error);
  return v;
}
let activeWrite=null, unresolvedWrite=null, lastWrite=null, pendingCreatedWorkspace=null;
function renderCreationStatus() {
  const host=$('creationStatus'),created=pendingCreatedWorkspace;host.hidden=!created;if(!created)return;
  host.replaceChildren(el('strong','课程表已建立：'+created.name),el('p',created.active?'已核对为当前活动课程表。共享数据的创建或加入仍需单独确认。':created.message??'已保留课程表及连接，尚未确认已切换。可使用下方入口切换，不会再次创建。'));
  if(!created.active){const b=button('切换到已建立的课程表',()=>switchWorkspace(created.id));b.disabled=!!(activeWrite||unresolvedWrite);host.append(b);}
}

function postSemantics(path,data) {
  // Temporary import tokens never acquire the page-wide business-write fence.
  if(path==='/api/courses/preview')return 'read';
  if(path==='/api/import/preview')return 'temporary-preview';
  return ['/api/backups/list','/api/backups/verify','/api/backups/cancel','/api/history/preview','/api/history/cleanup-preview','/api/connections/test','/api/connections/browse'].includes(path)
    || path==='/api/sync' && data.discover
    || path==='/api/migration' && ['v3.preview','v3.discover'].includes(data.action)
    || path==='/api/endpoint' && data.action==='status' ? 'read' : 'write';
}
function writeLabel(path,data) {
  return ({'/api/edit':'保存课程编辑','/api/sync':'检查并同步','/api/history/rollback':'历史回退','/api/backups/restore':'恢复应用备份','/api/backups/create':'创建应用备份','/api/migration':'迁移操作','/api/endpoint':'端点操作','/api/manage':'课程表与连接配置','/api/import/confirm':'导入课程','/api/resolve':'解决冲突'}[path]??'历史维护')+(data.action?' · '+data.action:'');
}
function renderWriteStatus() {
  const op=activeWrite??unresolvedWrite??lastWrite,host=$('writeStatus');host.hidden=!op;renderCreationStatus();if(!op)return;
  host.replaceChildren(el('strong',op.label+' · '+op.workspaceName),el('p',op.message));
  if(op.evidence)host.append(el('p',op.evidence));
  if(op.phase!=='running')host.append(button('重新读取操作状态',async()=>{
    await reconcileWrite(op);
    if(op.action==='workspace.switch' && op.evidenceReady){await refreshAfterWrite(op);if(op.confirmationPending){op.confirmationPending=false;if(unresolvedWrite===op && op.phase!=='uncertain')unresolvedWrite=null;}}
    renderWriteStatus();
  }));
  if(op.phase==='uncertain' && op.evidenceReady && unresolvedWrite===op)host.append(button('已核对结果，解除操作保护（不重发）',async()=>{
    if(unresolvedWrite!==op || activeWrite)return;
    unresolvedWrite=null;invalidateHistory();invalidateBackup();
    op.message='结果仍不确定；已由用户结束本次核对，没有重发原请求。请根据当前课程和恢复信息继续。';
    await refreshAfterWrite(op);renderWriteStatus();
  }));
}
async function authority(path) {
  const response=await fetch(base+path),value=await response.json();if(!response.ok)throw Error(value.error??'状态读取失败');return value;
}
async function reconcileWrite(op) {
  op.evidenceReady=false;
  try {
    if(op.path==='/api/manage' && op.action==='workspace.switch'){
      const m=await authority('/api/manage');
      if(!m.workspaces.some(w=>w.id===m.activeId))throw Error('活动课程表身份未获验证');
      op.observedWorkspaceId=m.activeId;op.evidence='已读取服务端活动课程表：'+m.workspaces.find(w=>w.id===m.activeId).name;op.evidenceReady=true;return;
    }
    // Restores and migrations may change the active catalog identity. Discover it read-only.
    const globalChange=op.path==='/api/backups/restore' || op.path==='/api/migration';
    const query=new URLSearchParams({date:$('date').value,zone:$('zone').value,origin:location.origin,...(!globalChange?{workspaceId:op.workspaceId}:{})});
    const schedule=await authority('/api/schedule?'+query);
    if(!globalChange && schedule.workspaceId!==op.workspaceId)throw Error('权威状态的课程表身份不匹配');
    const maintenance=await authority('/api/maintenance?workspaceId='+encodeURIComponent(schedule.workspaceId));
    if(maintenance.workspaceId!==schedule.workspaceId)throw Error('维护状态的课程表身份不匹配');
    op.observedWorkspaceId=schedule.workspaceId;
    op.evidence=`已重新读取服务器状态：版本 ${schedule.version} · ${scheduleStatus(schedule)}`;
    if(maintenance.protection)op.evidence+='；迁移保护阶段：'+maintenance.protection.phase;
    if(maintenance.restoreGuard || maintenance.restorePoisoned)op.evidence+='；恢复保护尚未解除，请继续恢复检查';
    if(maintenance.lastRestore)op.evidence+='；最近一条恢复记录：'+(maintenance.lastRestore.ok?'完成':'未完成')+'（'+new Date(maintenance.lastRestore.restoredAt).toLocaleString()+'，此记录不单独证明本请求结果）';
    op.evidenceReady=true;
  } catch(error) {op.evidence='权威状态暂不可读：'+error.message+'。保留请求结果，使用“重新读取操作状态”重试，不自动重发写请求。';}
}
async function refreshAfterWrite(op) {
  // Refresh only the still-selected scope, using authoritative identity for catalog replacement.
  if(workspaceId!==op.workspaceId)return;
  if(op.observedWorkspaceId && (op.path==='/api/backups/restore' || op.path==='/api/migration' || op.action==='workspace.switch'))workspaceId=op.observedWorkspaceId;
  await load();
  if(op.action==='workspace.switch' && currentPage==='history'){
    ++historyJobId;historyBusy=false;unlockControls([...$('history').querySelectorAll('button')]);
    await historyJob(historyRead);
  }
  if(pendingCreatedWorkspace){pendingCreatedWorkspace.active=workspaceId===pendingCreatedWorkspace.id;renderCreationStatus();}
}
async function post(path, data = {}) {
  const scope=data.workspaceId??workspaceId,pageTicket=viewEpoch,modelTicket=loadEpoch,formTicket=$('editor').open?editorEpoch:null,readOnly=postSemantics(path,data)!=='write';
  if(!readOnly && (activeWrite || unresolvedWrite))throw Error('已有写操作正在执行或等待结果核对，请查看页面上方操作状态，不能重复提交');
  // Revoking a view's restore capability must not cancel an already dispatched restore.
  if(path==='/api/backups/cancel' && [activeWrite,unresolvedWrite,lastWrite].some(op=>op?.path==='/api/backups/restore' && op.restoreToken===data.token))return {};
  const op=readOnly?null:{path,workspaceId:scope,workspaceName:management?.workspaces.find(w=>w.id===scope)?.name??scope,label:writeLabel(path,data),action:data.action,targetId:data.targetId,restoreToken:path==='/api/backups/restore'?data.token:null,phase:'running',message:'请求已发出，正在执行；切换页面不会取消。请等待结果，期间不提交其他写操作。'};
  if(op){activeWrite=op;lastWrite=op;renderWriteStatus();}
  let response,value,failure;
  try {
    response=await fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json','X-DormPanel-CSRF':token},body:JSON.stringify({workspaceId:scope,version,catalogVersion,...data})});
    value=await response.json();
    if(!response.ok)failure=Error(value.error??'服务器返回失败');
  } catch(error) {failure=error;}
  const draftWrite=!!op && ['/api/edit','/api/import/confirm'].includes(path) && formTicket!==null && localEditorContext;
  const detached=scope!==workspaceId || pageTicket!==viewEpoch || modelTicket!==loadEpoch || formTicket!==null && formTicket!==editorEpoch;
  if(op) {
    const rejected=failure && value?.writeApplied===false;
    const correctable=rejected && response.status===400;
    if(draftWrite && failure)failure.draftInvalid=!correctable;
    op.phase=!response || !value?'uncertain':rejected?'rejected':failure?'failed':'completed';
    op.message=op.phase==='uncertain'?'没有取得完整回执，结果不确定；这不表示操作取消或未写入。原请求不会自动重发。':failure?'服务器返回失败：'+failure.message+'。可能已有部分进度，请核对权威状态。':'服务器已返回成功响应；请求处理完成，具体保存、发布和恢复状态以核对结果为准。';
    if(rejected)op.message=draftWrite?(correctable?'服务器已拒绝，本次未提交修改。草稿已保留，可纠正输入后重试。':'服务器已拒绝，本次未提交修改；原提交资格已失效，草稿保留供核对。'):'服务器已拒绝，本次未执行请求：'+failure.message;
    if(!failure && path==='/api/manage' && data.action==='workspace.add' && value.workspaceId){
      pendingCreatedWorkspace={id:value.workspaceId,name:data.name,active:false};renderCreationStatus();
    }
    renderWriteStatus();
    // Keep the global write fence until reconciliation and the current model refresh finish.
    const switching=path==='/api/manage' && data.action==='workspace.switch';
    if(detached || failure && !correctable || switching || ['/api/history/rollback','/api/backups/restore','/api/migration'].includes(path))await reconcileWrite(op);
    if(switching && !op.evidenceReady){op.confirmationPending=true;unresolvedWrite=op;op.message+=' 活动课程表尚未确认，请只读核对，不能重发切换。';}
    if(op.phase==='uncertain')unresolvedWrite=op;
    if(failure || detached || switching){
      invalidateHistory();invalidateBackup();
      if(draftWrite && failure && !detached){
        if(!correctable)isolateEditing();
      }else if(!switching || op.evidenceReady)await refreshAfterWrite(op);
    }
    activeWrite=null;renderWriteStatus();
    if(detached)throw new DetachedWriteResult(op.message);
    if(switching && op.confirmationPending)throw Error(op.message);
  }
  if(failure)throw failure;
  if(readOnly && path!=='/api/backups/cancel' && detached)throw new StaleResponse('页面或课程表已切换');
  if(scope===workspaceId && value.version!==undefined)version=value.version;
  if(scope===workspaceId && value.catalogVersion!==undefined)catalogVersion=value.catalogVersion;
  return value;
}
async function runSync(options) {
  const scope=workspaceId;
  if ($('refresh').disabled) return;
  $("refresh").disabled = true;
  try {
    await post("/api/sync", options);
  } catch (e) {
    if (!obsoleteView(e)) await notifyUser(e.message);
  } finally {
    if(scope===workspaceId)await load();
    $("refresh").disabled = false;
  }
}
function button(label, action) {
  const b = el("button", label);
  b.type = "button";
  b.onclick = async event => {
    if (b.disabled) return;
    const managed=!!b.closest('#history,#backups');
    if(!managed)b.disabled = true;
    try { await action(event); }
    catch (e) { if (!obsoleteView(e)) await notifyUser(e.message); }
    finally { if (b.isConnected && !b.closest('#history') && !b.closest('#backups')) b.disabled = false; }
  };
  if (/删除|移除/.test(label)) b.className = "danger";
  return b;
}
function field(name, value = "", type = "text") {
  const label = el("label", name),
    input = el(type === "textarea" ? "textarea" : "input");
  if (type !== "textarea") input.type = type;
  input.value = value ?? "";
  label.append(input);
  $("fields").append(label);
  return input;
}
let editorEpoch = 0;
function dialog(title, build, save, saveLabel = '保存本地') {
  localEditorContext=null;
  const opener = document.activeElement;
  const epoch = ++editorEpoch, submit = $("editorForm").querySelector("[type=submit]");
  let scope = workspaceId, formVersion = version, formCatalogVersion = catalogVersion;
  let read, ready = true, saving = false, stale = false;
  const current = () => epoch === editorEpoch && $("editor").open;
  const update = () => { if (current()) submit.disabled = !read || !ready || saving || stale; };
  const form = {
    current,
    valid:()=>current() && !stale,
    setReady(value) { ready = value && !stale; update(); },
    captureVersion() { scope = workspaceId; formVersion = version; formCatalogVersion = catalogVersion; },
    invalidate(message) {stale=true;ready=false;update();if(current())$('formError').textContent=message;},
  };
  currentEditorForm=form;
  $("editorTitle").textContent = title;
  $("cancel").textContent = save ? "关闭" : "暂不处理";
  submit.hidden = !save; submit.textContent=saveLabel;
  submit.disabled = true;
  const initialize = async () => {
    read = null; ready = true;
    $("fields").replaceChildren(); $("formError").textContent = ""; update();
    try {
      const result = await build(form);
      if (!current()) return;
      read = result; update();
    } catch (error) {
      if (!current()) return;
      $("formError").textContent = '配置加载失败：' + error.message;
      $("fields").append(button('重试加载配置', initialize));
    }
  };
  $("editorForm").onsubmit = async (e) => {
    e.preventDefault();
    if (!save || !read || !ready || saving || stale) return;
    saving = true; update();
    try {
      if (scope !== workspaceId || formVersion !== version || formCatalogVersion !== catalogVersion) throw Object.assign(Error("表单版本或工作区已变化，请关闭后重新打开"),{draftInvalid:true});
      const input = await read();
      if (!current()) return;
      if (scope !== workspaceId || formVersion !== version || formCatalogVersion !== catalogVersion) throw Object.assign(Error("表单版本或工作区已变化，请关闭后重新打开"),{draftInvalid:true});
      await save(input);
      if (!current()) return;
      $("editor").close();
      await load();
    } catch (error) {
      if(error instanceof DetachedWriteResult){if(current())$('editor').close();return;}
      if(current()){
        if(error.draftInvalid)form.invalidate(error.message+'。草稿已保留；原提交资格已失效，请复制草稿后关闭窗口并重新读取、核对。');
        else $('formError').textContent=error.message;
      }
    } finally {
      saving = false; update();
    }
  };
  $('editor').addEventListener('close', () => {
    (opener?.isConnected ? opener : $('sourcesPage')).focus();
  }, { once: true });
  $("editor").showModal();
  // 先给出可见反馈，构建异常和异步加载失败都留在可取消、可重试的弹窗内。
  void initialize();
}
$("cancel").onclick = () => $("editor").close();
$("editor").addEventListener('close', () => {
  ++editorEpoch;localEditorContext=null;currentEditorForm=null;
  $("fields").replaceChildren();
  $("editorForm").onsubmit = null;
});
const edit = (operation, context = editingContext) => {requireEditing(context);return post("/api/edit", { workspaceId:context.workspaceId, version:context.version, operation });};
const time = (minute) =>
  `${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
const minute = (text) => {
  if (!/^\d{2}:\d{2}$/.test(text)) throw Error("时间格式应为 HH:mm");
  const [h, m] = text.split(":").map(Number);
  if (h > 23 || m > 59) throw Error("时间超限");
  return h * 60 + m;
};
function editEntry(row = {}) {
  businessDialog(
    row.id ? "编辑本地课程" : "新增本地课程",
    () => {
      const title = field("课程名", row.title),
        day = field("星期（1–7）", row.day ?? 1, "number"),
        start = field("开始", time(row.startMinute ?? 480), "time"),
        end = field("结束", time(row.endMinute ?? 590), "time"),
        location = field("地点", row.location);
      return () => ({
        id: row.id,
        title: title.value,
        day: Number(day.value),
        startMinute: minute(start.value),
        endMinute: minute(end.value),
        location: location.value,
      });
    },
    (value) => edit({ kind: "entry", action: "upsert", value }),
  );
}
function editEvent(row = {}) {
  businessDialog(
    row.id ? "编辑事件" : "新增事件",
    () => {
      const title = field("标题", row.title),
        start = field(
          "开始（含时区 ISO 时间，如 2026-10-21T08:00+08:00）",
          row.start ? new Date(Number(row.start)).toISOString() : "",
        ),
        end = field(
          "结束（含时区）",
          row.end ? new Date(Number(row.end)).toISOString() : "",
        ),
        note = field("备注", row.note, "textarea");
      return () => {
        const a = Date.parse(start.value),
          b = Date.parse(end.value);
        if (
          !Number.isFinite(a) ||
          !Number.isFinite(b) ||
          !/[zZ]|[+-]\d\d:\d\d$/.test(start.value) ||
          !/[zZ]|[+-]\d\d:\d\d$/.test(end.value)
        )
          throw Error("请填写带时区的日期时间");
        return {
          id: row.id,
          title: title.value,
          start: a,
          end: b,
          note: note.value,
        };
      };
    },
    (value) => edit({ kind: "event", action: "upsert", value }),
  );
}
function editOccurrence(c) {
  const context = requireEditing(), overrides=local.classOverrides;
  businessDialog(
    "课程详情：" + c.title,
    form => {
      const label = el("label", "编辑范围"),
        select = el("select");
      for (const [value, text] of [
        ["all", "全部周次"],
        ["week", "指定周 " + c.anchorDate],
      ]) {
        const o = el("option", text);
        o.value = value;
        select.append(o);
      }
      label.append(select);
      $("fields").append(label);
      const values = {};
      const fill = () => {
        Object.values(values).forEach((x) => x.label.remove());
        const anchor = select.value === "all" ? null : c.anchorDate,
          row =
            overrides.find(
              (e) =>
                e.sourceId === c.sourceId &&
                e.seriesId === c.seriesId &&
                e.anchorDate === anchor,
            ) ?? {};
        for (const [k, name, type] of [
          ["title", "标题", "text"],
          ["location", "地点", "text"],
          ["teacher", "教师", "text"],
          ["note", "备注", "text"],
          [
            select.value === "all" ? "weekday" : "date",
            select.value === "all" ? "星期（1–7）" : "目标日期（同一周）",
            select.value === "all" ? "number" : "date",
          ],
          ["periodStart", "起始节次", "number"],
          ["periodEnd", "结束节次", "number"],
          ["timingMode", "计时模式（linked / custom）", "text"],
          ["startMinute", "开始分钟（0–1439）", "number"],
          ["endMinute", "结束分钟（1–1439）", "number"],
        ]) {
          const input = field(name, row[k] ?? "", type),
            inherit = el("input");
          inherit.type = "checkbox";
          inherit.checked = row[k] == null;
          const label = input.parentElement;
          label.append(el("span", " 继承"), inherit);
          values[k] = { input, inherit, label, type };
        }
      };
      select.onchange = () => {
        for (const x of Object.values(values)) x.label.remove();
        for (const k of Object.keys(values)) delete values[k];
        fill();
      };
      fill();
      $("fields").append(
        el(
          "p",
          "勾选“继承”保存 null；取消勾选后留空文本表示显式清空。重置会删除该范围的覆盖，保留其他周次。",
        ),
      );
      $("fields").append(
        button("重置所选范围", async () => {
          if(!form.current())return;
          if (await confirmAction("确认重置所选范围？"))
            try {
              if(!form.current())return;
              await edit({
                kind: "override",
                action: "reset",
                sourceId: c.sourceId,
                seriesId: c.seriesId,
                anchorDate: select.value === "all" ? null : c.anchorDate,
              }, context);
              if(!form.current())return;
              $("editor").close();
              await load();
            } catch (e) {
              if(form.current() && !obsoleteView(e))$("formError").textContent = e.message;
            }
        }),
      );
      return () => ({
        kind: "override",
        action: "upsert",
        sourceId: c.sourceId,
        seriesId: c.seriesId,
        anchorDate: select.value === "all" ? null : c.anchorDate,
        patch: Object.fromEntries(
          Object.entries(values).map(([k, v]) => [
            k,
            v.inherit.checked
              ? null
              : v.type === "number"
                ? Number(v.input.value)
                : v.input.value,
          ]),
        ),
      });
    },
    (operation) => edit(operation),
  );
}
function editProfile(p = {}) {
  businessDialog(
    "学期、阶段作息与节次",
    () => {
      const key = field("学期标识", p.termKey),
        zone = field("时区", p.timezone ?? "Asia/Shanghai"),
        w1 = field("第一周周一", p.week1Monday ?? "", "date"),
        phases = field(
          "每行：生效日期 | 节次,开始,结束 | 节次,开始,结束",
          p.phases
            ?.map(
              (ph) =>
                ph.effectiveFrom +
                ph.periods
                  .map((t) => ` | ${t.periodNumber},${t.start},${t.end}`)
                  .join(""),
            )
            .join("\n") ?? "2026-09-07 | 1,08:00,08:45 | 2,08:55,09:40",
          "textarea",
        );
      if (p.termKey) key.disabled = true;
      return () => ({
        termKey: key.value,
        timezone: zone.value,
        week1Monday: w1.value,
        phases: phases.value
          .split("\n")
          .filter((s) => s.trim())
          .map((line) => {
            const [d, ...periods] = line.split("|").map((s) => s.trim());
            return {
              effectiveFrom: d,
              periods: periods.map((t) => {
                const [n, start, end] = t.split(",").map((s) => s.trim());
                return { periodNumber: Number(n), start, end };
              }),
            };
          }),
      });
    },
    (value) => edit({ kind: "profile", action: "upsert", value }),
  );
}
function adjustDate(row = {}) {
  businessDialog(
    "日期调整 / 停课与补课",
    () => {
      const d = field("目标日期", row.date ?? $("date").value, "date"),
        mode = el('select'),
        day = field("补用星期（1–7）", row.weekday ?? 1, "number"),
        label = field("说明", row.label ?? "");
      for (const [value, label] of [['none', '停课'], ['weekday', '补课（使用指定星期的课程）']]) {
        const option = el('option', label); option.value = value; mode.append(option);
      }
      mode.value = row.mode ?? 'none';
      const modeLabel = el('label', '调整方式'); modeLabel.append(mode); d.parentElement.after(modeLabel);
      day.disabled = mode.value === 'none'; mode.onchange = () => { day.disabled = mode.value === 'none'; };
      if (row.date) d.disabled = true;
      return () => ({
        date: d.value,
        mode: mode.value,
        weekday: mode.value === "none" ? null : Number(day.value),
        label: label.value,
      });
    },
    (value) => edit({ kind: "date", action: "upsert", value }),
  );
}
async function remove(kind, id, label, context=editingContext) {
  requireEditing(context);
  if (await confirmAction("确认删除 / 重置 " + label + "？相关覆盖会随课程或来源删除。"))
    try {
      await edit({ kind, id, action: "delete" }, context);
      await load();
    } catch (e) {
      if(!(e instanceof DetachedWriteResult))await notifyUser(e.message);
    }
}
function renderRecords() {
  const context=requireEditing();
  const root = $("records");
  root.replaceChildren();
  for (const [kind, rows, label, key, editor] of [
    ["entry", local.entries, "本地课程", "id", editEntry],
    ["event", local.events, "事件", "id", editEvent],
    ["profile", local.profiles, "学期", "termKey", editProfile],
    [
      "source",
      local.sources,
      "来源",
      "id",
      (row) =>
        businessDialog(
          "来源管理",
          () => {
            const name = field("显示名", row.displayName),
              term = field("关联学期（空表示不指定）", row.termKey);
            return () => ({
              kind: "source",
              action: "upsert",
              id: row.id,
              displayName: name.value,
              termKey: term.value || null,
            });
          },
          edit,
        ),
    ],
    ["date", local.dayAdjustments, "日期调整", "date", adjustDate],
  ]) {
    const group = el("section", null, "record-group");
    group.append(el("h3", label));
    root.append(group);
    if (!rows.length) group.append(el("p", "暂无" + label, "hint"));
    for (const row of rows) {
      const p = el(
        "p",
        row.title ??
          row.displayName ??
          row.termKey ??
          `${row.date} ${row.mode === "none" ? "停课" : "补课"} ${row.label}`,
      );
      p.append(
        button("编辑", () => {requireEditing(context);return editor(row);}),
        button("删除 / 重置", () =>
          remove(kind, row[key], row.title ?? row.displayName ?? row[key],context),
        ),
      );
      group.append(p);
    }
  }
}
async function importFile() {
  const scope = workspaceId, importVersion = version;
  businessDialog(
    "导入预览",
    (form) => {
      const file = field("选择 ICS、CSV 或 XLS", "", "file");
      file.accept = ".ics,.csv,.xls";
      const name = field("来源显示名", "课程表"),
        target = el("select");
      target.append(el("option", "新增来源"));
      target.firstChild.value = "";
      for (const s of local.sources) {
        const o = el("option", "替换 " + s.displayName);
        o.value = s.id;
        target.append(o);
      }
      $("fields").append(target);
      const info = el("pre", "请选择文件，预览成功后才能保存。"), retry = button('重试预览', () => previewFile(selectedFile));
      info.setAttribute('role', 'status'); retry.hidden = true;
      $("fields").append(info, retry);
      let preview = null, selectedFile = null, previewError = '请先选择文件', previewEpoch = 0;
      form.setReady(false);
      const previewFile = async (f) => {
        const epoch = ++previewEpoch, current = () => form.valid() && epoch === previewEpoch;
        preview = null; previewError = '正在检查文件预览，请稍候'; form.setReady(false);
        retry.hidden = true; $('formError').textContent = '';
        if (!f) { info.textContent = previewError = '未选择文件，请选择 ICS、CSV 或 XLS。'; return; }
        info.textContent = `已选择 ${f.name}，正在检查预览…`;
        try {
          if (f.size > 1048576) throw Error("文件超过 1 MiB");
          const bytes = new Uint8Array(await f.arrayBuffer());
          if (!current()) return;
          let binary = "";
          for (const b of bytes) binary += String.fromCharCode(b);
          const result = await post("/api/import/preview", {
            workspaceId: scope, version: importVersion,
            filename: f.name,
            base64: btoa(binary),
          });
          if (!current()) return;
          preview = result; previewError = ''; form.setReady(true);
          info.textContent =
            `${preview.filename} · ${preview.count} 个实例\n` +
            preview.warnings.join("\n") +
            "\n" +
            preview.samples
              .map(
                (r) =>
                  `${r.title} ${new Date(r.start).toISOString()} ${r.location}`,
              )
              .join("\n");
        } catch (e) {
          if (!current()) return;
          previewError = '预览失败：' + e.message;
          info.textContent = `已选择 ${f.name}\n${previewError}` + (/请先配置学期/.test(e.message) ? '\n请关闭此窗口，在管理中新增对应学期及节次时间，然后重新导入。' : '');
          $('formError').textContent = previewError; retry.hidden = false;
        }
      };
      file.onchange = () => { selectedFile = file.files[0] ?? null; void previewFile(selectedFile); };
      return async () => {
        if (!preview) throw Error(previewError || '请先完成文件预览');
        const confirmed = preview;
        if (
          !(await confirmAction("确认导入以上预览？替换来源将保留原 sourceId 和已有覆盖。" + (() => { const r = preview.replacements?.find(r => r.sourceId === target.value); return r ? `\n替换 ${r.count} 个原实例 → ${preview.count} 个新实例。\n可能不再匹配的课程：${r.unmatchedTitles.join("、") || "无"}` : ""; })()))
        )
          throw Error("已取消");
        if (confirmed !== preview) throw Error('所选文件已变化，请检查最新预览');
        return {
          workspaceId: scope, version: confirmed.version,
          id: confirmed.id,
          displayName: name.value,
          targetId: target.value || null,
        };
      };
    },
    (input) => post("/api/import/confirm", input),
  );
}
async function showConflicts() {
  try {
    const c = await get("/api/conflicts");
    if (c.workspaceId !== workspaceId) return;
    version = c.version;
    dialog(
      "同步冲突",
      form => {
        $("fields").append(
          el(
            "p",
            c.conflicts.length
              ? `${c.conflicts.length} 个关联冲突；远端方案按提交 UUID 稳定折叠，确认后才采用。独立修改继续保留。`
              : "暂无冲突",
          ),
        );
        for (const conflict of c.conflicts) {
          const names = conflict.keys.map((key) => {
            const v =
              conflict.local[key] ?? conflict.remote[key] ?? conflict.base[key];
            if (!v) return "已删除 / 已重置";
            if (key.startsWith("class:")) return "课程 " + v.title;
            if (key.startsWith("event:")) return "事件 " + v.title;
            if (key.startsWith("source:"))
              return "来源 " + v.source.displayName;
            if (key.startsWith("term:")) return "学期 " + v.value.termKey;
            if (key.startsWith("date:")) return "日期调整 " + v.date;
            const title =
              local.entries.find((r) => r.id === v.seriesId)?.title ??
              local.imported.find(
                (r) => r.sourceId === v.sourceId && r.seriesId === v.seriesId,
              )?.title ??
              v.seriesId;
            return `课程编辑 ${title} · ${v.anchorDate ?? "全部周次"}`;
          });
          const section = el("details");
          section.append(
            el("summary", [...new Set(names)].join("、")),
            el(
              "p",
              "此关联单位须整体决定；null 表示继承，空文本表示显式清空，记录缺失表示删除或重置。",
            ),
            el("pre", JSON.stringify(conflict, null, 2)),
          );
          $("fields").append(section);
        }
        for (const branch of c.branches) {
          const section = el("details");
          section.append(
            el(
              "summary",
              `完整远端分支 ${branch.commitId} / ${branch.writerId}`,
            ),
            el("pre", JSON.stringify(branch.state, null, 2)),
          );
          $("fields").append(section);
        }
        const result = el("details");
        result.append(
          el("summary", "已展示的远端方案（完整）"),
          el("pre", JSON.stringify(c.remote, null, 2)),
        );
        $("fields").append(result);
        for (const [choice, label] of [
          ["local", "保留本地"],
          ["remote", "采用远端方案"],
        ])
          $("fields").append(
            button(label, async () => {
              if(!form.current())return;
              if (!(await confirmAction(label + "？将保存待发布提交。"))) return;
              try {
                if(!form.current())return;
                await post("/api/resolve", { choice, token: c.token, workspaceId: c.workspaceId, version: c.version });
                if(!form.current())return;
                $("editor").close();
                await load();
              } catch (e) {
                if(form.current() && !obsoleteView(e))$("formError").textContent = e.message;
              }
            }),
          );
        return () => null;
      },
      null,
    );
  } catch (e) {
    await notifyUser(e.message);
  }
}
$("newCourse").onclick = () => editEntry();
$("newEvent").onclick = () => editEvent();
$('manage').onclick = () => {
  $('manager').hidden = !$('manager').hidden;
  $('welcome').hidden = $('welcome').dataset.unbound !== 'true' || !$('manager').hidden;
  $('manage').setAttribute('aria-expanded', String(!$('manager').hidden));
  if (!$('manager').hidden) $('manager').scrollIntoView({ block: 'start' });
};
$('managerCourse').onclick = () => editEntry();
$('managerEvent').onclick = () => editEvent();
$('welcomeSources').onclick = () => showSources(true);
$("newProfile").onclick = () => editProfile();
$("adjustDate").onclick = () => adjustDate();
$("importFile").onclick = importFile;
$("config").onclick = () => showSources(true);
$("conflicts").onclick = showConflicts;
let sharingBusy=false;
async function sharingAction(action) {
  if(sharingBusy)return;sharingBusy=true;$('bind').disabled=$('create').disabled=true;
  try {await action();} finally {sharingBusy=false;$('bind').disabled=$('create').disabled=false;}
}
$("bind").onclick = () => sharingAction(async () => {
  if (activeProtocol === 'v3') {
    const scope=workspaceId,page=viewEpoch;
    try {
      const discovery = await post('/api/sync', { workspaceId, version, discover:true });
      await load();
      if(scope!==workspaceId || page!==viewEpoch)throw new StaleResponse();
      const candidates = discovery.candidates.filter(c => c.state === 'DESCRIPTOR_VERIFIED');
      if (!candidates.length) return notifyUser('没有可加入的已验证共享课表。未执行绑定，也没有创建新的共享数据；请检查连接目录或重试发现。');
      const candidate = await chooseOption('选择已验证的共享课表（加入后继续验证历史）',candidates.map(c=>({label:'共享课表 '+c.datasetId.slice(0,8),value:c})));
      if(!candidate)return;
      if (await confirmAction(`确认加入 ${candidate.datasetId}？本地必须为空；已有课程不会自动清空。`, '加入此共享课表'))
        await runSync({ workspaceId, version, bind:true, descriptorPath:candidate.descriptorPath, descriptorHash:candidate.descriptorHash });
    } catch (e) { if (!obsoleteView(e)) await notifyUser(e.message); }
    return;
  }
  const context = { workspaceId, version };
  if (await confirmAction("确认加入此目录已有共享课表？本地未发布课程会阻止加入，请保留现有课程并改用独立空白课程表。", "加入共享课表"))
    await runSync({ ...context, bind: true });
});
$("create").onclick = () => sharingAction(async () => {
  const context = { workspaceId, version };
  if (await confirmAction("将当前课程表内容发布为新的独立共享课表。仅可使用已配置的空目录；已有历史不会被覆盖。", "创建并发布共享课表"))
    await runSync({ ...context, create: true });
});
async function loadManagement(epoch = loadEpoch) {
  const scope = workspaceId, ticket=++managementEpoch;
  $('managementStatus').textContent = '正在读取管理数据…';
  let m;
  try { m = await get('/api/manage',{pageSensitive:false}); }
  catch (error) {
    if (!obsoleteView(error) && epoch === loadEpoch && scope === workspaceId && ticket===managementEpoch) $('managementStatus').textContent = '管理数据加载失败：' + error.message + '。可点击“刷新状态”或重新打开连接弹窗重试。';
    throw error;
  }
  if (epoch !== loadEpoch || scope !== workspaceId || ticket!==managementEpoch) return;
  const active = m.workspaces.find(w => w.id === scope);
  if (!active) {
    $('managementStatus').textContent = '工作区尚未就绪，请刷新状态后重试。';
    throw Error('工作区尚未就绪，请刷新状态后重试');
  }
  $('managementStatus').textContent = '';
  $('currentContext').textContent=active.name+' · '+$('status').textContent;
  management = m; catalogVersion = m.revision;
  const context = { workspaceId: scope, version: active.version, catalogVersion: m.revision };
  $('workspaceSelect').replaceChildren(...m.workspaces.map(w => {
    const option = el('option', w.name + (w.unknown ? ' · 待检查' : w.conflict ? ' · 冲突' : w.pending ? ' · 待确认' : w.dirty ? ' · 未发布' : ''));
    option.value = w.id; return option;
  }));
  $('workspaceSelect').value = workspaceId;
  $('workspaceRows').replaceChildren(...m.workspaces.map(w => {
    const row = el('article', null, 'source-card'), c = m.connections.find(c => c.id === w.connectionId);
    const type = !c ? '仅本地编辑 · 未连接存储' : c.type === 'webdav' ? 'WebDAV' : c.rootId === 'private' ? '应用私有目录' : 'NAS 本地目录';
    row.append(el('h3', w.name), el('span', w.id === workspaceId ? '当前使用' : '已保存', 'badge'), el('p', type + (c ? ' · ' + c.name : '')));
    const state = w.unknown ? '状态待检查；切换后读取' : [w.busy ? '正在同步检查' : '', w.dirty ? '有本地修改待发布' : '本地已保存', w.pending ? '发布结果待回读确认' : '', w.conflict ? '存在冲突，请处理' : '', w.recovery ? '有恢复信息，请检查详情' : '', w.error ? '连接或同步异常，请检查详情' : ''].filter(Boolean).join(' · ');
    row.append(el('p', state, w.error || w.conflict ? 'warning' : ''), el('small', `最近检查：${w.checkedAt ? new Date(w.checkedAt).toLocaleString() : '尚未检查'}。其他设备接收情况未知。`));
    const switchButton = button(w.id === workspaceId ? '正在使用' : '切换', () => switchWorkspace(w.id)); switchButton.disabled = w.id === workspaceId;
    row.append(switchButton);
    const more = el('details'); more.append(el('summary', '高级操作：端点、迁移与移除'));
    more.append(button('修改名称', async () => {
      const name = await requestText('课程表名称（工作区）', w.name); if (!name) return;
      await manageAction({ ...context, action: 'workspace.rename', targetId: w.id, targetVersion: w.version, name });
    }), button('选择访问端点', () => chooseEndpoint(w)), button('移除配置', async () => {
      const name = await requestText('只移除配置，保留课表文件。当前/唯一工作区和有修改、冲突、恢复信息的工作区不能移除。输入名称确认：' + w.name,'','移除课程表配置');
      if (name !== w.name) return;
      await manageAction({ ...context, action: 'workspace.remove', targetId: w.id, targetVersion: w.version, confirm: name });
    }));
    if (w.id === workspaceId) {
      if (c && !w.datasetId) row.append(el('p', '尚未加入或创建共享数据；本地课程保留。'), button('加入已有共享课表…', () => $('bind').onclick()), button('创建新的共享课表…', () => $('create').onclick()));
      if (!c) row.append(button('开启共享 / 添加连接', () => $('addLocal').click()));
      row.append(button('查看课程', () => navigate('dashboard')));
      if (w.conflict) row.append(button('查看和处理冲突', showConflicts));
      else if (c) row.append(button(w.pending ? '继续检查远端回执' : w.error ? '重试连接并同步' : '检查并同步', () => runSync({})));
      if (w.recovery || w.protection) row.append(button('继续原操作 / 查看恢复信息', async()=>{const m=await get('/api/migration');if(m.v3Records?.some(r=>!['ACTIVE','CANCELLED'].includes(r.phase)))return recoverV3Migration();return $('maintenancePage').onclick();}));
      if (c && w.protocol !== 'v3') more.append(button('创建 / 迁移到 Sync v3…', migrateToV3));
      more.append(button('恢复 v3 迁移 / 查看证据', recoverV3Migration));
    }
    if (w.unknown) for (const b of more.querySelectorAll('button')) b.disabled = true;
    const diagnostics = el('details'); diagnostics.append(el('summary', '高级诊断'), el('pre', JSON.stringify(w, null, 2)));
    row.append(more, diagnostics); return row;
  }));
  $('connectionRows').replaceChildren(...m.connections.map(c => {
    const row = el('article', null, 'source-card');
    row.append(el('h3', c.name), el('span', c.type === 'webdav' ? 'WebDAV' : c.rootId === 'private' ? '应用私有目录' : 'NAS 本地目录', 'badge'), el('p', c.type === 'webdav' ? c.url : `${m.roots.find(r => r.id === c.rootId)?.name ?? '授权已撤销'} / ${c.relative || '根目录'}`));
    const owners = m.workspaces.filter(w => w.connectionId === c.id);
    row.append(el('p', owners.length ? '用于：' + owners.map(w => w.name).join('、') : '尚未关联课程表；可新建工作区后接收或创建共享课表。'));
    if (!owners.length) row.append(button('新建课程表', () => createWorkspace(c)),
      button('用当前课程开启共享', () => attachCurrent(c)));
    const more = el('details'); more.append(el('summary', '连接操作'));
    more.append(button('编辑连接', () => connectionDialog(c.type, c)), button('测试权限与历史', async () => {
      try { const result = await post('/api/connections/test', { ...context, connectionId: c.id, name: c.name }); await notifyUser(`${result.permission}；${result.observed} 个提交；异常 ${result.deferred}`); }
      catch (e) { await notifyUser(e.message); }
    }), button('移除连接', async () => {
      if (await confirmAction('移除连接配置？不会删除课表文件；仍被课程表使用时会拒绝。','移除连接配置')) await manageAction({ ...context, action: 'connection.remove', connectionId: c.id });
    })); row.append(more); return row;
  }));
  if (!m.connections.length) $('connectionRows').append(el('p', '尚未添加存储位置。可以继续本地编辑，或添加下面任一种连接。'));

  return m;
}
async function migrateToV3() {
  try {
    const p = await post('/api/migration', { action:'v3.preview' });
    const targetMode=await chooseOption('选择迁移目标',[{label:'首台设备：创建唯一新目标',value:'create'},{label:'后续设备：加入已迁移目标',value:'join'}]);
    if(!['create','join'].includes(targetMode))return;
    let selected=null;
    if(targetMode==='join') {
      const discovery=await post('/api/migration',{action:'v3.discover'});
      const candidates=discovery.candidates.filter(c=>c.state==='DESCRIPTOR_VERIFIED');
      if(!candidates.length)return notifyUser('未发现可验证的已有 v3 目标；没有创建第二个数据集。');
      selected=await chooseOption('选择已有迁移目标',candidates.map(c=>({label:c.datasetId+' · Descriptor: '+c.descriptorHash,value:c})));if(!selected)return;
    }
    const message = `源 ${p.sourceProtocol === 'LOCAL' ? '本地未绑定课表' : p.sourceDatasetId}\n已验证 ${p.commits} 个提交，${p.tips.length} 个前沿。\n八类 RAW: ${JSON.stringify(p.counts)}\nRAW SHA-256: ${p.rawHash}\n\n保留未发布本地修改及原 v2 基线，不新增 v2 提交。确认后本机源 Writer 持久暂停；${selected ? '只读验证既有目标，完整 RAW 等价后加入，不创建新 Genesis' : '精确回读新 Genesis 并再次验证源后激活 v3'}。${p.sourceProtocol === 'LOCAL' ? '保留原本地完整快照' : '原 v2 远端历史仍保留'}；这不是独立验证的备份。\n\n请先停止其他已知旧客户端。普通 WebDAV 无法证明未知设备已经停写，旧目录没有被服务端禁止写入。后续设备须加入此目标，不要各自再次迁移。`;
    if (!await confirmAction(message, '已停止已知旧客户端，准备迁移')) return;
    const m = await post('/api/migration', { action:'v3.prepare', token:p.token, confirm:p.sourceDatasetId ?? 'LOCAL', writersStopped:true,
      ...(selected ? {descriptorPath:selected.descriptorPath,descriptorHash:selected.descriptorHash} : {}) });
    await load();
    await resumeV3Migration(m.id);
  } catch (e) { if(e instanceof DetachedWriteResult)return;await notifyUser(e.message + '。本地、源历史与目标候选已保留，请从恢复入口继续。'); await load(); }
}
async function resumeV3Migration(id) {
  const m = await post('/api/migration', { action:'v3.resume', migrationId:id });
  if (m.phase === 'ACTIVE') workspaceId=m.targetId;
  await load(); await notifyUser(`迁移 ${m.phase} · ${m.targetDescriptor?.datasetId ?? '目标待验证'}。其他设备接收情况未知；旧目录仍可能接收旧客户端写入。`);
}
async function recoverV3Migration() {
  try {
    const status = await get('/api/migration');
    if (!status.v3Records.length) return notifyUser('当前工作区没有 v3 迁移记录。');
    const m = status.v3Records.at(-1);
    if (m.phase === 'ACTIVE' || m.phase === 'CANCELLED') return notifyUser(JSON.stringify(m,null,2));
    if (await confirmAction(JSON.stringify(m,null,2) + '\n继续恢复同一事务和目标？')) await resumeV3Migration(m.id);
    else if (await confirmAction('仅在尚未发布本机候选、尚未激活目标时可取消准备并恢复源 Writer。加入既有目标的只读预检可以取消；不删除两端文件。检查并取消？')) { await post('/api/migration', { action:'v3.cancel', migrationId:m.id }); await load(); }
  } catch (e) { if(e instanceof DetachedWriteResult)return;await notifyUser(e.message + '。保留两端数据，禁止清库或简单切回旧协议。'); }
}
async function manageAction(input) {
  try { const result = await post('/api/manage', input); await load(); return result; }
  catch (e) { if(e instanceof DetachedWriteResult)return;await notifyUser(e.message); await load(); }
}
async function switchWorkspace(id) {
  if (!id || id === workspaceId || switching) return;
  if(activeWrite || unresolvedWrite){$('workspaceSelect').value=workspaceId;renderWriteStatus();return;}
  switching = true; isolateEditing();invalidateHistory(); invalidateBackup(); ++viewEpoch; $('workspaceSelect').disabled = true;
  for (const id of ['dashboard', 'dashboardActions', 'dataSources','history','backups']) $(id).inert = true;
  $('editor').close();
  try {
    await post('/api/manage', { action: 'workspace.switch', targetId: id });
    $('editor').close();navigate('dashboard');
  } catch (e) { if(e instanceof DetachedWriteResult)return;await notifyUser(e.message); await load(); }
  finally {
    switching = false; $('workspaceSelect').disabled = false;
    for (const id of ['dashboard', 'dashboardActions', 'dataSources','history','backups']) $(id).inert = false;
  }
}
function chooseEndpoint(w) {
  if(w.protocol==='v3')return v3EndpointDialog(w);
  dialog('工作区访问端点', () => {
    const select = el('select');
    for (const c of management.connections) { const o = el('option', c.name); o.value = c.id; select.append(o); }
    select.value = w.connectionId ?? '';
    $('fields').append(el('p', '已有数据集必须完整验证身份、已知提交和历史闭包。不同数据集请创建独立工作区。'), select);
    return () => ({ action: 'workspace.endpoint', targetId: w.id, targetVersion: w.version, connectionId: select.value });
  }, input => post('/api/manage', input));
}
function v3EndpointDialog(w) {
  const key='v3-endpoint:'+w.id;
  dialog('安全更换 v3 访问端点', form=>{
    const select=el('select');select.setAttribute('aria-label','候选访问连接');
    for(const c of management.connections){const o=el('option',c.name);o.value=c.id;select.append(o);}
    const output=el('pre'), controls=el('nav');let preview=null,resumeButton,cancelButton,confirmButton;
    const display=p=>{
      preview=p;if(p.token)sessionStorage.setItem(key,p.token);
      resumeButton.disabled=!['VALIDATING','READY'].includes(p.status);cancelButton.disabled=!['VALIDATING','READY','EXPIRED','STALE'].includes(p.status);confirmButton.disabled=p.status!=='READY';
      const status={NOT_STARTED:'尚未开始',VALIDATING:'验证中，进度已保存，可继续',READY:'候选已通过已知历史验证，等待确认',CANCELLED:'已取消，原绑定保留',EXPIRED:'预检已到期，请新建预检',STALE:'预检已失效，请新建预检',COMMITTED:'本机访问端点已切换，原 Writer 和本地数据保留'}[p.status]??p.status;
      output.textContent=`${status}\n数据集：${p.datasetId??'见当前工作区绑定'}\n`+(p.facts?`网络可达：${p.facts.networkReachable?'已验证':'未确认'}；TLS / 认证：${p.facts.tlsAndAuthenticationValid?'已验证':'未确认'}\nDescriptor 精确匹配：${p.facts.descriptorExact?'是':'未确认'}\n已知历史完整回读：${p.facts.knownHistoryComplete?'完成':'尚未完成'}；额外对象：${p.facts.extraObjects??'待验证'}\n`:'')+
        (p.failure?`本次验证暂停：${p.failure.stage} / ${p.failure.code}\n`:'')+'旧端点新变化与未知 Writer 未能证明；其他设备接收未知。';
    };
    const call=async(action,extra={})=>{try{
      const result=await post('/api/endpoint',{workspaceId:w.id,action,token:preview?.token??sessionStorage.getItem(key),...extra});
      // 确认会原子更新 Catalog；回执不携带新 revision，后续操作须重新读取。
      if(result.status==='COMMITTED')await load();
      display(result);
    }catch(e){output.textContent=e.message;}};
    controls.append(button('只读预检所选连接',()=>call('preview',{candidate:{connectionId:select.value}})),resumeButton=button('继续上次预检',()=>call('resume')),button('查询状态',()=>call('status')),cancelButton=button('取消预检',()=>call('cancel')),
      confirmButton=button('确认更换此工作区端点',async()=>{
        if(preview?.status!=='READY')return notifyUser('请先继续预检，直到已知历史验证完成');
        if(!await confirmAction(`仅工作区 ${w.name} 改用独立连接；保留旧连接和其他工作区。本地课程、Writer、冲突和回执保留。旧端点新写入未能证明，其他设备接收未知。数据集 ${preview.datasetId}`,'确认安全切换'))return;
        await call('confirm',{confirm:preview.datasetId,version:preview.version,catalogVersion:preview.catalogVersion});
      }));
    $('fields').append(el('p','先添加候选连接，填入新地址、凭据或经独立核对的证书。本预检只读取；不初始化、不上传、不改变绑定。验证进度持久保存，创建后 24 小时固定到期；重开和继续不延长到期时间。共享连接不会被隐式修改。'),select,controls,output);
    resumeButton.disabled=cancelButton.disabled=confirmButton.disabled=true;void call('status');return ()=>({});
  });
}
// One read-only navigator for authorized local roots and WebDAV drafts.
// A location is typed; callers never construct paths from display labels.
function folderBrowser(host, { roots, initial, current = () => host.isConnected, list, select, changed = () => {}, chooseLabel }) {
  host.classList.add('folder-browser');
  const scope = el('select'); scope.setAttribute('aria-label', '存储范围');
  for (const r of roots) { const o = el('option', r.name); o.value = r.id; scope.append(o); }
  scope.value = initial.rootId ?? roots[0]?.id ?? '';
  let location = { ...initial }, epoch = 0, ready = false;
  const crumbs = el('nav'), status = el('p'), rows = el('div', null, 'folder-list');
  crumbs.setAttribute('aria-label', '文件夹路径'); status.setAttribute('role', 'status');
  const up = button('上一级', () => navigate(parent())), retry = button('重试读取目录', () => navigate(location));
  const choose = button('选择此文件夹', () => { if (ready && current()) select({ ...location }); }); choose.className = 'primary';
  const rootLocation = () => location.kind === 'webdav' ? { ...location, url:location.root } : { kind:'local', rootId:scope.value, relative:'' };
  const parts = () => location.kind === 'webdav' ? new URL(location.url).pathname.slice(new URL(location.root).pathname.length).split('/').filter(Boolean).map(decodeURIComponent) : location.relative.split('/').filter(Boolean);
  const ancestor = count => {
    if (location.kind === 'local') return { ...location, relative:parts().slice(0,count).join('/') };
    const u = new URL(location.root); u.pathname += parts().slice(0,count).map(encodeURIComponent).join('/') + (count ? '/' : ''); return { ...location, url:u.href };
  };
  const parent = () => ancestor(Math.max(0, parts().length-1));
  async function navigate(next, restore = false) {
    location = { ...next }; const ticket = ++epoch, captured = { ...location };
    const active = () => current() && ticket === epoch;
    ready = false; choose.disabled = true; changed(); rows.replaceChildren(); retry.hidden = true;
    up.disabled = !parts().length; status.textContent = '正在读取目录…';
    crumbs.replaceChildren(button(scope.selectedOptions[0]?.textContent ?? '服务器起始目录', () => navigate(rootLocation())));
    parts().forEach((name,i) => crumbs.append(el('span','/'),button(name,()=>navigate(ancestor(i+1)))));
    try {
      const result = await list(captured); if (!active()) return;
      for (const dir of result.directories) {
        const name = typeof dir === 'string' ? dir : dir.name;
        const child = captured.kind === 'webdav' ? { ...captured, url:dir.url } : { ...captured, relative:[captured.relative,name].filter(Boolean).join('/') };
        rows.append(button('📁 '+name,()=>navigate(child)));
      }
      if (!result.directories.length) rows.append(el('p','此文件夹没有子目录，可以选择当前文件夹。'));
      status.textContent = '当前路径：' + (captured.kind === 'webdav' ? decodeURI(captured.url) : (scope.selectedOptions[0]?.textContent ?? '')+' / '+(captured.relative || '根目录'));
      ready = true; choose.disabled = false; choose.textContent = chooseLabel?.(captured) ?? '选择此文件夹';
      if (restore) choose.click();
    } catch (e) { if (active()) { status.textContent = '目录加载失败：'+e.message; retry.hidden = false; } }
  }
  scope.onchange = () => navigate({ kind:'local',rootId:scope.value,relative:'' });
  const label = el('label','存储范围'); label.append(scope); label.hidden = initial.kind === 'webdav';
  host.append(label,crumbs,up,status,rows,retry,choose);
  return { navigate, invalidate() { ++epoch; ready=false; choose.disabled=true; changed(); }, location:()=>({...location}) };
}
const localListing = l => get('/api/directories?'+new URLSearchParams({rootId:l.rootId,relative:l.relative}));
function pickLocalFolder(title, initial, roots = management.roots.filter(r=>r.id!=='private')) {
  return new Promise(resolve => {
    const scope = workspaceId, modal = el('dialog'), heading = el('h2',title), content = el('div');
    let result = null; modal.append(heading,content,button('取消',()=>modal.close())); document.body.append(modal);
    const picker = folderBrowser(content,{roots,initial:{kind:'local',rootId:initial.rootId ?? roots[0]?.id,relative:initial.relative ?? ''},
      current:()=>modal.open && workspaceId===scope,list:localListing,select:l=>{result=l;modal.close();}});
    modal.addEventListener('close',()=>{picker.invalidate();modal.remove();resolve(workspaceId===scope?result:null);},{once:true});
    modal.showModal(); void picker.navigate(picker.location());
  });
}
function chooseOption(title, choices) {
  return new Promise(resolve => {
    const modal=el('dialog');modal.append(el('h2',title));let result=null;
    for(const c of choices)modal.append(button(c.label,()=>{result=c.value;modal.close();}));
    modal.append(button('取消',()=>modal.close()));document.body.append(modal);
    modal.addEventListener('close',()=>{modal.remove();resolve(result);},{once:true});modal.showModal();
  });
}
function connectionDialog(type, c = null) {
  dialog(c ? '编辑连接' : (type === 'local' ? '添加本地目录连接' : '添加 WebDAV 连接'), async form => {
    const name = field('连接名称', c?.name ?? (type === 'local' ? '本地课程' : 'WebDAV 课程'));
    let read;
    if (type === 'webdav') {
      const server = field('WebDAV 服务器访问地址（可含起始路径）', c?.url ?? '');
      const username = field('用户名', c?.username ?? ''), password = field('密码（仅同一访问身份可留空保留）', '', 'password');
      const insecure = field('允许 HTTP 明文内网连接（帐号、密码和课表不加密）', '', 'checkbox'); insecure.checked = c?.allowInsecureHttp === true;
      const cert = field('独立信任的服务器公钥证书 PEM（留空保留）', '', 'textarea'), system = field('恢复系统 CA / 清除独立证书', '', 'checkbox');
      const selectedText=el('p',c ? '已选择：'+c.url : '尚未选择共享父目录','folder-location'), host=el('div'); let selectedUrl=c?.url ?? '', picker=null;
      const advanced=el('details'); advanced.append(el('summary','高级：手动输入完整 URL（服务器不支持目录列举时使用）'));
      const manual=el('input'); manual.setAttribute('aria-label','共享父目录完整 URL'); manual.value=c?.url ?? ''; advanced.append(manual);
      const config = () => ({ type, name:name.value, connectionId:c?.id, username:username.value, password:password.value, allowInsecureHttp:insecure.checked, certificatePem:system.checked ? '' : cert.value || undefined });
      const invalidate = () => { selectedUrl=''; selectedText.textContent='访问配置已变化，请重新浏览选择或使用高级地址'; picker?.invalidate(); host.replaceChildren(); picker=null; manual.value=''; form.setReady(false); };
      for(const input of [server,username,password,insecure,cert,system])input.addEventListener('input',invalidate);
      manual.oninput=()=>{selectedUrl=manual.value;selectedText.textContent='手动目录：'+selectedUrl;form.setReady(!!selectedUrl);};
      $('fields').append(el('p','先配置服务器与信任，再只读浏览共享父目录。浏览不创建数据集。证书须独立核对；已绑定 v3 的连接仍须通过安全端点更换。'),
        button('浏览服务器文件夹',()=>{
          try {
            const u=new URL(server.value);if(!u.pathname.endsWith('/'))u.pathname+='/'; const root=u.href, snapshot={...config(),url:root};
            host.replaceChildren();
            picker=folderBrowser(host,{roots:[{id:'server',name:'服务器起始目录'}],initial:{kind:'webdav',root,url:root},current:form.current,
              list:l=>post('/api/connections/browse',{...snapshot,browseUrl:l.url}),select:l=>{selectedUrl=l.url;manual.value=l.url;selectedText.textContent='已选择：'+decodeURI(l.url);form.setReady(true);}});
            let initial=root;try { const old=new URL(selectedUrl);if(old.origin===u.origin && old.pathname.startsWith(u.pathname))initial=old.href; }catch{}
            void picker.navigate({kind:'webdav',root,url:initial});
          }catch { $('formError').textContent='请输入有效的服务器访问地址'; }
        }),host,selectedText,advanced);
      form.setReady(!!selectedUrl);
      read=()=>{if(!selectedUrl)throw Error('请浏览并选择共享父目录，或使用高级完整 URL');return {...config(),url:selectedUrl};};
    } else {
      form.setReady(false);
      const host=el('div'), chosen=el('p','正在加载授权目录…');chosen.id='selectedFolder';$('fields').append(host,chosen);
      const m=await loadManagement();if(!form.current())return;
      if(!m || !workspaceId || !token)throw Error('工作区尚未就绪，请重试');
      if(!m.roots.length)throw Error('没有可用的授权目录');
      const initial={kind:'local',rootId:c?.rootId ?? 'private',relative:c?.relative ?? ''};
      if(!m.roots.some(r=>r.id===initial.rootId))throw Error('原目录未授权或授权已撤销');
      let selected=null;
      const privateFolder=l=>!c && l.rootId==='private' && !l.relative;
      const picker=folderBrowser(host,{roots:m.roots,initial,current:form.current,list:localListing,
        changed:()=>{selected=null;form.setReady(false);chosen.textContent='尚未选择文件夹';},
        chooseLabel:l=>privateFolder(l)?'使用私有目录（自动创建专用文件夹）':'选择此文件夹',
        select:l=>{selected=l;chosen.textContent='已选择：'+m.roots.find(r=>r.id===l.rootId).name+' / '+(l.relative||'根目录');form.setReady(true);}});
      form.captureVersion();void picker.navigate(initial,!!c);
      read=()=>{if(!selected)throw Error('请先成功读取所选目录并点击“选择此文件夹”');return {type,name:name.value,connectionId:c?.id,rootId:selected.rootId,relative:selected.relative,createPrivate:privateFolder(selected)};};
    }
    $('fields').append(el('p', '保存连接只保存目录和凭据。下一步选择保留当前课程开启共享，或创建独立空白课程表；加入或创建共享数据均需单独确认。'), button('测试（不保存）', async () => {
      try {
        const input = read();
        if (input.createPrivate) throw Error('新私有目录请先保存连接，再测试权限；测试不会创建工作区');
        const result = await post('/api/connections/test', input);
        if (form.current()) $('formError').textContent = `${result.permission}；${result.observed} 个提交；异常 ${result.deferred}`;
      } catch (e) { if (form.current()) $('formError').textContent = e.message; }
    }));
    return read;
  }, async input => {
    const result = await post('/api/manage', { action: 'connection.save', ...input });
    $('connectionNext').replaceChildren(el('h3','连接已保存，下一步选择课程表'),el('p','没有创建或加入共享数据。可保留当前课程开启共享，或创建独立空白课程表。'));
    const c = { ...input, id:result.connectionId };
    if (!input.connectionId) $('connectionNext').append(button('继续：新建空白课程表',()=>createWorkspace(c)),button('用当前课程开启共享',()=>attachCurrent(c)));
    showSources(true);
  }, '保存连接');
}
$('workspaceSelect').onchange = () => switchWorkspace($('workspaceSelect').value);
function showSources(open) { navigate(open ? 'dataSources' : 'dashboard'); }
$('sourcesPage').onclick = () => showSources(true);
$('schedulePage').onclick = () => navigate('dashboard');
async function createWorkspace(c) {
  let creationForm;
  dialog('新建独立课程表', form => {
    creationForm=form;
    const name=field('课程表名称',c.name), protocol=el('select'); protocol.setAttribute('aria-label','共享协议');
    for (const [value,label] of [['v3','Sync v3（推荐）'],['v2','Sync v2（旧版设备兼容）']]) {const o=el('option',label);o.value=value;protocol.append(o);}
    $('fields').append(el('p',`使用连接：${c.name}。新课程表为空，不包含当前本地课程。保存仅建立本机配置；远端创建或加入须另行确认。`),protocol);
    return ()=>({action:'workspace.add',connectionId:c.id,name:name.value,protocol:protocol.value});
  }, async input=>{
    const r=await post('/api/manage',input);
    if(!creationForm.current())return;
    creationForm.setReady(false);
    try{await post('/api/manage',{action:'workspace.switch',targetId:r.workspaceId});}
    catch(error){
      if(pendingCreatedWorkspace?.id===r.workspaceId){pendingCreatedWorkspace.message='课程表已经建立；自动切换尚未完成。'+error.message;renderCreationStatus();}
      return;
    }
    $('connectionNext').replaceChildren(el('p','独立空白课程表已建立。请在当前课程表卡片选择“加入已有共享课表”或“创建新的共享课表”；尚未写入远端。'));
  },'建立空白课程表');
}
async function attachCurrent(c) {
  if (management?.workspaces.find(w=>w.id===workspaceId)?.connectionId) return notifyUser('当前课程表已有连接。请使用高级端点更换，或新建独立空白课程表。');
  const w=management?.workspaces.find(w=>w.id===workspaceId); if(!w)return;
  dialog('将当前课程用于共享',()=>{
    $('fields').append(el('p',`保留“${w.name}”的全部本地课程，使用连接“${c.name}”。此步骤只关联连接，不写入远端。随后可迁移为推荐的 v3 共享课表；已有本地课程不能直接加入另一份共享数据。若要加入，请创建独立空白课程表。`));
    return ()=>({action:'workspace.endpoint',targetId:w.id,targetVersion:w.version,connectionId:c.id});
  },async input=>{
    await post('/api/manage',input);
    $('connectionNext').replaceChildren(el('h3','当前本地课程已关联连接'),el('p','本地内容保留，尚未写入远端。推荐下一步迁移为 v3 共享课表；如果需要加入另一份共享数据，请保留本地课程并创建独立空白课程表。'),button('继续：迁移当前课程到 v3',migrateToV3));
  });
}
$('addWebDav').onclick = () => connectionDialog('webdav');
$('addLocal').onclick = () => connectionDialog('local');
$('refreshSources').onclick = () => load();
let backupToken = null, backupDirectoryIdentity = null, backupEpoch = 0;
function invalidateBackup() {
  ++backupEpoch; const old=backupToken; backupToken=null;backupDirectoryIdentity=null;
  $('backupPreview').replaceChildren();$('backupRows').replaceChildren();$('backupRestore').disabled=true;
  if(old && ![activeWrite,unresolvedWrite,lastWrite].some(op=>op?.path==='/api/backups/restore' && op.restoreToken===old))void post('/api/backups/cancel',{token:old}).catch(()=>{});
}
$('workspaceSelect').addEventListener('change',invalidateBackup);
const backupLocation = () => ({ rootId: $('backupRoot').value, relative: $('backupRelative').value, directoryIdentity: backupDirectoryIdentity });
async function backupJob(action) {
  if (backupBusy) return;
  backupBusy=true; const job=++backupJobId, scope=workspaceId;
  const buttons=lockControls('backups'); $('backupStatus').textContent='正在处理…';
  try { await action(); }
  catch(e) { if (!obsoleteView(e) && scope===workspaceId && job===backupJobId) $('backupStatus').textContent=e.message; }
  finally {
    if(job!==backupJobId)return;
    backupBusy=false; $('backupPassword').value='';
    unlockControls(buttons);
    $('backupRestore').disabled=!backupToken;
  }
}
function renderBackupPreview(p) {
  const root = $('backupPreview'); root.replaceChildren();
  root.append(el('h3', '已验证的备份内容'), el('p', `${new Date(p.createdAt).toLocaleString()} · FPK ${p.appVersion} · 格式 ${p.formatVersion} · ${p.workspaceCount} 个工作区 · ${p.fileCount} 个文件 · ${capacity(p.bytes)}`), el('p', p.coverage));
  for (const d of p.dependencies) {
    const row = el('article', null, 'course');
    row.append(el('strong', d.workspaceName), el('p', `${d.courseCount} 门课程 · ${d.pending ? '有待确认候选' : '无候选'} · ${d.conflict ? '有未解决冲突' : '无冲突'}`), el('p', d.included ? '已包含：应用状态和私有本地提交' : d.type === 'unbound' ? '已包含：离线应用状态；未关联外部数据' : '需外部访问：' + (d.type === 'webdav' ? d.endpoint : d.endpoint.relative || '授权目录')));
    const status = p.external?.find(x => x.workspaceId === d.workspaceId);
    if (status) row.append(el('p', status.verified ? status.status : '外部数据暂不可验证：' + status.reason));
    const details = el('details'); details.append(el('summary', '身份、已知历史与依赖详情'), el('pre', JSON.stringify(d, null, 2))); row.append(details); root.append(row);
  }
  if (p.externalStatus) root.append(el('p', p.externalStatus));
}
async function browseBackups() {
  invalidateBackup(); const ticket=backupEpoch, scope=workspaceId, location=backupLocation();
  const current=()=>ticket===backupEpoch && scope===workspaceId;
  const result = await post('/api/backups/list', location); if(!current())return;
  backupDirectoryIdentity = result.directoryIdentity; location.directoryIdentity=result.directoryIdentity;
  $('backupRows').replaceChildren(...result.backups.map(b => {
    const row = el('article', null, 'course');
    row.append(el('strong', b.name), el('p', `${capacity(b.size)} · 文件时间 ${new Date(b.modifiedAt).toLocaleString()} · ${b.status}`), button('验证并预览恢复', () => backupJob(async () => {
      if(!current())return;
      const old=backupToken; backupToken=null; $('backupRestore').disabled=true; $('backupPreview').replaceChildren();
      if(old)await post('/api/backups/cancel',{token:old});
      const preview = await post('/api/backups/verify', { ...location, name: b.name, password: $('backupPassword').value });
      if(!current()){void post('/api/backups/cancel',{token:preview.token}).catch(()=>{});return;}
      backupToken = preview.token;
      renderBackupPreview(preview);
      $('backupStatus').textContent = '认证解密、文件 SHA-256 和应用状态验证通过。请检查覆盖范围与外部依赖。';
    })));
    return row;
  }));
  $('backupStatus').textContent = `${result.backups.length} 个加密备份；未输入口令的文件不标为已验证。` + (result.lastRestore?.ok ? ' 最近恢复完成：' + new Date(result.lastRestore.restoredAt).toLocaleString() : '');
}
$('backupsPage').onclick = () => { navigate('backups'); return backupJob(async () => {
  await loadManagement();
  const old = $('backupRoot').value;
  $('backupRoot').replaceChildren(...management.roots.filter(r => r.id !== 'private').map(r => { const o = el('option', r.name); o.value = r.id; return o; }));
  if ([...$('backupRoot').options].some(o => o.value === old)) $('backupRoot').value = old;
  else $('backupRelative').value='';
  await browseBackups();
}); };
$('backupRoot').onchange = () => { invalidateBackup(); return backupJob(async () => { $('backupRelative').value = ''; await browseBackups(); }); };
$('backupChoose').onclick = async () => {
  const chosen=await pickLocalFolder('选择备份文件夹',backupLocation());if(!chosen)return;
  invalidateBackup();$('backupRoot').value=chosen.rootId;$('backupRelative').value=chosen.relative;await backupJob(browseBackups);
};
$('backupBrowse').onclick = () => backupJob(browseBackups);
$('backupCreate').onclick = () => backupJob(async () => {
  const ticket=backupEpoch,scope=workspaceId;
  const result = await post('/api/backups/create', { ...backupLocation(), password: $('backupPassword').value });
  if(ticket!==backupEpoch || scope!==workspaceId)return;
  await browseBackups(); renderBackupPreview(result); $('backupStatus').textContent = '备份已保存并从实际文件重新认证验证。请妥善保管口令。';
});
$('backupRestore').onclick = () => backupJob(async () => {
  const token = backupToken, ticket=backupEpoch, scope=workspaceId;
  if(!token)return;
  const confirm = await requestText('将用已验证备份替换当前全部应用工作区和连接。恢复前状态会保留，外部历史不会改写。输入“恢复应用数据”确认：','','恢复应用数据');
  if (confirm !== '恢复应用数据') {
    await post('/api/backups/cancel', { token }); backupToken = null;
    $('backupStatus').textContent = '已取消恢复并撤销预览'; return;
  }
  if(ticket!==backupEpoch || scope!==workspaceId || token!==backupToken)throw Error('目录或工作区已变化，请重新验证备份');
  const result = await post('/api/backups/restore', { token, confirm });
  backupToken = null; workspaceId = undefined; await load(); $('backupStatus').textContent = result.result;
});

const generatedLocations=[];
$('historyChoose').onclick=async()=>{
  const chosen=await pickLocalFolder('选择历史导出目标目录',{rootId:$('historyRoot').value,relative:$('historyRelative').value});
  if(chosen){$('historyRoot').value=chosen.rootId;$('historyRelative').value=chosen.relative;}
};
$('historyRoot').onchange=()=>{$('historyRelative').value='';};
async function historyJob(action) {
  if(historyBusy)return;
  historyBusy=true; const job=++historyJobId;
  const scope=workspaceId, page=viewEpoch;
  const buttons=lockControls('history');
  try { await action(); }
  catch(e) {
    if (!obsoleteView(e) && scope===workspaceId && job===historyJobId && page===viewEpoch) {
      $('historyStatus').textContent=e.message+'。请查看操作状态与当前课程；不自动重发写操作。'; historyPreview=null;
      $('historyRows').append(button('刷新当前课表与历史',()=>historyJob(async()=>{await load();await historyRead();})));
    }
  } finally {
    if(job!==historyJobId)return;
    historyBusy=false;
    unlockControls(buttons);
    $('historyConfirm').parentElement.hidden=!historyPreview;
    $('historyConfirm').disabled=!historyPreview || workspaceId!==historyScope || historyPreview.unchanged;
    $('historyPreview').disabled=!historyEligible || !$('historyTo').value || $('historyTo').value==='current';
    $('historyDiff').disabled=!$('historyFrom').value || !$('historyTo').value;
    const bound=!!management?.workspaces.find(w=>w.id===workspaceId)?.datasetId;
    $('historyArchive').disabled=!bound;
    $('historyCompact').disabled=activeProtocol==='v3' || !bound;
  }
}
async function versionContents(id) {
  const ticket=++historyOutputEpoch;
  $('historyOutput').replaceChildren(el('h3','版本内容 · '+id.slice(0,8)));
  for(const [table,label] of Object.entries(tableLabels)) {
    const group=el('section');group.append(el('h4',label));$('historyOutput').append(group);
    const read=async offset=>{
      const v=await get('/api/history/snapshot?'+new URLSearchParams({id,table,offset}));
      if(ticket!==historyOutputEpoch || !group.isConnected)return;
      group.append(el('p',`共 ${v.total} 条记录`),...v.rows.map(r=>historyRecord(table,r)));
      if(v.next!==null)group.append(button('继续查看',e=>historyJob(async()=>{const trigger=e.currentTarget;await read(v.next);trigger.remove();})));
    };
    group.append(button('展开此集合',e=>historyJob(async()=>{const trigger=e.currentTarget;await read(0);trigger.remove();})));
  }
  revealHistoryOutput();
}
async function compareVersion(id, target='current') {
  historyPreview=null; const ticket=++historyOutputEpoch;
  const read=async offset=>{
    const d=await get('/api/history/diff?'+new URLSearchParams({from:id,to:target,offset}));
    if(ticket!==historyOutputEpoch)return;
    renderHistoryDiff(d,offset>0,read);
    if(!offset){$('historyOutput').prepend(el('p',`比较方向：${id==='current'?'当前课程表':id.slice(0,8)} → ${target==='current'?'当前课程表':target.slice(0,8)}。新增与删除均相对左侧起始内容。`));revealHistoryOutput();}
  }; await read(0);
}
function versionActions(row,id) {
  row.append(button('查看版本内容',()=>historyJob(()=>versionContents(id))),
    button('与当前课程表比较',()=>historyJob(()=>compareVersion(id))),
    button('预览恢复到此版本',()=>{ $('historyTo').value=id; return $('historyPreview').onclick(); }));
  row.lastChild.disabled=!historyEligible;
}
function revealHistoryOutput() {const output=$('historyOutput');output.tabIndex=-1;output.scrollIntoView({block:'start'});output.focus({preventScroll:true});}
function historyJSON(value) { return el('pre', JSON.stringify(value, null, 2)); }
function historyRecord(table, r) {
  const card = el('article', null, 'source-card');
  if (typeof r === 'string') { card.append(el('strong', r)); return card; }
  const clock = n => n == null ? '未设置' : String(Math.floor(n/60)).padStart(2,'0')+':'+String(n%60).padStart(2,'0');
  const instant = n => n == null ? '未设置' : new Date(Number(n)).toLocaleString('zh-CN', {timeZone:$('zone').value});
  card.append(el('strong', r.title ?? r.displayName ?? r.termKey ?? r.date ?? r.key ?? r.id));
  if (table === 'entries') card.append(el('p', `每周${r.day} · ${clock(r.startMinute)}–${clock(r.endMinute)} · ${r.location || '未设置教室'}`));
  if (table === 'events' || table === 'imported') card.append(el('p', `${instant(r.start)} → ${instant(r.end)} · ${r.location ?? ''}`));
  if (table === 'imported') card.append(el('p', `来源 ${r.sourceId} · 系列 ${r.seriesId} · 原始发生 ${instant(r.originalStart)} · ${r.timezone}`));
  if (table === 'sources') card.append(el('p', `${r.filename} · ${r.occurrenceCount} 个原始实例 · 学期 ${r.termKey ?? '按来源推导 / 未关联'}；关联覆盖在覆盖集合中保留。`));
  if (table === 'classOverrides') card.append(el('p', `${r.anchorDate === null ? '全周覆盖' : '指定周 '+r.anchorDate} · ${r.date ? '调整到 '+r.date : r.weekday ? '改为周'+r.weekday : '保留原日期'} · ${r.timingMode === 'linked' ? '关联节次 '+r.periodStart+'–'+r.periodEnd : r.startMinute != null ? clock(r.startMinute)+'–'+clock(r.endMinute) : '保留原时间'} · ${r.location ?? '保留原教室'}`));
  if (table === 'dayAdjustments') card.append(el('p', `${r.mode === 'none' ? '停课' : '按周'+r.weekday+'补课'} · ${r.label}`));
  if (table === 'profiles') card.append(el('p', `第一周周一 ${r.week1Monday} · ${r.timezone} · ${r.phases.length} 个作息阶段（节次完整字段见详情）`));
  const fields = el('details'); fields.append(el('summary','完整字段与业务身份'),historyJSON(r)); card.append(fields);return card;
}

function renderHistoryDiff(d, append = false, more = null) {
  const output = $('historyOutput'); if (!append) output.replaceChildren();
  if (!append) {
    output.append(el('h3', '目标状态相对起始状态的变化'));
    for (const c of Object.values(d.counts)) if (c.added + (c.restored ?? 0) + c.deleted + c.modified) output.append(el('p', `${c.label}：新增 ${c.added} · 恢复 ${c.restored ?? 0} · 删除 ${c.deleted} · 修改 ${c.modified}`));
    for (const g of d.groups ?? []) output.append(el('p', `关联组「${g.title}」${g.termKey ? ' · 学期 '+g.termKey : ''}：新增 ${g.added} · 恢复 ${g.restored ?? 0} · 删除 ${g.deleted} · 修改 ${g.modified}`));
    output.append(el('p', `共 ${d.total} 条记录变化。来源、原始实例和覆盖通过关联组标识对应；来源替换须一起检查这些关联。`));
  }
  for (const r of d.rows) {
    const row = el('details'); row.append(el('summary', `${r.label} · ${{added:'新增',restored:'恢复',deleted:'删除',modified:'修改'}[r.action]} · ${r.title}`), el('p', '关联组：' + r.group), el('p','变化前'), r.before === undefined ? el('p','无此记录') : historyRecord(r.table,r.before), el('p','目标状态'), r.after === undefined ? el('p','删除此记录') : historyRecord(r.table,r.after)); output.append(row);
  }
  if (d.next !== null && more) output.append(button('继续查看下 40 条', e => { e.currentTarget.remove(); return historyJob(() => more(d.next)); }));
}
let v3HistoryFilters = {};
async function historyReadV3() {
  const ticket=++historyReadEpoch, scope=workspaceId;
  historyPreview=null;historyScope=scope;historyEligible=false;++historyOutputEpoch;
  $('historyOutput').replaceChildren(); $('historyRows').replaceChildren();
  const business=$('historyView').value==='business';
  $('historyFilters').hidden=business;
  $('historyExportTitle').textContent='共享历史归档';
  $('historyExportHint').textContent='独立复制冻结索引中的已验证对象并实际回读。覆盖范围以归档验证证据为准；不代表未知远端历史已归档。归档目录只供授权管理员使用。';
  $('historyCompactHint').hidden=true;
  $('datasetMigration')?.remove();
  for(const key of ['historyFrom','historyTo']) { const o=el('option','当前课程表');o.value='current';$(key).replaceChildren(o); }
  const pageHost=$('historyRows'), cursors=[0];
  const read=async(after=0)=>{
    const h=await get('/api/history?'+new URLSearchParams({...(!business?v3HistoryFilters:{}),view:business?'business':'evidence',after,limit:40}));
    if(ticket!==historyReadEpoch || scope!==workspaceId)throw new StaleResponse();
    version=h.version;historyEligible=h.rollbackEnabled;
    pageHost.replaceChildren();
    for(const key of ['historyFrom','historyTo']){const o=el('option','当前课程表');o.value='current';$(key).replaceChildren(o);}
    $('historyStatus').textContent=`已知历史 · ${h.frontier.length} 个当前前沿。`+(h.blockers.length?'恢复前请处理：'+h.blockers.join('；'):'可查看版本、比较或预览恢复。');
    if(!business)$('historyStatus').textContent+=` 索引 ${h.count} 项。仅本机已验证证据；旧历史审计、远端全目录和服务器停写未获证明。`;
    const jobs=el('section');
    for(const job of h.archiveJobs??[]) if(job.status!=='VERIFIED') jobs.append(button('继续归档 '+job.id.slice(0,8),()=>historyJob(async()=>{
      const result=await post('/api/history/archive',{jobId:job.id,rootId:job.location.rootId});
      await historyReadV3();showMaintenance(result);
    })));
    if(after===0)$('localMaintenance').replaceChildren(maintenanceControls(),jobs);
    for(const r of h.rows) {
      // The server projection is bounded; also keep old-server evidence out of the business list.
      if(business && (![2,3].includes(r.type) || !r.indexVerified))continue;
      const row=el('article',null,'course');
      const parents=(r.refs??[]).filter(ref=>ref.role===1), frontier=h.frontier.includes(r.path)||h.frontier.includes(r.id);
      row.append(el('strong',business?`版本 ${r.id.slice(0,8)}${frontier?' · 当前已知前沿':''}${parents.length>1?' · 多父合并版本':''}`:`${r.status} · ${['','Descriptor','Genesis / Checkpoint','Commit','Transition'][r.type]??'索引项'}`),
        el('p',parents.length?'已验证父版本：'+parents.map(ref=>ref.objectId.slice(0,8)).join('、'):'无直接父版本；跨代继承关系见证据'),
        el('small','本机验证时间：'+(r.verifiedAt?new Date(r.verifiedAt).toLocaleString():'未知')));
      if(r.reason || r.anomaly?.reason)row.append(el('p',r.reason??r.anomaly.reason,'warning'));
      const detail=el('details');detail.append(el('summary','查看完整诊断证据（RAW 身份与引用）'),historyJSON(r));row.append(detail);
      if([2,3].includes(r.type) && r.indexVerified) {
        for(const key of ['historyFrom','historyTo']) {const o=el('option','版本 '+r.id.slice(0,8)+(frontier?' · 前沿':''));o.value=r.id;$(key).append(o);}
        versionActions(row,r.id);
        for(const parent of parents)row.append(button('比较父版本 '+parent.objectId.slice(0,8)+' → 此版本',()=>historyJob(()=>compareVersion(parent.objectId,r.id))));
      }
      pageHost.append(row);
    }
    if(!h.rows.length)pageHost.append(el('p',h.next!==null?'这一页没有可展示的业务版本；继续读取后续索引。':'尚无可展示的已验证版本。可检查远端历史；本地课程不会被清空。'));
    if(after>0)pageHost.prepend(button('上一页',()=>historyJob(async()=>{const i=cursors.indexOf(after);await read(cursors[Math.max(0,i-1)]);})));
    if(h.next!==null && !cursors.includes(h.next))cursors.push(h.next);
    if(h.next!==null)pageHost.append(button('继续下一页',e=>historyJob(async()=>{const trigger=e.currentTarget;await read(h.next);trigger.remove();})));
    pageHost.dataset.after=String(after);
  };
  try { await read(); }
  catch(e) { if(e instanceof StaleResponse)throw e;pageHost.append(button('重试版本列表',()=>historyJob(historyReadV3)));throw e; }
  $('historyArchive').textContent='归档并回读验证';
}
function showMaintenance(result) {
  const output=$('historyOutput');output.replaceChildren(el('h3','维护结果'));
  for(const [key,label] of Object.entries({estimatedReusableBytes:'预计 SQLite 内部可复用',reusableBytes:'内部可复用',reclaimedFileBytes:'实测文件空间释放',reclaimedAllocatedFileBytes:'实测文件分配量减少'}))if(result[key]!=null)output.append(el('p',label+'：'+capacity(result[key])));
  if(result.location)output.append(el('p','归档位置：'+(management?.roots.find(r=>r.id===result.location.rootId)?.name??result.location.rootId)+' / '+result.location.relative));
  if(result.status)output.append(el('p','任务状态：'+({COPYING:'复制并回读中，可继续',VERIFIED:'冻结范围已回读验证',COMPLETED:'本机整理完成'}[result.status]??result.status)));
  if(result.pageCount!=null)output.append(el('p',`已保存 ${result.pageCount} 页归档清单${result.buffer?.length?'，另有 '+result.buffer.length+' 个已回读对象等待写入清单':''}。覆盖范围限任务开始时冻结的已验证历史；未验证对象与后来出现的历史不在完成声明内。`));
  if(result.validationPending || result.anomalies || result.scanPending)output.append(el('p',`未覆盖的待验证对象 ${result.validationPending??0}，异常 ${result.anomalies??0}，待扫描范围 ${result.scanPending??0}。`,'warning'));
  if(result.message)output.append(el('p',result.message));
  output.append(el('p','本机整理不会删除远端对象。SQLite 可复用页不等于 NAS 文件空间已经释放。'));
  const details=el('details');details.append(el('summary','查看完整诊断证据'),historyJSON(result));output.append(details);revealHistoryOutput();
}
function maintenanceControls() {
  const controls=el('section');controls.append(el('h3','空间使用与本机整理'),
    button('空间使用与保护范围',()=>historyJob(async()=>{
      const r=await get('/api/history/space');showMaintenance(r);
      for(const [key,n] of Object.entries(r.bytes??{}))$('historyOutput').prepend(el('p',({sqlite:'本机数据库',cacheBlobs:'对象缓存',witness:'身份与保护证据',damaged:'隔离损坏材料',otherProtected:'其他受保护文件'}[key]??key)+'：'+capacity(n)));
      $('historyOutput').append(el('p','受保护：'+(r.protected??[]).join('、')),el('p','已登记归档任务：'+r.archives));
    })),button('预览本机冗余快照整理',()=>historyJob(async()=>{
      const p=await post('/api/history/cleanup-preview');showMaintenance(p);
      if(p.candidates.length && await confirmAction(`将整理 ${p.candidates.length} 个已证明无引用的本机快照。预计内部可复用 ${capacity(p.estimatedReusableBytes)}；实际文件释放量以结果为准。`,'整理本机冗余快照'))showMaintenance(await post('/api/history/cleanup',{token:p.token,confirm:'整理本地冗余快照'}));
    })),button('从独立归档恢复缺失缓存',()=>historyJob(async()=>{
      const scope=workspaceId, chosen=await pickLocalFolder('选择包含 verified.json 的独立归档目录',{rootId:$('historyRoot').value,relative:$('historyRelative').value});if(!chosen)return;
      const restore=async()=>{
        if(scope!==workspaceId)throw new StaleResponse();
        const r=await post('/api/history/archive-restore',{rootId:chosen.rootId,relative:chosen.relative});showMaintenance(r);
        if(!r.complete)$('historyOutput').append(button('继续恢复并验证此归档',()=>historyJob(restore)));
      };await restore();
    })));
  return controls;
}
async function historyRead() {
  for (const id of ['historyDiff','historyPreview','historyConfirm','historyArchive','historyCompact']) $(id).disabled = activeProtocol === 'v3';
  if (activeProtocol === 'v3') return historyReadV3();
  $('historyFilters').hidden=true; $('localMaintenance').replaceChildren();
  const ticket=++historyReadEpoch;
  $('historyExportTitle').textContent='完整已知 v2 历史归档与精简候选';
  $('historyExportHint').textContent='复制本轮已验证的 DPC2 原始字节；不包含应用私有课程和凭据。不会删除、移动或改写旧库。';
  $('historyCompactHint').hidden=false;
  void loadMigrationTools();
  historyPreview = null; historyEligible = false; historyScope = workspaceId; $('historyOutput').replaceChildren();
  const h = await get('/api/history'); if (historyScope !== workspaceId || ticket!==historyReadEpoch) throw new StaleResponse();
  version = h.version;
  $('historyStatus').textContent = `${h.count} / ${h.capacity} 个已验证提交 · 原始字节 ${capacity(h.bytes)} · 前沿 ${h.tips.length} · 缓存视图，需主动检查远端。${h.warning ?? ''} ${h.error ?? ''} ${h.blockers.join('；')}`;
  $('historyRows').replaceChildren();
  const renderPage=(offset=0)=>{
  $('historyRows').replaceChildren();
  for (const r of h.rows.slice(offset,offset+40)) {
    const row = el('article', null, 'course');
    row.append(el('strong', `因果层 ${r.level} · 版本 ${r.id.slice(0,8)}${r.tip ? ' · 当前前沿' : ''}${r.matchesCurrent ? ' · 与当前课程相同' : ''}${r.parents.length > 1 ? ' · 合并版本' : ''}`));
    row.append(el('p', `版本 ${r.id.slice(0,8)} · ${r.parents.length ? '← 父版本 '+r.parents.map(id => id.slice(0,8)).join('、') : '根版本'} · ${h.rows.filter(x => x.parents.includes(r.id)).length > 1 ? '分叉点' : '因果链'}`));
    const comparisons = r.comparisons ?? [{ parentId: r.parents[0] ?? null, changes: r.changes, total: r.total }];
    for (const comparison of comparisons) {
      row.append(el('p', `相对${comparison.parentId ? '父版本 '+comparison.parentId.slice(0,8) : '空快照（根版本）'}：${comparison.total} 条变化`));
      for (const c of Object.values(comparison.changes)) if (c.added+c.deleted+c.modified) row.append(el('small', `${c.label}：+${c.added} / −${c.deleted} / 修改 ${c.modified}；`));
    }
    const detail = el('details'); detail.append(el('summary', '高级校验详情'), historyJSON(r)); row.append(detail);
    historyEligible=h.rollbackEnabled; versionActions(row,r.id);
    $('historyRows').append(row);
  }
  if(offset>0)$('historyRows').prepend(button('上一页',()=>renderPage(Math.max(0,offset-40))));
  if(offset+40<h.rows.length)$('historyRows').append(button('继续下一页',e=>{e.currentTarget.remove();renderPage(offset+40);}));
  };renderPage();
  for (const key of ['historyFrom','historyTo']) $(key).replaceChildren(...h.rows.map(r => { const o = el('option', `层 ${r.level} · ${r.id.slice(0,8)}${r.tip ? ' · 前沿' : ''}`); o.value = r.id; return o; }));
  for(const key of ['historyFrom','historyTo']){const o=el('option','当前课程表');o.value='current';$(key).prepend(o);}
  historyEligible = h.rollbackEnabled; $('historyPreview').disabled = !historyEligible;
}
$('historyPage').onclick = () => { navigate('history'); return historyJob(async () => {
  await loadManagement();
  const oldRoot=$('historyRoot').value;
  $('historyRoot').replaceChildren(...management.roots.filter(r => r.id !== 'private').map(r => { const o = el('option',r.name); o.value = r.id; return o; }));
  if([...$('historyRoot').options].some(o=>o.value===oldRoot))$('historyRoot').value=oldRoot;else $('historyRelative').value='';
  await historyRead();
}); };
$('historyCheck').onclick = () => historyJob(async () => { await post('/api/history/check'); await historyRead(); });
$('historyDiff').onclick = () => historyJob(()=>compareVersion($('historyFrom').value,$('historyTo').value));
$('historyPreview').onclick = () => historyJob(async () => {
  if(!historyEligible || !$('historyTo').value || $('historyTo').value==='current')return;
  const target=$('historyTo').value, scope=workspaceId, page=viewEpoch, outputTicket=++historyOutputEpoch;
  const result=await post('/api/history/preview',{target});
  if(scope!==workspaceId || page!==viewEpoch || target!==$('historyTo').value || outputTicket!==historyOutputEpoch)throw new StaleResponse();
  historyPreview=result;
  const preview = historyPreview;
  const more = async offset => {const d=await get(`/api/history/preview-diff?token=${preview.token}&offset=${offset}`);if(historyPreview===preview && outputTicket===historyOutputEpoch)renderHistoryDiff(d,true,more);};
  renderHistoryDiff(preview.diff, false, more);
  $('historyOutput').prepend(el('h3','回退预览：当前课程表 → 版本 '+preview.target.slice(0,8)));
  revealHistoryOutput();
  $('historyOutput').append(el('p','回退中的新增会重新带回目标版本的记录及其原始身份；不会按课程名称创建副本。'));
  $('historyStatus').textContent = historyPreview.unchanged ? '目标与当前相同，不会创建提交。' : '请检查新增 / 恢复、修改与删除。确认时将重新验证远端和全部输入。';
});
for (const key of ['historyFrom','historyTo','workspaceSelect']) $(key).addEventListener('change', () => { historyPreview = null; ++historyOutputEpoch; $('historyConfirm').disabled = true; $('historyConfirm').parentElement.hidden=true; $('historyPreview').disabled=!historyEligible || !$('historyTo').value || $('historyTo').value==='current'; if (key === 'workspaceSelect') $('history').hidden = true; });
$('historyConfirm').onclick = () => historyJob(async () => {
  const p = historyPreview, scope = workspaceId;
  if (!p || scope !== historyScope) throw Error('工作区已变化，请刷新');
  if (!await confirmAction('将以此完整历史快照发布新版本，预览中的删除也会生效。旧提交仍然保留。', '确认回退')) return;
  if(p!==historyPreview || scope!==workspaceId)throw Error('预览已失效，请重新生成');
  await post('/api/history/rollback', { token:p.token, target:p.target, confirm:p.target }); historyPreview = null;
  await load(); await historyRead(); $('historyStatus').textContent += ' 回退处理结果：'+$('status').textContent+'。其他设备接收情况未知。';
});
for (const [id, action] of [['historyArchive','archive'],['historyCompact','compact']]) $(id).onclick = () => historyJob(async () => {
  historyPreview = null;
  if (!await confirmAction(action === 'compact' ? '将先完整归档，再在另一个新目录准备单根数据集。不会切换客户端或删除旧库。' : activeProtocol === 'v3' ? '将冻结索引中已验证的对象及已知引用复制到独立目录并实际回读。未知路径、旧 Writer 和服务端封存仍未证明。' : '将完整课程历史复制到受保护的新目录，请保护 NAS 授权。', '确认导出')) return;
  const result = await post('/api/history/' + action, { rootId:$('historyRoot').value, relative:$('historyRelative').value });
  if(result.candidate)generatedLocations.push({rootId:$('historyRoot').value,relative:result.candidate});
  if(result.candidate)await migrationRead();
  if(activeProtocol==='v3' && result.status!=='VERIFIED')await historyReadV3();
  showMaintenance(result); $('historyStatus').textContent = result.message;
});

let migrationReadEpoch=0;
async function loadMigrationTools() {
  const ticket=++migrationReadEpoch,scope=workspaceId,page=viewEpoch;
  try {await migrationRead(()=>ticket===migrationReadEpoch && scope===workspaceId && page===viewEpoch);}
  catch(e) {
    if(e instanceof StaleResponse || ticket!==migrationReadEpoch || scope!==workspaceId || page!==viewEpoch)return;
    let panel=$('datasetMigration');if(!panel){panel=el('section');panel.id='datasetMigration';$('advancedHistory').append(panel);}
    panel.replaceChildren(el('h3','高级精简换库工具'),el('p','迁移信息暂不可用：'+e.message+'。普通历史仍可浏览；现有迁移保护保持不变。','warning'),button('重试读取迁移工具',loadMigrationTools));
  }
}
async function migrationRead(current=()=>true) {
  let panel = $('datasetMigration');
  if (!panel) { panel = el('section'); panel.id = 'datasetMigration'; $('advancedHistory').append(panel); }
  const result = await get('/api/migration');
  if(!current())throw new StaleResponse();
  panel.replaceChildren(el('h3', '迁移到精简数据集'), el('p', result.warning));
  const form = el('div');
  const candidate = el('input'); candidate.readOnly=true; candidate.placeholder = '选择已生成的精简候选目录'; candidate.id = 'migrationCandidate'; candidate.setAttribute('aria-label', '精简候选相对目录');
  let candidateLocation=null;
  const chooseCandidate=button('选择精简候选目录',async()=>{
    const scope=workspaceId;
    const parent={kind:'local',rootId:$('historyRoot').value,relative:$('historyRelative').value};
    const recent=generatedLocations.filter(l=>management.roots.some(r=>r.id===l.rootId));
    // Rediscover immediate compact directories after reload; names are suggestions,
    // never verification evidence. No recursive scan or manifest writes.
    try {
      const listing=await localListing(parent);
      for(const name of listing.directories.filter(n=>/^compact-/.test(n)).slice(0,64)) {
        const location={rootId:parent.rootId,relative:[parent.relative,name].filter(Boolean).join('/')};
        if(!recent.some(l=>l.rootId===location.rootId && l.relative===location.relative))recent.push(location);
      }
    }catch { /* The authorized browser offers retry and other roots below. */ }
    if(scope!==workspaceId || !candidate.isConnected)return;
    let chosen=recent.length?await chooseOption('选择已生成候选或浏览授权目录',[
      ...recent.map(l=>({label:l.relative+'（待后端验证）',value:l})),{label:'浏览其他授权目录',value:'browse'}]):'browse';
    if(chosen==='browse')chosen=await pickLocalFolder('选择 compact 候选目录（后端仍会验证归档和身份）',{rootId:$('historyRoot').value,relative:$('historyRelative').value});
    if(chosen && scope===workspaceId && candidate.isConnected){candidateLocation=chosen;candidate.value=chosen.relative;}
  });
  const connection = el('select'); connection.id = 'migrationConnection'; connection.setAttribute('aria-label', 'Android 实际使用的目标 WebDAV 连接');
  const management = await get('/api/manage');
  if(!current())throw new StaleResponse();
  connection.append(...management.connections.filter(c => c.type === 'webdav').map(c => { const o = el('option', `${c.name} · ${c.url}`); o.value = c.id; return o; }));
  const job = async data => {
    await post('/api/migration', data);
    // 切换会由后端改变活动工作区；重新读取它，避免继续携带旧工作区身份。
    if (data.action === 'switch') workspaceId = undefined;
    await load();
    await historyRead();
  };
  form.append(el('p', '1. 先用上方功能归档并准备候选，再添加指向该候选的独立 WebDAV 连接。选择 Android 实际使用的地址，后端将通过它读取全图。准备成功即保护新旧工作区；重启后从本页继续。'), candidate, chooseCandidate, connection,
    button('检查归档与候选，准备换库', () => historyJob(() => { if(!candidateLocation)throw Error('请先选择精简候选目录'); return job({ action:'prepare',rootId:candidateLocation.rootId,candidateRelative:candidateLocation.relative,targetConnectionId:connection.value }); })));
  panel.append(form);
  for (const m of result.records) {
    const card = el('article'); card.append(el('h4', `阶段：${m.phase}`), el('p', `旧库 ${m.sourceDatasetId} → 新库 ${m.targetDatasetId}`), el('p', `旧 ${m.oldCount} 个提交 → 新 ${m.newCount} 个根。旧目录原始 DPC 可回收估算 ${m.reclaimEstimateBytes} 字节（仅旧副本，不含保留归档）；实际已回收 0 字节。`), el('p', `最后检查：${m.lastCheck}`));
    if (m.failure) card.append(el('p', `失败：${m.failure}。恢复网络/正确原始字节后重新检查；禁止跨 dataset 合并。`));
    card.append(el('p', `归档位置：${m.archive.relative}。旧工作区保留完整缓存、身份与历史。`));
    card.append(el('p', `旧图实际观察到的 Writer：${(m.sourceWriters ?? []).join('、')}。这不表示全部客户端；同一 Writer 可能有多份安装副本，管理员须另行登记全部设备。`));
    const action = (name, data = {}) => job({ action: name, migrationId: m.id, migrationVersion: m.revision, ...data });
    const steps = [ ['prepared', '2–4. 已登记所有已知 Writer，确认停写', 'freeze', m.sourceDatasetId], ['frozen', '5. 切换 FPK（仍保持新旧停写）', 'switch', m.targetDatasetId], ['switching', '恢复 FPK 切换', 'switch', m.targetDatasetId], ['switched', '6–7. 已逐台验收，标记旧库退役并开放新库', 'retire', m.sourceDatasetId] ];
    for (const [phase, label, name, identity] of steps) if (m.phase === phase) card.append(button(label, () => historyJob(async () => {
      const confirm = await requestText(`请输入 ${identity} 确认。管理员勾选不证明全部设备永久停写。`);
      if (confirm !== null) await action(name, { confirm });
    })));
    card.append(button('重新检查两库、归档与目标地址', () => historyJob(() => action('recheck'))));
    if (!['retired','cancelled'].includes(m.phase)) card.append(button('取消（切换后不覆盖旧库）', () => historyJob(async () => {
      if (await confirmAction('取消后仅原工作区恢复写入；保留新工作区与恢复证据。确认所有设备仍停写，尚未在新库独立编辑？')) await action('cancel', { confirm: m.sourceDatasetId, writersStopped: true });
    })));
    const list = el('ul');
    for (const d of m.devices) list.append(el('li', `${d.name} / ${d.type} · 停写确认 ${d.stopped ? '是' : '否'} · 新库验收 ${d.accepted ? '是' : '未完成'} · ${d.origin} · ${d.evidence} · 最后登记 ${d.lastCheck?.at ?? '未记录'} ${d.lastCheck?.status ?? ''}`));
    card.append(el('p', '设备清单（人工维护；Android 本机结果请在设备换库页查看后记录）：'), list);
    if (!['retired','cancelled'].includes(m.phase)) {
      const name = el('input'); name.placeholder = '设备名称（同名可更新登记）'; name.setAttribute('aria-label', '迁移设备名称');
      const type = el('select'); for (const label of ['X08E','Phone','其他']) { const o = el('option', label); o.value = label; type.append(o); }
      const stopped = el('input'); stopped.type = 'checkbox'; const accepted = el('input'); accepted.type = 'checkbox';
      const stopLabel = el('label', '管理员确认已停写旧库'); stopLabel.append(stopped);
      const acceptLabel = el('label', '管理员已核对 Android 本机结果'); acceptLabel.append(accepted);
      const evidence = el('input'); evidence.placeholder = '本机阶段、datasetId、Writer 及验收记录'; evidence.setAttribute('aria-label', '本机迁移验收记录');
      card.append(name, type, stopLabel, acceptLabel, evidence, button('登记 / 更新设备', () => historyJob(() => action('device', { name: name.value, type: type.value, stopped: stopped.checked, accepted: accepted.checked, evidence: evidence.value }))));
    }
    card.append(el('p', '人工清理指导：全部客户端完成换库、NAS 管理员已在存储或服务端禁止旧目录写入、完整归档已复制到独立安全位置并验证后，再由管理员在 fnOS 文件管理器人工处理旧目录。先保留本日志及全部哈希，核对目录身份，禁止删除归档或新库；本应用不执行 WebDAV 批量 DELETE。'));
    panel.append(card);
  }
}

async function loadAgentAccess() {
  const r = await fetch(base + '/api/agent');
  const state = await r.json(); if (!r.ok) throw Error(state.error);
  const panel = $('agentPanel'); panel.replaceChildren();
  const choices = [];
  for (const permission of ['edit', 'admin', 'maintenance']) {
    const label = el('label', permission + ' '), box = document.createElement('input');
    box.type = 'checkbox'; box.checked = state.permissions.includes(permission); label.append(box); panel.append(label); choices.push([permission, box]);
  }
  const save = el('button', '保存 Agent 权限');
  save.onclick = async () => {
    try {
      if (!await confirmAction('授予的权限适用于所有以可信包用户连接的本地客户端。高影响操作仍需独立批准。')) return;
      await post('/api/agent', { action:'permissions', permissions:choices.filter(([,box]) => box.checked).map(([p]) => p) }); await loadAgentAccess();
    } catch (e) { await notifyUser(e.message); }
  }; panel.append(save);
  for (const p of state.approvals) {
    const item = el('article'), summary = el('pre', JSON.stringify({ operation:p.operation, operationId:p.operationId, summary:p.summary, expires:new Date(p.expires).toLocaleString(), approvedBy:p.approvedBy, consumed:p.consumed }, null, 2));
    item.append(summary);
    if (!p.approvedBy && !p.consumed) {
      const approve = el('button', '核对并批准此具体操作');
      approve.onclick = async () => {
        try {
          if (!await confirmAction('请核对上方工作区、数据集、版本和操作摘要。批准十分钟内一次有效。')) return;
          await post('/api/agent', { action:'approve', id:p.id }); await loadAgentAccess();
        } catch (e) { await notifyUser(e.message); }
      }; item.append(approve);
    }
    panel.append(item);
  }
  panel.append(el('pre', JSON.stringify(state.audit.slice(-20), null, 2)));
}
$('editingRetry').onclick=()=>load();
$('agentReload').onclick = () => loadAgentAccess().catch(e => notifyUser(e.message));

$('historyCancel').onclick=()=>{historyPreview=null;++historyOutputEpoch;$('historyOutput').replaceChildren();$('historyConfirm').disabled=true;$('historyConfirm').parentElement.hidden=true;$('historyStatus').textContent='已取消预览，没有执行回退。';};
$('historyView').onchange=()=>{invalidateHistory();++historyJobId;historyBusy=false;return historyJob(historyRead);};
$('historyFilters').onsubmit=event=>{
  event.preventDefault(); invalidateHistory(); ++historyJobId;historyBusy=false;
  v3HistoryFilters={status:$('historyFilterStatus').value};
  for(const [field,id] of [['epoch','Epoch'],['writer','Writer'],['type','Type'],['since','Since'],['until','Until']]) {
    const v=$('historyFilter'+id).value;if(v)v3HistoryFilters[field]=['since','until'].includes(field)?new Date(v).getTime():v;
  }
  return historyJob(historyRead);
};
$('maintenancePage').onclick=async()=>{await $('historyPage').onclick();$('advancedHistory').open=true;$('advancedHistory').scrollIntoView();};
navigate('dashboard');
await load();

installCourseManagement({context:()=>({workspaceId,version,catalogVersion,datasetId:local?.datasetId??null,epoch:loadEpoch,view:viewEpoch}),get,post,load,requireEditing,confirmAction,notifyUser});
