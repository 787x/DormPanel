import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile, readFile, mkdir } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { randomUUID } from "node:crypto";
import http from "node:http";
import https from 'node:https';
import { execFileSync } from 'node:child_process';
import { networkInterfaces } from 'node:os';
import { createApp, PREFIX } from "../src/server.mjs";
import { WebDav, safeAddress, tlsOptions } from "../src/webdav.mjs";
import { X509Certificate } from "node:crypto";
import { encode } from '../src/protocol.mjs';
import { emptyState } from '../src/merge.mjs';
import { loadGraph } from '../src/graph.mjs';

async function fixture(t, authorized = false, realNetwork = false) {
  const dir = await mkdtemp(join(tmpdir(), "pr38-api-"));
  t.after(() => rm(dir, { force: true, recursive: true }));
  const configPath = join(dir, "webdav.json"),
    remote = {
      key: "https://example.com/folder/dormpanel-sync-v2/",
      list: async () => [],
      read: async () => null,
      put: async () => {
        throw Error("本测试禁止远端 PUT");
      },
    };
  const app = await createApp({
      dataDir: dir,
      configPath,
      remoteFactory: realNetwork ? undefined : () => remote,
      authorizedRoots: authorized ? [{ id:'approved', path:dir, name:'独立测试目录' }] : [],
    }),
    socketPath =
      process.platform === "win32"
        ? "\\\\.\\pipe\\dp-" + randomUUID()
        : join(dir, "api.sock");
  await new Promise((r) => app.server.listen(socketPath, r));
  t.after(() => new Promise((r) => app.server.close(r)));
  const admin = { "X-Trim-Userid": "1000", "X-Trim-Isadmin": "true" };
  const call = (method, path, data, headers = {}) =>
    new Promise((resolve, reject) => {
      const req = http.request(
        {
          socketPath,
          path: PREFIX + path,
          method,
          headers: { ...admin, ...headers },
        },
        (res) => {
          let text = "";
          res.on("data", (b) => (text += b));
          res.on("end", () =>
            resolve({ status: res.statusCode, data: JSON.parse(text), text }),
          );
        },
      );
      req.on("error", reject);
      req.end(data === undefined ? undefined : JSON.stringify(data));
    });
  const view = (
    await call(
      "GET",
      "/api/schedule?origin=http%3A%2F%2Fnas.test%3A5666",
      undefined,
      { Host: "nas.test" },
    )
  ).data;
  const post = (path, data = {}, headers = {}) =>
    call(
      "POST",
      path,
      { workspaceId: app.catalog.manifest.activeId, catalogVersion: app.catalog.manifest.revision, version: app.db.frame.revision, ...data },
      {
        "Content-Type": "application/json",
        "X-DormPanel-CSRF": view.csrf,
        Host: "nas.test",
        Origin: "http://nas.test:5666",
        ...headers,
      },
    );
  return { app, dir, configPath, call, post, view, remote };
}
test("每条修改路由均检查管理员、CSRF、同源、请求版本；拒绝过期重放", async (t) => {
  const f = await fixture(t);
  for (const path of [
    "/api/edit",
    "/api/sync",
    "/api/refresh",
    "/api/resolve",
    "/api/import/preview",
    "/api/import/confirm",
    "/api/manage",
    "/api/connections/test",
    '/api/history/check', '/api/history/preview', '/api/history/rollback', '/api/history/archive', '/api/history/compact',
    '/api/migration',
  ]) {
    assert.equal(
      (await f.post(path, {}, { "X-Trim-Isadmin": "false" })).status,
      403,
    );
    assert.equal(
      (await f.post(path, {}, { "X-DormPanel-CSRF": "invalid" })).status,
      403,
    );
    assert.equal(
      (await f.post(path, {}, { Origin: "https://evil.test" })).status,
      403,
    );
    assert.equal(
      (await f.post(path, {}, { "Sec-Fetch-Site": "same-site" })).status,
      403,
    );
    assert.equal((await f.post(path, { version: -1 })).status, 409);
    assert.equal(
      (await f.post(path, {}, { "Content-Type": "text/plain" })).status,
      400,
    );
  }
  const operation = {
    kind: "entry",
    action: "upsert",
    value: {
      title: "<img src=x onerror=alert(1)>",
      day: 1,
      startMinute: 480,
      endMinute: 530,
      location: "",
    },
  };
  assert.equal(
    (
      await f.post(
        "/api/edit",
        { operation },
        { Origin: "http://nas.test:5667" },
      )
    ).status,
    403,
  );
  assert.equal(
    (
      await f.call(
        "GET",
        "/api/schedule?origin=https%3A%2F%2Fevil.test",
        undefined,
        { Host: "nas.test" },
      )
    ).status,
    400,
  );
  assert.equal((await f.post("/api/edit", { operation })).status, 200);
  assert.equal(
    (await f.post("/api/edit", { operation, version: 0 })).status,
    409,
  );
  assert.equal(f.app.db.frame.local.entries.length, 1);
  assert.equal(
    (await f.post("/api/edit", { operation }, { "X-Trim-Userid": "1001" }))
      .status,
    403,
    "CSRF 绑定真实管理员用户",
  );
  assert.equal((await f.call("GET", "/api/edit")).status, 404);
  assert.equal((await f.call("DELETE", "/api/edit")).status, 405);
  assert.equal(
    (
      await f.post("/api/edit", {
        operation: {
          kind: "entry",
          action: "upsert",
          value: { ...operation.value, title: "x".repeat(2 * 1024 * 1024) },
        },
      })
    ).status,
    400,
  );
  assert.equal(f.app.db.frame.local.entries.length, 1);
});
test('仅私有目录可完成连接、工作区、编辑和合法发布；保留已有工作区', { skip: process.platform !== 'linux' }, async t => {
  const f = await fixture(t), original = structuredClone(f.app.db.frame);
  const roots = (await f.call('GET', '/api/manage')).data.roots;
  assert.deepEqual(roots.map(r => r.id), ['private']);
  const result = await f.post('/api/manage', { action: 'connection.save', type: 'local', name: '私有测试', rootId: 'private', relative: '', createPrivate: true });
  assert.equal(result.status, 200, result.text);
  const connectionId = result.data.connectionId, connection = f.app.catalog.connection(connectionId);
  assert.equal(connection.config.relative, connectionId); assert(connection.config.directoryIdentity);
  assert.equal((await f.post('/api/manage', { action: 'workspace.add', name: '独立测试', connectionId })).status, 200);
  const targetId = f.app.catalog.manifest.workspaces.find(w => w.connectionId === connectionId).id;
  assert.equal((await f.post('/api/manage', { action: 'workspace.switch', targetId })).status, 200);
  assert.equal((await f.post('/api/edit', { operation: { kind: 'entry', action: 'upsert', value: {
    title: '仅私有目录课程', day: 1, startMinute: 480, endMinute: 530, location: '',
  } } })).status, 200);
  const published = await f.post('/api/sync', { create: true }); assert.equal(published.status, 200, published.text);
  assert(f.app.db.frame.datasetId); assert(f.app.db.receipt || f.app.db.frame.receipt);
  assert.deepEqual((await f.app.catalog.load(f.view.workspaceId)).frame, original);
});

