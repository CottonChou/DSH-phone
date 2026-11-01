package com.solace.dshphone.engine

import com.solace.dshphone.settings.SettingsStore

/**
 * Chooses and supervises execution engines.
 *
 * - BUILTIN: the app's own loop drives phone and web capabilities directly.
 * - DSH: an external engine runs behind a host bridge; every capability call
 *   passes the HostSafetyPolicy first, then the engine's own approval.
 *
 * Crashes are classified (never started / early / late) and counted. Two
 * consecutive abnormal exits flip the external engine into SafeMode: it stops
 * restarting and the UI suggests falling back to the built-in engine.
 */
class EngineHost(private val settings: SettingsStore) {

    enum class Kind { BUILTIN, DSH }

    enum class CrashStage { START, EARLY, LATE }

    fun select(kind: Kind): Kind {
        if (kind == Kind.DSH && settings.isSafeMode()) return Kind.BUILTIN
        settings.preferredEngine = if (kind == Kind.DSH) "dsh" else "builtin"
        return kind
    }

    fun current(): Kind = if (settings.preferredEngine == "dsh" && !settings.isSafeMode()) Kind.DSH else Kind.BUILTIN

    fun reportCrash(stage: CrashStage) {
        settings.recordEngineCrash(stage.name.lowercase())
    }

    fun safeMode(): Boolean = settings.isSafeMode()

    fun describe(): String = when {
        safeMode() -> "外部引擎连续异常，已进入安全模式；新对话请使用内置引擎"
        current() == Kind.DSH -> "外部引擎（DSH）"
        else -> "内置引擎"
    }
}
