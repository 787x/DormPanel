package com.dormpanel.phone

/** Independent lists keep only their own rows and business identities, including their own empty state. */
internal object RecentColumnRows {
    fun <R,C> select(rows: List<R>,column: Int,cells: (R)->List<C>,text: (C)->String,key: (C)->String,row: (String,C)->R): List<R> {
        require(column in 0..1)
        return rows.mapNotNull { original -> cells(original).getOrNull(column)?.takeIf { text(it).isNotEmpty() }
            ?.let { row(key(it),it) } }
    }
}
