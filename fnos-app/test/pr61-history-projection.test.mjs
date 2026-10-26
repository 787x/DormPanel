import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './fixtures/v3-catalog.mjs';
import { V3Maintenance } from '../src/v3-maintenance.mjs';

test('PR61 业务版本投影按有界索引游标筛选，稀疏页仍可继续，不读取全部快照或远端', async t => {
  const f = await fixture(t); await f.sync({create:true}); await f.settle();
  await f.db.edit({kind:'entry',action:'upsert',value:{id:'stable',title:'projection',day:1,startMinute:480,endMinute:540,location:''}});
  await f.sync(); await f.settle();
  const m = new V3Maintenance(f.catalog,f.id), frame=f.db.snapshot(), rows=()=>m.meta.db.prepare('SELECT * FROM v3_records ORDER BY seq').all();
  const before=rows(), remote=[...f.remote.objects], puts=f.remote.puts;
  f.remote.read=async()=>{throw Error('list must be local');};
  m.snapshot=async()=>{throw Error('list must not decode snapshots');};
  const found=[];let after=0,pages=0,emptyPages=0;
  do {
    const p=m.list({view:'business',after,limit:1});
    assert(p.rows.length<=1);for(const r of p.rows){assert([2,3].includes(r.type));assert(r.indexVerified);assert.equal(r.createdAt,null);found.push(r.id);}
    if(!p.rows.length)emptyPages++;pages++;assert(pages<20);if(p.next===null)break;assert(p.next>after);after=p.next;
  } while(true);
  assert.equal(found.length,2);assert(emptyPages>0);
  assert(m.list({view:'evidence'}).rows.some(r=>r.type===1));
  assert.throws(()=>m.list({view:'invalid'}),/视图/);
  assert.deepEqual(rows(),before);assert.deepEqual(f.db.snapshot(),frame);assert.deepEqual([...f.remote.objects],remote);assert.equal(f.remote.puts,puts);
});
