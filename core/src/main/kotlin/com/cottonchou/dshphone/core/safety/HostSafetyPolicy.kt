package com.cottonchou.dshphone.core.safety

/**
 * Host-level safety policy. Every device-capability call passes through here
 * before the engine's own approval logic gets a say.
 */
enum class PackageCategory { OWN, BANK, COMMS, SYSTEM, PAYMENT, ORDINARY }

enum class SafetyVerdict { ALLOW, REQUIRE_CONFIRM, DENY_HARD, DENY_READ_ONLY }

data class SafetyDecision(
    val verdict: SafetyVerdict,
    /** Human-readable reason, surfaced to the model and the user. */
    val reason: String,
) {
    companion object {
        fun allow() = SafetyDecision(SafetyVerdict.ALLOW, "")
    }
}

/** Authorization granted by the user inside one task. Expires with the task. */
data class TaskGrant(
    val target: String,
    val kind: GrantKind,
    val issuedAtMs: Long,
) {
    enum class GrantKind { SYSTEM_WRITE, COMMS_WRITE_ONCE }
}

/**
 * Classification of packages into protection tiers. Categories are prefix- and
 * keyword-based and shipped as data, not per-app adapters.
 */
object PackageClassifier {

    private val bankHints = listOf(
        "bank", "icbc", "ccb", "abc", "boc", "bcm", "cib", "psbc", "citic", "cmbc",
        "unionpay", "银联", "银帽子", "quickpay", "wallet",
    )

    private val paymentHints = listOf(
        "alipay", "tenpay", "wechatpay", "pay", "jdjr", "lufax",
    )

    private val commsHints = listOf(
        "tencent.mm", "tencent.mobileqq", "whatsapp", "telegram", "discord", "slack",
        "sms", "mms", "dialer", "contacts", "mail",
    )

    private val systemHints = listOf(
        "com.android.settings", "com.android.systemui", "android", "com.android",
        "com.miui.securitycenter", "com.coloros.safecenter", "com.oppo.market",
    )

    fun categorize(packageName: String, ownPackage: String): PackageCategory {
        if (packageName == ownPackage) return PackageCategory.OWN
        val p = packageName.lowercase()
        return when {
            systemHints.any { p == it || p.startsWith(it) } -> PackageCategory.SYSTEM
            bankHints.any { p.contains(it) } -> PackageCategory.BANK
            paymentHints.any { p.endsWith(it) || p == it } -> PackageCategory.PAYMENT
            commsHints.any { p.contains(it) } -> PackageCategory.COMMS
            else -> PackageCategory.ORDINARY
        }
    }
}

/**
 * The one policy object every run is built with. Immutable during a task;
 * task-level grants are granted into a mutable grant set owned by the run.
 */
class HostSafetyPolicy(
    private val ownPackage: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val grants = ArrayList<TaskGrant>()

    /** Evaluate a device action before it reaches the executor. */
    fun evaluate(
        packageName: String,
        action: DeviceAction,
        userPresent: Boolean,
    ): SafetyDecision {
        val category = PackageClassifier.categorize(packageName, ownPackage)
        if (category == PackageCategory.OWN && !userPresent) {
            // Never let the agent operate its own UI unprompted: that is how
            // a stuck loop starts approving itself.
            return SafetyDecision(SafetyVerdict.DENY_HARD, "不能操作本应用自身")
        }
        if (category == PackageCategory.BANK) {
            // No toggle, no grant, no exception. The user does banking by hand.
            return SafetyDecision(SafetyVerdict.DENY_HARD, "银行类应用禁止自动化操作，请亲自完成")
        }
        if (category == PackageCategory.SYSTEM) {
            val write = action is DeviceAction.Tap || action is DeviceAction.Input ||
                action is DeviceAction.Gesture
            if (write && !hasGrant(packageName, TaskGrant.GrantKind.SYSTEM_WRITE)) {
                return SafetyDecision(SafetyVerdict.DENY_READ_ONLY, "系统相关应用默认只读；需要修改时请在本次任务中按提示授权")
            }
            return SafetyDecision.allow()
        }
        if (category == PackageCategory.COMMS && action is DeviceAction.External) {
            if (!userPresent) {
                return SafetyDecision(SafetyVerdict.DENY_HARD, "无人在场时不允许代发消息")
            }
            return SafetyDecision(SafetyVerdict.REQUIRE_CONFIRM, "向通讯类应用发送内容需要逐次确认")
        }
        return SafetyDecision.allow()
    }

    fun grant(packageName: String, kind: TaskGrant.GrantKind) {
        grants.add(TaskGrant(packageName, kind, clock()))
    }

    fun clearGrants() = grants.clear()

    private fun hasGrant(packageName: String, kind: TaskGrant.GrantKind): Boolean =
        grants.any { it.target == packageName && it.kind == kind }
}

/** The four primitive device actions, capability-agnostic. */
sealed interface DeviceAction {
    data object Tap : DeviceAction
    data class Input(val text: String) : DeviceAction
    data class Gesture(val kind: String) : DeviceAction
    /** Sends, submits, posts: anything that reaches another person or the outside world. */
    data class External(val summary: String) : DeviceAction
    data object Read : DeviceAction
}
