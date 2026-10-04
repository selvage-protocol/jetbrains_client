package dev.dontblameme.selvage.intellij.e2e

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.WindowManager
import dev.dontblameme.selvage.canonical.CanonicalJson
import dev.dontblameme.selvage.canonical.JsonValue
import dev.dontblameme.selvage.intellij.session.RoomSession
import dev.dontblameme.selvage.intellij.session.SelvageService
import dev.dontblameme.selvage.intellij.settings.SelvageSettings
import dev.dontblameme.selvage.intellij.ui.Notifier
import dev.dontblameme.selvage.intellij.ui.Prompts
import dev.dontblameme.selvage.intellij.ui.StatusWidgets
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The end-to-end test's hand inside a real IDE: a socket on the loopback interface, one JSON object
 * per line each way. A command runs the plugin's own actions on the event thread, with the
 * questions they ask answered from the command; `state` reports what the IDE shows: the session's
 * row and widgets, the bound documents, the drawn carets, and every sentence said. It starts only
 * when `selvage.e2e.portFile` names where to write its port.
 */
class Driver : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        val portFile = System.getProperty("selvage.e2e.portFile") ?: return
        if (!started.compareAndSet(false, true)) return
        Notifier.listeners.add { said.add(it.level.name to it.sentence) }
        Notifier.progressListeners.add { progress.add(it) }
        Prompts.current = answers
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        Thread({ serve(server) }, "selvage-e2e-driver").apply { isDaemon = true }.start()
        val target = Path.of(portFile)
        val partial = target.resolveSibling("${target.fileName}.partial")
        Files.writeString(partial, server.localPort.toString())
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun serve(server: ServerSocket) {
        while (true) {
            val socket = server.accept()
            Thread({ talk(socket) }, "selvage-e2e-connection").apply { isDaemon = true }.start()
        }
    }

    private fun talk(socket: Socket) {
        socket.use {
            val reader = it.getInputStream().bufferedReader(StandardCharsets.UTF_8)
            val writer = it.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: return
                val reply =
                    try {
                        val command = CanonicalJson.parseObject(line)
                        // A read may look in while a modal progress runs; a command changes the model,
                        // which the platform allows only outside modal dialogs.
                        val modality =
                            if (command.string("op") ==
                                "state"
                            ) {
                                ModalityState.any()
                            } else {
                                ModalityState.nonModal()
                            }
                        val answer = onEdt(modality) { handle(command) }
                        JsonValue.Obj(mapOf("ok" to JsonValue.Bool(true)) + answer)
                    } catch (e: Throwable) {
                        JsonValue.Obj(mapOf("ok" to JsonValue.Bool(false), "error" to str("$e")))
                    }
                writer.write(CanonicalJson.write(reply))
                writer.write("\n")
                writer.flush()
            }
        }
    }

    private fun <T> onEdt(
        modality: ModalityState,
        work: () -> T,
    ): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait({ result = runCatching(work) }, modality)
        return result!!.getOrThrow()
    }

    private fun handle(command: JsonValue.Obj): Map<String, JsonValue> {
        val service = SelvageService.get()
        return when (val op = command.string("op")) {
            "state" -> {
                state(service)
            }

            "settings" -> {
                SelvageSettings.get().update {
                    command.string("displayName")?.let { displayName = it }
                    command.string("serverUrl")?.let { serverUrl = it }
                    command.string("cursorLabel")?.let { cursorLabel = it }
                }
                emptyMap()
            }

            "action" -> {
                answers.load(command.obj("answers"))
                val id = command.string("id") ?: throw IllegalArgumentException("`action` needs `id`")
                val action =
                    ActionManager.getInstance().getAction(id) ?: throw IllegalArgumentException("no action $id")
                val project = service.current?.project ?: openProject()
                val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
                action.actionPerformed(
                    AnActionEvent.createFromDataContext(
                        ActionPlaces.UNKNOWN,
                        action.templatePresentation.clone(),
                        context,
                    ),
                )
                emptyMap()
            }

            "rename" -> {
                answers.load(JsonValue.Obj(mapOf("inputs" to JsonValue.Arr(listOf(str(command.string("name")!!))))))
                service.displayName(service.current?.project, rename = true)
                emptyMap()
            }

            "openFile" -> {
                val project = service.current?.project ?: openProject() ?: throw IllegalStateException("no project")
                val file =
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(
                        Path.of(project.basePath!!).resolve(command.string("path")!!),
                    ) ?: throw IllegalArgumentException("no such file")
                FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)
                emptyMap()
            }

            "type" -> {
                val session = service.current ?: throw IllegalStateException("no session")
                val path = command.string("path")!!
                val document = session.sync.documentOf(path) ?: throw IllegalStateException("$path is not bound")
                val offset = (command["offset"] as JsonValue.Number).literal.toInt()
                WriteCommandAction.runWriteCommandAction(session.project, "Typing", null, {
                    document.insertString(offset, command.string("text")!!)
                })
                emptyMap()
            }

            "caret" -> {
                val session = service.current ?: throw IllegalStateException("no session")
                val path = command.string("path")!!
                val editor = session.openRoomPath(path) ?: throw IllegalStateException("could not open $path")
                editor.caretModel.moveToOffset((command["offset"] as JsonValue.Number).literal.toInt())
                editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                emptyMap()
            }

            "select" -> {
                val session = service.current ?: throw IllegalStateException("no session")
                val path = command.string("path")!!
                val editor = session.openRoomPath(path) ?: throw IllegalStateException("could not open $path")
                val anchor = (command["anchor"] as JsonValue.Number).literal.toInt()
                val head = (command["head"] as JsonValue.Number).literal.toInt()
                editor.caretModel.moveToOffset(head)
                editor.selectionModel.setSelection(anchor, head)
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                emptyMap()
            }

            "save" -> {
                FileDocumentManager.getInstance().saveAllDocuments()
                emptyMap()
            }

            in Stage.ops -> {
                val project = service.current?.project ?: openProject() ?: throw IllegalStateException("no project")
                Stage.handle(op!!, command, project)
            }

            else -> {
                throw IllegalArgumentException("unknown op $op")
            }
        }
    }

    private fun openProject(): Project? = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }

    private fun state(service: SelvageService): Map<String, JsonValue> {
        val out =
            linkedMapOf<String, JsonValue>(
                "projects" to arr(ProjectManager.getInstance().openProjects.mapNotNull { it.basePath }),
                "said" to
                    JsonValue.Arr(
                        said.map { (level, sentence) ->
                            obj("level" to str(level), "sentence" to str(sentence))
                        },
                    ),
                "progress" to arr(progress.toList()),
                "unexpected" to arr(answers.unexpected.toList()),
                "connecting" to JsonValue.Bool(service.connecting),
            )
        out.putAll(Stage.report())
        out["session"] = service.current?.let { session(it) } ?: JsonValue.Null
        return out
    }

    private fun session(session: RoomSession): JsonValue {
        val project = session.project
        val manager = FileEditorManager.getInstance(project)
        val selected = manager.selectedTextEditor
        val bar = WindowManager.getInstance().getStatusBar(project)
        // A control's words, when the status bar holds it.
        val shown = { id: String, text: String? ->
            if (bar?.getWidget(id) != null &&
                text != null
            ) {
                str(text)
            } else {
                JsonValue.Null
            }
        }
        val widgets =
            mapOf(
                "session" to shown(StatusWidgets.SESSION_ID, StatusWidgets.sessionText(project)),
                "invite" to shown(StatusWidgets.INVITE_ID, StatusWidgets.inviteText(project)),
                "follow" to shown(StatusWidgets.FOLLOW_ID, StatusWidgets.followText(project)),
            )
        return obj(
            "host" to JsonValue.Bool(session.isHost),
            "role" to str(session.role.wire),
            "project" to str(project.basePath ?: ""),
            "mirror" to (
                session.mirror
                    ?.root
                    ?.toString()
                    ?.let(::str) ?: JsonValue.Null
            ),
            "status" to str(session.statusText()),
            "tooltip" to str(session.statusTooltip()),
            "invite" to (session.invite()?.let(::str) ?: JsonValue.Null),
            "wireInvite" to (session.engine.invite?.let(::str) ?: JsonValue.Null),
            "follow" to (session.followLabel()?.let(::str) ?: JsonValue.Null),
            "people" to
                arr(
                    session.rows().map { if (it.description.isEmpty()) it.label else "${it.label}  ${it.description}" },
                ),
            "offered" to arr(session.offered()),
            "documents" to
                JsonValue.Obj(session.sync.paths().associateWith { str(session.sync.documentOf(it)?.text ?: "") }),
            "writable" to
                JsonValue.Obj(
                    session.sync.paths().associateWith {
                        JsonValue.Bool(session.sync.documentOf(it)?.isWritable == true)
                    },
                ),
            "editor" to
                if (selected == null) {
                    JsonValue.Null
                } else {
                    obj(
                        "path" to (session.sync.pathOf(selected.document)?.let(::str) ?: JsonValue.Null),
                        "caret" to JsonValue.Number.of(selected.caretModel.offset.toLong()),
                    )
                },
            "drawn" to
                JsonValue.Obj(
                    session.presence.lastDrawn.mapValues { (_, cursors) ->
                        JsonValue.Arr(
                            cursors.map {
                                obj(
                                    "label" to str(it.label),
                                    "anchor" to JsonValue.Number.of(it.anchor.toLong()),
                                    "head" to JsonValue.Number.of(it.head.toLong()),
                                )
                            },
                        )
                    },
                ),
            "widgets" to JsonValue.Obj(widgets),
        )
    }

    /** The questions a command asks, answered from the command; one the command did not expect is refused and recorded. */
    private class Answers : Prompts {
        val inputs = ArrayDeque<String>()
        val choices = ArrayDeque<String>()
        val confirms = ArrayDeque<Boolean>()
        val unexpected = CopyOnWriteArrayList<String>()

        fun load(answers: JsonValue.Obj?) {
            inputs.clear()
            choices.clear()
            confirms.clear()
            (answers?.get("inputs") as? JsonValue.Arr)?.items?.forEach { inputs.add((it as JsonValue.Str).value) }
            (answers?.get("choices") as? JsonValue.Arr)?.items?.forEach { choices.add((it as JsonValue.Str).value) }
            (answers?.get("confirms") as? JsonValue.Arr)?.items?.forEach { confirms.add((it as JsonValue.Bool).value) }
        }

        override fun input(
            project: Project?,
            title: String,
            prompt: String,
            initial: String,
            validate: (String) -> String?,
        ): String? {
            val answer = inputs.pollFirst()
            if (answer == null) unexpected.add("input: $title")
            return answer
        }

        /** A choice names the row by the text it starts with. */
        override fun choose(
            project: Project?,
            title: String,
            rows: List<String>,
            placeholder: String?,
            closed: () -> Unit,
            chosen: (Int) -> Unit,
        ): Prompts.Chooser {
            val handle =
                object : Prompts.Chooser {
                    override fun refill(
                        title: String,
                        placeholder: String?,
                        rows: List<String>,
                    ) {}

                    override fun close() {}
                }
            val wanted = choices.pollFirst()
            val index = wanted?.let { w -> rows.indexOfFirst { it.startsWith(w) } } ?: -1
            closed()
            if (index < 0) {
                unexpected.add("choose: $title $rows (wanted $wanted)")
            } else {
                chosen(index)
            }
            return handle
        }

        override fun confirm(
            project: Project?,
            message: String,
            yes: String,
        ): Boolean {
            val answer = confirms.pollFirst()
            if (answer == null) unexpected.add("confirm: $message")
            return answer ?: false
        }
    }

    companion object {
        private val started = AtomicBoolean(false)
        private val said = CopyOnWriteArrayList<Pair<String, String>>()
        private val progress = CopyOnWriteArrayList<String>()
        private val answers = Answers()

        private fun str(value: String) = JsonValue.Str(value)

        private fun arr(values: List<String>) = JsonValue.Arr(values.map(::str))

        private fun obj(vararg members: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*members))
    }
}
