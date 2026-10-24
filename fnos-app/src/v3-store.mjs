import { constants as C } from 'node:fs';
import { open, mkdir, opendir, link, unlink, statfs, rmdir } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { XMLParser, XMLValidator } from 'fast-xml-parser';
import { ensure, uuid } from './protocol.mjs';
import { WebDav } from './webdav.mjs';
import { decode, MAX_OBJECT, ZERO } from './v3-protocol.mjs';

export class Budget {
  constructor({ requests = 512, bytes = 64 * 1024 * 1024, millis = 90000, clock = () => performance.now() } = {}) {
    ensure(Number.isInteger(requests) && requests >= 0 && requests <= 512 && Number.isInteger(bytes) && bytes >= 0 && bytes <= 64 * 1024 * 1024 && Number.isInteger(millis) && millis >= 0 && millis <= 90000);
    Object.assign(this, { requests, bytes, millis, clock }); this.start = clock();
  }
  charge(bytes = 0, requests = 1) {
    ensure(Number.isSafeInteger(bytes) && bytes >= 0 && Number.isInteger(requests) && requests >= 0);
    ensure(this.clock() - this.start < this.millis && bytes <= this.bytes && requests <= this.requests, 'v3 budget exhausted');
    this.bytes -= bytes; this.requests -= requests;
  }
}
export const datasetScope = id => { uuid(id); ensure(id !== ZERO); return `dataset-${id}/`; };
export const epochsScope = dataset => datasetScope(dataset) + 'epochs/';
export const epochScope = (dataset, epoch) => { uuid(epoch); ensure(epoch !== ZERO); return epochsScope(dataset) + epoch.replaceAll('-', '').match(/../g).join('/') + '/'; };
/** A validated prefix is a serializable discovery cursor; a full UUID resolves without listing. */
export function scopeInfo(s) {
  ensure(typeof s === 'string');
  if (s === '') return { kind: 'root', datasetId: null, epochPrefix: '', epochId: null };
  const m = /^dataset-([0-9a-f-]{36})\/(?:epochs\/((?:[0-9a-f]{2}\/){0,16}))?$/.exec(s); ensure(m);
  const datasetId = m[1]; datasetScope(datasetId);
  if (s === datasetScope(datasetId)) return { kind: 'dataset', datasetId, epochPrefix: '', epochId: null };
  const epochPrefix = (m[2] ?? '').replaceAll('/', ''); ensure(epochPrefix.length <= 32);
  let epochId = null;
  if (epochPrefix.length === 32) { epochId = `${epochPrefix.slice(0,8)}-${epochPrefix.slice(8,12)}-${epochPrefix.slice(12,16)}-${epochPrefix.slice(16,20)}-${epochPrefix.slice(20)}`; ensure(s === epochScope(datasetId, epochId)); }
  return { kind: epochId ? 'epoch' : 'prefix', datasetId, epochPrefix, epochId };
}
export function scope(s) { scopeInfo(s); }
export function file(s) {
  ensure(typeof s === 'string'); const split = s.lastIndexOf('/'), parent = s.slice(0, split + 1), name = s.slice(split + 1), info = scopeInfo(parent);
  const m = /^(descriptor|genesis|commit|transition)-([0-9a-f-]{36})\.dpv$/.exec(name); ensure(m); uuid(m[2]); ensure(m[2] !== ZERO);
  ensure(m[1] === 'descriptor' ? info.kind === 'dataset' : info.kind === 'epoch');
}
export const objectPath = o => `${o.type === 1 ? datasetScope(o.datasetId) : epochScope(o.datasetId, o.epochId)}${['', 'descriptor', 'genesis', 'commit', 'transition'][o.type]}-${o.objectId}.dpv`;
export function match(path, bytes) { file(path); const o = decode(bytes); ensure(objectPath(o) === path, 'v3 path identity'); return o; }
function child(parent, name, directory) {
  const info = scopeInfo(parent), p = parent + name + (directory ? '/' : '');
  if (directory) {
    const next = scopeInfo(p);
    ensure(info.kind === 'root' ? next.kind === 'dataset' : info.kind === 'dataset' ? name === 'epochs' : info.kind === 'prefix' && /^[0-9a-f]{2}$/.test(name) && next.epochPrefix.length === info.epochPrefix.length + 2);
  } else file(p);
  return p;
}
const array = x => x == null ? [] : Array.isArray(x) ? x : [x];
export function parseListing(xml, base, parent) {
  scope(parent); ensure(Buffer.byteLength(xml) <= 512 * 1024 && !/<!DOCTYPE|<!ENTITY/i.test(xml) && XMLValidator.validate(xml) === true);
  const doc = new XMLParser({ removeNSPrefix: true, parseTagValue: false, trimValues: false }).parse(xml); ensure(doc.multistatus && typeof doc.multistatus === 'object');
  const seen = new Set(), result = [];
  for (const row of array(doc.multistatus.response)) {
    ensure(typeof row.href === 'string' && !row.href.includes('\\') && !/%2f|%5c|%00|(?:^|\/)(?:\.|%2e){1,2}(?:\/|$)/i.test(row.href));
    const u = new URL(row.href, base); ensure(u.origin === base.origin && !u.search && !u.hash && !u.username && !u.password && !u.pathname.includes('\\'));
    ensure(!seen.has(u.href)); seen.add(u.href);
    const props = array(row.propstat); ensure(props.length && props.every(p => /^HTTP\/\d(?:\.\d)? 200(?: |$)/.test(p.status)));
    const directory = props.some(p => p.prop?.resourcetype && Object.hasOwn(p.prop.resourcetype, 'collection'));
    if (u.href === base.href || u.href + '/' === base.href) { ensure(directory); continue; }
    ensure(u.href.startsWith(base.href)); const tail = u.href.slice(base.href.length).replace(/\/$/, ''); ensure(!tail.includes('/'));
    const p = child(parent, tail, directory); ensure(new URL(p.slice(parent.length), base).href === u.href); result.push(p); ensure(result.length <= 256);
  }
  return result.sort();
}

