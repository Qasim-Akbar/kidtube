package com.bbro.kidtube.player

import com.bbro.kidtube.data.repo.EventRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes tracked events to Room (`EventRepository.record`, which stamps client UUIDs and asks for a debounced sync) in the
 * order they were submitted, on an app-wide scope: a screen or view model that goes away (or a back press) never loses an
 * event that was already handed over. [awaitIdle] returns once everything submitted before the call is in Room, so a caller
 * can "flush, then evaluate the feed".
 */
@Singleton
class KidEventWriter internal constructor(
    private val events: EventRepository,
    scope: CoroutineScope,
) : EventSink {
    @Inject
    constructor(events: EventRepository) : this(events, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private sealed interface Item {
        data class Write(val event: TrackedEvent) : Item
        data class Marker(val done: CompletableDeferred<Unit>) : Item
    }

    private val queue = Channel<Item>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (item in queue) {
                when (item) {
                    is Item.Write -> runCatching {
                        val e = item.event
                        events.record(e.type, e.childId, e.payload, localDate = e.localDate, occurredAt = e.occurredAt)
                    } // a failed local write must not stop the queue; there is nothing better to do with it
                    is Item.Marker -> item.done.complete(Unit)
                }
            }
        }
    }

    override fun emit(event: TrackedEvent) {
        queue.trySend(Item.Write(event))
    }

    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        queue.trySend(Item.Marker(done))
        done.await()
    }
}
