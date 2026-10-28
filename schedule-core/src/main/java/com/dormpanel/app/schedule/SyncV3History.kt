package com.dormpanel.app.schedule

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray.strings() = (0 until length()).map { getString(it) }
internal fun JSONObject.namesList(): List<String> = keys().asSequence().toList()
internal fun V3Ref.json() = JSONObject().put("role", role).put("type", type).put("epochId", epochId).put("epochNumber", epochNumber).put("objectId", objectId).put("hash", hash)
internal fun ref(o: V3Object, hash: String, role: Int) = V3Ref(role, o.type, o.epochId, o.epochNumber, o.objectId, hash)
internal fun refPath(dataset: String, r: V3Ref) = V3Paths.path(V3Object(r.type, dataset, r.objectId, r.objectId, r.epochId, r.epochNumber))
internal fun JSONObject.refs(): List<V3Ref> = getJSONArray("refs").let { a -> (0 until a.length()).map { n -> a.getJSONObject(n).let { V3Ref(it.getInt("role"), it.getInt("type"), it.getString("epochId"), it.getInt("epochNumber"), it.getString("objectId"), it.getString("hash")) } } }

/** Indexed queues: a listing's child witnesses and acknowledgement share one short transaction. */
class V3Discovery(private val journal: V3Journal, private val remote: V3ObjectStore, private val dataset: String) {
    private val meta = journal.meta
    private fun advance(progress: JSONObject?) { if (progress != null) { meta.put("control", "discoveryLane", JSONObject().put("value", progress.getInt("lane"))); progress.optJSONObject("audit")?.let { meta.put("control", "scopeAudit", it) } } }
    fun observe(scope: String, children: List<String>, progress: JSONObject? = null) {
        require(children.size <= 256 && children.distinct().size == children.size)
        children.forEach { p -> require(p.startsWith(scope) && '/' !in p.removePrefix(scope).removeSuffix("/")); if (p.endsWith('/')) require(V3Paths.describe(p).datasetId == dataset) else V3Paths.file(p) }
        meta.atomic {
            children.forEach { p ->
                if (p.endsWith('/')) { if (meta.get("scope", p) == null) { meta.put("scope", p, JSONObject().put("path", p)); meta.put("scan", p, JSONObject().put("path", p)) } }
                else if (meta.get("observed", p) == null) { meta.put("observed", p, JSONObject().put("path", p)); meta.put("todo", p, JSONObject().put("path", p)) }
            }
            meta.put("listing", scope, JSONObject().put("children", JSONArray(children)).put("round", journal.roundId).put("endpoint", (remote as? V3WebDavStore)?.endpointIdentity())); meta.remove("scan", scope); meta.remove("error", scope)
            if (meta.page("scan", 0, 1).isEmpty()) meta.put("control", "initialComplete", JSONObject().put("value", true))
            advance(progress)
        }
    }
    fun scan(limit: Int = 8) {
        require(limit in 0..32); val root = V3Paths.dataset(dataset)
        if (meta.get("scope", root) == null) meta.atomic { meta.put("scope", root, JSONObject().put("path", root)); meta.put("scan", root, JSONObject().put("path", root)) }
        repeat(limit) {
            val pending = meta.page("scan", 0, 1).firstOrNull(); var cursor = meta.get("control", "scopeAudit")?.optLong("seq") ?: 0
            var ceiling = meta.get("control", "scopeAudit")?.optLong("ceiling", meta.lastSeq("scope")) ?: meta.lastSeq("scope")
            var audit = meta.page("scope", cursor, 1, ceiling).firstOrNull()
            if (audit == null) { cursor = 0; ceiling = meta.lastSeq("scope"); audit = meta.page("scope", cursor, 1, ceiling).firstOrNull() }
            val lane = meta.get("control", "discoveryLane")?.getInt("value") ?: 0
            val task = (if (pending != null && lane == 0) pending else audit) ?: return
            val progress = JSONObject().put("lane", 1 - lane).put("audit", if (task === audit) JSONObject().put("seq", task.seq).put("ceiling", ceiling) else null)
            try { observe(task.key, remote.list(task.key), progress) }
            catch (e: Exception) { if (!e.message.orEmpty().contains("budget", true)) meta.atomic { meta.put("error", task.key, JSONObject().put("reason", e.message)); advance(progress) }; throw e }
        }
    }
}

