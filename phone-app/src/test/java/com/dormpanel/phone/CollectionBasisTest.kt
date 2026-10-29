package com.dormpanel.phone
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class CollectionBasisTest {
    @Test fun dateAndZoneCannotBeRelabeledAfterMidnight() {
        val zone=ZoneId.of("Asia/Shanghai")
        val before=LocalDate.of(2026,10,29).atTime(23,59,59).atZone(zone).toInstant()
        val basis=CollectionBasis.at(before,zone)
        assertTrue(basis.current(before,zone))
        assertFalse(basis.current(before.plusSeconds(2),zone))
        assertEquals(LocalDate.of(2026,10,29),basis.date)
        assertEquals(LocalDate.of(2026,10,30),basis.date.plusDays(1))
        assertFalse(basis.current(before,ZoneId.of("UTC")))
        assertEquals(LocalDate.of(2026,10,30),CollectionBasis.at(before.plusSeconds(2),zone).date)
    }
}
