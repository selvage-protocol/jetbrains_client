# Architecture

Two Gradle projects:

- `engine/`: the protocol in Kotlin, with no IDE dependency. It speaks the `selvage/2` sealed
  envelope over a WebSocket, keeps the room's documents as a Yjs-compatible CRDT, and runs a host's
  or a guest's session, including a host's return and a guest's reconnect after a dropped socket.
  Its tests replay the specification's vectors and run against real `yjs`, a real `selvaged` and
  the TypeScript engine the other clients share.
- `plugin/`: the IntelliJ Platform plugin. It depends on `engine` and on
  `com.intellij.modules.platform` only, so it installs into any IDE on that platform.

## The plugin

| Part | What it does |
|---|---|
| `bridge/` | The words, seat colours, names, initials, invite and address rules, the grant and the mirror, ported from `vscode_client/src/bridge`. A test runs the TypeScript originals through Node and compares the answers. |
| `session/SelvageService` | The twelve commands and the one session this IDE is in. |
| `session/RoomSession` | One live session bound to one project: the engine's events, handled on the event thread in order; the listing, the mirror, the fetch, presence, go to and follow, and how the session ends. |
| `session/DocumentSync` | Keeps each open document and the room's copy of it in step, both ways. |
| `session/Presence` | Draws a peer's caret, selection, gutter badge and name. |
| `ui/` | Notifications, the questions a command asks, the three status bar controls, the tool window and the project view's badges. |
| `settings/` | The settings page and its store. |

## How a document stays in step

A `Document` is UTF-16 with LF line endings, which is what the room holds, so offsets carry over
unchanged; the file's own line separator is applied when it is saved.

Each bound document remembers the room text it last agreed with. A keystroke goes to the room at
once when the room still reads that text (`SelvageSession.replaceIf`). When a peer's edit has
landed in between, the keystroke waits, and on the next pass both are merged against the common
text and written to the room and the document together. Nothing is written at a stale offset, and
a change that would split a character in two is widened to whole characters first.

A peer's edit is written into the document as an undoable command, so undoing it is a change like
any other and goes to the room. While it is being written, the document's own change events are
ignored, so it is not sent back.

A viewer's documents are made read-only, and the sync lifts that for the moment it writes the
room's own edits.

## Threads

The engine runs on its own thread. A host reads a file for a peer outside the engine's lock, on the
thread whose call found the read was needed, which is the network thread when a peer opened the
path, so the event thread can still read the session while the disk is read. The read takes no IDE
lock. If a file in the folder is swapped for a named pipe between the check and the open, the read
waits on that pipe, and the room's network thread waits with it. That is a residual: only a process
on the host racing the host's own folder can cause it. Every engine event is handed to the event
thread with the non-modal modality, as the platform requires for code that writes documents.
