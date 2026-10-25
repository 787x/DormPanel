import { randomUUID, X509Certificate } from 'node:crypto';
import { ensure } from './protocol.mjs';
import { sha } from './v3-protocol.mjs';
import { Budget, V3WebDavStore, V3LocalStore, match, epochScope } from './v3-store.mjs';
import { IndexedV3Cache } from './v3-index.mjs';
import { V3History, V3Discovery } from './v3-history.mjs';
import { json } from './store.mjs';
import { EndpointJobs, ENDPOINT_TTL } from './v3-endpoint-lifecycle.mjs';

const hash=v=>sha(Buffer.from(json(v)));

// Candidate graph lives in the existing owner's SQLite transaction, never in the
// active frontier. Cache witnesses remain immutable and shared by the same owner.
function candidateMeta(meta,token) {
  const prefix='endpoint:'+token+':';
  return new Proxy(meta,{get(target,key) {
    if(['get','put','remove','page','count','lastSeq'].includes(key))return (ns,...args)=>target[key](prefix+ns,...args);
    const value=target[key];return typeof value==='function'?value.bind(target):value;
  },set(target,key,value){target[key]=value;return true;}});
}

/** Access-only move. No engine round, publication or business receipt is invoked.
 * The Catalog manifest is the sole endpoint identity on fnOS: row + independent
 * connection + durable decision are committed by ONE fsync/rename, without a
 * cross-file half-commit. Old runtime references resolve that same Catalog. */
