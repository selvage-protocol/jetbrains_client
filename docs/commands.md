# Command behaviour

The twelve commands share their names, their sentences and their order of questions with the
VS Code and Neovim clients. A sentence is shown as a notification in the **Selvage** group, as
`Selvage: <sentence>.`, at the level the other clients use. The room's own lines (the host going
away and coming back, the room ending, a follow that ends on its own, the host's leave question)
are shown as they stand, without the `Selvage: ` in front, as VS Code shows them.

A question is an input dialog, a list or a dialog with one affirming button and Cancel. A list
that names people keeps itself in step with the room while it is open: a person who leaves is
dropped, a rename is redrawn, and the list closes when nobody is left to pick.

## Host a session

1. A window with no project folder is refused: `open a folder first — hosting shares the folder
   this window is open on, and a room from a window with no folder would share nothing.`
2. Already hosting: the invite is copied again and nothing else happens. In someone else's room:
   `you are in this session; hosting a session means leaving it first.`, with **Leave and host**.
3. The server: the **Server URL** setting, else the address used last, else one question
   prefilled with the demo server. The address is remembered.
4. The display name: the setting, else the name given last, else one question.
5. A modal progress dialog reads `Selvage: connecting to <address>…` and the status bar reads
   `Connecting…`. A refusal is said with its reason; a remembered address adds **Change the
   server**.
6. `the room is open. Send this link to your friend — it is on the clipboard.`, with **Copy again**.

The project's folder is the grant. What a host leaves out is the VS Code client's list, which
includes `.idea`, and the folder's own ignore files narrow it further. A file a peer asks for is
read from disk with every step of its path checked, so a link out of the folder is refused.

## Join a session from an invite link

The invite question starts empty: the clipboard is not read. A link whose fragment names one of
the two keys and not the other is refused before anything is dialled. The room opens as a new project window on a folder the plugin
keeps under the IDE's system directory, `selvage/rooms/<room>/<window>`, holding one file per
listed path. That project opens untrusted, in the IDE's safe mode, because its build files are the
host's and joining a room is not agreeing to run them. When you join with a project already open,
the IDE asks where to open the room unless you told it not to ask.

The notice is VS Code's: `joined the room, opening <path>.`, with `<n> more files are open.` and an
**Open a document from the room** button when there are more. The room's project settings
(`.idea/**`, `.run/**`, `*.run.xml`, `*.iml`, `*.ipr`, `*.iws`) are not written into the copy, and that is said once.

When the session ends, the copy and its window go. When the room ends under you, the copy is kept
and the sentence says where.

## The others

- **Copy the invite link**: no notification on success. A refused clipboard says why.
- **Open a document from the room**: one document opens at once; several give a list whose footer
  reads `<n> open in this room`.
- **Download a file from the room**: a list with **Fetch the whole listing (<n> files)** first; the
  whole listing asks first and is capped at 100 files. While the text is on its way, the IDE's
  background task reads `Selvage: fetching <path>…`. A path the host stopped sharing is refused
  with the reason. `fetched the files.` when everything arrived.
- **Leave the session**: a host is asked `Leaving ends the room for everyone and stops the invite
  link.`, with **Leave anyway**, and the room is closed for everyone before the session ends.
  Closing the project leaves too, and a host's room closes without asking.
- **Set the name other participants see** and **Change the server**: a notification with the
  value in force and a button that asks for a new one.
- **List the room's participants**: a list titled with the session, footer `Everyone in the room`.
  A row opens that person's menu: **Go to**, **Follow** or **Stop following**, and **Rename** on
  your own row.
- **Go to** and **Follow**: with one other person there is no list. A follow ends silently when you
  go somewhere or stop it, and with a sentence when you type, move, the person leaves or their file
  goes.

