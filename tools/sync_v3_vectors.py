"""独立标准库 oracle：v3 布局 + 已冻结 v2 集合编码；不调用 Kotlin/Node。"""
import copy
import hashlib
import json
import struct
from pathlib import Path
from sync_v2_vectors import snapshot

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'schedule-core/src/test/resources/sync-v3'
ZERO = '00000000-0000-0000-0000-000000000000'
H0 = '0' * 64
def uid(n): return f'00000000-0000-4000-8000-{n:012d}'
def i(n): return struct.pack('>i', n)
def digest(b): return hashlib.sha256(b).hexdigest()
def state_bytes(state):
    return snapshot(dict(datasetId=ZERO, commitId=ZERO, state=state))[109:-32]
def business_hash(state):
    state = copy.deepcopy(state)
    for source in state['sources']: source['importedAt'] = 0
    return digest(b'DPBS' + i(1) + state_bytes(state))
def reference(role, obj, raw):
    return dict(role=role, type=obj['type'], epochId=obj['epochId'], epochNumber=obj['epochNumber'], objectId=obj['objectId'], hash=digest(raw))
def ref_bytes(r):
    return i(r['role']) + i(r['type']) + r['epochId'].encode() + i(r['epochNumber']) + r['objectId'].encode() + bytes.fromhex(r['hash'])
def encode(o):
    body = b'DPV3' + i(3) + i(o['type']) + b''.join(o[k].encode() for k in ['datasetId', 'objectId', 'writerId', 'epochId'])
    refs = sorted(map(ref_bytes, o['refs']))
    raw = state_bytes(o['state']) if o['state'] is not None else b''
    body += i(o['epochNumber']) + bytes.fromhex(o['descriptorHash']) + i(len(refs)) + b''.join(refs) + i(len(raw)) + raw
    return body + hashlib.sha256(body).digest()
def vectors():
    complex_state = json.loads((ROOT / 'schedule-core/src/test/resources/sync-v2/complex-root.json').read_text(encoding='utf8'))['state']
    empty = {k: [] for k in complex_state}
    objects = {}
    def add(name, kind, n, epoch=ZERO, number=0, refs=None, state=None):
        o = dict(type=kind, datasetId=uid(1), objectId=uid(n), writerId=uid(2), epochId=epoch, epochNumber=number,
                 descriptorHash=digest(objects['descriptor'][1]) if kind != 1 else H0, refs=refs or [], state=state)
        raw = encode(o); objects[name] = o, raw; return o, raw
    descriptor, d = add('descriptor', 1, 3)
    g, gb = add('genesis-empty', 2, 4, uid(10), 1, state=empty)
    c, cb = add('commit-complex', 3, 5, uid(10), 1, [reference(1, g, gb)], complex_state)
    c2, cb2 = add('commit-multi', 3, 6, uid(10), 1, [reference(1, g, gb), reference(1, c, cb)], complex_state)
    g2, gb2 = add('checkpoint', 2, 7, uid(11), 2, [reference(2, c2, cb2)], complex_state)
    add('transition', 4, 8, uid(10), 1, [reference(2, c2, cb2), reference(5, g2, gb2)])
    add('bridge', 3, 9, uid(11), 2, [reference(1, g2, gb2), reference(3, c, cb), reference(4, g, gb)], complex_state)
    return objects
def output():
    files = {}; manifest = {}
    for name, (obj, raw) in vectors().items():
        files[name + '.dpv'] = raw
        manifest[name + '.hash'] = digest(raw); manifest[name + '.size'] = str(len(raw))
        if obj['state'] is not None: manifest[name + '.business'] = business_hash(obj['state'])
    def bad(name, raw, offset=None, value=None):
        b = bytearray(raw)
        if offset is not None: b[offset:offset+len(value)] = value
        b[-32:] = hashlib.sha256(b[:-32]).digest()
        files['bad-' + name + '.dpv'] = bytes(b)
    v = vectors(); raw = v['commit-complex'][1]
    bad('type', raw, 8, i(5)); bad('version', raw, 4, i(4)); bad('uuid', raw, 12, b'A')
    bad('negative-count', raw, 192, i(-1)); bad('large-count', raw, 192, i(129))
    bad('missing-parent', v['genesis-empty'][1], 8, i(3))
    bad('descriptor-fields', v['descriptor'][1], 156, i(1))
    bad('zero-reference-hash', raw, 280, bytes(32))
    bad('reference-role', raw, 196, i(6)); bad('reference-type', raw, 200, i(4))
    bad('negative-state-length', raw, 312, i(-1)); bad('large-state-length', raw, 312, i(16 * 1024 * 1024 + 1))
    bad('unknown-state-field', raw, 312, i(len(raw) - 348 + 1))
    multi = v['commit-multi'][1]; bad('ref-order', multi, 196, multi[312:428] + multi[196:312])
    title = raw.index('日历'.encode()); bad('utf8', raw, title, b'\xc0')
    bad('domain', raw, title + 14, raw[title+6:title+14])
    bad('tail', raw[:-32] + b'\0' + raw[-32:])
    files['bad-checksum.dpv'] = raw[:-1] + bytes([raw[-1] ^ 1])
    files['bad-truncated.dpv'] = raw[:-1]
    files['manifest.properties'] = ''.join(f'{k}={v}\n' for k,v in sorted(manifest.items())).encode()
    return files
if __name__ == '__main__':
    import sys
    files = output()
    if '--generate' in sys.argv:
        OUT.mkdir(parents=True, exist_ok=True)
        for name, raw in files.items(): (OUT / name).write_bytes(raw)
    for name, raw in files.items(): assert (OUT / name).read_bytes() == raw, name
    print(f'独立 v3 oracle：{len(files)} 个固定文件逐字节通过')
