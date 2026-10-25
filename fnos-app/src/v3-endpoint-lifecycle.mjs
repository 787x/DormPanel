import {ensure} from './protocol.mjs';
import {sha} from './v3-protocol.mjs';
export const ENDPOINT_TTL=24*60*60*1000;
const base=new Set(['node','identity','epoch','frontier','covered','observed','scope','scan','todo','listing','activate','control','error','evidence','failedWalk','proof']);
export function endpointNamespace(ns){const m=/^endpoint:([0-9a-f-]{36}):(.*)$/.exec(ns);return m?{token:m[1],kind:m[2]}:null;}
export function endpointGraphKind(kind){return base.has(kind)||/^(members:[0-9a-f-]{36}|epochFrontier:[0-9a-f-]{36}(?::covered)?|proof:[0-9a-f]{64}:(queue|seen|tips|covered)|(stack|visiting):[0-9a-f-]{36})$/.test(kind);}
function noRawRef(v){if(!v||typeof v!=='object')return true;return !v.$v3Snapshot&&Object.values(v).every(noRawRef);}
/** No timer. Called under the existing endpoint/maintenance exclusion. Jobs enter
 * CLEANING before any private row is removed; interrupted pages are idempotent.
 * Shared cache/witness/pin and immutable RAW tables are deliberately untouched. */
export class EndpointJobs {
  constructor(service){this.s=service;this.m=service.meta;}
  async protectedTokens(now){
    const c=this.s.catalog,state=c.endpointApprovalState?.()??await c.store.read('agent-control.json')??{approvals:[],receipts:[]},tokens=new Set();
    for(const p of state.approvals??[]){
      if(p.operation!=='endpoint.confirm')continue;
      const token=p.summary?.preview?.token;
      if(token && ((!p.consumed&&p.expires>now)||(state.receipts??[]).some(r=>r.operationId===p.operationId&&!r.result&&r.time>now-ENDPOINT_TTL)))tokens.add(token);
    }
    for(const r of state.receipts??[])if(r.operation==='endpoint.confirm'&&!r.result&&r.time>now-ENDPOINT_TTL&&r.endpointToken)tokens.add(r.endpointToken);
    return tokens;
  }
  terminal(j,now){return this.s.catalog.manifest.endpointReceipts?.[j.token]?'COMMITTED':j.status==='CLEANING'?j.terminalStatus:['CANCELLED','STALE'].includes(j.status)?j.status:now>=this.deadline(j)?'EXPIRED':null;}
  deadline(j){return j.expiresAt??(Number.isSafeInteger(j.createdAt)?j.createdAt+ENDPOINT_TTL:0);}
  async reap(now=Date.now()){
    const s=this.s,c=s.catalog,m=this.m;s.local();
    ensure(!c.maintenance&&!c.invalidated&&!c.manifest.transition&&!c.protection(s.id)&&!c.restoreGuards[s.id],'恢复保护禁止预检清理');
    ensure((!c.configuring&&!c.busy.size)||(c.endpointTaskOwner===s.id&&!c.configuring&&[...c.busy].every(id=>id===s.id)),'工作区正在执行操作，停止清理');
    const pins=await this.protectedTokens(now),fingerprint=s.fingerprint();let remaining=256;
    for(const row of m.page('endpointJob',0,128)){
      const j=row.value;ensure(j.token===row.key&&j.workspaceId===s.id&&typeof j.user==='string','预检归属异常，停止清理');
      ensure(['VALIDATING','READY','CANCELLED','STALE','EXPIRED','COMMITTED','CLEANING'].includes(j.status),'未知预检状态，停止清理');
      if(j.status==='CLEANING')ensure(['CANCELLED','STALE','EXPIRED','COMMITTED'].includes(j.terminalStatus),'未知清理决定，停止清理');
      const status=this.terminal(j,now)??(j.fingerprint&&j.fingerprint!==fingerprint?'STALE':null);if(!status||pins.has(j.token))continue;
      if(j.status!=='CLEANING'){
        const result=c.manifest.endpointReceipts?.[j.token];
        m.atomic(()=>{
          if(result){ensure(result.workspaceId===s.id&&result.datasetId===j.binding.datasetId,'端点回执身份变化');m.put('endpointReceipt',j.token,{token:j.token,user:j.user,workspaceId:s.id,createdAt:j.createdAt,result});}
          // No password material is needed once a task is irrevocably retired.
          m.put('endpointJob',j.token,{token:j.token,user:j.user,workspaceId:s.id,createdAt:j.createdAt,status:'CLEANING',terminalStatus:status});
        });s.cleanupFault?.('marked');
      }
      const prefix='endpoint:'+j.token+':',rows=m.db.prepare('SELECT ns,k,v,sum FROM v3_records WHERE ns>=? AND ns<? ORDER BY ns,k LIMIT ?').all(prefix,prefix+'\uffff',remaining);
      m.atomic(()=>{for(const r of rows){const kind=endpointNamespace(r.ns);ensure(kind&&kind.token===j.token&&endpointGraphKind(kind.kind),'未知候选命名空间，停止清理');
        m.budget?.charge(Buffer.byteLength(r.v),0);ensure(sha(Buffer.from(r.ns+'\0'+r.k+'\0'+r.v))===r.sum,'候选记录校验失败');
        // Inspect the stored representation BEFORE unpacking immutable RAW references.
        ensure(noRawRef(JSON.parse(r.v)),'候选引用 RAW，停止清理');m.remove(r.ns,r.k);}});
      remaining-=rows.length;s.cleanupFault?.('page');
      const left=m.db.prepare('SELECT 1 FROM v3_records WHERE ns>=? AND ns<? LIMIT 1').get(prefix,prefix+'\uffff');
      if(!left)m.atomic(()=>{
        m.put('endpointRetired',j.token,{token:j.token,user:j.user,workspaceId:s.id,status,createdAt:j.createdAt,retiredAt:now});m.remove('endpointJob',j.token);
        const total=m.count('endpointRetired');if(total>32)for(const r of m.page('endpointRetired',0,Math.min(256,total-32)))m.remove('endpointRetired',r.key);
      });
      if(!remaining)break;
    }
    return {active:m.page('endpointJob',0,128).filter(r=>!this.terminal(r.value,now)).length,cleaning:m.page('endpointJob',0,128).filter(r=>r.value.status==='CLEANING').length};
  }
}
