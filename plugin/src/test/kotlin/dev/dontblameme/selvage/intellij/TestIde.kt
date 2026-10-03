package dev.dontblameme.selvage.intellij

import com.intellij.openapi.application.AccessToken
import com.intellij.testFramework.LoggedErrorProcessor
import java.util.EnumSet

/**
 * The unified IntelliJ IDEA the plugin is tested in carries obfuscated product-only extensions that
 * cannot be created in a test IDE ("Cannot create extension (class=Z.Z.Z.Z.Z) [Plugin:
 * com.intellij.modules.ultimate]"). That one logged error, and only it, is not a test failure.
 */
object TestIde {
    private val processor =
        object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<out String>,
                t: Throwable?,
            ): Set<Action> =
                if (message.startsWith("Cannot create extension (class=Z.") &&
                    message.contains("com.intellij.modules.ultimate")
                ) {
                    EnumSet.noneOf(Action::class.java)
                } else {
                    super.processError(category, message, details, t)
                }
        }

    fun tolerateProductExtensions(): AccessToken = LoggedErrorProcessor.executeWith(processor)
}
