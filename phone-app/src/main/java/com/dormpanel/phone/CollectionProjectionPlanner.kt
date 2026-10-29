package com.dormpanel.phone

internal data class WeekWidgetMode(val periods: Boolean = true,val days: Int = 7) {
    init { require(days==5 || days==7) }
}
internal data class CollectionDemand(val recent: Boolean,val weeks: Set<WeekWidgetMode>)

/** One immutable publication, at most one recent projection and four distinct week variants.
 * No previous generation is retained. Store failure and independent display failures stay distinct. */
internal object CollectionProjectionPlanner {
    data class Publication<T>(val recent: Result<T>?,val weeks: Map<WeekWidgetMode,Result<T>>)
    fun <S,T> build(read: Result<S>,demand: CollectionDemand,recent: (S)->T,week: (S,WeekWidgetMode)->T): Publication<T> {
        fun compute(block: (S)->T): Result<T> = read.fold({ state -> runCatching { block(state) } },{ Result.failure(it) })
        return Publication(if(demand.recent) compute(recent) else null,
            demand.weeks.associateWith { mode -> compute { week(it,mode) } })
    }
}
