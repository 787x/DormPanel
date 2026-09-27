package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

class WebDavCsvSelectionTest {
    private fun item(url: String, name: String, folder: Boolean = false, modifiedAt: Long? = 100L) =
        WebDavItem(url, name, folder, "\"e\"", "Thu, 24 Sep 2026 09:00:00 GMT", modifiedAt, 10, null)

    @Test fun childrenKeepsIcsAndCsvAndFolders() {
        val items = listOf(
            item("https://e/dav/", "dav", true),
            item("https://e/dav/sub/", "sub", true),
            item("https://e/dav/a.ics", "a.ics"),
            item("https://e/dav/b.csv", "b.csv"),
            item("https://e/dav/c.txt", "c.txt"))
        val kids = WebDavSelection.children("https://e/dav/", items)
        assertEquals(listOf("sub", "a.ics", "b.csv"), kids.map { it.name })
    }

    @Test fun latestIcsDoesNotSelectCsv() {
        val items = listOf(
            item("https://e/dav/a.csv", "a.csv", modifiedAt = 200L),
            item("https://e/dav/b.ics", "b.ics", modifiedAt = 100L))
        assertEquals("b.ics", WebDavSelection.latest(items, "ics").name)
    }

    @Test fun latestCsvDoesNotSelectIcs() {
        val items = listOf(
            item("https://e/dav/a.csv", "a.csv", modifiedAt = 100L),
            item("https://e/dav/b.ics", "b.ics", modifiedAt = 200L))
        assertEquals("a.csv", WebDavSelection.latest(items, "csv").name)
    }

    @Test fun latestCsvPicksNewestByModifiedAt() {
        val items = listOf(
            item("https://e/dav/old.csv", "old.csv", modifiedAt = 100L),
            item("https://e/dav/new.csv", "new.csv", modifiedAt = 200L))
        assertEquals("new.csv", WebDavSelection.latest(items, "csv").name)
    }

    @Test fun oldBindingsWithoutNewFieldsStillParse() {
        // Mode names FILE and FOLDER_LATEST_ICS must keep their saved meaning.
        assertEquals(WebDavMode.FILE, WebDavMode.valueOf("FILE"))
        assertEquals(WebDavMode.FOLDER_LATEST_ICS, WebDavMode.valueOf("FOLDER_LATEST_ICS"))
        assertEquals(WebDavMode.FOLDER_LATEST_CSV, WebDavMode.valueOf("FOLDER_LATEST_CSV"))
        val binding = WebDavBinding("s", WebDavMode.FOLDER_LATEST_ICS, "https://e/folder")
        assertNull(binding.format)
        assertNull(binding.termKey)
        assertNull(binding.profileFingerprint)
    }

    @Test fun csvBindingCarriesProfileFingerprintMetadata() {
        val profile = BuiltInProfiles.term2026
        val print = TimetableImporter.profileFingerprint(profile)
        val binding = WebDavBinding("s", WebDavMode.FOLDER_LATEST_CSV, "https://e/folder",
            format = "csv", termKey = "2026-2027-1", profileFingerprint = print)
        assertEquals("csv", binding.format)
        assertEquals("2026-2027-1", binding.termKey)
        assertEquals(print, binding.profileFingerprint)
        val changed = TimetableImporter.profileFingerprint(profile.copy(
            week1Monday = profile.week1Monday.plusWeeks(1)))
        assertNotEquals(binding.profileFingerprint, changed)
    }
}
