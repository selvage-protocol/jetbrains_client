package dev.dontblameme.selvage.intellij.bridge

import dev.dontblameme.selvage.sealed.Role

/**
 * Everyone in the room as the people list draws them, ported from `vscode_client/src/adapter/participants.ts`:
 * you first, then the others in the room's order, each in the colour of their seat.
 */
object People {
    const val HOST_LABEL = "Host"
    const val NO_PATH = "not in a file yet"
    const val YOU_MARK = "(you)"
    const val EVERYONE_LABEL = "Everyone in the room"

    data class RoomMember(
        val peerId: String,
        val displayName: String,
        val role: Role,
        val path: String? = null,
    )

    data class Person(
        val peerId: String,
        val displayName: String,
        val role: Role,
        val path: String?,
        val self: Boolean,
        val label: String,
        val colour: String,
    )

    fun seatPeople(
        self: RoomMember,
        others: List<RoomMember>,
    ): List<Person> {
        val everyone = listOf(self) + others.filter { it.peerId != self.peerId }
        val colours = Seats.seatColours(everyone.map { Seats.Seat(it.peerId, it.role) })
        val named = everyone.map { Names.NamedPeer(PeerColours.peerName(it.displayName, it.peerId), it.peerId) }
        return everyone.mapIndexed { index, member ->
            Person(
                peerId = member.peerId,
                displayName = member.displayName,
                role = member.role,
                path = member.path,
                self = member.peerId == self.peerId,
                label = Names.rosterLabel(named[index], named),
                colour = colours[member.peerId] ?: PeerColours.peerColour(member.peerId),
            )
        }
    }

    data class PersonRow(
        val peerId: String,
        val label: String,
        val description: String,
        val tooltip: String,
        val colour: String,
        val initials: String,
        val self: Boolean,
        val host: Boolean,
        val following: Boolean,
        val path: String?,
    )

    fun whereLine(
        self: Boolean,
        path: String?,
    ): String {
        if (self) return ""
        return if (path == null) NO_PATH else "in $path"
    }

    enum class Act(
        val label: String,
    ) {
        GO_TO("Go to"),
        FOLLOW("Follow"),
        STOP_FOLLOWING("Stop following"),
        RENAME("Rename"),
    }

    fun personActs(row: PersonRow): List<Act> {
        if (row.self) return listOf(Act.RENAME)
        val acts = ArrayList<Act>()
        if (row.path != null) acts.add(Act.GO_TO)
        acts.add(if (row.following) Act.STOP_FOLLOWING else Act.FOLLOW)
        return acts
    }

    fun personRows(
        people: List<Person>,
        followingPeerId: String? = null,
    ): List<PersonRow> =
        people.map { person ->
            val host = person.role == Role.HOST
            val following = !person.self && person.peerId == followingPeerId
            val where = whereLine(person.self, person.path)
            val description =
                listOf(
                    if (person.self) YOU_MARK else "",
                    if (host) HOST_LABEL else "",
                    if (following) "following" else "",
                    where,
                ).filter { it != "" }
                    .joinToString(" · ")
            val tooltip =
                listOf(
                    if (person.self) "${person.label} $YOU_MARK" else person.label,
                    if (host) HOST_LABEL else "",
                    where,
                    if (following) "Following ${person.label}" else "",
                ).filter { it != "" }.joinToString(" · ")
            PersonRow(
                peerId = person.peerId,
                label = person.label,
                description = description,
                tooltip = tooltip,
                colour = person.colour,
                initials = Initials.initials(person.label),
                self = person.self,
                host = host,
                following = following,
                path = person.path,
            )
        }

    /** A room path as a row caption shows it, ported from `captionPath` in `extension.ts`. */
    fun captionPath(path: String): String {
        if (path.length <= Grant.MAX_GRANT_PATH_BYTES) return path
        val head = path.substring(0, Grant.MAX_GRANT_PATH_BYTES - 1)
        val whole = if (head.last().isHighSurrogate()) head.dropLast(1) else head
        return "$whole\u2026"
    }
}
