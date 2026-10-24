package com.dormpanel.app.schedule

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray.strings() = (0 until length()).map { getString(it) }
internal fun JSONObject.namesList(): List<String> = keys().asSequence().toList()
internal fun V3Ref.json() = JSONObject().put("role", role).put("type", type).put("epochId", epochId).put("epochNumber", epochNumber).put("objectId", objectId).put("hash", hash)
internal fun ref(o: V3Object, hash: String, role: Int) = V3Ref(role, o.type, o.epochId, o.epochNumber, o.objectId, hash)
internal fun refPath(dataset: String, r: V3Ref) = V3Paths.path(V3Object(r.type, dataset, r.objectId, r.objectId, r.epochId, r.epochNumber))
internal fun JSONObject.refs(): List<V3Ref> = getJSONArray("refs").let { a -> (0 until a.length()).map { n -> a.getJSONObject(n).let { V3Ref(it.getInt("role"), it.getInt("type"), it.getString("epochId"), it.getInt("epochNumber"), it.getString("objectId"), it.getString("hash")) } } }

/** Persistent prefix queue and fair audit cursor. A validated listing and its children commit together. */
class V3Discovery(private val journal: V3Journal, private val remote: V3ObjectStore, private val dataset: String) {
    fun scan(limit: Int = 8) {
        require(limit in 0..32)
        journal.transaction { s -> if (!s.has("discovery")) s.put("discovery", JSONObject().put("scopes", JSONArray(listOf(V3Paths.dataset(dataset))))
            .put("queue", JSONArray(listOf(V3Paths.dataset(dataset)))).put("observed", JSONArray()).put("audit", 0).put("failures", JSONObject())) }
        var working = journal.snapshot().getJSONObject("discovery")
        try { repeat(limit) {
            val d = working; val initial = d.getJSONArray("queue").length() > 0
            val scope = if (initial) d.getJSONArray("queue").getString(0) else d.getJSONArray("scopes").getString(d.getInt("audit") % d.getJSONArray("scopes").length())
            val children = try { remote.list(scope) } catch (e: Exception) {
                working.getJSONObject("failures").put(scope, e.message.orEmpty().take(512)); throw e
            }
            require(children.size <= 256 && children.distinct().size == children.size)
            run {
                val next = JSONObject(working.toString()); val scopes = next.getJSONArray("scopes").strings().toMutableList()
                val queue = next.getJSONArray("queue").strings().toMutableList(); val observed = next.getJSONArray("observed").strings().toMutableSet()
                children.forEach { child ->
                    require(child.startsWith(scope) && '/' !in child.removePrefix(scope).removeSuffix("/"))
                    if (child.endsWith('/')) { require(V3Paths.describe(child).datasetId == dataset); if (child !in scopes) { scopes += child; queue += child } }
                    else { V3Paths.file(child); observed += child }
                }
                require(scopes.size <= 16384 && observed.size <= 65536) { "v3 discovery metadata budget" }
                if (initial) queue.removeAt(0) else next.put("audit", (next.getInt("audit") + 1) % scopes.size)
                next.put("scopes", JSONArray(scopes)).put("queue", JSONArray(queue)).put("observed", JSONArray(observed)); next.getJSONObject("failures").remove(scope)
                if (queue.isEmpty()) next.put("initialComplete", true)
                working = next
            }
        } } finally { journal.transaction { it.put("discovery", working) } }
    }
}

