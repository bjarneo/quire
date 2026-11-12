package app.quire.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quire.notes.core.Sync
import app.quire.notes.core.SyncField
import app.quire.notes.data.Settings
import app.quire.notes.state.QuireViewModel

private val sections = listOf(
    "appearance" to "Appearance",
    "editor" to "Editor",
    "files" to "Files & links",
    "sync" to "Sync & backup",
)

/** Settings as a full screen. The section list from the prototype runs across the top. */
@Composable
fun SettingsScreen(vm: QuireViewModel) {
    val c = LocalQ.current
    BackHandler { vm.settingsOpen = false }
    val sec = sections.find { it.first == vm.setSec } ?: sections[0]
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
    ) {
        Column(Modifier.fillMaxWidth().background(c.side).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("settings", Modifier.weight(1f))
                IconBtn(QIcons.CLOSE, viewport = 8f, iconSize = 10.dp, stroke = 1.2f) { vm.settingsOpen = false }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                sections.forEach { (id, label) ->
                    val on = id == sec.first
                    Box(
                        Modifier
                            .height(32.dp)
                            .background(if (on) c.act else Color.Transparent, RoundedCornerShape(6.dp))
                            .tap(RoundedCornerShape(6.dp)) { vm.setSec = id }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Txt(label, 13.5.sp, if (on) c.ink else c.ink2, weight = if (on) FontWeight.W500 else FontWeight.W400, maxLines = 1) }
                }
            }
        }
        Hairline(Modifier.fillMaxWidth())
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Txt(sec.second, 17.sp, c.ink, Modifier.padding(top = 20.dp, bottom = 18.dp), weight = FontWeight.W600)
            when (sec.first) {
                "appearance" -> Appearance(vm)
                "editor" -> EditorSection(vm)
                "files" -> FilesSection(vm)
                else -> SyncSection(vm)
            }
        }
    }
}

