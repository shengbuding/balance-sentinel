package com.balancesentinel.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class PrimaryUiLocalizationContractTest {
    private val appDir: File = listOf(File("app"), File("."))
        .first { File(it, "src/main").isDirectory }

    @Test
    fun `primary UI sources contain no Chinese string literals`() {
        val sourceFiles = listOf(
            "ui/screen/HomeScreen.kt",
            "ui/screen/SettingsScreen.kt",
            "ui/screen/AlertSettingsScreen.kt",
            "ui/screen/DataManagementScreen.kt",
            "ui/screen/ClearDataScreen.kt",
            "ui/screen/BackupRestoreScreen.kt",
            "ui/screen/LogScreen.kt",
            "ui/screen/OnboardingScreen.kt",
            "ui/components/AccountBalanceCard.kt",
            "ui/components/AddAccountDialog.kt",
            "ui/components/EditAccountDialog.kt",
            "ui/components/ProviderCredentialFields.kt"
        ).map { File(appDir, "src/main/java/com/balancesentinel/app/$it") }

        sourceFiles.forEach { file ->
            val literals = STRING_LITERAL.findAll(file.readText()).map { it.value }.toList()
            assertFalse("Chinese visible literal remains in ${file.name}: $literals", literals.any(HAN::containsMatchIn))
        }
    }

    private companion object {
        val STRING_LITERAL = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
        val HAN = Regex("[\\u4e00-\\u9fff]")
    }
}
