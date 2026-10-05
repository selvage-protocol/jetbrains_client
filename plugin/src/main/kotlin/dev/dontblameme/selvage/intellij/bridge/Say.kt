package dev.dontblameme.selvage.intellij.bridge

/**
 * Every sentence this client puts in front of a person, worded as the VS Code client words it
 * (`docs/studies/client-command-parity.md` §5 in the design notes). `VocabularyTest` renders each
 * with `${}` for its holes and requires it among the sentences `vscode_client/test/vocabulary.test.ts`
 * pins, except the few this editor says in its own words, which it pins by name.
 */
@Suppress("TooManyFunctions")
object Say {
    const val WRAP = "Selvage: "

    fun wrap(sentence: String): String = "$WRAP$sentence"

    // --- the twelve command titles ---------------------------------------------------------------

    const val HOST = "Host a session"
    const val JOIN = "Join a session from an invite link"
    const val COPY_INVITE = "Copy the invite link"
    const val OPEN_DOCUMENT = "Open a document from the room"
    const val FETCH = "Download a file from the room"
    const val LEAVE = "Leave the session"
    const val DISPLAY_NAME = "Set the name other participants see"
    const val CHANGE_SERVER = "Change the server"
    const val PEERS = "List the room's participants"
    const val GO_TO = "Go to a participant"
    const val FOLLOW = "Follow a participant"
    const val STOP_FOLLOWING = "Stop following"

    // --- hosting --------------------------------------------------------------------------------

    fun alreadyHostingCopied() = "Selvage: you are already hosting this session; the invite link is on the clipboard."

    fun alreadyHostingRefused(why: String) =
        "Selvage: you are already hosting this session, but the invite link could not be copied ($why)."

    fun alreadyHostingNoInvite() =
        "Selvage: you are already hosting this session, but this connection holds no invite link to send."

    fun hostWarning() = "Selvage: you are in this session; hosting a session means leaving it first."

    fun openFolderFirst() =
        "Selvage: open a folder first — hosting shares the folder this window is open on, and a room from a window with no folder would share nothing."

    fun connecting(address: String) = "Selvage: connecting to $address…"

    fun couldNotHost(
        address: String,
        why: String,
    ) = "Selvage: could not host on $address. $why"

    fun roomOpenRefused(why: String) = "Selvage: the room is open, but the invite link could not be copied ($why)."

    fun roomOpenNoInvite() = "Selvage: the room is open, but this connection holds no invite link to send."

    fun roomOpenOn(address: String) =
        "Selvage: the room is open on $address. Send this link to your friend — it is on the clipboard."

    fun roomOpen() = "Selvage: the room is open. Send this link to your friend — it is on the clipboard."

    const val COPY_AGAIN = "Copy again"

    // --- the server ---------------------------------------------------------------------------

    fun willHostOn(address: String) =
        "Selvage: will host on $address next. Leave this session and host again to move there."

    fun noServerRemembered() = "Selvage: no server is remembered yet; the next host asks."

    fun nextHostUses(address: String) = "Selvage: the next host uses $address."

    fun settingFixesServer(address: String) =
        "Selvage: the \"selvage.serverUrl\" setting fixes the server at $address; change it in Settings to use a different one."

    const val SERVER_TITLE = "The Selvage server to host on"
    const val SERVER_PROMPT = "The server you and your guest both connect to."

    // --- joining ------------------------------------------------------------------------------

    fun joinWarningHost() =
        "Selvage: you are hosting this session; joining another session ends this room for everyone."

    fun joinWarningGuest() = "Selvage: you are in this session; joining another session leaves it."

    fun couldNotOpenRoomFolder(why: String) =
        "Selvage: could not open the room's folder in this window ($why); join again."

    fun couldNotJoin(why: String) = "Selvage: could not join the session. $why"

    fun joinedEmpty() = "Selvage: joined the room. No one has a file open yet."

    fun joinedOpening(path: String) = "Selvage: joined the room, opening $path."

    fun joinedOpeningMore(
        path: String,
        more: String,
    ) = "Selvage: joined the room, opening $path. $more open."

