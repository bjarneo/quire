package app.quire.notes.ui

import android.app.Activity
import android.graphics.Color as AColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import app.quire.notes.state.QuireViewModel
import app.quire.notes.state.Screen

@Composable
fun QuireApp(vm: QuireViewModel) {
    val sysDark = isSystemInDarkTheme()
    LaunchedEffect(sysDark) { vm.systemDark = sysDark }
    val dark = vm.settings.theme == "dark" || (vm.settings.theme == "system" && sysDark)
    val c = if (dark) DarkQ else LightQ
    val activity = LocalActivity.current
    LaunchedEffect(dark, activity) { activity?.let { setBars(it, dark) } }
    val scheme = if (dark) {
        darkColorScheme(primary = c.acc, background = c.bg, surface = c.bg, onSurface = c.ink, onBackground = c.ink, surfaceContainerLow = c.bg)
    } else {
        lightColorScheme(primary = c.acc, background = c.bg, surface = c.bg, onSurface = c.ink, onBackground = c.ink, surfaceContainerLow = c.bg)
    }
    CompositionLocalProvider(
        LocalQ provides c,
        LocalTextSelectionColors provides TextSelectionColors(handleColor = c.acc, backgroundColor = Selection),
    ) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(handleColor = c.acc, backgroundColor = Selection)) {
                Box(Modifier.fillMaxSize().background(c.bg)) {
                    when (vm.screen) {
                        Screen.Loading -> Unit
                        Screen.Welcome -> WelcomeScreen(vm)
                        Screen.Vault -> VaultScreen(vm)
                    }
                    if (vm.screen == Screen.Vault && vm.settingsOpen) SettingsScreen(vm)
                    if (vm.screen == Screen.Vault && vm.palette != null) PaletteScreen(vm)
                    vm.notice?.let { Toast(it) }
                }
            }
        }
    }
}

/** Transparent system bars with icons that match the Quire theme, not the system theme. */
private fun setBars(activity: Activity, dark: Boolean) {
    val a = activity as? ComponentActivity ?: return
    val style = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT) else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
    a.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}

/** A short message near the bottom, above the drawer and the keyboard. It does not take touches. */
@Composable
private fun Toast(msg: String) {
    val c = LocalQ.current
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 24.dp, end = 24.dp, bottom = 56.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .widthIn(max = 420.dp)
                .shadow(12.dp, RoundedCornerShape(8.dp), ambientColor = Shadow, spotColor = Shadow)
                .background(c.bg, RoundedCornerShape(8.dp))
                .border(1.dp, c.line, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Txt(msg, 12.5.sp, c.err, lineHeight = 18.sp)
        }
    }
}
