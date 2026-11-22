package com.craft.capturespace.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.craft.capturespace.App

sealed class Screen {
    data object Home : Screen()
    data class Detail(val id: Long) : Screen()
    data class Collection(val id: Long) : Screen()
    data object Search : Screen()
    data object Settings : Screen()
    data object Trash : Screen()
    data object Onboarding : Screen()
    data object Models : Screen()
}

class Nav(start: Screen) {
    val stack = mutableStateListOf(start)
    val top: Screen get() = stack.last()
    var homeTab by mutableIntStateOf(0)
    fun push(s: Screen) { stack.add(s) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun reset(s: Screen) { stack.clear(); stack.add(s) }
}

class MainActivity : ComponentActivity() {
    private lateinit var nav: Nav
    private var themeMode by mutableIntStateOf(0)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val s = App.instance.settings
        themeMode = s.theme
        nav = Nav(if (s.onboarded) Screen.Home else Screen.Onboarding)
        handle(intent)
        setContent {
            SpaceTheme(themeMode) {
                val t = LocalTones.current
                BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
                Box(Modifier.fillMaxSize().background(t.bg)) {
                    AnimatedContent(
                        targetState = nav.top,
                        transitionSpec = {
                            // Original: nav_default_*_anim = alpha 0↔1 over config_navAnimTime (150 ms).
                            fadeIn(tween(Motion.NAV_MS)) togetherWith fadeOut(tween(Motion.NAV_MS))
                        },
                        label = "nav"
                    ) { screen ->
                        when (screen) {
                            Screen.Home -> HomeScreen(nav)
                            is Screen.Detail -> Centered { DetailScreen(nav, screen.id) }
                            is Screen.Collection -> CollectionScreen(nav, screen.id)
                            Screen.Search -> Centered(960) { SearchScreen(nav) }
                            Screen.Settings -> Centered { SettingsScreen(nav, onTheme = { themeMode = it }) }
                            Screen.Trash -> Centered { TrashScreen(nav) }
                            Screen.Models -> Centered { ModelsScreen(nav) }
                            Screen.Onboarding -> Centered { OnboardingScreen {
                                s.onboarded = true
                                if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                nav.reset(Screen.Home)
                            } }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() { super.onResume(); App.visible = true }
    override fun onPause() { super.onPause(); App.visible = false }

    private fun handle(i: Intent?) {
        if (i == null || !App.instance.settings.onboarded) return
        val card = i.getLongExtra("card", -1)
        if (card > 0) { nav.reset(Screen.Home); nav.push(Screen.Detail(card)) }
        when (i.getStringExtra("open")) {
            "search" -> { nav.reset(Screen.Home); nav.push(Screen.Search) }
            "tasks" -> { nav.reset(Screen.Home); nav.homeTab = 3 }
        }
    }
}
