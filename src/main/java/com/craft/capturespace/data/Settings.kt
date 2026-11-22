package com.craft.capturespace.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


class Settings(ctx: Context) {
    val prefs: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = prefs.getString(k, d) ?: d
    private fun put(k: String, v: String) = prefs.edit().putString(k, v).apply()
    private fun bool(k: String, d: Boolean) = prefs.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()

    /** Download on-device models only over Wi-Fi (default on). */
    var modelsWifiOnly: Boolean
        get() = bool("models_wifi_only", true)
        set(v) = put("models_wifi_only", v)
    var autoAnalyze: Boolean
        get() = bool("auto_analyze", true)
        set(v) = put("auto_analyze", v)
    /** The app is English only: speech is recognised as English and notes are written in English. */
    val language: String get() = "en"

    /**
     * Link previews: "ask" (not decided yet: asked on the first shared link), "on" or "off".
     * When on, only the shared public page itself is read (title, description, preview picture).
     */
    var linkPreviews: String
        get() = str("link_previews", "ask")
        set(v) = put("link_previews", v)

    /** The profile picture: one of [com.craft.capturespace.ui.Avatars] ids, or "none". */
    var avatar: String
        get() = str("avatar", "none")
        set(v) = put("avatar", v)

    /** 0 = follow the phone, 1 = light, 2 = dark */
    var theme: Int
        get() = prefs.getInt("theme", 0)
        set(v) = prefs.edit().putInt("theme", v).apply()
    var showFilters: Boolean
        get() = bool("filters", true)
        set(v) = put("filters", v)
    var haptics: Boolean
        get() = bool("haptics", true)
        set(v) = put("haptics", v)
    var onboarded: Boolean
        get() = bool("onboarded", false)
        set(v) = put("onboarded", v)

    // Shortcuts that stand in for the Essential Key.
    var volumeShortcut: Boolean
        get() = bool("volume_shortcut", true)
        set(v) = put("volume_shortcut", v)
    /** "screenshot" or "open" */
    var doublePressAction: String
        get() = str("double_action", "screenshot")
        set(v) = put("double_action", v)
    /** "voice" or "none" */
    var holdAction: String
        get() = str("hold_action", "voice")
        set(v) = put("hold_action", v)
    var floatingButton: Boolean
        get() = bool("floating", false)
        set(v) = put("floating", v)
    var notificationShortcut: Boolean
        get() = bool("notification_shortcut", false)
        set(v) = put("notification_shortcut", v)
    /** What the power-button assistant opens: "menu", "screenshot", "voice" or "note". */
    var assistAction: String
        get() = str("assist_action", "menu")
        set(v) = put("assist_action", v)

    var reminders: Boolean
        get() = bool("reminders", true)
        set(v) = put("reminders", v)
    var reminderLeadMinutes: Int
        get() = prefs.getInt("lead", 10)
        set(v) = prefs.edit().putInt("lead", v).apply()

    /**
     * The app uses no AI key of any kind. Earlier versions stored a Gemini key here; this erases
     * it, its Android Keystore key and the old model settings from the phone.
     */
    fun forgetOldAiKey() {
        if (prefs.contains("ai_key") || prefs.contains("model") || prefs.contains("api_base")) {
            prefs.edit().remove("ai_key").remove("model").remove("embed_model").remove("api_base").apply()
        }
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias("capture_space_ai_key")) ks.deleteEntry("capture_space_ai_key")
        } catch (_: Exception) {
        }
    }
}
