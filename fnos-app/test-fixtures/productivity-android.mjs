// Run against a fresh authenticated WebDAV directory and installed .pr89testbed APKs.
import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile as exec } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID } from 'node:crypto';
import { productivityFixture } from './productivity-fixture.mjs';
import { businessHash } from '../src/v3-protocol.mjs';

const url = process.env.PR89_DAV_URL;
const x08e = process.env.PR89_X08E_SERIAL, phone = process.env.PR89_PHONE_SERIAL;
assert.match(url ?? '', /^http:\/\/127[.]0[.]0[.]1:[0-9]+\/pr89-[0-9a-f-]{36}\/$/);
assert(x08e && phone && x08e !== phone);
const adb = async (serial, ...args) => (await promisify(exec)('adb', ['-s', serial, ...args], { timeout:180000 })).stdout;
async function instrument(serial, app, testName, arg, method) {
  const output = await adb(serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', arg, url,
    '-e', 'class', testName+'#'+method, app+'.pr89testbed.test/androidx.test.runner.AndroidJUnitRunner');
  assert.match(output, /OK \(1 test\)/, output);
  console.log(output);
}
const appName = 'com.dormpanel.app', phoneName = 'com.dormpanel.timetable';
const appTest = appName+'.ProductivitySharedCardTest', phoneTest = 'com.dormpanel.phone.PhoneProductivityAndroidTest';

async function installedPackage(endpoint) {
  const base = new URL(endpoint);
  assert.equal(base.hostname, '127.0.0.1');
  assert.equal(base.pathname, '/app/dormpanel-pr89/');
  const request = async (path, input) => {
    const response = await fetch(new URL(path, base), input === undefined ? {} : {
      method:'POST', headers:{ 'X-DormPanel-CSRF':csrf, 'Content-Type':'application/json' },
      body:JSON.stringify(input),
    });
    return { status:response.status, data:await response.json() };
  };
  let state = (await request('api/schedule')).data;
  const csrf = state.csrf;
  const ctx = () => ({ workspaceId:state.workspaceId, datasetId:state.datasetId, version:state.version, catalogVersion:state.catalogVersion });
  const manage = async input => {
    const result = await request('api/manage', { ...ctx(), ...input });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    state = (await request('api/schedule')).data;
    return result.data;
  };
  const name = 'PR89 隔离工作区 ' + new URL(url).pathname.slice(1, 13);
  const remote = new URL(url);
  remote.host = process.env.PR89_FPK_DAV_HOST;
  assert.match(remote.hostname, /^192[.]168[.]3[.]\d+$/);
  const { connectionId } = await manage({ action:'connection.save', name, type:'webdav', url:remote.href,
    username:'fixture', password:'fixture', allowInsecureHttp:true });
  const { workspaceId } = await manage({ action:'workspace.add', name, connectionId, protocol:'v3' });
  await manage({ action:'workspace.switch', targetId:workspaceId });
  assert.equal(state.workspaceId, workspaceId);
  const read = (view, params = {}) => request('api/productivity?' + new URLSearchParams({ workspaceId, view, ...params }));
  const perform = async (path, input) => {
    const result = await request(path, { ...ctx(), ...input });
    state = (await request('api/schedule')).data;
    return result;
  };
  const joinDataset = async () => {
    const result = await request('api/sync/discover?workspaceId='+workspaceId);
    const { descriptorHash, descriptorPath, descriptorFormat } = result.data.candidates[0];
    assert.equal(descriptorFormat, 4);
    return perform('api/sync', { descriptorPath, descriptorHash, descriptorFormat, bind:true });
  };
  const edit = (action, input) => perform('api/productivity/edit', { action, ...input });
  async function settle() {
    for (let n = 0; n < 10; n++) {
      const result = await perform('api/sync', {});
      assert.equal(result.status, 200, JSON.stringify(result.data));
      const status = (await read('todo.lists')).data;
      if (status.conflict || !status.dirty && !status.pending && status.availability === 'ready') return status;
    }
    throw Error('FPK 工作区未收敛');
  }
  const evidence = async () => ({
    businessHash:businessHash((await request('api/state?workspaceId='+workspaceId)).data.local),
    receiptHash:state.published?.hash,
  });
  return { joinDataset, ctx, read, edit, settle, evidence };
}

test('X08E 卡片 → fnOS HTTP → Phone 原生 Widget → fnOS 与 X08E', { timeout:300000 }, async t => {
  const f = process.env.PR89_FPK_HTTP ? await installedPackage(process.env.PR89_FPK_HTTP) : await productivityFixture(t, { url });
  const port = new URL(url).port;
  for (const serial of [x08e, phone]) await adb(serial, 'reverse', 'tcp:'+port, 'tcp:'+port);
  await adb(phone, 'shell', 'cmd', 'appwidget', 'grantbind', '--package', phoneName+'.pr89testbed', '--user', '0');
  await instrument(x08e, appName, appTest, 'pr88Url', 'importConnectAndReturnToLegacy');
  const joined = await f.joinDataset();
  assert.equal(joined.status, 200, JSON.stringify(joined.data));
  await f.settle();
  const list = (await f.read('todo.lists')).data.rows[0];
  const tasks = (await f.read('todo.tasks', { id:list.id })).data.rows;
  assert.equal(tasks.length, 12);
  assert.equal(tasks[0].text, '检查 WebDAV 回执');
  assert(tasks[0].completed);
  const memoId = (await f.read('memo.list')).data.rows[0].id;
  const before = (await f.read('memo.get', { id:memoId })).data.memo;
  assert.match(before.body, /^周五宿舍检查\n记得带钥匙\r\n/);
  const taskId = randomUUID(), body = '  fnOS 编辑\r\n第二行🙂\n  ';
  const task = await f.edit('todo.createTask', { id:taskId, listId:list.id, text:'fnOS 新增的共享任务' });
  assert.equal(task.data.writeApplied, true, JSON.stringify(task.data));
  assert.equal(task.data.remoteConfirmed, false);
  assert.equal(task.data.dirty, true);
  const memo = await f.edit('memo.edit', { id:memoId, title:'fnOS 编辑的共享备忘', body });
  assert.equal(memo.data.localSaved, true, JSON.stringify(memo.data));
  const published = await f.settle();
  assert.equal(published.remoteConfirmed, true);
  assert.equal(published.otherDevicesReceived, 'unknown');
  await instrument(phone, phoneName, phoneTest, 'pr88Url', 'joinSharedEditAndOperateRealWidgetInstances');
  await f.settle();
  assert.equal((await f.read('todo.task', { id:taskId })).data.task.completed, true);
  assert.equal((await f.read('memo.get', { id:memoId })).data.memo.body, body);
  await instrument(x08e, appName, appTest, 'pr89Url', 'receivesFnOsEditAndPhoneWidget');
  const evidence = f.evidence ? await f.evidence() : {
    businessHash:businessHash(f.app.db.frame.local), receiptHash:f.app.db.frame.receipt?.hash,
  };
  const hash = evidence.businessHash;
  const log = serial => adb(serial, 'logcat', '-d', '-t', '9000', '-s', 'System.out:I');
  assert((await log(x08e)).includes('PR89_X08E_HASH='+hash));
  const phoneLog = await log(phone);
  assert(phoneLog.includes('PR89_PHONE_HASH='+hash));
  assert.match(phoneLog, /PR89_WIDGET_COUNT=12->11/);
  console.log(JSON.stringify({ datasetId:f.ctx().datasetId, taskId, memoId, ...evidence, widgetCount:'12→11' }));
});
