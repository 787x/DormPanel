// Developer UI fixture. Loopback-only proxy into an isolated owner socket.
// Gateway identity injection is confined to this process, never production routes.
import http from 'node:http';
import {mkdtemp} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {createApp,PREFIX} from '../src/server.mjs';
const root=await mkdtemp(join(tmpdir(),'pr57-ui-')),objects=new Map();
const remote={read:async p=>objects.get(p)??null,putOnce:async(p,b)=>{if(objects.has(p))throw Error('occupied');objects.set(p,Buffer.from(b));},
  list:async s=>[...new Set([...objects.keys()].filter(p=>p.startsWith(s)).map(p=>{const tail=p.slice(s.length),i=tail.indexOf('/');return s+(i<0?tail:tail.slice(0,i+1));}))].sort()};
const app=await createApp({dataDir:root,configPath:join(root,'unused.json'),v3RemoteFactory:()=>remote});
const c=app.catalog,manage=i=>c.manage({catalogVersion:c.manifest.revision,...i});
const config={type:'webdav',url:'https://isolated.invalid/',username:'synthetic',password:'synthetic'};
const {connectionId}=await manage({action:'connection.save',name:'旧端点（隔离示例）',...config});
const {workspaceId}=await manage({action:'workspace.add',name:'PR57 隔离课表',protocol:'v3',connectionId});await manage({action:'workspace.switch',targetId:workspaceId});
await c.network(workspaceId,e=>e.synchronize({create:true}));for(let n=0;n<5;n++)await c.network(workspaceId,e=>e.synchronize());
await manage({action:'connection.save',name:'新端点（同一隔离数据集）',...config,url:'https://new.invalid/'});
const socket=process.platform==='win32'?'\\\\.\\pipe\\pr57-ui-'+randomUUID():join(root,'ui.sock');await new Promise(r=>app.server.listen(socket,r));
const proxy=http.createServer((req,res)=>{
  const upstream=http.request({socketPath:socket,path:req.url,method:req.method,headers:{...req.headers,'x-trim-userid':'1000','x-trim-isadmin':'true'}},response=>{res.writeHead(response.statusCode,response.headers);response.pipe(res);});
  upstream.on('error',()=>{res.writeHead(502);res.end();});req.pipe(upstream);
});
await new Promise(r=>proxy.listen(0,'127.0.0.1',r));console.log(JSON.stringify({url:`http://127.0.0.1:${proxy.address().port}${PREFIX}/`,root}));