test('授权 NAS 目录可选择保存；未授权根、绝对路径、穿越和过期版本不能改配置', { skip: process.platform !== 'linux' }, async t => {
  const f = await fixture(t), approved = join(f.dir, 'approved'); await mkdir(approved);
  f.app.catalog.grants.roots.push({ id: 'approved', name: '已授权 NAS 测试目录', path: approved });
  assert.equal((await f.call('GET', '/api/directories?rootId=approved')).status, 200);
  const input = { action: 'connection.save', type: 'local', name: 'NAS 测试', rootId: 'approved', relative: '' };
  assert.equal((await f.post('/api/manage', input)).status, 200);
  const before = structuredClone(f.app.catalog.manifest);
  for (const change of [{ rootId: 'unapproved' }, { relative: '/etc' }, { relative: '../approved' }, { catalogVersion: -1 }]) {
    assert.notEqual((await f.post('/api/manage', { ...input, ...change })).status, 200);
    assert.deepEqual(f.app.catalog.manifest, before);
  }
  assert.equal((await f.call('GET', '/api/directories?rootId=unapproved')).status, 400);
});

test("多连接配置凭据不返回浏览器，测试不改变当前工作区，拒绝覆盖绑定", async t => {
  const f = await fixture(t), secret = 'private-password-39';
  const result = await f.post('/api/manage', { action: 'connection.save', type: 'webdav', name: '连接 A', url: 'https://example.com/folder/', username: 'w', password: secret });
  assert.equal(result.status, 200, result.text);
  const connectionId = result.data.connectionId;
  const before = JSON.stringify(f.app.db.frame), manifest = JSON.stringify(f.app.catalog.manifest);
  assert.equal((await f.post('/api/connections/test', { name: '预览', type: 'webdav', url: 'https://other.test/preview/', username: 'w', password: 'temporary' })).status, 200);
  assert.equal(JSON.stringify(f.app.db.frame), before);
  assert.equal(JSON.stringify(f.app.catalog.manifest), manifest);
  for (const path of ['/api/manage', '/api/state', '/api/schedule', '/api/conflicts']) {
    const result = await f.call('GET', path + '?workspaceId=' + f.view.workspaceId);
    assert.equal(result.status, 200); assert(!result.text.includes(secret));
  }
  assert.equal((await f.post('/api/manage', { action: 'workspace.endpoint', targetId: f.view.workspaceId, targetVersion: f.app.db.frame.revision, connectionId })).status, 200);
  await f.app.db.tx(frame => { frame.datasetId = randomUUID(); });
  const rotated = await f.post('/api/manage', { action: 'connection.save', connectionId, password: 'rotated' });
  assert.equal(rotated.status, 400, '不能绕过已绑定历史验证');
  assert.equal(f.app.catalog.connection(connectionId).config.password, secret);
});

