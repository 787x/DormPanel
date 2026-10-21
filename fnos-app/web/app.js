const $ = id => document.getElementById(id), base = '/app/dormpanel-schedule-manager';
const now = () => new Intl.DateTimeFormat('en-CA', { timeZone: $('zone').value, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
const add = (d, n) => { const t = new Date(d + 'T12:00:00Z'); t.setUTCDate(t.getUTCDate() + n); return t.toISOString().slice(0, 10); };
function el(tag, text, cls) { const e = document.createElement(tag); if (text != null) e.textContent = text; if (cls) e.className = cls; return e; }
function paragraphs(id, rows) { $(id).replaceChildren(...rows.map(s => el('p', s))); }
let token, profiles = [];
async function load() {
  try {
    const r = await fetch(`${base}/api/schedule?date=${encodeURIComponent($('date').value)}&zone=${encodeURIComponent($('zone').value)}`); const s = await r.json(); if (!r.ok) throw Error(s.error);
    token = s.csrf;
    const statuses = { unbound: '未绑定：请通过 fnOS 安装向导配置 WebDAV 目录和凭据。', unchecked: '尚未检查远端，请点击“检查远端版本”。', empty: '指定目录未发现 v2 课程数据。', verified: '已验证本轮观察到的版本。', incomplete: '存在暂不可验证历史，尚未完成全部版本检查。', branches: '存在不同内容的并发分支，等待 PR38 决策；未选择最终课表。', error: '离线或历史验证失败：' + s.error };
    $('status').textContent = statuses[s.status] + (s.cached ? '\n当前显示上次验证的本地缓存，不能视为最新同步结果。' : ''); $('status').dataset.error = ['incomplete', 'branches', 'error'].includes(s.status);
    $('range').textContent = `${s.first} — ${add(s.first, 6)}`;
    profiles = s.profiles; const selectedTerm = $('term').value;
    $('term').replaceChildren(...profiles.map(p => { const option = el('option', p.termKey); option.value = p.termKey; return option; }));
    if (profiles.some(p => p.termKey === selectedTerm)) $('term').value = selectedTerm;
    $('jump').disabled = !profiles.length;
    const active = profiles.find(p => p.termKey === $('term').value);
    if (active) $('teachingWeek').value = Math.floor((Date.parse(s.first) - Date.parse(active.week1Monday)) / 604800000) + 1;
    $('week').replaceChildren(...Array.from({ length: 7 }, (_, i) => {
      const d = add(s.first, i), col = el('section', null, 'day'); col.append(el('h2', ['周一', '周二', '周三', '周四', '周五', '周六', '周日'][i]), el('small', d));
      const dateOf = ms => new Intl.DateTimeFormat('en-CA', { timeZone: s.zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(ms));
      const courses = s.courses.filter(c => dateOf(c.start) <= d && dateOf(c.end - 1) >= d);
      for (const c of courses) {
        const card = el('article', null, 'course'), fmt = ms => new Intl.DateTimeFormat('zh-CN', { timeZone: s.zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(ms));
        card.append(el('small', `${fmt(c.start)}–${fmt(c.end)}`), el('strong', c.title), el('span', c.location || '未设置教室'));
        if (dateOf(c.start) !== dateOf(c.end - 1)) card.append(el('small', `跨日 ${dateOf(c.start)} → ${dateOf(c.end - 1)}`));
        for (const t of [c.periodLabel, c.teacher, c.note, c.description, c.copiedFrom && `调自 ${c.copiedFrom}`, c.allWeeksEdited && '已应用全周编辑', c.thisWeekEdited && '已应用指定周编辑']) if (t) card.append(el('small', t));
        col.append(card);
      }
      if (!courses.length) col.append(el('p', '—')); return col;
    }));
    paragraphs('details', [`数据集：${s.datasetId ?? '未绑定'}`, `Writer：${s.writerId}`, `历史：${s.historyCount} · 分支：${s.tips.length} · 异常：${s.deferred.length}`, `检查时间：${s.checkedAt ? new Intl.DateTimeFormat('zh-CN', { timeZone: s.zone, dateStyle: 'short', timeStyle: 'medium' }).format(new Date(s.checkedAt)) : '尚未检查'}`, ...s.tips.map(id => `tip ${id}`)]);
    paragraphs('profiles', s.profiles.length ? s.profiles.map(p => { const week = Math.floor((Date.parse(s.first) - Date.parse(p.week1Monday)) / 604800000) + 1; return `${p.termKey} · 第 ${week} 教学周\n${p.timezone} · 第一周 ${p.week1Monday}\n` + p.phases.map(ph => `${ph.effectiveFrom}：${ph.periods.map(t => `${t.periodNumber}节 ${t.start}–${t.end}`).join(' / ')}`).join('\n'); }) : ['暂无可显示配置']);
    paragraphs('sources', s.sources.map(r => `${r.displayName} · ${r.filename}\n${r.occurrenceCount} 个实例 · ${r.kind}`)); paragraphs('events', s.events.map(e => `${e.title} · ${e.note}`));
  } catch (e) { $('status').textContent = e.message; $('status').dataset.error = true; }
}
$('date').value = now(); $('date').onchange = load; $('zone').onchange = load;
$('jump').onclick = () => { const p = profiles.find(p => p.termKey === $('term').value), n = Number($('teachingWeek').value); if (p && Number.isInteger(n) && n >= 1 && n <= 1000) { $('date').value = add(p.week1Monday, (n - 1) * 7); load(); } };
$('prev').onclick = () => { $('date').value = add($('date').value, -7); load(); }; $('next').onclick = () => { $('date').value = add($('date').value, 7); load(); }; $('today').onclick = () => { $('date').value = now(); load(); };
$('refresh').onclick = async () => { $('refresh').disabled = true; try { const r = await fetch(base + '/api/refresh', { method: 'POST', headers: { 'X-DormPanel-CSRF': token } }); if (!r.ok) throw Error('刷新请求未通过授权'); await load(); } catch (e) { $('status').textContent = e.message; } finally { $('refresh').disabled = false; } }; await load();
