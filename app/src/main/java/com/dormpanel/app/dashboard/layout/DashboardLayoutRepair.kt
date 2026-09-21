package com.dormpanel.app.dashboard.layout

import com.dormpanel.app.dashboard.model.*
import com.dormpanel.app.dashboard.persistence.*

data class DashboardRepairResult(val cards: List<PlacedCard>, val quarantine: List<QuarantinedCard>)

/** Stored order defines priority; recovery records follow active records. Never reflows accepted cards. */
class DashboardLayoutRepair(private val grid: GridDefinition, private val catalog: CardSizeCatalog) {
    fun repair(rawCards: List<RawDashboardCard>, quarantine: List<QuarantinedCard> = emptyList()): DashboardRepairResult {
        val engine = DashboardLayoutEngine(grid, catalog)
        var accepted = emptyList<PlacedCard>()
        val rejected = mutableListOf<QuarantinedCard>()
        val seen = mutableSetOf<String>()
        for (raw in rawCards + quarantine.map { it.raw }) {
            fun reject(reason: RecoveryReason) { rejected += QuarantinedCard(raw, reason) }
            if (!seen.add(raw.id)) { reject(RecoveryReason.DUPLICATE_ID); continue }
            if (raw.id.isBlank() || raw.columnSpan <= 0 || raw.rowSpan <= 0) {
                reject(RecoveryReason.MALFORMED); continue
            }
            val policy = catalog.sizePolicy(raw.providerType)
            if (policy == null) { reject(RecoveryReason.UNKNOWN_PROVIDER); continue }
            val original = raw.toModel()
            val maximum = CardSize(grid.columns, grid.rows)
            val size = if (policy.allows(original.size) && raw.columnSpan <= grid.columns && raw.rowSpan <= grid.rows) original.size
                else policy.snap(original.size, maximum)
            if (size == null) { reject(RecoveryReason.NO_VALID_SIZE); continue }
            val candidate = original.copy(size = size)
            if (engine.validate(accepted + candidate) == null) {
                accepted = accepted + candidate
            } else {
                when (val placed = engine.addFirstAvailable(accepted, candidate)) {
                    is LayoutMutationResult.Success -> accepted = placed.cards
                    is LayoutMutationResult.Failure -> reject(RecoveryReason.NO_SPACE)
                }
            }
        }
        check(engine.validate(accepted) == null)
        return DashboardRepairResult(accepted, rejected)
    }
}
