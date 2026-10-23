import { ensure } from "./protocol.mjs";
export const XLS_ID = "hubei-personal-xls-v1";
export const oleSignature = (b) =>
  b.length >= 8 &&
  b.subarray(0, 8).equals(Buffer.from("d0cf11e0a1b11ae1", "hex"));
// Intentionally bounded BIFF8 text reader. No formula evaluation or object loading.
export function xlsSheets(b) {
  const deadline = performance.now() + 2000;
  const ok = (v) =>
    ensure(
      v && performance.now() <= deadline,
      "不支持、损坏或超出资源限制的 BIFF8 XLS",
    );
  const u16 = (b, p) => {
    ok(p >= 0 && p + 2 <= b.length);
    return b.readUInt16LE(p);
  };
  const u32 = (b, p) => {
    ok(p >= 0 && p + 4 <= b.length);
    return b.readUInt32LE(p);
  };
  ok(oleSignature(b) && b.length <= 1048576 && b.length >= 512);
  ok(
    u16(b, 26) === 3 &&
      u16(b, 28) === 0xfffe &&
      u16(b, 30) === 9 &&
      u16(b, 32) === 6,
  );
  const count = b.length / 512 - 1;
  ok(Number.isInteger(count) && count > 0);
  const sector = (n) => {
    ok(n < count);
    return b.subarray((n + 1) * 512, (n + 2) * 512);
  };
  ok(
    u32(b, 44) > 0 &&
      u32(b, 44) <= 109 &&
      u32(b, 72) === 0 &&
      u32(b, 56) === 4096,
  );
  const fat = [];
  const fatIds = new Set();
  for (let i = 0; i < u32(b, 44); i++) {
    const n = u32(b, 76 + 4 * i);
    ok(!fatIds.has(n));
    fatIds.add(n);
    const s = sector(n);
    for (let j = 0; j < 512; j += 4) fat.push(u32(s, j));
  }
  const chain = (start, table, read, max) => {
    let n = start;
    const seen = new Set(),
      out = [];
    while (n !== 0xfffffffe) {
      ok(n < table.length && !seen.has(n) && seen.size < max);
      seen.add(n);
      out.push(read(n));
      n = table[n];
    }
    return Buffer.concat(out);
  };
  const regular = (n) => chain(n, fat, sector, count);
  const dir = regular(u32(b, 48));
  let root, work;
  for (let p = 0; p + 128 <= dir.length; p += 128) {
    const type = dir[p + 66];
    if (!type) continue;
    const len = u16(dir, p + 64);
    ok(len >= 2 && len <= 64 && len % 2 === 0);
    const name = dir.subarray(p, p + len - 2).toString("utf16le");
    const size = u32(dir, p + 120);
    ok(u32(dir, p + 124) === 0 && size <= 1048576);
    const entry = { start: u32(dir, p + 116), size };
    if (type === 5) {
      ok(!root);
      root = entry;
    }
    if (type === 2 && ["Workbook", "Book"].includes(name)) {
      ok(!work);
      work = entry;
    }
  }
  ok(root && work && work.size > 0);
  let stream;
  if (work.size < 4096) {
    ok(u32(b, 64) <= count);
    const mf = regular(u32(b, 60));
    ok(mf.length === u32(b, 64) * 512);
    const table = [];
    for (let p = 0; p < mf.length; p += 4) table.push(u32(mf, p));
    const mini = regular(root.start).subarray(0, root.size);
    stream = chain(
      work.start,
      table,
      (n) => {
        ok(n * 64 + 64 <= mini.length);
        return mini.subarray(n * 64, n * 64 + 64);
      },
      16384,
    );
  } else stream = regular(work.start);
  ok(stream.length >= work.size);
  stream = stream.subarray(0, work.size);
  const records = [];
  let p = 0;
  while (p + 4 <= stream.length) {
    const id = u16(stream, p),
      len = u16(stream, p + 2);
    ok(len <= 8224 && p + 4 + len <= stream.length && records.length < 32768);
    records.push({ id, p, data: stream.subarray(p + 4, p + 4 + len) });
    p += 4 + len;
    if (id === 0 && len === 0) break;
  }
  ok(
    records[0]?.id === 0x809 &&
      u16(records[0].data, 0) === 0x600 &&
      u16(records[0].data, 2) === 5,
  );
  const bounds = [],
    strings = [];
  let chars = 0;
  for (let i = 0; i < records.length; i++) {
    const { id, data: d } = records[i];
    ok(![0x2f, 0x6, 0x406, 0x206, 0x204, 0xd6, 0x207, 0xbd].includes(id));
    if (id === 0x85) {
      ok(d.length >= 8 && d[5] === 0 && bounds.length < 16);
      bounds.push(u32(d, 0));
    }
    if (id === 0xfc) {
      ok(!strings.length);
      const unique = u32(d, 4);
      ok(unique <= 8192 && u32(d, 0) <= 65536);
      const parts = [d.subarray(8)];
      while (records[i + 1]?.id === 0x3c) parts.push(records[++i].data);
      let part = 0,
        pos = 0;
      const byte = () => {
        while (pos === parts[part]?.length) {
          part++;
          pos = 0;
        }
        ok(part < parts.length);
        return parts[part][pos++];
      };
      const word = () => byte() | (byte() << 8);
      const long = () => word() + word() * 65536;
      for (let s = 0; s < unique; s++) {
        const n = word(),
          flags = byte();
        ok(n <= 16384 && (flags & ~13) === 0);
        let wide = !!(flags & 1);
        const runs = flags & 8 ? word() : 0,
          ext = flags & 4 ? long() : 0;
        ok(runs <= 4096 && ext <= 65536);
        chars += n;
        ok(chars <= 262144);
        let value = "";
        for (let c = 0; c < n; c++) {
          if (pos === parts[part]?.length) {
            part++;
            pos = 0;
            ok(part < parts.length);
            const f = byte();
            ok(f <= 1);
            wide = !!f;
          }
          const lo = byte();
          value += String.fromCharCode(wide ? lo | (byte() << 8) : lo);
        }
        for (let j = 0; j < runs * 4 + ext; j++) byte();
        strings.push(value);
      }
    }
  }
  ok(bounds.length > 0 && new Set(bounds).size === bounds.length);
  return bounds.map((offset) => {
    let i = records.findIndex((r) => r.p === offset);
    ok(i >= 0 && records[i].id === 0x809 && u16(records[i].data, 2) === 0x10);
    const rows = [],
      cells = new Set(),
      merges = [];
    let ended = false;
    for (i++; i < records.length; i++) {
      const { id, data: d } = records[i];
      if (id === 0xa) {
        ended = true;
        break;
      }
      ok(id !== 0x809);
      if (
        id === 0xfd ||
        id === 0x201 ||
        id === 0x203 ||
        id === 0x27e ||
        id === 0x205
      ) {
        const r = u16(d, 0),
          c = u16(d, 2);
        ok(r < 256 && c < 64 && !cells.has(r * 64 + c));
        cells.add(r * 64 + c);
        if (!rows[r]) rows[r] = [];
        if (id === 0xfd) {
          const s = u32(d, 6);
          ok(s < strings.length);
          rows[r][c] = strings[s];
        } else if (id !== 0x201) ok(false);
      }
      if (id === 0xe5) {
        const n = u16(d, 0);
        ok(n <= 256 && d.length === 2 + n * 8);
        for (let j = 0; j < n; j++) {
          const q = 2 + j * 8,
            a = u16(d, q),
            z = u16(d, q + 2),
            c = u16(d, q + 4),
            e = u16(d, q + 6);
          ok(a <= z && z < 256 && c <= e && e < 64);
          merges.push([a, z, c, e]);
        }
      }
    }
    ok(ended);
    return Array.from({ length: rows.length }, (_, r) =>
      Array.from({ length: 64 }, (_, c) => rows[r]?.[c] ?? ""),
    );
  });
}
