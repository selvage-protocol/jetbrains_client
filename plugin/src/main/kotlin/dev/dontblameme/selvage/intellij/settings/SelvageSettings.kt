package dev.dontblameme.selvage.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

/**
 * The VS Code client's `contributes.configuration`, with its defaults, and the two answers it keeps
 * in `globalState` (the last server and the last name typed), so the next host asks nothing.
 */
@Service(Service.Level.APP)
@State(name = "SelvageSettings", storages = [Storage("selvage.xml")])
class SelvageSettings : SimplePersistentStateComponent<SelvageSettings.Options>(Options()) {
    class Options : BaseState() {
        /** `selvage.serverUrl`: set, hosting never asks. */
        var serverUrl by string("")

        /** `selvage.displayName`: the name other participants see. */
        var displayName by string("")

        /** `selvage.autoSave`: save a document the room changed, once it settles. */
        var autoSave by property(true)

        /** `selvage.openOnJoin`: put the room's first document in an editor when a guest joins. */
        var openOnJoin by property(true)

        /** `selvage.cursorLabel`: `none`, `floating` or `chip`. */
        var cursorLabel by string(CURSOR_LABEL_DEFAULT)

        var lastServer by string()
        var lastDisplayName by string()
    }

    val serverUrl: String get() = state.serverUrl ?: ""
    val displayName: String get() = state.displayName ?: ""
    val autoSave: Boolean get() = state.autoSave
    val openOnJoin: Boolean get() = state.openOnJoin
    val cursorLabel: String get() = labelMode(state.cursorLabel)

    fun update(change: Options.() -> Unit) {
        state.change()
        ApplicationManager
            .getApplication()
            .messageBus
            .syncPublisher(CHANGED)
            .changed(this)
    }

    fun interface Listener {
        fun changed(settings: SelvageSettings)
    }

    companion object {
        const val CURSOR_LABEL_DEFAULT = "none"
        val CURSOR_LABELS = listOf("none", "floating", "chip")

        @Topic.AppLevel
        val CHANGED: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun get(): SelvageSettings = ApplicationManager.getApplication().getService(SelvageSettings::class.java)

        /** `labelMode` in `labels.ts`: anything but `floating` or `chip` is `none`. */
        fun labelMode(value: String?): String =
            if (value == "floating" ||
                value == "chip"
            ) {
                value
            } else {
                CURSOR_LABEL_DEFAULT
            }
    }
}
