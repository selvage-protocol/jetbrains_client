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
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

/** Settings → Tools → Selvage: the VS Code client's settings, with its defaults. */
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
                "The server you host on: a full address such as wss://example.org, or just the domain. " +
                    "Left empty, hosting uses the last server you picked, or asks.",
            )
            row("Display name:") {
                textField().bindText(::displayName).columns(COLUMNS_LARGE)
            }.rowComment(
                "The name others see. Up to 32 characters, and an emoji counts as two. " +
                    "Changing it renames you in a live session too.",
            )
            row {
                checkBox("Save a document the room changed").bindSelected(::autoSave)
            }.rowComment(
                "Saves once the edits settle. Turned off, others' edits stay unsaved until you save.",
            )
            row {
                checkBox("Open the room's first document on join").bindSelected(::openOnJoin)
            }.rowComment(
                "The others open from Tools | Selvage | Open a document from the room.",
            )
            row("Cursor label:") {
                comboBox(SelvageSettings.CURSOR_LABELS, textListCellRenderer(::cursorLabelText))
                    .bindItem(::cursorLabel.toNullableProperty())
            }.rowComment(
                "Shows a participant's name at their caret. Off keeps the code clear, and the name " +
                    "is still in the caret's tooltip and the Selvage tool window.",
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

    companion object {
        /** How each stored `selvage.cursorLabel` value reads in the list. */
        fun cursorLabelText(value: String?): String =
            when (value) {
                "floating" -> "Above the caret"
                "chip" -> "Beside the caret"
                else -> "Off"
            }
    }
}