    fun joined() = "Selvage: joined the room."

    fun moreFiles(rest: Int) = if (rest == 1) "1 more file is" else "$rest more files are"

    const val JOIN_TITLE = "Join a Selvage session"
    const val JOIN_PROMPT = "Paste the invite link the host sent you."

    // --- the invite ---------------------------------------------------------------------------

    fun noInvite() = "Selvage: there is no invite link; host or join a room first."

    fun inviteNotCopied(why: String) = "Selvage: the invite link could not be copied ($why)."

    fun sessionHoldsNoInvite() = "Selvage: this session holds no invite link to copy."

    // --- documents ----------------------------------------------------------------------------

    fun joinFirst() = "Selvage: join a session first."

    fun youAreHost() = "Selvage: you are the host — the files you open are the ones your guests see."

    fun viewerReadOnly() = "Selvage: you are a viewer in this room, so its documents are read-only."

    fun noOneHasFile() = "Selvage: no one in the room has a file open yet."

    fun noSharedDocumentMatches(wanted: String) = "Selvage: no shared document matches \"$wanted\"."

    fun couldNotOpen(
        path: String,
        why: String,
    ) = "Selvage: could not open $path from the room: $why"

    fun leftListingClosed(path: String) = "Selvage: $path is no longer in the room, so it was closed."

    fun leftListingKept(path: String) =
        "Selvage: $path is no longer in the room; your unsaved copy is kept but no longer shared."

    fun applyRefused(path: String) =
        "Selvage: the editor would not apply the room's change to $path; the file may be read-only."

    fun divergence(path: String) = "Selvage: $path was out of step with the room; the room's copy has been put back."

    fun saveFailed(path: String) = "Selvage: could not save $path; the file on disk is behind the room."

    fun saveFailedBecause(
        path: String,
        why: String,
    ) = "Selvage: could not save $path; the file on disk is behind the room ($why)."

    fun unlistedOpened(path: String) =
        "Selvage: $path is not in the room, so it is not shared. Save a copy outside the room's folder to keep it."

    fun unlistedSaved(path: String) =
        "Selvage: $path is not in the room, so this save was not shared. Save a copy outside the room's folder to keep it."

    fun mirrorOverCapacityOne(path: String) =
        "Selvage: the room lists more files than this window mirrors; $path is left out."

    fun mirrorOverCapacity(
        count: String,
        first: String,
    ) =
        "Selvage: the room lists more files than this window mirrors; $count of them are left out, starting with $first."

    fun mirrorWriteFailedOne(path: String) = "Selvage: one of the room's files could not be written to disk: $path."

    fun mirrorWriteFailed(
        count: String,
        first: String,
    ) = "Selvage: $count of the room's files could not be written to disk, starting with $first."

    fun listingCutPaths() =
        "Selvage: this window shares more paths than one room listing carries, so some of its files are not in the room."

    fun listingCutBytes() =
        "Selvage: this window's paths are longer in total than one room listing carries, so some of its files are not in the room."

    fun listingCutBudget() =
        "Selvage: reading this window's folder took more work than one listing walk pays for, so the listing may be missing some of its files."

    /** IntelliJ's own: the project files a room may not put into a project opened on its mirror. */
    fun projectSettingsWithheld(paths: String) =
        "Selvage: the room's project settings ($paths) are not put in this window, because the IDE would apply them rather than just show them."

    // --- fetching -----------------------------------------------------------------------------

    fun fetchWhileHosting() =
        "Selvage: your files are already on your disk, so there is nothing to fetch while you host."

    fun couldNotFetch(
        path: String,
        why: String,
    ) = "Selvage: could not fetch $path from the room: $why"

    fun noListedMatch(wanted: String) = "Selvage: no file the room lists matches \"$wanted\"."

    fun tooManyUnder(
        count: String,
        dir: String,
        max: String,
    ) =
        "Selvage: $count files under $dir is more than one fetch holds (at most $max at once); name a narrower directory."

    fun nothingToFetch() = "Selvage: the room lists no files to fetch."