test("阻止回环、元数据地址、IPv4映射回环和多播 SSRF，保留可明确配置的 LAN NAS", () => {
  for (const [ip, family] of [
    ["127.0.0.1", 4],
    ["169.254.169.254", 4],
    ["0.0.0.0", 4],
    ["::1", 6],
    ["::ffff:127.0.0.1", 6],
    ["fe80::1", 6],
    ["224.0.0.1", 4],
  ])
    assert.equal(safeAddress(ip, family), false, ip);
  assert.equal(safeAddress("192.168.3.153", 4), true);
});

test('所有管理写路由拒绝缺失/伪造工作区与过期管理版本；导入预览不能跨工作区使用', async t => {
  const f = await fixture(t);
  const c = await f.post('/api/manage', { action: 'connection.save', type: 'webdav', name: 'B 连接', url: 'https://b.test/', username: 'u', password: 'p' });
  const w = await f.post('/api/manage', { action: 'workspace.add', connectionId: c.data.connectionId, name: 'B' });
  const b = (await f.app.catalog.load(w.data.workspaceId)), a = f.app.db;
  for (const path of ['/api/edit', '/api/sync', '/api/resolve', '/api/import/preview', '/api/import/confirm', '/api/manage', '/api/connections/test']) {
    assert.equal((await f.post(path, { workspaceId: undefined })).status, 400);
    assert.equal((await f.post(path, { workspaceId: randomUUID() })).status, 400);
  }
  assert.equal((await f.post('/api/manage', { action: 'workspace.switch', targetId: b.id, catalogVersion: -1 })).status, 409);
  const bytes = await readFile(new URL('../../schedule-core/src/test/resources/schedule/wakeup.ics', import.meta.url));
  const preview = await f.post('/api/import/preview', { filename: 'wakeup.ics', base64: bytes.toString('base64') });
  assert.equal(preview.status, 200, preview.text);
  await f.post('/api/manage', { action: 'workspace.switch', targetId: w.data.workspaceId });
  const before = JSON.stringify(b.frame);
  const replay = await f.post('/api/import/confirm', { id: preview.data.id, displayName: '错误目标' });
  assert.equal(replay.status, 409, replay.text); assert.equal(JSON.stringify(b.frame), before);
  const confirmed = await f.post('/api/import/confirm', { workspaceId: f.view.workspaceId, version: a.frame.revision, id: preview.data.id, displayName: '正确目标' });
  assert.equal(confirmed.status, 200, confirmed.text); assert((await f.app.catalog.load(f.view.workspaceId)).frame.local.sources.length > 0);
  assert.equal(b.frame.local.sources.length, 0);
  assert.equal((await f.call('GET', '/api/state')).status, 400);
  const denied = await f.call('GET', '/api/directories?workspaceId=' + w.data.workspaceId + '&rootId=/etc&relative=');
  assert.equal(denied.status, 400);
});

