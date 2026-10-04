package dev.dontblameme.selvage.intellij.session

import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.util.Alarm
import dev.dontblameme.selvage.engine.HostContent
import dev.dontblameme.selvage.engine.SelvageSession
import dev.dontblameme.selvage.engine.SessionOptions
import dev.dontblameme.selvage.intellij.bridge.Grant
import dev.dontblameme.selvage.intellij.bridge.GrantFolder
import dev.dontblameme.selvage.intellij.bridge.Invites
import dev.dontblameme.selvage.intellij.bridge.Mirror
import dev.dontblameme.selvage.intellij.bridge.PeerColours
import dev.dontblameme.selvage.intellij.bridge.People
import dev.dontblameme.selvage.intellij.bridge.Say
import dev.dontblameme.selvage.intellij.bridge.Words
import dev.dontblameme.selvage.intellij.settings.SelvageSettings
import dev.dontblameme.selvage.intellij.ui.Notifier
import dev.dontblameme.selvage.intellij.ui.Prompts
import dev.dontblameme.selvage.intellij.ui.StatusWidgets
import dev.dontblameme.selvage.sealed.Role
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * The session this IDE is in, and the twelve commands, ported from the command functions of
 * `vscode_client/src/adapter/extension.ts`. One session at a time, as one per VS Code window: a
 * second host or join asks to leave the first.
 */
@Service(Service.Level.APP)
class SelvageService : Disposable {
    var current: RoomSession? = null
        private set

    /** True from the dial until the seat: the status bar's `Connecting…`. */
    var connecting = false
        private set

    /** Where guests' mirrors are minted: `<system>/selvage/rooms/<room>/<window>`. */
    var storage: Path = PathManager.getSystemDir().resolve("selvage")

    /** The engine's options for a display name; a test shortens the clocks here. */
    var sessionOptions: (String) -> SessionOptions = { SessionOptions(it, client = CLIENT) }

    /** Opens a guest's mirror as a project; a test attaches it to a project it already has. */
    var openMirror: (Path) -> Project? = ::openMirrorProject

