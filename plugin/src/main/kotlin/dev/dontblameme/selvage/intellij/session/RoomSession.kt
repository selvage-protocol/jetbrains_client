package dev.dontblameme.selvage.intellij.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import dev.dontblameme.selvage.engine.SelvageSession
import dev.dontblameme.selvage.engine.SessionEnding
import dev.dontblameme.selvage.engine.SessionEvent
import dev.dontblameme.selvage.intellij.bridge.Editing
import dev.dontblameme.selvage.intellij.bridge.Grant
import dev.dontblameme.selvage.intellij.bridge.GrantFolder
import dev.dontblameme.selvage.intellij.bridge.Invites
import dev.dontblameme.selvage.intellij.bridge.Mirror
import dev.dontblameme.selvage.intellij.bridge.PeerColours
import dev.dontblameme.selvage.intellij.bridge.People
import dev.dontblameme.selvage.intellij.bridge.RoomPaths
import dev.dontblameme.selvage.intellij.bridge.Say
import dev.dontblameme.selvage.intellij.bridge.Words
import dev.dontblameme.selvage.intellij.settings.SelvageSettings
import dev.dontblameme.selvage.intellij.ui.Notifier
import dev.dontblameme.selvage.peer.Selection
import dev.dontblameme.selvage.sealed.Role
import dev.dontblameme.selvage.wire.WirePeer
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * One live session bound to one project: the engine on one side, the project's editors on the other.
 * Every engine event is handed to the EDT and handled there, in order, so the session's state is
 * read and written on one thread. Ported from `Session` in `vscode_client/src/adapter/extension.ts`.
 */
