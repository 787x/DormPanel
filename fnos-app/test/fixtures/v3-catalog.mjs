import assert from 'node:assert/strict';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store } from '../../src/store.mjs';
import { Catalog } from '../../src/catalog.mjs';

export async function fixture(t) {
  const objects = new Map(); let puts = 0;
  const remote = { objects, get puts() { return puts; }, read: async p => objects.get(p) ?? null,
    putOnce: async (p,b) => { assert(!objects.has(p)); objects.set(p,Buffer.from(b)); puts++; },
    list: async s => [...new Set([...objects.keys()].filter(p=>p.startsWith(s)).map(p=> { const tail=p.slice(s.length), i=tail.indexOf('/'); return s+(i<0?tail:tail.slice(0,i+1)); }))].sort() };
  const dir = await mkdtemp(join(tmpdir(),'pr56-')), store = await new Store(dir).init();
  const catalog = await Catalog.open({store,configPath:join(dir,'config.json'),v3RemoteFactory:()=>remote});
  t.after(()=>catalog.close());
  const manage = input=>catalog.manage({catalogVersion:catalog.manifest.revision,...input});
  const {connectionId}=await manage({action:'connection.save',name:'isolated',type:'webdav',url:'https://isolated.test/',username:'test',password:'test'});
  const {workspaceId:id}=await manage({action:'workspace.add',name:'test',connectionId,protocol:'v3'});
  await manage({action:'workspace.switch',targetId:id});
  const db=catalog.db(id), sync=opts=>catalog.network(id,e=>e.synchronize(opts));
  const settle=async()=>{for(let i=0;i<5;i++)await sync();};
  return {dir,catalog,id,db,remote,sync,settle};
}
