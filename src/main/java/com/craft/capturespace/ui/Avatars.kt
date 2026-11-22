package com.craft.capturespace.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craft.capturespace.App
import com.craft.capturespace.R

/**
 * Profile pictures the person can pick in Settings (artwork supplied by the app's owner).
 * Only the id is saved, in the app's settings, so the choice survives restarts and updates.
 */
object Avatars {
    data class Avatar(val id: String, val res: Int, val description: String)

    val ALL = listOf(
        Avatar("cat", R.drawable.avatar_cat, "Orange cat with headphones"),
        Avatar("husky", R.drawable.avatar_husky, "Husky with a red scarf"),
        Avatar("panda", R.drawable.avatar_panda, "Panda in a green hoodie and cap"),
        Avatar("fox", R.drawable.avatar_fox, "Fox with goggles"),
        Avatar("penguin", R.drawable.avatar_penguin, "Penguin with a yellow hat"),
    )

    /** The chosen id, shared by the bottom bar and Settings so a change shows at once. */
    val current = mutableStateOf(App.instance.settings.avatar)

    fun set(id: String) { App.instance.settings.avatar = id; current.value = id }

    fun find(id: String): Avatar? = ALL.firstOrNull { it.id == id }
}

/** The profile circle: the chosen avatar, or a plain head-and-shoulders outline for "None". */
@Composable
fun AvatarCircle(size: Dp, modifier: Modifier = Modifier) {
    val t = LocalTones.current
    val a = Avatars.find(Avatars.current.value)
    Box(modifier.size(size).clip(CircleShape).background(t.text), contentAlignment = Alignment.Center) {
        if (a != null) {
            Image(painterResource(a.res), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val fg = t.card
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = this.size.width
                drawCircle(fg.copy(alpha = 0.85f), radius = w * 0.17f, center = Offset(w / 2, w * 0.40f))
                drawCircle(fg.copy(alpha = 0.85f), radius = w * 0.34f, center = Offset(w / 2, w * 1.02f))
            }
        }
    }
}

/** "Profile picture" row in Settings: None plus the five avatars; the chosen one has a red ring. */
@Composable
fun AvatarPicker() {
    val t = LocalTones.current
    val chosen = Avatars.current.value
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val ring = { on: Boolean -> if (on) Modifier.border(3.dp, Palette.Red, CircleShape) else Modifier.border(1.dp, t.chip, CircleShape) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).then(ring(chosen == "none")).padding(4.dp).clip(CircleShape).background(t.chip)
                    .clickable(role = Role.RadioButton) { Avatars.set("none") }
                    .semantics { contentDescription = "No profile picture"; selected = chosen == "none" },
                contentAlignment = Alignment.Center
            ) { Text("None", color = t.text2, fontSize = 12.sp) }
        }
        Avatars.ALL.forEach { a ->
            Box(
                Modifier.size(56.dp).then(ring(chosen == a.id)).padding(4.dp).clip(CircleShape).background(Color.White)
                    .clickable(role = Role.RadioButton) { Avatars.set(a.id) }
                    .semantics { contentDescription = a.description; selected = chosen == a.id }
            ) {
                Image(painterResource(a.res), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
