# What is not here yet

- A host that loses its connection does not come back: the engine has no way yet to reclaim a
  hosting session, so the room waits out its grace and ends. A guest reconnects.
- While a modal dialog is open in the IDE, the room's changes to your documents wait and land when
  it closes. The platform does not allow document writes under a modal dialog. Your own typing in
  another window still goes out.
- Only the primary caret is shared; extra carets stay yours.
- A save the IDE finishes in the background and that fails afterwards is reported by the IDE's own
  notification, not with the room's `could not save` sentence.
- A host shares one folder, the project's base directory, even when the project has more content
  roots.
- Joining with a project already open makes the IDE ask where to open the room, unless you have
  told it not to ask.
- The join notice names the first path of the room's listing, which need not be the document it
  opens. The VS Code client says the same, since both build the notice from the same list.
- A file created in the copy of the room is not part of the room; saving it says so once.
