package dev.dontblameme.selvage.intellij

import com.google.gson.JsonParser
import dev.dontblameme.selvage.intellij.settings.SelvageSettings
import junit.framework.TestCase
import java.io.File

/** The settings and their defaults are the VS Code client's `contributes.configuration`. */
class SettingsTest : TestCase() {
    fun testTheDefaultsAreTheVsCodeClientsOwn() {
        val manifest = JsonParser.parseString(File(Siblings.vscodeClient(), "package.json").readText()).asJsonObject
        val properties =
            manifest.getAsJsonObject("contributes").getAsJsonObject("configuration").getAsJsonObject("properties")
        assertEquals(
            setOf(
                "selvage.serverUrl",
                "selvage.displayName",
                "selvage.autoSave",
                "selvage.openOnJoin",
                "selvage.cursorLabel",
            ),
            properties.keySet(),
        )
        val defaults = SelvageSettings.Options()
        assertEquals("", defaults.serverUrl)
        assertFalse(properties.getAsJsonObject("selvage.serverUrl").has("default"))
        assertEquals("", defaults.displayName)
        assertFalse(properties.getAsJsonObject("selvage.displayName").has("default"))
        assertEquals(properties.getAsJsonObject("selvage.autoSave").get("default").asBoolean, defaults.autoSave)
        assertEquals(properties.getAsJsonObject("selvage.openOnJoin").get("default").asBoolean, defaults.openOnJoin)
        val label = properties.getAsJsonObject("selvage.cursorLabel")
        assertEquals(label.get("default").asString, defaults.cursorLabel)
        assertEquals(label.getAsJsonArray("enum").map { it.asString }, SelvageSettings.CURSOR_LABELS)
        assertNull(defaults.lastServer)
        assertNull(defaults.lastDisplayName)
    }

    fun testAnUnknownLabelModeReadsAsNone() {
        assertEquals("none", SelvageSettings.labelMode("bogus"))
        assertEquals("none", SelvageSettings.labelMode(null))
        assertEquals("chip", SelvageSettings.labelMode("chip"))
        assertEquals("floating", SelvageSettings.labelMode("floating"))
    }
}
