# Configuration

The settings are on **Settings → Tools → Selvage** and are stored application-wide in the IDE's
`options/selvage.xml`. They have the VS Code client's names, defaults and meanings; each row names
the VS Code setting it matches.

| Setting | VS Code | Default | What it does |
|---|---|---|---|
| Server URL | `selvage.serverUrl` | empty | The server to host on. In full (`wss://host`, `ws://127.0.0.1:8080`) or a domain on its own, which means `wss://<domain>`. Set it and hosting never asks; while it is set, **Change the server** says so and changes nothing. |
| Display name | `selvage.displayName` | empty | The name other participants see. At most 32 UTF-16 code units, so an emoji costs two; a longer name is refused, never shortened. A change while a session is live renames it at once. |
| Save a document the room changed | `selvage.autoSave` | on | Saves a document once the room's edits to it settle, half a second after the last. The save is made as the document stands: the IDE's own on-save changes, such as adding a final line break, are not applied, because every participant would receive them as an edit. |
| Open the room's first document on join | `selvage.openOnJoin` | on | Opens the room's first document when you join, or when a room that was empty at the join reports one. Only the first. |
| Cursor label | `selvage.cursorLabel` | `none` | `floating` draws a peer's name above their caret's line; `chip` draws it inline at the caret. Either covers some text. A name is clipped to 24 code points. |

Two values are remembered beside the settings rather than set: the last server a host used, and
the last name given to the name question. **Change the server** reads and writes the first; an empty
**Display name** falls back to the second.

The answers to hosting's questions follow the same order as VS Code: the setting, then the
remembered value, then one question.

A guest's copy of the room lives under the IDE's system directory,
`selvage/rooms/<room>/<window>`, with a `.selvage-mirror.json` marker. It is removed when the
session ends, and kept when the room ends under you. When you join the same room again, the copies
of it whose IDE is no longer running are removed, as VS Code removes them.