/** Lazy certificates, incremental causal frontier and resumable paged ancestry proofs. */
class V3History(private val journal: V3Journal, internal val cache: V3IndexedCache, private val remote: V3ObjectStore,
    private val descriptorPath: String, private val descriptorHash: String, internal val budget: V3Budget, private val sharedRegistry: SharedTypeRegistry = SharedTypes.registry()) {
    internal fun withRegistry(registry: SharedTypeRegistry): V3History = if (registry === sharedRegistry) this else V3History(journal,cache,remote,descriptorPath,descriptorHash,budget,registry).open()
    var dataset = ""; private set
    private val meta = journal.meta
    private val domainValidated = linkedSetOf<String>()
    private fun rememberDomain(path: String) { if(domainValidated.size>=256)domainValidated.remove(domainValidated.first());domainValidated.add(path) }
    private val loaded = linkedMapOf<String, JSONObject>()
    init { meta.budget = budget }
    fun optionalNode(path: String): JSONObject? {
        loaded[path]?.let { return it }; val row = meta.get("node", path) ?: return null
        require(meta.get("identity", row.getString("objectId"))?.getString("path") == path) { "v3 metadata identity index corrupt" }
        if (loaded.size >= 128) loaded.remove(loaded.keys.first()); loaded[path] = row; return row
    }
    fun node(path: String) = requireNotNull(optionalNode(path)) { "v3 unverified ancestor" }
    fun objectMetadata(path: String): V3Object = node(path).let { o -> V3Object(o.getInt("type"), dataset, o.getString("objectId"), o.getString("writerId"), o.getString("epochId"), o.getInt("epochNumber"), o.getString("descriptorHash"), o.refs(), format = o.optInt("format", 3)) }
    fun reference(path: String, role: Int) = ref(objectMetadata(path), node(path).getString("hash"), role)
    private fun edges(o: JSONObject) = o.refs().filter { it.role in 1..3 }.map { refPath(dataset, it) }
    fun open(): V3History {
        dataset = requireNotNull(V3Paths.describe(descriptorPath.substringBeforeLast('/') + "/").datasetId)
        val old = optionalNode(descriptorPath)
        if (old != null) require(old.getInt("type") == 1 && old.getString("hash") == descriptorHash) { "v3 descriptor identity" }
        else { val bytes = requireNotNull(cache.load(descriptorPath, remote, descriptorHash)); require(SyncV3Codec.hash(bytes) == descriptorHash); val o = V3Paths.match(descriptorPath, bytes); require(o.type == 1); record(descriptorPath, bytes, o) }; return this
    }
    private fun record(path: String, bytes: ByteArray, o: V3Object) {
        o.state?.let { sharedRegistry.validate(it.sharedRecords.orEmpty()) }; rememberDomain(path)
        cache.pin(path, "referenced-history")
        val causal = o.refs.filter { it.role in 1..3 }; val primary = causal.firstOrNull()?.let { node(refPath(dataset, it)) }
        val jumps = mutableListOf<String>(); if (primary != null) jumps += refPath(dataset, causal.first())
        var n = 1
        while (jumps.size >= n) { val a = node(jumps[n - 1]).getJSONArray("jumps"); if (a.length() < n) break; jumps += a.getString(n - 1); n++ }
        val m = JSONObject().put("format", o.format).put("type", o.type).put("datasetId", o.datasetId).put("objectId", o.objectId).put("writerId", o.writerId).put("epochId", o.epochId).put("epochNumber", o.epochNumber).put("descriptorHash", o.descriptorHash)
            .put("refs", JSONArray(o.refs.map { it.json() })).put("hash", SyncV3Codec.hash(bytes)).put("size", bytes.size)
            .put("root", if (o.type == 2) path else primary?.optString("root")).put("depth", if (primary == null) 0 else primary.getLong("depth") + 1).put("jumps", JSONArray(jumps))
        o.state?.let { m.put("businessHash", SyncV3Codec.businessHash(it,sharedRegistry)) }
        meta.atomic {
            val identity = meta.get("identity", o.objectId); val old = meta.get("node", path)
            require(identity == null || identity.getString("path") == path) { "v3 duplicate objectId" }; require(old == null || old.getString("hash") == m.getString("hash")) { "v3 identity changed" }
            if (old == null && o.type != 1) require(epochCount(o.epochId) < 256) { "v3 epoch capacity" }
            if (o.type == 2) {
                val epoch = meta.get("epoch", o.epochId); require(epoch == null || epoch.getString("root") == path) { "v3 multiple genesis" }
                if (o.epochNumber == 1) { val initial = meta.get("control", "initial"); require(initial == null || initial.getString("path") == path) { "v3 multiple initial genesis" }; meta.put("control", "initial", JSONObject().put("path", path)) }
                meta.put("epoch", o.epochId, epoch ?: JSONObject().put("root", path).put("number", o.epochNumber).put("active", o.epochNumber == 1).put("frontierIndexed", true))
            }
            meta.put("identity", o.objectId, JSONObject().put("path", path).put("hash", m.getString("hash"))); meta.put("node", path, m); meta.remove("todo", path); meta.remove("error", path)
            if (o.type != 1) { meta.put("members:" + o.epochId, path, JSONObject().put("path", path)); if (meta.get("epoch", o.epochId)?.getBoolean("active") == true) meta.put("activate", path, JSONObject().put("path", path)) }
            if (o.type in 2..3 && meta.get("epoch", o.epochId)?.optBoolean("frontierIndexed") == true) {
                val ns = "epochFrontier:" + o.epochId; if (meta.get("$ns:covered", path) == null) meta.put(ns, path, JSONObject().put("path", path))
                o.refs.filter { it.role == 1 }.forEach { r -> val p = refPath(dataset, r); meta.put("$ns:covered", p, JSONObject().put("by", path)); meta.remove(ns, p) }
            }
        }; loaded.remove(path)
    }
    fun drainActivation() {
        repeat(256) {
            val task = meta.page("activate", 0, 1).firstOrNull() ?: return; budget.work("activation", 256); tick(); val o = node(task.key)
            meta.atomic {
                if (o.getInt("type") in 2..3) {
                    if (meta.get("covered", task.key) == null) meta.put("frontier", task.key, JSONObject().put("path", task.key))
                    edges(o).forEach { p -> meta.put("covered", p, JSONObject().put("by", task.key)); meta.remove("frontier", p) }
                } else if (o.getInt("type") == 4) {
                    val target = o.refs().single { it.role == 5 }; val epoch = requireNotNull(meta.get("epoch", target.epochId))
                    if (!epoch.getBoolean("active")) { epoch.put("active", true); meta.put("epoch", target.epochId, epoch); meta.page("members:" + target.epochId, 0, 256).forEach { meta.put("activate", it.key, JSONObject().put("path", it.key)) } }
                }; meta.remove("activate", task.key)
            }
        }; check(meta.page("activate", 0, 1).isEmpty()) { "v3 activation budget; progress saved" }
    }
    fun tips(): List<String> { drainActivation(); return meta.page("frontier", 0, 65).map { it.key }.sorted().also { require(it.size <= 64) { "v3 tips budget" } } }
    fun activeEpochs() = tips().map { node(it).getString("epochId") }.distinct()
    fun epochCount(epoch: String) = meta.count("members:$epoch")
    fun epochTips(epoch: String): List<String> {
        val e = requireNotNull(meta.get("epoch", epoch)); val ns = "epochFrontier:$epoch"
        if (!e.optBoolean("frontierIndexed")) meta.atomic {
            meta.page("members:$epoch", 0, 256).forEach { row -> val o = node(row.key); tick(); if (o.getInt("type") in 2..3) { if (meta.get("$ns:covered", row.key) == null) meta.put(ns, row.key, JSONObject().put("path", row.key)); o.refs().filter { it.role == 1 }.forEach { r -> val p = refPath(dataset, r); meta.put("$ns:covered", p, JSONObject().put("by", row.key)); meta.remove(ns, p) } } }
            e.put("frontierIndexed", true); meta.put("epoch", epoch, e)
        }
        return meta.page(ns, 0, 65).map { it.key }.sorted().also { require(it.isNotEmpty() && it.size <= 64) { "v3 source frontier budget" } }
    }
    fun auditObjects(limit: Int = 1) {
        require(limit in 0..32)
        repeat(limit) {
            budget.work("object audit", 32); var cursor = meta.get("control", "objectAudit")?.optLong("seq") ?: 0
            var ceiling = meta.get("control", "objectAudit")?.optLong("ceiling", meta.lastSeq("node")) ?: meta.lastSeq("node")
            var candidate = meta.page("node", cursor, 1, ceiling).firstOrNull()
            if (candidate == null) { cursor = 0; ceiling = meta.lastSeq("node"); candidate = meta.page("node", cursor, 1, ceiling).firstOrNull() }
            val row = candidate ?: return
            val progress = JSONObject().put("seq", row.seq).put("ceiling", ceiling)
            try { val raw = requireNotNull(remote.read(row.key)) { "v3 observed object missing or changed" }; require(SyncV3Codec.hash(raw) == row.value.getString("hash")) { "v3 observed object missing or changed" }; cache.put(row.key, raw); meta.atomic { meta.remove("error", row.key); meta.put("evidence", row.key, JSONObject().put("hash", row.value.getString("hash")).put("round", budget.roundId).put("endpoint", (remote as? V3WebDavStore)?.endpointIdentity())); meta.put("control", "objectAudit", progress) } }
            catch (e: Exception) { if (!e.message.orEmpty().contains("budget", true)) meta.atomic { meta.put("error", row.key, JSONObject().put("reason", e.message).put("expectedHash", row.value.getString("hash"))); meta.put("control", "objectAudit", progress) }; throw e }
        }
    }
    fun value(path: String, expectedHash: String? = null): V3Object { val bytes = requireNotNull(cache.load(path, remote, expectedHash ?: optionalNode(path)?.getString("hash"))) { "v3 missing ancestor" }; val o = V3Paths.match(path, bytes,sharedRegistry); require(optionalNode(path)?.getString("hash")?.let { it == SyncV3Codec.hash(bytes) } != false) { "v3 identity changed" }; o.state?.let { sharedRegistry.validate(it.sharedRecords.orEmpty()) }; rememberDomain(path); return o }
    private fun tick() = budget.work("ancestry", 32768)
    private fun primaryAncestor(a: String, b: String): Boolean {
        val left = node(a); val right = node(b); if (left.getLong("depth") > right.getLong("depth")) return false
        var distance = right.getLong("depth") - left.getLong("depth"); var path = b; var bit = 0
        while (distance > 0) { tick(); if (distance % 2L == 1L) { val jumps = node(path).getJSONArray("jumps"); if (jumps.length() <= bit) return false; path = jumps.getString(bit) }; distance /= 2; bit++ }; return path == a
    }
    fun isAncestor(a: String, b: String) = primaryAncestor(a, b) || a in commonCandidates(listOf(a, b))
    fun frontier(paths: List<String>): List<String> { val unique = paths.distinct(); return unique.filter { p -> unique.none { other -> p != other && isAncestor(p, other) } }.sorted() }
    fun common(paths: List<String>): String = commonCandidates(paths).first()
    private fun commonCandidates(input: List<String>): List<String> {
        val paths = input.distinct().sorted(); require(paths.isNotEmpty() && paths.size <= 128)
        paths.firstOrNull { p -> paths.all { primaryAncestor(p, it) } }?.let { return listOf(it) }
        val token = SyncV3Codec.hash(JSONArray(paths).toString().toByteArray(Charsets.UTF_8)); val ns = "proof:$token"; val queue = "$ns:queue"; val seen = "$ns:seen"; val candidates = "$ns:tips"; val covered = "$ns:covered"
        var control = meta.get("proof", token)
        if (control == null) meta.atomic { control = JSONObject().put("paths", JSONArray(paths)).put("done", false); meta.put("proof", token, control!!); paths.forEachIndexed { bit, path -> meta.put(queue, "$bit:$path", JSONObject().put("path", path).put("bit", bit)) } }
        if (control!!.getBoolean("done")) return control!!.getJSONArray("result").strings()
        while (true) {
            val task = meta.page(queue, 0, 1).firstOrNull() ?: break; budget.work("proof", 256); tick(); val path = task.value.getString("path"); val bit = task.value.getInt("bit"); val o = node(path)
            meta.atomic {
                val oldBits = meta.get(seen, path)?.getJSONArray("bits"); val bits = if (oldBits == null) mutableListOf() else (0 until oldBits.length()).map { oldBits.getInt(it) }.toMutableList()
                if (bit !in bits) {
                    bits += bit; meta.put(seen, path, JSONObject().put("bits", JSONArray(bits)))
                    if (bits.size == paths.size) { if (meta.get(covered, path) == null) meta.put(candidates, path, JSONObject().put("path", path)); edges(o).forEach { parent -> meta.put(covered, parent, JSONObject().put("value", true)); meta.remove(candidates, parent) } }
                    edges(o).forEach { parent -> val a = meta.get(seen, parent)?.getJSONArray("bits"); if (a == null || (0 until a.length()).none { a.getInt(it) == bit }) meta.put(queue, "$bit:$parent", JSONObject().put("path", parent).put("bit", bit)) }
                }; meta.remove(queue, task.key)
            }
        }
        val result = meta.page(candidates, 0, 65).map { it.key }.sorted(); require(result.isNotEmpty() && result.size <= 64 && result.map { node(it).getString("businessHash") }.distinct().size == 1) { "v3 unknown/ambiguous common baseline" }
        meta.atomic { meta.put("proof", token, JSONObject().put("paths", JSONArray(paths)).put("done", true).put("result", JSONArray(result))) }; return result
    }
    fun merged(paths: List<String>): ScheduleState {
        val tips = frontier(paths).sortedBy { node(it).getString("objectId") }; require(tips.isNotEmpty()); if (tips.size == 1) return requireNotNull(value(tips.single()).state)
        val base = requireNotNull(value(common(tips)).state); val bytes = tips.sumOf { node(it).getLong("size") } * tips.size; require(bytes <= 64L * 1024 * 1024) { "v3 merge budget" }; budget.work("merge bytes", 64 * 1024 * 1024, bytes.toInt())
        val states = tips.map { requireNotNull(value(it).state) }
        states.indices.forEach { i -> (i + 1 until states.size).forEach { j -> require(ScheduleMerge.merge(base, states[i], states[j], sharedRegistry=sharedRegistry).conflicts.isEmpty()) { "v3 unresolved branch conflict" } } }
        return states.drop(1).fold(states.first()) { a, b -> val result = ScheduleMerge.merge(base, a, b, sharedRegistry=sharedRegistry); require(result.conflicts.isEmpty()) { "v3 unresolved branch conflict" }; result.state }
    }
    fun verify(path: String, expectedHash: String? = null) {
        if (optionalNode(path) != null) { if(path !in domainValidated)value(path,expectedHash); require(expectedHash == null || node(path).getString("hash") == expectedHash) { "v3 reference identity changed" }; meta.atomic { meta.remove("todo", path) }; return }
        var control = meta.get("control", "walk")
        if (control == null) meta.atomic { control = JSONObject().put("root", path).put("depth", 1).put("id", java.util.UUID.randomUUID().toString()); meta.put("control", "walk", control!!); meta.put("stack:" + control!!.getString("id"), "0", JSONObject().put("path", path).put("expectedHash", expectedHash)) }
        val walk = control!!; require(walk.has("id")) { "v3 unsupported private validation cursor" }; val suffix = walk.getString("id"); val stack = "stack:$suffix"; val visiting = "visiting:$suffix"
        try {
            while (walk.getLong("depth") > 0) {
                tick(); val key = (walk.getLong("depth") - 1).toString(); val frame = requireNotNull(meta.get(stack, key)) { "v3 missing validation frame" }; val p = frame.getString("path")
                if (optionalNode(p) != null) { meta.atomic { meta.remove(stack, key); walk.put("depth", walk.getLong("depth") - 1); meta.put("control", "walk", walk) }; continue }
                val expected = if (frame.has("expectedHash") && !frame.isNull("expectedHash")) frame.getString("expectedHash") else null
                var fresh: V3Object? = null
                if (!frame.has("refs")) { budget.work("expansion", 256); fresh = value(p, expected); frame.put("refs", JSONArray(fresh.refs.map { it.json() })); meta.atomic { meta.put(stack, key, frame); meta.put(visiting, p, JSONObject().put("depth", walk.getLong("depth"))) } }
                val child = frame.refs().map { refPath(dataset, it) }.firstOrNull { optionalNode(it) == null }
                if (child != null) { require(meta.get(visiting, child) == null) { "v3 cycle" }; meta.atomic { meta.put(stack, walk.getLong("depth").toString(), JSONObject().put("path", child).put("expectedHash", frame.refs().first { refPath(dataset, it) == child }.hash)); walk.put("depth", walk.getLong("depth") + 1); meta.put("control", "walk", walk) } }
                else { verifyReady(p, expected, fresh); meta.atomic { meta.remove(visiting, p); meta.remove(stack, key); walk.put("depth", walk.getLong("depth") - 1); meta.put("control", "walk", walk) } }
            }; meta.atomic { meta.remove("control", "walk") }
        } catch (e: Exception) { meta.atomic { meta.put("error", walk.getString("root"), JSONObject().put("reason", e.message)); if (!Regex("budget|missing|unavailable|timed|Network|ENOENT", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())) { meta.put("failedWalk", walk.getString("id"), JSONObject(walk.toString()).put("reason", e.message)); meta.remove("control", "walk") } }; throw e }
        if (optionalNode(path) == null) verify(path, expectedHash)
        require(expectedHash == null || node(path).getString("hash") == expectedHash) { "v3 reference identity changed" }
    }
    private fun verifyReady(path: String, expectedHash: String?, fresh: V3Object? = null) {
        if (optionalNode(path) != null) { if(path !in domainValidated)value(path,expectedHash); return }; budget.work("validation", 256)
        val bytes = if (fresh != null) SyncV3Codec.encode(fresh,sharedRegistry) else requireNotNull(cache.load(path, remote, expectedHash)) { "v3 missing object" }; val o = fresh ?: V3Paths.match(path, bytes,sharedRegistry); o.state?.let { sharedRegistry.validate(it.sharedRecords.orEmpty()) }; require(o.datasetId == dataset && o.descriptorHash == descriptorHash && o.type != 1 && o.format == node(descriptorPath).optInt("format", 3)) { "v3 descriptor identity" }
        o.refs.forEach { r -> val p = refPath(dataset, r); val target = objectMetadata(p); require(node(p).getString("hash") == r.hash && target.type == r.type && target.epochId == r.epochId && target.epochNumber == r.epochNumber && target.objectId == r.objectId && target.descriptorHash == descriptorHash) { "v3 reference identity" } }
        if (o.type == 2 && o.epochNumber > 1) { val source = o.refs.map { refPath(dataset, it) }; require(frontier(source).size == source.size) { "v3 checkpoint redundant frontier" }; require(ScheduleMerge.same(requireNotNull(o.state), merged(source))) { "v3 checkpoint extra edit" } }
        if (o.type == 3) { val parents = o.refs.filter { it.role == 1 }.map { node(refPath(dataset, it)) }; require(parents.all { it.optString("root").isNotEmpty() && it.getString("root") == parents.first().getString("root") }) { "v3 parent common root" }; if (o.refs.any { it.role == 3 }) require(refPath(dataset, o.refs.single { it.role == 4 }) in commonCandidates(o.refs.filter { it.role == 1 || it.role == 3 }.map { refPath(dataset, it) })) { "v3 unproven BASELINE" } }
        if (o.type == 4) require(node(refPath(dataset, o.refs.single { it.role == 5 })).refs().toSet() == o.refs.filter { it.role == 2 }.toSet()) { "v3 transition frontier mismatch" }
        record(path, bytes, o)
    }
}