export class V3EndpointMove {
  constructor(catalog,id) {
    this.catalog=catalog;this.id=id;this.db=catalog.db(id);ensure(this.db.activeV3,'需要活动 v3 工作区');
    this.journal=this.db.activeV3.journal;this.meta=this.journal.meta;
    this.jobs=new EndpointJobs(this);
  }
  reap(){return this.jobs.reap();}
  local(){this.meta.budget=new Budget({requests:0});}
  fingerprint(){
    this.local();const m=this.meta;
    return hash({frame:this.db.snapshot(),catalog:this.catalog.manifest.revision,node:m.lastSeq('node'),observed:m.lastSeq('observed'),
      frontier:m.page('frontier',0,65),todo:m.count('todo'),error:m.count('error'),walk:m.get('control','walk')});
  }
  protect(){
    const s=this.journal.value;
    ensure(s.binding && !s.initialization?.length && !s.rotation,'初始化或换代尚未结束');
    ensure(!this.catalog.protection(this.id) && !this.catalog.restoreGuards[this.id],'迁移或备份恢复保护，请先恢复原端点并验证');
    ensure(!s.pending || ['STAGED','RECEIPTED'].includes(s.pending.phase),'存在未回执 PUT，请先在原端点只读恢复并完成回执；新端点 404 不能证明原 PUT 未发生');
    ensure(!this.meta.count('todo') && !this.meta.count('error') && !this.meta.get('control','walk'),'已知历史尚未验证或有异常；请先在原端点继续验证');
  }
  record(token,user){
    this.local();const job=this.meta.get('endpointJob',token)??this.meta.get('endpointReceipt',token)??this.meta.get('endpointRetired',token);ensure(job && job.workspaceId===this.id && job.user===user,'预检已回收、不存在或归属不符');return job;
  }
  save(job){this.local();this.meta.atomic(()=>this.meta.put('endpointJob',job.token,job));}
  status(token,user){
    const job=this.record(token,user),receipt=this.catalog.manifest.endpointReceipts?.[token];
    return receipt??this.summary(job);
  }
  latest(user){
    this.local();const row=this.meta.db.prepare("SELECT ns,k FROM v3_records WHERE ns IN ('endpointJob','endpointRetired','endpointReceipt') AND json_extract(v,'$.user')=? ORDER BY COALESCE(json_extract(v,'$.createdAt'),0) DESC,seq DESC LIMIT 1").get(user);
    if(!row)return {status:'NOT_STARTED',workspaceId:this.id};
    this.meta.get(row.ns,row.k);return this.status(row.k,user); // verify checksum before returning an indexed selection
  }
  summary(j){
    const ended=this.jobs.terminal(j,Date.now())??(j.fingerprint&&j.fingerprint!==this.fingerprint()?'STALE':null);if(ended||!j.binding)return {token:j.token,workspaceId:this.id,status:ended??j.status,materials:j.status==='CLEANING'?'CLEANING':j.binding?'RETAINED':'RETIRED'};
    return {token:j.token,workspaceId:this.id,datasetId:j.binding.datasetId,descriptor:j.binding,status:j.status,expiresAt:this.jobs.deadline(j),version:j.version,catalogVersion:j.catalogVersion,
    candidate:{type:j.candidate.type,name:j.candidate.name,...(j.candidate.config?(j.candidate.type==='webdav'?{
      url:j.candidate.config.url,username:j.candidate.config.username,allowInsecureHttp:j.candidate.config.allowInsecureHttp===true,
      trust:j.candidate.config.certificatePem?new X509Certificate(j.candidate.config.certificatePem).fingerprint256:'SYSTEM_CA'
    }:{rootId:j.candidate.config.rootId,relative:j.candidate.config.relative}):{})},knownReadback:j.after,knownCeiling:j.ceiling,observedAfter:j.observedAfter,
    facts:j.facts??{},failure:j.failure??null,oldEndpointNewChanges:'UNPROVEN',completeDirectoryView:false,serverWriteProhibited:false,otherDevicesReceived:'UNKNOWN',
    impact:'仅目标工作区改用独立连接；原共享连接与其他工作区保留。旧端点仍可能接收未知 Writer。'};}
  async preview(input,user){
    this.protect();ensure(!input.createPrivate,'只读预检禁止创建目录');
    await this.reap();const live=this.meta.page('endpointJob',0,128).filter(r=>!this.jobs.terminal(r.value,Date.now()));
    ensure(live.length<64&&live.filter(r=>r.value.user===user).length<8,'有效预检已达上限；请继续或取消已有任务（24 小时固定到期）');
    ensure(this.meta.count('endpointJob')<128,'候选清理尚未完成；请继续触发有界清理');
    const candidate=await this.catalog.candidate(input,input.connectionId?this.catalog.connection(input.connectionId):null),token=randomUUID(),s=this.journal.value;
    candidate.id=randomUUID();
    // Explicit type conversion is supported only via the existing authorized
    // LocalGrants and TLS/HTTP policy. candidate() does not bypass either.
    const job={token,user,workspaceId:this.id,candidate,binding:structuredClone(s.binding),version:this.db.frame.revision,
      catalogVersion:this.catalog.manifest.revision,fingerprint:this.fingerprint(),after:0,ceiling:this.meta.lastSeq('node'),
      observedAfter:0,observedCeiling:this.meta.lastSeq('observed'),status:'VALIDATING',createdAt:Date.now(),expiresAt:Date.now()+ENDPOINT_TTL,facts:{}};
    this.save(job);return this.resume(token,user);
  }
  remote(job,budget){const c=job.candidate;return this.catalog.v3RemoteFactory?this.catalog.v3RemoteFactory(c,budget):c.type==='local'?new V3LocalStore(c.config,this.catalog.grants,budget):new V3WebDavStore(c.config,budget);}
  async resume(token,user,budget=new Budget()){
    const j=this.record(token,user);if(this.jobs.terminal(j,Date.now())||!j.binding)return this.status(token,user);
    ensure(j.status!=='STALE','候选历史变化导致预览过期，请新建预检');
    if(j.fingerprint!==this.fingerprint()){j.status='STALE';this.save(j);return this.summary(j);}this.protect();
    const remote=this.remote(j,budget),cache=new IndexedV3Cache(this.journal.directory,budget),m=candidateMeta(this.meta,token);
    this.meta.budget=budget;
    // Always GET Descriptor; a cache hit cannot authenticate the candidate.
    j.status='VALIDATING';j.facts={knownHistoryComplete:false};delete j.failure;
    try {
      const bytes=await remote.read(j.binding.descriptorPath);
      j.facts.networkReachable=true;j.facts.tlsAndAuthenticationValid=true;j.facts.descriptorExact=false;
      ensure(bytes && sha(bytes)===j.binding.descriptorHash && match(j.binding.descriptorPath,bytes).datasetId===j.binding.datasetId,'候选 Descriptor 缺失或完整 RAW 身份变化');
      j.facts.descriptorExact=true;
      for(const row of this.meta.page('node',j.after,16,j.ceiling)) {
        const raw=await remote.read(row.key);ensure(raw && sha(raw)===row.value.hash,'新端点已知对象缺失或同路径内容变化');match(row.key,raw);await cache.put(row.key,raw);
        m.atomic(()=>m.put('todo',row.key,{path:row.key}));j.after=row.seq;this.save(j);this.meta.budget=budget;
      }
      for(const row of this.meta.page('observed',j.observedAfter,16,j.observedCeiling)) {
        m.atomic(()=>m.put('todo',row.key,{path:row.key}));j.observedAfter=row.seq;this.save(j);this.meta.budget=budget;
      }
      // Candidate closure verification must also read newly referenced paths at
      // the candidate. Immutable cache content alone never proves availability.
      const actualCache={get:p=>cache.get(p),pin:(...a)=>cache.pin(...a),load:async(p,_remote,expected)=>{
        const raw=await remote.read(p);ensure(raw && (!expected || sha(raw)===expected),'候选祖先缺失或哈希变化');await cache.put(p,raw);return raw;
      }};
      const history=new V3History({meta:m},actualCache,remote,j.binding.descriptorPath,j.binding.descriptorHash,budget);await history.open();
      const discovery=new V3Discovery({meta:m},remote,j.binding.datasetId);await discovery.scan(32);
      for(let n=0;n<16;n++){const row=m.page('todo',0,1)[0];if(!row)break;await history.verify(row.key);}
      history.drainActivation();
      const complete=!this.meta.page('node',j.after,1,j.ceiling).length && !this.meta.page('observed',j.observedAfter,1,j.observedCeiling).length &&
        !m.count('scan') && !m.count('todo') && !m.count('error') && !m.get('control','walk');
      j.status=complete?'READY':'VALIDATING';j.facts.knownHistoryComplete=complete;
      j.facts.extraObjects=Math.max(0,m.count('node')-this.meta.count('node'));j.frontier=history.tips();j.candidateNodes=m.lastSeq('node');
      if(complete)j.readyAt=Date.now();
    } catch(e){j.status='VALIDATING';j.failure={stage:!j.facts.networkReachable?'ACCESS':!j.facts.descriptorExact?'DESCRIPTOR':'HISTORY',code:/401|403/.test(e.message)?'AUTH':/certificate|TLS|SSL/i.test(e.message)?'TLS':/budget|incomplete/.test(e.message)?'BUDGET':'UNVERIFIED'};this.save(j);if(!/budget|incomplete/.test(e.message))throw e;}
    this.save(j);return this.summary(j);
  }
  async cancel(token,user){const j=this.record(token,user);ensure(!this.catalog.manifest.endpointReceipts?.[token],'切换已提交，不能取消已完成决策');
    ensure(!(await this.jobs.protectedTokens(Date.now())).has(token),'批准或结果待恢复，保留任务依据');
    if(j.binding){j.status='CANCELLED';delete j.candidate.config;this.save(j);}await this.reap();return this.status(token,user);}
  async confirm(input,user){
    const j=this.record(input.token,user),c=this.catalog,previous=c.manifest.endpointReceipts?.[j.token];
    ensure(input.confirm===(j.binding?.datasetId??j.result?.datasetId),'请输入同一 datasetId 确认');if(previous)return previous;
    return c.exclusive(input.catalogVersion,async()=>{
      ensure(!c.busy.size,'工作区存在网络操作');await this.db.queue;
      ensure(!this.jobs.terminal(j,Date.now())&&j.status==='READY' && Date.now()-j.readyAt<10*60000 && j.version===input.version && j.fingerprint===this.fingerprint(),'端点预览过期，请重新验证');this.protect();
      const budget=new Budget(),before=j.candidateNodes,frontier=hash(j.frontier);await this.resume(j.token,user,budget);const current=this.record(j.token,user);
      if(current.status!=='READY' || current.candidateNodes!==before || hash(current.frontier)!==frontier){current.status='STALE';this.save(current);throw Error('候选新增历史，预览过期；请新建预检并重新确认');}
      const remote=this.remote(current,budget),m=candidateMeta(this.meta,j.token);this.meta.budget=budget;
      for(const path of current.frontier){const raw=await remote.read(path);ensure(raw && sha(raw)===m.get('node',path).hash,'候选前沿身份变化');}
      // Re-list every currently active frontier Epoch. Additional objects force
      // another preview round; no business state is adopted during the move.
      for(const epoch of new Set(current.frontier.map(p=>m.get('node',p).epochId))) {
        for(const path of await remote.list(epochScope(j.binding.datasetId,epoch)))ensure(m.get('node',path),'候选存在未知历史，请继续验证');
      }
      ensure(j.fingerprint===this.fingerprint(),'确认期间本地输入变化');
      const next=structuredClone(c.manifest);ensure(next.connections.length<64,'连接数量超限');next.connections.push(j.candidate);
      next.workspaces.find(w=>w.id===this.id).connectionId=j.candidate.id;
      const result={token:j.token,workspaceId:this.id,datasetId:j.binding.datasetId,status:'COMMITTED',connectionId:j.candidate.id,committedAt:Date.now()};
      next.endpointReceipts={...next.endpointReceipts,[j.token]:result};next.revision++;
      try { await c.commit(next); } catch(e) { c.invalidated=true;throw e; } // ambiguous fsync/rename: old memory cannot publish
      return result;
    });
  }
}