    fun tooManyAll(
        count: String,
        max: String,
    ) =
        "Selvage: fetching all $count listed files at once would hold every one in the room; fetch a file or a directory instead (at most $max at once)."

    fun fetchAllQuestion(count: String) =
        "Selvage: fetch all $count listed files? Everyone in the room receives them, and they are stored on your disk."

    fun fetched() = "Selvage: fetched the files."

    fun fetching(path: String) = "Selvage: fetching $path…"

    fun stillEmpty(path: String) =
        "Selvage: $path is still empty — the host has not sent its text yet. Fetch it again later."

    const val FETCH_WHOLE = "Fetch the whole listing"

    fun leftListingNotice(path: String) =
        "the host no longer shares $path; it may have been deleted after the listing was published"

    // --- people -------------------------------------------------------------------------------

    fun noOtherParticipants() = "Selvage: no other participants yet."

    fun nothingToFollow(name: String) = "Selvage: nothing to follow: $name is not in a document."

    fun notFollowing() = "Selvage: not following anyone."

    // --- leaving and the session's end ----------------------------------------------------------

    fun notInSession() = "Selvage: not in a session."

    fun leftSession() = "Selvage: left the session."

    fun disconnectedGuest() = "Selvage: the connection ended and the session is over; it could not be re-established."

    fun disconnectedHost() =
        "Selvage: the connection ended and the session is over; this client cannot resume a hosting session, so it will not reconnect."

    fun roomFull() = "Selvage: the room is full — it seats no more people."

    fun copyKept(
        sentence: String,
        root: String,
    ) = "$sentence Your copy is kept at $root."

    /** The host's leave question: the shared one's first sentence, without the second about the last keystrokes (`HOST_LEAVE_ASKING`). */
    val HOST_LEAVE_ASKING: String = Words.HOST_LEAVE_CONSEQUENCE

    // --- the name -----------------------------------------------------------------------------

    fun noDisplayName() = "Selvage: no display name is set yet."

    fun nameOthersSee(name: String) = "Selvage: the name others see is \"$name\"."

    fun displayNameSet(name: String) = "Selvage: display name set to \"$name\"."

    fun serverRefusedName(
        name: String,
        why: String,
    ) = "the server refused the display name \"$name\": $why"

    const val CHANGE_THE_NAME = "Change the name"
    const val NAME_TITLE = "The name other participants see"

    fun namePrompt(current: String?) =
        "At most 32 characters; some emoji and accented characters count as more than one." +
            if (current.isNullOrEmpty()) "" else " The name others see is \"$current\"."

    // --- the host's refusals of a peer's read --------------------------------------------------

    fun couldNotShare(
        path: String,
        why: String,
    ) = "could not share $path: $why; nothing was shared for it"

    fun willNotShare(
        path: String,
        why: String,
    ) = "will not share $path with the room: $why; nothing was shared for it"

    fun refusal(cause: GrantFolder.Refusal): String? =
        when (cause) {
            GrantFolder.Refusal.NOT_GRANTED -> {
                null
            }

            GrantFolder.Refusal.MISSING -> {
                "there is no readable file there any more (it may have been deleted after the listing was published)"
            }

            GrantFolder.Refusal.NOT_A_FILE -> {
                "it is not a plain file in the folder this session shares (a directory, a link, or something else that cannot be read as one)"
            }

            GrantFolder.Refusal.TOO_LARGE -> {
                "it is over the ${Grant.MAX_GRANT_FILE_BYTES} bytes a session will carry"
            }

            GrantFolder.Refusal.BINARY -> {
                "it is a binary file, and a room carries text, so this is not a file that can be shared at all"
            }
        }

    const val NOT_A_ROOM_PATH = "it is not a path the room shares (excluded from the grant, or escaping the folder)"

    // --- the status bar -----------------------------------------------------------------------

    const val RECONNECTING = "Reconnecting…"
    const val CONNECTING = "Connecting…"

    fun disconnecting(
        headline: String,
        reading: String,
    ) = "$headline · Disconnecting in $reading"

    fun following(name: String) = "Following $name"
}
