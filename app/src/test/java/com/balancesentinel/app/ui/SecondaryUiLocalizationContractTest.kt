package com.balancesentinel.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class SecondaryUiLocalizationContractTest {
    private val appDir: File = listOf(File("app"), File("."))
        .first { File(it, "src/main").isDirectory }

    @Test
    fun `console update and widget surfaces contain no Chinese visible literals`() {
        val relativePaths = listOf(
            "ui/console/ConsoleSelectScreen.kt",
            "ui/console/AddPlatformScreen.kt",
            "ui/console/ConsoleScreen.kt",
            "ui/console/ConsoleComponents.kt",
            "ui/screen/UpdateDialog.kt",
            "data/update/ApkDownloader.kt",
            "widget/WidgetConfigActivity.kt",
            "widget/StaticWidgetProvider.kt"
        )

        relativePaths.forEach { path ->
            val file = File(appDir, "src/main/java/com/balancesentinel/app/$path")
            val source = if (file.name == "ConsoleComponents.kt") {
                file.readText().withoutMachineDiagnosticReport()
            } else {
                file.readText()
            }
            val literals = STRING_LITERAL.findAll(source).map { it.value }.toList()
            assertFalse("Chinese visible literal remains in ${file.name}: $literals", literals.any(HAN::containsMatchIn))
        }
    }

    private fun String.withoutMachineDiagnosticReport(): String {
        val start = indexOf("    val content = DebugReportFormatter.formatText(buildString {")
        val end = indexOf("    val clipboard =", startIndex = start.coerceAtLeast(0))
        return if (start >= 0 && end > start) removeRange(start, end) else this
    }

    private companion object {
        val STRING_LITERAL = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
        val HAN = Regex("[\\u4e00-\\u9fff]")
    }
}
