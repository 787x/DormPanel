import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { networkInterfaces } from 'node:os';
import { safeAddress } from '../src/webdav.mjs';
import { browseFolders } from '../src/folder-browser.mjs';

const address = Object.values(networkInterfaces()).flat().find(r=>r.family==='IPv4' && !r.internal && safeAddress(r.address,4))?.address;
// Bind to a real LAN interface: production SSRF protection remains enabled even in tests.
test('真实只读传输：PROPFIND、权限错误、禁止重定向、异常响应与 SSRF', {skip:!address}, async t=>{
  let mode='normal'; const requests=[];
  const server=http.createServer((req,res)=>{
    requests.push({method:req.method,depth:req.headers.depth,auth:req.headers.authorization});
    if(mode==='redirect'){res.writeHead(302,{Location:'http://evil.test/'});res.end();return;}
    if(typeof mode==='number'){res.writeHead(mode);res.end();return;}
    res.writeHead(207,{'Content-Type':'application/xml'});
    if(mode==='large'){res.end('x'.repeat(512*1024+1));return;}
    if(mode==='xml'){res.end('<!DOCTYPE x [<!ENTITY x SYSTEM "file:///etc/passwd">]><x/>');return;}
    res.end('<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>');
  });
  await new Promise(resolve=>server.listen(0,'0.0.0.0',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)));
  const config={url:`http://${address}:${server.address().port}/dav/`,username:'test',password:'private-test',allowInsecureHttp:true};
  assert.deepEqual((await browseFolders(config)).directories,[]);
  for(const [next,error] of [[401,/账号或密码/],[403,/无权/],[405,/PROPFIND/],[501,/PROPFIND/],['redirect',/重定向/],['large',/响应大小/],['xml',/XML/]]){
    mode=next;await assert.rejects(browseFolders(config),error);
  }
  const count=requests.length;
  await assert.rejects(browseFolders({...config,url:`http://127.0.0.1:${server.address().port}/dav/`}));
  assert.equal(requests.length,count);
  assert(requests.every(r=>r.method==='PROPFIND' && r.depth==='1' && r.auth==='Basic '+Buffer.from('test:private-test').toString('base64')));
});
