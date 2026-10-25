import https from "node:https";
import http from "node:http";
import dns from "node:dns";
import { X509Certificate } from "node:crypto";
import { BlockList } from "node:net";
import { XMLParser, XMLValidator } from "fast-xml-parser";
import { ensure, uuid, MAX } from "./protocol.mjs";

const array = (x) => (x == null ? [] : Array.isArray(x) ? x : [x]);
const blocked = new BlockList();
for (const [network, prefix, family] of [
  ["0.0.0.0", 8, "ipv4"],
  ["127.0.0.0", 8, "ipv4"],
  ["169.254.0.0", 16, "ipv4"],
  ["224.0.0.0", 4, "ipv4"],
  ["240.0.0.0", 4, "ipv4"],
  ["::", 128, "ipv6"],
  ["::1", 128, "ipv6"],
  ["fe80::", 10, "ipv6"],
  ["ff00::", 8, "ipv6"],
])
  blocked.addSubnet(network, prefix, family);
export function safeAddress(address, family) {
  return !blocked.check(address, family === 6 ? "ipv6" : "ipv4");
}
function lookup(host, options, callback) {
  dns.lookup(host, { all: true }, (error, rows) => {
    if (error) return callback(error);
    if (!rows.length || rows.some((r) => !safeAddress(r.address, r.family)))
      return callback(Error("WebDAV 目标地址被拒绝"));
    const filtered = options.family
      ? rows.filter((r) => r.family === options.family)
      : rows;
    if (!filtered.length) return callback(Error("WebDAV 地址族不可用"));
    callback(
      null,
      options.all ? filtered : filtered[0].address,
      filtered[0].family,
    );
  });
}
export class NetworkError extends Error {
  recoverable = true;
}
// 管理员显式信任从独立可信渠道取得的服务器公钥证书。仅此连接信任，绝不关闭 TLS 校验。
export function tlsOptions(config) {
  if (!config.certificatePem) return {};
  ensure(
    typeof config.certificatePem === "string" &&
      Buffer.byteLength(config.certificatePem) <= 16384,
    "证书大小无效",
  );
  const expected = new X509Certificate(config.certificatePem);
  return {
    ca: config.certificatePem,
    rejectUnauthorized: true,
    checkServerIdentity: (_host, peer) => {
      if (!peer.raw || !new X509Certificate(peer.raw).raw.equals(expected.raw))
        return Error("WebDAV 服务器证书与显式信任的证书不符");
    },
  };
}
export function binding(config) {
  const u = new URL(config.url);
  ensure(
    (u.protocol === "https:" || u.protocol === "http:" && config.allowInsecureHttp === true) &&
      !u.username &&
      !u.password &&
      !u.search &&
      !u.hash,
    "连接需要 HTTPS；HTTP 须显式允许明文传输帐号、密码和课表",
  );
  ensure(
    u.pathname.endsWith("/") && !/%2f|%5c|%00/i.test(u.pathname),
    "绑定目录路径无效",
  );
  const base = new URL("dormpanel-sync-v2/", u);
  ensure(u.protocol !== 'http:' || !config.certificatePem, 'HTTP 不支持证书信任；请明确清除旧证书或使用 HTTPS');
  tlsOptions(config);
  return { base, key: base.href };
}
export function parseListing(xml, base) {
  ensure(
    Buffer.byteLength(xml) <= 512 * 1024 && !/<!DOCTYPE|<!ENTITY/i.test(xml),
    "WebDAV XML 超限或实体声明",
  );
  ensure(XMLValidator.validate(xml) === true, "WebDAV XML 损坏");
  const doc = new XMLParser({
    removeNSPrefix: true,
    parseTagValue: false,
    trimValues: false,
    processEntities: true,
  }).parse(xml);
  ensure(
    doc.multistatus && typeof doc.multistatus === "object",
    "缺少 multistatus",
  );
  const seen = new Set(),
    ids = [];
  for (const r of array(doc.multistatus.response)) {
    ensure(typeof r.href === "string", "非法 href");
    const u = new URL(r.href, base);
    ensure(
      u.origin === base.origin &&
        !u.search &&
        !u.hash &&
        !u.username &&
        !u.password &&
        !/%2f|%5c|%00/i.test(u.pathname),
      "越界 href",
    );
    ensure(!seen.has(u.href), "重复 href");
    seen.add(u.href);
    const props = array(r.propstat);
    ensure(
      props.length > 0 &&
        props.every((p) => /^HTTP\/\d(?:\.\d)? 200(?: |$)/.test(p.status)),
      "不可读 propstat",
    );
    const collection = props.some(
      (p) =>
        p.prop?.resourcetype &&
        Object.hasOwn(p.prop.resourcetype, "collection"),
    );
    if (u.href === base.href || u.href + "/" === base.href) {
      ensure(collection, "目录资源类型不符");
      continue;
    }
    ensure(
      !collection && u.pathname.startsWith(base.pathname),
      "子目录或越界资源",
    );
    const name = u.pathname.slice(base.pathname.length),
      match = /^commit-([0-9a-f-]{36})\.dpc$/.exec(name);
    ensure(match, "v2 目录含非协议文件");
    uuid(match[1]);
    ids.push(match[1]);
    ensure(ids.length <= 256, "目录提交数超限");
  }
  return ids.sort();
}
export class WebDav {
  constructor(config) {
    this.config = config;
    Object.assign(this, binding(config));
    this.requests = 0;
    this.bytes = 0;
    this.started = Date.now();
  }
  async request(method, url, max, body) {
    ensure(
      ["GET", "PROPFIND", "PUT", "MKCOL"].includes(method) &&
        url.origin === this.base.origin &&
        url.pathname.startsWith(this.base.pathname) &&
        !url.username &&
        !url.password &&
        !url.search &&
        !url.hash,
      "传输路径越界",
    );
    ensure(
      method !== "MKCOL" || url.href === this.base.href,
      "仅允许建立 v2 目录",
    );
    ensure(
      ++this.requests <= 512 && Date.now() - this.started < 90000,
      "同步请求或时间预算超限",
    );
    return new Promise((resolve, reject) => {
      const literal = url.hostname.replace(/^\[|\]$/g, "");
      ensure(
        !/^\d+\.\d+\.\d+\.\d+$/.test(literal) || safeAddress(literal, 4),
        "WebDAV 目标地址被拒绝",
      );
      ensure(
        !literal.includes(":") || safeAddress(literal, 6),
        "WebDAV 目标地址被拒绝",
      );
      const req = (url.protocol === 'https:' ? https : http).request(
        url,
        {
          method,
          lookup,
          ...tlsOptions(this.config),
          headers: {
            Authorization:
              "Basic " +
              Buffer.from(
                `${this.config.username}:${this.config.password}`,
              ).toString("base64"),
            ...(method === "PROPFIND"
              ? { Depth: "1", "Content-Type": "application/xml" }
              : method === "PUT"
                ? {
                    "Content-Type": "application/octet-stream",
                    "Content-Length": body.length,
                  }
                : {}),
          },
        },
        (res) => {
          const chunks = [];
          let length = 0;
          res.on("data", (b) => {
            length += b.length;
            this.bytes += b.length;
            if (length > max || this.bytes > 64 * 1024 * 1024) {
              reject(new Error("响应字节数超限"));
              req.destroy();
            } else chunks.push(b);
          });
          res.on("end", () => {
            if (res.statusCode === 404 && ["GET", "PROPFIND"].includes(method))
              return resolve(null);
            if (
              !(method === "PUT"
                ? [200, 201, 204].includes(res.statusCode)
                : res.statusCode ===
                  (method === "PROPFIND"
                    ? 207
                    : method === "MKCOL"
                      ? 201
                      : 200))
            )
              return reject(new Error("WebDAV HTTP " + res.statusCode));
            resolve(Buffer.concat(chunks));
          });
          res.on("error", () => reject(new NetworkError("WebDAV 响应中断")));
        },
      );
      const total = setTimeout(
        () => req.destroy(new Error("WebDAV 总超时")),
        60000,
      );
      req.on("socket", (socket) => {
        if (!socket.connecting) return; // keep-alive 复用的 TLS 已建立，不重复注册握手监听。
        const connect = setTimeout(
          () => req.destroy(new Error("WebDAV 连接超时")),
          10000,
        );
        const clear = () => {
          clearTimeout(connect);
          socket.removeListener(url.protocol === 'https:' ? "secureConnect" : "connect", clear);
        };
        socket.once(url.protocol === 'https:' ? "secureConnect" : "connect", clear);
        req.once("close", clear);
      });
      req.setTimeout(20000, () => req.destroy(new Error("WebDAV 读取超时")));
      req.on("close", () => clearTimeout(total));
      req.on("error", (e) =>
        reject(e.fatal ? e : new NetworkError("WebDAV 网络失败")),
      );
      req.end(body);
    });
  }
  async list() {
    const xml = await this.request(
      "PROPFIND",
      this.base,
      512 * 1024,
      '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>',
    );
    this.missing = xml === null;
    return xml === null
      ? []
      : parseListing(
          new TextDecoder("utf-8", { fatal: true }).decode(xml),
          this.base,
        );
  }
  read(id) {
    uuid(id);
    return this.request(
      "GET",
      new URL(`commit-${id}.dpc`, this.base),
      MAX + 8192,
    );
  }
  put(id, bytes) {
    uuid(id);
    ensure(bytes.length <= MAX + 8192, "上传超限");
    return this.request(
      "PUT",
      new URL(`commit-${id}.dpc`, this.base),
      65536,
      bytes,
    );
  }
  async ensureDirectory() {
    if (this.missing) await this.request("MKCOL", this.base, 65536);
  }
}
