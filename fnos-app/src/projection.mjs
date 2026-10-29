import {rgb as sharedCourseRgb} from './shared-course-colors.mjs';
import {scheduleSlots} from './shared-course-schedule.mjs';
import { Temporal } from '@js-temporal/polyfill';
import { date, overrideKey, termKey } from './protocol.mjs';

export const addDays = (d, days) => date(d).add({ days }).toString();
export const monday = d => addDays(d, 1 - date(d).dayOfWeek);
const local = (ms, z) => Temporal.Instant.fromEpochMilliseconds(Number(ms)).toZonedDateTimeISO(z);
const instant = (d, minute, z) => date(d).toPlainDateTime({ hour: Math.floor(minute / 60), minute: minute % 60 }).toZonedDateTime(z).epochMilliseconds;
const dayDiff = (a, b) => date(a).until(date(b)).days;
const periods = label => { const a = (label ?? '').match(/\d+/g)?.map(Number) ?? []; return a.length > 0 && a.length <= 30 && a[0] >= 1 && a[0] <= 30 && a.at(-1) >= 1 && a.at(-1) <= 30 ? [a[0], a.at(-1)] : null; };

export function linkedPeriodTimes(p, d, a, b) {
    const phase = [...p.phases].filter(ph => ph.effectiveFrom <= d).sort((a, b) => a.effectiveFrom < b.effectiveFrom ? -1 : 1).at(-1);
    const start = phase?.periods.find(t => t.periodNumber === a)?.start, end = phase?.periods.find(t => t.periodNumber === b)?.end;
    if (!start || !end) return null;
    const from = date(d).toPlainDateTime(start).toZonedDateTime(p.timezone).epochMilliseconds;
    const to = date(d).toPlainDateTime(end).toZonedDateTime(p.timezone).epochMilliseconds;
    return to > from ? [from, to] : null;
  }

