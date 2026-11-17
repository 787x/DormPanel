const route = '/api/productivity';
const editRoute = route + '/edit';
const uuid = () => {
  // fnOS may serve an HTTP LAN origin where crypto.randomUUID is unavailable.
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
  return [hex.slice(0,8), hex.slice(8,12), hex.slice(12,16), hex.slice(16,20), hex.slice(20)].join('-');
};
const context = ({ workspaceId, datasetId, version, catalogVersion }) => ({ workspaceId, datasetId, version, catalogVersion });
const sameContext = (a, b) => a && b && Object.keys(context(a)).every(key => a[key] === b[key]);
const normalize = text => text.replace(/\r\n?/g, '\n');

// Textarea normalizes newlines. Keep exact raw bytes in untouched regions of each edit.
function updateBody(raw, next, pasted) {
  const before = normalize(raw);
  if (before === next) return raw;
  let prefix = 0, suffix = 0;
  while (prefix < Math.min(before.length, next.length) && before[prefix] === next[prefix]) prefix++;
  while (suffix < Math.min(before.length, next.length) - prefix && before.at(-1-suffix) === next.at(-1-suffix)) suffix++;
  const rawOffset = index => {
    let i = 0;
    for (let n = 0; n < index; n++, i++) if (raw[i] === '\r' && raw[i+1] === '\n') i++;
    return i;
  };
  const inserted = next.slice(prefix, next.length-suffix);
  const newline = raw.match(/\r\n|\r|\n/)?.[0] ?? '\n';
  return raw.slice(0, rawOffset(prefix)) +
    (pasted !== undefined && normalize(pasted) === inserted ? pasted : inserted.replaceAll('\n', newline)) +
    raw.slice(rawOffset(before.length-suffix));
}

