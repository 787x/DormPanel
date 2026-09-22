package com.dormpanel.app.dashboard.catalog

class CombinedCardCatalog(private vararg val sources: CardCatalog) : CardCatalog {
    override val candidates get() = sources.flatMap { it.candidates }.distinctBy { it.candidateId }
    private val subscriptions = mutableMapOf<(List<CardAddCandidate>) -> Unit, (List<CardAddCandidate>) -> Unit>()
    override fun addListener(listener: (List<CardAddCandidate>) -> Unit) {
        removeListener(listener)
        var last: List<CardAddCandidate>? = null
        val subscription: (List<CardAddCandidate>) -> Unit = {
            val next = candidates
            if (last != next) { last = next; listener(next) }
        }
        subscriptions[listener] = subscription; sources.forEach { it.addListener(subscription) }
    }
    override fun removeListener(listener: (List<CardAddCandidate>) -> Unit) {
        subscriptions.remove(listener)?.let { subscription -> sources.forEach { it.removeListener(subscription) } }
    }
}
