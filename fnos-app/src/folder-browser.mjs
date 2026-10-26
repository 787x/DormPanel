import { XMLParser, XMLValidator } from 'fast-xml-parser';
import { WebDav, binding } from './webdav.mjs';
import { ensure } from './protocol.mjs';

const array = x => x == null ? [] : Array.isArray(x) ? x : [x];
const internal = name => /^dormpanel-sync-v[23]$/i.test(name);
// Validate before URL resolves dot segments. Decode once; ambiguous encoded separators
// and double-encoded traversal are never navigation targets.
export function folderUrl(raw, root = null) {
  ensure(typeof raw === 'string' && raw.length <= 4096 && !/[\\\x00-\x20]/.test(raw), '目录地址无效');
  const path = raw.replace(/^[a-z]+:\/\/[^/]+/i, '').split(/[?#]/)[0];
  for (const segment of path.split('/')) {
    let decoded;
    try { decoded = decodeURIComponent(segment); } catch { throw Error('目录编码无效'); }
    ensure(!['.', '..'].includes(decoded) && !/[\\/\x00-\x1f\x7f]/.test(decoded) && !/%(?:2e|2f|5c|00)/i.test(decoded), '目录路径越界');
  }
  const u = new URL(raw, root ?? undefined);
  ensure(!u.username && !u.password && !u.search && !u.hash && ['http:', 'https:'].includes(u.protocol), '目录地址无效');
  if (!u.pathname.endsWith('/')) u.pathname += '/';
  if (root) ensure(u.origin === root.origin && u.pathname.startsWith(root.pathname), '目录超出浏览范围');
  ensure(u.pathname.split('/').filter(Boolean).length <= 64, '目录层级超限');
  return u;
}

export function parseFolders(xml, current, root) {
  ensure(Buffer.byteLength(xml) <= 512 * 1024 && !/<!DOCTYPE|<!ENTITY|\x00/i.test(xml), '目录响应超限或包含不安全 XML');
  ensure(XMLValidator.validate(xml) === true, '目录 XML 无效');
  const doc = new XMLParser({ removeNSPrefix:true, parseTagValue:false, trimValues:false, processEntities:true }).parse(xml);
  ensure(doc.multistatus && typeof doc.multistatus === 'object', '缺少目录列表');
  const rows = array(doc.multistatus.response), seen = new Set(), directories = [];
  ensure(rows.length <= 1024, '目录条目超限');
  let self = false;
  for (const row of rows) {
    ensure(typeof row.href === 'string', '目录链接无效');
    const url = folderUrl(row.href, current);
    folderUrl(url.href, root);
    ensure(!seen.has(url.href), '目录链接重复'); seen.add(url.href);
    const tail = url.pathname.slice(current.pathname.length).replace(/\/$/, '');
    ensure(url.href === current.href || tail && !tail.includes('/'), '服务器返回非直属目录');
    const props = array(row.propstat).filter(p => /^HTTP\/\d(?:\.\d)? 200(?: |$)/.test(p.status));
    const collection = props.some(p => p.prop?.resourcetype && Object.hasOwn(p.prop.resourcetype, 'collection'));
    if (url.href === current.href) { ensure(collection, '无法确认当前文件夹'); self = true; continue; }
    if (!collection) continue;
    const name = decodeURIComponent(tail);
    if (!internal(name)) directories.push({ name, url:url.href });
  }
  ensure(self, '服务器未确认当前文件夹，请使用高级手动地址或重试');
  return directories.sort((a,b) => a.name.localeCompare(b.name));
}

export async function browseFolders(config, target = config.url) {
  binding(config); // Same HTTP opt-in and exact certificate validation as sync.
  const root = folderUrl(config.url), current = folderUrl(target, root);
  ensure(!current.pathname.split('/').some(s => internal(decodeURIComponent(s))), '不能选择协议内部目录');
  // Transport retains DNS/SSRF, TLS, redirect rejection and bounded response handling.
  // This instance is local to one read: no sync list parser and no PUT/MKCOL calls.
  const transport = new WebDav(config); transport.base = root;
  let bytes;
  try {
    bytes = await transport.request('PROPFIND', current, 512 * 1024,
      Buffer.from('<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>'));
  } catch (error) {
    const message = String(error.message);
    throw Error(/HTTP 401\b/.test(message) ? '账号或密码验证失败，请检查服务器凭据' :
      /HTTP 403\b/.test(message) ? '无权读取此目录，请检查目录授权' :
      /HTTP (405|501)\b/.test(message) ? '服务器不支持 PROPFIND 目录列举，请使用高级手动完整 URL' :
      /HTTP 3\d\d\b/.test(message) ? '目录请求发生重定向，已停止；请核对服务器地址' :
      '目录读取失败，请检查网络、服务器地址、证书信任及响应大小后重试；也可使用高级完整 URL');
  }
  ensure(bytes, '目录不存在或无权访问');
  return { kind:'webdav', root:root.href, url:current.href, directories:parseFolders(bytes.toString('utf8'), current, root) };
}
