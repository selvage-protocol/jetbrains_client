package dev.dontblameme.selvage.intellij.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty

/** Settings → Tools → Selvage: the same settings, words and defaults as the VS Code client's. */
class SelvageConfigurable : BoundConfigurable("Selvage") {
    private val settings = SelvageSettings.get()
    private var serverUrl = settings.serverUrl
    private var displayName = settings.displayName
    private var autoSave = settings.autoSave
    private var openOnJoin = settings.openOnJoin
    private var cursorLabel = settings.cursorLabel

    override fun createPanel(): DialogPanel =
        panel {
            row("Server URL:") {
                textField().bindText(::serverUrl).columns(COLUMNS_LARGE)
            }.rowComment(
                "The server address to host on: in full, or a domain on its own (a domain alone " +
                    "means the secure server). " +
                    "Set it and hosting never asks. The Change the server command reports which address is in force.",
            )
            row("Display name:") {
                textField().bindText(::displayName).columns(COLUMNS_LARGE)
            }.rowComment(
                "The name other participants see. At most 32 UTF-16 code units, so an emoji costs " +
                    "two; a longer name is refused. " +
                    "A change while a session is live renames this connection at once, and applies to " +
                    "the next host or join.",
            )
            row {
                checkBox("Save a document the room changed").bindSelected(::autoSave)
            }.rowComment(
                "Save a document the room changed, once the room has settled on it. With this " +
                    "off, a remote edit leaves the " +
                    "file on disk stale, with the editor's unsaved marker still on it.",
            )
            row {
                checkBox("Open the room's first document on join").bindSelected(::openOnJoin)
            }.rowComment(
                "Put the room's first document in an editor when a guest joins, and when a room " +
                    "that was empty at join reports " +
                    "its first document. Only the first; Open a document from the room lists every " +
                    "path. A host is unaffected.",
            )
            row("Cursor label:") {
                comboBox(SelvageSettings.CURSOR_LABELS).bindItem(::cursorLabel.toNullableProperty())
            }.rowComment(
                "Whether a remote peer's name is drawn at their cursor. \"floating\" and \"chip\" " +
                    "each cover some of the text; " +
                    "the full name is always in the caret's tooltip and in the participants view.",
            )
        }

    override fun apply() {
        super.apply()
        settings.update {
            this.serverUrl = this@SelvageConfigurable.serverUrl
            this.displayName = this@SelvageConfigurable.displayName
            this.autoSave = this@SelvageConfigurable.autoSave
            this.openOnJoin = this@SelvageConfigurable.openOnJoin
            this.cursorLabel = this@SelvageConfigurable.cursorLabel
        }
    }
}
