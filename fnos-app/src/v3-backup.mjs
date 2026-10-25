import { DatabaseSync } from 'node:sqlite';
import { readFile, open } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { constants as C } from 'node:fs';
import { ensure } from './protocol.mjs';
import { decode, sha } from './v3-protocol.mjs';
import { match } from './v3-store.mjs';
import { canonical, Workspace } from './workspace.mjs';
import { createHash } from 'node:crypto';

export const v3File = name => /^workspaces\/[0-9a-f-]{36}\/v3\/(?:v3-state-v2.sqlite|sync-v3-index-v2\/[0-9a-f]{2}\/[0-9a-f]{2}\/[0-9a-f]{60}\/(?:witness.json|object.dpv|damaged-[0-9a-f-]{36}.dpv))$/.test(name);
export const v3Directory = name => /^workspaces\/[0-9a-f-]{36}\/v3(?:\/sync-v3-index-v2(?:\/[0-9a-f]{2}(?:\/[0-9a-f]{2}(?:\/[0-9a-f]{60})?)?)?)?$/.test(name);
export async function sqliteSnapshot(source,target) {
  const db=new DatabaseSync(source,{readOnly:true});
  try { db.exec("VACUUM INTO '"+target.replaceAll("'","''")+"'"); }
  finally {db.close();}
  // VACUUM INTO itself does not guarantee fsync of its output. The durable
  // restore decision may reference this snapshot as the retained old state.
  const f=await open(target,C.O_RDWR|C.O_NOFOLLOW);try{await f.sync();}finally{await f.close();}
  if(process.platform!=='win32'){const d=await open(dirname(target),'r');try{await d.sync();}finally{await d.close();}}
}
/** Validate every row and all snapshot references in the staged consistent SQLite file.
 * Read-only: no lock file, WAL, schema migration or reconstruction of missing state. */