/** One row: label and description on the left, a control on the right, with a rule on top. */
@Composable
private fun SetRow(label: String, desc: String? = null, control: @Composable () -> Unit) {
    val c = LocalQ.current
    Hairline(Modifier.fillMaxWidth())
    // On a narrow screen a wide control moves under its label.
    Layout(
        content = {
            Column {
                Txt(label, 14.sp, c.ink, weight = FontWeight.W500)
                if (desc != null) Txt(desc, 12.5.sp, c.mute, Modifier.padding(top = 3.dp), lineHeight = 18.sp)
            }
            Box { control() }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    ) { m, cs ->
        val w = cs.maxWidth
        val ctl = m[1].measure(Constraints(maxWidth = w))
        if (ctl.width > w * 0.55f) {
            val txt = m[0].measure(Constraints(maxWidth = w))
            val gap = 12.dp.roundToPx()
            layout(w, txt.height + gap + ctl.height) {
                txt.place(0, 0)
                ctl.place(0, txt.height + gap)
            }
        } else {
            val txt = m[0].measure(Constraints(maxWidth = (w - ctl.width - 20.dp.roundToPx()).coerceAtLeast(0)))
            val h = maxOf(txt.height, ctl.height)
            layout(w, h) {
                txt.place(0, (h - txt.height) / 2)
                ctl.place(w - ctl.width, (h - ctl.height) / 2)
            }
        }
    }
}

@Composable
private fun SetBlock(label: String, desc: String? = null, top: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalQ.current
    Hairline(Modifier.fillMaxWidth())
    Column(Modifier.fillMaxWidth().padding(top = top, bottom = 16.dp)) {
        Txt(label, 14.sp, c.ink, weight = FontWeight.W500)
        if (desc != null) Txt(desc, 12.5.sp, c.mute, Modifier.padding(top = 3.dp), lineHeight = 18.sp)
        content()
    }
}

private fun <T> opts(cur: T, vararg o: Pair<T, String>, set: (T) -> Unit) = o.map { (v, label) -> SegOpt(label, cur == v) { set(v) } }

@Composable
private fun Appearance(vm: QuireViewModel) {
    val s = vm.settings
    SetRow("Theme", "Match system follows your phone's setting.") {
        Segmented(opts(s.theme, "light" to "Light", "dark" to "Dark", "system" to "Match system") { v -> vm.updateSettings { it.copy(theme = v) } })
    }
    SetRow("Preview typeface", "Used when reading a note in preview.") {
        Segmented(opts(s.previewFont, "serif" to "Serif", "sans" to "Sans") { v -> vm.updateSettings { it.copy(previewFont = v) } })
    }
}

@Composable
private fun EditorSection(vm: QuireViewModel) {
    val c = LocalQ.current
    val s = vm.settings
    SetBlock("Editor font", "Monospaced, so Markdown syntax lines up as you type.") {
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("plex" to "IBM Plex Mono", "courier" to "Courier Prime", "spline" to "Spline Sans Mono").forEach { (id, label) ->
                val on = s.font == id
                val fam = QFonts.editor(id)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(if (on) 2.dp else 1.dp, if (on) c.acc else c.line, RoundedCornerShape(9.dp))
                        .background(c.bg, RoundedCornerShape(9.dp))
                        .tap(RoundedCornerShape(9.dp), pressed = Color.Transparent) { vm.updateSettings { it.copy(font = id) } }
                        .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Txt(label, 11.5.sp, if (on) c.acc else c.mute)
                    Txt("## Field notes", 15.sp, c.ink, family = fam, lineHeight = 22.sp)
                    Txt("Rain until 14:00.", 13.sp, c.ink2, family = fam, lineHeight = 20.sp)
                }
            }
        }
    }
    SetRow("Font size") {
        Row(
            Modifier.background(c.side, RoundedCornerShape(8.dp)).padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.height(28.dp).widthIn(min = 32.dp).tap(RoundedCornerShape(6.dp), pressed = c.bg) {
                vm.updateSettings { it.copy(size = maxOf(12, it.size - 1)) }
            }, contentAlignment = Alignment.Center) { Txt("−", 15.sp, c.ink2) }
            Box(Modifier.widthIn(min = 48.dp), contentAlignment = Alignment.Center) { Mono("${s.size} px", 12.sp, c.ink) }
            Box(Modifier.height(28.dp).widthIn(min = 32.dp).tap(RoundedCornerShape(6.dp), pressed = c.bg) {
                vm.updateSettings { it.copy(size = minOf(24, it.size + 1)) }
            }, contentAlignment = Alignment.Center) { Txt("+", 15.sp, c.ink2) }
        }
    }
    SetRow("Line width", "Readable keeps lines to about 72 characters.") {
        Segmented(opts(s.width, "readable" to "Readable", "full" to "Full width") { v -> vm.updateSettings { it.copy(width = v) } })
    }
    val toggles: List<Triple<String, String, Pair<(Settings) -> Boolean, (Settings, Boolean) -> Settings>>> = listOf(
        Triple("Spell check", "Underline misspelled words while you type.", Pair({ x: Settings -> x.spellcheck }, { x: Settings, v: Boolean -> x.copy(spellcheck = v) })),
        Triple("Continue lists", "Pressing Enter in a list starts the next item.", Pair({ x: Settings -> x.continueLists }, { x: Settings, v: Boolean -> x.copy(continueLists = v) })),
        Triple("Dim other paragraphs in focus mode", "Only the paragraph you are writing stays at full strength.", Pair({ x: Settings -> x.dimFocus }, { x: Settings, v: Boolean -> x.copy(dimFocus = v) })),
        Triple("Typewriter scrolling in focus mode", "Keeps the line you are typing near the middle of the window.", Pair({ x: Settings -> x.typewriter }, { x: Settings, v: Boolean -> x.copy(typewriter = v) })),
    )
    toggles.forEach { (label, desc, acc) ->
        val on = acc.first(s)
        SetRow(label, desc) { Toggle(on) { vm.updateSettings { acc.second(it, !on) } } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilesSection(vm: QuireViewModel) {
    val c = LocalQ.current
    val s = vm.settings
    SetRow("Folder location", null) { OutlineButton("Switch folder…") { vm.goWelcome() } }
    Mono(vm.vault?.path ?: "", 12.sp, c.mute, Modifier.padding(bottom = 16.dp), lineHeight = 18.sp)
    SetBlock("New notes go in", "Notes created from links and the switcher land here too.") {
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf("" to "Folder root") + vm.folderList().map { it to "$it/" }).forEach { (v, label) ->
                val on = (s.newFolder) == v
                Box(
                    Modifier
                        .height(32.dp)
                        .border(1.dp, c.line, RoundedCornerShape(7.dp))
                        .background(if (on) c.bg else Color.Transparent, RoundedCornerShape(7.dp))
                        .tap(RoundedCornerShape(7.dp)) { vm.updateSettings { it.copy(newFolder = v) } }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Mono(label, 12.sp, if (on) c.ink else c.mute) }
            }
        }
    }
    SetRow("Update links when renaming", "Rewrites [[links]] in other notes so they still point to the renamed file.") {
        Toggle(s.updateLinks) { vm.updateSettings { it.copy(updateLinks = !it.updateLinks) } }
    }
    SetRow("Sample notes", "Put Field Notes back the way it started.") { OutlineButton("Restore") { vm.resetSample() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncSection(vm: QuireViewModel) {
    val c = LocalQ.current
    val sy = vm.settings.sync
    val pid = sy.provider
    val cfg = vm.syncCfg()
    val st = vm.syncStatus
    SetBlock("Storage backend", "Notes always live on this phone first. A backend keeps a copy in object storage you control.") {
        val rows = Sync.providers.chunked(2)
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { p ->
                        val on = p.id == pid
                        Column(
                            Modifier
                                .weight(1f)
                                .border(if (on) 2.dp else 1.dp, if (on) c.acc else c.line, RoundedCornerShape(9.dp))
                                .background(c.bg, RoundedCornerShape(9.dp))
                                .tap(RoundedCornerShape(9.dp), pressed = Color.Transparent) { vm.selectProvider(p.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Txt(p.name, 13.sp, c.ink, weight = FontWeight.W500, maxLines = 1)
                            Mono(p.kind, 10.5.sp, if (on) c.acc else c.mute, maxLines = 1)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    if (pid == "none") return
    val spec = Sync.spec(pid, cfg, vm.vault?.name ?: "")
    SetBlock(Sync.provider(pid)?.full ?: "", top = 16.dp) {
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            spec.forEach { f -> SyncFieldView(vm, f, cfg, f.key in st.missing) }
        }
    }
    SetRow("When to sync", "On save uploads a note a moment after you stop typing.") {
        Segmented(opts(sy.whenSync, "save" to "On save", "interval" to "Every 5 min", "manual" to "Manually") { vm.setWhen(it) })
    }
    SetRow("Encrypt before upload", "Notes are encrypted on this phone, so the provider only stores ciphertext.") {
        Toggle(sy.encrypt) { vm.toggleEncrypt() }
    }
    if (sy.encrypt) {
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt("Passphrase", 12.5.sp, c.ink2, weight = FontWeight.W500)
            SecretInput(vm, "pass", sy.pass, "At least 12 characters", c.line, Modifier.widthIn(max = 320.dp)) { vm.setPass(it) }
            Txt("If you lose this passphrase, the remote copy can't be recovered.", 12.sp, c.mute, lineHeight = 17.sp)
        }
    }
    Hairline(Modifier.fillMaxWidth())
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryButton("Test connection", height = 36.dp) { vm.testSync() }
        val msg = if (st.state == "idle") "Not connected yet." else st.msg
        Txt(
            msg, 12.5.sp,
            when (st.state) {
                "error" -> c.err
                "ok" -> c.ink2
                else -> c.mute
            },
            Modifier.widthIn(min = 180.dp),
            lineHeight = 18.sp,
        )
    }
    Mono(
        "Keys are stored with the Android Keystore, never inside the notes folder.",
        11.sp, c.mute, Modifier.padding(top = 14.dp), lineHeight = 16.5.sp,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncFieldView(vm: QuireViewModel, f: SyncField, cfg: Map<String, String>, missing: Boolean) {
    val c = LocalQ.current
    val v = if (f.type == "read") f.value else (cfg[f.key] ?: f.def)
    val border = if (missing) c.err else c.line
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(f.label, 12.5.sp, c.ink2, weight = FontWeight.W500, maxLines = 1)
            if (f.hint.isNotEmpty()) Txt(f.hint, 11.5.sp, c.mute, Modifier.weight(1f), maxLines = 1, align = androidx.compose.ui.text.style.TextAlign.End)
        }
        when (f.type) {
            null -> TextInput(v, f.ph, border) { vm.setCfg(f.key, it) }
            "secret" -> SecretInput(vm, f.key, v, f.ph, border) { vm.setCfg(f.key, it) }
            "choice" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                f.opts.forEach { (ov, label) ->
                    val on = v == ov
                    Box(
                        Modifier
                            .height(32.dp)
                            .border(1.dp, if (on) c.acc else c.line, RoundedCornerShape(7.dp))
                            .tap(RoundedCornerShape(7.dp)) { vm.setCfg(f.key, ov) }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Mono(label, 11.5.sp, if (on) c.acc else c.ink2, maxLines = 1) }
                }
            }
            "area" -> Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .border(1.dp, border, RoundedCornerShape(7.dp))
                    .background(c.bg, RoundedCornerShape(7.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                BasicTextField(
                    value = v,
                    onValueChange = { vm.setCfg(f.key, it) },
                    textStyle = TextStyle(fontFamily = QFonts.plex, fontSize = 11.5.sp, lineHeight = 17.sp, color = c.ink),
                    cursorBrush = SolidColor(c.acc),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxSize(),
                    decorationBox = { inner ->
                        if (v.isEmpty()) Mono(f.ph, 11.5.sp, c.mute, lineHeight = 17.sp)
                        inner()
                    },
                )
            }
            "read" -> Box(Modifier.fillMaxWidth().background(c.code, RoundedCornerShape(7.dp)).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Mono(v, 12.sp, c.ink2, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun TextInput(value: String, ph: String, border: Color, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val c = LocalQ.current
    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .border(1.dp, border, RoundedCornerShape(7.dp))
            .background(c.bg, RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = QFonts.plex, fontSize = 12.5.sp, color = c.ink),
            cursorBrush = SolidColor(c.acc),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Mono(ph, 12.5.sp, c.mute, maxLines = 1)
                inner()
            },
        )
    }
}

@Composable
private fun SecretInput(vm: QuireViewModel, key: String, value: String, ph: String, border: Color, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val c = LocalQ.current
    val shown = key in vm.reveal
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .border(1.dp, border, RoundedCornerShape(7.dp))
            .background(c.bg, RoundedCornerShape(7.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = QFonts.plex, fontSize = 12.5.sp, color = c.ink),
                cursorBrush = SolidColor(c.acc),
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = if (shown) KeyboardType.Ascii else KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) Mono(ph, 12.5.sp, c.mute, maxLines = 1)
                    inner()
                },
            )
        }
        VLine(Modifier.fillMaxHeight())
        Box(
            Modifier
                .fillMaxHeight()
                .tap { vm.reveal = if (shown) vm.reveal - key else vm.reveal + key }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Mono(if (shown) "hide" else "show", 11.sp, c.mute) }
    }
}