export function installProductivity({ host, get, post, el, button, confirmAction, sync, sources, openConflictReview, reload, obsoleteView, canWrite }) {
  let mode, scope, epoch = 0, models = {};
  const model = () => models[mode] ??= { offset:0, taskOffset:0, conflictOffset:0 };
  const read = (view, params = {}) => get(route + '?' + new URLSearchParams({ view, ...params }));
  function leave() { ++epoch; mode = null; host.hidden = true; }
  function open(page, workspace) {
    if (scope !== workspace) { scope = workspace; models = {}; }
    mode = page;
    host.hidden = false;
    if (workspace) return refresh();
    host.replaceChildren(el('p', '正在读取工作区…'));
  }
  async function refresh() {
    if (!mode) return;
    const state = model(), ticket = ++epoch, todo = mode === 'todo';
    host.setAttribute('aria-busy', 'true');
    if (!state.draft) host.replaceChildren(el('p', '正在读取…'));
    else for (const b of host.querySelectorAll('button[type=submit]')) b.disabled = true;
    try {
      const list = await read(todo ? 'todo.lists' : 'memo.list', { offset:state.offset });
      if (ticket !== epoch) return;
      if (!list.rows?.length && state.offset && list.total) { state.offset = 0; return refresh(); }
      if (list.availability === 'ready' && !state.selected) state.selected = list.rows[0]?.id;
      let detail = null;
      if (list.availability === 'ready' && state.selected) {
        if (todo) {
          const selected = await read('todo.get', { id:state.selected });
          if (ticket !== epoch) return;
          if (!sameContext(list, selected)) throw Error('工作区版本已变化，请重新读取');
          if (selected.list) {
            const tasks = await read('todo.tasks', { id:state.selected, offset:state.taskOffset });
            if (!sameContext(selected, tasks)) throw Error('任务版本已变化，请重新读取');
            if (ticket !== epoch) return;
            if (!tasks.rows.length && state.taskOffset && tasks.total) { state.taskOffset = 0; return refresh(); }
            detail = { ...tasks, taskIds:selected.taskIds };
          }
        } else {
          const selected = await read('memo.get', { id:state.selected });
          if (!sameContext(list, selected)) throw Error('备忘版本已变化，请重新读取');
          if (selected.memo) detail = selected;
        }
      }
      const conflicts = list.conflict && list.availability === 'ready'
        ? await read('productivity.conflicts', { offset:state.conflictOffset }) : null;
      if (ticket !== epoch) return;
      if (conflicts && !sameContext(list, conflicts)) throw Error('冲突版本已变化，请重新读取');
      if (conflicts?.total && !conflicts.rows.length) { state.conflictOffset = 0; return refresh(); }
      state.data = { list, detail, conflicts };
      render();
    } catch (error) {
      if (ticket !== epoch || obsoleteView(error)) return;
      state.data = null;
      state.message = '读取失败：' + error.message;
      render();
    } finally {
      if (ticket === epoch) host.removeAttribute('aria-busy');
    }
  }

  function pageButtons(value, step, change) {
    const nav = el('nav');
    if (value.offset > 0) nav.append(button('上一页', () => change(Math.max(value.offset - step, 0))));
    if (value.next !== null) nav.append(button('下一页', () => change(value.next)));
    return nav;
  }
  function writable(control, allowed) { control.disabled = !allowed; return control; }
  async function apply(snapshot, request, draft) {
    const state = model(), ticket = epoch;
    const current = () => mode && state === model() && ticket === epoch;
    try {
      if (!canWrite()) throw Error('请先等待写入结束或核对上一次结果');
      if (!sameContext(snapshot, state.data?.list)) throw Error('草稿版本已过期，请重新读取');
      if (draft) draft.sending = true;
      await post(editRoute, { ...context(snapshot), ...request });
      if (draft && state.draft === draft) delete state.draft;
      if (current()) {
        if (request.action === 'todo.createList' || request.action === 'memo.create') {
          state.selected = request.id;
          state.taskOffset = 0;
        }
        state.message = '';
      }
      if (mode && state === model()) await reload();
    } catch (error) {
      if (obsoleteView(error)) return;
      if (draft && state.draft === draft) draft.error = error.message;
      if (current()) { state.message = error.message; render(); }
    } finally {
      if (draft) draft.sending = false;
      if (current()) render();
    }
  }
  async function discard(state) {
    if (!state.draft) return true;
    if (state.draft.changed && !await confirmAction('当前草稿尚未保存。放弃草稿？', '放弃草稿')) return false;
    delete state.draft;
    return true;
  }
  function start(snapshot, action, values, fields) {
    const state = model();
    state.draft = { snapshot, action, values, fields };
    render();
    host.querySelector('.productivity-form input, .productivity-form textarea')?.focus();
  }
  function editor(state, allowed) {
    const draft = state.draft;
    const form = el('form', null, 'panel productivity-form'), status = el('p', null, 'hint');
    const valid = sameContext(draft.snapshot, state.data?.list);
    const statusText = () => status.textContent = !valid ? '编辑版本已失效。可复制草稿，取消后重新读取。'
      : draft.changed ? '有未保存的草稿' : '正在编辑';
    form.append(status);
    for (const [key, label, bytes] of draft.fields) {
      const field = el('label', label), control = el('textarea');
      control.value = draft.values[key] ?? '';
      control.rows = key === 'body' ? 12 : key === 'text' ? 3 : 1;
      const count = el('small');
      let pasted;
      control.onpaste = event => { pasted = event.clipboardData?.getData('text/plain'); };
      const countBytes = () => {
        const size = new TextEncoder().encode(draft.values[key] ?? '').length;
        count.textContent = `${size} / ${bytes} B`;
        control.setCustomValidity(size > bytes ? '超过 UTF-8 字节限制' : '');
      };
      control.oninput = () => {
        draft.values[key] = updateBody(draft.values[key] ?? '', control.value, pasted);
        pasted = undefined;
        draft.changed = true;
        statusText();
        countBytes();
      };
      if (key !== 'body') control.required = true;
      countBytes();
      field.append(control, count); form.append(field);
    }
    statusText();
    const error = el('p', draft.error, 'warning'); error.setAttribute('role', 'alert');
    const save = el('button', '保存本机', 'primary');
    save.type = 'submit';
    save.disabled = !valid || !allowed || draft.sending;
    form.append(error, save, button('取消编辑', () => { delete state.draft; render(); }));
    form.onsubmit = async event => {
      event.preventDefault();
      if (save.disabled) return;
      save.disabled = true;
      await apply(draft.snapshot, { action:draft.action, ...draft.values }, draft);
      if (save.isConnected) save.disabled = !sameContext(draft.snapshot, state.data?.list) || !canWrite();
    };
    return form;
  }

  function conflictRows(panel, conflicts) {
    const sides = [['base','共同基线'],['local','fnOS 本机'],['remote','远端方案']];
    const types = { 'todo.list':'Todo 清单', 'todo.task':'Todo 任务', 'memo.text':'备忘', 'class':'课程',
      'event':'事件', 'source':'导入来源', 'date':'日期调整', 'edit':'课程覆盖', 'term':'学期',
      'course.color':'课程颜色', 'course.schedule':'课程时段' };
    const fields = { 'todo.list':[['name','名称']], 'memo.text':[['title','标题'],['body','正文']],
      'todo.task':[['listId','所属清单（listId）'],['position','顺序（position）'],
        ['completed','完成状态（completed）'],['text','任务正文（text）']] };
    for (const row of conflicts.rows) {
      panel.append(el('h4', `关联组 ${row.group} · ${types[row.typeId] ?? '未知记录'}`),
        el('p', `${row.typeId} / ${row.recordId}`));
      if (row.parts) panel.append(el('p', `记录内容分段 ${row.part} / ${row.parts}`));
      const group = el('div', null, 'productivity-conflict');
      for (const [key, label] of sides) {
        const side = row[key], column = el('div');
        column.append(el('strong', label));
        if (row.unknownSides.includes(key)) column.append(el('p', '该侧记录无法完整读取'));
        else if (side === null) column.append(el('p', '不存在'));
        else if (!fields[row.typeId]) column.append(el('pre', row.parts ? side : JSON.stringify(side, null, 2)));
        else for (const [field, name] of fields[row.typeId]) {
          const changed = new Set(sides.map(([s]) => row[s]?.[field])).size > 1;
          column.append(el('small', name, changed ? 'warning' : undefined),
            el('pre', field === 'completed' ? (side[field] ? '已完成' : '未完成') : String(side[field])));
        }
        group.append(column);
      }
      panel.append(group);
    }
    if (conflicts.rows.length) panel.append(el('p', `关联记录 ${conflicts.offset+1} / ${conflicts.total}`));
  }

  function decisionButtons(panel, enabled, decide) {
    for (const [choice, label] of [['local','采用本机冲突方案'],['remote','采用远端冲突方案']]) {
      panel.append(writable(button(label, () => decide(choice, label)), enabled));
    }
  }

  function renderConflicts(conflicts, state) {
    const panel = el('section', null, 'panel');
    panel.append(el('h3', '共享效率冲突'));
    conflictRows(panel, conflicts);
    panel.append(pageButtons(conflicts, 1, offset => { state.conflictOffset = offset; return refresh(); }));
    panel.append(el('p', conflicts.hidden ? '此页未展示课程等其他业务记录。请检查完整范围后决定。'
      : '请检查全部关联记录后决定。'), button('查看完整共享冲突', openConflictReview));
    return panel;
  }

  async function reviewConflicts(form, panel, close) {
    let initial, request = 0, confirmScope;
    const examined = new Set();
    const cursors = new Map();
    async function page(offset) {
      const ticket = ++request;
      const reviewCursor = cursors.get(offset);
      const current = await read('productivity.conflicts', { scope:'all', offset, ...(reviewCursor ? { reviewCursor } : {}) });
      if (!form.current() || ticket !== request) return;
      initial ??= current;
      if (!sameContext(initial, current) || initial.token !== current.token || initial.scopeId !== current.scopeId) {
        panel.replaceChildren(el('p', '冲突版本已变化，请关闭后重新打开。'));
        return;
      }
      if (!current.conflict) {
        panel.replaceChildren(el('p', '当前没有共享冲突。'));
        return;
      }
      if (current.rows.length) examined.add(offset);
      if (current.reviewCursor) cursors.set(current.next, current.reviewCursor);
      if (current.confirmScope) confirmScope = current.confirmScope;
      panel.replaceChildren(el('p', `本次决定覆盖全部 ${current.groups} 个冲突组。独立修改仍保留。`));
      conflictRows(panel, current);
      panel.append(pageButtons(current, 1, page));
      if (!current.complete) panel.append(el('p', '有记录无法完整展示。当前无法提交决定。'));
      else if (examined.size !== current.total) panel.append(el('p', `请逐页检查关联记录（${examined.size} / ${current.total}）。`));
      decisionButtons(panel, !!confirmScope && examined.size === current.total && !current.contextBlock && canWrite(),
        async (choice, label) => {
          if (!await confirmAction(`${label}？此次决定应用于全部冲突组。`, label) || !form.current() || ticket !== request) return;
          close();
          try {
            await post(editRoute, { ...context(current), action:'productivity.resolve', token:current.token, choice, confirmScope });
            await reload();
          } catch { /* The shared write status displays the rejection or pending verification. */ }
        });
    }
    await page(0);
  }

  function render() {
    const state = model(), todo = mode === 'todo';
    const { list, detail, conflicts } = state.data ?? {};
    const actions = el('nav');
    actions.append(button('重新读取', refresh), button('检查并同步', sync), button('课程表与共享', sources));
    host.replaceChildren(actions);
    if (state.message) {
      const message = el('p', state.message);
      message.setAttribute('role', 'status');
      host.append(message);
    }
    if (!list) {
      if (state.draft) host.append(editor(state, false));
      return;
    }
    const statuses = [
      list.availability === 'unsupported' && '当前格式不支持共享效率内容。请在课程表与共享中使用创建、加入或安全迁移入口。',
      list.availability === 'uninitialized' && '尚未完成共享数据集初始化或加入验证。请打开课程表与共享。',
      list.recovery && '恢复保护生效',
      list.conflict && '存在共享冲突',
      list.syncError && '同步异常，请检查连接和同步状态',
      list.pending ? '发布结果待确认' : list.dirty ? '本机已保存，待发布' : list.remoteConfirmed && '最近发布已取得远端精确回读确认',
    ].filter(Boolean);
    host.append(el('p', [...statuses, '其他设备接收情况未知。'].join(' · ')));
    if (list.writeBlock) host.append(el('p', '编辑受限：' + list.writeBlock, 'warning'));
    if (conflicts) host.append(renderConflicts(conflicts, state));
    const allowed = !list.writeBlock && !list.writeInFlight && canWrite();
    if (state.draft) host.append(editor(state, allowed));
    if (list.availability !== 'ready') return;
    const layout = el('div', null, 'productivity-layout'), sidebar = el('section', null, 'panel'), content = el('section', null, 'panel');
    const choose = async id => {
      const ticket = epoch;
      if (!await discard(state) || ticket !== epoch) return;
      state.selected = id; state.taskOffset = 0; state.message = '';
      await refresh();
    };
    const edit = (label, action, values, fields) => writable(button(label, () => start(list, action, values, fields)), allowed && !state.draft);
    const remove = (label, description, request) => writable(button(label, async () => {
      const ticket = epoch;
      if (await confirmAction(description, label) && ticket === epoch) await apply(list, request);
    }), allowed && !state.draft);
    const listName = ['name','清单名称',256], taskText = ['text','任务正文',4096];
    const memoTitle = ['title','备忘标题',256], memoBody = ['body','备忘正文',32768];
    sidebar.append(el('h2', todo ? 'Todo 清单' : '备忘列表'), edit(todo ? '新建清单' : '新建备忘',
      todo ? 'todo.createList' : 'memo.create', todo ? { id:uuid(), name:'' } : { id:uuid(), title:'', body:'' },
      todo ? [listName] : [memoTitle, memoBody]));
    if (!list.total) sidebar.append(el('p', todo ? '还没有清单。' : '还没有备忘。'));
    for (const row of list.rows) {
      const item = el('div', null, 'productivity-item'), select = button(row.name ?? row.title, () => choose(row.id));
      select.setAttribute('aria-pressed', String(state.selected === row.id));
      item.append(select, el('small', todo ? `${row.remaining} 项未完成 · 共 ${row.total} 项` : row.preview));
      sidebar.append(item);
    }
    sidebar.append(pageButtons(list, 20, offset => { state.offset = offset; return refresh(); }));
    if (!detail) content.append(el('p', todo ? '请选择 Todo 清单。' : '请选择备忘。'));
    else if (todo) {
      const { id, name, remaining, total } = detail.list;
      content.append(el('h2', name), el('p', `${remaining} 项未完成 · 共 ${total} 项`),
        edit('重命名清单', 'todo.renameList', { id, name }, [listName]),
        edit('新增任务', 'todo.createTask', { id:uuid(), listId:id, text:'' }, [taskText]),
        remove('删除清单', `删除「${name}」及其全部 ${total} 项任务？`, { action:'todo.deleteList', id, taskIds:detail.taskIds }));
      if (!total) content.append(el('p', '还没有任务。'));
      for (const task of detail.rows) {
        const row = el('div', null, 'productivity-item');
        row.append(el('p', task.text), writable(button(task.completed ? '✓ 已完成 · 设为未完成' : '未完成 · 标为完成', () =>
          apply(list, { action:'todo.setCompleted', id:task.id, completed:!task.completed })), allowed && !state.draft),
          edit('编辑任务', 'todo.editTask', { id:task.id, text:task.text }, [taskText]),
          remove('删除任务', '删除这项任务？', { action:'todo.deleteTask', id:task.id }));
        content.append(row);
      }
      content.append(pageButtons(detail, 20, offset => { state.taskOffset = offset; return refresh(); }));
    } else {
      const { id, title, body } = detail.memo;
      content.append(el('h2', title), el('pre', body || '（正文为空）'),
        edit('编辑备忘', 'memo.edit', { id, title, body }, [memoTitle, memoBody]),
        remove('删除备忘', `删除「${title}」？`, { action:'memo.delete', id }));
    }
    layout.append(sidebar, content); host.append(layout);
  }

  window.addEventListener('beforeunload', event => {
    if (Object.values(models).some(state => state.draft?.changed)) {
      event.preventDefault(); event.returnValue = '';
    }
  });
  return { open, leave, reviewConflicts, hasDraft:() => Object.values(models).some(state => state.draft?.changed) };
}
