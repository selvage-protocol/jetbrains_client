"""The JetBrains Marketplace screenshots, taken from two real IDEs in one room on a real selvaged.

It reuses the end-to-end test's pieces (`scripts/e2e/two_instance.py`): each IDE has its own
sandbox under `.tmp/screenshots/` and its own Xvfb display, here 1280×800, the size Marketplace
recommends, and the driver plugin stages each scene through the plugin's own commands. Ada hosts
a small Kotlin project written below; Grace joins from the invite. A scene is captured once the
IDE reports smart mode, no background task, nothing modal it did not ask for, and two captures of
the display in a row are the same.

    python3 scripts/screenshots/capture.py <kit.properties> <output directory>

Environment: SELVAGE_SELVAGED (required), SELVAGE_E2E_LIBRARY_PATH (the dev shell sets it).
"""

import subprocess
import sys
import threading
import time
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "e2e"))
import two_instance as e2e  # noqa: E402

e2e.RUN = e2e.ROOT / ".tmp" / "screenshots"
e2e.SCREEN = "1280x800x24"
FOLDER = "taskboard"
BOARD = "src/main/kotlin/taskboard/Board.kt"
TASK = "src/main/kotlin/taskboard/Task.kt"
COMMANDS = 12

PROJECT = {
    "settings.gradle.kts": 'rootProject.name = "taskboard"\n',
    "build.gradle.kts": """plugins {
    kotlin("jvm") version "2.2.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("taskboard.MainKt")
}
""",
    "README.md": """# taskboard

A small kanban board for the terminal: tasks move from To do to Done, and a column refuses a
task once it is at its work-in-progress limit.

    ./gradlew run
""",
    TASK: """package taskboard

import java.time.LocalDate

enum class Status(val title: String) {
    TODO("To do"),
    IN_PROGRESS("In progress"),
    REVIEW("Review"),
    DONE("Done"),
}

data class Task(
    val id: Int,
    val title: String,
    val status: Status = Status.TODO,
    val assignee: String? = null,
    val due: LocalDate? = null,
) {
    fun isOverdue(today: LocalDate): Boolean =
        status != Status.DONE && due != null && due < today
}
""",
    BOARD: """package taskboard

import java.time.LocalDate

/** The board: every task, and how many each column may hold at once. */
class Board(private val limits: Map<Status, Int> = mapOf(Status.IN_PROGRESS to 3, Status.REVIEW to 2)) {
    private val tasks = linkedMapOf<Int, Task>()
    private var nextId = 1

    fun add(title: String, due: LocalDate? = null): Task {
        val task = Task(nextId++, title, due = due)
        tasks[task.id] = task
        return task
    }

    fun column(status: Status): List<Task> = tasks.values.filter { it.status == status }

    fun move(id: Int, to: Status): Result<Task> {
        val task = tasks[id] ?: return Result.failure(NoSuchElementException("no task $id"))
        val limit = limits[to]
        if (limit != null && column(to).size >= limit) {
            return Result.failure(IllegalStateException("${to.title} is full ($limit)"))
        }
        val moved = task.copy(status = to)
        tasks[id] = moved
        return Result.success(moved)
    }

    fun assign(id: Int, person: String): Task? =
        tasks.computeIfPresent(id) { _, task -> task.copy(assignee = person) }

    fun overdue(today: LocalDate = LocalDate.now()): List<Task> =
        tasks.values.filter { it.isOverdue(today) }.sortedBy { it.due }
}
""",
    "src/main/kotlin/taskboard/Main.kt": """package taskboard

import java.time.LocalDate

fun main() {
    val board = Board()
    val today = LocalDate.now()
    board.add("Write the release notes", due = today.plusDays(2))
    board.add("Fix the login redirect", due = today.minusDays(1))
    board.add("Review the caching change")
    board.move(2, Status.IN_PROGRESS)

    for (status in Status.entries) {
        println("${status.title}:")
        board.column(status).forEach { println("  #${it.id} ${it.title}") }
    }
    board.overdue(today).forEach { println("Overdue: ${it.title}") }
}
""",
    "src/test/kotlin/taskboard/BoardTest.kt": """package taskboard

import kotlin.test.Test
import kotlin.test.assertTrue

class BoardTest {
    @Test
    fun `a full column refuses a task`() {
        val board = Board(mapOf(Status.REVIEW to 1))
        board.add("one")
        board.add("two")
        assertTrue(board.move(1, Status.REVIEW).isSuccess)
        assertTrue(board.move(2, Status.REVIEW).isFailure)
    }
}
""",
    # An IntelliJ project of its own, so opening the folder imports nothing; the room never shares `.idea`.
    ".idea/modules.xml": """<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectModuleManager">
    <modules>
      <module fileurl="file://$PROJECT_DIR$/taskboard.iml" filepath="$PROJECT_DIR$/taskboard.iml" />
    </modules>
  </component>
</project>
""",
    ".idea/misc.xml": """<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectRootManager" version="2" languageLevel="JDK_21" project-jdk-name="21" project-jdk-type="JavaSDK" />
</project>
""",
    "taskboard.iml": """<?xml version="1.0" encoding="UTF-8"?>
<module type="JAVA_MODULE" version="4">
  <component name="NewModuleRootManager" inherit-compiler-output="true">
    <exclude-output />
    <content url="file://$MODULE_DIR$">
      <sourceFolder url="file://$MODULE_DIR$/src/main/kotlin" isTestSource="false" />
      <sourceFolder url="file://$MODULE_DIR$/src/test/kotlin" isTestSource="true" />
    </content>
    <orderEntry type="inheritedJdk" />
    <orderEntry type="sourceFolder" forTests="false" />
    <orderEntry type="library" name="KotlinJavaRuntime" level="project" />
  </component>
</module>
""",
}