export function projectWeek(state, first, zone = 'Asia/Shanghai') {
  const sources = new Map(state.sources.map(s => [s.id, s]));
  const profiles = new Map(state.profiles.map(p => [p.termKey, p]));
  const edits = new Map(state.classOverrides.map(e => [e.key, e]));
  const days = new Map(state.dayAdjustments.map(d => [d.date, d]));
  const profile = v => profiles.get(termKey(sources.get(v.sourceId) ?? { termKey: null, filename: '' }));
  function patch(v, e) {
    const target = e.date ?? (e.weekday == null ? v.academicDate : addDays(monday(v.academicDate), e.weekday - 1));
    if (e.date != null && monday(target) !== monday(v.anchorDate)) return null;
    const original = periods(v.periodLabel), a = e.periodStart ?? original?.[0], b = e.periodEnd ?? original?.[1];
    const linked = e.timingMode === 'linked' || e.timingMode == null && v.linked, p = profile(v);
    if (e.timingMode === 'linked' && (!p || a == null || b == null)) return null;
    let bounds = null;
    if (linked && p && a != null && b != null) { bounds = linkedPeriodTimes(p, target, a, b); if (!bounds) return null; }
    const from = bounds ? local(bounds[0], zone).hour * 60 + local(bounds[0], zone).minute : e.startMinute ?? v.startMinute;
    const to = bounds ? local(bounds[1], zone).hour * 60 + local(bounds[1], zone).minute : e.endMinute ?? v.endMinute;
    const overnight = local(v.end, zone).toPlainDate().toString() > v.date && ['startMinute', 'endMinute', 'periodStart', 'periodEnd', 'timingMode'].every(k => e[k] == null);
    if (!bounds && to <= from && !overnight) return null;
    const display = addDays(v.date, dayDiff(v.academicDate, target));
    const start = bounds?.[0] ?? instant(display, from, zone), end = bounds?.[1] ?? instant(addDays(display, overnight ? 1 : 0), to, zone);
    if ((e.periodStart != null || e.periodEnd != null) && (a == null || b == null)) return null;
    return { ...v, title: e.title ?? v.title, location: e.location ?? v.location, teacher: e.teacher ?? v.teacher, note: e.note ?? v.note,
      startMinute: from, endMinute: to, academicDate: target, date: local(start, zone).toPlainDate().toString(), start, end, linked,
      periodLabel: e.periodStart != null || e.periodEnd != null ? `[${Array.from({ length: Math.max(0, b - a + 1) }, (_, i) => String(a + i).padStart(2, '0')).join('-')}节]` : v.periodLabel,
      allWeeksEdited: v.allWeeksEdited || e.anchorDate === null, thisWeekEdited: v.thisWeekEdited || e.anchorDate !== null };
  }
  function move(v, target) {
    if (target === v.academicDate) return { ...v, anchorDate: target };
    const display = addDays(v.date, dayDiff(v.academicDate, target));
    const overnight = local(v.end, zone).toPlainDate().toString() > v.date;
    return { ...v, date: display, academicDate: target, anchorDate: target, start: instant(display, v.startMinute, zone), end: instant(addDays(display, overnight ? 1 : 0), v.endMinute, zone) };
  }
  function academicWeek(first) {
    const mon = monday(first), base = [];
    for (let i = 0; i < 7; i++) {
      const d = addDays(mon, i);
      for (const r of state.entries.filter(r => r.day === i + 1)) base.push({ ...r, seriesId: r.id, sourceId: null, date: d, academicDate: d, anchorDate: d, start: instant(d, r.startMinute, zone), end: instant(d, r.endMinute, zone), teacher: '', note: '' });
    }
    const min = instant(addDays(mon, -2), 0, zone), max = instant(addDays(mon, 9), 0, zone);
    for (const r of state.imported) {
      if (Number(r.start) >= max || Number(r.end) <= min) continue;
      const start = local(r.start, zone), end = local(r.end, zone), display = start.toPlainDate().toString();
      const academic = termKey(sources.get(r.sourceId)) != null ? local(r.start, r.timezone).toPlainDate().toString() : display;
      if (academic < mon || academic >= addDays(mon, 7)) continue;
      base.push({ ...r, start: Number(r.start), end: Number(r.end), date: display, academicDate: academic, anchorDate: academic, startMinute: start.hour * 60 + start.minute, endMinute: end.hour * 60 + end.minute, teacher: '', note: '' });
    }
    const slots=scheduleSlots(state);
    const projected=base.filter(v=>!slots.some(s=>s.sourceId===v.sourceId&&s.replacesSeries===v.seriesId));
    for(const s of slots){
      const week=Math.floor(dayDiff(s.week1,mon)/7)+1;if(!s.enabled||!s.weeks.includes(week))continue;
      const d=addDays(mon,s.weekday-1),p=profiles.get(termKey(sources.get(s.sourceId)??{termKey:null,filename:''}));
      const origin=state.imported.find(r=>r.sourceId===s.sourceId&&r.seriesId===s.ownerSeries);
      const timingZone=p?.timezone??origin?.timezone??zone;
      const anchorDay=s.replacesSeries===null?s.weekday:s.sourceId===null?(state.entries.find(e=>e.id===s.ownerSeries)?.day??s.weekday):(origin?local(origin.start,origin.timezone).dayOfWeek:s.weekday);
      const times=s.periodStart>0?(p?linkedPeriodTimes(p,d,s.periodStart,s.periodEnd):null):[instant(d,s.startMinute,timingZone),instant(addDays(d,s.endMinute<s.startMinute?1:0),s.endMinute,timingZone)];
      if(!times)continue;const from=local(times[0],zone),to=local(times[1],zone);
      projected.push({...s,id:s.id,seriesId:s.replacesSeries??s.id,colorSeriesId:s.ownerSeries,date:from.toPlainDate().toString(),academicDate:d,anchorDate:addDays(mon,anchorDay-1),start:times[0],end:times[1],startMinute:from.hour*60+from.minute,endMinute:to.hour*60+to.minute,periodLabel:s.periodStart>0?`[${s.periodStart}-${s.periodEnd}节]`:null});
    }
    const templates = projected.map(v => { v = { ...v, linked: !!profile(v) && periods(v.periodLabel) !== null }; const e = edits.get(overrideKey(v.sourceId, v.seriesId, null)); return e ? patch(v, e) ?? v : v; });
    const composed = [];
    for (let i = 0; i < 7; i++) {
      const target = addDays(mon, i), d = days.get(target);
      if (d?.mode === 'none') continue;
      if (d?.mode !== 'weekday') { composed.push(...templates.filter(v => v.academicDate === target)); continue; }
      const source = addDays(mon, d.weekday - 1);
      for (const v of templates.filter(v => v.academicDate === source)) {
        let copied = { ...move(v, target), copiedFrom: source }; const range = periods(copied.periodLabel);
        if (copied.linked && range && profile(copied)) {
          copied = patch(copied, { periodStart: range[0], periodEnd: range[1], timingMode: 'linked', anchorDate: null });
          if (!copied) continue;
          copied.allWeeksEdited = v.allWeeksEdited; copied.thisWeekEdited = v.thisWeekEdited;
        }
        composed.push(copied);
      }
    }
    return composed.map(v => { const e = edits.get(overrideKey(v.sourceId, v.seriesId, v.anchorDate)); return e ? patch(v, e) ?? v : v; }).filter(v => days.get(v.date)?.mode !== 'none').map(v=>state.sharedRecords===undefined?v:{...v,sharedRgb:sharedCourseRgb(state,v.sourceId,v.colorSeriesId??v.seriesId)});
  }
  const start = instant(first, 0, zone), end = instant(addDays(first, 7), 0, zone);
  return [-7, 0, 7].flatMap(n => academicWeek(addDays(first, n))).filter(v => v.start < end && v.end > start).sort((a, b) => a.start - b.start || (a.title < b.title ? -1 : a.title > b.title ? 1 : 0) || (a.id < b.id ? -1 : 1));
}