test('冲突 token 同时绑定工作区，即使两个恢复帧碰巧完全相同也不能重放', async t => {
  const f = await fixture(t), a = f.app.db;
  const c = await f.post('/api/manage', { action: 'connection.save', type: 'webdav', name: 'B', url: 'https://b.test/', username: 'u', password: 'p' });
  const w = await f.post('/api/manage', { action: 'workspace.add', name: 'B', connectionId: c.data.connectionId });
  const b = (await f.app.catalog.load(w.data.workspaceId)), base = structuredClone(a.frame.local);
  const plan = { base, localBase: base, branches: [] };
  await a.tx(frame => { frame.conflict = plan; }); await b.tx(frame => { frame.conflict = plan; });
  assert.equal(a.conflictToken(), b.conflictToken());
  const response = await f.post('/api/resolve', { workspaceId: w.data.workspaceId, version: b.frame.revision, token: f.view.workspaceId + ':' + a.conflictToken(), choice: 'local' });
  assert.equal(response.status, 409); assert(!f.app.catalog.busy.size); assert(b.frame.conflict);
});
test("显式信任的独立证书仍启用 TLS 校验，精确拒绝另一个证书", async () => {
  const pem = await readFile(
      new URL("./fixtures/test-certificate.pem", import.meta.url),
      "utf8",
    ),
    cert = new X509Certificate(pem),
    options = tlsOptions({ certificatePem: pem });
  assert.equal(options.rejectUnauthorized, true);
  assert.equal(
    options.checkServerIdentity("192.168.3.153", { raw: cert.raw }),
    undefined,
  );
  const changed = Buffer.from(cert.raw);
  changed[changed.length - 1] ^= 1;
  assert(
    options.checkServerIdentity("192.168.3.153", { raw: changed }) instanceof
      Error,
  );
  assert.throws(() => tlsOptions({ certificatePem: "invalid" }));
  assert.throws(() => tlsOptions({ certificatePem: "x".repeat(16385) }));
  assert.deepEqual(tlsOptions({}), {});
});

test('历史 API 完整闭环、回退确认和授权归档精简，读取默认不暴露原始图', async t => {
  const f = await fixture(t, true), datasetId=randomUUID(), rootId=randomUUID(), tipId=randomUUID(), connectionId=randomUUID(), rows=new Map();
  const root={datasetId,writerId:randomUUID(),commitId:rootId,parents:[],state:emptyState()}, tip={...root,commitId:tipId,parents:[rootId],state:{...emptyState(),entries:[{id:'course',title:'测试课程',day:1,startMinute:480,endMinute:540,location:''}]}};
  rows.set(rootId,encode(root));rows.set(tipId,encode(tip));
  f.remote.list=async()=>[...rows.keys()];f.remote.read=async id=>rows.get(id)??null;f.remote.put=async(id,b)=>rows.set(id,b);
  const graph=await loadGraph(f.remote), manifest=structuredClone(f.app.catalog.manifest);
  manifest.connections.push({id:connectionId,name:'test',type:'webdav',config:{url:'https://example.com/test/',username:'test',password:'hidden-test-secret'}});manifest.workspaces[0].connectionId=connectionId;await f.app.catalog.commit(manifest);
  await f.app.db.tx(frame=>{frame.local=graph.state;frame.baseline=graph.state;frame.datasetId=datasetId;frame.graph=graph;frame.frontier=graph.tips;frame.checkpoint={hashes:graph.hashes,datasetId};frame.bindingKey=f.remote.key;});
  const path='/api/history?workspaceId='+manifest.activeId;
  const h=await f.call('GET',path);assert.equal(h.status,200);assert.equal(h.data.count,2);assert(!h.text.includes('base64'));assert(!h.text.includes('hidden-test-secret'));assert.equal(h.data.rows[0].state,undefined);
  const p=await f.post('/api/history/preview',{target:rootId});assert.equal(p.status,200);assert.equal(p.data.diff.total,1);
  const previewPath='/api/history/preview-diff?workspaceId='+manifest.activeId+'&token='+p.data.token+'&offset=0';
  assert.equal((await f.call('GET',previewPath)).data.total,1);
  assert.equal((await f.call('GET',previewPath,undefined,{'X-Trim-Userid':'1001'})).status,409);
  const confirm=await f.post('/api/history/rollback',{token:p.data.token,target:rootId,confirm:rootId});assert.equal(confirm.status,200,confirm.text);assert.equal(rows.size,3);
  const check=await f.post('/api/history/check');assert.equal(check.status,200);
  let entered, release; const ready=new Promise(r=>{entered=r;}), gate=new Promise(r=>{release=r;}), list=f.remote.list;
  f.remote.list=async()=>{entered();await gate;return list();};
  const checking=f.post('/api/history/check');await ready;
  try {const blocked=await f.post('/api/edit',{operation:{kind:'entry',action:'delete',id:'course'}});assert.notEqual(blocked.status,200);assert.match(blocked.data.error,/历史操作/);}
  finally {release();f.remote.list=list;} assert.equal((await checking).status,200);
  if(process.platform==='linux') {
    const result=await f.post('/api/history/compact',{rootId:'approved',relative:''});assert.equal(result.status,200,result.text);assert(result.data.candidate);assert.equal(rows.size,3);
    const marker=JSON.parse(await readFile(join(f.dir,result.data.archive,'verified.json'),'utf8'));assert.equal(marker.commits.length,3);
  }
});

