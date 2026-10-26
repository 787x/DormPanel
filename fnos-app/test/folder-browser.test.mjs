import test from 'node:test';
import assert from 'node:assert/strict';
import { folderUrl, parseFolders, browseFolders } from '../src/folder-browser.mjs';
import { WebDav } from '../src/webdav.mjs';

const root = new URL('https://nas.test/dav/');
const response = (href, folder=true, status='200 OK') => `<d:response><d:href>${href}</d:href><d:propstat><d:prop><d:resourcetype>${folder?'<d:collection/>':''}</d:resourcetype></d:prop><d:status>HTTP/1.1 ${status}</d:status></d:propstat></d:response>`;
const xml = (...rows) => `<d:multistatus xmlns:d="DAV:">${rows.join('')}</d:multistatus>`;
test('普通目录解析与 DPC 列举分离：直属文件夹、Unicode、空格与协议目录过滤',()=>{
  const rows=parseFolders(xml(response('/dav/'),response('/dav/%E8%AF%BE%E8%A1%A8%20%F0%9F%93%9A/'),response('/dav/a%2Bb/'),response('/dav/a.ics',false),response('/dav/dormpanel-sync-v2/'),response('/dav/dormpanel-sync-v3/')),root,root);
  assert.deepEqual(new Set(rows.map(r=>r.name)),new Set(['课表 📚','a+b']));
  assert.equal(rows.find(r=>r.name==='a+b').url,'https://nas.test/dav/a%2Bb/');
  assert.deepEqual(parseFolders(xml(response('/dav/')),root,root),[]);
});
test('拒绝越界、穿越、编码分隔符、实体、重复链接、异常类型与超量目录',()=>{
  for(const href of ['https://evil.test/dav/x/','/other/','/dav/a/b/','/dav/../dav/x/','/dav/%2e%2e/dav/x/','/dav/x%2fy/','/dav/x%5cy/','/dav/%252e%252e/','/dav/%00/','/dav/%zz/','/dav/x/?token=secret','https://u:p@nas.test/dav/x/'])
    assert.throws(()=>parseFolders(xml(response('/dav/'),response(href)),root,root),href);
  for(const body of [xml(response('/dav/',false)),xml(response('/dav/','true','403 Forbidden')),xml(response('/dav/x/')),xml(response('/dav/'),response('/dav/x/'),response('/dav/x/')), '<!DOCTYPE x [<!ENTITY x SYSTEM "file:///etc/passwd">]>'+xml(response('/dav/')),xml(response('/dav/'))+'x'.repeat(512*1024),xml(...Array.from({length:1025},()=>response('/dav/')))])assert.throws(()=>parseFolders(body,root,root));
  assert.throws(()=>folderUrl('https://nas.test/dav2/',root));
  assert.throws(()=>folderUrl('https://nas.test/dav/'+ 'x/'.repeat(65),root));
});
test('浏览只发一个有界 PROPFIND；HTTP 显式授权、内部目录及路径边界在传输前阻断',async t=>{
  const original=WebDav.prototype.request, calls=[];
  WebDav.prototype.request=async function(method,url,max,body){calls.push({method,url:url.href,max,body:body.toString(),password:this.config.password});return Buffer.from(xml(response('/dav/')));};
  t.after(()=>{WebDav.prototype.request=original;});
  const config={url:root.href,username:'u',password:'private'};
  assert.equal((await browseFolders(config)).kind,'webdav');
  assert.equal(calls.length,1);assert.equal(calls[0].method,'PROPFIND');assert.equal(calls[0].max,512*1024);
  assert(!JSON.stringify(await browseFolders(config)).includes('private'));
  const before=calls.length;
  for(const target of ['https://evil.test/dav/','https://nas.test/dav/../','https://nas.test/dav/dormpanel-sync-v3/'])await assert.rejects(browseFolders(config,target));
  await assert.rejects(browseFolders({...config,url:'http://nas.test/dav/'}),/显式允许/);
  await assert.rejects(browseFolders({...config,certificatePem:'invalid'}));
  assert.equal(calls.length,before);
});