class RoomSession(
    val engine: SelvageSession,
    val project: Project,
    /** The host's folder: the grant. Null for a guest or a viewer. */
    val folder: GrantFolder?,
    /** A guest's mirror. Null for a host. */
    val mirror: Mirror?,
    /** The host's listing, read by the engine when it publishes (§13.3). */
    private val hostListing: AtomicReference<List<String>>? = null,
    /** The link a guest joined by, as the person gave it. */
    private val joinedWith: String? = null,
) : Disposable {
    val isHost: Boolean = folder != null
    private val settings = SelvageSettings.get()

    private var peers: List<WirePeer> = engine.peers()
    private var documents: List<String> = engine.openSet()
    private var granted: List<String> = engine.listing()
    private var listedBefore: Set<String> = emptySet()
    private var hostName = ""
    private var reconnecting = false
    private var awayGraceMs = 0L
    private var awayDeadline: Long? = null
    private var copiedUntil = 0L
    private var leaving = false
    var finished = false
        private set
    private var autoOpen = !isHost
    private var viewerSaid = false

    private var followingPeerId: String? = null
    private var followingName = ""
    private var followedPath: String? = null
    private var pendingGoTo: String? = null
    private var landing = 0
    private var expectedEcho: Pair<Editor, Int>? = null

    private val refusedSeeds = HashSet<String>()
    private val unlistedSaid = LinkedHashSet<String>()
    private val unlistedSaved = LinkedHashSet<String>()
    private val dropped = HashSet<String>()

    /** Documents with unsaved edits whose path left the listing: no longer shared, kept on disk while open. */
    private val kept = HashMap<String, Document>()
    private val waitingForText = HashSet<String>()
    private val fetches = HashMap<String, () -> Unit>()
    private var saidWithheld = false
    private var saidOverCapacity = false
    private var listingCut: Grant.Cut? = null

    private val presenceAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val statusAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val copiedAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val grantAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val fetchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val fetchedOnly = HashSet<String>()
    private var keepMirror = false

    /** The open people lists, each drawn again (the key) when membership moves, or closed (the value). */
    val pickers = LinkedHashMap<() -> Unit, () -> Unit>()

    /** Called once the session is over, whichever way it ended. */
    val onFinished = CopyOnWriteArrayList<(RoomSession) -> Unit>()

    /** Who to tell when anything a view draws has moved: the status bar, the participants, the badges. */
    val changed = CopyOnWriteArrayList<() -> Unit>()

    val sync: DocumentSync
    val presence = PresenceRenderer(project)
    private val stopEngine: () -> Unit

    init {
        rememberHost()
        sync =
            DocumentSync(
                project,
                EngineReplica(engine),
                object : DocumentSync.Reports {
                    override fun localEdit(path: String) = localEditEndsFollow()

                    override fun applyRefused(path: String) {
                        Notifier.error(project, Say.applyRefused(path))
                    }

                    override fun divergence(path: String) {
                        Notifier.warn(project, Say.divergence(path))
                    }

                    override fun saveFailed(
                        path: String,
                        why: String?,
                    ) {
                        Notifier.error(
                            project,
                            if (why ==
                                null
                            ) {
                                Say.saveFailed(path)
                            } else {
                                Say.saveFailedBecause(path, why)
                            },
                        )
                    }
                },
                this,
                autoSave = { settings.autoSave },
            )
        stopEngine =
            engine.addListener { event ->
                ApplicationManager.getApplication().invokeLater(
                    { if (!finished) onEvent(event) },
                    ModalityState.nonModal(),
                )
            }
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(
                    source: FileEditorManager,
                    file: VirtualFile,
                ) = opened(file)

                override fun fileClosed(
                    source: FileEditorManager,
                    file: VirtualFile,
                ) = closed(file)

                override fun selectionChanged(event: FileEditorManagerEvent) = schedulePresence()
            },
        )
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) =
                    caretMoved(event.editor, event.caret?.offset ?: 0)
            },
            this,
        )
        multicaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) = schedulePresence()
            },
            this,
        )
        if (isHost) {
            ApplicationManager
                .getApplication()
                .messageBus
                .connect(this)
                .subscribe(
                    com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
                    object : BulkFileListener {
                        override fun after(events: List<VFileEvent>) {
                            val root = folder!!.root.toString()
                            if (events.any {
                                    it.path.startsWith(root) &&
                                        (it !is com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent)
                                }
                            ) {
                                scheduleGrant()
                            }
                        }
                    },
                )
        }
        ApplicationManager
            .getApplication()
            .messageBus
            .connect(this)
            .subscribe(SelvageSettings.CHANGED, SelvageSettings.Listener { settingsChanged() })
        if (!isHost) {
            ApplicationManager
                .getApplication()
                .messageBus
                .connect(this)
                .subscribe(
                    com.intellij.openapi.fileEditor.FileDocumentManagerListener.TOPIC,
                    object : com.intellij.openapi.fileEditor.FileDocumentManagerListener {
                        override fun beforeDocumentSaving(document: Document) {
                            FileDocumentManager.getInstance().getFile(document)?.let(::saving)
                        }
                    },
                )
        }
        for (file in FileEditorManager.getInstance(project).openFiles) opened(file)
        if (!isHost) applyListing(granted, null)
        openFromRoom()
        sayViewerOnce()
        flushPresence()
    }

    // --- reads --------------------------------------------------------------------------------

    val role: Role get() = engine.ownRole() ?: if (isHost) Role.HOST else Role.GUEST

    fun invite(): String? = if (isHost) Invites.pageInviteFor(engine.invite) else joinedWith

    fun displayName(): String = engine.displayName

    fun identity(): String {
        if (isHost) {
            val name = folder!!.root.fileName?.toString() ?: ""
            return if (name == "") Words.SHARED_SESSION_IDENTITY else Words.hostingIdentity(name)
        }
        return Words.guestIdentity(hostName.ifEmpty { null })
    }

    /** The session row's words: the identity, `Reconnecting…`, or the host-away countdown. */
    fun statusText(now: Long = System.currentTimeMillis()): String {
        if (reconnecting) return Say.RECONNECTING
        val deadline = awayDeadline
        if (deadline != null) {
            val reading = Words.disconnectingReading(awayGraceMs.toDouble(), (deadline - now).toDouble())
            return Say.disconnecting(Words.hostLeftSentence(hostName), reading)
        }
        return identity()
    }

    /** The session row's tooltip, in VS Code's words: why it reads what it reads, or who and what is in the room. */
    fun statusTooltip(): String {
        if (reconnecting) return RECONNECTING_TOOLTIP
        if (awayDeadline != null) return Words.hostAwaySentence(hostName, awayGraceMs.toDouble())
        val side =
            when {
                engine.ownRole() == null -> "Waiting for the host in"
                isHost -> "Hosting"
                engine.ownRole() == Role.VIEWER -> "Viewer in"
                else -> "Guest in"
            }
        return listOf(
            "$side this session",
            "In the room: ${summarise(peers.map { it.displayName } + "you")}",
            "Documents the room offers: ${summarise(documents)}",
            "Shared from this window: ${summarise(sync.paths())}",
        ).joinToString("\n")
    }

    fun isHostAway(): Boolean = awayDeadline != null

    fun isReconnecting(): Boolean = reconnecting

    fun inviteLabel(now: Long = System.currentTimeMillis()): String =
        if (now <
            copiedUntil
        ) {
            Words.COPIED_LABEL
        } else {
            Words.COPY_INVITE_LABEL
        }

    fun followLabel(): String? = followingPeerId?.let { Say.following(followingName) }

    fun followingId(): String? = followingPeerId

    fun listed(): List<String> = granted

    /** The room's listing named [path] and the one that replaced it does not: the host stopped sharing it. */
    fun leftListing(path: String): Boolean = path in listedBefore && path !in granted

    fun offered(): List<String> = Grant.grantUnion(granted, documents).filter { it !in dropped }

    fun roomDocuments(): List<String> = engine.documents()

    fun people(): List<People.Person> {
        val paths = HashMap<String, String>()
        val byClient = peers.associateBy { it.awarenessClientId }
        for (cursor in engine.cursors()) {
            val peer = byClient[cursor.clientId] ?: continue
            cursor.path?.let { paths[peer.peerId] = People.captionPath(it) }
        }
        val roles = engine.rolesBySeat()
        val self = People.RoomMember(engine.seat ?: "", engine.displayName, role)
        val others =
            peers.map {
                People.RoomMember(it.peerId, it.displayName, roles[it.peerId] ?: Role.GUEST, paths[it.peerId])
            }
        return People.seatPeople(self, others)
    }

    fun participants(): List<People.Person> = people().filter { !it.self }

    fun rows(): List<People.PersonRow> = People.personRows(people(), followingPeerId)

    fun fileOf(path: String): Path? {
        if (!Grant.isGrantedPath(path)) return null
        mirror?.let { return it.fileOf(path) }
        return folder?.root?.let { RoomPaths.under(it, path) }
    }

    /** Who is in [file], for the project view's badge. */
    fun peopleIn(file: VirtualFile): List<People.Person> {
        val path = pathOf(file) ?: return emptyList()
        return participants().filter { it.path == path }
    }

    // --- the engine's events --------------------------------------------------------------------

    private fun onEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.Seated -> {
                reconnecting = false
            }

            is SessionEvent.Reconnecting -> {
                reconnecting = true
            }

            is SessionEvent.Peers -> {
                peers = event.peers
                rememberHost()
                if (awayDeadline != null && peers.any { engine.rolesBySeat()[it.peerId] == Role.HOST }) clearHostAway()
                pickers.keys.toList().forEach { it() }
                val following = followingPeerId
                if (following != null) {
                    val peer = peers.firstOrNull { it.peerId == following }
                    if (peer ==
                        null
                    ) {
                        stopForLeftPeer()
                    } else {
                        followingName = PeerColours.peerName(peer.displayName, following)
                    }
                }
            }

            is SessionEvent.Listing -> {
                listedBefore = granted.toHashSet()
                granted = event.paths
                if (!isHost) applyListing(event.paths, listedBefore)
            }

            is SessionEvent.OpenSet -> {
                documents = event.paths
                openFromRoom()
            }

            is SessionEvent.RemoteEdits -> {
                for (edit in event.edits) {
                    if (edit.path in waitingForText) arrive(edit.path) else sync.remoteEdit(edit.path, edit.delta)
                    fetches.remove(edit.path)?.invoke()
                }
            }

            is SessionEvent.Presence -> {}

            // A record, not an alarm (PROTOCOL §13.2): the VS Code client shows a person nothing either.
            is SessionEvent.FrameRefused -> {
                log.info("refused frame ${event.frame} (${event.reason}) from ${event.sender ?: "an unknown sender"}")
            }

            is SessionEvent.HostAway -> {
                armHostAway(event.graceMs)
                Notifier.warn(project, Words.hostAwaySentence(hostName, event.graceMs.toDouble()))
            }

            is SessionEvent.HostBack -> {
                clearHostAway()
                Notifier.info(project, Words.hostBackSentence(hostName))
            }

            is SessionEvent.Ended -> {
                ended(event.ending)
                return
            }

            is SessionEvent.Failed -> {
                Notifier.error(project, Invites.sessionErrorSentence(event.error.message, event.error.code))
            }
        }
        for (path in fetches.keys.toList()) if (engine.has(path)) fetches.remove(path)?.invoke()
        sayViewerOnce()
        followTick()
        retryGoTo()
        redrawPresence()
        fireChanged()
    }

    private fun ended(ending: SessionEnding) {
        if (leaving || finished) return
        when (ending) {
            SessionEnding.CONNECTION_LOST -> {
                Notifier.error(project, if (isHost) Say.disconnectedHost() else Say.disconnectedGuest())
                end()
            }

            else -> {
                val sentence = Words.roomGoneSentence(ending.sentence)
                val kept = mirror
                if (kept != null) {
                    Notifier.warn(project, Say.copyKept(sentence, kept.root.toString()))
                    end(keepMirror = true)
                } else {
                    Notifier.warn(project, sentence)
                    end()
                }
            }
        }
    }

    private fun rememberHost() {
        val roles = engine.rolesBySeat()
        val host = peers.firstOrNull { roles[it.peerId] == Role.HOST } ?: return
        hostName = PeerColours.peerName(host.displayName, host.peerId)
    }

    private fun armHostAway(graceMs: Long) {
        awayGraceMs = maxOf(0, graceMs)
        awayDeadline = System.currentTimeMillis() + awayGraceMs
        tickStatus()
    }

    private fun tickStatus() {
        statusAlarm.cancelAllRequests()
        if (awayDeadline == null || finished) return
        fireChanged()
        statusAlarm.addRequest({ tickStatus() }, 1000)
    }

    private fun clearHostAway() {
        awayDeadline = null
        statusAlarm.cancelAllRequests()
    }

    /**
     * §13.4, §13.9: a viewer's documents are read-only, which is how this editor refuses a keystroke
     * the room would not take, and the reason is said once. The role is the applied state's word and
     * can arrive after the join, so it is read again on every event.
     */
    private fun sayViewerOnce() {
        val viewer = !isHost && engine.ownRole() == Role.VIEWER
        sync.viewer = viewer
        if (viewerSaid || !viewer) return
        viewerSaid = true
        Notifier.warn(project, Say.viewerReadOnly())
    }

    // --- documents ----------------------------------------------------------------------------

    fun pathOf(file: VirtualFile): String? {
        val root = (mirror?.root ?: folder?.root) ?: return null
        val base = LocalFileSystem.getInstance().findFileByNioFile(root) ?: return null
        if (!VfsUtilCore.isAncestor(base, file, true)) return null
        return VfsUtilCore.getRelativePath(file, base, '/')
    }

    private fun opened(file: VirtualFile) {
        if (finished) return
        val path = pathOf(file) ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        bindDocument(path, document)
        schedulePresence()
    }

    private fun bindDocument(
        path: String,
        document: Document,
    ) {
        if (sync.isBound(path)) return
        if (isHost) {
            val refusal =
                when {
                    !Grant.isGrantedPath(path) -> Say.NOT_A_ROOM_PATH
                    Grant.overFileBound(document.text) -> Say.refusal(GrantFolder.Refusal.TOO_LARGE)
                    else -> null
                }
            if (refusal != null) {
                if (refusedSeeds.add(path)) Notifier.error(project, Say.wrap(Say.willNotShare(path, refusal) + "."))
                return
            }
            sync.bind(path, document, seed = true)
            return
        }
        val room = mirror ?: return
        if (!room.accepts(path)) return
        // A copy kept when its path left the room was said to be no longer shared; a listing naming
        // the path again does not make its unsaved text the room's.
        if (kept[path] === document) return
        if (path !in granted && path !in documents && !engine.has(path)) {
            if (noteUnlisted(unlistedSaid, path)) Notifier.warn(project, Say.unlistedOpened(path))
            return
        }
        if (!engine.has(path) && document.textLength > 0) {
            waitingForText.add(path)
            engine.open(path)
            return
        }
        sync.bind(path, document, seed = false)
    }

    /**
     * A save writes the mirror file even when the room has no path for it, since the editor writes
     * what it is told to; the sentence is the honest half of that, said once per path.
     */
    private fun saving(file: VirtualFile) {
        if (finished || mirror == null) return
        val path = pathOf(file) ?: return
        if (path == Mirror.MARKER || path in offered()) return
        if (noteUnlisted(unlistedSaved, path)) Notifier.warn(project, Say.unlistedSaved(path))
    }

    /** Once per path, and never more paths remembered than [MAX_UNLISTED_WARNINGS]: the oldest goes first. */
    private fun noteUnlisted(
        warned: LinkedHashSet<String>,
        path: String,
    ): Boolean {
        if (path in warned) return false
        if (warned.size >= MAX_UNLISTED_WARNINGS) warned.remove(warned.first())
        warned.add(path)
        return true
    }

    private fun arrive(path: String) {
        waitingForText.remove(path)
        val file = fileOf(path)?.let { LocalFileSystem.getInstance().findFileByNioFile(it) } ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        sync.bind(path, document, seed = false)
    }

    private fun closed(file: VirtualFile) {
        val path = pathOf(file) ?: return
        if (FileEditorManager.getInstance(project).isFileOpen(file)) return
        kept.entries.removeIf { FileDocumentManager.getInstance().getFile(it.value) == file }
        if (path in fetchedOnly) return
        waitingForText.remove(path)
        sync.unbind(path)
    }

    /**
     * A guest's listing: the documents of paths that left it are ended first, then the mirror is brought
     * to it, keeping on disk what a document of this window still holds.
     */
    private fun applyListing(
        paths: List<String>,
        before: Set<String>?,
    ) {
        val room = mirror ?: return
        val listed = paths.toHashSet()
        dropped.removeAll(listed)
        if (before != null) dropDocuments(before.filter { it !in listed })
        val report = room.republish(paths) { held(it) }
        if (report.withheld.isNotEmpty() && !saidWithheld) {
            saidWithheld = true
            Notifier.info(project, Say.projectSettingsWithheld(report.withheld.joinToString(", ")))
        }
        if (report.overCapacity.isNotEmpty() && !saidOverCapacity) {
            saidOverCapacity = true
            Notifier.warn(
                project,
                if (report.overCapacity.size == 1) {
                    Say.mirrorOverCapacityOne(report.overCapacity.first())
                } else {
                    Say.mirrorOverCapacity(report.overCapacity.size.toString(), report.overCapacity.first())
                },
            )
        }
        if (report.refused.isNotEmpty()) {
            val first = report.refused.first()
            Notifier.warn(
                project,
                if (report.refused.size == 1) {
                    Say.mirrorWriteFailedOne(first)
                } else {
                    Say.mirrorWriteFailed(report.refused.size.toString(), first)
                },
            )
        }
        LocalFileSystem.getInstance().refreshNioFiles(listOf(room.root), true, true, null)
    }

    /** Whether a document of this window still holds [path]: a shared one, or a kept copy still open. */
    private fun held(path: String): Boolean {
        if (sync.isBound(path)) return true
        val file = kept[path]?.let { FileDocumentManager.getInstance().getFile(it) } ?: return false
        return FileEditorManager.getInstance(project).isFileOpen(file)
    }

    /**
     * Ends this window's share of each path in [gone] it has open: the host deleted or moved the file.
     * A document with unsaved edits keeps its editor, since closing it would lose what the person
     * typed, and stops being shared all the same.
     */
    private fun dropDocuments(gone: List<String>) {
        for (path in gone) {
            val document = sync.documentOf(path) ?: continue
            dropped.add(path)
            sync.unbind(path)
            if (FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
                kept[path] = document
                Notifier.warn(project, Say.leftListingKept(path))
            } else {
                FileDocumentManager
                    .getInstance()
                    .getFile(
                        document,
                    )?.let { FileEditorManager.getInstance(project).closeFile(it) }
                Notifier.warn(project, Say.leftListingClosed(path))
            }
        }
    }

    private fun openFromRoom() {
        if (!autoOpen) return
        val path = documents.firstOrNull() ?: return
        autoOpen = false
        if (settings.openOnJoin) openRoomPath(path)
    }

    /** Opens a room path in an editor: the guest's mirror file, or the host's own file under its folder. */
    fun openRoomPath(path: String): Editor? {
        val manager = FileEditorManager.getInstance(project)
        val target =
            if (isHost) {
                if (folder!!.read(path) is GrantFolder.Read.Refused) null else fileOf(path)
            } else {
                mirror?.let { if (it.plainPath(path)) it.fileOf(path) else null }
            }
        if (target == null) {
            Notifier.error(project, Say.couldNotOpen(path, "the path is not one this window shares"))
            return null
        }
        if (!isHost) mirror?.materialise(listOf(path))
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
        if (file == null) {
            Notifier.error(project, Say.couldNotOpen(path, "the file is not on disk"))
            return null
        }
        val selected = manager.selectedTextEditor
        if (selected != null && FileDocumentManager.getInstance().getFile(selected.document) == file) return selected
        return manager.openTextEditor(OpenFileDescriptor(project, file), true)
    }

    // --- fetching -----------------------------------------------------------------------------

    /** Fills [path]'s mirror file from the room, holding it while the text comes; [done] when it is in. */
    fun fetch(
        path: String,
        done: (Boolean) -> Unit,
    ) {
        val room = mirror
        if (room == null || !room.accepts(path) || room.materialise(listOf(path)).mirrored.isEmpty()) {
            Notifier.error(project, Say.couldNotFetch(path, "the file could not be mirrored"))
            done(false)
            return
        }
        val target = room.fileOf(path)
        val file = target?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
        val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
        if (document == null) {
            Notifier.error(project, Say.couldNotFetch(path, "the file could not be mirrored"))
            done(false)
            return
        }
        fetchedOnly.add(path)
        val finish = {
            sync.sync(path)
            FileDocumentManager.getInstance().saveDocumentAsIs(document)
            fetchedOnly.remove(path)
            if (!FileEditorManager.getInstance(project).isFileOpen(file)) sync.unbind(path)
        }
        sync.bind(path, document, seed = false)
        if (engine.has(path)) {
            finish()
            done(true)
            return
        }
        // A read that has to ask the room says so while it waits: without it the file fills when
        // the wait is over and nothing said it was ever loading.
        val waited = java.util.concurrent.CompletableFuture<Unit>()
        lateinit var timeout: Runnable
        val arrived = {
            fetchAlarm.cancelRequest(timeout)
            waited.complete(Unit)
            finish()
            done(true)
        }
        // Text the replica holds at the deadline is the room's too: a first sync need carry no edit.
        timeout =
            Runnable {
                if (fetches.remove(path) == null) return@Runnable
                if (!finished && engine.has(path)) {
                    arrived()
                    return@Runnable
                }
                waited.complete(Unit)
                fetchedOnly.remove(path)
                when {
                    finished -> {}

                    leftListing(path) -> {
                        Notifier.error(project, Say.couldNotFetch(path, Say.leftListingNotice(path)))
                    }

                    else -> {
                        Notifier.warn(project, Say.stillEmpty(path))
                    }
                }
                done(false)
            }
        fetches[path] = arrived
        fetchAlarm.addRequest(timeout, FETCH_TIMEOUT_MS)
        Notifier.progress(project, Say.fetching(path), waited, FETCH_TIMEOUT_MS + 1_000L)
    }

    // --- the host's listing -------------------------------------------------------------------

    private fun scheduleGrant() {
        if (finished) return
        grantAlarm.cancelAllRequests()
        grantAlarm.addRequest({ republishGrant() }, GRANT_REFRESH_MS)
    }

    private fun republishGrant() {
        val walk = folder?.walk() ?: return
        val listing = hostListing ?: return
        val cut = walk.cut
        if (listing.get() != walk.paths) {
            listing.set(walk.paths)
            engine.listingChanged()
        }
        ApplicationManager.getApplication().invokeLater({
            if (finished || cut == listingCut) return@invokeLater
            listingCut = cut
            when (cut) {
                Grant.Cut.PATHS -> {
                    Notifier.warn(project, Say.listingCutPaths())
                }

                Grant.Cut.BYTES -> {
                    Notifier.warn(project, Say.listingCutBytes())
                }

                Grant.Cut.BUDGET -> {
                    Notifier.warn(project, Say.listingCutBudget())
                }

                null -> {}
            }
        }, ModalityState.nonModal())
    }

    /** A host's refusal of a path a peer asked for, said as the bridge says it. */
    fun refusedRead(
        path: String,
        cause: GrantFolder.Refusal,
    ) {
        val said = Say.refusal(cause) ?: return
        ApplicationManager.getApplication().invokeLater({
            if (!finished) Notifier.error(project, Say.wrap(Say.couldNotShare(path, said) + "."))
        }, ModalityState.nonModal())
    }

    // --- presence -----------------------------------------------------------------------------

    private fun schedulePresence() {
        if (finished || presenceAlarm.activeRequestCount > 0) return
        presenceAlarm.addRequest({ flushPresence() }, SELECTION_INTERVAL_MS)
    }

    /** Publishes where this window's caret is, in the replica's offsets, or that it is in no shared document. */
    fun flushPresence() {
        presenceAlarm.cancelAllRequests()
        if (finished) return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val path = editor?.let { sync.pathOf(it.document) }
        if (editor == null || path == null) {
            engine.setCursor(null)
            return
        }
        val caret = editor.caretModel.primaryCaret
        val anchor = if (caret.hasSelection()) caret.leadSelectionOffset else caret.offset
        engine.setCursor(path, Selection(anchor, caret.offset))
    }

    /** The remote carets this window can draw: resolved, attributed and in a bound document. */
    fun remoteCursors(): List<RemoteCursor> {
        val colours = people().associate { it.peerId to it.colour }
        val roles = engine.rolesBySeat()
        val byClient = peers.associateBy { it.awarenessClientId }
        val own = engine.awarenessClientId()
        return engine
            .cursors()
            .mapNotNull { cursor ->
                if (cursor.clientId == own) return@mapNotNull null
                val peer = byClient[cursor.clientId] ?: return@mapNotNull null
                val path = cursor.path ?: return@mapNotNull null
                val selection = cursor.selection ?: return@mapNotNull null
                if (!sync.isBound(path)) return@mapNotNull null
                RemoteCursor(
                    peer.peerId,
                    PeerColours.peerName(peer.displayName, peer.peerId),
                    (roles[peer.peerId] ?: Role.GUEST).wire,
                    path,
                    selection.anchor,
                    selection.head,
                    colours[peer.peerId] ?: PeerColours.peerColour(peer.peerId),
                )
            }.sortedBy { it.peerId }
    }

    fun redrawPresence() {
        if (finished) return
        sync.syncAll()
        presence.render(remoteCursors(), sync::documentOf, settings.cursorLabel)
    }

    private fun settingsChanged() {
        if (finished) return
        redrawPresence()
        val configured = settings.displayName.trim()
        if (Invites.displayNameRefusal(configured) != null || configured == engine.displayName) return
        engine.rename(configured).whenComplete { _, error ->
            if (error == null) return@whenComplete
            ApplicationManager.getApplication().invokeLater({
                Notifier.error(
                    project,
                    Invites.sessionErrorSentence(
                        Say.serverRefusedName(
                            configured,
                            error.cause?.message ?: error.message ?: "$error",
                        ),
                        "error",
                    ),
                )
            }, ModalityState.nonModal())
        }
    }

    // --- follow and go-to ----------------------------------------------------------------------

    private enum class Landing { LANDED, WAITING, REFUSED, GONE, FILE_GONE }

    fun goTo(peerId: String) {
        if (followingPeerId != null) clearFollow()
        pendingGoTo = peerId
        retryGoTo()
    }

    private fun retryGoTo() {
        val peerId = pendingGoTo ?: return
        if (landOn(peerId, follow = false) != Landing.WAITING && pendingGoTo == peerId) pendingGoTo = null
    }

    fun follow(peerId: String) {
        if (followingPeerId == peerId) {
            followTick()
            return
        }
        followingPeerId = peerId
        followedPath = null
        followingName = label(peerId)
        pendingGoTo = null
        fireChanged()
        followTick()
    }

    /** Stop following, or say there is nothing to stop. */
    fun stopFollowing() {
        if (followingPeerId == null) {
            Notifier.warn(project, Say.notFollowing())
            return
        }
        clearFollow()
    }

    private fun followTick() {
        val peerId = followingPeerId ?: return
        when (landOn(peerId, follow = true)) {
            Landing.GONE -> {
                if (followingPeerId == peerId) stopForLeftPeer()
            }

            Landing.FILE_GONE -> {
                val name = followingName
                clearFollow()
                Notifier.info(project, Words.followEndedByFileGone(name))
            }

            else -> {}
        }
    }

    private fun landOn(
        peerId: String,
        follow: Boolean,
    ): Landing {
        val peer = peers.firstOrNull { it.peerId == peerId }
        val cursor = peer?.let { p -> engine.cursors().firstOrNull { it.clientId == p.awarenessClientId } }
        if (cursor == null) {
            if (peer != null) return Landing.WAITING
            if (!follow) {
                Notifier.warn(project, Say.nothingToGoTo(label(peerId)))
                return Landing.REFUSED
            }
            return Landing.GONE
        }
        if (follow) followingName = PeerColours.peerName(peer.displayName, peerId)
        val path = cursor.path
        if (path != null && follow && path == followedPath && path in dropped) return Landing.FILE_GONE
        if (path == null || path in dropped) return Landing.WAITING
        val editor = openRoomPath(path) ?: return Landing.REFUSED
        val selection = cursor.selection
        if (selection == null) {
            if (!engine.has(path)) return Landing.WAITING
            if (!follow) Notifier.warn(project, Say.caretDoesNotResolve(label(peerId)))
            return Landing.REFUSED
        }
        sync.sync(path)
        val head = selection.head.coerceIn(0, editor.document.textLength)
        landing += 1
        try {
            editor.caretModel.removeSecondaryCarets()
            editor.selectionModel.removeSelection()
            editor.caretModel.moveToOffset(head)
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        } finally {
            landing -= 1
        }
        expectedEcho = editor to head
        if (follow) followedPath = path
        schedulePresence()
        return Landing.LANDED
    }

    private fun caretMoved(
        editor: Editor,
        offset: Int,
    ) {
        if (finished) return
        if (sync.pathOf(editor.document) != null) schedulePresence()
        if (landing > 0 || followingPeerId == null || sync.applying) return
        if (editor.project != project || editor != FileEditorManager.getInstance(project).selectedTextEditor) return
        val expected = expectedEcho
        expectedEcho = null
        if (expected != null && expected.first == editor && expected.second == offset) return
        val name = followingName
        clearFollow()
        Notifier.info(project, Words.followEndedByMoving(name))
    }

    private fun localEditEndsFollow() {
        if (followingPeerId == null) return
        val name = followingName
        clearFollow()
        Notifier.info(project, Words.followEndedByTyping(name))
    }

    private fun stopForLeftPeer() {
        if (followingPeerId == null) return
        val name = followingName
        clearFollow()
        Notifier.info(project, Words.followEndedByLeaving(name))
    }

    private fun clearFollow() {
        followingPeerId = null
        followedPath = null
        expectedEcho = null
        fireChanged()
    }

    private fun label(peerId: String): String {
        val peer = peers.firstOrNull { it.peerId == peerId }
        return PeerColours.peerName(peer?.displayName ?: "", peerId)
    }

    // --- the invite control -------------------------------------------------------------------

    /** The invite control reads `Copied` for as long as the web's pill stands. */
    fun showCopied() {
        copiedUntil = System.currentTimeMillis() + Words.COPIED_STAND_MS
        copiedAlarm.cancelAllRequests()
        copiedAlarm.addRequest({ fireChanged() }, Words.COPIED_STAND_MS)
        fireChanged()
    }

    // --- leaving ------------------------------------------------------------------------------

    /** A host's leave: the closing goes out first so every guest hears the room ended. */
    fun closeRoom() {
        leaving = true
        if (isHost) engine.closeRoom()
    }

    fun fireChanged() {
        changed.forEach { it() }
    }

    /** Ends the session: the editors let go, the engine left, the mirror removed unless [keepMirror]. */
    fun end(keepMirror: Boolean = false) {
        this.keepMirror = keepMirror
        Disposer.dispose(this)
    }

    override fun dispose() {
        if (finished) return
        finished = true
        leaving = true
        stopEngine()
        // A list left open would offer people in a room this window has left.
        pickers.values.toList().forEach { it() }
        presence.clear()
        sync.viewer = false
        engine.leave()
        if (mirror != null && !keepMirror) {
            val files = FileEditorManager.getInstance(project).openFiles
            for (file in files) if (pathOf(file) != null) FileEditorManager.getInstance(project).closeFile(file)
            mirror.remove()
            // A window opened on the mirror would be left on a folder that is gone; a kept copy keeps its window.
            if (project.basePath?.let { Path.of(it) } == mirror.root) {
                ApplicationManager.getApplication().invokeLater(
                    {
                        if (!project.isDisposed) {
                            com.intellij.openapi.project.ProjectManager
                                .getInstance()
                                .closeAndDispose(
                                    project,
                                )
                        }
                    },
                    ModalityState.nonModal(),
                )
            }
        }
        onFinished.forEach { it(this) }
        changed.forEach { it() }
    }

    private class EngineReplica(
        private val engine: SelvageSession,
    ) : Replica {
        override fun text(path: String): String = engine.text(path)

        override fun has(path: String): Boolean = engine.has(path)

        override fun replaceIf(
            path: String,
            expected: String,
            start: Int,
            end: Int,
            text: String,
        ): Boolean = engine.replaceIf(path, expected, start, end - start, text)

        override fun hold(path: String) = engine.open(path)

        override fun release(path: String) = engine.release(path)
    }

    companion object {
        const val SELECTION_INTERVAL_MS = 100
        const val GRANT_REFRESH_MS = 250
        const val FETCH_TIMEOUT_MS = 5000

        const val RECONNECTING_TOOLTIP = "The connection dropped; trying to rejoin the room."

        /** How many names a tooltip lists before it counts the rest: the room's lists are a stranger's input. */
        const val MAX_TOOLTIP_ENTRIES = 20

        fun summarise(names: List<String>): String {
            if (names.isEmpty()) return "none"
            val shown = names.take(MAX_TOOLTIP_ENTRIES).joinToString(", ")
            return if (names.size >
                MAX_TOOLTIP_ENTRIES
            ) {
                "$shown, … and ${names.size - MAX_TOOLTIP_ENTRIES} more"
            } else {
                shown
            }
        }

        /** How many paths the not-in-the-room notices remember (`MAX_UNLISTED_WARNINGS`). */
        const val MAX_UNLISTED_WARNINGS = 500
    }
}

private val log = Logger.getInstance(RoomSession::class.java)
