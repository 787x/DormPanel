import { randomUUID } from 'node:crypto';
import { ensure, schemas } from './protocol.mjs';
import { differences } from './history.mjs';
import { sha } from './v3-protocol.mjs';
import { Budget, match, V3LocalStore } from './v3-store.mjs';
import { IndexedV3Cache } from './v3-index.mjs';
import { V3History, V3Discovery } from './v3-history.mjs';
import { remoteV3 } from './active-v3.mjs';
import { statSync, statfsSync, readdirSync, lstatSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const hash = v => sha(Buffer.from(JSON.stringify(v)));
const namespaces = { verified:'node', observed:'observed', pending:'todo', error:'error', epoch:'epoch' };
// One service for Web/CLI/MCP. Catalog retains ownership and permission enforcement.
export class V3Maintenance {
  constructor(catalog,id) { this.catalog=catalog; this.id=id; this.db=catalog.db(id); this.runtime=this.db.activeV3; this.journal=this.runtime.journal; this.meta=this.journal.meta; }
  context() {
    const budget=new Budget(), remote=remoteV3(this.catalog,this.id,budget), cache=new IndexedV3Cache(this.journal.directory,budget);
    this.meta.budget=budget; const b=this.journal.value.binding; ensure(b,'尚未绑定 v3');
    return {budget,remote,cache,history:new V3History(this.journal,cache,remote,b.descriptorPath,b.descriptorHash,budget)};
  }
  local() { this.meta.budget=new Budget({requests:0}); }
  list(input={}) {
    this.local(); const after=Number(input.after??0), limit=Number(input.limit??40), status=input.status??'verified';
    ensure(Object.hasOwn(namespaces,status) && Number.isSafeInteger(after) && after>=0 && Number.isInteger(limit) && limit>0 && limit<=100,'历史分页无效');
    const ns=namespaces[status], scanned=this.meta.page(ns,after,limit), rows=[];
    for(const row of scanned) {
      const node=ns==='node'?row.value:this.meta.get('node',row.key);
      if(input.epoch && node?.epochId!==input.epoch || input.writer && node?.writerId!==input.writer || input.type && node?.type!==Number(input.type))continue;
      // DPV3 does not encode a wall-clock creation timestamp. Never substitute file mtime.
      rows.push({seq:row.seq,path:row.key,id:node?.objectId??row.key,...row.value,status:ns==='node'?'VERIFIED':ns==='error'?'ERROR':node?'VERIFIED':'OBSERVED',createdAt:null});
    }
    const last=scanned.at(-1)?.seq??after, s=this.journal.value;
    return {protocol:'v3',datasetId:s.binding?.datasetId,descriptor:s.binding,activeEpoch:s.activeEpoch,
      rows,next:last<this.meta.lastSeq(ns)?last:null,count:this.meta.count(ns),frontier:this.meta.page('frontier',0,65).map(r=>r.key),
      pendingPhase:s.pending?.phase??null,oldHistoryAuditComplete:false,completeDirectoryView:false,serverWriteProhibited:false,
      rollbackEnabled:!this.blockers().length,blockers:this.blockers(),timeFilterSupported:false};
  }
  async snapshot(id) {
    this.local(); if(id==='current')return this.db.frame.local;
    const path=this.meta.get('identity',id)?.path??id, node=this.meta.get('node',path); ensure(node && [2,3].includes(node.type),'版本未经过完整验证或没有业务快照');
    const cache=new IndexedV3Cache(this.journal.directory,new Budget({requests:0})), raw=await cache.get(path);
    ensure(raw && sha(raw)===node.hash,'历史缓存缺失或身份变化；请继续审计或从独立归档恢复');
    const object=match(path,raw); ensure(object.datasetId===this.journal.value.binding.datasetId,'数据集身份变化');return object.state;
  }
  async read(kind,input,user) {
    if(kind==='list')return this.list(input);
    if(kind==='space')return this.space();
    if(kind==='eligibility')return this.eligibility();
    if(kind==='diff')return differences(await this.snapshot(input.from),await this.snapshot(input.to),Number(input.offset??0));
    if(kind==='preview-diff') { const p=this.previewRecord(input.token,user); return differences(this.db.frame.local,await this.snapshot(p.target),Number(input.offset??0)); }
    const state=await this.snapshot(input.id), table=input.table??'entries', offset=Number(input.offset??0);
    ensure(schemas.some(([t])=>t===table) && Number.isSafeInteger(offset) && offset>=0,'快照分页无效');
    return {table,total:state[table].length,rows:state[table].slice(offset,offset+40),next:offset+40<state[table].length?offset+40:null};
  }
  blockers() {
    const s=this.journal.value;
    return [!s.binding?'未绑定':null,s.pending && s.pending.phase!=='RECEIPTED'?'待确认上传':null,s.conflict?'未决冲突':null,s.rotation?'换代尚未完成':null,s.initialization?.length?'初始化未完成':null,
      this.meta.count('todo')||this.meta.count('scan')||this.meta.count('error')||this.meta.get('control','walk')?'历史证明未完成或异常':null,
      this.catalog.protection(this.id)||this.catalog.restoreGuards[this.id]?'迁移或恢复保护':null].filter(Boolean);
  }
  fingerprint() {
    this.local(); return hash({frame:this.db.snapshot(),catalog:this.catalog.manifest.revision,
      frontier:this.meta.page('frontier',0,65),node:this.meta.lastSeq('node'),observed:this.meta.lastSeq('observed'),errors:this.meta.page('error',0,256)});
  }
  previewRecord(token,user) {
    this.local(); const p=this.meta.get('rollbackPreview',token);
    ensure(p && p.user===user && p.expires>Date.now() && p.fingerprint===this.fingerprint(),'回退预览过期或输入变化');return p;
  }
  async audit() {
    if(this.catalog.restoreGuards[this.id])return this.revalidateRestored();
    const {budget,remote,cache,history}=this.context(); let status='BOUNDED';
    try {
      await history.open(); await new V3Discovery(this.journal,remote,this.journal.value.binding.datasetId).scan(16);
      for(let n=0;n<16;n++){const row=this.meta.page('todo',0,1)[0];if(!row)break;await history.verify(row.key);}
      history.drainActivation(); await history.auditObjects(16);
    } catch(e) { if(!/budget|incomplete/.test(e.message))throw e;status='BUDGET'; }
    this.local(); const result={status,round:budget.roundId,checkedAt:Date.now(),scanPending:this.meta.count('scan'),validationPending:this.meta.count('todo'),
      verified:this.meta.count('node'),observed:this.meta.count('observed'),errors:this.meta.count('error'),scopeCursor:this.meta.get('control','scopeAudit'),objectCursor:this.meta.get('control','objectAudit'),oldHistoryAuditComplete:false,completeDirectoryView:false};
    this.meta.atomic(()=>this.meta.put('maintenance','audit',result)); return result;
  }
  async revalidateRestored() {
    this.local();const guard=this.catalog.restoreGuards[this.id];ensure(guard?.protocol==='v3' && hash(guard.binding)===hash(this.journal.value.binding),'恢复绑定身份变化');
    const connection=this.catalog.connection(this.catalog.row(this.id).connectionId);ensure(!guard.endpointHash || guard.endpointHash===hash(connection),'恢复端点身份变化');
    const {remote,cache}=this.context(), b=this.journal.value.binding;
    const descriptor=await remote.read(b.descriptorPath);ensure(descriptor && sha(descriptor)===b.descriptorHash && match(b.descriptorPath,descriptor).type===1,'恢复 Descriptor 回读失败');
    this.local();let cursor=this.meta.get('restoreValidation',guard.restoredAt)??{after:0,ceiling:this.meta.lastSeq('node')};
    for(const r of this.meta.page('node',cursor.after,16,cursor.ceiling)) {
      const raw=await remote.read(r.key);ensure(raw && sha(raw)===r.value.hash,'恢复已知引用缺失或变化；继续隔离发布');await cache.put(r.key,raw);
      cursor.after=r.seq;this.local();this.meta.atomic(()=>this.meta.put('restoreValidation',guard.restoredAt,cursor));
    }
    if(this.meta.page('node',cursor.after,1,cursor.ceiling).length)return {status:'RESTORE_VALIDATING',cursor,publishingIsolated:true};
    ensure(!this.meta.count('error') && !this.meta.count('todo') && !this.meta.get('control','walk'),'恢复存在未验证历史或异常，保留发布隔离');
    const c=this.catalog, clear=c.guardQueue.then(async()=>{
      ensure(c.restoreGuards[this.id]===guard,'恢复保护变化');const next={...c.restoreGuards};delete next[this.id];await c.store.save('restore-guards.json',next);c.restoreGuards=next;
    });c.guardQueue=clear.catch(()=>{});await clear;
    return {status:'RESTORE_KNOWN_REFERENCES_VERIFIED',publishingIsolated:false,completeRemoteHistory:false};
  }
  async preview(input,user) {
    ensure(!this.blockers().length,this.blockers().join('；'));
    const state=await this.snapshot(input.target), token=randomUUID(), fingerprint=this.fingerprint();
    const record={token,target:input.target,user,expires:Date.now()+10*60000,fingerprint,version:this.db.frame.revision};
    this.meta.atomic(()=>this.meta.put('rollbackPreview',token,record));
    return {...record,diff:differences(this.db.frame.local,state),unchanged:hash(state)===hash(this.db.frame.local)};
  }
  async rollback(input,user) {
    this.local(); const previous=this.meta.get('rollbackOperation',input.token);
    if(previous) { ensure(previous.user===user && previous.target===input.target,'操作身份变化');return previous; }
    const p=this.previewRecord(input.token,user);ensure(input.target===p.target && input.confirm===p.target && input.version===p.version,'回退确认或版本过期');
    ensure(!this.blockers().length,this.blockers().join('；'));
    const state=await this.snapshot(p.target);
    // Re-read Descriptor, current frontier and selected target under original hashes.
    const {remote,history}=this.context();await history.open();
    for(const path of [...this.meta.page('frontier',0,65).map(r=>r.key),this.meta.get('identity',p.target)?.path??p.target]) {
      const node=this.meta.get('node',path), bytes=await remote.read(path);ensure(node && bytes && sha(bytes)===node.hash,'回退身份或前沿变化');
    }
    ensure((await remote.read(this.journal.value.binding.descriptorPath))?.equals(await history.cache.get(this.journal.value.binding.descriptorPath)),'Descriptor 身份变化');
    // A bounded discovery pass refuses unseen paths; it does not claim global completeness.
    await new V3Discovery(this.journal,remote,this.journal.value.binding.datasetId).scan(32);
    ensure(p.fingerprint===this.fingerprint() && !this.blockers().length,'回退预览过期或新历史尚未验证');
    const result={operationId:input.token,user,target:p.target,status:'LOCAL_SAVED',savedAt:Date.now()};
    await this.journal.transaction(s=>{ensure(p.fingerprint===this.fingerprint(),'回退输入变化');s.local=state;s.revision++;s.decision=null;this.meta.put('rollbackOperation',input.token,result);},true);
    // Normal engine stages a fresh Commit with current parents; never move frontier backwards.
    await this.runtime.synchronize();return {...result,receipt:this.journal.value.receipt};
  }
  eligibility() { this.local();return {status:'NOT_ELIGIBLE',automaticDelete:false,serverWriteProhibited:false,unknownOldWriters:true,completeRemoteHistory:false,knownClosureRecoverable:'UNPROVEN',reasons:['普通 WebDAV 无服务端封存证明','未知旧 Writer 及未观察路径不可排除','Checkpoint 和管理员确认不构成永久停写证明']}; }
  async archive(input,user) {
    this.local();let job=input.jobId?this.meta.get('archiveJob',input.jobId):null;
    if(!job) {
      ensure(!input.jobId && this.journal.value.binding,'归档任务不存在');
      const location=await this.catalog.grants.protectedFolder(input.rootId,input.relative??'','history-'+randomUUID());
      job={id:randomUUID(),user,location,binding:this.journal.value.binding,startedAt:Date.now(),ceiling:this.meta.lastSeq('node'),after:0,pages:[],
        frontier:this.meta.page('frontier',0,65).map(r=>r.key),observed:this.meta.count('observed'),anomalies:this.meta.count('error'),scanPending:this.meta.count('scan'),validationPending:this.meta.count('todo'),status:'COPYING'};
      this.meta.atomic(()=>this.meta.put('archiveJob',job.id,job));
    }
    ensure(job.user===user && hash(job.binding)===hash(this.journal.value.binding),'归档身份变化');
    if(job.status==='VERIFIED')return job;
    const budget=new Budget(), remote=remoteV3(this.catalog,this.id,budget), cache=new IndexedV3Cache(this.journal.directory,budget), archive=new V3LocalStore(job.location,this.catalog.grants,budget);
    this.meta.budget=budget;
    const rows=this.meta.page('node',job.after,8,job.ceiling), objects=[];
    for(const row of rows) {
      let bytes=await cache.get(row.key);if(!bytes)bytes=await remote.read(row.key);
      ensure(bytes && sha(bytes)===row.value.hash,'归档对象缺失或身份变化');match(row.key,bytes);
      const old=await archive.read(row.key);ensure(!old || old.equals(bytes),'归档同路径身份冲突');
      if(!old){await archive.prepare(row.key.slice(0,row.key.lastIndexOf('/')+1));await archive.putOnce(row.key,bytes);}
      ensure((await archive.read(row.key))?.equals(bytes),'实际归档回读失败');
      objects.push({path:row.key,type:row.value.type,epoch:row.value.epochId,length:bytes.length,sha256:row.value.hash,refs:row.value.refs});
    }
    if(objects.length) {
      ensure(job.pages.length<4096,'归档页预算耗尽；保留进度');
      const name='v3-manifest-'+String(job.pages.length).padStart(6,'0')+'.json', bytes=Buffer.from(JSON.stringify({format:'DormPanel-v3-archive-page-1',binding:job.binding,objects}));
      // Retry after a crash: a previously linked identical page is accepted, never overwritten.
      let old=null;try{old=await this.catalog.grants.readPrivate(job.location,name);}catch(e){if(e.code!=='ENOENT')throw e;}
      if(old)ensure(old.equals(bytes),'归档清单身份变化');else await this.catalog.grants.writePrivate(job.location,name,bytes);
      ensure((await this.catalog.grants.readPrivate(job.location,name)).equals(bytes),'清单实际回读失败');
      job.pages.push({name,sha256:sha(bytes)});job.after=rows.at(-1).seq;
    }
    if(!this.meta.page('node',job.after,1,job.ceiling).length) {
      const manifest={format:'DormPanel-v3-history-1',binding:job.binding,frontier:job.frontier,pages:job.pages,startedAt:job.startedAt,
        verifiedAt:Date.now(),knownReferenceClosure:true,completeRemoteHistory:false,serverSealed:false,unknownPaths:true,
        observed:job.observed,anomalies:job.anomalies,scanPending:job.scanPending,validationPending:job.validationPending,
        coverage:'仅包含截止冻结索引位置的已验证对象及已知引用；未证明远端全目录或旧 Writer 停写'};
      let raw=null;try{raw=await this.catalog.grants.readPrivate(job.location,'verified.json');}catch(e){if(e.code!=='ENOENT')throw e;}
      if(raw){const previous=JSON.parse(raw);ensure(hash(previous.binding)===hash(manifest.binding) && hash(previous.pages)===hash(manifest.pages),'归档完成清单变化');}
      else {raw=Buffer.from(JSON.stringify(manifest));await this.catalog.grants.writePrivate(job.location,'verified.json',raw);}
      ensure((await this.catalog.grants.readPrivate(job.location,'verified.json')).equals(raw),'完成清单实际回读失败');
      job.status='VERIFIED';job.manifestHash=sha(raw);
    }
    this.local();this.meta.atomic(()=>this.meta.put('archiveJob',job.id,job));return job;
  }
  async restoreArchive(input,user) {
    this.local();const location={rootId:input.rootId,relative:input.relative,directoryIdentity:input.directoryIdentity},raw=await this.catalog.grants.readPrivate(location,'verified.json'), manifest=JSON.parse(raw);
    ensure(manifest.format==='DormPanel-v3-history-1' && hash(manifest.binding)===hash(this.journal.value.binding) && Array.isArray(manifest.pages) && manifest.pages.length<=4096,'归档格式或 Descriptor 身份变化');
    const key=sha(raw), old=this.meta.get('archiveRestore',key), after=old?.next??0, budget=new Budget(), cache=new IndexedV3Cache(this.journal.directory,budget), archive=new V3LocalStore(location,this.catalog.grants,budget);
    let restored=0;
    for(let n=after;n<Math.min(after+2,manifest.pages.length);n++) {
      const p=manifest.pages[n], bytes=await this.catalog.grants.readPrivate(location,p.name);ensure(sha(bytes)===p.sha256,'归档页校验失败');const page=JSON.parse(bytes);
      ensure(hash(page.binding)===hash(manifest.binding) && page.format==='DormPanel-v3-archive-page-1' && page.objects.length<=8,'归档页格式无效');
      for(const object of page.objects){const b=await archive.read(object.path);ensure(b && b.length===object.length && sha(b)===object.sha256,'归档对象校验失败');const decoded=match(object.path,b);ensure(decoded.datasetId===manifest.binding.datasetId,'归档对象数据集变化');await cache.put(object.path,b);restored++;}
      this.local();this.meta.atomic(()=>this.meta.put('archiveRestore',key,{next:n+1,user,location,manifestHash:key}));
    }
    return {restoredObjects:restored,next:this.meta.get('archiveRestore',key)?.next??0,totalPages:manifest.pages.length,remoteWritten:false,historyProof:'缓存已按归档字节恢复；因果证明仍由现有有界审计验证'};
  }
  async space() {
    this.local();const {readdir,lstat}=await import('node:fs/promises'),{join}=await import('node:path');
    const totals={sqlite:0,cacheBlobs:0,witness:0,damaged:0,otherProtected:0}, root=this.journal.directory;let count=0;
    const walk=async dir=>{for(const e of await readdir(dir,{withFileTypes:true})){ensure(++count<=20000,'空间扫描预算耗尽，未产生清理决策');const path=join(dir,e.name),s=await lstat(path);if(s.isSymbolicLink()){totals.otherProtected+=s.size;continue;}if(s.isDirectory())await walk(path);else {const category=/^v3-state-v2.sqlite/.test(e.name)?'sqlite':e.name==='object.dpv'?'cacheBlobs':e.name==='witness.json'?'witness':e.name.startsWith('damaged-')?'damaged':'otherProtected';totals[category]+=s.size;}}};await walk(root);
    return {bytes:totals,measurement:'实际文件逻辑字节；SQLite 页内回收不等于文件缩小',snapshotRows:this.meta.count('snapshot'),protected:['local/baseline/Pending/capture/receipt','frontier/祖先/witness/pin','冲突/决定/迁移/备份与恢复/回退预览','所有缓存及损坏隔离材料'],audit:this.meta.get('maintenance','audit'),archives:this.meta.count('archiveJob'),remoteGC:this.eligibility()};
  }
  snapshotPlan() {
    this.local();const refs=new Set(),candidates=[],sums=[];let rows=0;
    ensure(this.meta.get('format','version')?.value===2,'未知元数据版本，拒绝整理');
    const known=new Set(['format','installation','workspace','state','node','identity','epoch','frontier','covered','observed','scope','scan','todo','listing','activate','control','error','evidence','failedWalk','proof','decision','conflictHistory','hostMigration','hostMigrationHeader','hostV2Fork','rollbackPreview','rollbackOperation','archiveJob','archiveRestore','maintenance','restoreValidation']);
    const visit=v=>{if(typeof v==='string' && /^[0-9a-f]{64}$/.test(v))refs.add(v);else if(Array.isArray(v))v.forEach(visit);else if(v && typeof v==='object')Object.values(v).forEach(visit);};
    // Every durable namespace participates, including unknown extensions. No age heuristics.
    for(const r of this.meta.db.prepare("SELECT ns,k,v,sum FROM v3_records WHERE ns NOT IN ('snapshot','cleanupPreview','cleanupAudit') ORDER BY seq").iterate()) {
      ensure(known.has(r.ns) || /^(?:members:[0-9a-f-]{36}|epochFrontier:[0-9a-f-]{36}(?::covered)?|proof:[0-9a-f]{64}(?::(?:queue|seen|tips|covered))?|(?:stack|visiting):[0-9a-f-]{36})$/.test(r.ns),'未知元数据命名空间，保护全部快照');
      ensure(++rows<=20000,'清理分析预算耗尽；不产生可删除候选');ensure(sha(Buffer.from(r.ns+'\0'+r.k+'\0'+r.v))===r.sum,'损坏引用，拒绝整理');
      const value=JSON.parse(r.v);visit(value);sums.push(r.ns+'\0'+r.k+'\0'+r.sum);
    }
    for(const r of this.meta.db.prepare("SELECT k,v,sum FROM v3_records WHERE ns='snapshot' ORDER BY seq").iterate()) {
      ensure(++rows<=20000,'清理分析预算耗尽');ensure(sha(Buffer.from('snapshot\0'+r.k+'\0'+r.v))===r.sum,'损坏快照，拒绝整理');
      if(!refs.has(r.k)){ensure(sha(Buffer.from(JSON.parse(r.v).bytes,'base64'))===r.k,'快照损坏，拒绝整理');candidates.push({key:r.k,bytes:Buffer.byteLength(r.v),sum:r.sum});}
    }
    // Cache pins are filesystem metadata. A new pin must invalidate an existing preview too.
    const scan=dir=>{let entries;try{entries=readdirSync(dir,{withFileTypes:true});}catch(e){if(e.code==='ENOENT')return;throw e;}
      for(const e of entries.sort((a,b)=>a.name.localeCompare(b.name))){ensure(++rows<=20000,'引用扫描预算耗尽');const p=join(dir,e.name),s=lstatSync(p);ensure(!s.isSymbolicLink(),'未知缓存链接，拒绝整理');if(s.isDirectory())scan(p);else if(e.name==='witness.json'){ensure(s.size<=32768,'witness 超限');const raw=readFileSync(p),w=JSON.parse(raw);ensure(w.version===2 && Array.isArray(w.pins),'未知 witness 版本');sums.push(p+'\0'+sha(raw));}else {ensure(e.name==='object.dpv'||/^damaged-[0-9a-f-]{36}\.dpv$/.test(e.name),'未知缓存文件，拒绝整理');sums.push(p+'\0'+s.size+'\0'+s.mtimeMs+'\0'+s.ctimeMs);}}
    };scan(join(this.journal.directory,'sync-v3-index-v2'));
    return {fingerprint:hash(sums),candidates:candidates.slice(0,256),candidateTotal:candidates.length,estimatedReusableBytes:candidates.slice(0,256).reduce((n,r)=>n+r.bytes,0)};
  }
  async cleanupPreview(input,user) {
    ensure(!this.catalog.protection(this.id) && !this.catalog.restoreGuards[this.id],'迁移或恢复保护禁止整理');
    const plan=this.snapshotPlan(), token=randomUUID(), result={...plan,token,user,expires:Date.now()+10*60000,version:this.db.frame.revision};
    this.meta.atomic(()=>this.meta.put('cleanupPreview',token,result));return result;
  }
  async cleanup(input,user) {
    this.local();const previous=this.meta.get('cleanupAudit',input.token);if(previous){ensure(previous.user===user,'清理操作身份变化');return previous.status==='ROWS_REMOVED'?this.compact(previous):previous;}
    const p=this.meta.get('cleanupPreview',input.token);ensure(p && p.user===user && p.expires>Date.now() && p.version===input.version && input.confirm==='整理本地冗余快照','清理预览过期或未确认');
    ensure(!this.catalog.protection(this.id) && !this.catalog.restoreGuards[this.id],'迁移或恢复保护禁止整理');
    const now=this.snapshotPlan();ensure(now.fingerprint===p.fingerprint && hash(now.candidates)===hash(p.candidates),'引用或候选变化；请重新预览');
    // One SQLite transaction: crash before commit deletes nothing; after commit has its receipt.
    const result={token:input.token,user,status:'ROWS_REMOVED',rowsRemoved:p.candidates.length,reusableSQLiteBytes:p.estimatedReusableBytes,reclaimedFileBytes:0,at:Date.now()};
    this.meta.atomic(()=>{for(const c of p.candidates)this.meta.db.prepare("DELETE FROM v3_records WHERE ns='snapshot' AND k=? AND sum=?").run(c.key,c.sum);this.meta.put('cleanupAudit',input.token,result);});return this.compact(result);
  }
  compact(result) {
    const path=join(this.journal.directory,'v3-state-v2.sqlite');
    const bytes=()=>[path,path+'-wal'].reduce((n,p)=>{try{return n+statSync(p).size;}catch(e){if(e.code==='ENOENT')return n;throw e;}},0), before=bytes(), fs=statfsSync(this.journal.directory);
    if(before<=64*1024*1024 && fs.bavail*fs.bsize>before*3) {
      // SQLite's own atomic rebuild, on its existing connection. No copying of live DB/WAL.
      this.meta.db.exec('VACUUM');this.meta.db.exec('PRAGMA wal_checkpoint(TRUNCATE)');
      result.reclaimedFileBytes=Math.max(0,before-bytes());result.measurement='本次 SQLite VACUUM/checkpoint 前后主库与 WAL 实测差；后续审计日志可能重新占页';
    }else result.compactionDeferred='超过 64 MiB 重建预算或磁盘不足；删除页可被 SQLite 重用，未声称文件已释放';
    result.status='COMPLETED';this.meta.atomic(()=>this.meta.put('cleanupAudit',result.token,result));return result;
  }
}
