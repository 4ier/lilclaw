package com.lilclaw.app.service.gateway

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Writes OpenClaw gateway configuration files.
 * Pure logic — no coroutine scope, no state. Call from any coroutine.
 */
object ConfigWriter {

    private const val TAG = "ConfigWriter"

    /**
     * Write the main openclaw.json config into the rootfs.
     */
    fun writeGatewayConfig(
        rootfsDir: File,
        port: Int,
        provider: String,
        apiKey: String,
        model: String,
    ) {
        val configDir = File(rootfsDir, "root/.openclaw")
        configDir.mkdirs()
        val configFile = File(configDir, "openclaw.json")

        val providerSlug = normalizeProvider(provider)
        val effectiveModel = model.ifBlank { defaultModelFor(providerSlug) }

        val config = JSONObject().apply {
            put("agents", buildAgentsBlock(providerSlug, effectiveModel))
            put("gateway", buildGatewayBlock(port))
            put("commands", JSONObject().apply {
                put("native", "auto")
                put("nativeSkills", "auto")
            })
            putProviderEnv(this, providerSlug, apiKey)
        }

        configFile.writeText(config.toString(2))
        Log.i(TAG, "Configuration written")
    }

    /**
     * Write the android-compat.cjs shim that patches os.networkInterfaces()
     * for Android 10+ /proc/net restrictions.
     */
    fun writeAndroidCompat(rootfsDir: File) {
        val file = File(rootfsDir, "root/android-compat.cjs")
        file.parentFile?.mkdirs()
        file.writeText(
            """
            const os = require('os');
            const _ni = os.networkInterfaces;
            os.networkInterfaces = function() {
              try { return _ni.call(this); } catch(e) {
                return { lo: [{ address: '127.0.0.1', netmask: '255.0.0.0', family: 'IPv4', mac: '00:00:00:00:00:00', internal: true, cidr: '127.0.0.1/8' }] };
              }
            };
            """.trimIndent() + "\n"
        )
    }

    // ── Private helpers ───────────────────────────────────

    private fun normalizeProvider(provider: String): String = when (provider.lowercase()) {
        "openai" -> "openai"
        "anthropic" -> "anthropic"
        "deepseek" -> "deepseek"
        "aws bedrock" -> "amazon-bedrock"
        else -> provider.lowercase()
    }

    private fun defaultModelFor(providerSlug: String): String = when (providerSlug) {
        "openai" -> "gpt-4o"
        "anthropic" -> "claude-sonnet-4-20250514"
        "deepseek" -> "deepseek-chat"
        "amazon-bedrock" -> "anthropic.claude-sonnet-4-20250514-v1:0"
        else -> "gpt-4o"
    }

    private fun buildAgentsBlock(providerSlug: String, model: String) = JSONObject().apply {
        put("defaults", JSONObject().apply {
            put("model", JSONObject().apply {
                put("primary", "$providerSlug/$model")
            })
            put("workspace", "/root/.openclaw/workspace-dev")
            put("skipBootstrap", true)
        })
        put("list", JSONArray().apply {
            put(JSONObject().apply {
                put("id", "dev")
                put("default", true)
                put("workspace", "/root/.openclaw/workspace-dev")
            })
        })
    }

    private fun buildGatewayBlock(port: Int) = JSONObject().apply {
        put("mode", "local")
        put("port", port)
        put("bind", "loopback")
        put("auth", JSONObject().apply {
            put("token", "lilclaw-local")
        })
        put("controlUi", JSONObject().apply {
            put("allowInsecureAuth", true)
        })
    }

    private fun putProviderEnv(config: JSONObject, providerSlug: String, apiKey: String) {
        when (providerSlug) {
            "openai" -> {
                config.put("env", JSONObject().apply { put("OPENAI_API_KEY", apiKey) })
            }
            "anthropic" -> {
                config.put("env", JSONObject().apply { put("ANTHROPIC_API_KEY", apiKey) })
            }
            "deepseek" -> {
                config.put("models", buildDeepSeekModels(apiKey))
            }
        }
    }

    private fun buildDeepSeekModels(apiKey: String) = JSONObject().apply {
        put("mode", "merge")
        put("providers", JSONObject().apply {
            put("deepseek", JSONObject().apply {
                put("baseUrl", "https://api.deepseek.com/v1")
                put("apiKey", apiKey)
                put("api", "openai-completions")
                put("models", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "deepseek-chat")
                        put("name", "DeepSeek Chat")
                        put("reasoning", false)
                        put("input", JSONArray().put("text"))
                        put("contextWindow", 64000)
                        put("maxTokens", 8192)
                    })
                    put(JSONObject().apply {
                        put("id", "deepseek-reasoner")
                        put("name", "DeepSeek Reasoner")
                        put("reasoning", true)
                        put("input", JSONArray().put("text"))
                        put("contextWindow", 64000)
                        put("maxTokens", 8192)
                    })
                })
            })
        })
    }
}
