import https from 'node:https';
import { XMLParser, XMLValidator } from 'fast-xml-parser';
import { ensure, uuid, MAX } from './protocol.mjs';

const array = x => x == null ? [] : Array.isArray(x) ? x : [x];
export class NetworkError extends Error { recoverable = true; }
export function binding(config) {
  const u = new URL(config.url);
  ensure(u.protocol === 'https:' && !u.username && !u.password && !u.search && !u.hash, '绑定必须是无凭据的 HTTPS 目录 URL');
  ensure(u.pathname.endsWith('/') && !/%2f|%5c|%00/i.test(u.pathname), '绑定目录路径无效');
  const base = new URL('dormpanel-sync-v2/', u);
  return { base, key: base.href };
}
export function parseListing(xml, base) {
  ensure(Buffer.byteLength(xml) <= 512 * 1024 && !/<!DOCTYPE|<!ENTITY/i.test(xml), 'WebDAV XML 超限或实体声明');
  ensure(XMLValidator.validate(xml) === true, 'WebDAV XML 损坏');
  const doc = new XMLParser({ removeNSPrefix: true, parseTagValue: false, trimValues: false, processEntities: true }).parse(xml);
  ensure(doc.multistatus && typeof doc.multistatus === 'object', '缺少 multistatus');
  const seen = new Set(), ids = [];
  for (const r of array(doc.multistatus.response)) {
    ensure(typeof r.href === 'string', '非法 href'); const u = new URL(r.href, base);
    ensure(u.origin === base.origin && !u.search && !u.hash && !u.username && !u.password && !/%2f|%5c|%00/i.test(u.pathname), '越界 href');
    ensure(!seen.has(u.href), '重复 href'); seen.add(u.href);
    const props = array(r.propstat); ensure(props.length > 0 && props.every(p => /^HTTP\/\d(?:\.\d)? 200(?: |$)/.test(p.status)), '不可读 propstat');
    const collection = props.some(p => p.prop?.resourcetype && Object.hasOwn(p.prop.resourcetype, 'collection'));
    if (u.href === base.href || u.href + '/' === base.href) { ensure(collection, '目录资源类型不符'); continue; }
    ensure(!collection && u.pathname.startsWith(base.pathname), '子目录或越界资源');
    const name = u.pathname.slice(base.pathname.length), match = /^commit-([0-9a-f-]{36})\.dpc$/.exec(name);
    ensure(match, 'v2 目录含非协议文件'); uuid(match[1]); ids.push(match[1]);
    ensure(ids.length <= 256, '目录提交数超限');
  }
  return ids.sort();
}
export class WebDav {
  constructor(config) { this.config = config; Object.assign(this, binding(config)); this.requests = 0; this.bytes = 0; this.started = Date.now(); }
  async request(method, url, max, body) {
    ensure(['GET', 'PROPFIND'].includes(method) && url.origin === this.base.origin && url.pathname.startsWith(this.base.pathname), '只读传输边界');
    ensure(++this.requests <= 512 && Date.now() - this.started < 90000, '同步请求或时间预算超限');
    return new Promise((resolve, reject) => {
      const req = https.request(url, { method, headers: { Authorization: 'Basic ' + Buffer.from(`${this.config.username}:${this.config.password}`).toString('base64'), ...(method === 'PROPFIND' ? { Depth: '1', 'Content-Type': 'application/xml' } : {}) } }, res => {
        const chunks = []; let length = 0;
        res.on('data', b => { length += b.length; this.bytes += b.length; if (length > max || this.bytes > 64 * 1024 * 1024) { reject(new Error('响应字节数超限')); req.destroy(); } else chunks.push(b); });
        res.on('end', () => { if (res.statusCode === 404) return resolve(null); if (res.statusCode !== (method === 'PROPFIND' ? 207 : 200)) return reject(new Error('WebDAV HTTP ' + res.statusCode)); resolve(Buffer.concat(chunks)); });
        res.on('error', () => reject(new NetworkError('WebDAV 响应中断')));
      });
      const total = setTimeout(() => req.destroy(new Error('WebDAV 总超时')), 60000);
      req.on('socket', socket => { const connect = setTimeout(() => req.destroy(new Error('WebDAV 连接超时')), 10000); socket.once('secureConnect', () => clearTimeout(connect)); socket.once('close', () => clearTimeout(connect)); });
      req.setTimeout(20000, () => req.destroy(new Error('WebDAV 读取超时')));
      req.on('close', () => clearTimeout(total)); req.on('error', e => reject(e.fatal ? e : new NetworkError('WebDAV 网络失败')));
      req.end(body);
    });
  }
  async list() {
    const xml = await this.request('PROPFIND', this.base, 512 * 1024, '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>');
    return xml === null ? [] : parseListing(new TextDecoder('utf-8', { fatal: true }).decode(xml), this.base);
  }
  read(id) { uuid(id); return this.request('GET', new URL(`commit-${id}.dpc`, this.base), MAX + 8192); }
}
