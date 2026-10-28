package com.dormpanel.app.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Sync core. The production host owns activation, permissions and its single Writer. */
class V3Engine(private val journal: V3Journal, private val writerId: String, private val sharedRegistry: SharedTypeRegistry = SharedTypes.registry()) {
    init { require(!journal.snapshot().has("writerId") || journal.snapshot().getString("writerId") == writerId) { "v3 writer identity changed" } }
    init {
        fun validate(value: Any?) { when(value) {
            is JSONObject -> value.keys().forEach { validate(value.get(it)) }
            is JSONArray -> (0 until value.length()).forEach { validate(value.get(it)) }
            is String -> if(value.startsWith("RFBWMw")) obj(value)
        } }
        validate(journal.snapshot())
    }
    private val network = journal.networkGate
    private fun configure(remote: V3ObjectStore, cache: V3IndexedCache, budget: V3Budget) {
        require(cache.budget === budget && (remote !is V3WebDavStore || remote.budget === budget)) { "v3 round must share one budget" }
        journal.meta.assertWritable(); journal.roundId = budget.roundId
        journal.meta.budget = budget
        journal.prepareReceipt = { p ->
            val b = journal.snapshot().getJSONObject("binding"); val path = p.getString("path"); val witness = cache.witness(path)
            require(witness == null || witness.getString("hash") == p.getString("hash")) { "v3 pending cache identity changed" }
            if (witness == null || !cache.blob(path).isFile) cache.put(path, Base64.getDecoder().decode(p.getString("bytes")))
            val h = V3History(journal, cache, remote, b.getString("descriptorPath"), b.getString("descriptorHash"), budget, sharedRegistry).open(); h.verify(p.getString("path"), p.getString("hash")); h.drainActivation()
        }
    }
    private fun merge(base: ScheduleState, local: ScheduleState, remote: ScheduleState, choice: ScheduleMerge.Choice? = null,
        forceConflicts: Set<String> = emptySet()) = ScheduleMerge.merge(base,local,remote,choice,forceConflicts,sharedRegistry=sharedRegistry)
    private fun id() = UUID.randomUUID().toString()
    private fun raw(o: V3Object) = Base64.getEncoder().encodeToString(SyncV3Codec.encode(o,sharedRegistry))
    private fun obj(raw: String) = SyncV3Codec.decode(Base64.getDecoder().decode(raw),sharedRegistry).also { it.state?.let { state -> sharedRegistry.validate(state.sharedRecords.orEmpty()) } }
    // Private state envelope, never a published object or an implicit v2 migration.
    private fun state(s: ScheduleState): String = raw(V3Object(2, STATE_ID, STATE_ID, STATE_ID, STATE_ID, 1, STATE_HASH, state = s))
    private fun state(s: JSONObject, key: String) = requireNotNull(obj(s.getString(key)).state)
    fun local(): ScheduleState = journal.snapshot().let { if (it.has("local")) state(it, "local") else ScheduleState() }
    fun bind(descriptor: V3Object, hash: String, local: ScheduleState? = null) = bindWithCandidates(descriptor, hash, local, null)
    private fun bindWithCandidates(descriptor: V3Object, hash: String, local: ScheduleState?, initialization: JSONArray?) {
        require(descriptor.type == 1 && SyncV3Codec.hash(SyncV3Codec.encode(descriptor)) == hash)
        journal.localTransaction { s -> require(!s.has("binding") && (!s.has("writerId") || s.getString("writerId") == writerId)); val existing = if (s.has("local")) state(s, "local") else ScheduleState(); val seed = local ?: if (descriptor.format == 4) existing.copy(sharedRecords = emptyList()) else existing
            require(ScheduleMerge.same(existing, ScheduleState()) || ScheduleMerge.same(existing, seed)) { "v3 binding must preserve unbound local edits" }
            s.put("writerId", writerId).put("binding", JSONObject().put("datasetId", descriptor.datasetId).put("descriptorPath", V3Paths.path(descriptor)).put("descriptorHash", hash).put("format", descriptor.format)); s.put("local", state(seed)).put("unbasedLocal", !ScheduleMerge.same(seed, ScheduleState())).put("revision", s.optLong("revision"))
            if (initialization != null) s.put("initialization", initialization)
        }
    }
    private fun upgradeEvidence(capture: ByteArray): String = journal.meta.localOperation {
        val s=journal.snapshot(); val binding=s.getJSONObject("binding")
        require(s.optJSONObject("pending")?.optString("phase") in listOf(null,"RECEIPTED") && !s.has("conflict") && !s.has("rotation") && (s.optJSONArray("initialization")?.length() ?: 0)==0) { "Source recovery incomplete" }
        require(listOf("todo","error","scan").all { journal.meta.count(it)==0L } && journal.meta.get("control","walk")==null) { "Source history validation incomplete" }
        val settled=s.getJSONObject("settled")
        require(settled.getLong("revision")==s.getLong("revision") && settled.getJSONArray("tips").strings()==journal.meta.page("frontier",0,65).map { it.key }.sorted())
        val baseline=Base64.getDecoder().decode(s.getString("baseline"));val o=SyncV3Codec.decode(capture)
        require(o.format==3 && o.state!=null && SyncV3Codec.businessHash(requireNotNull(SyncV3Codec.decode(baseline).state))==SyncV3Codec.businessHash(o.state) && o.datasetId==binding.getString("datasetId") && o.descriptorHash==binding.getString("descriptorHash"))
        require(SyncV3Codec.businessHash(state(s,"local"))==SyncV3Codec.businessHash(o.state)) { "Unpublished source edits" }
        val node=requireNotNull(journal.meta.get("node",s.getString("baselinePath")))
        require(node.getString("hash")==SyncV3Codec.hash(capture))
        binding.getString("datasetId")+"/"+s.getString("baselinePath")+"/"+node.getString("hash")+"/"+s.getLong("revision")
    }
    /** Target must be a separate unbound Store; source capture is never modified. */
    fun previewSharedUpgrade(sourceCapture: ByteArray, sourceEngine: V3Engine): JSONObject {
        require(sourceEngine !== this); val evidence=sourceEngine.upgradeEvidence(sourceCapture)
        val source = SyncV3Codec.decode(sourceCapture); require(source.format == 3 && source.state != null)
        require(!journal.snapshot().has("binding")) { "Upgrade requires a separate unbound target" }
        val descriptor = V3Object(1, id(), id(), writerId, format = 4)
        val initial = source.state.copy(sharedRecords = emptyList())
        val genesis = V3Object(2, descriptor.datasetId, id(), writerId, id(), 1, SyncV3Codec.hash(SyncV3Codec.encode(descriptor)), state = initial)
        val preview = JSONObject().put("id", id()).put("status", "PREVIEW").put("sourceHash", SyncV3Codec.hash(sourceCapture)).put("sourceEvidence",evidence)
            .put("targetDatasetId", descriptor.datasetId).put("extensions", 0).put("events", initial.events.size).put("courses", initial.entries.size)
            .put("sources", initial.sources.size).put("imported", initial.imported.size).put("overrides", initial.classOverrides.size)
            .put("days", initial.dayAdjustments.size).put("profiles", initial.profiles.size).put("customProfiles", initial.customProfileKeys.size)
            .put("descriptor", raw(descriptor)).put("genesis", raw(genesis)).put("sourceCapture", Base64.getEncoder().encodeToString(sourceCapture))
            .put("warning", "旧 Writer 仍可能写入源；源数据集、历史、绑定及未发布修改保留，本操作不会证明源已停写或自动切换绑定")
        journal.meta.localOperation { journal.meta.atomic { journal.meta.put("sharedUpgrade", preview.getString("id"), preview) } }
        return JSONObject(preview.toString())
    }
    fun cancelSharedUpgrade(token: String) = journal.meta.localOperation {
        val p = requireNotNull(journal.meta.get("sharedUpgrade", token)); require(p.getString("status") == "PREVIEW")
        p.put("status", "CANCELLED"); journal.meta.atomic { journal.meta.put("sharedUpgrade", token, p) }
    }
    fun confirmSharedUpgrade(token: String, currentSourceCapture: ByteArray, remote: V3PublicationStore, sourceEngine: V3Engine): JSONObject {
        require(sourceEngine !== this)
        val p = journal.meta.localOperation { requireNotNull(journal.meta.get("sharedUpgrade", token)) }
        require(p.getString("status") in listOf("PREVIEW", "PUBLISHING"))
        require(p.getString("sourceHash") == SyncV3Codec.hash(currentSourceCapture)) { "Source changed; preview again" }
        require(p.getString("sourceEvidence")==sourceEngine.upgradeEvidence(currentSourceCapture)) { "Source evidence changed" }
        val descriptor = obj(p.getString("descriptor")); val genesis = obj(p.getString("genesis"))
        if (!journal.snapshot().has("binding")) bindWithCandidates(descriptor, genesis.descriptorHash, genesis.state,
            JSONArray(listOf(p.getString("descriptor"), p.getString("genesis"))))
        require(journal.snapshot().getJSONObject("binding").getString("datasetId") == descriptor.datasetId)
        p.put("status", "PUBLISHING"); journal.meta.localOperation { journal.meta.atomic { journal.meta.put("sharedUpgrade", token, p) } }
        val result = initialize(remote)
        require(p.getString("sourceEvidence")==sourceEngine.upgradeEvidence(currentSourceCapture))
        p.put("status", "TARGET_READBACK_CONFIRMED"); journal.meta.localOperation { journal.meta.atomic { journal.meta.put("sharedUpgrade", token, p) } }
        return result
    }
    fun setCourseColor(sourceId: String?, seriesId: String, rgb: Int?) = edit { SharedCourseColors.change(it, sourceId, seriesId, rgb) }
    fun edit(local: ScheduleState) = edit { local }
    /** Host DAO edits run inside the journal's own transaction, before encoding/commit.
     * Do not wrap this in an outer transaction: the journal publishes memory after COMMIT. */
    fun edit(change: (ScheduleState) -> ScheduleState) {
        journal.localTransaction { s ->
            require(!s.has("writerId") || s.getString("writerId") == writerId)
            val before = if (s.has("local")) state(s, "local") else ScheduleState()
            before.sharedRecords?.let { sharedRegistry.writable(it) }
            val after = change(before)
            require(before.sharedRecords.orEmpty().filterNot(sharedRegistry::supported).toSet() == after.sharedRecords.orEmpty().filterNot(sharedRegistry::supported).toSet()) { "Unknown optional records are read-only" }
            require((before.sharedRecords != null) == (after.sharedRecords != null)) { "Use explicit isolated upgrade" }
            after.sharedRecords?.let { sharedRegistry.writable(it) }
            val encoded = state(after)
            s.put("writerId", writerId).put("local", encoded).put("revision", s.optLong("revision") + 1); s.remove("decision")
        }
    }
    fun initialize(remote: V3PublicationStore, initial: ScheduleState = local()): JSONObject = synchronized(network) {
        require(remote is V3ObjectStore)
        if (journal.snapshot().optJSONArray("initialization")?.length()?.let { it > 0 } == true) {
            val budget = (remote as? V3WebDavStore)?.budget ?: V3Budget(); configure(remote, V3IndexedCache(journal.directory, budget), budget)
            resumeInitialization(remote); return@synchronized journal.snapshot().getJSONObject("binding")
        }
        val descriptor = V3Object(1, id(), id(), writerId, format = if (initial.sharedRecords != null) 4 else 3)
        val genesis = V3Object(2, descriptor.datasetId, id(), writerId, id(), 1, SyncV3Codec.hash(SyncV3Codec.encode(descriptor)), state = initial)
        // A crash can expose neither a binding without candidates nor candidates without binding.
        bindWithCandidates(descriptor, genesis.descriptorHash, initial, JSONArray(listOf(raw(descriptor), raw(genesis))))
        val budget = (remote as? V3WebDavStore)?.budget ?: V3Budget(); configure(remote, V3IndexedCache(journal.directory, budget), budget)
        resumeInitialization(remote); journal.snapshot().getJSONObject("binding")
    }
    private fun publish(remote: V3PublicationStore, o: V3Object, capture: JSONObject) {
        require(o.format == journal.snapshot().getJSONObject("binding").optInt("format", 3)) { "Dataset format mismatch" }
        o.state?.sharedRecords?.let { sharedRegistry.writable(it) }
        val path = V3Paths.path(o); remote.prepare(path.substringBeforeLast('/') + "/")
        V3Publisher(journal, remote).stage(path, SyncV3Codec.encode(o,sharedRegistry), capture); resumePublication(remote)
    }
    private fun resumePublication(remote: V3PublicationStore) {
        val publisher = V3Publisher(journal, remote); val phase = publisher.resume()
        check(phase in listOf("READBACK_CONFIRMED", "RECEIPTED")) { "v3 UNCERTAIN: read-only recovery required" }; if (phase == "RECEIPTED") return
        publisher.receipt { s, c ->
            val p = s.getJSONObject("pending"); val o = obj(p.getString("bytes"))
            when (c.getString("kind")) {
                "commit" -> {
                    val replay = merge(state(c, "local"), state(s, "local"), requireNotNull(o.state))
                    if (replay.conflicts.isEmpty()) s.put("local", state(replay.state))
                    else s.put("conflict", conflict(s, state(c, "local"), state(s, "local"), o.state, replay.conflicts, listOf(p.getString("path"))).put("receipt", true))
                    s.put("baseline", state(o.state)).put("baselinePath", p.getString("path")).put("baselineHash", p.getString("hash")).put("convergenceRotations", 0); s.remove("decision")
                    if (replay.conflicts.isEmpty() && c.getLong("revision") == s.getLong("revision")) s.put("settled", JSONObject().put("tips", JSONArray(journal.meta.page("frontier", 0, 65).map { it.key }.sorted())).put("revision", s.getLong("revision")))
                }
                "initial-genesis" -> s.put("baseline", state(requireNotNull(o.state))).put("baselinePath", p.getString("path")).put("baselineHash", p.getString("hash")).put("activeEpoch", o.epochId)
                "transition" -> s.put("activeEpoch", c.getString("targetEpoch"))
            }
            s.put("receipt", JSONObject().put("path", p.getString("path")).put("hash", p.getString("hash")).put("confirmation", p.optJSONObject("confirmation")))
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
    fun rotate(inputHistory: V3History, remote: V3PublicationStore, sourceEpoch: String, tips: List<String>) = synchronized(network) {
        val history=inputHistory.withRegistry(sharedRegistry)
        require(remote is V3ObjectStore); configure(remote, history.cache, history.budget)
        require(sourceEpoch in history.activeEpochs() && tips.sorted() == history.epochTips(sourceEpoch)) { "v3 stale/incomplete rotation frontier" }
        val source = history.objectMetadata(tips.first()); val refs = tips.map { history.reference(it, 2) }
        val genesis = V3Object(2, source.datasetId, id(), writerId, id(), source.epochNumber + 1, source.descriptorHash, refs, history.merged(tips))
        val transition = V3Object(4, source.datasetId, id(), writerId, sourceEpoch, source.epochNumber, source.descriptorHash,
            refs + ref(genesis, SyncV3Codec.hash(SyncV3Codec.encode(genesis,sharedRegistry)), 5), format = source.format)
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
            val next = conflict(s, base, local, remote, conflicts, paths).put("branches", JSONArray(branches.map { JSONObject().put("objectId", it.commitId).put("state", state(it.state)) }))
            s.optJSONObject("conflict")?.let { if (it.getString("id") != next.getString("id")) journal.meta.put("conflictHistory", it.getString("id"), it) }; s.put("conflict", next) }
    }
    fun resolve(id: String, choice: ScheduleMerge.Choice) {
        journal.localTransaction { s ->
            val c = s.getJSONObject("conflict"); require(c.getString("id") == id && c.getLong("revision") == s.getLong("revision")) { "v3 stale conflict decision" }
            val a = c.getJSONArray("conflicts"); val keys = (0 until a.length()).flatMap { a.getJSONObject(it).getJSONArray("keys").strings() }.toSet()
            val resolved = merge(state(c, "base"), state(c, "local"), state(c, "remote"), choice, keys)
            val replay = merge(state(c, "base"), state(c, "capturedLocal"), resolved.state, ScheduleMerge.Choice.REMOTE, keys)
            require(replay.conflicts.all { it.keys.all { key -> key in keys } }) { "v3 new local conflict requires separate decision" }
            journal.meta.put("decision", id, JSONObject().put("choice", choice.name).put("input", c))
            s.put("local", state(replay.state)).put("revision", s.getLong("revision") + 1); s.remove("conflict")
            s.put("decision", JSONObject().put("id", id).put("choice", choice.name).put("remotePaths", c.getJSONArray("paths")).put("input", c))
        }
    }
    fun round(remote: V3ObjectStore, publication: V3PublicationStore, cache: V3IndexedCache, budget: V3Budget, discoverySteps: Int = 8): String = synchronized(network) {
        try {
            configure(remote, cache, budget)
            resumeInitialization(publication)
            journal.snapshot().optJSONObject("pending")?.let { if (it.getString("phase") != "RECEIPTED") resumePublication(publication) }
            resumeRotation(publication)
            val binding = journal.snapshot().getJSONObject("binding"); val dataset = binding.getString("datasetId")
            val discovery = V3Discovery(journal, remote, dataset); discovery.scan(discoverySteps)
            val history = V3History(journal, cache, remote, binding.getString("descriptorPath"), binding.getString("descriptorHash"), budget, sharedRegistry).open()
            journal.snapshot().optJSONObject("receipt")?.let { history.verify(it.getString("path")); require(history.node(it.getString("path")).getString("hash") == it.getString("hash")) { "v3 receipt identity changed" } }
            journal.snapshot().let { s -> s.optString("baselinePath").takeIf { it.isNotEmpty() }?.let { history.verify(it); require(!s.has("baselineHash") || history.node(it).getString("hash") == s.getString("baselineHash")) { "v3 baseline identity changed" } } }
            history.activeEpochs().takeLast(8).forEach { epoch ->
                val listed = remote.list(V3Paths.epoch(dataset, epoch))
                discovery.observe(V3Paths.epoch(dataset, epoch), listed)
            }
            repeat(32) { journal.meta.page("todo", 0, 1).firstOrNull()?.let { history.verify(it.key) } }
            history.drainActivation()
            check(journal.meta.get("control", "initialComplete") != null && journal.meta.page("todo", 0, 1).isEmpty()) { "v3 initial discovery/validation incomplete" }
            check(journal.meta.page("error", 0, 1).isEmpty()) { "v3 unresolved observed history error" }
            val epochs = history.activeEpochs(); require(epochs.isNotEmpty()) { "v3 no activated epoch" }
            val tips = history.tips(); val current = journal.snapshot()
            current.optJSONObject("conflict")?.takeIf { it.optBoolean("receipt") }?.let { old ->
                if (old.getLong("revision") != current.getLong("revision")) journal.transaction { s ->
                    s.optJSONObject("conflict")?.takeIf { it.optBoolean("receipt") }?.let { c ->
                        val a = c.getJSONArray("conflicts"); val keys = (0 until a.length()).flatMap { a.getJSONObject(it).getJSONArray("keys").strings() }.toSet()
                        val result = merge(state(c, "base"), state(s, "local"), state(c, "remote"), forceConflicts = keys)
                        journal.meta.put("conflictHistory", c.getString("id"), c); s.put("conflict", conflict(s, state(c, "base"), state(s, "local"), state(c, "remote"), result.conflicts, c.getJSONArray("paths").strings()).put("receipt", true))
                    }
                }; return@synchronized "CONFLICT"
            }
            current.optJSONObject("settled")?.let { if (it.getLong("revision") == current.getLong("revision") && it.getJSONArray("tips").strings() == tips) return@synchronized "OBSERVED" }
            var carrier = current.optString("activeEpoch")
            if (carrier !in epochs) carrier = history.objectMetadata(tips.first()).epochId
            val highest = tips.maxOf { history.objectMetadata(it).epochNumber }
            if (tips.none { history.objectMetadata(it).let { o -> o.epochId == carrier && o.epochNumber == highest } }) carrier = history.objectMetadata(tips.first { history.objectMetadata(it).epochNumber == highest }).epochId
            val parents = tips.filter { history.objectMetadata(it).epochId == carrier }; val other = tips - parents.toSet(); require(parents.isNotEmpty())
            val decisionPaths = current.optJSONObject("decision")?.getJSONArray("remotePaths")?.strings()
            val decision = !decisionPaths.isNullOrEmpty() && tips.all { p -> decisionPaths.any { old -> history.node(old).getString("businessHash") == history.node(p).getString("businessHash") && history.isAncestor(old, p) } } && decisionPaths.all { old -> tips.any { p -> history.node(old).getString("businessHash") == history.node(p).getString("businessHash") && history.isAncestor(old, p) } }
            val remoteState = try { history.merged(tips) } catch (e: IllegalArgumentException) {
                if (!e.message.orEmpty().contains("conflict")) throw e
                if (decision) local() else {
                    budget.work("merge bytes", 64 * 1024 * 1024, (tips.sumOf { history.node(it).getLong("size") } * tips.size).also { require(it <= 64L * 1024 * 1024) { "v3 merge budget" } }.toInt())
                    val baseObject = history.value(history.common(tips)); val localBaseObject = history.value(history.common(tips + listOfNotNull(current.optString("baselinePath").takeIf { it.isNotEmpty() })))
                    fun branch(o: V3Object) = ScheduleCommit(o.datasetId, o.objectId, o.writerId, emptyList(), requireNotNull(o.state))
                    val branches = tips.map { branch(history.value(it)) }.sortedBy { it.commitId }; val plan = ScheduleV2Plan(branch(baseObject), branch(localBaseObject), branches, sharedRegistry)
                    val result = plan.merge(state(current, "local")); saveConflict(requireNotNull(localBaseObject.state), state(current, "local"), plan.remoteState(), result.conflicts, tips, current.getLong("revision"), branches); return@synchronized "CONFLICT"
                }
            }
            val basePath = history.common(tips + listOfNotNull(current.optString("baselinePath").takeIf { it.isNotEmpty() }))
            require(current.has("baselinePath") || !current.optBoolean("unbasedLocal") && current.getLong("revision") == 0L) { "v3 local state has no proven baseline" }
            val local = if (current.has("baselinePath")) state(current, "local") else remoteState
            val base = requireNotNull(history.value(basePath).state); val result = if (decision) ScheduleMerge.Result(local, emptyList()) else merge(base, local, remoteState)
            if (result.conflicts.isNotEmpty()) { saveConflict(base, state(current, "local"), remoteState, result.conflicts, tips, current.getLong("revision")); return@synchronized "CONFLICT" }
            current.optJSONObject("conflict")?.let { old -> journal.transaction { s -> if (s.getLong("revision") == current.getLong("revision") && s.optJSONObject("conflict")?.getString("id") == old.getString("id")) { journal.meta.put("conflictHistory", old.getString("id"), old); s.remove("conflict") } } }
            val count = history.epochCount(carrier); check(count < 256) { "v3 epoch capacity" }
            val resolveSourceFirst = decision && parents.size > 1
            val equivalent = tips.map { history.node(it).getString("businessHash") }.distinct().size == 1
            if (other.isNotEmpty() && equivalent && ScheduleMerge.same(result.state, remoteState)) {
                journal.transaction { s -> if (s.getLong("revision") == current.getLong("revision")) s.put("local", state(result.state)).put("baseline", state(remoteState)).put("baselinePath", parents.first()).put("baselineHash", history.node(parents.first()).getString("hash")).put("equivalentFrontiers", JSONArray(tips)) }
                return@synchronized "EQUIVALENT_FRONTIERS"
            }
            if (!resolveSourceFirst && (other.any { history.objectMetadata(it).epochNumber >= history.objectMetadata(parents.first()).epochNumber } || count >= 160)) {
                check(count < 255) { "v3 transition capacity" }
                if (other.isNotEmpty()) journal.transaction { s -> check(s.optInt("convergenceRotations") < 4) { "v3 convergence retry budget; candidates retained" }; s.put("convergenceRotations", s.optInt("convergenceRotations") + 1) }
                rotate(history, publication, carrier, parents); return@synchronized "ROTATED"
            }
            if (ScheduleMerge.same(result.state, remoteState) && equivalent && other.isEmpty()) {
                journal.transaction { s -> if (s.getLong("revision") == current.getLong("revision")) s.put("local", state(result.state)).put("baseline", state(remoteState)).put("baselinePath", tips.first()).put("baselineHash", history.node(tips.first()).getString("hash")).put("settled", JSONObject().put("tips", JSONArray(tips)).put("revision", s.getLong("revision"))) }; return@synchronized "OBSERVED"
            }
            check(count < 192 || resolveSourceFirst && count < 255) { "v3 resolved source needs room for commit and transition" }; val refs = parents.map { history.reference(it, 1) }.toMutableList()
            val eligibleLate = other.filter { history.objectMetadata(it).epochNumber < history.objectMetadata(parents.first()).epochNumber }
            if (eligibleLate.isNotEmpty()) {
                val common = history.common(parents + eligibleLate); require(history.objectMetadata(common).epochNumber < history.objectMetadata(parents.first()).epochNumber) { "v3 bridge baseline generation" }
                refs += eligibleLate.map { history.reference(it, 3) }; refs += history.reference(common, 4)
            }
            val source = history.objectMetadata(parents.first()); val o = V3Object(3, dataset, id(), writerId, carrier, source.epochNumber, binding.getString("descriptorHash"), refs, result.state)
            publish(publication, o, JSONObject().put("kind", "commit").put("local", current.getString("local")).put("revision", current.getLong("revision")).put("decision", current.optJSONObject("decision"))); if (journal.snapshot().optJSONObject("conflict")?.optBoolean("receipt") == true) "CONFLICT" else "PUBLISHED"
        } catch (e: Exception) { journal.transaction { it.put("error", e.message.orEmpty().take(1024)) }; throw e }
    }
    companion object {
        private const val STATE_ID = "00000000-0000-0000-0000-000000000001"
        private val STATE_HASH = "1".repeat(64)
    }
}