/** Validate all-weeks linked overrides against the pre-day-composition effective instances. */
export function validateLinkedCourseOverrides(state,sourceId,series) {
 const selected=new Set(series),edits=state.classOverrides.filter(e=>e.sourceId===sourceId&&selected.has(e.seriesId)&&e.anchorDate===null&&e.timingMode==='linked');
 if(!edits.length)return;
 const owner=state.sources.find(s=>s.id===sourceId),profile=owner&&state.profiles.find(p=>p.termKey===termKey(owner));
 if(!profile)throw Error('无法证明重复课程的有限生效范围或来源作息；请先提供有限安排');
 const weeks=new Set(state.imported.filter(r=>r.sourceId===sourceId&&selected.has(r.seriesId)).map(r=>monday(local(r.start,r.timezone).toPlainDate().toString())));
 for(const slot of scheduleSlots(state).filter(s=>s.sourceId===sourceId&&selected.has(s.ownerSeries)&&s.replacesSeries!==null&&s.enabled))for(const week of slot.weeks)weeks.add(addDays(slot.week1,(week-1)*7));
 const baseline={...state,classOverrides:[],dayAdjustments:[]};
 const rows=[...weeks].flatMap(week=>projectWeek(baseline,week,profile.timezone));
 for(const edit of edits){
  const affected=rows.filter(r=>r.sourceId===sourceId&&r.seriesId===edit.seriesId);
  if(!affected.length)throw Error('无法确定此系列的实际有效日期，请明确有限范围');
  if(edit.weekday!=null&&new Set(affected.map(r=>date(r.academicDate).dayOfWeek)).size>1)throw Error('多星期系列不能统一移动星期');
  for(const row of affected){const target=edit.weekday==null?row.academicDate:addDays(monday(row.academicDate),edit.weekday-1);
   if(!linkedPeriodTimes(profile,target,edit.periodStart,edit.periodEnd))throw Error(`有效日期 ${target} 的作息不能解析所选节次`);
  }
 }
}
