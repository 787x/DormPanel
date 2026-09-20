package com.dormpanel.app.dashboard.card

import com.dormpanel.app.dashboard.model.CardSize

enum class CardDensity { COMPACT, STANDARD, EXPANDED }

fun cardDensity(size: CardSize): CardDensity = when {
    size.rowSpan == 1 -> CardDensity.COMPACT
    size.rowSpan >= 3 && size.columnSpan >= 3 -> CardDensity.EXPANDED
    else -> CardDensity.STANDARD
}

fun millisUntilNextMinute(now: Long): Long = 60_000L - Math.floorMod(now, 60_000L)
