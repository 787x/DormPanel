package com.dormpanel.phone
import org.junit.Assert.*
import org.junit.Test
class CollectionProjectionPlannerTest {
    @Test fun recentOnlyNeverBuildsWeeks() {
        var recent=0
        val result=CollectionProjectionPlanner.build(Result.success(1),CollectionDemand(true,emptySet()),{ recent++; it },{ _,_ -> error("unused week") })
        assertEquals(1,recent); assertEquals(1,result.recent!!.getOrThrow()); assertTrue(result.weeks.isEmpty())
    }
    @Test fun weekOnlyBuildsOnlyDistinctRequestedModes() {
        val calls=mutableListOf<WeekWidgetMode>(); val mode=WeekWidgetMode(false,5)
        val result=CollectionProjectionPlanner.build(Result.success(2),CollectionDemand(false,listOf(mode,mode,WeekWidgetMode()).toSet()),{ error("unused recent") },{ state,m -> calls+=m; state })
        assertNull(result.recent); assertEquals(2,calls.size); assertEquals(2,result.weeks[mode]!!.getOrThrow())
    }
    @Test fun recentBudgetFailureDoesNotPoisonWeeksAndWeekFailureDoesNotPoisonRecent() {
        val mode=WeekWidgetMode()
        val a=CollectionProjectionPlanner.build(Result.success(1),CollectionDemand(true,setOf(mode)),{ require(false) { "recent budget" }; it },{ s,_ -> s })
        assertTrue(a.recent!!.isFailure); assertEquals(1,a.weeks[mode]!!.getOrThrow())
        val b=CollectionProjectionPlanner.build(Result.success(2),CollectionDemand(true,setOf(mode)),{ it },{ _,_ -> error("week preparation") })
        assertEquals(2,b.recent!!.getOrThrow()); assertTrue(b.weeks[mode]!!.isFailure)
    }
    @Test fun failedModeAndNextPublicationNeverReuseOldResults() {
        val good=WeekWidgetMode(); val bad=WeekWidgetMode(false,5)
        val result=CollectionProjectionPlanner.build(Result.success(3),CollectionDemand(true,setOf(good,bad)),{ it },{ s,m -> if(m==bad) error("bad mode") else s })
        assertEquals(3,result.weeks[good]!!.getOrThrow()); assertTrue(result.weeks[bad]!!.isFailure)
        val next=CollectionProjectionPlanner.build(Result.success(4),CollectionDemand(false,setOf(bad)),{ it },{ s,_ -> s })
        assertNull(next.recent); assertEquals(setOf(bad),next.weeks.keys); assertEquals(4,next.weeks[bad]!!.getOrThrow())
    }
    @Test fun storeFailureReachesAllRequestedViewsWithoutProjectionCalls() {
        val failure=IllegalStateException("read")
        val result=CollectionProjectionPlanner.build<Int,Int>(Result.failure(failure),CollectionDemand(true,setOf(WeekWidgetMode(),WeekWidgetMode(false,5))),{ error("must not run") },{ _,_ -> error("must not run") })
        assertSame(failure,result.recent!!.exceptionOrNull()); assertTrue(result.weeks.values.all { it.exceptionOrNull()===failure })
    }
}
