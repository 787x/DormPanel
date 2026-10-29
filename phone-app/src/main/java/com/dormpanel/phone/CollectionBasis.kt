package com.dormpanel.phone
import java.time.*
/** A display snapshot cannot acquire a new date or timezone by being rendered later. */
internal data class CollectionBasis(val date: LocalDate,val zone: ZoneId) {
    fun current(now: Instant,currentZone: ZoneId)=zone==currentZone && date==now.atZone(currentZone).toLocalDate()
    companion object { fun at(now: Instant,zone: ZoneId)=CollectionBasis(now.atZone(zone).toLocalDate(),zone) }
}
