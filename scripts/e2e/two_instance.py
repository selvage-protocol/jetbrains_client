"""Two real IntelliJ IDEA instances, each with the built plugin, in one room on a real selvaged.

Each instance runs in its own sandbox (config, system, plugins, log and temporary directories)
on its own Xvfb display, with the test-only driver plugin (`plugin/src/e2e`) answering on a
loopback socket. The scenario is the sibling clients' end-to-end one: host, join from the invite,
open a file, edit both ways, see each other's caret, open a granted path the host never opened,
rename, follow, leave, rejoin, the host's leave ending the room, and the host going away until
the room ends. The TypeScript engine the other clients share takes part twice: as a third
participant in the IDE's room, and as a host the IDE joins. A host coming back is not driven:
neither engine reclaims a hosting session after a drop.

Every spawn is bounded and killed on the way out, every wait polls a predicate against a
deadline and reports what it last saw, and a watchdog bounds the whole run.

    python3 scripts/e2e/two_instance.py <kit.properties>

Environment: SELVAGE_SELVAGED (required), SELVAGE_VSCODE_CLIENT (default: the nearest sibling
checkout, whose packages `npm ci` has installed),
SELVAGE_E2E_LIBRARY_PATH (the dev shell sets it), SELVAGE_E2E_DEADLINE_S (one wait, default 90),
SELVAGE_E2E_WATCHDOG_S (the run, default 900).
"""

import json
import os
import queue
import re
import select
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / ".tmp" / "e2e"
DEADLINE_S = float(os.environ.get("SELVAGE_E2E_DEADLINE_S", "90"))
WATCHDOG_S = float(os.environ.get("SELVAGE_E2E_WATCHDOG_S", "900"))
SPAWN_S = 30.0
POLL_S = 0.1
ROOM_GRACE_MS = 5000

SEED_PATH = "notes.txt"
SEED_TEXT = "a document two real editors are about to share\n"
GRANTED_PATH = "granted/never-opened.txt"
GRANTED_TEXT = "a file the host never opens in its own window\n"
MARKER_HOST = "[[HOST-EDIT]]"
MARKER_GUEST = "[[GUEST-EDIT]]"
MARKER_TS = "[[TS-EDIT]]"
MARKER_HOST_2 = "[[HOST-EDIT-2]]"

children = []
cleanup_lock = threading.Lock()


def say(line):
    print(line, flush=True)


class Failure(Exception):
    pass


def wait_for(label, check, timeout=DEADLINE_S):
    """Polls `check` until it returns something truthy; on the deadline, fails with what it last saw."""
    deadline = time.monotonic() + timeout
    last = None
    while True:
        try:
            last = check()
        except Failure:
            raise
        except Exception as error:  # the next poll may see it settled
            last = f"<{error!r}>"
        if last:
            return last
        if time.monotonic() > deadline:
            raise Failure(f"not within {timeout:.0f}s: {label}")
        threading.Event().wait(POLL_S)


def spawn(name, argv, **kwargs):
    log = open(RUN / f"{name}.log", "wb")
    process = subprocess.Popen(argv, stdout=kwargs.pop("stdout", log), stderr=log, start_new_session=True, **kwargs)
    children.append((name, process))
    return process


