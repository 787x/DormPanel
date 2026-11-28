package com.dormpanel.app.icloud

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class CalendarWriteTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private val fixture = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//DormPanel//Test//EN
        BEGIN:VEVENT
        UID:fixture
        DTSTAMP:20260101T000000Z
        DTSTART:20261101T063000Z
        DTEND:20261102T073000Z
        SUMMARY:原始标题
        X-DORMPANEL-TEST:保留
        BEGIN:VALARM
        ACTION:DISPLAY
        DESCRIPTION:提醒
        TRIGGER:-PT15M
        END:VALARM
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Test fun editsPreserveOverlapInstantAndUnknownPropertiesAndAlarm() {
        val source = EditableCalendarObject.parse(fixture, "fixture", zone)
        val title = "你好，\n" + "跨天".repeat(45) + ";,\\"
        val edited = source.edit(source.draft.copy(title = title), now)
        assertTrue(edited.contains("DTSTART:20261101T063000Z"))
        assertTrue(edited.contains("DTEND:20261102T073000Z"))
        assertTrue(edited.contains("X-DORMPANEL-TEST:保留"))
        assertTrue(edited.contains("TRIGGER:-PT15M"))
        assertTrue(edited.split("\r\n").all { it.toByteArray().size <= 75 })
        assertEquals(title, EditableCalendarObject.parse(edited, "fixture", zone).draft.title)
    }

    @Test fun refusesRecurrenceAndSchedulingAndRejectsGap() {
        for (property in listOf("RRULE:FREQ=DAILY", "RECURRENCE-ID:20261101T063000Z",
            "ORGANIZER:mailto:test@example.invalid", "ATTENDEE:mailto:test@example.invalid")) {
            assertThrows(IllegalArgumentException::class.java) {
                EditableCalendarObject.parse(fixture.replace("SUMMARY:", "$property\nSUMMARY:"), "fixture", zone)
            }
        }
        val source = EditableCalendarObject.parse(fixture, "fixture", zone)
        assertThrows(IllegalArgumentException::class.java) {
            source.edit(source.draft.copy(start = LocalDateTime.parse("2026-03-08T02:30")), now)
        }
    }

    @Test fun preservesEmbeddedTimezoneWhenEditingAnotherField() {
        val timezone = """
            BEGIN:VTIMEZONE
            TZID:Fixture/Offset
            BEGIN:STANDARD
            DTSTART:19700101T000000
            TZOFFSETFROM:+0430
            TZOFFSETTO:+0430
            END:STANDARD
            END:VTIMEZONE
        """.trimIndent()
        val original = fixture.replace("BEGIN:VEVENT", "$timezone\nBEGIN:VEVENT")
            .replace("DTSTART:20261101T063000Z", "DTSTART;TZID=Fixture/Offset:20261101T110000")
        val event = EditableCalendarObject.parse(original, "fixture", zone)
        val updated = event.edit(event.draft.copy(description = "说明"), now)
        assertTrue(updated.contains(timezone.replace("\n", "\r\n")))
        assertTrue(updated.contains("DTSTART;TZID=Fixture/Offset:20261101T110000"))
        val readback = EditableCalendarObject.parse(updated, "fixture", zone)
        assertEquals(event.draft.copy(description = "说明"), readback.draft)
        val withoutAlarm = updated.replace(Regex("BEGIN:VALARM[\\s\\S]+?END:VALARM\\r\\n"), "")
        assertFalse(readback.matches(EditableCalendarObject.parse(withoutAlarm, "fixture", zone), preserveProperties = true))
        val changedTime = event.edit(event.draft.copy(end = event.draft.end.plusHours(1)), now)
        assertTrue(changedTime.contains("DTSTART:20261101T063000Z"))
        assertTrue(changedTime.contains("DTEND:20261102T083000Z"))
        assertTrue(changedTime.contains(timezone.replace("\n", "\r\n")))
    }
}
