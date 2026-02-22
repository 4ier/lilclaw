package com.lilclaw.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level checks: ensure all user-facing strings are Chinese.
 * Catches English strings that slip through during development.
 */
class ChineseLocalizationTest {

    private val srcRoot = File("src/main/kotlin/com/lilclaw/app")

    // English patterns that should NOT appear in user-facing Text() calls
    private val forbiddenPatterns = listOf(
        "\"Get Started\"",
        "\"Your pocket AI\"",
        "\"Connect your AI\"",
        "\"Setting things up\"",
        "\"Downloading components\"",
        "\"Installing\"",
        "\"Starting AI engine\"",
        "\"Almost ready\"",
        "\"Here we go\"",
        "\"Something went wrong\"",
        "\"Try Again\"",
        "\"Continue\"",
        "\"Settings\"",
        "\"Running\"",
        "\"Stopped\"",
        "\"Starting...\"",
        "\"Preparing...\"",
        "\"Gateway Service\"",
        "\"Keeps the AI gateway\"",
        "\"Powered by OpenClaw\"",
        "\"Not configured\"",
    )

    private fun readKotlinFiles(dir: File): List<Pair<String, String>> {
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown()
            .filter { it.extension == "kt" }
            .map { it.relativeTo(File("app/src/main")).path to it.readText() }
            .toList()
    }

    @Test
    fun `no English UI strings in setup screens`() {
        val files = readKotlinFiles(srcRoot.resolve("ui/setup"))
        assertTrue("Setup screen files should exist", files.isNotEmpty())

        for ((path, content) in files) {
            for (pattern in forbiddenPatterns) {
                assertFalse(
                    "Found English string $pattern in $path",
                    content.contains(pattern)
                )
            }
        }
    }

    @Test
    fun `no English UI strings in settings screen`() {
        val files = readKotlinFiles(srcRoot.resolve("ui/settings"))
        assertTrue("Settings screen files should exist", files.isNotEmpty())

        for ((path, content) in files) {
            for (pattern in forbiddenPatterns) {
                assertFalse(
                    "Found English string $pattern in $path",
                    content.contains(pattern)
                )
            }
        }
    }

    @Test
    fun `no English strings in GatewayService notifications`() {
        val file = srcRoot.resolve("service/GatewayService.kt")
        if (!file.exists()) return
        val content = file.readText()

        for (pattern in forbiddenPatterns) {
            assertFalse(
                "Found English string $pattern in GatewayService.kt",
                content.contains(pattern)
            )
        }
    }

    @Test
    fun `no English strings in GatewayManager logs`() {
        val file = srcRoot.resolve("service/GatewayManager.kt")
        if (!file.exists()) return
        val content = file.readText()

        for (pattern in forbiddenPatterns) {
            assertFalse(
                "Found English string $pattern in GatewayManager.kt",
                content.contains(pattern)
            )
        }
    }

    @Test
    fun `strings xml has Chinese app name`() {
        val stringsFile = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml should exist", stringsFile.exists())
        val content = stringsFile.readText()
        assertTrue("app_name should be 小爪", content.contains("小爪"))
        assertFalse("app_name should not be LilClaw", content.contains(">LilClaw<"))
    }

    @Test
    fun `AndroidManifest uses string resource for label`() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml should exist", manifest.exists())
        val content = manifest.readText()
        assertTrue("label should reference @string/app_name", content.contains("@string/app_name"))
        assertFalse("label should not be hardcoded", content.contains("android:label=\"LilClaw\""))
    }

    @Test
    fun `PROVIDERS list has DeepSeek first`() {
        val file = srcRoot.resolve("ui/setup/SetupScreen.kt")
        if (!file.exists()) return
        val content = file.readText()

        val match = Regex("""PROVIDERS\s*=\s*listOf\(([^)]+)\)""").find(content)
        assertTrue("PROVIDERS list should exist", match != null)

        val providers = match!!.groupValues[1]
        val firstProvider = providers.split(",").first().trim().trim('"')
        assertTrue("First provider should be DeepSeek, got: $firstProvider",
            firstProvider.contains("DeepSeek"))
    }
}
