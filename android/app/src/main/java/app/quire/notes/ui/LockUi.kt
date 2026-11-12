package app.quire.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quire.notes.state.QuireViewModel

/** A password field. The done action of the keyboard runs [onDone]. */
@Composable
private fun PasswordField(
    value: String,
    ph: String,
    bad: Boolean,
    modifier: Modifier = Modifier,
    last: Boolean = true,
    focus: FocusRequester? = null,
    onDone: () -> Unit = {},
    onChange: (String) -> Unit,
) {
    val c = LocalQ.current
    Box(
        modifier
            .height(40.dp)
            .border(1.dp, if (bad) c.err else c.line, RoundedCornerShape(7.dp))
            .background(c.bg, RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = QFonts.plex, fontSize = 13.sp, color = c.ink),
            cursorBrush = SolidColor(c.acc),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = if (last) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = ph }
                .let { if (focus != null) it.focusRequester(focus) else it },
            decorationBox = { inner ->
                if (value.isEmpty()) Mono(ph, 13.sp, c.mute, maxLines = 1)
                inner()
            },
        )
    }
}

/** In place of the text of a locked note: the password field and the Unlock button. */
@Composable
fun LockPanel(vm: QuireViewModel) {
    val c = LocalQ.current
    var pass by remember(vm.active) { mutableStateOf("") }
    val err = vm.unlockError
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(top = 56.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QIcon(QIcons.LOCK, 16f, 28.dp, 1.3f, c.mute, Modifier.padding(bottom = 6.dp))
            Txt("This note is locked.", 17.sp, c.ink, weight = FontWeight.W500)
            Txt("Enter the password to read and edit it.", 13.5.sp, c.mute, lineHeight = 20.sp)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PasswordField(pass, "Password", err != null, Modifier.weight(1f), onDone = { vm.unlock(pass) }) { pass = it }
                PrimaryButton(if (vm.lockBusy) "Working…" else "Unlock", height = 40.dp, hPad = 16.dp) { vm.unlock(pass) }
            }
            if (err != null) Txt(err, 12.5.sp, c.err, lineHeight = 18.sp)
        }
    }
}

/** The dialog that locks the active note with a new password. */
@Composable
fun LockDialog(vm: QuireViewModel) {
    val c = LocalQ.current
    var pass by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    val err = vm.lockError
    val first = remember { FocusRequester() }
    val second = remember { FocusRequester() }
    BackHandler { vm.lockDialog = false }
    LaunchedEffect(Unit) {
        try {
            first.requestFocus()
        } catch (e: IllegalStateException) {
            // Not attached yet.
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(c.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.lockDialog = false }
            .imePadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(12.dp), ambientColor = Shadow, spotColor = Shadow)
                .background(c.bg, RoundedCornerShape(12.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt("Lock this note", 16.sp, c.ink, weight = FontWeight.W500)
                Txt("Quire encrypts the note with this password. If you forget it, nobody can open the note.", 13.sp, c.mute, lineHeight = 19.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt("Password", 12.5.sp, c.ink2, weight = FontWeight.W500)
                PasswordField(pass, "At least 8 characters", err != null, Modifier.fillMaxWidth(), last = false, focus = first) { pass = it }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt("Repeat password", 12.5.sp, c.ink2, weight = FontWeight.W500)
                PasswordField(repeat, "The same password again", err != null, Modifier.fillMaxWidth(), focus = second, onDone = { vm.lockNote(pass, repeat) }) { repeat = it }
            }
            if (err != null) Txt(err, 12.5.sp, c.err, lineHeight = 18.sp)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                OutlineButton("Cancel", height = 36.dp, hPad = 14.dp, size = 13.sp, color = c.ink) { vm.lockDialog = false }
                PrimaryButton(if (vm.lockBusy) "Working…" else "Lock", height = 36.dp, hPad = 16.dp) { vm.lockNote(pass, repeat) }
            }
        }
    }
}

/** The lock icon, in the color of an active toolbar button when the note is unlocked. */
internal fun lockColor(c: QColors, on: Boolean): Color = if (on) c.acc else c.ink2
