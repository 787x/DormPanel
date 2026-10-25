import { ensure } from './protocol.mjs';
import { V3Engine } from './v3-engine.mjs';
import { V3History,V3Discovery } from './v3-history.mjs';
import { IndexedV3Cache } from './v3-index.mjs';
import { decode,sha } from './v3-protocol.mjs';
import { objectPath,epochScope } from './v3-store.mjs';

/** No publication interface. Discovery and ancestry validation resume in this journal. */
export async function inspectV3Target(journal,writerId,remote,budget,descriptorRaw) {
  const descriptor=decode(descriptorRaw); ensure(descriptor.type===1,'目标不是 Descriptor');
  if(!journal.value.binding) await new V3Engine(journal,writerId).bind(descriptor,sha(descriptorRaw));
  ensure(journal.value.binding.descriptorHash===sha(descriptorRaw),'预检目标身份变化');
  journal.meta.budget=budget;
  try {
    const cache=new IndexedV3Cache(journal.directory,budget); await cache.open();
    const discovery=new V3Discovery(journal,remote,descriptor.datasetId); await discovery.scan(32);
    const history=await new V3History(journal,cache,remote,objectPath(descriptor),sha(descriptorRaw),budget).open();
    for(const epoch of history.activeEpochs().slice(-8)) { const scope=epochScope(descriptor.datasetId,epoch); discovery.observe(scope,await remote.list(scope)); }
    for(let n=0;n<32;n++) { const row=journal.meta.page('todo',0,1)[0]; if(!row)break; await history.verify(row.key); }
    history.drainActivation();
    if(!journal.meta.get('control','initialComplete') || journal.meta.page('todo',0,1).length) return null;
    ensure(!journal.meta.page('error',0,1).length,'目标存在未决历史异常');
    const tips=history.tips(); ensure(tips.length===1,'目标存在并发前沿；请由活动 v3 Writer 合并或解决冲突后再加入');
    const path=tips[0],raw=await remote.read(path); ensure(raw && sha(raw)===history.node(path).hash,'目标快照缺失或身份变化');
    const object=decode(raw); ensure(objectPath(object)===path && object.datasetId===descriptor.datasetId);
    ensure((await remote.read(objectPath(descriptor)))?.equals(descriptorRaw),'目标 Descriptor 身份变化');
    return { descriptor,descriptorPath:objectPath(descriptor),descriptorHash:sha(descriptorRaw),path,hash:sha(raw),raw,state:object.state };
  } catch(e) { if(/budget/.test(e.message))return null; throw e; }
}