/** Previously validated metadata is distinct from endpoint/round observation evidence. */
class V3History(private val journal: V3Journal, private val cache: V3IndexedCache, private val remote: V3ObjectStore,
    private val descriptorPath: String, private val descriptorHash: String, private val budget: V3Budget) {
    var dataset: String = ""; private set
    private var metadata = JSONObject()
    private val visiting = mutableSetOf<String>(); private var newObjects = 0; private var expanded = 0; private var work = 0
    fun paths() = metadata.namesList()
    fun node(path: String) = metadata.getJSONObject(path)
    fun objectMetadata(path: String): V3Object = node(path).let { o -> V3Object(o.getInt("type"), dataset, o.getString("objectId"), o.getString("writerId"), o.getString("epochId"), o.getInt("epochNumber"), o.getString("descriptorHash"), o.refs()) }
    fun reference(path: String, role: Int) = ref(objectMetadata(path), node(path).getString("hash"), role)
    private fun edges(path: String) = node(path).refs().filter { it.role in 1..3 }.map { refPath(dataset, it) }
    private fun certificate(m: JSONObject): String {
        val fields = listOf(m.getInt("type"), m.getString("datasetId"), m.getString("objectId"), m.getString("writerId"), m.getString("epochId"), m.getInt("epochNumber"), m.getString("descriptorHash"), m.getString("hash"), m.getInt("size"), m.optString("businessHash", "-"))
        val refs = m.refs().map { listOf(it.role, it.type, it.epochId, it.epochNumber, it.objectId, it.hash).joinToString("|") }
        return SyncV3Codec.hash((fields + refs).joinToString("\n").toByteArray(Charsets.US_ASCII))
    }
    fun open(): V3History {
        journal.transaction { if (!it.has("history")) it.put("history", JSONObject().put("nodes", JSONObject()).put("ids", JSONObject()).put("roots", JSONObject()).put("anomalies", JSONObject())) }
        metadata = journal.snapshot().getJSONObject("history").getJSONObject("nodes")
        budget.charge(metadata.toString().toByteArray(Charsets.UTF_8).size.toLong(), 0)
        val ids = journal.snapshot().getJSONObject("history").getJSONObject("ids")
        paths().forEach { p -> val m = node(p); require(m.getString("certificate") == certificate(m)) { "v3 metadata certificate corrupt" }; require(ids.getString(m.getString("objectId")) == p) { "v3 metadata identity index corrupt" } }
        val bytes = requireNotNull(cache.load(descriptorPath, remote)) { "v3 descriptor missing" }; require(SyncV3Codec.hash(bytes) == descriptorHash)
        val o = V3Paths.match(descriptorPath, bytes); require(o.type == 1); dataset = o.datasetId; record(descriptorPath, bytes, o); return this
    }
    private fun record(path: String, bytes: ByteArray, o: V3Object) {
        val m = JSONObject().put("type", o.type).put("datasetId", o.datasetId).put("objectId", o.objectId).put("writerId", o.writerId)
            .put("epochId", o.epochId).put("epochNumber", o.epochNumber).put("descriptorHash", o.descriptorHash)
            .put("refs", JSONArray(o.refs.map { it.json() })).put("hash", SyncV3Codec.hash(bytes)).put("size", bytes.size)
        o.state?.let { m.put("businessHash", SyncV3Codec.businessHash(it)) }
        m.put("certificate", certificate(m))
        journal.transaction { s ->
            val h = s.getJSONObject("history"); val ids = h.getJSONObject("ids"); val nodes = h.getJSONObject("nodes"); val roots = h.getJSONObject("roots")
            require(!ids.has(o.objectId) || ids.getString(o.objectId) == path) { "v3 duplicate objectId" }
            require(!nodes.has(path) || nodes.getJSONObject(path).getString("hash") == m.getString("hash")) { "v3 identity changed" }
            if (o.type != 1 && !nodes.has(path)) require(nodes.namesList().count { nodes.getJSONObject(it).getString("epochId") == o.epochId } < 256) { "v3 epoch capacity" }
            if (o.type == 2) {
                require(!roots.has(o.epochId) || roots.getString(o.epochId) == path) { "v3 multiple genesis" }
                if (o.epochNumber == 1) require(nodes.namesList().none { p -> nodes.getJSONObject(p).let { it.getInt("type") == 2 && it.getInt("epochNumber") == 1 && it.getString("objectId") != o.objectId } }) { "v3 multiple initial genesis" }
                roots.put(o.epochId, path)
            }
            ids.put(o.objectId, path); nodes.put(path, m); h.getJSONObject("anomalies").remove(path)
        }; metadata.put(path, m)
    }
    fun value(path: String): V3Object {
        val raw = requireNotNull(cache.load(path, remote)) { "v3 missing ancestor" }; val o = V3Paths.match(path, raw)
        require(!metadata.has(path) || node(path).getString("hash") == SyncV3Codec.hash(raw)) { "v3 identity changed" }; return o
    }
    private fun tick() { budget.charge(0, 0); check(++work <= 32768) { "v3 ancestry budget" } }
    fun ancestors(path: String): Set<String> {
        val seen = mutableSetOf<String>(); val queue = java.util.ArrayDeque<String>(); queue.add(path)
        while (!queue.isEmpty()) { tick(); val p = queue.removeLast(); if (seen.add(p)) { require(metadata.has(p)) { "v3 unverified ancestor" }; queue.addAll(edges(p)) } }; return seen
    }
    fun frontier(paths: List<String>): List<String> {
        val unique = paths.toSet(); val covered = mutableSetOf<String>()
        unique.forEach { p ->
            val queue = java.util.ArrayDeque<String>(); queue.addAll(edges(p)); val seen = mutableSetOf<String>()
            while (!queue.isEmpty()) { tick(); val a = queue.removeLast(); if (seen.add(a)) { if (a in unique) covered += a else { require(metadata.has(a)); queue.addAll(edges(a)) } } }
        }
        return (unique - covered).sorted().also { require(it.size <= 64) { "v3 tips budget" } }
    }
    fun common(paths: List<String>): String = commonCandidates(paths).first()
    private fun commonCandidates(paths: List<String>): List<String> {
        require(paths.isNotEmpty() && paths.size <= 64); val sets = paths.map(::ancestors); val shared = sets.first().filter { p -> sets.all { p in it } }.toSet()
        val covered = mutableSetOf<String>(); shared.forEach { p -> edges(p).forEach { tick(); if (it in shared) covered += it } }
        val maximal = (shared - covered).sorted()
        require(maximal.isNotEmpty() && maximal.map { node(it).getString("businessHash") }.distinct().size == 1) { "v3 unknown/ambiguous common baseline" }; return maximal
    }
    fun merged(paths: List<String>): ScheduleState {
        val tips = frontier(paths).sortedBy { node(it).getString("objectId") }; require(tips.isNotEmpty()); if (tips.size == 1) return requireNotNull(value(tips.single()).state)
        val base = requireNotNull(value(common(tips)).state); val bytes = tips.sumOf { node(it).getLong("size") } * tips.size
        require(bytes <= 64L * 1024 * 1024) { "v3 merge budget" }; budget.charge(bytes, 0)
        val states = tips.map { requireNotNull(value(it).state) }
        states.indices.forEach { i -> (i + 1 until states.size).forEach { j -> require(ScheduleMerge.merge(base, states[i], states[j]).conflicts.isEmpty()) { "v3 unresolved branch conflict" } } }
        return states.drop(1).fold(states.first()) { a, b -> val result = ScheduleMerge.merge(base, a, b); require(result.conflicts.isEmpty()) { "v3 unresolved branch conflict" }; result.state }
    }
    fun verify(path: String) {
        if (metadata.has(path)) return
        val walk = journal.snapshot().getJSONObject("history").optJSONObject("walk") ?: JSONObject().put("root", path).put("stack", JSONArray(listOf(JSONObject().put("path", path))))
        val stack = walk.getJSONArray("stack").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
        try {
            while (stack.isNotEmpty()) {
                budget.charge(0, 0); val frame = stack.last(); val p = frame.getString("path")
                if (metadata.has(p)) { stack.removeAt(stack.lastIndex); continue }
                if (!frame.has("refs")) { check(++expanded <= 256) { "v3 active graph expansion budget" }; frame.put("refs", JSONArray(value(p).refs.map { it.json() })) }
                val child = frame.refs().map { refPath(dataset, it) }.firstOrNull { !metadata.has(it) }
                if (child != null) { require(stack.none { it.getString("path") == child }) { "v3 cycle" }; stack += JSONObject().put("path", child) }
                else { verifyReady(p); stack.removeAt(stack.lastIndex) }
            }
        } catch (e: Exception) {
            journal.transaction { it.getJSONObject("history").getJSONObject("anomalies").put(walk.getString("root"), e.message.orEmpty().take(1024)) }
            if (!Regex("budget|missing|unavailable|timed|Network|ENOENT", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())) stack.clear()
            throw e
        } finally { journal.transaction { s -> val h = s.getJSONObject("history"); if (stack.isEmpty()) h.remove("walk") else h.put("walk", walk.put("stack", JSONArray(stack))) } }
        if (!metadata.has(path)) verify(path)
    }
    private fun verifyReady(path: String) {
        if (metadata.has(path)) return
        require(path !in visiting) { "v3 cycle" }; check(++newObjects <= 256) { "v3 active graph budget" }; visiting += path
        try {
            val bytes = requireNotNull(cache.load(path, remote)) { "v3 missing object" }; val o = V3Paths.match(path, bytes)
            require(o.datasetId == dataset && o.descriptorHash == descriptorHash && o.type != 1) { "v3 descriptor identity" }
            o.refs.forEach { r ->
                val p = refPath(dataset, r); require(metadata.has(p)) { "v3 unverified reference" }; val target = objectMetadata(p)
                require(node(p).getString("hash") == r.hash && target.type == r.type && target.epochId == r.epochId && target.epochNumber == r.epochNumber && target.objectId == r.objectId && target.descriptorHash == descriptorHash) { "v3 reference identity" }
            }
            if (o.type == 2 && o.epochNumber > 1) {
                val source = o.refs.map { refPath(dataset, it) }; require(frontier(source).size == source.size) { "v3 checkpoint redundant frontier" }
                require(ScheduleMerge.same(requireNotNull(o.state), merged(source))) { "v3 checkpoint extra edit" }
            }
            if (o.type == 3) {
                val parents = o.refs.filter { it.role == 1 }.map { refPath(dataset, it) }
                val roots = parents.map { p -> ancestors(p).filter { node(it).getInt("type") == 2 && node(it).getString("epochId") == o.epochId } }
                require(roots.all { it.size == 1 && it.single() == roots.first().single() }) { "v3 parent common root" }
                val late = o.refs.filter { it.role == 3 }
                if (late.isNotEmpty()) {
                    val branches = parents + late.map { refPath(dataset, it) }; val baseline = refPath(dataset, o.refs.single { it.role == 4 })
                    require(baseline in commonCandidates(branches)) { "v3 unproven BASELINE" }
                }
            }
            if (o.type == 4) {
                val target = value(refPath(dataset, o.refs.single { it.role == 5 }))
                require(target.refs.toSet() == o.refs.filter { it.role == 2 }.toSet()) { "v3 transition frontier mismatch" }
            }
            record(path, bytes, o)
        } catch (e: Exception) { journal.transaction { it.getJSONObject("history").getJSONObject("anomalies").put(path, e.message.orEmpty().take(1024)) }; throw e }
        finally { visiting -= path }
    }
    fun activeEpochs(): List<String> {
        val objects = paths().map(::objectMetadata); val activated = objects.filter { it.type == 2 && it.epochNumber == 1 }.map { it.epochId }.toMutableSet()
        val queue = java.util.ArrayDeque<String>(); queue.addAll(activated)
        while (!queue.isEmpty()) { val source = queue.removeFirst(); objects.filter { it.type == 4 && it.epochId == source }.forEach { val target = it.refs.single { r -> r.role == 5 }.epochId; if (activated.add(target)) queue.add(target) } }
        return activated.toList()
    }
}
