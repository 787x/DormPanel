import {encode as encodeRecords, decode as decodeRecords, wireRecords, durableRecords, canonicalRecords} from './shared-records.mjs';
import { ensure, uuid, hash, encode as v2encode, decode as v2decode, stateBytes as collections, MAX } from './protocol.mjs';

export const ZERO = '00000000-0000-0000-0000-000000000000', ZERO_HASH = '0'.repeat(64);
export const MAX_OBJECT = MAX + 32768;
export const sha = b => hash(b).toString('hex');
export const hashValue = s => ensure(typeof s === 'string' && /^[0-9a-f]{64}$/.test(s), 'v3 hash');
const i = n => { ensure(Number.isInteger(n) && n >= -2147483648 && n <= 2147483647); const b = Buffer.alloc(4); b.writeInt32BE(n); return b; };
const h = s => { hashValue(s); return Buffer.from(s, 'hex'); };
export const canonicalLegacy = state => v2decode(v2encode({ datasetId: ZERO, commitId: ZERO, writerId: ZERO, parents: [], state: Object.fromEntries(Object.entries(state).filter(([k])=>k!=='sharedRecords')) })).state;
export const stateBytes = state => {
 const legacy=collections(canonicalLegacy(state));
 if(state.sharedRecords===undefined)return legacy;
 const ext=encodeRecords(wireRecords(canonicalRecords(state.sharedRecords)));
 const raw=Buffer.concat([Buffer.from('DPXS'),i(1),i(legacy.length),legacy,i(ext.length),ext]);ensure(raw.length<=MAX);return raw;
};
export const businessHash = state => sha(Buffer.concat([Buffer.from('DPBS'), i(1), stateBytes({ ...state, sources: state.sources.map(s => ({ ...s, importedAt: 0n })) })]));
const refBytes = r => Buffer.concat([i(r.role), i(r.type), Buffer.from(r.epochId), i(r.epochNumber), Buffer.from(r.objectId), h(r.hash)]);
function validate(o) {
  [o.datasetId, o.objectId, o.writerId, o.epochId].forEach(uuid);
  ensure([o.datasetId, o.objectId, o.writerId].every(x => x !== ZERO)); hashValue(o.descriptorHash);
  ensure([1, 2, 3, 4].includes(o.type) && o.refs.length <= 128);
  ensure(new Set(o.refs.map(r => `${r.epochId}/${r.objectId}`)).size === o.refs.length);
  for (const r of o.refs) {
    [r.epochId, r.objectId].forEach(uuid); hashValue(r.hash);
    ensure(r.epochId !== ZERO && r.objectId !== ZERO && r.objectId !== o.objectId && r.hash !== ZERO_HASH && Number.isInteger(r.epochNumber) && r.epochNumber > 0 && r.epochNumber <= 2147483647 && [2, 3].includes(r.type) && [1, 2, 3, 4, 5].includes(r.role));
  }
  if (o.type === 1) { ensure(o.epochId === ZERO && o.epochNumber === 0 && o.descriptorHash === ZERO_HASH && !o.refs.length && o.state === null); return; }
  ensure(o.epochId !== ZERO && Number.isInteger(o.epochNumber) && o.epochNumber > 0 && o.epochNumber <= 2147483647 && o.descriptorHash !== ZERO_HASH);
  const same = r => r.epochId === o.epochId && r.epochNumber === o.epochNumber;
  if (o.type === 2) {
    ensure(o.state !== null && o.refs.every(r => r.role === 2 && r.epochNumber === o.epochNumber - 1 && r.epochId !== o.epochId));
    ensure(o.epochNumber === 1 ? !o.refs.length : o.refs.length >= 1 && o.refs.length <= 64 && new Set(o.refs.map(r => r.epochId)).size === 1);
  } else if (o.type === 3) {
    const parents = o.refs.filter(r => r.role === 1), late = o.refs.filter(r => r.role === 3), base = o.refs.filter(r => r.role === 4);
    ensure(o.state !== null && o.refs.every(r => [1, 3, 4].includes(r.role)) && parents.length >= 1 && parents.length <= 64 && parents.every(same));
    ensure(late.length <= 62 && late.every(r => r.epochNumber < o.epochNumber && r.epochId !== o.epochId));
    ensure(late.length ? base.length === 1 && base[0].epochNumber < o.epochNumber && base[0].epochId !== o.epochId : !base.length);
  } else {
    const source = o.refs.filter(r => r.role === 2), target = o.refs.filter(r => r.role === 5);
    ensure(o.state === null && o.refs.every(r => [2, 5].includes(r.role)) && source.length >= 1 && source.length <= 64 && source.every(same) && target.length === 1);
    ensure(o.epochNumber < 2147483647 && target[0].type === 2 && target[0].epochNumber === o.epochNumber + 1 && target[0].epochId !== o.epochId);
  }
}
export function encode(o) {
  ensure([3,4].includes(o.format ?? (o.state?.sharedRecords!==undefined?4:3)));
  ensure(o.state===null || ((o.format ?? (o.state?.sharedRecords!==undefined?4:3))===4)===(o.state.sharedRecords!==undefined));
  validate(o); const state = o.state === null ? Buffer.alloc(0) : stateBytes(o.state);
  const refs = o.refs.map(refBytes).sort(Buffer.compare);
  const body = Buffer.concat([Buffer.from('DPV3'), i(o.format ?? (o.state?.sharedRecords!==undefined?4:3)), i(o.type), ...[o.datasetId, o.objectId, o.writerId, o.epochId].map(s => Buffer.from(s)), i(o.epochNumber), h(o.descriptorHash), i(refs.length), ...refs, i(state.length), state]);
  ensure(body.length + 32 <= MAX_OBJECT); return Buffer.concat([body, hash(body)]);
}
export function decode(bytes) {
  ensure(Buffer.isBuffer(bytes) && bytes.length >= 232 && bytes.length <= MAX_OBJECT);
  const body = bytes.subarray(0, -32); ensure(hash(body).equals(bytes.subarray(-32)), 'v3 checksum');
  let p = 0;
  const take = n => { ensure(Number.isInteger(n) && n >= 0 && p + n <= body.length); const b = body.subarray(p, p + n); p += n; return b; };
  const num = () => take(4).readInt32BE(), id = () => { const s = take(36).toString('utf8'); uuid(s); return s; }, hex = () => take(32).toString('hex');
  ensure(take(4).toString() === 'DPV3'); const format=num();ensure(format===3||format===4);
  const type = num(), datasetId = id(), objectId = id(), writerId = id(), epochId = id(), epochNumber = num(), descriptorHash = hex(), n = num();
  ensure(n >= 0 && n <= 128);
  const refs = Array.from({ length: n }, () => ({ role: num(), type: num(), epochId: id(), epochNumber: num(), objectId: id(), hash: hex() }));
  const length = num(); ensure(length >= 0 && length <= MAX); let raw = take(length); ensure(p === body.length);
  let state = null;
  let sharedRecords;
  if(length && format===4){
   let q=0;const get=n=>{ensure(n>=0&&q+n<=raw.length);const b=raw.subarray(q,q+n);q+=n;return b;};const count=()=>get(4).readInt32BE();
   ensure(get(4).toString()==='DPXS'&&count()===1);const legacy=get(count());sharedRecords=durableRecords(decodeRecords(get(count())));ensure(q===raw.length);raw=legacy;
  }
  if (length) {
    // Reconstruct a fixed-identity DPC2 solely to reuse the mature state reader/validator.
    const empty = { events: [], entries: [], sources: [], imported: [], dayAdjustments: [], classOverrides: [], profiles: [], customProfileKeys: [] };
    const sample = v2encode({ datasetId: ZERO, commitId: ZERO, writerId: ZERO, parents: [], state: empty });
    const payload = Buffer.concat([sample.subarray(136, 233), raw]);
    const snapshot = Buffer.concat([Buffer.from('DPSS'), i(1), i(payload.length), payload, hash(payload)]);
    const envelope = Buffer.concat([sample.subarray(0, 120), i(snapshot.length), snapshot]);
    state = v2decode(Buffer.concat([envelope, hash(envelope)])).state;
  }
  if(sharedRecords!==undefined)state.sharedRecords=sharedRecords;
  const o = { ...(format===4?{format}:{}), type, datasetId, objectId, writerId, epochId, epochNumber, descriptorHash, refs, state };
  ensure(encode(o).equals(bytes), '非规范 v3 对象'); return o;
}
export function check(bytes, dataset, objectId, expectedHash) {
  hashValue(expectedHash); ensure(sha(bytes) === expectedHash, 'v3 content hash'); const o = decode(bytes);
  ensure(o.datasetId === dataset && o.objectId === objectId, 'v3 identity'); return o;
}
export function checkReference(bytes, dataset, descriptorHash, ref) {
  const o = check(bytes, dataset, ref.objectId, ref.hash);
  ensure(o.type === ref.type && o.epochId === ref.epochId && o.epochNumber === ref.epochNumber && o.descriptorHash === descriptorHash, 'v3 reference identity');
  return o;
}