def stop_all():
    with cleanup_lock:
        for name, process in reversed(children):
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
        deadline = time.monotonic() + 10
        for name, process in reversed(children):
            try:
                process.wait(max(0.1, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(5)
        children.clear()


def watchdog():
    threading.Event().wait(WATCHDOG_S)
    say(f"FAIL: the run outlasted its {WATCHDOG_S:.0f}s watchdog")
    stop_all()
    os._exit(2)


def read_line(stream, timeout, label):
    lines = queue.Queue()
    threading.Thread(target=lambda: lines.put(stream.readline()), daemon=True).start()
    try:
        line = lines.get(timeout=timeout)
    except queue.Empty:
        raise Failure(f"{label} said nothing within {timeout:.0f}s")
    if not line:
        raise Failure(f"{label} closed its output")
    return line


# --- the server, the displays, the proxy ---------------------------------------------------------


def start_selvaged():
    binary = os.environ.get("SELVAGE_SELVAGED")
    if not binary or not os.access(binary, os.X_OK):
        raise Failure(f"SELVAGE_SELVAGED is {binary!r}, not an executable selvaged")
    process = spawn(
        "selvaged",
        [binary, "--listen", "127.0.0.1:0", "--room-grace-ms", str(ROOM_GRACE_MS)],
        stdout=subprocess.PIPE,
        text=True,
    )
    first = read_line(process.stdout, SPAWN_S, "selvaged")
    match = re.search(r"ws://(\S+)/session", first)
    if not match:
        raise Failure(f"selvaged's first line names no URL: {first!r}")
    threading.Thread(target=lambda: process.stdout.read(), daemon=True).start()
    return f"ws://{match.group(1)}"


def start_display(name):
    fbdir = RUN / name / "screen"
    fbdir.mkdir(parents=True, exist_ok=True)
    read, write = os.pipe()
    spawn(
        f"{name}-xvfb",
        ["Xvfb", "-displayfd", str(write), "-screen", "0", "1600x1000x24", "-nolisten", "tcp", "-fbdir", str(fbdir)],
        pass_fds=(write,),
    )
    os.close(write)
    ready, _, _ = select.select([read], [], [], SPAWN_S)
    if not ready:
        raise Failure(f"{name}'s Xvfb named no display within {SPAWN_S:.0f}s")
    number = os.read(read, 64).decode().strip()
    os.close(read)
    if not number.isdigit():
        raise Failure(f"{name}'s Xvfb answered {number!r}")
    return f":{number}"


# --- an IDE ---------------------------------------------------------------------------------------


class Ide:
    def __init__(self, name, kit, project=None):
        self.name = name
        self.project = project
        self.seen = 0
        self.home = RUN / name
        for part in ("config/options", "system", "plugins", "log", "tmp"):
            (self.home / part).mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(kit["plugin"]) as archive:
            # The folder the plugin installs into is the zip's top folder; a generic one would be
            # shared with any other plugin built from a module of the same name.
            tops = {name.split("/", 1)[0] for name in archive.namelist()}
            if tops != {"selvage"}:
                raise SystemExit(f"the plugin's zip installs into {sorted(tops)}, not selvage")
            archive.extractall(self.home / "plugins")
        driver = self.home / "plugins" / "selvage-e2e" / "lib"
        driver.mkdir(parents=True)
        shutil.copy(kit["driver"], driver / "selvage-e2e.jar")
        (self.home / "idea.properties").write_text(
            "".join(f"idea.{key}.path={self.home / key}\n" for key in ("config", "system", "plugins", "log"))
        )
        base = [
            line
            for line in (Path(kit["ide"]) / "bin" / "idea64.vmoptions").read_text().splitlines()
            if line and not line.startswith("-Xmx")
        ]
        self.port_file = self.home / "driver.port"
        (self.home / "idea.vmoptions").write_text(
            "\n".join(
                base
                + [
                    "-Xmx1500m",
                    f"-Djava.io.tmpdir={self.home / 'tmp'}",
                    f"-Dselvage.e2e.portFile={self.port_file}",
                    "-Djb.privacy.policy.text=<!--999.999-->",
                    "-Djb.consents.confirmation.enabled=false",
                    "-Dide.show.tips.on.startup.default.value=false",
                    "-Didea.initially.ask.config=never",
                    "-Dide.no.platform.update=true",
                    # The first-run tour is a modal dialog; the room's changes wait while one is open.
                    "-Dide.experimental.ui.onboarding=false",
                ]
            )
            + "\n"
        )
        # A person who ticked "Don't ask again" on the IDE's own question and chose a new window:
        # the guest joins with a project already open, and the IDE would otherwise ask where the room goes.
        (self.home / "config" / "options" / "ide.general.xml").write_text(
            '<application>\n  <component name="GeneralSettings">\n'
            '    <option name="confirmOpenNewProject2" value="0" />\n  </component>\n</application>\n'
        )
        if project is not None:
            # The host's own folder is trusted, as a person opening their own project would say.
            (self.home / "config" / "options" / "trusted-paths.xml").write_text(
                '<application>\n  <component name="Trusted.Paths">\n    <option name="TRUSTED_PROJECT_PATHS">\n'
                f'      <map>\n        <entry key="{project}" value="true" />\n      </map>\n    </option>\n'
                '  </component>\n  <component name="Trusted.Paths.Settings">\n    <option name="TRUSTED_PATHS">\n'
                f'      <list>\n        <option value="{project}" />\n      </list>\n    </option>\n'
                "  </component>\n</application>\n"
            )
        self.display = start_display(name)
        environment = {key: value for key, value in os.environ.items() if key != "JAVA_TOOL_OPTIONS"}
        environment.update(
            DISPLAY=self.display,
            LD_LIBRARY_PATH=os.environ.get("SELVAGE_E2E_LIBRARY_PATH", ""),
            IDEA_PROPERTIES=str(self.home / "idea.properties"),
            IDEA_VM_OPTIONS=str(self.home / "idea.vmoptions"),
            TMPDIR=str(self.home / "tmp"),
        )
        argv = [str(Path(kit["ide"]) / "bin" / "idea")] + ([str(project)] if project else [])
        self.process = spawn(f"{name}-ide", argv, env=environment, cwd=self.home)
        self.socket = None

    def connect(self):
        port = wait_for(
            f"{self.name}'s driver to listen (log: {self.home / 'log' / 'idea.log'})",
            lambda: self.port_file.read_text().strip() if self.port_file.exists() else self.alive(),
            timeout=180,
        )
        self.socket = socket.create_connection(("127.0.0.1", int(port)), timeout=DEADLINE_S)
        self.reader = self.socket.makefile("r", encoding="utf-8")
        if self.project is not None:
            wait_for(f"{self.name} opens {self.project}", lambda: str(self.project) in self.state()["projects"], timeout=180)

    def alive(self):
        if self.process.poll() is not None:
            raise Failure(f"{self.name}'s IDE exited with {self.process.returncode}")
        return None

    def ask(self, **command):
        self.alive()
        try:
            self.socket.sendall((json.dumps(command, sort_keys=True, ensure_ascii=False) + "\n").encode())
            line = self.reader.readline()
        except OSError as error:
            raise Failure(f"{self.name} did not answer {command} within {DEADLINE_S:.0f}s ({error!r})")
        if not line:
            raise Failure(f"{self.name}'s driver closed the connection during {command}")
        reply = json.loads(line)
        if not reply.get("ok"):
            raise Failure(f"{self.name} refused {command}: {reply.get('error')}")
        return reply

    def state(self):
        return self.ask(op="state")

    def session(self):
        return self.state()["session"]

    def said(self):
        return [entry["sentence"] for entry in self.state()["said"]]

    def mark(self):
        """What is said from here on is what the next step caused."""
        self.seen = len(self.state()["said"])

    def action(self, id, **answers):
        self.mark()
        self.ask(op="action", id=id, answers=answers)
        unexpected = self.state()["unexpected"]
        if unexpected:
            raise Failure(f"{self.name}: {id} asked what the script did not expect: {unexpected}")

    def kill(self):
        os.killpg(self.process.pid, signal.SIGKILL)
        self.process.wait(10)

    def screenshot(self, label):
        frame = self.home / "screen" / "Xvfb_screen0"
        out = RUN / f"{self.name}-{label}.png"
        if frame.exists() and shutil.which("magick"):
            subprocess.run(["magick", f"xwd:{frame}", str(out)], timeout=30, check=False)
        return out


def said_one(ide, prefix):
    return wait_for(
        f"{ide.name} says something starting {prefix!r}",
        lambda: next((s for s in ide.said()[ide.seen :] if s.startswith(prefix)), None),
    )


def session_field(ide, field, predicate, label):
    def check():
        session = ide.session()
        return session is not None and predicate(session.get(field)) and session

    try:
        return wait_for(f"{ide.name}: {label}", check)
    except Failure as failure:
        raise Failure(f"{failure}; last session: {json.dumps(ide.session(), ensure_ascii=False)[:1500]}")


# --- the TypeScript engine -------------------------------------------------------------------------


class TsPeer:
    def __init__(self, name):
        sibling = next((d / "vscode_client" for d in ROOT.parents if (d / "vscode_client").is_dir()), None)
        vscode = Path(os.environ.get("SELVAGE_VSCODE_CLIENT") or sibling or "vscode_client").resolve()
        node = os.environ.get("SELVAGE_NODE", "node")
        script = ROOT / "engine" / "src" / "test" / "node" / "ts-peer.mjs"
        self.name = name
        self.process = spawn(
            name, [node, str(script), str(vscode)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True
        )

    def ask(self, **command):
        self.process.stdin.write(json.dumps(command) + "\n")
        self.process.stdin.flush()
        reply = json.loads(read_line(self.process.stdout, DEADLINE_S, self.name))
        if not reply.get("ok"):
            raise Failure(f"{self.name} refused {command}: {reply.get('error')}")
        return reply

    def report(self):
        return self.ask(op="report")


# --- the scenario ----------------------------------------------------------------------------------


def two_ides(host, guest, base):
    host.ask(op="settings", displayName="Ada", serverUrl=base)
    guest.ask(op="settings", displayName="Bob")

    host.action("Selvage.Host")
    session = session_field(host, "invite", bool, "hosting with an invite")
    invite = session["invite"]
    said_one(host, "Selvage: the room is open")
    session_field(host, "widgets", lambda w: w["session"] == "Sharing “shared”", "the session row in the status bar")
    say(f"ok: the host IDE opened a room; its row reads {session['widgets']['session']!r}, invite {invite[:40]}…")

    host.ask(op="openFile", path=SEED_PATH)
    session_field(host, "documents", lambda d: d.get(SEED_PATH) == SEED_TEXT, "the host's notes.txt is bound")

    guest.action("Selvage.Join", inputs=[invite])
    session = session_field(guest, "role", lambda r: r == "guest", "the guest is seated")
    said_one(guest, "Selvage: joined the room")
    session_field(guest, "status", lambda s: s == "In Ada’s session", "the guest's row names the host")
    session_field(guest, "documents", lambda d: d.get(SEED_PATH) == SEED_TEXT, "the guest lands in notes.txt")
    if session["project"] != session["mirror"]:
        raise Failure(f"the guest's project is {session['project']}, not its mirror {session['mirror']}")
    say(f"ok: the guest IDE joined from the invite into a new project window on its mirror {session['mirror']}")

    host.ask(op="type", path=SEED_PATH, offset=len(SEED_TEXT), text=MARKER_HOST)
    session_field(guest, "documents", lambda d: MARKER_HOST in d.get(SEED_PATH, ""), "the host's edit reaches the guest")
    guest.ask(op="type", path=SEED_PATH, offset=0, text=MARKER_GUEST)
    session_field(host, "documents", lambda d: MARKER_GUEST in d.get(SEED_PATH, ""), "the guest's edit reaches the host")
    final = wait_for(
        "both copies agree",
        lambda: (h := host.session()["documents"][SEED_PATH]) == guest.session()["documents"][SEED_PATH] and h,
    )
    say(f"ok: edits both ways converged: {final!r}")

    host.ask(op="caret", path=SEED_PATH, offset=5)
    session_field(
        guest,
        "drawn",
        lambda d: any(c["label"] == "Ada" and c["head"] == 5 for c in d.get(SEED_PATH, [])),
        "the host's caret is drawn in the guest",
    )
    guest.ask(op="caret", path=SEED_PATH, offset=3)
    session_field(
        host,
        "drawn",
        lambda d: any(c["label"] == "Bob" and c["head"] == 3 for c in d.get(SEED_PATH, [])),
        "the guest's caret is drawn in the host",
    )
    say("ok: each IDE draws the other's caret where it is (Ada at 5, Bob at 3)")

    session_field(guest, "offered", lambda o: GRANTED_PATH in o, "the guest is offered the granted path")
    guest.action("Selvage.OpenDocument", choices=[GRANTED_PATH])
    session_field(guest, "documents", lambda d: d.get(GRANTED_PATH) == GRANTED_TEXT, "the granted path's text arrives")
    if GRANTED_PATH in host.session()["documents"]:
        raise Failure("the host had the granted path open; the phase proves nothing")
    say("ok: the guest opened a path the host never opened, and the host read it from its folder")

    guest.mark()
    guest.ask(op="rename", name="Robert")
    session_field(host, "people", lambda p: any(r.startswith("Robert") for r in p), "the rename reaches the host's list")
    said_one(guest, 'Selvage: display name set to "Robert"')
    say(f"ok: the guest renamed itself; the host's list reads {host.session()['people']}")

    guest.action("Selvage.FollowParticipant")
    session_field(guest, "follow", lambda f: f == "Following Ada", "the guest follows the host")
    session_field(guest, "widgets", lambda w: w["follow"] == "Following Ada", "the follow control stands")
    target = len(host.session()["documents"][SEED_PATH]) - 2
    host.ask(op="caret", path=SEED_PATH, offset=target)
    session_field(
        guest,
        "editor",
        lambda e: e is not None and e["path"] == SEED_PATH and e["caret"] == target,
        "the follow moves the guest's caret with the host's",
    )
    guest.action("Selvage.StopFollowing")
    session_field(guest, "follow", lambda f: f is None, "the follow stops")
    say(f"ok: the guest followed the host to offset {target}, then stopped")

    guest.action("Selvage.Leave")
    wait_for("the guest's session ends", lambda: guest.session() is None)
    said_one(guest, "Selvage: left the session.")
    session_field(host, "people", lambda p: len(p) == 1, "the host's list is the host alone")
    say("ok: the guest left; the host's list is the host alone")

    guest.action("Selvage.Join", inputs=[invite])
    session_field(guest, "role", lambda r: r == "guest", "the guest is seated again")
    guest.mark()
    host.action("Selvage.Leave", confirms=[True])
    wait_for("the host's session ends", lambda: host.session() is None)
    ended = said_one(guest, "The host ended the session.")
    wait_for("the guest's session ends with the room", lambda: guest.session() is None)
    if "Your copy is kept at " not in ended:
        raise Failure(f"the guest's ending names no kept copy: {ended!r}")
    say(f"ok: the guest rejoined, the host left after its question, and the guest heard: {ended!r}")


def ide_host_with_ts_guest(host, guest, base):
    host.action("Selvage.Host")
    session = session_field(host, "wireInvite", bool, "hosting again")
    ts = TsPeer("ts-guest")
    ts.ask(op="join", invite=session["wireInvite"], name="Tess")
    wait_for("the TypeScript guest is seated", lambda: ts.report().get("role") == "guest")
    ts.ask(op="open", path=SEED_PATH)
    host_text = host.session()["documents"][SEED_PATH]
    wait_for("the TypeScript guest has the host's text", lambda: ts.report()["texts"].get(SEED_PATH) == host_text)
    ts.ask(op="insert", path=SEED_PATH, index=0, text=MARKER_TS)
    session_field(host, "documents", lambda d: d[SEED_PATH].startswith(MARKER_TS), "the TypeScript edit reaches the IDE")
    host.ask(op="type", path=SEED_PATH, offset=0, text=MARKER_HOST_2)
    wait_for(
        "the IDE's edit reaches the TypeScript guest",
        lambda: ts.report()["texts"].get(SEED_PATH, "").startswith(MARKER_HOST_2),
    )
    ts.ask(op="select", path=SEED_PATH, anchor=2, head=2)
    session_field(
        host,
        "drawn",
        lambda d: any(c["label"] == "Tess" and c["head"] == 2 for c in d.get(SEED_PATH, [])),
        "the TypeScript guest's caret is drawn in the IDE",
    )
    host.ask(op="caret", path=SEED_PATH, offset=4)
    wait_for(
        "the IDE's caret reaches the TypeScript guest",
        lambda: any(
            p["displayName"] == "Ada" and p["path"] == SEED_PATH and (p["resolved"] or {}).get("head") == 4
            for p in ts.report()["presence"]
        ),
    )
    ts.ask(op="rename", name="Tessa")
    session_field(host, "people", lambda p: any(r.startswith("Tessa") for r in p), "the TypeScript rename reaches the IDE")
    say("ok: mixed: the IDE host and the TypeScript guest edit both ways, see each other's caret and the rename")

    guest.action("Selvage.Join", inputs=[session["invite"]])
    session_field(
        guest,
        "people",
        lambda p: any(r.startswith("Ada") for r in p) and any(r.startswith("Tessa") for r in p),
        "the IDE guest sees the host and the TypeScript guest",
    )
    say(f"ok: three in one room: the IDE guest's list reads {guest.session()['people']}")

    host.screenshot("hosting")
    guest.screenshot("joined")
    guest.mark()
    host.kill()
    away = said_one(guest, "Ada left the session. The room disconnects in ")
    session_field(
        guest,
        "widgets",
        lambda w: (w["session"] or "").startswith("Ada left the session · Disconnecting in "),
        "the IDE guest's row counts down",
    )
    row = guest.session()["widgets"]["session"]
    wait_for(
        "the TypeScript guest hears the host detach",
        lambda: any(e["type"] == "hostDetached" for e in ts.report()["events"]),
    )
    gone = said_one(guest, "The host was away too long, so the session ended.")
    wait_for("the IDE guest's session ends", lambda: guest.session() is None)
    wait_for("the TypeScript guest hears the room end", lambda: any(e["type"] == "roomGone" for e in ts.report()["events"]))
    say(f"ok: the host IDE was killed; the guest said {away!r}, its row read {row!r}, then {gone!r}")


def ts_host_with_ide_guest(guest, base):
    ts = TsPeer("ts-host")
    invite = ts.ask(op="host", base=base, name="Tess", files={SEED_PATH: SEED_TEXT})["invite"]
    guest.action("Selvage.Join", inputs=[invite])
    session_field(guest, "status", lambda s: s == "In Tess’s session", "the IDE guest is in the TypeScript host's room")
    guest.action("Selvage.OpenDocument", choices=[SEED_PATH])
    session_field(guest, "documents", lambda d: d.get(SEED_PATH) == SEED_TEXT, "the TypeScript host's text arrives")
    guest.ask(op="type", path=SEED_PATH, offset=0, text=MARKER_GUEST)
    wait_for(
        "the IDE guest's edit reaches the TypeScript host",
        lambda: ts.report()["texts"].get(SEED_PATH, "").startswith(MARKER_GUEST),
    )
    ts.ask(op="insert", path=SEED_PATH, index=0, text=MARKER_TS)
    session_field(
        guest, "documents", lambda d: d.get(SEED_PATH, "").startswith(MARKER_TS + MARKER_GUEST), "the host's edit arrives"
    )
    say("ok: mixed: the TypeScript host and the IDE guest edit both ways")
    guest.mark()
    ts.ask(op="close")
    ended = said_one(guest, "The host ended the session.")
    wait_for("the IDE guest's session ends", lambda: guest.session() is None)
    say(f"ok: the TypeScript host closed the room; the IDE guest said {ended!r}")


def main():
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    kit = dict(line.split("=", 1) for line in Path(sys.argv[1]).read_text().splitlines() if "=" in line)
    if RUN.exists():
        shutil.rmtree(RUN)
    RUN.mkdir(parents=True)
    threading.Thread(target=watchdog, daemon=True).start()
    signal.signal(signal.SIGTERM, lambda *_: (stop_all(), os._exit(3)))
    started = time.monotonic()
    host = guest = None
    try:
        base = start_selvaged()
        say(f"ok: selvaged listens at {base}")
        project = RUN / "shared"
        (project / "granted").mkdir(parents=True)
        (project / SEED_PATH).write_text(SEED_TEXT)
        (project / GRANTED_PATH).write_text(GRANTED_TEXT)
        host = Ide("host", kit, project)
        guest = Ide("guest", kit)
        host.connect()
        guest.connect()
        say(f"ok: two IDEs up, on displays {host.display} and {guest.display}, each with its own sandbox")
        two_ides(host, guest, base)
        ide_host_with_ts_guest(host, guest, base)
        ts_host_with_ide_guest(guest, base)
        guest.screenshot("end")
        say(f"PASS: two IDEs and the TypeScript engine, {time.monotonic() - started:.0f}s")
        return 0
    except Failure as failure:
        say(f"FAIL: {failure}")
        for ide in (host, guest):
            if ide is not None:
                say(f"  {ide.name}: screenshot {ide.screenshot('failure')}, log {ide.home / 'log' / 'idea.log'}")
                try:
                    state = ide.state()
                    say(f"  {ide.name} said: {[s['sentence'] for s in state['said']][-8:]}")
                    say(f"  {ide.name} projects: {state['projects']}, unexpected: {state['unexpected']}")
                    if state["session"]:
                        shown = {k: state["session"][k] for k in ("documents", "editor", "status", "people", "drawn")}
                        say(f"  {ide.name} session: {json.dumps(shown, ensure_ascii=False)[:800]}")
                except Exception as error:
                    say(f"  {ide.name}: no state ({error!r})")
        return 1
    finally:
        stop_all()


if __name__ == "__main__":
    sys.exit(main())