# Ada adds a line to `move` while Grace has `column(to).size >= limit` selected.
TYPED = "        if (to == task.status) return Result.success(task)\n"
TYPED_AFTER = "        val task = tasks[id] ?: return Result.failure(NoSuchElementException(\"no task $id\"))\n"
SELECTED = "column(to).size >= limit"
STILL_SAMPLES = 3


def write_project(root, ide):
    """The project, with the IDE's own runtime as its JDK and the Kotlin plugin's standard library."""
    kotlinc = ide / "plugins" / "Kotlin" / "kotlinc" / "lib"
    jars = "".join(f'      <root url="jar://{kotlinc / jar}!/" />\n' for jar in ("kotlin-stdlib.jar", "kotlin-test.jar"))
    files = dict(PROJECT)
    files[".idea/libraries/KotlinJavaRuntime.xml"] = (
        '<component name="libraryTable">\n  <library name="KotlinJavaRuntime">\n    <CLASSES>\n'
        f"{jars}    </CLASSES>\n    <JAVADOC />\n    <SOURCES />\n  </library>\n</component>\n"
    )
    for path, text in files.items():
        (root / path).parent.mkdir(parents=True, exist_ok=True)
        (root / path).write_text(text)


def write_jdk_table(name, ide):
    """The host's sandbox knows the IDE's bundled runtime as the JDK named `21`."""
    jbr = ide / "jbr"
    options = e2e.RUN / name / "config" / "options"
    options.mkdir(parents=True, exist_ok=True)
    (options / "jdk.table.xml").write_text(
        '<application>\n  <component name="ProjectJdkTable">\n    <jdk version="2">\n'
        '      <name value="21" />\n      <type value="JavaSDK" />\n'
        f'      <homePath value="{jbr}" />\n      <roots>\n'
        '        <annotationsPath><root type="composite" /></annotationsPath>\n'
        f'        <classPath><root type="composite"><root url="jrt://{jbr}!/java.base" type="simple" /></root></classPath>\n'
        '        <javadocPath><root type="composite" /></javadocPath>\n'
        '        <sourcePath><root type="composite" /></sourcePath>\n'
        "      </roots>\n      <additional />\n    </jdk>\n  </component>\n</application>\n"
    )


def frame(ide):
    return subprocess.run(
        ["import", "-display", ide.display, "-window", "root", "ppm:-"], capture_output=True, timeout=30, check=True
    ).stdout