export async function verifyV3Backup(root,id,identity,files) {
  const prefix=`workspaces/${id}/v3/`, directory=join(root,...prefix.split('/'));
  ensure(files.some(f=>f.name===prefix+'v3-state-v2.sqlite'),'备份缺少 v3 SQLite');
  const db=new DatabaseSync(join(directory,'v3-state-v2.sqlite'),{readOnly:true});
  try {
    ensure(db.prepare('PRAGMA integrity_check').all().every(r=>Object.values(r)[0]==='ok'),'SQLite 完整性验证失败');
    const select=db.prepare('SELECT k,v,sum FROM v3_records WHERE ns=? AND k=?');
    const raw=(ns,k)=>{
      const row=select.get(ns,k);ensure(row && sha(Buffer.from(ns+'\0'+k+'\0'+row.v))===row.sum,'v3 备份记录校验失败');return JSON.parse(row.v);
    };
    const unpack=value=>{
      if(value?.$v3Snapshot) {ensure(value.kind==='node','未知快照版本');const body=raw('snapshot',value.$v3Snapshot),bytes=Buffer.from(body.bytes,'base64');ensure(sha(bytes)===value.$v3Snapshot,'快照身份损坏');return decode(bytes).state;}
      if(Array.isArray(value))return value.map(unpack);
      if(value && typeof value==='object')return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,unpack(v)]));return value;
    };
    ensure(raw('format','version').value===2,'未知 v3 元数据版本');
    ensure(JSON.stringify(raw('installation','identity'))===JSON.stringify(identity),'v3 安装身份变化');
    // Cursor iteration avoids a whole-index array. Resource bounds also enforced by container.
    const recordsHash=createHash('sha256');
    for(const row of db.prepare('SELECT seq,ns,k,v,sum FROM v3_records ORDER BY seq').iterate()) {
      recordsHash.update(JSON.stringify([row.seq,row.ns,row.k,row.sum]));
      ensure(sha(Buffer.from(row.ns+'\0'+row.k+'\0'+row.v))===row.sum,'v3 元数据校验失败');
      const value=unpack(JSON.parse(row.v));
      if(row.ns==='snapshot')ensure(sha(Buffer.from(value.bytes,'base64'))===row.k,'快照身份损坏');
      if(row.ns==='node' || /^archiveVerify:[0-9a-f]{64}:node$/.test(row.ns)) {
        const prefix=row.ns.slice(0,-4);
        ensure(raw(prefix+'identity',value.objectId).path===row.k,'历史身份索引损坏');
        for(const ref of value.refs) {
          const r=raw(prefix+'identity',ref.objectId);ensure(r.hash===ref.hash,'历史引用身份损坏');const target=raw(prefix+'node',r.path);
          ensure(target.hash===ref.hash && target.type===ref.type && target.epochId===ref.epochId && target.epochNumber===ref.epochNumber,'历史引用证明损坏');
        }
      }
    }
    const frame=unpack(raw('workspace','workspace.json')), s=frame.v3;
    await Workspace.open({read:async name=>name==='workspace.json'?frame:null,save:async()=>{throw Error('备份禁止补建帧');}});
    ensure(!frame.datasetId && (!s?.writerId || s.writerId===identity.writerId),'v3 Writer/工作区身份异常');
    for(const state of [s?.baseline,s?.conflict?.base,s?.conflict?.local,s?.conflict?.remote])if(state)canonical(state);
    if(s?.binding) {
      const row=select.get('node',s.binding.descriptorPath);
      if(row){const node=raw('node',s.binding.descriptorPath);ensure(node.type===1 && node.hash===s.binding.descriptorHash && node.datasetId===s.binding.datasetId,'Descriptor 身份异常');}
      else if(s.initialization?.length){const bytes=Buffer.from(s.initialization[0],'base64');ensure(sha(bytes)===s.binding.descriptorHash && match(s.binding.descriptorPath,bytes).type===1,'初始化 Descriptor 证据异常');}
      else ensure(s.hostIntent==='JOIN' && !s.baselinePath,'缺少 Descriptor 证明');
    }
    if(s?.pending) {
      const p=s.pending,bytes=Buffer.from(p.bytes,'base64');ensure(sha(bytes)===p.hash,'Pending 精确字节变化');const object=match(p.path,bytes);
      ensure(['STAGED','ATTEMPTED','UNCERTAIN','READBACK_CONFIRMED','RECEIPTED'].includes(p.phase) && Number.isSafeInteger(p.attempts) && p.attempts>=0 && object.writerId===identity.writerId && object.datasetId===s.binding?.datasetId,'Pending 状态或身份异常');
      ensure(['descriptor','initial-genesis','checkpoint','transition','commit'].includes(p.capture?.kind),'未知 capture 类型');
      if(p.capture.kind==='commit'){canonical(p.capture.local);ensure(Number.isSafeInteger(p.capture.revision),'capture revision 异常');}
      if(['READBACK_CONFIRMED','RECEIPTED'].includes(p.phase))ensure(p.confirmation && Object.hasOwn(p.confirmation,'round'),'缺少精确回读回执');
    }
    if(s?.receipt){const node=raw('node',s.receipt.path);ensure(node.hash===s.receipt.hash && s.receipt.confirmation,'持久回执证明异常');}
    if(s?.rotation){for(const field of ['genesis','transition']){const object=decode(Buffer.from(s.rotation[field],'base64'));ensure(object.datasetId===s.binding?.datasetId && object.writerId===identity.writerId,'换代恢复身份异常');}}
    const externalCacheDependencies=[];
    for(const f of files.filter(f=>f.name.startsWith(prefix) && f.name.endsWith('/witness.json'))) {
      const w=JSON.parse(await readFile(join(root,...f.name.split('/')),'utf8')), key=sha(Buffer.from(w.path,'ascii'));
      ensure(w.version===2 && Array.isArray(w.pins) && /^[0-9a-f]{64}$/.test(w.hash) && f.name===prefix+`sync-v3-index-v2/${key.slice(0,2)}/${key.slice(2,4)}/${key.slice(4)}/witness.json`,'缓存 witness 身份异常');
      const blob=f.name.replace(/witness.json$/,'object.dpv');
      if(files.some(x=>x.name===blob)) {const bytes=await readFile(join(root,...blob.split('/')));ensure(sha(bytes)===w.hash,'缓存对象损坏');match(w.path,bytes);}
      else externalCacheDependencies.push({path:w.path,sha256:w.hash,pins:w.pins});
    }
    return {protocol:'v3',workspaceId:id,revision:frame.revision,recordsHash:recordsHash.digest('hex'),frameHash:sha(Buffer.from(JSON.stringify(raw('workspace','workspace.json')))),
      datasetId:s?.binding?.datasetId??null,binding:s?.binding??null,pendingPhase:s?.pending?.phase??null,writerId:identity.writerId,
      courseCount:frame.local.entries.length+frame.local.imported.length,pending:!!s?.pending && s.pending.phase!=='RECEIPTED',conflict:!!s?.conflict,
      remoteHistoryIncluded:false,privateStateComplete:true,externalCacheDependencies,frontier:db.prepare("SELECT k FROM v3_records WHERE ns='frontier' ORDER BY k").all().map(r=>r.k)};
  } finally { db.close(); }
}
