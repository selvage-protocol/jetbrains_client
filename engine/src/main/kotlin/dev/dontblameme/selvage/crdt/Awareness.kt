package dev.dontblameme.selvage.crdt

/** Which clients an awareness change added, updated and removed. */
data class AwarenessChange(
    val added: List<Long>,
    val updated: List<Long>,
    val removed: List<Long>,
)

/**
 * y-protocols' `Awareness` (1.0.7) without its own timer: [tick] renews and expires on the
 * caller's schedule, with the renewal and expiry windows the session advertises (§8.2), and
 * [now] is the only clock it reads.
 *
 * A state is any JSON value; [YAny.Null] is a removed one.
 */
class Awareness(
    clientID: Long,
    private val renewMs: Long,
    private val expireMs: Long,
    private val now: () -> Long,
) {
    data class Meta(
        val clock: Long,
        val lastUpdated: Long,
    )

    /**
     * Called with what changed and its origin: "update" listeners for every accepted state,
     * "change" listeners only when a state was added, removed, or changed in content.
     */
    fun interface Listener {
        fun onChange(
            change: AwarenessChange,
            origin: Any?,
        )
    }

    var clientID: Long = clientID
        private set

    private val statesMap = LinkedHashMap<Long, YAny>()
    private val metaMap = LinkedHashMap<Long, Meta>()
    private val changeListeners = ArrayList<Listener>()
    private val updateListeners = ArrayList<Listener>()

    init {
        require(renewMs > 0 && expireMs > 0) { "renewal and expiry windows must be positive" }
        setLocalState(YAny.Obj.of())
    }

    val states: Map<Long, YAny> get() = statesMap
    val meta: Map<Long, Meta> get() = metaMap

    val localState: YAny? get() = statesMap[clientID]

    fun onChange(listener: Listener): () -> Unit {
        changeListeners.add(listener)
        return { changeListeners.remove(listener) }
    }

    fun onUpdate(listener: Listener): () -> Unit {
        updateListeners.add(listener)
        return { updateListeners.remove(listener) }
    }

    /**
     * Takes [next] as this client's id, keeping every remote state. The old id's entry goes, and
     * the new one starts removed at clock 0, so its first state is above the clock 0 y-protocols
     * ignores for an id it has not seen.
     */
    fun rotate(next: Long) {
        if (next == clientID) return
        statesMap.remove(clientID)
        metaMap.remove(clientID)
        clientID = next
        statesMap.remove(next)
        metaMap[next] = Meta(0, now())
    }

    /** Publishes [state] as this client's on a newer clock; null clears it. */
    fun setLocalState(state: YAny?) {
        val clock = metaMap[clientID]?.let { it.clock + 1 } ?: 0
        val prevState = statesMap[clientID]
        if (state == null || state == YAny.Null) statesMap.remove(clientID) else statesMap[clientID] = state
        metaMap[clientID] = Meta(clock, now())
        val cleared = state == null || state == YAny.Null
        val added = ArrayList<Long>()
        val updated = ArrayList<Long>()
        val filteredUpdated = ArrayList<Long>()
        val removed = ArrayList<Long>()
        if (cleared) {
            removed.add(clientID)
        } else if (prevState == null) {
            added.add(clientID)
        } else {
            updated.add(clientID)
            if (prevState != state) filteredUpdated.add(clientID)
        }
        if (added.isNotEmpty() || filteredUpdated.isNotEmpty() || removed.isNotEmpty()) {
            emit(changeListeners, AwarenessChange(added, filteredUpdated, removed), LOCAL)
        }
        emit(updateListeners, AwarenessChange(added, updated, removed), LOCAL)
    }

    /**
     * The renewal tick: republishes the local state once [renewMs] has passed since it was last
     * published, and forgets every remote state not renewed for [expireMs].
     */
    fun tick() {
        val t = now()
        val local = localState
        if (local != null && t - metaMap.getValue(clientID).lastUpdated >= renewMs) setLocalState(local)
        val remove =
            metaMap.entries
                .filter { (client, m) ->
                    client != clientID && t - m.lastUpdated >= expireMs &&
                        statesMap.containsKey(client)
                }.map { it.key }
        if (remove.isNotEmpty()) removeStates(remove, TIMEOUT)
    }

    /** y-protocols' `removeAwarenessStates`. */
    fun removeStates(
        clients: List<Long>,
        origin: Any?,
    ) {
        val removed = ArrayList<Long>()
        for (client in clients) {
            if (statesMap.containsKey(client)) {
                statesMap.remove(client)
                if (client == clientID) {
                    val cur = metaMap.getValue(client)
                    metaMap[client] = Meta(cur.clock + 1, now())
                }
                removed.add(client)
            }
        }
        if (removed.isNotEmpty()) {
            val change = AwarenessChange(emptyList(), emptyList(), removed)
            emit(changeListeners, change, origin)
            emit(updateListeners, change, origin)
        }
    }

    /** y-protocols' `encodeAwarenessUpdate`: each client's clock and state, a missing state as null. */
    fun encodeUpdate(clients: Collection<Long> = metaMap.keys): ByteArray {
        val encoder = Lib0Encoder()
        encoder.writeVarUint(clients.size)
        for (client in clients) {
            val state = statesMap[client]
            val clock =
                metaMap[client]?.clock ?: throw IllegalArgumentException("no awareness clock for client $client")
            encoder.writeVarUint(client)
            encoder.writeVarUint(clock)
            encoder.writeVarString(Json.stringify(stringifiable(state)) ?: "null")
        }
        return encoder.toByteArray()
    }

    /** y-protocols' `applyAwarenessUpdate`. */
    fun applyUpdate(
        update: ByteArray,
        origin: Any? = null,
    ) {
        val decoder = Lib0Decoder(update)
        val timestamp = now()
        val added = ArrayList<Long>()
        val updated = ArrayList<Long>()
        val filteredUpdated = ArrayList<Long>()
        val removed = ArrayList<Long>()
        repeat(decoder.readLength()) {
            val client = decoder.readVarUint()
            var clock = decoder.readVarUint()
            val state = Json.parse(decoder.readVarString())
            val clientMeta = metaMap[client]
            val prevState = statesMap[client]
            val currClock = clientMeta?.clock ?: 0
            if (currClock < clock || (currClock == clock && state == YAny.Null && statesMap.containsKey(client))) {
                if (state == YAny.Null) {
                    // A remote client never removes the local state: announce it is still here.
                    if (client == clientID && localState != null) clock++ else statesMap.remove(client)
                } else {
                    statesMap[client] = state
                }
                metaMap[client] = Meta(clock, timestamp)
                if (clientMeta == null && state != YAny.Null) {
                    added.add(client)
                } else if (clientMeta != null && state == YAny.Null) {
                    removed.add(client)
                } else if (state != YAny.Null) {
                    if (state != prevState) filteredUpdated.add(client)
                    updated.add(client)
                }
            }
        }
        if (added.isNotEmpty() || filteredUpdated.isNotEmpty() || removed.isNotEmpty()) {
            emit(changeListeners, AwarenessChange(added, filteredUpdated, removed), origin)
        }
        if (added.isNotEmpty() || updated.isNotEmpty() || removed.isNotEmpty()) {
            emit(updateListeners, AwarenessChange(added, updated, removed), origin)
        }
    }

    private fun emit(
        listeners: List<Listener>,
        change: AwarenessChange,
        origin: Any?,
    ) {
        for (listener in listeners.toList()) listener.onChange(change, origin)
    }

    companion object {
        /** The origin of a change made through [setLocalState]. */
        const val LOCAL = "local"

        /** The origin of an expiry made by [tick]. */
        const val TIMEOUT = "timeout"

        /** `state || null`: a falsy state is written as null. */
        private fun stringifiable(state: YAny?): YAny =
            when (state) {
                null, YAny.Undefined, YAny.Null -> YAny.Null
                is YAny.Bool -> if (state.value) state else YAny.Null
                is YAny.Num -> if (state.value == 0.0 || state.value.isNaN()) YAny.Null else state
                is YAny.Str -> if (state.value.isEmpty()) YAny.Null else state
                else -> state
            }
    }
}
