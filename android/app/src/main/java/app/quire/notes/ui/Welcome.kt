package app.quire.notes.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import app.quire.notes.data.VaultRef
import app.quire.notes.state.QuireViewModel

@Composable
fun WelcomeScreen(vm: QuireViewModel) {
    val c = LocalQ.current
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Some providers grant access for this session only. The folder still opens.
            }
            vm.openTree(uri)
        }
    }
    BackHandler(enabled = vm.creating != null) { vm.creating = null }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val minH = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minH)
                .padding(start = 24.dp, end = 24.dp, top = 66.dp, bottom = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Lockup()
                Mono("A plain editor for folders of Markdown files.", 12.5.sp, c.mute, lineHeight = 20.sp)
            }
            val creating = vm.creating
            if (creating == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card("Create a new folder", "Start an empty notebook on this phone.") { vm.creating = "" }
                    Card("Open a folder…", "Use any folder of .md files you already have.") { pick.launch(null) }
                }
            } else {
                CreateCard(vm, creating)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label("recent", Modifier.padding(bottom = 8.dp))
                vm.vaults.forEach { RecentRow(vm, it) }
            }
        }
        }
    }
}

@Composable
private fun Card(title: String, desc: String, onClick: () -> Unit) {
    val c = LocalQ.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (pressed) c.mark else c.line, RoundedCornerShape(10.dp))
            .background(c.bg, RoundedCornerShape(10.dp))
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt(title, 14.5.sp, c.ink, weight = FontWeight.W500)
            Txt(desc, 12.5.sp, c.mute)
        }
        Mono("→", 15.sp, c.mute)
    }
}

@Composable
private fun CreateCard(vm: QuireViewModel, name: String) {
    val c = LocalQ.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            fr.requestFocus()
        } catch (e: IllegalStateException) {
            // Not attached yet.
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label("folder name")
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .border(1.dp, c.line, RoundedCornerShape(8.dp))
                .background(c.bg, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = name,
                onValueChange = { vm.creating = it },
                singleLine = true,
                textStyle = TextStyle(fontFamily = QFonts.sans, fontSize = 14.sp, color = c.ink),
                cursorBrush = SolidColor(c.acc),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { vm.createVault() }),
                modifier = Modifier.fillMaxWidth().wrapContentHeight().focusRequester(fr),
                decorationBox = { inner ->
                    if (name.isEmpty()) Txt("My notes", 14.sp, c.mute)
                    inner()
                },
            )
        }
        Mono(vm.newVaultPath(name), 11.5.sp, c.mute, maxLines = 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlineButton("Cancel", height = 34.dp, hPad = 14.dp, size = 13.sp, color = c.ink2) { vm.creating = null }
            PrimaryButton("Create", height = 34.dp, hPad = 16.dp) { vm.createVault() }
        }
    }
}

@Composable
private fun RecentRow(vm: QuireViewModel, v: VaultRef) {
    val c = LocalQ.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .bleed(10.dp)
                .tap(RoundedCornerShape(8.dp), onLongClick = { menu = true }) { vm.openVault(v) }
                .padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Txt(v.name, 14.sp, c.ink, weight = FontWeight.W500, maxLines = 1)
            Mono(vm.shortPath(v), 11.5.sp, c.mute, Modifier.weight(1f), maxLines = 1)
            Mono("${v.count} ${if (v.count == 1) "note" else "notes"}", 11.5.sp, c.mute, maxLines = 1)
        }
        if (menu) {
            Popup(onDismissRequest = { menu = false }, properties = PopupProperties(focusable = true)) {
                Column(
                    Modifier
                        .padding(start = 10.dp, top = 40.dp)
                        .widthIn(min = 200.dp)
                        .shadow(14.dp, RoundedCornerShape(9.dp), ambientColor = Shadow, spotColor = Shadow)
                        .background(c.bg, RoundedCornerShape(9.dp))
                        .border(1.dp, c.line, RoundedCornerShape(9.dp))
                        .padding(5.dp)
                ) {
                    Mono(v.path, 11.sp, c.mute, Modifier.padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 8.dp), lineHeight = 16.sp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .tap(RoundedCornerShape(6.dp)) {
                                menu = false
                                vm.forgetVault(v)
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Txt("Remove from recent", 13.sp, c.ink) }
                }
            }
        }
    }
}

/** A negative side margin, like "margin: 0 -10px" in the prototype. */
private fun Modifier.bleed(h: androidx.compose.ui.unit.Dp) = layout { m, c ->
    val extra = (h * 2).roundToPx()
    val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
    layout(c.maxWidth, p.height) { p.place(-h.roundToPx(), 0) }
}

/**
 * The mark from assets/brand: three 46 by 60 sheets with 6-unit corners in a 100-unit box.
 * The dark theme uses the colours of mark-on-ink.svg.
 */
@Composable
fun QuireMark(size: Dp, dark: Boolean, modifier: Modifier = Modifier) {
    val colors = if (dark) listOf(Color(0xFF3A352E), Color(0xFF5F584E), Color(0xFFB597E8))
    else listOf(Color(0xFFE1DBCF), Color(0xFFC9BFAE), Color(0xFF7A4FBA))
    Canvas(modifier.size(size)) {
        val u = this.size.width / 100f
        listOf(38f to 10f, 27f to 20f, 16f to 30f).forEachIndexed { i, (x, y) ->
            drawRoundRect(colors[i], Offset(x * u, y * u), Size(46 * u, 60 * u), CornerRadius(6 * u, 6 * u))
        }
    }
}

/** The horizontal lockup: the mark at 48 dp, a 14 dp gap, then "quire" at 52 sp. */
@Composable
private fun Lockup() {
    val c = LocalQ.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        QuireMark(48.dp, c.dark)
        BasicText(
            "quire",
            style = TextStyle(
                fontFamily = QFonts.sans,
                fontWeight = FontWeight.W600,
                fontSize = 52.sp,
                lineHeight = 52.sp,
                letterSpacing = (-0.045).em,
                color = c.ink,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            ),
        )
    }
}
