package com.solace.dshphone.keepalive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Vendor keep-alive entries. These screens are private pages the OS does not
 * expose state for: we can only open them and ask the user to toggle by hand,
 * then re-check what we can when they come back.
 */
object ColorOsKeepAlive {

    data class Entry(val label: String, val explanation: String)

    val entries = listOf(
        Entry("后台运行与电池", "允许 DSH-phone 在后台运行、不限制电池"),
        Entry("自启动管理", "允许 DSH-phone 开机自启动"),
        Entry("后台弹出界面", "允许任务把需要你处理的界面弹到前台"),
    )

    fun isColorOs(): Boolean = listOf("oppo", "oneplus", "realme")
        .any { android.os.Build.MANUFACTURER.lowercase().contains(it) }

    /** Try the candidate pages in order; fall back to the app details page. */
    fun open(context: Context, index: Int): Boolean {
        val pkg = context.packageName
        val candidates = when (index) {
            0 -> listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )
            1 -> listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.FakeActivity"),
                ComponentName("com.oplus.battery", "com.oplus.battery.BatteryActivity"),
            )
            else -> listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.FloatingWindowTransparentActivity"),
            )
        }
        for (cn in candidates) {
            val intent = Intent().setComponent(cn).putExtra("package_name", pkg)
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return true
        }
        return runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            true
        }.getOrDefault(false)
    }
}
