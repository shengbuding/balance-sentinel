package com.balancesentinel.app.data.debug

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugSourceHealthTest {
    private val appDir: File = listOf(File("app"), File("."))
        .first { File(it, "src/main").isDirectory }

    // Mutation caught: declaring an activity class that does not exist in the application.
    @Test
    fun `manifest contains no nonexistent console activity`() {
        val manifest = xml(File(appDir, "src/main/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        val names = (0 until activities.length).map { index ->
            activities.item(index).attributes.getNamedItem("android:name")?.nodeValue.orEmpty()
        }

        assertFalse(names.contains(".ui.console.ConsoleWebViewActivity"))
    }

    // Mutation caught: making lint non-blocking or hiding findings behind any baseline.
    @Test
    fun `lint is blocking and baseline free`() {
        val build = File(appDir, "build.gradle.kts").readText()

        assertTrue(build.contains("abortOnError = true"))
        assertFalse(build.contains("baseline ="))
        assertFalse(File(appDir, "lint-baseline.xml").exists())
    }

    // Mutation caught: omitting any English Console-clear string that exists in the default locale.
    @Test
    fun `console clear strings exist in default and english resources`() {
        val required = setOf(
            "data_clear_console_title",
            "data_clear_console_desc",
            "data_clear_console_btn",
            "data_confirm_clear_console"
        )
        val defaults = stringNames(File(appDir, "src/main/res/values/strings.xml"))
        val english = stringNames(File(appDir, "src/main/res/values-en/strings.xml"))

        assertTrue(defaults.containsAll(required))
        assertTrue(english.containsAll(required))
    }

    // Mutation caught: introducing an invisible literal NUL into Kotlin source.
    @Test
    fun `usage script source contains no literal nul byte`() {
        val bytes = File(
            appDir,
            "src/main/java/com/balancesentinel/app/data/api/balance/UsageScriptExecutor.kt"
        ).readBytes()

        assertFalse(bytes.contains(0))
    }

    // Mutation caught: keeping a second Console diagnostic model beside ApiDebugEntry.
    @Test
    fun `console diagnostics use no duplicate api log model`() {
        val components = File(
            appDir,
            "src/main/java/com/balancesentinel/app/ui/console/ConsoleComponents.kt"
        ).readText()
        val screen = File(
            appDir,
            "src/main/java/com/balancesentinel/app/ui/console/ConsoleScreen.kt"
        ).readText()

        assertFalse(components.contains("data class ApiLogEntry"))
        assertFalse(screen.contains("MutableList<ApiLogEntry>"))
    }

    // Mutation caught: accepting raw account script text in the API debug dialog or clipboard builder.
    @Test
    fun `debug dialog accepts no raw custom script source`() {
        val source = File(
            appDir,
            "src/main/java/com/balancesentinel/app/ui/components/DebugDialog.kt"
        ).readText()

        assertFalse(source.contains("customScript"))
        assertFalse(source.contains("scriptPreview"))
    }

    private fun stringNames(file: File): Set<String> {
        val document = xml(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).mapNotNull { index ->
            nodes.item(index).attributes.getNamedItem("name")?.nodeValue
        }.toSet()
    }

    private fun xml(file: File) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(file)
}