A viewer's documents are read-only in the editor, and `you are a viewer in this room, so its
documents are read-only.` is said once, as a warning. The room's edits still reach them.

## Where this client differs from the VS Code client, and why

Each of these is the editor's business under `AGENTS.md` §3; none removes a command.

| What | VS Code | Here | Why |
|---|---|---|---|
| Where a joined room opens | The window reloads onto the room's folder, after asking `joining replaces this window's folder…` | A new project window; your own project stays open | An IntelliJ window holds one project and opening another does not replace it, so there is nothing to warn about. The IDE asks where to open it when a project is open. |
| Restricted Mode for the room's folder | Workspace trust | The project opens untrusted, in safe mode | The IDE's own form of the same protection. |
| Workspace settings left out of the copy | `.vscode/**`, `*.code-workspace` | `.idea/**`, `.run/**`, `*.run.xml`, `*.iml`, `*.ipr`, `*.iws`, said in this client's own sentence | Those are the files this IDE would apply rather than show. |
| A host with several folders | Paths qualified `<folder>/<path>` | One folder, the project's base | A project has one base directory. |
| Clicking the session's name | The people list | The Selvage menu, with the people list in it and in the tool window | The menu reaches all twelve commands from the status bar, which has no palette beside it. |
| A viewer's keystroke | Put back to the room's text | Refused by the editor: the document is read-only | The IDE can mark one document read-only, as Neovim's `modifiable = false`; VS Code cannot, so it puts the text back. `§13.9` allows either. |
| List placeholders | A line above the rows | The footer of the list | Where an IntelliJ list keeps its hint. |
| Status bar icons and the host-away background | Codicons, a warning background | Plain text | Presentation. |
| A peer's caret hover | `<name> · <role>` in the caret's hover | The same words on the error stripe mark and the gutter badge | Where this editor shows a hover for a range. |
| A failed save | The save's answer and its cause | A read-only file, a save the IDE refused, or one it held back is said; a write the IDE finishes in the background and that fails later is reported by the IDE's own notification | This platform writes files asynchronously and keeps the failure to itself. |

## Against the VS Code client, row by row

The rows of the parity study (`client-command-parity.md` §1 to §5), read against this client.
"Same" means the same words at the same level, or the same behaviour. A row VS Code alone has
because of its own editor, or Neovim alone, is marked so.

### Commands (§1)

| Row | VS Code | Here | Status |
|---|---|---|---|
| Host | `selvage.host`, `Host a session` | `Selvage.Host`, same title | Same |
| Join | `selvage.join`, `Join a session from an invite link` | `Selvage.Join`, same title | Same |
| Copy invite | `selvage.copyInvite`, `Copy the invite link` | `Selvage.CopyInvite`, same title | Same |
| Open | `selvage.openDocument`, `Open a document from the room` | `Selvage.OpenDocument`, same title | Same |
| Fetch | `selvage.fetch`, `Download a file from the room` | `Selvage.Fetch`, same title | Same |
| Leave | `selvage.leave`, `Leave the session` | `Selvage.Leave`, same title | Same |
| Name | `selvage.displayName`, `Set the name other participants see` | `Selvage.DisplayName`, same title | Same |
| Server | `selvage.changeServer`, `Change the server` | `Selvage.ChangeServer`, same title | Same |
| People | `selvage.peers`, `List the room's participants` | `Selvage.Peers`, same title | Same |
| Go to | `selvage.goToParticipant`, `Go to a participant` | `Selvage.GoToParticipant`, same title | Same |
| Follow | `selvage.followParticipant`, `Follow a participant` | `Selvage.FollowParticipant`, same title | Same |
| Stop | `selvage.stopFollowing`, `Stop following` | `Selvage.StopFollowing`, same title | Same |
| Arguments | Programmatic `*Args` interfaces; the palette asks | No arguments; every command asks | Same for a person; VS Code's programmatic seam has no IntelliJ counterpart a person reaches |

### Strings (§2)

