import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

test('真实升级回调忽略陈旧安装向导，逐字节保留当前地址、凭据和证书',async t=>{
  const dir=await mkdtemp(join(tmpdir(),'pr38-upgrade-'));t.after(()=>rm(dir,{force:true,recursive:true}));
  const certificatePem=await readFile(new URL('./fixtures/test-certificate.pem',import.meta.url),'utf8');
  const path=join(dir,'webdav.json'),bytes=JSON.stringify({url:'https://192.168.3.153:5006/dormpanel/current/',username:'new-user',password:'rotated-password',certificatePem},null,2);
  await writeFile(path,bytes);
  const env={...process.env,TRIM_PKGETC:dir,wizard_webdav_url:'https://old.test/old/',wizard_webdav_username:'stale-user',wizard_webdav_password:'stale-password'};
  const script=fileURLToPath(new URL('../src/configure.mjs',import.meta.url));
  const upgrade=spawnSync(process.execPath,[script,'--upgrade'],{env});assert.equal(upgrade.status,0,upgrade.stderr.toString());assert.equal(await readFile(path,'utf8'),bytes);
  const legacy=spawnSync(process.execPath,[script],{env});assert.equal(legacy.status,0);assert.equal(await readFile(path,'utf8'),bytes,'无参数旧 callback 同样保留已有配置');
  const explicit=spawnSync(process.execPath,[script,'--configure'],{env});assert.equal(explicit.status,0);assert.equal(await readFile(path,'utf8'),bytes,'0.3 系统配置不消费旧向导，不更换绑定；目录授权可正常完成');
});

test('多工作区启用后目录授权的 config_callback 正常完成，忽略旧向导且不改写凭据', async t => {
  const dir=await mkdtemp(join(tmpdir(),'pr39-authorize-'));t.after(()=>rm(dir,{force:true,recursive:true}));
  const path=join(dir,'webdav.json'),bytes=JSON.stringify({url:'https://current.test/',username:'u',password:'old-secret'});
  await writeFile(path,bytes);await writeFile(join(dir,'catalog.json'),'{"schema":1}');
  const env={...process.env,TRIM_PKGETC:dir,TRIM_PKGVAR:dir,wizard_webdav_url:'https://stale.test/',wizard_webdav_username:'stale',wizard_webdav_password:'stale'};
  const r=spawnSync(process.execPath,[fileURLToPath(new URL('../src/configure.mjs',import.meta.url)),'--configure'],{env});
  assert.equal(r.status,0,r.stderr.toString());assert.equal(await readFile(path,'utf8'),bytes);
});
