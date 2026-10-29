import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import http from 'node:http';
import {createApp,PREFIX} from '../src/server.mjs';
test('ICS 下载管理员、工作区、版本、同源和只读边界',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'dp-ics-'));const app=await createApp({dataDir:dir,configPath:join(dir,'config.json')});
 const socketPath=process.platform==='win32'?'\\\\.\\pipe\\dp-ics-'+randomUUID():join(dir,'app.sock');await new Promise(r=>app.server.listen(socketPath,r));
 t.after(async()=>{await new Promise(r=>app.server.close(r));await app.catalog.close();await rm(dir,{recursive:true,force:true});});
 const call=(path,admin='true',site='same-origin')=>new Promise((resolve,reject)=>{http.get({socketPath,path:PREFIX+path,headers:{host:'nas.test','x-trim-userid':'1000','x-trim-isadmin':admin,'sec-fetch-site':site,'sec-fetch-mode':'cors'}},r=>{let body='';r.on('data',b=>body+=b);r.on('end',()=>resolve({status:r.statusCode,headers:r.headers,body}));}).on('error',reject);});
 const view=JSON.parse((await call('/api/schedule')).body),db=await app.catalog.load(view.workspaceId),before=JSON.stringify(db.frame,(_,v)=>typeof v==='bigint'?String(v):v);
 const q=new URLSearchParams({workspaceId:view.workspaceId,version:view.version,start:'2026-10-12',end:'2026-10-18',zone:'Asia/Shanghai',origin:'http://nas.test'}),path='/api/export/ics?'+q;
 const ok=await call(path);assert.equal(ok.status,200);assert.equal(ok.headers['content-type'],'text/calendar; charset=utf-8');assert.equal(ok.headers['cache-control'],'no-store');assert.match(ok.headers['content-disposition'],/^attachment; filename="DormPanel-/);
 assert.equal((await call(path,'false')).status,403);assert.notEqual((await call(path,'true','cross-site')).status,200);
 for(const changes of [{workspaceId:'missing'},{version:-1},{end:'2028-10-12'},{origin:'file:///private'}]){const invalid=new URLSearchParams({...Object.fromEntries(q),...changes});assert.notEqual((await call('/api/export/ics?'+invalid)).status,200);}
 assert.equal(JSON.stringify(db.frame,(_,v)=>typeof v==='bigint'?String(v):v),before);
});