| Row | VS Code | Here | Status |
|---|---|---|---|
| Host succeeded, reused address, clipboard refused, no invite | Four sentences, buttons `Copy again`, `Change the server` | Same sentences, levels and buttons | Same |
| Hosting again | Three sentences | Same | Same |
| Host while a guest | Modal, `Leave and host` | Dialog, `Leave and host` | Same |
| Host, no folder | Warning `open a folder first — …` | Same | Same |
| Join succeeded, room empty | `joined the room…` with `Open a document from the room` | Same | Same |
| Join while in a session | Two sentences, `Leave and join` | Same | Same |
| Join replaces the folder | Modal warning | Not asked | Divergence: a new window replaces nothing |
| Invite copied | `Copied` for 1800 ms | Same | Same |
| No invite, no session | Two warnings | Same | Same |
| Open: no session, while hosting, none open | Three sentences | Same | Same |
| Open picker | Title, placeholder `<n> open in this room` | Title, footer `<n> open in this room` | Same words, IntelliJ's place |
| Open: path matches none, left the listing | Programmatic path only | No path argument | n/a: no argument here either |
| Open failed | Error `could not open <path> from the room: …` | Same | Same |
| Fetch sentences | Session, hosting, empty, consent, progress, failure, done | Same; the progress is the IDE's background task | Same (progress added in this stage) |
| Fetch picker and whole-listing consent, cap | Row, modal, cap of 100 | Same | Same |
| Fetch: path matches none, left the listing | Warning, error | Same | Same (left-listing refusal added in this stage) |
| Leave, leave with no session | Host asked, `left the session.`, `not in a session.` | Same | Same |
| Display name read, set, prompt, empty, over the bound | VS Code's sentences and validator | Same | Same |
| Setting write refused | Error | Cannot happen: the IDE's settings store does not fail a write | n/a |
| Peers list | Title, placeholder `Everyone in the room`, menu per row | Same, footer | Same; refills while open (added in this stage) |
| Peers: no session, alone | Warning; opens with you | Same | Same |
| Viewer's notice | Warning, once | Warning, once | Same (was info before this stage) |
| Following, stopped by you, stopped on its own | `Following <name>`; silent; four info lines | Same | Same |
| Nothing to go to or follow | Three warnings | Same | Same |
| Host detached, attached, room gone | Three room lines, countdown | Same | Same |
| Session error, room full | Error | Same | Same |
| Live rename refused | `the server refused the display name …` | Same | Same |
| Apply refused, divergence | Error, warning | Same | Same |
| Save failed | Error, with a cause when there is one | Same, for the failures this IDE reports synchronously | Same words; see the divergence above |
| Disconnected | Two sentences by role | Same | Same |
| Not in the room, opened and saved | Two warnings, once per path | Same | Same (the save added in this stage) |
| Unfetched file | Warning `… is still empty …` | Same | Same |
| Mirror write failed | One and many | Same | Same (added in this stage) |
| Server and invite prompts | Titles, prompts, placeholders, validators | Titles, prompts, validators; no placeholder line | Same words; an IntelliJ input dialog has no placeholder |
| Drawn name clip | 24 code points, `<name> · <role>` hover | Same clip; the hover on the stripe and badge | Same, presentation |
| Neovim-only rows (not UTF-8, outside the grant, companion problems, nobody to ask) | n/a | n/a | n/a: one process, UTF-16 documents, a dialog is always there |

### Behaviour (§3)

| Row | VS Code | Here | Status |
|---|---|---|---|
| Host: already hosting, already a guest, no folder, order, cancel | As §3.1 | Same | Same |
| Host: connect failure | Error, `Change the server` for a remembered address | Same | Same |
| Host: blocks | Modal prompts | Modal prompts and progress | Same |
| Join: while hosting or joined | Asked before the invite | Same | Same |
| Join: invite input | Empty, validated, then the replace-window modal | Empty, validated | Same, less the modal (see above) |
| Join: landing | First document once, and a room that was empty lands later | Same | Same |
| Leave: guard, host leaving, what ends, after a drop | As §3.3 | Same; a guest's window on its copy closes too | Same; the window closing is this client's, since its folder is gone |
| Open: one, several, failure | As §3.4 | Same | Same |
| Name: unset, bound, empty, live change, confirmation, refusal | As §3.5 | Same | Same |
| People: no session, alone, rows, order | As §3.6 | Same | Same |
| Lifecycle: host away, back, room gone, disconnected | As §3.7 | Same | Same |
| Lifecycle: reconnecting | `Reconnecting…`, tooltip `The connection dropped; trying to rejoin the room.` | Same | Same (tooltip added in this stage) |

### Configuration (§4)

| Row | VS Code | Here | Status |
|---|---|---|---|
| Server URL | `selvage.serverUrl`, then remembered, then a question | Same | Same |
| Display name | `selvage.displayName`, then remembered, then a question | Same | Same |
| Auto-save | `selvage.autoSave`, on | Same | Same |
| Cursor label | `selvage.cursorLabel`, `none` | Same values and default | Same |
| Open on join | `selvage.openOnJoin`, on | Same | Same |
| Neovim's indicator, fetch bound, IPC log, load guard | none | none | n/a |

### Sentences (§5)

Every sentence this client says is in `Say` and `Words`, and `VocabularyTest` checks each against
VS Code's own pin (`vscode_client/test/vocabulary.test.ts`) or this client's short list of its own
(the project-settings notice). The §5 rows VS Code says, this client says with the same words and
level; the rows marked Neovim-only are not said here, for the reasons in the Strings table.