test('未绑定工作区历史检查的第一次列举直接持久化，不能被身份预探测的第二次列举遗忘', async t=>{
  const f=await fixture(t), root={datasetId:randomUUID(),writerId:randomUUID(),commitId:randomUUID(),parents:[],state:emptyState()}, broken=randomUUID(), connectionId=randomUUID();
  let lists=0;const reads=[];
  f.remote.list=async()=>++lists===1?[root.commitId,broken]:[root.commitId];
  f.remote.read=async id=>{reads.push(id);return id===broken?Buffer.alloc(250):encode(root);};
  const manifest=structuredClone(f.app.catalog.manifest);
  manifest.connections.push({id:connectionId,name:'test',type:'webdav',config:{url:'https://example.com/history/',username:'test',password:'test'}});manifest.workspaces[0].connectionId=connectionId;
  await f.app.catalog.commit(manifest);
  assert.equal((await f.post('/api/history/check')).status,200);
  assert.equal(lists,1);assert(reads.includes(broken));assert(f.app.db.frame.checkpoint.deferred.includes(broken));
  assert.equal(f.app.db.frame.datasetId,null);assert.deepEqual(f.app.db.frame.local,emptyState());
  reads.length=0;
  assert.notEqual((await f.post('/api/history/preview',{target:root.commitId})).status,200);
  assert(reads.includes(broken));assert(f.app.db.frame.checkpoint.deferred.includes(broken));
});


test('PR59 浏览 API：候选不保存，已保存密码只留后端，管理员与 CSRF 边界保持',async t=>{
  const f=await fixture(t), before=JSON.stringify(f.app.catalog.manifest), original=WebDav.prototype.request;
  const calls=[];
  WebDav.prototype.request=async function(method,url,max){
    calls.push({method,url:url.href,password:this.config.password,max});
    return Buffer.from('<d:multistatus xmlns:d="DAV:"><d:response><d:href>'+url.pathname+'</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>');
  };
  t.after(()=>{WebDav.prototype.request=original;});
  const input={type:'webdav',name:'浏览候选',url:'https://nas.test/dav/',username:'user',password:'never-return-this'};
  const preview=await f.post('/api/connections/browse',input);assert.equal(preview.status,200);assert.equal(preview.data.kind,'webdav');
  assert.equal(JSON.stringify(f.app.catalog.manifest),before);assert(!preview.text.includes(input.password));
  const saved=await f.post('/api/manage',{action:'connection.save',...input});assert.equal(saved.status,200);
  const snapshot=JSON.stringify(f.app.catalog.manifest);
  const selected=await f.post('/api/connections/browse',{connectionId:saved.data.connectionId});assert.equal(selected.status,200);
  assert.equal(calls.at(-1).password,input.password);assert.equal(JSON.stringify(f.app.catalog.manifest),snapshot);
  for(const extra of [{browseUrl:'https://evil.test/'},{browseUrl:'https://nas.test/other/'},{browseUrl:'https://nas.test/dav/%2e%2e/'}])assert.equal((await f.post('/api/connections/browse',{...input,...extra})).status,400);
  assert.equal((await f.post('/api/connections/browse',input,{'X-DormPanel-CSRF':'wrong'})).status,403);
  assert.equal((await f.post('/api/connections/browse',input,{'X-Trim-Isadmin':'false'})).status,403);
  assert.equal((await f.post('/api/connections/browse',input,{Origin:'https://evil.test'})).status,403);
  assert(calls.every(c=>c.method==='PROPFIND' && c.max===512*1024));assert.equal(calls.length,2);
});


