import { randomUUID } from 'node:crypto';
import { ensure, schemas } from './protocol.mjs';
import { differences } from './history.mjs';
import { sha } from './v3-protocol.mjs';
import { Budget, match, V3LocalStore, epochScope } from './v3-store.mjs';
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
    for(const field of ['since','until'])if(input[field]!=null)ensure(Number.isSafeInteger(Number(input[field])) && Number(input[field])>=0,'验证时间筛选无效');
    ensure(!input.since || !input.until || Number(input.since)<=Number(input.until),'验证时间范围无效');
    const ns=namespaces[status], scanned=this.meta.page(ns,after,limit), rows=[];
    for(const row of scanned) {
      const node=ns==='node'?row.value:this.meta.get('node',row.key);
      if(input.epoch && node?.epochId!==input.epoch || input.writer && node?.writerId!==input.writer || input.type && node?.type!==Number(input.type))continue;
      if(input.since && (!node?.verifiedAt || node.verifiedAt<Number(input.since)) || input.until && (!node?.verifiedAt || node.verifiedAt>Number(input.until)))continue;
      // DPV3 does not encode a wall-clock creation timestamp. Never substitute file mtime.
      rows.push({seq:row.seq,path:row.key,id:node?.objectId??row.key,...(node??{}),...row.value,status:ns==='node'?'VERIFIED':ns==='error'?'ERROR':ns==='epoch'?(row.value.active?'ACTIVE_EPOCH':'INACTIVE_EPOCH'):node?'VERIFIED':'OBSERVED',createdAt:null});
    }
    const last=scanned.at(-1)?.seq??after, s=this.journal.value;
    return {protocol:'v3',datasetId:s.binding?.datasetId,descriptor:s.binding,activeEpoch:s.activeEpoch,
      rows,next:last<this.meta.lastSeq(ns)?last:null,count:this.meta.count(ns),frontier:this.meta.page('frontier',0,65).map(r=>r.key),
      pendingPhase:s.pending?.phase??null,oldHistoryAuditComplete:false,completeDirectoryView:false,serverWriteProhibited:false,
      rollbackEnabled:!this.blockers().length,blockers:this.blockers(),timeFilterSupported:true,timeMeaning:'本机首次完成验证的时间；旧索引可能未知，不是作者创建时间',nextParameter:'after',
      archiveJobs:this.meta.page('archiveJob',0,32).map(r=>({id:r.key,status:r.value.status,location:r.value.location,after:r.value.after,ceiling:r.value.ceiling}))};
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
    const tips=this.meta.page('frontier',0,65).map(r=>r.key).sort(), settled=s.settled?.tips;
    return [!s.binding?'未绑定':null,s.pending && s.pending.phase!=='RECEIPTED'?'待确认上传':null,s.conflict?'未决冲突':null,s.rotation?'换代尚未完成':null,s.initialization?.length?'初始化未完成':null,
      s.binding && (!settled || hash([...settled].sort())!==hash(tips))?'已知前沿尚未合入当前基线，请先检查并同步':null,
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
  async audit(options={}) {
    if(this.catalog.restoreGuards[this.id])return this.revalidateRestored();
    const objectSteps=options.objectSteps??16;ensure(Number.isInteger(objectSteps) && objectSteps>=0 && objectSteps<=32,'审计对象预算无效');
    const {budget,remote,cache,history}=this.context(); let status='BOUNDED';
    try {
      await history.open(); const discovery=new V3Discovery(this.journal,remote,this.journal.value.binding.datasetId);await discovery.scan(16);
      await this.observeCurrentEpochs(history,remote,discovery);
      for(let n=0;n<16;n++){const row=this.meta.page('todo',0,1)[0];if(!row)break;await history.verify(row.key);}
      history.drainActivation(); await history.auditObjects(objectSteps);
    } catch(e) { if(!/budget|incomplete/.test(e.message))throw e;status='BUDGET'; }
    this.local(); const result={status,round:budget.roundId,checkedAt:Date.now(),scanPending:this.meta.count('scan'),validationPending:this.meta.count('todo'),
      verified:this.meta.count('node'),observed:this.meta.count('observed'),errors:this.meta.count('error'),scopeCursor:this.meta.get('control','scopeAudit'),objectCursor:this.meta.get('control','objectAudit'),oldHistoryAuditComplete:false,completeDirectoryView:false};
    this.meta.atomic(()=>this.meta.put('maintenance','audit',result)); return result;
  }
  async observeCurrentEpochs(history,remote,discovery) {
    const s=this.journal.value, epochs=[...new Set([s.activeEpoch,...this.meta.page('frontier',0,65).map(r=>history.node(r.key)?.epochId)].filter(Boolean))];
    ensure(epochs.length<=8,'维护当前 Epoch 预算超限，请先同步收敛');
    for(const id of epochs){const scope=epochScope(s.binding.datasetId,id);discovery.observe(scope,await remote.list(scope));}
  }
  async revalidateRestored() {
    this.local();const guard=this.catalog.restoreGuards[this.id];ensure(guard?.protocol==='v3' && hash(guard.binding??null)===hash(this.journal.value.binding??null),'恢复绑定身份变化');
    const connection=this.catalog.connection(this.catalog.row(this.id).connectionId);ensure(!guard.endpointHash || guard.endpointHash===hash(connection),'恢复端点身份变化');
    if(!this.journal.value.binding) {
      ensure(!this.journal.value.pending && !this.meta.count('node'),'未绑定恢复状态含历史或候选；保留隔离');
      await this.clearRestoreGuard(guard);return {status:'RESTORE_UNBOUND_PRIVATE_STATE_VERIFIED',publishingIsolated:false,descriptorCreated:false};
    }
    const {remote,cache}=this.context(), b=this.journal.value.binding;
    const descriptor=await remote.read(b.descriptorPath);ensure(descriptor && sha(descriptor)===b.descriptorHash && match(b.descriptorPath,descriptor).type===1,'恢复 Descriptor 回读失败');
    this.local();let cursor=this.meta.get('restoreValidation',guard.restoredAt)??{after:0,ceiling:this.meta.lastSeq('node')};
    for(const r of this.meta.page('node',cursor.after,16,cursor.ceiling)) {
      const raw=await remote.read(r.key);ensure(raw && sha(raw)===r.value.hash,'恢复已知引用缺失或变化；继续隔离发布');await cache.put(r.key,raw);
      cursor.after=r.seq;this.local();this.meta.atomic(()=>this.meta.put('restoreValidation',guard.restoredAt,cursor));
    }
    if(this.meta.page('node',cursor.after,1,cursor.ceiling).length)return {status:'RESTORE_VALIDATING',cursor,publishingIsolated:true};
    ensure(!this.meta.count('error') && !this.meta.count('todo') && !this.meta.get('control','walk'),'恢复存在未验证历史或异常，保留发布隔离');
    await this.clearRestoreGuard(guard);
    return {status:'RESTORE_KNOWN_REFERENCES_VERIFIED',publishingIsolated:false,completeRemoteHistory:false};
  }
  async clearRestoreGuard(guard) {
    const c=this.catalog, clear=c.guardQueue.then(async()=>{
      ensure(c.restoreGuards[this.id]===guard,'恢复保护变化');const next={...c.restoreGuards};delete next[this.id];await c.store.save('restore-guards.json',next);c.restoreGuards=next;
    });c.guardQueue=clear.catch(()=>{});await clear;
  }
  async preview(input,user) {
    const audit=await this.audit({objectSteps:0});ensure(audit.status!=='BUDGET','回退预检预算耗尽；进度已保存，请继续历史检查');
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
    const discovery=new V3Discovery(this.journal,remote,this.journal.value.binding.datasetId);await discovery.scan(32);await this.observeCurrentEpochs(history,remote,discovery);
    ensure(p.fingerprint===this.fingerprint() && !this.blockers().length,'回退预览过期或新历史尚未验证');
    const result={operationId:input.token,user,target:p.target,status:'LOCAL_SAVED',savedAt:Date.now()};
    await this.journal.transaction(s=>{ensure(p.fingerprint===this.fingerprint(),'回退输入变化');s.local=state;s.revision++;s.decision=null;this.meta.put('rollbackOperation',input.token,result);},true);
    // Normal engine stages a fresh Commit with current parents; never move frontier backwards.
    await this.runtime.synchronize();return {...result,receipt:this.journal.value.receipt};
  }
  eligibility() {
    this.local();const archives=this.meta.page('archiveJob',0,256).filter(r=>r.value.status==='VERIFIED').map(r=>({id:r.key,manifestHash:r.value.manifestHash,
      recordedReadbackVerified:true,currentReadbackPerformed:false,ceiling:r.value.ceiling,coversCurrentKnownNodes:r.value.ceiling===this.meta.lastSeq('node'),location:r.value.location}));
    const s=this.journal.value;
    return {status:'NOT_ELIGIBLE',automaticDelete:false,serverWriteProhibited:false,serverSealingEvidence:null,unknownOldWriters:true,completeRemoteHistory:false,
      knownArchiveEvidence:archives,knownClosureRecoverable:archives.some(a=>a.coversCurrentKnownNodes)?'ARCHIVED_AT_RECORDED_READBACK':'UNPROVEN',
      unverifiedObserved:this.meta.count('todo'),anomalies:this.meta.count('error'),scanPending:this.meta.count('scan'),
      recoveryDependencies:{pending:s.pending?.phase??null,conflict:!!s.conflict,rotation:!!s.rotation,migration:!!this.catalog.protection(this.id),restore:!!this.catalog.restoreGuards[this.id]},
      reasons:['普通 WebDAV 无服务端封存证明','未知旧 Writer 及未观察路径不可排除','Checkpoint 和管理员确认不构成永久停写证明']};
  }
  async archive(input,user) {
    this.local();let job=input.jobId?this.meta.get('archiveJob',input.jobId):null;
    if(!job) {
      ensure(!input.jobId && this.journal.value.binding,'归档任务不存在');
      const location=await this.catalog.grants.protectedFolder(input.rootId,input.relative??'','history-'+randomUUID());
      job={id:randomUUID(),user,location,binding:this.journal.value.binding,startedAt:Date.now(),ceiling:this.meta.lastSeq('node'),after:0,formatVersion:2,pageCount:0,chainHash:null,buffer:[],
        frontier:this.meta.page('frontier',0,65).map(r=>r.key),observed:this.meta.count('observed'),anomalies:this.meta.count('error'),scanPending:this.meta.count('scan'),validationPending:this.meta.count('todo'),status:'COPYING',
        evidence:{lastAudit:this.meta.get('maintenance','audit'),scopeCursor:this.meta.get('control','scopeAudit'),objectCursor:this.meta.get('control','objectAudit'),
          incompleteScopes:this.meta.page('scan',0,16),unverifiedObjects:this.meta.page('todo',0,16),anomalySample:this.meta.page('error',0,16).map(r=>({seq:r.seq,path:r.key,reason:String(r.value.reason??'').slice(0,512)})),samplesOnly:true}};
      this.meta.atomic(()=>this.meta.put('archiveJob',job.id,job));
    }
    ensure(job.user===user && hash(job.binding)===hash(this.journal.value.binding),'归档身份变化');
    if(job.status==='VERIFIED')return this.archiveResult(job);
    const budget=new Budget(), remote=remoteV3(this.catalog,this.id,budget), cache=new IndexedV3Cache(this.journal.directory,budget), archive=new V3LocalStore(job.location,this.catalog.grants,budget);
    this.meta.budget=budget;
    const rows=this.meta.page('node',job.after,1,job.ceiling), objects=job.buffer??[];
    for(const row of rows) {
      let bytes=await cache.get(row.key);const cached=!!bytes;if(!bytes)bytes=await remote.read(row.key);
      ensure(bytes && sha(bytes)===row.value.hash,'归档对象缺失或身份变化');match(row.key,bytes);
      const old=await archive.read(row.key);ensure(!old || old.equals(bytes),'归档同路径身份冲突');
      if(!old){await archive.prepare(row.key.slice(0,row.key.lastIndexOf('/')+1));await archive.putOnce(row.key,bytes);}
      ensure((await archive.read(row.key))?.equals(bytes),'实际归档回读失败');
      objects.push({path:row.key,type:row.value.type,epoch:row.value.epochId,length:bytes.length,sha256:row.value.hash,refs:row.value.refs,
        verification:{verifiedAt:row.value.verifiedAt??null,cacheUsed:cached,currentRemoteRead:!cached,recordedRemoteReadback:this.meta.get('evidence',row.key),observed:this.meta.get('observed',row.key)}});job.after=row.seq;
    }
    job.buffer=objects;
    const atEnd=!this.meta.page('node',job.after,1,job.ceiling).length, pageNumber=job.formatVersion===2?job.pageCount:job.pages.length;
    if(objects.length && (objects.length>=2 || atEnd)) {
      ensure(pageNumber<1000000,'归档清单页数量达到容器限制；保留进度');
      const name='v3-manifest-'+String(pageNumber).padStart(6,'0')+'.json', bytes=Buffer.from(JSON.stringify({format:job.formatVersion===2?'DormPanel-v3-archive-page-2':'DormPanel-v3-archive-page-1',binding:job.binding,...(job.formatVersion===2?{previousHash:job.chainHash}:{}),objects}));
      // Retry after a crash: a previously linked identical page is accepted, never overwritten.
      let old=null;try{old=await this.catalog.grants.readPrivate(job.location,name);}catch(e){if(e.code!=='ENOENT')throw e;}
      if(old)ensure(old.equals(bytes),'归档清单身份变化');else await this.catalog.grants.writePrivate(job.location,name,bytes);
      ensure((await this.catalog.grants.readPrivate(job.location,name)).equals(bytes),'清单实际回读失败');
      if(job.formatVersion===2){job.pageCount++;job.chainHash=sha(bytes);}else job.pages.push({name,sha256:sha(bytes)});job.buffer=[];
    }
    if(atEnd) {
      const manifest={format:job.formatVersion===2?'DormPanel-v3-history-2':'DormPanel-v3-history-1',binding:job.binding,frontier:job.frontier,...(job.formatVersion===2?{pageCount:job.pageCount,chainHash:job.chainHash}:{pages:job.pages}),startedAt:job.startedAt,
        verifiedAt:Date.now(),knownReferenceClosure:true,completeRemoteHistory:false,serverSealed:false,unknownPaths:true,
        observed:job.observed,anomalies:job.anomalies,scanPending:job.scanPending,validationPending:job.validationPending,evidence:job.evidence??null,
        coverage:'仅包含截止冻结索引位置的已验证对象及已知引用；未证明远端全目录或旧 Writer 停写'};
      let raw=null;try{raw=await this.catalog.grants.readPrivate(job.location,'verified.json');}catch(e){if(e.code!=='ENOENT')throw e;}
      if(raw){const previous=JSON.parse(raw);ensure(hash(previous.binding)===hash(manifest.binding) && hash(previous.pages??previous.chainHash)===hash(manifest.pages??manifest.chainHash) && previous.pageCount===manifest.pageCount,'归档完成清单变化');}
      else {raw=Buffer.from(JSON.stringify(manifest));await this.catalog.grants.writePrivate(job.location,'verified.json',raw);}
      ensure((await this.catalog.grants.readPrivate(job.location,'verified.json')).equals(raw),'完成清单实际回读失败');
      job.status='VERIFIED';job.manifestHash=sha(raw);
    }
    this.local();this.meta.atomic(()=>this.meta.put('archiveJob',job.id,job));return this.archiveResult(job);
  }
  archiveResult(job) {return {...job,message:job.status==='VERIFIED'?'冻结索引范围的对象与清单已实际回读验证；远端全目录、未知旧 Writer 和服务端封存仍未被证明。':'本轮归档进度已保存；请继续同一任务完成剩余对象回读。'};}
  async restoreArchive(input,user) {
    this.local();const location={rootId:input.rootId,relative:input.relative,directoryIdentity:input.directoryIdentity},raw=await this.catalog.grants.readPrivate(location,'verified.json'), manifest=JSON.parse(raw);
    const chained=manifest.format==='DormPanel-v3-history-2', pageCount=chained?manifest.pageCount:manifest.pages?.length;
    ensure((chained || manifest.format==='DormPanel-v3-history-1') && hash(manifest.binding)===hash(this.journal.value.binding) && Number.isSafeInteger(pageCount) && pageCount>0 && pageCount<=1000000 && (chained || Array.isArray(manifest.pages)),'归档格式或 Descriptor 身份变化');
    const key=sha(raw), old=this.meta.get('archiveRestore',key), task=old??{next:0,verifiedAfter:0,user,location,manifestHash:key,status:'READING'}, budget=new Budget(), cache=new IndexedV3Cache(this.journal.directory,budget), archive=new V3LocalStore(location,this.catalog.grants,budget);
    ensure(task.user===user && hash(task.location)===hash(location),'归档恢复操作身份变化');
    const prefix='archiveVerify:'+key+':', meta=this.meta;
    const scoped={get:(n,k)=>meta.get(prefix+n,k),put:(n,k,v)=>meta.put(prefix+n,k,v),remove:(n,k)=>meta.remove(prefix+n,k),
      page:(n,a,l,t)=>meta.page(prefix+n,a,l,t),count:n=>meta.count(prefix+n),lastSeq:n=>meta.lastSeq(prefix+n),atomic:fn=>meta.atomic(fn),set budget(b){meta.budget=b;}};
    let restored=0;
    if(task.next<pageCount) {
      const n=task.next, p=chained?{name:'v3-manifest-'+String(n).padStart(6,'0')+'.json'}:manifest.pages[n];
      const bytes=await this.catalog.grants.readPrivate(location,p.name), pageHash=sha(bytes), page=JSON.parse(bytes);
      ensure(chained ? page.previousHash===(task.chainHash??null) : pageHash===p.sha256,'归档页哈希链校验失败');
      ensure(!task.pageHash || task.pageHash===pageHash,'处理中归档页身份变化');
      ensure(hash(page.binding)===hash(manifest.binding) && page.format===(chained?'DormPanel-v3-archive-page-2':'DormPanel-v3-archive-page-1') && Array.isArray(page.objects) && page.objects.length>0 && page.objects.length<=8,'归档页格式无效');
      const position=task.objectIndex??0, object=page.objects[position];ensure(object,'归档对象游标损坏');
      const b=await archive.read(object.path);ensure(b && b.length===object.length && sha(b)===object.sha256,'归档对象校验失败');
      const decoded=match(object.path,b);ensure(decoded.datasetId===manifest.binding.datasetId && (decoded.type===1?object.path===manifest.binding.descriptorPath && sha(b)===manifest.binding.descriptorHash:decoded.descriptorHash===manifest.binding.descriptorHash),'归档对象数据集变化');
      await cache.put(object.path,b);restored++;
      task.pageHash=pageHash;task.objectIndex=position+1;
      if(task.objectIndex===page.objects.length){task.next++;task.chainHash=pageHash;task.objectIndex=0;task.pageHash=null;}
      this.local();meta.atomic(()=>{ensure(!scoped.get('input',object.path),'归档清单对象重复');scoped.put('input',object.path,{hash:object.sha256});meta.put('archiveRestore',key,task);});
    }
    if(task.next===pageCount && task.status!=='COMPLETE') {
      ensure(!chained || task.chainHash===manifest.chainHash,'归档完成哈希链不匹配');
      // Reuse the existing causal/checkpoint verifier with paged scratch namespaces.
      // Cache bytes alone are never promoted to a historical proof.
      const membership=p=>ensure(scoped.get('input',p),'归档引用不在实际清单中');
      const verifiedCache={load:async(p,r,h)=>{membership(p);return cache.load(p,r,h);},pin:(p,o)=>cache.pin(p,o)};
      const history=new V3History({meta:scoped,roundId:budget.roundId},verifiedCache,archive,manifest.binding.descriptorPath,manifest.binding.descriptorHash,budget);
      try {
        await history.open();
        for(const row of scoped.page('input',task.verifiedAfter,8)){await history.verify(row.key,row.value.hash);task.verifiedAfter=row.seq;}
        history.drainActivation();
        if(!scoped.page('input',task.verifiedAfter,1).length){ensure(scoped.count('node')===scoped.count('input'),'归档闭包对象集合不等价');task.status='COMPLETE';}
        else task.status='VALIDATING';
      }catch(e){if(!/budget/.test(e.message))throw e;task.status='BUDGET';}
      this.local();meta.atomic(()=>meta.put('archiveRestore',key,task));
    }
    return {restoredObjects:restored,next:task.next,totalPages:pageCount,status:task.status,complete:task.status==='COMPLETE',
      knownReferenceClosureVerified:task.status==='COMPLETE',remoteWritten:false,completeRemoteHistory:false};
  }
  async space() {
    this.local();const {readdir,lstat}=await import('node:fs/promises'),{join}=await import('node:path');
    const totals={sqlite:0,cacheBlobs:0,witness:0,damaged:0,otherProtected:0}, root=this.journal.directory;let count=0;
    const walk=async dir=>{for(const e of await readdir(dir,{withFileTypes:true})){ensure(++count<=20000,'空间扫描预算耗尽，未产生清理决策');const path=join(dir,e.name),s=await lstat(path);if(s.isSymbolicLink()){totals.otherProtected+=s.size;continue;}if(s.isDirectory())await walk(path);else {const category=/^v3-state-v2.sqlite/.test(e.name)?'sqlite':e.name==='object.dpv'?'cacheBlobs':e.name==='witness.json'?'witness':e.name.startsWith('damaged-')?'damaged':'otherProtected';totals[category]+=s.size;}}};await walk(root);
    return {...this.summary(),bytes:totals,measurement:'实际文件逻辑字节；SQLite 页内回收不等于文件缩小',snapshotRows:this.meta.count('snapshot'),protected:['local/baseline/Pending/capture/receipt','frontier/祖先/witness/pin','冲突/决定/迁移/备份与恢复/回退预览','所有缓存及损坏隔离材料'],archives:this.meta.count('archiveJob'),remoteGC:this.eligibility()};
  }
  summary() {
    this.local();const seq=this.meta.lastSeq('cleanupAudit'),lastCleanup=seq?this.meta.page('cleanupAudit',seq-1,1)[0]?.value:null;
    return {knownVerifiedObjects:this.meta.count('node'),observedObjects:this.meta.count('observed'),validationPending:this.meta.count('todo'),scanPending:this.meta.count('scan'),
      audit:this.meta.get('maintenance','audit'),lastCleanup,reclaimedBytes:lastCleanup?.reclaimedFileBytes??0,reclaimedBytesScope:'LAST_RECORDED_COMPACTION_INTERVAL',oldHistoryAuditComplete:false,completeRemoteHistory:false};
  }
  snapshotPlan() {
    this.local();const refs=new Set(),candidates=[],sums=[];let rows=0;
    ensure(this.meta.get('format','version')?.value===2,'未知元数据版本，拒绝整理');
    const known=new Set(['format','installation','workspace','state','node','identity','epoch','frontier','covered','observed','scope','scan','todo','listing','activate','control','error','evidence','failedWalk','proof','decision','conflictHistory','hostMigration','hostMigrationHeader','hostV2Fork','rollbackPreview','rollbackOperation','archiveJob','archiveRestore','maintenance','restoreValidation']);
    const visit=v=>{if(typeof v==='string' && /^[0-9a-f]{64}$/.test(v))refs.add(v);else if(Array.isArray(v))v.forEach(visit);else if(v && typeof v==='object')Object.values(v).forEach(visit);};
    // Every durable namespace participates, including unknown extensions. No age heuristics.
    for(const r of this.meta.db.prepare("SELECT ns,k,v,sum FROM v3_records WHERE ns NOT IN ('snapshot','cleanupPreview','cleanupAudit') ORDER BY seq").iterate()) {
      const namespace=r.ns.replace(/^archiveVerify:[0-9a-f]{64}:/,'');
      ensure(known.has(namespace) || namespace==='input' && namespace!==r.ns || /^(?:members:[0-9a-f-]{36}|epochFrontier:[0-9a-f-]{36}(?::covered)?|proof:[0-9a-f]{64}(?::(?:queue|seen|tips|covered))?|(?:stack|visiting):[0-9a-f-]{36})$/.test(namespace),'未知元数据命名空间，保护全部快照');
      ensure(++rows<=20000,'清理分析预算耗尽；不产生可删除候选');ensure(sha(Buffer.from(r.ns+'\0'+r.k+'\0'+r.v))===r.sum,'损坏引用，拒绝整理');
      this.meta.budget.charge(Buffer.byteLength(r.v),0);const value=JSON.parse(r.v);visit(value);sums.push(r.ns+'\0'+r.k+'\0'+r.sum);
    }
    for(const r of this.meta.db.prepare("SELECT k,v,sum FROM v3_records WHERE ns='snapshot' ORDER BY seq").iterate()) {
      ensure(++rows<=20000,'清理分析预算耗尽');ensure(sha(Buffer.from('snapshot\0'+r.k+'\0'+r.v))===r.sum,'损坏快照，拒绝整理');
      this.meta.budget.charge(Buffer.byteLength(r.v),0);if(!refs.has(r.k)){ensure(sha(Buffer.from(JSON.parse(r.v).bytes,'base64'))===r.k,'快照损坏，拒绝整理');candidates.push({key:r.k,bytes:Buffer.byteLength(r.v),sum:r.sum});}
    }
    // Cache pins are filesystem metadata. A new pin must invalidate an existing preview too.
    const scan=dir=>{let entries;try{entries=readdirSync(dir,{withFileTypes:true});}catch(e){if(e.code==='ENOENT')return;throw e;}
      for(const e of entries.sort((a,b)=>a.name.localeCompare(b.name))){ensure(++rows<=20000,'引用扫描预算耗尽');const p=join(dir,e.name),s=lstatSync(p);ensure(!s.isSymbolicLink(),'未知缓存链接，拒绝整理');if(s.isDirectory())scan(p);else if(e.name==='witness.json'){ensure(s.size<=32768,'witness 超限');const raw=readFileSync(p);this.meta.budget.charge(raw.length,0);const w=JSON.parse(raw);ensure(w.version===2 && Array.isArray(w.pins),'未知 witness 版本');sums.push(p+'\0'+sha(raw));}else {ensure(e.name==='object.dpv'||/^damaged-[0-9a-f-]{36}\.dpv$/.test(e.name),'未知缓存文件，拒绝整理');sums.push(p+'\0'+s.size+'\0'+s.mtimeMs+'\0'+s.ctimeMs);}}
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