/** No production caller. A new transport per call reuses v2 TLS/SSRF/timeouts, never v2 paths.
 * The shared outer budget is mandatory across transports, epochs and cache reads. */
export class V3WebDavStore {
  constructor(config, budget) {
    this.config = config; this.budget = budget; const u = new URL(config.url);
    ensure(!u.pathname.split('/').some(p => ['dormpanel-sync-v2', 'dormpanel-sync-v3'].includes(decodeURIComponent(p))));
    this.base = new URL('dormpanel-sync-v3/', u); new WebDav(config);
  }
  async request(method, path, max, bytes) {
    this.budget.charge(bytes?.length ?? 0);
    const transport = new WebDav(this.config); transport.base = method === 'MKCOL' ? new URL(path, this.base) : this.base;
    const result = await transport.request(method, new URL(path, this.base), Math.min(max, this.budget.bytes), bytes);
    this.budget.charge(result?.length ?? 0, 0); return result;
  }
  async list(parent) {
    scope(parent); const raw = await this.request('PROPFIND', parent, 512 * 1024, '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>');
    // A missing collection is explicit failure, never evidence of an empty/unique dataset.
    ensure(raw !== null, 'v3 missing collection'); return parseListing(new TextDecoder('utf8', { fatal: true }).decode(raw), new URL(parent, this.base), parent);
  }
  async read(path) { file(path); const raw = await this.request('GET', path, MAX_OBJECT); if (raw) match(path, raw); return raw; }
  async prepareForIsolatedTest(parent) {
    scope(parent);
    for (const p of ['', ...parent.split('/').filter(Boolean).map((_, i, a) => a.slice(0, i + 1).join('/') + '/')]) {
      // Existing directories are verified by a bounded listing, not inferred from MKCOL 405.
      const t = new WebDav(this.config); t.base = new URL(p, this.base); this.budget.charge();
      const found = await t.request('PROPFIND', t.base, Math.min(512 * 1024, this.budget.bytes)); this.budget.charge(found?.length ?? 0, 0);
      if (found === null) await this.request('MKCOL', p, 65536);
    }
  }
  async createForIsolatedTest(path, raw, beforeWriteIntent) {
    match(path, raw); const old = await this.read(path); if (old) { ensure(old.equals(raw), 'v3 occupied'); return; }
    await beforeWriteIntent(); await this.request('PUT', path, 65536, raw);
    ensure((await this.read(path))?.equals(raw), 'v3 uncertain write; do not retry PUT');
  }
}