    /** Who to tell when [current] or [connecting] changes. */
    val sessionChanged = CopyOnWriteArrayList<() -> Unit>()

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        sessionChanged.add {
            for (open in ProjectManager.getInstance().openProjects) {
                StatusWidgets.refresh(open)
                if (!open.isDisposed) ProjectView.getInstance(open).refresh()
            }
        }
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ProjectManager.TOPIC,
            object : ProjectManagerListener {
                override fun projectClosing(project: Project) {
                    val session = current ?: return
                    if (session.project != project) return
                    session.closeRoom()
                    session.end()
                }
            },
        )
    }

    fun sessionFor(project: Project?): RoomSession? = current?.takeIf { project == null || it.project == project }

    private fun settings() = SelvageSettings.get()

    private fun prompts() = Prompts.current

    private fun fire() = sessionChanged.forEach { it() }

    private fun adopt(session: RoomSession) {
        current = session
        session.changed.add { fire() }
        session.onFinished.add { if (current === it) current = null }
        session.onFinished.add { fire() }
        fire()
    }

    // --- Host a session --------------------------------------------------------------------------

    fun host(project: Project) {
        val inSession = current
        if (inSession != null && inSession.isHost) {
            when (val reach = reachForInvite()) {
                is Reach.Copied -> Notifier.info(project, Say.alreadyHostingCopied())
                is Reach.Refused -> Notifier.warn(project, Say.alreadyHostingRefused(reach.why))
                else -> Notifier.warn(project, Say.alreadyHostingNoInvite())
            }
            return
        }
        if (inSession != null) {
            if (!prompts().confirm(project, Say.hostWarning(), "Leave and host")) return
            inSession.end()
        }
        val base = project.basePath?.let { Path.of(it) }
        if (base == null || !base.toFile().isDirectory) {
            Notifier.warn(project, Say.openFolderFirst())
            return
        }
        val resolved = resolveServerUrl(project) ?: return
        val (address, fromMemory) = resolved
        Invites.serverAddressRefusal(address)?.let {
            Notifier.error(project, Say.wrap(it))
            return
        }
        settings().update { lastServer = address }
        val name = resolveDisplayName(project) ?: return
        val folder = GrantFolder(base)
        val listing = AtomicReference<List<String>>(emptyList())
        val sessionHolder = AtomicReference<RoomSession?>()
        val engine =
            connect(project, Say.connecting(address)) {
                listing.set(folder.walk().paths)
                SelvageSession.host(
                    address,
                    HostContent(
                        { listing.get() },
                        { path ->
                            when (val read = folder.read(path)) {
                                is GrantFolder.Read.Text -> {
                                    if (Grant.overFileBound(read.text)) {
                                        sessionHolder.get()?.refusedRead(path, GrantFolder.Refusal.TOO_LARGE)
                                        null
                                    } else {
                                        read.text
                                    }
                                }

                                is GrantFolder.Read.Refused -> {
                                    sessionHolder.get()?.refusedRead(path, read.cause)
                                    null
                                }
                            }
                        },
                    ),
                    sessionOptions(name),
                )
            }
        if (engine is Connected.Failed) {
            val why = Invites.connectRefusal(engine.error, Invites.HOST_CHECK)
            if (fromMemory) {
                Notifier.error(
                    project,
                    Say.couldNotHost(address, why),
                    Say.CHANGE_SERVER to { offerServerChange(project, address) },
                )
            } else {
                Notifier.error(project, Say.couldNotHost(address, why))
            }
            return
        }
        val session = RoomSession((engine as Connected.Ok).engine, project, folder, null, listing)
        sessionHolder.set(session)
        adopt(session)
        when (val reach = reachForInvite()) {
            is Reach.Refused -> {
                Notifier.warn(project, Say.roomOpenRefused(reach.why))
            }

            is Reach.Copied -> {
                val copyAgain = Say.COPY_AGAIN to { copyInvite(project) }
                if (fromMemory) {
                    Notifier.info(
                        project,
                        Say.roomOpenOn(address),
                        copyAgain,
                        Say.CHANGE_SERVER to { offerServerChange(project, address) },
                    )
                } else {
                    Notifier.info(project, Say.roomOpen(), copyAgain)
                }
            }

            else -> {
                Notifier.warn(project, Say.roomOpenNoInvite())
            }
        }
    }

    // --- Join a session from an invite link --------------------------------------------------------

    fun join(
        project: Project?,
        given: String? = null,
    ) {
        val inSession = current
        if (inSession != null) {
            val warning = if (inSession.isHost) Say.joinWarningHost() else Say.joinWarningGuest()
            if (!prompts().confirm(project, warning, "Leave and join")) return
        }
        val invite =
            (given ?: prompts().input(project, Say.JOIN_TITLE, Say.JOIN_PROMPT, "") { Invites.inviteLinkRefusal(it) })
                ?.trim()
        if (invite.isNullOrEmpty()) return
        Invites.inviteLinkRefusal(invite)?.let {
            Notifier.error(project, Say.wrap(it))
            return
        }
        val name = resolveDisplayName(project) ?: return
        if (inSession != null) {
            inSession.closeRoom()
            inSession.end()
        }
        val fragment = Invites.fragmentOf(invite)
        val wire = Invites.wireInviteFor(invite)
        val connected =
            connect(project, Say.connecting(Invites.sessionAddress(wire))) {
                SelvageSession.join("$wire$fragment", sessionOptions(name))
            }
        if (connected is Connected.Failed) {
            Notifier.error(project, Say.couldNotJoin(Invites.connectRefusal(connected.error, Invites.JOIN_CHECK)))
            return
        }
        val engine = (connected as Connected.Ok).engine
        val mirror =
            try {
                Mirror.mint(storage, engine.roomId ?: Invites.parseSessionUrl(wire)?.room ?: "room").also { minted ->
                    ApplicationManager.getApplication().executeOnPooledThread {
                        Mirror.pruneRoom(storage, minted.room, minted.window)
                    }
                }
            } catch (e: Exception) {
                engine.leave()
                Notifier.error(project, Say.couldNotOpenRoomFolder(e.message ?: e.toString()))
                return
            }
        val room =
            try {
                openMirror(mirror.root)
            } catch (e: Exception) {
                null
            }
        if (room == null) {
            engine.leave()
            mirror.remove()
            Notifier.error(project, Say.couldNotOpenRoomFolder("the IDE did not open it"))
            return
        }
        val session = RoomSession(engine, room, null, mirror, joinedWith = invite)
        adopt(session)
        whenSettled(session) { sayJoined(session) }
    }

    /** Waits for the room's first listing or open set, at most [timeoutMs], before [then]. */
    private fun whenSettled(
        session: RoomSession,
        timeoutMs: Int = 750,
        then: () -> Unit,
    ) {
        var done = false
        lateinit var listener: () -> Unit
        val finish = {
            if (!done) {
                done = true
                session.changed.remove(listener)
                if (!session.finished) then()
            }
        }
        listener = { if (session.listed().isNotEmpty() || session.roomDocuments().isNotEmpty()) finish() }
        if (session.listed().isNotEmpty() || session.roomDocuments().isNotEmpty()) {
            finish()
            return
        }
        session.changed.add(listener)
        alarm.addRequest({ finish() }, timeoutMs)
    }

    private fun sayJoined(session: RoomSession) {
        val project = session.project
        val documents = Grant.grantUnion(session.listed(), session.roomDocuments())
        val first = documents.firstOrNull()
        val open = Say.OPEN_DOCUMENT to { openDocument(project) }
        when {
            first == null -> {
                Notifier.info(project, Say.joinedEmpty())
            }

            !settings().openOnJoin -> {
                Notifier.info(project, Say.joined(), open)
            }

            documents.size > 1 -> {
                Notifier.info(project, Say.joinedOpeningMore(first, Say.moreFiles(documents.size - 1)), open)
            }

            else -> {
                Notifier.info(project, Say.joinedOpening(first))
            }
        }
    }

    // --- Copy the invite link ------------------------------------------------------------------

    private sealed interface Reach {
        data object NoSession : Reach

        data object NoInvite : Reach

        data class Refused(
            val why: String,
        ) : Reach

        data class Copied(
            val invite: String,
        ) : Reach
    }

    private fun reachForInvite(): Reach {
        val session = current ?: return Reach.NoSession
        val invite = session.invite() ?: return Reach.NoInvite
        return try {
            CopyPasteManager.getInstance().setContents(StringSelection(invite))
            Reach.Copied(invite)
        } catch (e: IllegalStateException) {
            Reach.Refused(e.message ?: e.toString())
        }
    }

    fun copyInvite(project: Project?) {
        when (val reach = reachForInvite()) {
            Reach.NoSession -> Notifier.warn(project, Say.noInvite())
            Reach.NoInvite -> Notifier.warn(project, Say.sessionHoldsNoInvite())
            is Reach.Refused -> Notifier.warn(project, Say.inviteNotCopied(reach.why))
            is Reach.Copied -> current?.showCopied()
        }
    }

    // --- Open a document from the room ------------------------------------------------------------

    fun openDocument(project: Project?) {
        val session = current
        if (session == null) {
            Notifier.warn(project, Say.joinFirst())
            return
        }
        if (session.isHost) {
            Notifier.info(project, Say.youAreHost())
            return
        }
        val paths = session.offered()
        if (paths.isEmpty()) {
            Notifier.info(project, Say.noOneHasFile())
            return
        }
        if (paths.size == 1) {
            session.openRoomPath(paths.first())
            return
        }
        prompts().choose(session.project, Say.OPEN_DOCUMENT, paths, "${paths.size} open in this room") {
            session.openRoomPath(paths[it])
        }
    }

    // --- Download a file from the room ---------------------------------------------------------

    fun fetch(
        project: Project?,
        wanted: String? = null,
    ) {
        val session = current
        if (session == null) {
            Notifier.warn(project, Say.joinFirst())
            return
        }
        if (session.isHost) {
            Notifier.info(project, Say.fetchWhileHosting())
            return
        }
        val listed = session.listed()
        val trimmed = (wanted ?: "").trim().trimEnd('/')
        if (trimmed != "") {
            val targets =
                if (trimmed in listed) {
                    listOf(trimmed)
                } else {
                    listed.filter { it.startsWith("$trimmed/") }
                }
            if (targets.isEmpty()) {
                if (session.leftListing(trimmed)) {
                    Notifier.error(project, Say.couldNotFetch(trimmed, Say.leftListingNotice(trimmed)))
                } else {
                    Notifier.warn(project, Say.noListedMatch(trimmed))
                }
                return
            }
            if (targets.size > MAX_FETCH_ALL_PATHS) {
                Notifier.error(
                    project,
                    Say.tooManyUnder(targets.size.toString(), trimmed, MAX_FETCH_ALL_PATHS.toString()),
                )
                return
            }
            fetchAll(session, targets)
            return
        }
        if (listed.isEmpty()) {
            Notifier.info(project, Say.nothingToFetch())
            return
        }
        if (listed.size > MAX_FETCH_ALL_PATHS) {
            Notifier.error(project, Say.tooManyAll(listed.size.toString(), MAX_FETCH_ALL_PATHS.toString()))
            return
        }
        val whole = "${Say.FETCH_WHOLE} (${if (listed.size == 1) "1 file" else "${listed.size} files"})"
        prompts().choose(
            session.project,
            Say.FETCH,
            listOf(whole) + listed,
            "${listed.size} listed in this room",
        ) { index ->
            if (index == 0) {
                if (prompts().confirm(session.project, Say.fetchAllQuestion(listed.size.toString()), Say.FETCH_WHOLE)) {
                    fetchAll(session, listed)
                }
            } else {
                fetchAll(session, listOf(listed[index - 1]))
            }
        }
    }

    private fun fetchAll(
        session: RoomSession,
        targets: List<String>,
    ) {
        val project = session.project
        val fresh = targets.filter { !session.engine.has(it) }
        if (fresh.isNotEmpty()) {
            Notifier.info(
                project,
                if (targets.size ==
                    1
                ) {
                    Say.fetchingOpensOne(targets.first())
                } else {
                    Say.fetchingOpensMany()
                },
            )
        }
        var left = targets.size
        var failures = 0
        for (target in targets) {
            session.fetch(target) { ok ->
                if (!ok) failures += 1
                left -= 1
                if (left == 0 && failures == 0 &&
                    targets.all { session.engine.has(it) }
                ) {
                    Notifier.info(project, Say.fetched())
                }
            }
        }
    }

    // --- Leave the session ---------------------------------------------------------------------

    fun leave(project: Project?) {
        val session = current
        if (session == null) {
            Notifier.warn(project, Say.notInSession())
            return
        }
        if (session.isHost) {
            if (!prompts().confirm(session.project, Say.HOST_LEAVE_ASKING, Words.LEAVE_ASKING_LABEL) ||
                current !== session
            ) {
                return
            }
            session.closeRoom()
        }
        session.end()
        Notifier.info(project ?: session.project, Say.leftSession())
    }

    // --- Set the name other participants see ------------------------------------------------------

    private fun nameInForce(): String? {
        current?.displayName()?.takeIf { it.isNotEmpty() }?.let { return it }
        settings()
            .displayName
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        return settings().state.lastDisplayName?.takeIf { Invites.displayNameRefusal(it) == null }
    }

    private fun withinBound(
        project: Project?,
        raw: String,
    ): String? {
        val name = raw.trim()
        Invites.displayNameRefusal(name)?.let {
            Notifier.error(project, Say.wrap(it))
            return null
        }
        return name
    }

    private fun resolveDisplayName(project: Project?): String? {
        val configured = settings().displayName.trim()
        if (configured == "") {
            settings()
                .state.lastDisplayName
                ?.takeIf { Invites.displayNameRefusal(it) == null }
                ?.let { return it }
        } else {
            withinBound(project, configured)?.let { return it }
        }
        val answer =
            prompts().input(
                project,
                Say.NAME_TITLE,
                Say.namePrompt(null),
                configured.ifEmpty { System.getenv("USER") ?: System.getenv("USERNAME") ?: "" },
            ) { Invites.displayNameRefusal(it) } ?: return null
        val name = withinBound(project, answer) ?: return null
        settings().update { lastDisplayName = name }
        return name
    }

    fun displayName(
        project: Project?,
        rename: Boolean = false,
    ) {
        val currentName = nameInForce()
        if (!rename) {
            val reported = if (currentName == null) Say.noDisplayName() else Say.nameOthersSee(currentName)
            Notifier.info(project, reported, Say.CHANGE_THE_NAME to { displayName(project, rename = true) })
            return
        }
        val answer =
            prompts().input(
                project,
                Say.DISPLAY_NAME,
                Say.namePrompt(currentName),
                currentName ?: System.getenv("USER") ?: "",
            ) { Invites.displayNameRefusal(it) } ?: return
        val name = withinBound(project, answer) ?: return
        settings().update {
            displayName = name
            lastDisplayName = name
        }
        Notifier.info(project, Say.displayNameSet(name))
    }

    // --- Change the server ---------------------------------------------------------------------

    private fun resolveServerUrl(project: Project?): Pair<String, Boolean>? {
        val configured = Invites.normaliseServerUrl(settings().serverUrl)
        if (configured != "") return configured to false
        val remembered = Invites.normaliseServerUrl(settings().state.lastServer ?: "")
        if (remembered != "") return remembered to true
        val answer =
            prompts().input(project, Say.SERVER_TITLE, Say.SERVER_PROMPT, Invites.DEFAULT_SERVER_URL) {
                Invites.serverAddressRefusal(it)
            }
        val trimmed = Invites.normaliseServerUrl(answer ?: "")
        return if (trimmed == "") null else trimmed to false
    }

    fun changeServer(
        project: Project?,
        given: String? = null,
    ) {
        val configured = Invites.normaliseServerUrl(settings().serverUrl)
        if (configured != "") {
            Notifier.info(project, Say.settingFixesServer(configured))
            return
        }
        val address = Invites.normaliseServerUrl(given ?: "")
        if (address != "") {
            writeServer(project, address)
            return
        }
        val remembered = Invites.normaliseServerUrl(settings().state.lastServer ?: "").ifEmpty { null }
        val reported = if (remembered == null) Say.noServerRemembered() else Say.nextHostUses(remembered)
        Notifier.info(
            project,
            reported,
            Say.CHANGE_SERVER to { offerServerChange(project, remembered ?: Invites.DEFAULT_SERVER_URL) },
        )
    }

    private fun offerServerChange(
        project: Project?,
        current: String,
    ) {
        val answer =
            prompts().input(
                project,
                Say.SERVER_TITLE,
                Say.SERVER_PROMPT,
                current,
            ) { Invites.serverAddressRefusal(it) }
        val address = Invites.normaliseServerUrl(answer ?: "")
        if (address == "" || address == current) return
        writeServer(project, address)
    }

    private fun writeServer(
        project: Project?,
        value: String,
    ) {
        Invites.serverAddressRefusal(value)?.let {
            Notifier.error(project, Say.wrap(it))
            return
        }
        val address = Invites.normaliseServerUrl(value)
        settings().update { lastServer = address }
        Notifier.info(project, Say.willHostOn(address))
    }

    // --- people --------------------------------------------------------------------------------

    fun peers(project: Project?) {
        val session = current
        if (session == null) {
            Notifier.warn(project, Say.joinFirst())
            return
        }
        chooseLive(session, {
            Menu(session.identity(), People.EVERYONE_LABEL, session.rows().map { rowText(it) to it })
        }) { row ->
            chooseLive(session, {
                session.rows().firstOrNull { it.peerId == row.peerId }?.let { now ->
                    Menu(
                        if (now.self) "${now.label} ${People.YOU_MARK}" else now.label,
                        People.whereLine(now.self, now.path),
                        People.personActs(now).map { it.label to it },
                    )
                }
            }) { act(session, row.peerId, it) }
        }
    }

    fun act(
        session: RoomSession,
        peerId: String,
        act: People.Act,
    ) {
        when (act) {
            People.Act.GO_TO -> session.goTo(peerId)
            People.Act.FOLLOW -> session.follow(peerId)
            People.Act.STOP_FOLLOWING -> session.stopFollowing()
            People.Act.RENAME -> displayName(session.project, rename = true)
        }
    }

    private fun rowText(row: People.PersonRow): String =
        if (row.description.isEmpty()) row.label else "${row.label}  ${row.description}"

    /** What a live list shows: its title, the line under it, and each row with what picking it means. */
    private class Menu<T>(
        val title: String,
        val placeholder: String?,
        val rows: List<Pair<String, T>>,
    )

    /**
     * A list that follows the room: drawn again on every membership change while it is open, closed
     * when it has nothing left to offer or the session ends.
     */
    private fun <T> chooseLive(
        session: RoomSession,
        menuOf: () -> Menu<T>?,
        chosen: (T) -> Unit,
    ) {
        var menu = menuOf()?.takeIf { it.rows.isNotEmpty() } ?: return
        var handle: Prompts.Chooser? = null
        val fill: () -> Unit = {
            val next = menuOf()
            if (next == null || next.rows.isEmpty()) {
                handle?.close()
            } else {
                menu = next
                handle?.refill(next.title, next.placeholder, next.rows.map { it.first })
            }
        }
        session.pickers[fill] = { handle?.close() }
        handle =
            prompts().choose(
                session.project,
                menu.title,
                menu.rows.map { it.first },
                menu.placeholder,
                closed = { session.pickers.remove(fill) },
            ) { index ->
                menu.rows
                    .getOrNull(index)
                    ?.second
                    ?.let(chosen)
            }
    }

    private fun pickParticipant(
        project: Project?,
        title: String,
        follow: Boolean,
        then: (String) -> Unit,
    ) {
        val session = current
        if (session == null) {
            Notifier.warn(project, Say.joinFirst())
            return
        }
        val participants = session.participants()
        if (participants.isEmpty()) {
            Notifier.warn(project, Say.noOtherParticipants())
            return
        }
        val chosen = { peerId: String, path: String? ->
            if (path == null) {
                val name =
                    PeerColours.peerName(
                        session.participants().firstOrNull { it.peerId == peerId }?.displayName ?: "",
                        peerId,
                    )
                Notifier.warn(project, if (follow) Say.nothingToFollow(name) else Say.nothingToGoTo(name))
            } else {
                then(peerId)
            }
        }
        if (participants.size == 1) {
            val only = participants.first()
            chosen(only.peerId, only.path)
            return
        }
        chooseLive(session, {
            Menu(
                title,
                PICK_A_PARTICIPANT,
                People.personRows(session.participants(), session.followingId()).map {
                    rowText(it) to
                        it
                },
            )
        }) { row -> chosen(row.peerId, row.path) }
    }

    fun goToParticipant(project: Project?) = pickParticipant(project, Say.GO_TO, follow = false) { current?.goTo(it) }

    fun followParticipant(project: Project?) =
        pickParticipant(project, Say.FOLLOW, follow = true) { current?.follow(it) }

    fun stopFollowing(project: Project?) {
        val session = current
        if (session == null) {
            Notifier.warn(project, Say.joinFirst())
            return
        }
        session.stopFollowing()
    }

    // --- connecting ----------------------------------------------------------------------------

    private sealed interface Connected {
        data class Ok(
            val engine: SelvageSession,
        ) : Connected

        data class Failed(
            val error: Throwable,
        ) : Connected
    }

    private fun connect(
        project: Project?,
        title: String,
        work: () -> SelvageSession,
    ): Connected {
        connecting = true
        fire()
        try {
            return Connected.Ok(
                ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    ThrowableComputable<SelvageSession, Exception> { work() },
                    title,
                    false,
                    project,
                ),
            )
        } catch (e: Exception) {
            return Connected.Failed(e)
        } finally {
            connecting = false
            fire()
        }
    }

    override fun dispose() {
        current?.let {
            it.closeRoom()
            it.end()
        }
        current = null
    }

    companion object {
        const val CLIENT = "selvage-jetbrains/0.1.0"

        /** The participant pickers' placeholder. */
        const val PICK_A_PARTICIPANT = "Pick a participant"

        /** The most paths one fetch holds at once (`MAX_FETCH_ALL_PATHS`). */
        const val MAX_FETCH_ALL_PATHS = 100

        fun get(): SelvageService = ApplicationManager.getApplication().getService(SelvageService::class.java)

        fun roleOf(session: RoomSession): Role = session.role

        /**
         * The mirror opens untrusted, in the IDE's safe mode, as VS Code keeps it in Restricted Mode: its
         * build files are the host's text, and joining a room is not agreeing to run what the host wrote.
         */
        private fun openMirrorProject(root: Path): Project? {
            TrustedProjects.setProjectTrusted(root, false)
            return ProjectUtil.openOrImport(root, null, true)
        }
    }
}
