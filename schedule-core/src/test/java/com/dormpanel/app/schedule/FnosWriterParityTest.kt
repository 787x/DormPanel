package com.dormpanel.app.schedule

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Node 生成的完整 DPC2 输入/结果，由未修改的 Android 领域实现独立验证。 */
class FnosWriterParityTest {
    private fun bytes(path: String) = javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes() }
    private fun state(name: String) = ScheduleCommitCodec.decode(bytes("pr38/$name.dpc")).state
    private val manifest get() = JSONObject(bytes("pr38/manifest.json").toString(Charsets.UTF_8))
    @Test fun nodeMergeMatchesAndroidAssociatedUnitsAndBothChoices() {
        val cases = manifest.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i); val name = item.getString("name")
            val b = state("$name-base"); val l = state("$name-input-local"); val r = state("$name-input-remote")
            val expected = item.getJSONArray("conflicts").let { a -> (0 until a.length()).map { j ->
                a.getJSONArray(j).let { row -> (0 until row.length()).map { row.getString(it) }.toSet() }
            }.toSet() }
            assertEquals(name, expected, ScheduleMerge.merge(b,l,r).conflicts.map { it.keys }.toSet())
            for (choice in ScheduleMerge.Choice.entries) {
                val merged = ScheduleMerge.merge(b,l,r,choice).state
                assertTrue("$name $choice", ScheduleMerge.same(state("$name-expected-${choice.name.lowercase()}"),merged))
            }
        }
    }
    @Test fun nodeIcsCsvImportPreservesExactAndroidIdentitiesInstantsAndFingerprint() {
        val imports = manifest.getJSONArray("imports")
        for (i in 0 until imports.length()) {
            val item = imports.getJSONObject(i); val filename = item.getString("filename")
            val expected = state(item.getString("name")); val source = expected.sources.single()
            val artifact = ScheduleArtifact(filename, bytes("schedule/$filename"))
            val outcome = TimetableImporter().parseOutcome(artifact, expected.profiles["2026-2027-1"])
            val preview = (outcome as TimetableImporter.ParseOutcome.Ready).preview
            assertEquals(filename, source.sha256, preview.sha256)
            assertEquals(filename, expected.imported.sortedBy { it.id }, preview.occurrences.map {
                it.copy(id="${source.id}:${it.id}",sourceId=source.id)
            }.sortedBy { it.id })
        }
    }
}
