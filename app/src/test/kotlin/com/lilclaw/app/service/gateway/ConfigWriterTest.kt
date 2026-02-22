package com.lilclaw.app.service.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for ConfigWriter — provider normalization, model defaults, config output.
 */
class ConfigWriterTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun writeAndParse(
        provider: String,
        apiKey: String = "test-key",
        model: String = "",
    ): JSONObject {
        val rootfs = tempDir.newFolder("rootfs")
        ConfigWriter.writeGatewayConfig(rootfs, 3000, provider, apiKey, model)
        val configFile = rootfs.resolve("root/.openclaw/openclaw.json")
        assertTrue("Config file should exist", configFile.exists())
        return JSONObject(configFile.readText())
    }

    // ── Provider normalization ────────────────────────────

    @Test
    fun `DeepSeek provider normalizes to deepseek`() {
        val config = writeAndParse("DeepSeek")
        val primary = config.getJSONObject("agents")
            .getJSONObject("defaults")
            .getJSONObject("model")
            .getString("primary")
        assertTrue("Primary model should start with deepseek/", primary.startsWith("deepseek/"))
    }

    @Test
    fun `OpenAI provider normalizes correctly`() {
        val config = writeAndParse("OpenAI")
        val primary = config.getJSONObject("agents")
            .getJSONObject("defaults")
            .getJSONObject("model")
            .getString("primary")
        assertEquals("openai/gpt-4o", primary)
    }

    @Test
    fun `Anthropic provider normalizes correctly`() {
        val config = writeAndParse("Anthropic")
        val primary = config.getJSONObject("agents")
            .getJSONObject("defaults")
            .getJSONObject("model")
            .getString("primary")
        assertTrue(primary.startsWith("anthropic/"))
    }

    @Test
    fun `AWS Bedrock normalizes to amazon-bedrock`() {
        val config = writeAndParse("AWS Bedrock")
        val primary = config.getJSONObject("agents")
            .getJSONObject("defaults")
            .getJSONObject("model")
            .getString("primary")
        assertTrue("Should use amazon-bedrock prefix", primary.startsWith("amazon-bedrock/"))
    }

    @Test
    fun `自定义 provider passes through lowercase`() {
        val config = writeAndParse("自定义", model = "my-model")
        val primary = config.getJSONObject("agents")
            .getJSONObject("defaults")
            .getJSONObject("model")
            .getString("primary")
        assertEquals("自定义/my-model", primary)
    }

    // ── Default models ────────────────────────────────────

    @Test
    fun `DeepSeek defaults to deepseek-chat`() {
        val config = writeAndParse("DeepSeek")
        val primary = config.getJSONObject("agents")
            .getJSONObject("defaults")
            .getJSONObject("model")
            .getString("primary")
        assertEquals("deepseek/deepseek-chat", primary)
    }

    @Test
    fun `explicit model overrides default`() {
        val config = writeAndParse("DeepSeek", model = "deepseek-reasoner")
        val primary = config.getJSONObject("agents")
            .getJSONObject("defaults")
            .getJSONObject("model")
            .getString("primary")
        assertEquals("deepseek/deepseek-reasoner", primary)
    }

    // ── API key placement ─────────────────────────────────

    @Test
    fun `OpenAI key goes to env OPENAI_API_KEY`() {
        val config = writeAndParse("OpenAI", apiKey = "sk-test-123")
        val env = config.getJSONObject("env")
        assertEquals("sk-test-123", env.getString("OPENAI_API_KEY"))
    }

    @Test
    fun `Anthropic key goes to env ANTHROPIC_API_KEY`() {
        val config = writeAndParse("Anthropic", apiKey = "sk-ant-test")
        val env = config.getJSONObject("env")
        assertEquals("sk-ant-test", env.getString("ANTHROPIC_API_KEY"))
    }

    @Test
    fun `DeepSeek key goes to models providers block`() {
        val config = writeAndParse("DeepSeek", apiKey = "sk-ds-test")
        val dsProvider = config.getJSONObject("models")
            .getJSONObject("providers")
            .getJSONObject("deepseek")
        assertEquals("sk-ds-test", dsProvider.getString("apiKey"))
        assertEquals("https://api.deepseek.com/v1", dsProvider.getString("baseUrl"))
    }

    // ── Gateway config ────────────────────────────────────

    @Test
    fun `gateway config has correct port and local mode`() {
        val config = writeAndParse("DeepSeek")
        val gw = config.getJSONObject("gateway")
        assertEquals("local", gw.getString("mode"))
        assertEquals(3000, gw.getInt("port"))
        assertEquals("loopback", gw.getString("bind"))
    }

    @Test
    fun `gateway auth token is lilclaw-local`() {
        val config = writeAndParse("DeepSeek")
        val token = config.getJSONObject("gateway")
            .getJSONObject("auth")
            .getString("token")
        assertEquals("lilclaw-local", token)
    }

    // ── Agent config ──────────────────────────────────────

    @Test
    fun `agent workspace and skipBootstrap are set`() {
        val config = writeAndParse("DeepSeek")
        val defaults = config.getJSONObject("agents").getJSONObject("defaults")
        assertEquals("/root/.openclaw/workspace-dev", defaults.getString("workspace"))
        assertTrue(defaults.getBoolean("skipBootstrap"))
    }

    @Test
    fun `agent list has dev agent as default`() {
        val config = writeAndParse("DeepSeek")
        val agents = config.getJSONObject("agents").getJSONArray("list")
        assertEquals(1, agents.length())
        val dev = agents.getJSONObject(0)
        assertEquals("dev", dev.getString("id"))
        assertTrue(dev.getBoolean("default"))
    }

    // ── Android compat shim ───────────────────────────────

    @Test
    fun `writeAndroidCompat creates shim file`() {
        val rootfs = tempDir.newFolder("rootfs2")
        ConfigWriter.writeAndroidCompat(rootfs)
        val shim = rootfs.resolve("root/android-compat.cjs")
        assertTrue("Shim file should exist", shim.exists())
        val content = shim.readText()
        assertTrue("Shim should patch networkInterfaces", content.contains("networkInterfaces"))
    }
}
