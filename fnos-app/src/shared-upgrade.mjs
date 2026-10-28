import {randomUUID} from 'node:crypto';
import {encode,decode,sha,ZERO,ZERO_HASH} from './v3-protocol.mjs';
import {ensure} from './protocol.mjs';
export function previewSharedUpgrade(engine,sourceCapture){
 const source=decode(sourceCapture);ensure((source.format??3)===3&&source.state&&!engine.journal.value.binding,'Separate unbound target required');
 const descriptor={format:4,type:1,datasetId:randomUUID(),objectId:randomUUID(),writerId:engine.writerId,epochId:ZERO,epochNumber:0,descriptorHash:ZERO_HASH,refs:[],state:null};
 const state={...source.state,sharedRecords:[]};
 const genesis={format:4,type:2,datasetId:descriptor.datasetId,objectId:randomUUID(),writerId:engine.writerId,epochId:randomUUID(),epochNumber:1,descriptorHash:sha(encode(descriptor)),refs:[],state};
 const preview={id:randomUUID(),status:'PREVIEW',sourceHash:sha(sourceCapture),sourceCapture:sourceCapture.toString('base64'),targetDatasetId:descriptor.datasetId,counts:Object.fromEntries(Object.entries(state).map(([k,v])=>[k,v.length])),descriptor:encode(descriptor).toString('base64'),genesis:encode(genesis).toString('base64'),warning:'源 Writer 仍可发布；本操作不切换源绑定，不证明源已停写'};
 engine.journal.meta.atomic(()=>engine.journal.meta.put('sharedUpgrade',preview.id,preview));return structuredClone(preview);
}
export function cancelSharedUpgrade(engine,id){const p=engine.journal.meta.get('sharedUpgrade',id);ensure(p?.status==='PREVIEW');p.status='CANCELLED';engine.journal.meta.atomic(()=>engine.journal.meta.put('sharedUpgrade',id,p));}
export async function confirmSharedUpgrade(engine,id,currentSourceCapture,remote){
 const p=engine.journal.meta.get('sharedUpgrade',id);ensure(['PREVIEW','PUBLISHING'].includes(p?.status)&&p.sourceHash===sha(currentSourceCapture),'Source changed or preview cancelled');
 const descriptor=decode(Buffer.from(p.descriptor,'base64')),genesis=decode(Buffer.from(p.genesis,'base64'));
 if(!engine.journal.value.binding)await engine.bind(descriptor,genesis.descriptorHash,genesis.state,[p.descriptor,p.genesis]);
 ensure(engine.journal.value.binding.datasetId===p.targetDatasetId);
 p.status='PUBLISHING';engine.journal.meta.atomic(()=>engine.journal.meta.put('sharedUpgrade',id,p));
 const result=await engine.initialize(remote);p.status='TARGET_READBACK_CONFIRMED';engine.journal.meta.atomic(()=>engine.journal.meta.put('sharedUpgrade',id,p));return result;
}
