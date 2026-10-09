package dev.dontblameme.selvage.peer

/**
 * What `PROTOCOL.md` §7.1 has a host keep together: the host key's private half, the highest
 * `issued` it has published, and the room's frame count beside it (`CANONICAL.md` §6.1).
 */
class PersistedHost(
    /** The host key's 32-byte seed, the private half of the `h` the invite's fragment carries. */
    val hostSeed: ByteArray,
    /** The highest `issued` this host has published. */
    val issued: Long,
    /**
     * The room's frame count as this host has kept it (`CANONICAL.md` §6.1's frame budget).
     * Null for a record written before the count existed; the room's count is then unknown.
     */
    val frames: Long? = null,
)

/**
 * Where a host keeps what makes it the host after a reload (§7.1, §9.1).
 *
 * The engine has no filesystem and this is the seam: a session handed no store is a host that
 * cannot outlive its process, which §7.1 permits and §9.1 prices — a host whose key is gone can
 * be seated in its room and can never publish a state a peer accepts again.
 *
 * `load` is called once, when the host is built; `save` on every state it publishes, on every
 * return, and once a renewal window while the room's count moves. A record whose seed is not the
 * one this host signs with is ignored, so a record another host left behind starts this series
 * at 1 rather than continuing it.
 */
interface HostStore {
    fun load(): PersistedHost?

    fun save(persisted: PersistedHost)
}

/**
 * A store that lives as long as the process it was made in. It keeps §7.1's three values and
 * writes them nowhere, so a host whose process ends is a host without its key: a resume inside one
 * process is the session's own state and never reads this back.
 */
class InMemoryHostStore : HostStore {
    private var held: PersistedHost? = null

    override fun load(): PersistedHost? = held

    override fun save(persisted: PersistedHost) {
        held = persisted
    }
}