def settled(ide, modal=False):
    """Smart mode and nothing in the background; no modal dialog unless the scene opens its own."""

    def check():
        state = ide.state()
        return state["smart"] and state["busy"] == 0 and (modal is None or state["modal"] == modal) and state

    return e2e.wait_for(f"{ide.name} settles (smart, idle, modal={modal})", check, timeout=300)


def capture(ide, out):
    """Saves the display once STILL_SAMPLES captures in a row are identical."""
    run = {"last": None, "same": 0}

    def still():
        current = frame(ide)
        run["same"] = run["same"] + 1 if current == run["last"] else 0
        run["last"] = current
        return run["same"] >= STILL_SAMPLES - 1

    e2e.wait_for(f"{ide.name}'s display stops changing", still)
    subprocess.run(["import", "-display", ide.display, "-window", "root", str(out)], timeout=30, check=True)
    e2e.say(f"ok: {out}")


def scene_host_editing(host, guest, out):
    host.ask(op="openFile", path=BOARD)
    host.action("Selvage.Host")
    invite = e2e.session_field(host, "invite", bool, "hosting with an invite")["invite"]
    e2e.session_field(host, "widgets", lambda w: w["session"] == f"Sharing “{FOLDER}”", "the session row")

    guest.action("Selvage.Join", inputs=[invite])
    e2e.session_field(guest, "status", lambda s: s == "In Ada’s session", "the guest is in Ada's session")
    guest.action("Selvage.OpenDocument", choices=[BOARD])
    e2e.session_field(guest, "documents", lambda d: BOARD in d, "Grace opens Board.kt")

    text = host.session()["documents"][BOARD]
    at = text.index(TYPED_AFTER) + len(TYPED_AFTER)
    host.ask(op="type", path=BOARD, offset=at, text=TYPED)
    e2e.session_field(guest, "documents", lambda d: TYPED in d.get(BOARD, ""), "Ada's line reaches Grace")
    host.ask(op="caret", path=BOARD, offset=at + len(TYPED) - 1)
    text = host.session()["documents"][BOARD]
    head = text.index(SELECTED) + len(SELECTED)
    guest.ask(op="select", path=BOARD, anchor=head - len(SELECTED), head=head)
    e2e.session_field(
        host,
        "drawn",
        lambda d: any(c["label"] == "Grace" and c["head"] == head and c["anchor"] != head for c in d.get(BOARD, [])),
        "Grace's selection is drawn in Ada's editor",
    )
    e2e.session_field(host, "people", lambda p: any(r.startswith("Grace") for r in p), "Ada's list has Grace")
    reveal(host, BOARD)
    stage(host)
    capture(host, out)


def scene_participants(host, out):
    def shown():
        reply = host.ask(op="toolWindow", id="Selvage", select="Grace")
        return reply["visible"] and reply["selected"] == "Grace" and len(reply["rows"]) == 2 and reply

    rows = e2e.wait_for("the Participants tool window lists both, Grace selected", shown)["rows"]
    e2e.say(f"ok: the Participants tool window reads {rows}")
    stage(host)

    def menu():
        items = host.ask(op="toolWindow", id="Selvage", select="Grace", menu=True)["menu"]
        return "Go to" in items and "Follow" in items and items

    items = e2e.wait_for("Grace's row offers Go to and Follow", menu)
    e2e.say(f"ok: Grace's row menu reads {items}")
    capture(host, out)
    host.ask(op="menu", path=[])
    host.ask(op="toolWindow", id="Selvage", show=False)


def scene_guest_following(host, guest, out):
    guest.action("Selvage.FollowParticipant")
    e2e.session_field(guest, "widgets", lambda w: w["follow"] == "Following Ada", "the follow control stands")
    target = PROJECT[TASK].index("fun isOverdue") + len("fun ")
    host.ask(op="caret", path=TASK, offset=target)
    e2e.session_field(guest, "editor", lambda e: e is not None and e["path"] == TASK, "the follow brings Grace to Task.kt")
    # Ada reads on; the follow keeps Grace where Ada's caret is.
    target = PROJECT[TASK].index("due < today") + len("due < today")
    host.ask(op="caret", path=TASK, offset=target)
    e2e.session_field(
        guest,
        "editor",
        lambda e: e is not None and e["path"] == TASK and e["caret"] == target,
        "the follow keeps Grace at Ada's caret in Task.kt",
    )
    e2e.session_field(guest, "follow", lambda f: f == "Following Ada", "Grace still follows")
    reveal(guest, TASK)
    stage(guest)
    capture(guest, out)


