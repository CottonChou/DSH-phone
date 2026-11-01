package com.solace.dshphone.core.action

import com.solace.dshphone.core.safety.DeviceAction

/** How much a tool can move the world. */
enum class Risk { READ, WRITE, EXTERNAL }

/**
 * Judgement of one concrete action. Irreversible actions get pacing and,
 * when they spend money, an approval gate.
 */
data class ClassifiedRisk(
    val risk: Risk,
    val irreversible: Boolean,
    val payment: Boolean,
    val outward: Boolean,
)

object RiskJudge {

    private val outwardButtons = listOf(
        "发送", "提交", "发布", "立即沟通", "打招呼", "投递", "回复", "转发",
        "send", "submit", "post", "reply", "apply",
    )

    private val paymentButtons = listOf(
        "支付", "付款", "确认付款", "立即支付", "下单", "提交订单", "pay now", "checkout",
    )

    private val currencyPattern = Regex("""[¥￥$€£]\s*[0-9][0-9,\.]*|([0-9][0-9,\.]*)\s*(元|圆)""")

    fun classify(
        action: DeviceAction,
        targetText: String,
        onScreenText: String,
    ): ClassifiedRisk {
        val lower = targetText.lowercase()
        val isExternal = action is DeviceAction.External ||
            outwardButtons.any { lower.contains(it.lowercase()) } ||
            paymentButtons.any { lower.contains(it.lowercase()) }
        val isPayment = paymentButtons.any { lower.contains(it.lowercase()) } ||
            (action is DeviceAction.External && currencyPattern.containsMatchIn(onScreenText))
        val risk = when {
            action is DeviceAction.Read -> Risk.READ
            isPayment -> Risk.EXTERNAL
            isExternal -> Risk.EXTERNAL
            action is DeviceAction.Tap || action is DeviceAction.Input || action is DeviceAction.Gesture -> Risk.WRITE
            else -> Risk.READ
        }
        val irreversible = risk == Risk.EXTERNAL || (isPayment && risk != Risk.READ)
        return ClassifiedRisk(
            risk = risk,
            irreversible = irreversible,
            payment = isPayment,
            outward = isExternal,
        )
    }
}

/**
 * Minimum spacing between irreversible outward actions on the same target.
 * The first action goes immediately; subsequent ones wait a randomized
 * interval that grows with recent volume.
 */
class OutwardPacing(
    private val minBaseMs: Long = 8_000,
    private val maxBaseMs: Long = 20_000,
    private val maxCapMs: Long = 30_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Last(val target: String, val atMs: Long)

    private var last: Last? = null
    private val recent = ArrayList<Long>()

    /** How long to wait before the next outward action on [target], or 0 to go now. */
    fun waitRequiredMs(target: String): Long {
        val prev = last ?: return 0
        if (prev.target != target) return 0
        val now = clock()
        val windowStart = now - 30 * 60_000
        recent.removeAll { it < windowStart }
        val grow = recent.size * 500L
        val lo = minOf(minBaseMs + grow / 2, maxCapMs)
        val hi = minOf(maxBaseMs + grow, maxCapMs)
        val interval = (lo..hi).random()
        val elapsed = now - prev.atMs
        return maxOf(0, interval - elapsed)
    }

    fun markDone(target: String) {
        last = Last(target, clock())
        recent.add(clock())
    }
}
