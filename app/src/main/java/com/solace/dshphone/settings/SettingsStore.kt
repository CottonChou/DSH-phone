package com.solace.dshphone.settings

import android.content.SharedPreferences
import com.solace.dshphone.core.action.ApprovalMode
import com.solace.dshphone.core.model.ModelEndpoint
import com.solace.dshphone.core.model.Protocol
import org.json.JSONObject

/**
 * All persisted state. The API key lives in Keystore-backed encrypted prefs;
 * everything else is plain JSON so it is easy to inspect and back up.
 */
class SettingsStore(
    private val secure: SharedPreferences,
    private val plain: SharedPreferences,
) {

    // --- model endpoint -------------------------------------------------

    var providerId: String
        get() = plain.getString("providerId", "deepseek") ?: "deepseek"
        set(value) = plain.edit().putString("providerId", value).apply()

    var baseUrl: String
        get() = plain.getString("baseUrl", "https://api.deepseek.com") ?: "https://api.deepseek.com"
        set(value) = plain.edit().putString("baseUrl", value).apply()

    var modelName: String
        get() = plain.getString("modelName", "deepseek-chat") ?: "deepseek-chat"
        set(value) = plain.edit().putString("modelName", value).apply()

    var protocol: Protocol
        get() = runCatching { Protocol.valueOf(plain.getString("protocol", Protocol.OPENAI_COMPATIBLE.name)!!) }
            .getOrDefault(Protocol.OPENAI_COMPATIBLE)
        set(value) = plain.edit().putString("protocol", value.name).apply()

    var apiKey: String
        get() = secure.getString("apiKey", "") ?: ""
        set(value) = secure.edit().putString("apiKey", value).apply()

    fun hasEndpoint(): Boolean = apiKey.isNotBlank() && baseUrl.startsWith("http")

    fun endpoint(): ModelEndpoint? = if (hasEndpoint()) ModelEndpoint(
        label = providerId,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = modelName,
        protocol = protocol,
    ) else null

    // --- agent behaviour --------------------------------------------------

    /** ASK by default: the agent asks before spending money, ALWAYS. */
    var approvalMode: ApprovalMode
        get() = if (plain.getBoolean("autonomous", false)) ApprovalMode.AUTONOMOUS else ApprovalMode.ASK
        set(value) = plain.edit().putBoolean("autonomous", value == ApprovalMode.AUTONOMOUS).apply()

    // --- engine -----------------------------------------------------------

    var preferredEngine: String
        get() = plain.getString("engine", "builtin") ?: "builtin"
        set(value) = plain.edit().putString("engine", value).apply()

    /** Crash bookkeeping for the external engine: three classes of failure. */
    fun recordEngineCrash(stage: String) {
        val obj = JSONObject(plain.getString("engineCrashes", "{}"))
        obj.put(stage, obj.optInt(stage, 0) + 1)
        obj.put("last", System.currentTimeMillis())
        plain.edit().putString("engineCrashes", obj.toString()).apply()
    }

    fun engineCrashCounts(): Map<String, Int> {
        val obj = JSONObject(plain.getString("engineCrashes", "{}"))
        val out = LinkedHashMap<String, Int>()
        for (key in obj.keys()) out[key] = obj.optInt(key, 0)
        return out
    }

    /** Two consecutive abnormal exits put the engine into SafeMode. */
    fun isSafeMode(): Boolean {
        val c = engineCrashCounts()
        val early = (c["early"] ?: 0) + (c["late"] ?: 0)
        val recentLate = (c["late"] ?: 0) > 0 && System.currentTimeMillis() - (c["last"] ?: 0) < 24 * 3600_000
        return early >= 2 || (recentLate && (c["late"] ?: 0) >= 2)
    }

    fun resetEngineCrashes() = plain.edit().putString("engineCrashes", "{}").apply()

    // --- memory persistence -------------------------------------------------

    fun exportMemories(json: String) = plain.edit().putString("memories", json).apply()

    fun importMemories(): String = plain.getString("memories", "[]") ?: "[]"
}