def scene_actions(host, out):
    host.ask(op="openFile", path=BOARD)
    stage(host)

    seen = {}

    def opened():
        seen["reply"] = host.ask(op="menu", path=["Tools", "Selvage"])
        items = seen["reply"].get("items", [])
        return "missing" not in seen["reply"] and len(items) == COMMANDS and items

    try:
        items = e2e.wait_for("Tools → Selvage lists the twelve commands", opened)
    except e2e.Failure as failure:
        raise e2e.Failure(f"{failure}; the menu answered {seen.get('reply')}")
    e2e.say(f"ok: Tools → Selvage reads {items}")
    capture(host, out)
    host.ask(op="menu", path=[])


def scene_settings(host, out):
    host.ask(op="settingsPage")
    e2e.wait_for(
        "the Settings dialog is open",
        lambda: any(":Settings" in w for w in host.state()["windows"]),
    )
    # The settings window is the IDE's own, and whether it counts as modal is the platform's business.
    settled(host, modal=None)
    capture(host, out)


def reveal(ide, path):
    """The project view opened down to the file, with whoever is in it marked beside its name."""
    name = path.rsplit("/", 1)[-1]
    # A file holding one class of its name is shown as the class.
    stem = name.rsplit(".", 1)[0]
    e2e.wait_for(
        f"{ide.name}'s project view shows {name}",
        lambda: stem in (ide.ask(op="reveal", path=path)["selected"] or ""),
    )


def stage(ide):
    cleared = ide.ask(op="quiet")["cleared"]
    if cleared:
        e2e.say(f"warning: {ide.name} had logged {len(cleared)} IDE errors, cleared from its status bar: {cleared}")
    e2e.wait_for(f"{ide.name}'s frame fills its display", lambda: ide.ask(op="frame")["bounds"] == "0,0,1280x800")
    settled(ide)


def main():
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    kit = dict(line.split("=", 1) for line in Path(sys.argv[1]).read_text().splitlines() if "=" in line)
    out = Path(sys.argv[2]).resolve()
    out.mkdir(parents=True, exist_ok=True)
    if e2e.RUN.exists():
        e2e.shutil.rmtree(e2e.RUN)
    e2e.RUN.mkdir(parents=True)
    threading.Thread(target=e2e.watchdog, daemon=True).start()
    started = time.monotonic()
    host = guest = None
    try:
        base = e2e.start_selvaged()
        project = e2e.RUN / FOLDER
        write_project(project, Path(kit["ide"]))
        write_jdk_table("host", Path(kit["ide"]))
        host = e2e.Ide("host", kit, project)
        guest = e2e.Ide("guest", kit)
        host.connect()
        guest.connect()
        host.ask(op="settings", displayName="Ada", serverUrl=base, cursorLabel="floating")
        guest.ask(op="settings", displayName="Grace", cursorLabel="floating")
        stage(host)
        scene_host_editing(host, guest, out / "01-host-editing.png")
        scene_participants(host, out / "02-participants.png")
        scene_guest_following(host, guest, out / "03-guest-following.png")
        scene_actions(host, out / "04-actions.png")
        scene_settings(host, out / "05-settings.png")
        e2e.say(f"PASS: five screenshots in {out}, {time.monotonic() - started:.0f}s")
        return 0
    except e2e.Failure as failure:
        e2e.say(f"FAIL: {failure}")
        for ide in (host, guest):
            if ide is not None:
                e2e.say(f"  {ide.name}: screenshot {ide.screenshot('failure')}, log {ide.home / 'log' / 'idea.log'}")
                try:
                    state = ide.state()
                    e2e.say(f"  {ide.name}: {state['said'][-6:]} windows {state['windows']} modal {state['modal']}")
                except Exception as error:
                    e2e.say(f"  {ide.name}: no state ({error!r})")
        return 1
    finally:
        e2e.stop_all()


if __name__ == "__main__":
    sys.exit(main())
