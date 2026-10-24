package com.dormpanel.app.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Explicit isolated engine. Production Room/FPK bindings never instantiate it in PR54. */
class V3Engine(private val journal: V3Journal, private val writerId: String) {
    init { require(!journal.snapshot().has("writerId") || journal.snapshot().getString("writerId") == writerId) { "v3 writer identity changed" } }
    private val network = journal.networkGate
    private fun id() = UUID.randomUUID().toString()
    private fun raw(o: V3Object) = Base64.getEncoder().encodeToString(SyncV3Codec.encode(o))
    private fun obj(raw: String) = SyncV3Codec.decode(Base64.getDecoder().decode(raw))
    // Private state envelope, never a published object or an implicit v2 migration.
    private fun state(s: ScheduleState): String = raw(V3Object(2, STATE_ID, STATE_ID, STATE_ID, STATE_ID, 1, STATE_HASH, state = s))
    private fun state(s: JSONObject, key: String) = requireNotNull(obj(s.getString(key)).state)
    fun local(): ScheduleState = state(journal.snapshot(), "local")
    fun bind(descriptor: V3Object, hash: String, local: ScheduleState = ScheduleState()) {
        require(descriptor.type == 1 && SyncV3Codec.hash(SyncV3Codec.encode(descriptor)) == hash)
        journal.transaction { s -> require(!s.has("binding")); s.put("writerId", writerId).put("binding", JSONObject().put("datasetId", descriptor.datasetId).put("descriptorPath", V3Paths.path(descriptor)).put("descriptorHash", hash)); s.put("local", state(local)).put("unbasedLocal", !ScheduleMerge.same(local, ScheduleState())).put("revision", 0) }
    }
    fun edit(local: ScheduleState) { val encoded = state(local); journal.transaction { it.put("local", encoded).put("revision", it.getLong("revision") + 1); it.remove("decision") } }
    fun initialize(remote: V3PublicationStore, initial: ScheduleState = ScheduleState()): JSONObject = synchronized(network) {
        val descriptor = V3Object(1, id(), id(), writerId)
        val genesis = V3Object(2, descriptor.datasetId, id(), writerId, id(), 1, SyncV3Codec.hash(SyncV3Codec.encode(descriptor)), state = initial)
        bind(descriptor, genesis.descriptorHash, initial)
        journal.transaction { it.put("initialization", JSONArray(listOf(raw(descriptor), raw(genesis)))) }
        resumeInitialization(remote); journal.snapshot().getJSONObject("binding")
    }
    private fun publish(remote: V3PublicationStore, o: V3Object, capture: JSONObject) {
        val path = V3Paths.path(o); remote.prepare(path.substringBeforeLast('/') + "/")
        V3Publisher(journal, remote).stage(path, SyncV3Codec.encode(o), capture); resumePublication(remote)
    }
    private fun resumePublication(remote: V3PublicationStore) {
        val publisher = V3Publisher(journal, remote); val phase = publisher.resume()
        check(phase in listOf("READBACK_CONFIRMED", "RECEIPTED")) { "v3 UNCERTAIN: read-only recovery required" }; if (phase == "RECEIPTED") return
        publisher.receipt { s, c ->
            val p = s.getJSONObject("pending"); val o = obj(p.getString("bytes"))
            when (c.getString("kind")) {
                "commit" -> {
                    val replay = ScheduleMerge.merge(state(c, "local"), state(s, "local"), requireNotNull(o.state))
                    if (replay.conflicts.isEmpty()) s.put("local", state(replay.state))
                    else s.put("conflict", conflict(s, state(c, "local"), state(s, "local"), o.state, replay.conflicts, listOf(p.getString("path"))))
                    s.put("baseline", state(o.state)).put("baselinePath", p.getString("path")).put("convergenceRotations", 0); s.remove("decision")
                }
                "initial-genesis" -> s.put("baseline", state(requireNotNull(o.state))).put("baselinePath", p.getString("path")).put("activeEpoch", o.epochId)
                "transition" -> s.put("activeEpoch", c.getString("targetEpoch"))
            }
            s.put("receipt", JSONObject().put("path", p.getString("path")).put("hash", p.getString("hash")))
        }
    }
    private fun resumeInitialization(remote: V3PublicationStore) {
        while (journal.snapshot().optJSONArray("initialization")?.length()?.let { it > 0 } == true) {
            val s = journal.snapshot(); val o = obj(s.getJSONArray("initialization").getString(0))
            if (s.has("pending") && s.getJSONObject("pending").getString("phase") != "RECEIPTED") resumePublication(remote)
            if (journal.snapshot().optJSONObject("receipt")?.optString("path") != V3Paths.path(o)) publish(remote, o, JSONObject().put("kind", if (o.type == 1) "descriptor" else "initial-genesis"))
            journal.transaction { val a = it.getJSONArray("initialization").strings().drop(1); it.put("initialization", JSONArray(a)) }
        }
    }
    fun rotate(history: V3History, remote: V3PublicationStore, sourceEpoch: String, tips: List<String>) = synchronized(network) {
        val source = history.objectMetadata(tips.first()); val refs = tips.map { history.reference(it, 2) }
        val genesis = V3Object(2, source.datasetId, id(), writerId, id(), source.epochNumber + 1, source.descriptorHash, refs, history.merged(tips))
        val transition = V3Object(4, source.datasetId, id(), writerId, sourceEpoch, source.epochNumber, source.descriptorHash,
            refs + ref(genesis, SyncV3Codec.hash(SyncV3Codec.encode(genesis)), 5))
        journal.transaction { it.put("rotation", JSONObject().put("genesis", raw(genesis)).put("transition", raw(transition)).put("phase", 0)) }
        resumeRotation(remote)
    }
    private fun resumeRotation(remote: V3PublicationStore) {
        val rotation = journal.snapshot().optJSONObject("rotation") ?: return
        for (step in rotation.getInt("phase")..1) {
            val o = obj(rotation.getString(if (step == 0) "genesis" else "transition")); val p = journal.snapshot().optJSONObject("pending")
            if (p != null && p.getString("phase") != "RECEIPTED") resumePublication(remote)
            if (journal.snapshot().optJSONObject("receipt")?.optString("path") != V3Paths.path(o)) publish(remote, o, JSONObject().put("kind", if (step == 0) "checkpoint" else "transition").put("targetEpoch", obj(rotation.getString("genesis")).epochId))
            journal.transaction { it.getJSONObject("rotation").put("phase", step + 1) }
        }
        journal.transaction { it.remove("rotation") }
    }
    private fun conflict(s: JSONObject, base: ScheduleState, local: ScheduleState, remote: ScheduleState, conflicts: List<ScheduleMerge.Conflict>, paths: List<String>): JSONObject =
        JSONObject().put("id", SyncV3Codec.hash((paths.joinToString("\n") + "\n" + s.getLong("revision")).toByteArray(Charsets.UTF_8)))
            .put("revision", s.getLong("revision")).put("base", state(base)).put("local", state(local)).put("remote", state(remote)).put("paths", JSONArray(paths))
            .put("capturedLocal", s.getString("local"))
            .put("conflicts", JSONArray(conflicts.map { JSONObject().put("key", it.key).put("keys", JSONArray(it.keys.toList())).put("description", it.description) }))
    private fun saveConflict(base: ScheduleState, local: ScheduleState, remote: ScheduleState, conflicts: List<ScheduleMerge.Conflict>, paths: List<String>, revision: Long, branches: List<ScheduleCommit> = emptyList()) {
        journal.transaction { s -> require(s.getLong("revision") == revision) { "v3 local edit changed; replan conflict" }
            s.put("conflict", conflict(s, base, local, remote, conflicts, paths).put("branches", JSONArray(branches.map { JSONObject().put("objectId", it.commitId).put("state", state(it.state)) }))) }
    }
    fun resolve(id: String, choice: ScheduleMerge.Choice) {
        journal.transaction { s ->
            val c = s.getJSONObject("conflict"); require(c.getString("id") == id && c.getLong("revision") == s.getLong("revision")) { "v3 stale conflict decision" }
            val a = c.getJSONArray("conflicts"); val keys = (0 until a.length()).flatMap { a.getJSONObject(it).getJSONArray("keys").strings() }.toSet()
            val resolved = ScheduleMerge.merge(state(c, "base"), state(c, "local"), state(c, "remote"), choice, keys)
            val replay = ScheduleMerge.merge(state(c, "base"), state(c, "capturedLocal"), resolved.state, ScheduleMerge.Choice.REMOTE, keys)
            require(replay.conflicts.all { it.keys.all { key -> key in keys } }) { "v3 new local conflict requires separate decision" }
            s.put("local", state(replay.state)).put("revision", s.getLong("revision") + 1); s.remove("conflict")
            s.put("decision", JSONObject().put("id", id).put("choice", choice.name).put("remotePaths", c.getJSONArray("paths")).put("input", c))
        }
    }
    fun round(remote: V3ObjectStore, publication: V3PublicationStore, cache: V3IndexedCache, budget: V3Budget, discoverySteps: Int = 8): String = synchronized(network) {
        try {
            resumeInitialization(publication)
            journal.snapshot().optJSONObject("pending")?.let { if (it.getString("phase") != "RECEIPTED") resumePublication(publication) }
            resumeRotation(publication)
            val binding = journal.snapshot().getJSONObject("binding"); val dataset = binding.getString("datasetId")
            V3Discovery(journal, remote, dataset).scan(discoverySteps)
            val history = V3History(journal, cache, remote, binding.getString("descriptorPath"), binding.getString("descriptorHash"), budget).open()
            journal.snapshot().optJSONObject("receipt")?.let { history.verify(it.getString("path")) }
            journal.snapshot().optString("baselinePath")?.takeIf { it.isNotEmpty() }?.let { history.verify(it) }
            history.activeEpochs().takeLast(8).forEach { epoch ->
                val listed = remote.list(V3Paths.epoch(dataset, epoch))
                journal.transaction { s -> val d = s.getJSONObject("discovery"); d.put("observed", JSONArray((d.getJSONArray("observed").strings() + listed).distinct())) }
            }
            val observed = journal.snapshot().getJSONObject("discovery").getJSONArray("observed").strings()
            if (observed.isNotEmpty()) {
                val cursor = journal.snapshot().optInt("objectAudit"); val path = observed[cursor % observed.size]
                cache.put(path, requireNotNull(remote.read(path)) { "v3 observed object missing" }); journal.transaction { it.put("objectAudit", (cursor + 1) % observed.size) }
            }
            observed.filter { it != binding.getString("descriptorPath") }.forEach(history::verify)
            check(journal.snapshot().getJSONObject("discovery").optBoolean("initialComplete")) { "v3 initial discovery incomplete" }
            val epochs = history.activeEpochs(); require(epochs.isNotEmpty()) { "v3 no activated epoch" }
            val all = history.paths().filter { history.objectMetadata(it).let { o -> o.type in 2..3 && o.epochId in epochs } }
            val tips = history.frontier(all); val current = journal.snapshot(); var carrier = current.optString("activeEpoch")
            if (carrier !in epochs) carrier = history.objectMetadata(tips.first()).epochId
            val highest = tips.maxOf { history.objectMetadata(it).epochNumber }
            if (tips.none { history.objectMetadata(it).let { o -> o.epochId == carrier && o.epochNumber == highest } }) carrier = history.objectMetadata(tips.first { history.objectMetadata(it).epochNumber == highest }).epochId
            val parents = tips.filter { history.objectMetadata(it).epochId == carrier }; val other = tips - parents.toSet(); require(parents.isNotEmpty())
            val decisionPaths = current.optJSONObject("decision")?.getJSONArray("remotePaths")?.strings()
            val decision = !decisionPaths.isNullOrEmpty() && tips.all { p -> decisionPaths.any { old -> history.node(old).getString("businessHash") == history.node(p).getString("businessHash") && old in history.ancestors(p) } } && decisionPaths.all { old -> tips.any { p -> history.node(old).getString("businessHash") == history.node(p).getString("businessHash") && old in history.ancestors(p) } }
            val remoteState = try { history.merged(tips) } catch (e: IllegalArgumentException) {
                if (!e.message.orEmpty().contains("conflict")) throw e
                if (decision) local() else {
                    budget.charge(tips.sumOf { history.node(it).getLong("size") } * tips.size, 0)
                    val baseObject = history.value(history.common(tips)); val localBaseObject = history.value(history.common(tips + listOfNotNull(current.optString("baselinePath").takeIf { it.isNotEmpty() })))
                    fun branch(o: V3Object) = ScheduleCommit(o.datasetId, o.objectId, o.writerId, emptyList(), requireNotNull(o.state))
                    val branches = tips.map { branch(history.value(it)) }.sortedBy { it.commitId }; val plan = ScheduleV2Plan(branch(baseObject), branch(localBaseObject), branches)
                    val result = plan.merge(state(current, "local")); saveConflict(requireNotNull(localBaseObject.state), state(current, "local"), plan.remoteState(), result.conflicts, tips, current.getLong("revision"), branches); return@synchronized "CONFLICT"
                }
            }
            val basePath = history.common(tips + listOfNotNull(current.optString("baselinePath").takeIf { it.isNotEmpty() }))
            require(current.has("baselinePath") || !current.optBoolean("unbasedLocal") && current.getLong("revision") == 0L) { "v3 local state has no proven baseline" }
            val local = if (current.has("baselinePath")) state(current, "local") else remoteState
            val base = requireNotNull(history.value(basePath).state); val result = if (decision) ScheduleMerge.Result(local, emptyList()) else ScheduleMerge.merge(base, local, remoteState)
            if (result.conflicts.isNotEmpty()) { saveConflict(base, state(current, "local"), remoteState, result.conflicts, tips, current.getLong("revision")); return@synchronized "CONFLICT" }
            val count = history.paths().count { history.objectMetadata(it).epochId == carrier }; check(count < 256) { "v3 epoch capacity" }
            val equivalent = tips.map { history.node(it).getString("businessHash") }.distinct().size == 1
            if (other.isNotEmpty() && equivalent && ScheduleMerge.same(result.state, remoteState)) {
                journal.transaction { s -> if (s.getLong("revision") == current.getLong("revision")) s.put("local", state(result.state)).put("baseline", state(remoteState)).put("baselinePath", parents.first()).put("equivalentFrontiers", JSONArray(tips)) }
                return@synchronized "EQUIVALENT_FRONTIERS"
            }
            if (other.any { history.objectMetadata(it).epochNumber >= history.objectMetadata(parents.first()).epochNumber } || count >= 160) {
                check(count < 255) { "v3 transition capacity" }
                if (other.isNotEmpty()) journal.transaction { s -> check(s.optInt("convergenceRotations") < 4) { "v3 convergence retry budget; candidates retained" }; s.put("convergenceRotations", s.optInt("convergenceRotations") + 1) }
                rotate(history, publication, carrier, parents); return@synchronized "ROTATED"
            }
            if (ScheduleMerge.same(result.state, remoteState) && equivalent && other.isEmpty()) {
                journal.transaction { s -> if (s.getLong("revision") == current.getLong("revision")) s.put("local", state(result.state)).put("baseline", state(remoteState)).put("baselinePath", tips.first()) }; return@synchronized "OBSERVED"
            }
            check(count < 192) { "v3 rotation must precede publication" }; val refs = parents.map { history.reference(it, 1) }.toMutableList()
            if (other.isNotEmpty()) {
                val common = history.common(tips); require(history.objectMetadata(common).epochNumber < history.objectMetadata(parents.first()).epochNumber) { "v3 bridge baseline generation" }
                refs += other.map { history.reference(it, 3) }; refs += history.reference(common, 4)
            }
            val source = history.objectMetadata(parents.first()); val o = V3Object(3, dataset, id(), writerId, carrier, source.epochNumber, binding.getString("descriptorHash"), refs, result.state)
            cache.put(V3Paths.path(o), SyncV3Codec.encode(o)); cache.pin(V3Paths.path(o), "pending")
            publish(publication, o, JSONObject().put("kind", "commit").put("local", current.getString("local")).put("revision", current.getLong("revision")).put("decision", current.optJSONObject("decision"))); "PUBLISHED"
        } catch (e: Exception) { journal.transaction { it.put("error", e.message.orEmpty().take(1024)) }; throw e }
    }
    companion object {
        private const val STATE_ID = "00000000-0000-0000-0000-000000000001"
        private val STATE_HASH = "1".repeat(64)
    }
}