test('审查修复：浏览/测试/保存共用身份门禁，真实 A/B 服务器不会收到跨身份旧 Authorization',async t=>{
  const address=Object.values(networkInterfaces()).flat().find(r=>r.family==='IPv4' && !r.internal && safeAddress(r.address,4))?.address;
  if(!address){t.skip('需要可用 LAN 接口；保留生产 SSRF 校验');return;}
  const openssl=process.env.PR59_OPENSSL || 'openssl';
  try { execFileSync(openssl,['version'],{stdio:'ignore'}); }catch {t.skip('需要 openssl 创建隔离测试证书');return;}
  const f=await fixture(t,false,true), material=[];
  for(let n=0;n<2;n++) {
    execFileSync(openssl,['req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=pr59-synthetic','-keyout',join(f.dir,`key${n}.pem`),'-out',join(f.dir,`cert${n}.pem`)],{stdio:'ignore'});
    material.push({key:await readFile(join(f.dir,`key${n}.pem`)),cert:await readFile(join(f.dir,`cert${n}.pem`),'utf8')});
  }
  const calls=[[],[]],servers=[];
  for(let n=0;n<2;n++) {
    const server=https.createServer(material[n],(req,res)=>{
      calls[n].push({method:req.method,path:req.url,auth:req.headers.authorization});
      res.writeHead(207,{'Content-Type':'application/xml'});
      res.end('<d:multistatus xmlns:d="DAV:"><d:response><d:href>'+req.url+'</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>');
    });
    await new Promise(r=>server.listen(0,'0.0.0.0',r));servers.push(server);t.after(()=>new Promise(r=>server.close(r)));
  }
  const url=n=>`https://${address}:${servers[n].address().port}/dav/`;
  const initial={type:'webdav',name:'server A',url:url(0),username:'user-a',password:'secret-a',certificatePem:material[0].cert};
  const saved=await f.post('/api/manage',{action:'connection.save',...initial});assert.equal(saved.status,200);
  const connectionId=saved.data.connectionId, before=JSON.stringify(f.app.catalog.manifest);
  const paths=['/api/connections/browse','/api/connections/test','/api/manage'];
  for(const route of paths)for(const changed of [
    {url:url(1)}, {username:'user-b'}, {certificatePem:material[1].cert}, {certificatePem:''}
  ]) {
    const result=await f.post(route,{action:'connection.save',connectionId,password:'',...changed});
    assert([400,409].includes(result.status));assert.match(result.data.error,/重新输入密码/);
    assert.equal(calls[0].length+calls[1].length,0);
    assert.equal(JSON.stringify(f.app.catalog.manifest),before);
  }
  // Name and same-origin directory changes retain the stored password across all paths.
  for(const route of paths){const result=await f.post(route,{action:'connection.save',connectionId,name:'renamed',url:url(0)+'child/',password:'',certificatePem:'\n'+material[0].cert+'\n'});assert.equal(result.status,200,JSON.stringify(result.data));}
  assert(calls[0].length>=2);assert(calls[0].every(r=>r.auth==='Basic '+Buffer.from('user-a:secret-a').toString('base64')));assert.equal(calls[1].length,0);
  // An explicit new password permits the changed identity; only the new secret leaves.
  for(const route of paths){const result=await f.post(route,{action:'connection.save',connectionId,url:url(1),username:'user-b',password:'secret-b',certificatePem:material[1].cert});assert.equal(result.status,200,JSON.stringify(result.data));}
  assert(calls[1].length>=2);assert(calls[1].every(r=>r.auth==='Basic '+Buffer.from('user-b:secret-b').toString('base64')));
  assert.equal(f.app.catalog.connection(connectionId).config.password,'secret-b');
});
