package app.quire.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quire.notes.state.QuireViewModel

/** The quick switcher and the command palette as one full-screen sheet. */
@Composable
fun PaletteScreen(vm: QuireViewModel) {
    val c = LocalQ.current
    val p = vm.palette ?: return
    val d = vm.palData() ?: return
    val commands = d.mode == "commands"
    val fr = remember { FocusRequester() }
    val list = rememberLazyListState()
    BackHandler { vm.closePalette() }
    LaunchedEffect(Unit) {
        try {
            fr.requestFocus()
        } catch (e: IllegalStateException) {
            // Not attached yet.
        }
    }
    LaunchedEffect(d.idx) {
        val vis = list.layoutInfo.visibleItemsInfo
        if (vis.isNotEmpty() && (d.idx < vis.first().index || d.idx > vis.last().index)) list.scrollToItem(d.idx)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .drawBehind { drawLine(c.line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.background(c.soft, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                Mono(if (commands) "run" else "open", 11.sp, c.acc)
            }
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = p.q,
                    onValueChange = { vm.setPaletteQuery(it) },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = QFonts.sans, fontSize = 15.sp, color = c.ink),
                    cursorBrush = SolidColor(c.acc),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { vm.runPaletteItem() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(fr),
                    decorationBox = { inner ->
                        if (p.q.isEmpty()) Txt(if (commands) "Type a command…" else "Find a note by name…", 15.sp, c.mute)
                        inner()
                    },
                )
            }
            IconBtn(QIcons.CLOSE, viewport = 8f, iconSize = 10.dp, stroke = 1.2f) { vm.closePalette() }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
            itemsIndexed(d.items) { i, it ->
                val label = buildAnnotatedString {
                    it.segs.forEach { g ->
                        if (g.hit) withStyle(SpanStyle(color = c.acc, fontWeight = FontWeight.W600)) { append(g.t) } else append(g.t)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 42.dp)
                        .background(if (i == d.idx) c.act else Color.Transparent, RoundedCornerShape(7.dp))
                        .tap(RoundedCornerShape(7.dp), onClick = it.run)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BasicText(
                        label,
                        Modifier.weight(1f),
                        style = TextStyle(fontFamily = QFonts.sans, fontSize = 14.sp, color = c.ink),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (it.sub.isNotEmpty()) Mono(it.sub, 11.5.sp, c.mute, maxLines = 1)
                }
            }
            if (d.items.isEmpty()) {
                item { Txt("Nothing matches.", 13.sp, c.mute, Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) }
            }
        }
        // The only hint left is about typing. Key hints do not help on a phone.
        if (!commands) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .drawBehind { drawLine(c.line, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono("type > for commands", 11.sp, c.mute, maxLines = 1)
            }
        }
    }
}
