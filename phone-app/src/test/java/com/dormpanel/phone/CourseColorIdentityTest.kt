package com.dormpanel.phone

import com.dormpanel.app.schedule.*
import org.junit.Assert.*
import org.junit.Test

class CourseColorIdentityTest {
    private fun row(source: String, series: String, title: String, id: String = series) = ImportedClassOccurrence(id,source,series,null,0,title,0,60000,"UTC","","",null)
    @Test fun groupingUsesOriginalTitlesAndIsolatesAmbiguityAndSources() {
        val state = ScheduleState(imported=listOf(row("s","1","数学"),row("s","2","数学"),row("s","3","数学"),row("other","4","数学"),row("s","5","英语"),row("s","6","甲","a"),row("s","6","乙","b")),entries=listOf(TimetableEntry("local","数学",1,0,60)))
        val colors = CourseColorIdentity(state)
        assertEquals(colors.key("s","1"),colors.key("s","2")); assertEquals(colors.key("s","1"),colors.key("s","3"))
        assertNotEquals(colors.key("s","1"),colors.key("other","4")); assertNotEquals(colors.key("s","1"),colors.key("s","5"))
        assertNotEquals(colors.key("s","6"),colors.key("s","1")); assertNotEquals(colors.key(null,"local"),colors.key("s","1"))
        val replacement = state.copy(imported=listOf(row("s","new","数学")),classOverrides=listOf(ClassOverride.of("s","new",null,ClassPatch(title="改名"))))
        assertEquals(colors.key("s","1"),CourseColorIdentity(replacement).key("s","new"))
    }
    @Test fun framingAndPersistentAllocation() {
        assertNotEquals(CourseColorIdentity.encode("a|b","c"),CourseColorIdentity.encode("a","b|c"))
        val keys=(0..15).map { "key$it" }.toSet()
        val first=CourseColorAllocation.allocate(keys,emptyMap(),16)
        assertEquals(16,first.values.toSet().size)
        val more=CourseColorAllocation.allocate(keys+"extra",first,16)
        first.forEach { (k,v) -> assertEquals(v,more[k]) }
        assertEquals(first,CourseColorAllocation.allocate(keys.reversed().toSet(),first,16))
        assertEquals(17,more.size)
    }
}
