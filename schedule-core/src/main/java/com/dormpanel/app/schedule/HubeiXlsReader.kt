package com.dormpanel.app.schedule

/** Bounded OLE v3 / BIFF8 text-only decoder. Never evaluates formulas or opens objects. */
object HubeiXlsReader {
    const val PARSER_ID = "hubei-personal-xls-v1"

    fun signature(b: ByteArray) =
        b.size >= 8 &&
            b.take(8) == listOf(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1).map { it.toByte() }

    private fun check(v: Boolean) = importCheck(v, "Unsupported or damaged BIFF8 XLS.")

    private fun u16(b: ByteArray, p: Int): Int {
        check(p >= 0 && p <= b.size - 2)
        return (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)
    }

    private fun u32(b: ByteArray, p: Int): Int = u16(b, p) or (u16(b, p + 2) shl 16)

    private data class Record(val id: Int, val offset: Int, val data: ByteArray)

    fun sheets(b: ByteArray): List<List<List<String>>> {
        val deadline = System.nanoTime() + 2_000_000_000L
        fun check(v: Boolean) =
            importCheck(
                v && System.nanoTime() <= deadline,
                "Unsupported, damaged or resource-limited BIFF8 XLS.",
            )
        check(signature(b) && b.size in 512..1048576 && b.size % 512 == 0)
        check(u16(b, 26) == 3 && u16(b, 28) == 65534 && u16(b, 30) == 9 && u16(b, 32) == 6)
        val count = b.size / 512 - 1
        fun sector(n: Int): ByteArray {
            check(n in 0 until count)
            return b.copyOfRange((n + 1) * 512, (n + 2) * 512)
        }
        check(u32(b, 44) in 1..109 && u32(b, 72) == 0 && u32(b, 56) == 4096)
        val fat = mutableListOf<Int>()
        val fatIds = mutableSetOf<Int>()
        repeat(u32(b, 44)) { i ->
            val n = u32(b, 76 + 4 * i)
            check(fatIds.add(n))
            val s = sector(n)
            repeat(128) { fat += u32(s, it * 4) }
        }
        fun chain(start: Int, table: List<Int>, max: Int, read: (Int) -> ByteArray): ByteArray {
            var n = start
            val seen = mutableSetOf<Int>()
            val out = java.io.ByteArrayOutputStream()
            while (n != -2) {
                check(n in table.indices && seen.add(n) && seen.size <= max)
                out.write(read(n))
                n = table[n]
            }
            return out.toByteArray()
        }
        fun regular(n: Int) = chain(n, fat, count, ::sector)
        val dir = regular(u32(b, 48))
        var root: Pair<Int, Int>? = null
        var work: Pair<Int, Int>? = null
        for (p in 0 until dir.size - 127 step 128) {
            val type = dir[p + 66].toInt()
            if (type == 0) continue
            val len = u16(dir, p + 64)
            check(len in 2..64 && len % 2 == 0)
            val name = String(dir, p, len - 2, Charsets.UTF_16LE)
            val size = u32(dir, p + 120)
            check(u32(dir, p + 124) == 0 && size in 0..1048576)
            val entry = u32(dir, p + 116) to size
            if (type == 5) {
                check(root == null)
                root = entry
            }
            if (type == 2 && name in listOf("Workbook", "Book")) {
                check(work == null)
                work = entry
            }
        }
        check(root != null && work != null && work!!.second > 0)
        val w = work!!
        val r = root!!
        var stream =
            if (w.second < 4096) {
                check(u32(b, 64) in 1..count)
                val mf = regular(u32(b, 60))
                check(mf.size == u32(b, 64) * 512)
                val table = (mf.indices step 4).map { u32(mf, it) }
                val mini = regular(r.first).take(r.second).toByteArray()
                chain(w.first, table, 16384) { n ->
                    check(n >= 0 && n * 64 + 64 <= mini.size)
                    mini.copyOfRange(n * 64, n * 64 + 64)
                }
            } else regular(w.first)
        check(stream.size >= w.second)
        stream = stream.copyOf(w.second)
        val records = mutableListOf<Record>()
        var p = 0
        while (p + 4 <= stream.size) {
            val id = u16(stream, p)
            val len = u16(stream, p + 2)
            check(len <= 8224 && p + 4 + len <= stream.size && records.size < 32768)
            records += Record(id, p, stream.copyOfRange(p + 4, p + 4 + len))
            p += 4 + len
            if (id == 0 && len == 0) break
        }
        check(
            records.firstOrNull()?.id == 0x809 &&
                u16(records[0].data, 0) == 0x600 &&
                u16(records[0].data, 2) == 5
        )
        val bounds = mutableListOf<Int>()
        val strings = mutableListOf<String>()
        var chars = 0
        var i = 0
        while (i < records.size) {
            val rec = records[i]
            val d = rec.data
            check(rec.id !in listOf(0x2f, 0x6, 0x406, 0x206, 0x204, 0xd6, 0x207, 0xbd))
            if (rec.id == 0x85) {
                check(d.size >= 8 && d[5].toInt() == 0 && bounds.size < 16)
                bounds += u32(d, 0)
            }
            if (rec.id == 0xfc) {
                check(strings.isEmpty())
                val unique = u32(d, 4)
                check(unique in 0..8192 && u32(d, 0) in 0..65536)
                val parts = mutableListOf(d.copyOfRange(8, d.size))
                while (i + 1 < records.size && records[i + 1].id == 0x3c) parts += records[++i].data
                var part = 0
                var pos = 0
                fun byte(): Int {
                    while (part < parts.size && pos == parts[part].size) {
                        part++
                        pos = 0
                    }
                    check(part < parts.size)
                    return parts[part][pos++].toInt() and 255
                }
                fun word() = byte() or (byte() shl 8)
                fun long() = word() or (word() shl 16)
                repeat(unique) {
                    val n = word()
                    val flags = byte()
                    check(n <= 16384 && flags and 13.inv() == 0)
                    var wide = flags and 1 != 0
                    val runs = if (flags and 8 != 0) word() else 0
                    val ext = if (flags and 4 != 0) long() else 0
                    check(runs <= 4096 && ext in 0..65536)
                    chars += n
                    check(chars <= 262144)
                    val value = StringBuilder()
                    repeat(n) {
                        if (part < parts.size && pos == parts[part].size) {
                            part++
                            pos = 0
                            check(part < parts.size)
                            val f = byte()
                            check(f <= 1)
                            wide = f == 1
                        }
                        val lo = byte()
                        value.append((if (wide) lo or (byte() shl 8) else lo).toChar())
                    }
                    repeat(runs * 4 + ext) { byte() }
                    strings += value.toString()
                }
            }
            i++
        }
        check(bounds.isNotEmpty() && bounds.distinct().size == bounds.size)
        return bounds.map { offset ->
            var index = records.indexOfFirst { it.offset == offset }
            check(index >= 0 && records[index].id == 0x809 && u16(records[index].data, 2) == 0x10)
            val rows = mutableMapOf<Int, MutableMap<Int, String>>()
            val cells = mutableSetOf<Int>()
            var ended = false
            index++
            while (index < records.size) {
                val rec = records[index++]
                val d = rec.data
                if (rec.id == 0xa) {
                    ended = true
                    break
                }
                check(rec.id != 0x809)
                if (rec.id in listOf(0xfd, 0x201, 0x203, 0x27e, 0x205)) {
                    val row = u16(d, 0)
                    val col = u16(d, 2)
                    check(row < 256 && col < 64 && cells.add(row * 64 + col))
                    if (rec.id == 0xfd) {
                        val s = u32(d, 6)
                        check(s in strings.indices)
                        rows.getOrPut(row) { mutableMapOf() }[col] = strings[s]
                    } else check(rec.id == 0x201)
                }
                if (rec.id == 0xe5) {
                    val n = u16(d, 0)
                    check(n <= 256 && d.size == 2 + n * 8)
                    repeat(n) {
                        val q = 2 + it * 8
                        val a = u16(d, q)
                        val z = u16(d, q + 2)
                        val c = u16(d, q + 4)
                        val e = u16(d, q + 6)
                        check(a <= z && z < 256 && c <= e && e < 64)
                    }
                }
            }
            check(ended)
            List((rows.keys.maxOrNull() ?: -1) + 1) { row ->
                List(64) { col -> rows[row]?.get(col).orEmpty() }
            }
        }
    }

    fun parse(bytes: ByteArray): HubeiCsvStructure {
        val candidates =
            sheets(bytes).filter { rows ->
                HubeiCsvTimetableParser.looksLike(rows.flatten().joinToString("\n"))
            }
        check(candidates.size == 1)
        return HubeiCsvTimetableParser.parseRows(candidates.single()).copy(parserId = PARSER_ID)
    }
}
