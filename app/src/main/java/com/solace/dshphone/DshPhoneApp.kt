package com.solace.dshphone

import android.app.Application
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.solace.dshphone.engine.EngineHost
import com.solace.dshphone.settings.SettingsStore
import com.solace.dshphone.task.TaskCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DshPhoneApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: SettingsStore
        private set
    lateinit var taskCenter: TaskCenter
        private set
    lateinit var engineHost: EngineHost
        private set

    /** Private desktop-mode browser used by the web capability; created lazily on the main thread. */
    @Volatile
    private var _agentWebView: android.webkit.WebView? = null

    val agentWebView: android.webkit.WebView?
        get() {
            if (_agentWebView == null) {
                synchronized(this) {
                    if (_agentWebView == null) {
                        _agentWebView = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Main) {
                            com.solace.dshphone.web.buildAgentWebView(this@DshPhoneApp)
                        }
                    }
                }
            }
            return _agentWebView
        }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(encryptedPrefs(this, "dshphone-secure"), getSharedPreferences("dshphone", MODE_PRIVATE))
        taskCenter = TaskCenter(appScope)
        engineHost = EngineHost(settings)
    }

    companion object {
        fun encryptedPrefs(context: Context, name: String = "dshphone-secure"): android.content.SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context, name, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        fun of(context: Context): DshPhoneApp = context.applicationContext as DshPhoneApp
    }
}
