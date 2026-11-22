package com.craft.capturespace

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.craft.capturespace.ai.Pipeline
import com.craft.capturespace.data.Notifications
import com.craft.capturespace.data.Repo
import com.craft.capturespace.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var repo: Repo
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        // No AI key is used: erase any key an earlier version saved on this phone.
        settings.forgetOldAiKey()
        repo = Repo(this)
        Notifications.createChannels(this)
        if (settings.notificationShortcut) Notifications.showShortcut(this)
        scope.launch {
            repo.purgeTrash()
            com.craft.capturespace.ai.Models.refreshNow(this@App) // size + SHA-256 check before any use
            Pipeline.resumePending(this@App)
        }
        // When the connection comes back, finish what couldn't be analysed offline.
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        scope.launch { Pipeline.resumePending(this@App) }
                    }
                }
            )
        } catch (_: Exception) {
        }
    }

    /** Frees the on-device engines when Android asks for memory and nothing is being analysed. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND && !com.craft.capturespace.ai.LocalAi.busy()) {
            scope.launch { com.craft.capturespace.ai.LocalAi.close() }
        }
    }

    companion object {
        lateinit var instance: App
            private set

        /** True while a Open Essential screen is on top; "ready" notifications are skipped then. */
        @Volatile
        var visible = false
    }
}