const dirs = C.O_RDONLY | C.O_DIRECTORY | C.O_NOFOLLOW, fdPath = (f, name = '') => `/proc/self/fd/${f.fd}${name ? '/' + name : ''}`;
/** Only LocalGrants can open the selected official authorized directory. Linux fd-relative traversal. */
export class V3LocalStore {
  constructor(config, grants, budget, afterStageForIsolatedTest = async () => {}) { Object.assign(this, { config, grants, budget, afterStageForIsolatedTest }); }
  async parent() {
    this.budget.charge();
    const selected = await this.grants.directory(this.config.rootId, this.config.relative, this.config.directoryIdentity);
    try {
      ensure(!selected.path.split('/').some(p => ['dormpanel-sync-v2', 'dormpanel-sync-v3'].includes(p)), 'v3 parent is inside protocol namespace');
      if (this.config.protected) ensure(((await selected.fd.stat()).mode & 0o777) === 0o700, 'v3 protected permissions changed');
      return selected;
    } catch (e) { await selected.fd.close(); throw e; }
  }
  async directory(parent, create = false) {
    scope(parent); let { fd } = await this.parent();
    try {
      for (const part of ['dormpanel-sync-v3', ...parent.split('/').filter(Boolean)]) {
        if (create) { try { await mkdir(fdPath(fd, part), { mode: 0o700 }); await fd.sync(); } catch (e) { if (e.code !== 'EEXIST') throw e; } }
        const next = await open(fdPath(fd, part), dirs); await fd.close(); fd = next;
      }
      return fd;
    } catch (e) { await fd.close(); throw e; }
  }
  async prepareForIsolatedTest(parent) { const fd = await this.directory(parent, true); await fd.close(); }
  async list(parent) {
    const fd = await this.directory(parent);
    try {
      const result = []; const dir = await opendir(fdPath(fd));
      for await (const row of dir) { ensure(result.length < 256 && (row.isFile() || row.isDirectory())); result.push(child(parent, row.name, row.isDirectory())); this.budget.charge(Buffer.byteLength(row.name), 0); }
      return result.sort();
    } finally { await fd.close(); }
  }
  async read(path) {
    file(path); let fd, f;
    try {
      fd = await this.directory(path.slice(0, path.lastIndexOf('/') + 1));
      f = await open(fdPath(fd, path.split('/').at(-1)), C.O_RDONLY | C.O_NOFOLLOW | C.O_NONBLOCK);
      const s = await f.stat(); ensure(s.isFile() && s.size <= MAX_OBJECT); this.budget.charge(s.size, 0);
      const b = Buffer.alloc(s.size + 1); let n = 0;
      while (n < b.length) { const r = await f.read(b, n, b.length - n, n); if (!r.bytesRead) break; n += r.bytesRead; }
      const after = await f.stat(); ensure(n === s.size && s.size === after.size && s.ctimeMs === after.ctimeMs && s.mtimeMs === after.mtimeMs);
      const current = await open(fdPath(fd, path.split('/').at(-1)), C.O_RDONLY | C.O_NOFOLLOW | C.O_NONBLOCK);
      try { const actual = await current.stat(); ensure(actual.dev === s.dev && actual.ino === s.ino, 'v3 local path replaced'); } finally { await current.close(); }
      const raw = b.subarray(0, n); match(path, raw); return raw;
    } catch (e) { if (e.code === 'ENOENT') return null; throw e; } finally { await f?.close(); await fd?.close(); }
  }
  async createForIsolatedTest(path, raw, beforeWriteIntent) {
    raw = Buffer.from(raw);
    match(path, raw); const old = await this.read(path); if (old) { ensure(old.equals(raw), 'v3 occupied'); return; }
    let protocol, parent, stage, f, created = false;
    const stageName = '.v3-stage-' + randomUUID();
    try {
      protocol = await this.directory(path.slice(0, path.lastIndexOf('/') + 1)); parent = await this.parent();
      const fs = await statfs(fdPath(parent.fd)); ensure([0xef53, 0x9123683e, 0x58465342, 0x01021994, 0x794c7630].includes(fs.type), 'v3 unverified filesystem');
      ensure((await parent.fd.stat()).dev === (await protocol.stat()).dev, 'v3 cross-filesystem publication');
      this.budget.charge(raw.length, 0);
      // Private sibling of dormpanel-sync-v3, following LocalCommitStore's no-follow/link/fsync boundary.
      await mkdir(fdPath(parent.fd, stageName), { mode: 0o700 }); created = true; await parent.fd.sync();
      stage = await open(fdPath(parent.fd, stageName), dirs); ensure(((await stage.stat()).mode & 0o777) === 0o700);
      f = await open(fdPath(stage, 'content'), C.O_WRONLY | C.O_CREAT | C.O_EXCL | C.O_NOFOLLOW, 0o600);
      await f.writeFile(raw); await f.chmod(0o444); await f.sync(); await f.close(); f = null;
      await stage.sync(); await this.afterStageForIsolatedTest(stageName, 'staged');
      this.budget.charge(0, 0); await beforeWriteIntent(); this.budget.charge(0, 0);
      await link(fdPath(stage, 'content'), fdPath(protocol, path.split('/').at(-1))); await protocol.sync();
      await this.afterStageForIsolatedTest(stageName, 'published');
    } finally {
      try {
        await f?.close();
        if (stage) { try { await unlink(fdPath(stage, 'content')); } catch (e) { if (e.code !== 'ENOENT') throw e; } await stage.sync(); }
      } finally {
        await stage?.close();
        try { if (created) { await rmdir(fdPath(parent.fd, stageName)); await parent.fd.sync(); } }
        finally { await protocol?.close(); await parent?.fd.close(); }
      }
    }
    ensure((await this.read(path))?.equals(raw), 'v3 uncertain local write');
  }
}
