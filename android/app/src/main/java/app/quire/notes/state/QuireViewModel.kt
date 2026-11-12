package app.quire.notes.state

import android.app.Application
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.quire.notes.core.Md
import app.quire.notes.core.Sample
import app.quire.notes.core.Sync
import app.quire.notes.data.LocalFs
import app.quire.notes.data.SafFs
import app.quire.notes.data.SecretStore
import app.quire.notes.data.Session
import app.quire.notes.data.Settings
import app.quire.notes.data.Store
import app.quire.notes.data.SyncSettings
import app.quire.notes.data.VaultFs
import app.quire.notes.data.VaultKind
import app.quire.notes.data.VaultRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class Screen { Loading, Welcome, Vault }
enum class Mode { Source, Preview }
enum class Panel { Files, Search, Tags }

data class PaletteState(val mode: String, val q: String = "", val idx: Int = 0)
data class SyncStatus(val state: String = "idle", val msg: String = "", val missing: List<String> = emptyList())
data class ScrollReq(val line: Int, val token: Long = System.nanoTime())
data class OpenSel(
    val start: Int,
    val end: Int,
    val line: Int? = null,
    val forceSource: Boolean = false,
    val hit: Boolean = false,
    val focus: Boolean = false,
)

data class Seg(val t: String, val hit: Boolean)
class PalItem(val segs: List<Seg>, val sub: String, val run: () -> Unit)
class PalData(val mode: String, val items: List<PalItem>, val idx: Int)
class Cmd(val label: String, val run: () -> Unit)

private const val SAMPLE_ID = "fieldnotes"

class QuireViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val store = Store(app)
    private val secrets = SecretStore(app)

    // One background thread keeps disk writes in order. It outlives the view model so pending writes finish.
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val ioScope = CoroutineScope(SupervisorJob() + io)

    var settings by mutableStateOf(store.loadSettings()); private set
    var vaults by mutableStateOf(store.loadVaults()); private set
    var screen by mutableStateOf(Screen.Loading); private set
    var vault by mutableStateOf<VaultRef?>(null); private set
    private var fs: VaultFs? = null

    var files by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var folders by mutableStateOf<Set<String>>(emptySet()); private set
    var names by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var tabs by mutableStateOf<List<String>>(emptyList()); private set
    var active by mutableStateOf<String?>(null); private set
    var recent by mutableStateOf<List<String>>(emptyList()); private set
    var expanded by mutableStateOf<Set<String>>(emptySet()); private set
    var mode by mutableStateOf(if (store.mode == "preview") Mode.Preview else Mode.Source); private set

    var leftPanel by mutableStateOf(Panel.Files)
    var query by mutableStateOf("")
    private var drawerOpenState by mutableStateOf(false)

    /** Closing the drawer also drops the new-folder field. */
    var drawerOpen: Boolean
        get() = drawerOpenState
        set(v) {
            drawerOpenState = v
            if (!v) newFolderParent = null
        }
    var outlineOpen by mutableStateOf(false)
    var vaultMenu by mutableStateOf(false)
    var palette by mutableStateOf<PaletteState?>(null)
    var settingsOpen by mutableStateOf(false)
    var setSec by mutableStateOf("appearance")
    var focus by mutableStateOf(false); private set
    var renaming by mutableStateOf(false); private set
    var renameVal by mutableStateOf(TextFieldValue(""))
    var creating by mutableStateOf<String?>(null)

    /** The text and selection of the active note in source mode. */
    var editor by mutableStateOf(TextFieldValue("")); private set

    /** Changes each time the editor loads another note. */
    var docKey by mutableLongStateOf(0L); private set

    /** A search match to mark in the editor until the next edit or tap. */
    var hit by mutableStateOf<TextRange?>(null); private set
    var scrollReq by mutableStateOf<ScrollReq?>(null)
    var pendingEditorFocus by mutableStateOf(false)
    /** True when the search field must take the focus the next time it shows. */
    var searchFocusPending by mutableStateOf(false)
    var prevHead by mutableIntStateOf(0)
    var syncStatus by mutableStateOf(SyncStatus()); private set
    var reveal by mutableStateOf<Set<String>>(emptySet())
    var editorFocused by mutableStateOf(false)
    var systemDark by mutableStateOf(false)

    /** A short message for the status bar or the welcome screen, for example a failed save. */
    var notice by mutableStateOf<String?>(null)
    private var noticeJob: Job? = null

    /** The folder that gets a new subfolder from the inline name field. Null hides the field. "" is the root. */
    var newFolderParent by mutableStateOf<String?>(null); private set
    var newFolderName by mutableStateOf(TextFieldValue(""))

    private val dirty = LinkedHashSet<String>()

    // Notes that move on disk right now. Their edits wait until the move is done.
    private val moving = HashSet<String>()
    private var saveJob: Job? = null
    private var sessionJob: Job? = null
    private var resumedOnce = false

    init {
        viewModelScope.launch {
            val stored = withContext(io) { secrets.load() }
            if (stored.isNotEmpty()) settings = mergeSecrets(settings, stored)
            if (!store.sampleCreated) {
                withContext(io) { writeSample(sampleDir()) }
                store.sampleCreated = true
                putVault(sampleRef(Sample.files.size), front = true)
                store.saveSession(SAMPLE_ID, sampleSession())
                store.lastVault = SAMPLE_ID
                store.lastScreen = "vault"
            }
            val last = vaults.find { it.id == store.lastVault }
            if (last != null && store.lastScreen == "vault") openVault(last) else screen = Screen.Welcome
        }
    }

    // ---- Vaults ----------------------------------------------------------------------------

    private fun vaultsDir() = File(ctx.filesDir, "vaults")
    private fun sampleDir() = File(vaultsDir(), Sample.NAME)
    private fun sampleRef(count: Int) = VaultRef(SAMPLE_ID, Sample.NAME, VaultKind.LOCAL, sampleDir().absolutePath, sampleDir().absolutePath, count)
    private fun sampleSession() = Session(Sample.tabs, Sample.tabs[0], Sample.tabs, Sample.expanded)

    private fun writeSample(dir: File) {
        dir.mkdirs()
        Sample.folders.forEach { File(dir, it).mkdirs() }
        Sample.files.forEach { (p, t) -> File(dir, p).also { it.parentFile?.mkdirs() }.writeText(t) }
    }

    private fun fsFor(ref: VaultRef): VaultFs =
        if (ref.kind == VaultKind.SAF) SafFs(ctx, Uri.parse(ref.location)) else LocalFs(File(ref.location))

    private fun putVault(ref: VaultRef, front: Boolean = false) {
        val i = vaults.indexOfFirst { it.id == ref.id }
        vaults = when {
            i >= 0 -> vaults.toMutableList().also { it[i] = ref }
            front -> listOf(ref) + vaults
            else -> vaults + ref
        }
        store.saveVaults(vaults)
    }

    fun shortPath(ref: VaultRef): String =
        if (ref.kind == VaultKind.LOCAL && ref.location.startsWith(vaultsDir().absolutePath)) {
            "~/vaults/" + File(ref.location).name
        } else {
            ref.path
        }

    fun newVaultPath(name: String): String = "~/vaults/" + (Md.safeName(name).ifEmpty { "Untitled notebook" })

    private fun leaveVault() {
        flushAll()
        saveSession()
    }

    fun openVault(ref: VaultRef, session: Session? = null) {
        viewModelScope.launch {
            leaveVault()
            dirty.clear()
            val f = fsFor(ref)
            val loaded = try {
                withContext(io) { f.load().also { f.displayPath } }
            } catch (e: Exception) {
                fs = null
                vault = null
                screen = Screen.Welcome
                store.lastScreen = "welcome"
                flash("Quire can't open ${ref.name}. ${e.message ?: ""}".trim())
                return@launch
            }
            fs = f
            vault = ref.copy(path = f.displayPath, count = loaded.files.size)
            putVault(vault!!)
            setStructure(loaded.files, loaded.folders)
            val ses = session ?: store.loadSession(ref.id)
            tabs = ses.tabs.filter { it in files }
            active = ses.active?.takeIf { it in files } ?: tabs.firstOrNull()
            recent = ses.recent.filter { it in files }.ifEmpty { tabs }
            expanded = ses.expanded
            query = ""
            leftPanel = Panel.Files
            focus = false
            creating = null
            palette = null
            settingsOpen = false
            renaming = false
            drawerOpen = false
            outlineOpen = false
            loadEditor(active, null)
            screen = Screen.Vault
            store.lastVault = ref.id
            store.lastScreen = "vault"
            saveSession()
        }
    }

    fun createVault() {
        val name = Md.safeName(creating ?: "").ifEmpty { "Untitled notebook" }
        viewModelScope.launch {
            val dir = withContext(io) {
                var d = File(vaultsDir(), name)
                var n = 2
                while (d.exists()) d = File(vaultsDir(), "$name ${n++}")
                d.mkdirs()
                d
            }
            val ref = VaultRef("v" + System.currentTimeMillis(), dir.name, VaultKind.LOCAL, dir.absolutePath, dir.absolutePath, 0)
            putVault(ref)
            creating = null
            openVault(ref)
        }
    }

    /** Opens a folder from ACTION_OPEN_DOCUMENT_TREE. The caller has taken the persistable permission. */
    fun openTree(uri: Uri) {
        val existing = vaults.find { it.kind == VaultKind.SAF && it.location == uri.toString() }
        if (existing != null) {
            openVault(existing)
            return
        }
        viewModelScope.launch {
            val (name, path) = withContext(io) {
                val n = try {
                    DocumentFile.fromTreeUri(ctx, uri)?.name
                } catch (e: Exception) {
                    null
                }
                (n ?: "Folder") to SafFs.describe(ctx, uri)
            }
            val ref = VaultRef("v" + System.currentTimeMillis(), name, VaultKind.SAF, uri.toString(), path, 0)
            putVault(ref)
            openVault(ref)
        }
    }

    /** Takes a vault off the recent list. The folder and its notes stay on disk. */
    fun forgetVault(ref: VaultRef) {
        vaults = vaults.filter { it.id != ref.id }
        store.saveVaults(vaults)
        store.forgetSession(ref.id)
        if (ref.kind == VaultKind.SAF) {
            try {
                ctx.contentResolver.releasePersistableUriPermission(
                    Uri.parse(ref.location),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // The permission is already gone.
            }
        }
    }

    fun goWelcome() {
        leaveVault()
        screen = Screen.Welcome
        palette = null
        settingsOpen = false
        vaultMenu = false
        drawerOpen = false
        outlineOpen = false
        focus = false
        renaming = false
        store.lastScreen = "welcome"
    }

    fun resetSample() {
        viewModelScope.launch {
            leaveVault()
            if (vault?.id == SAMPLE_ID) {
                vault = null
                fs = null
            }
            withContext(io) {
                sampleDir().deleteRecursively()
                writeSample(sampleDir())
            }
            putVault(sampleRef(Sample.files.size), front = vaults.none { it.id == SAMPLE_ID })
            store.saveSession(SAMPLE_ID, sampleSession())
            settingsOpen = false
            palette = null
            focus = false
            openVault(sampleRef(Sample.files.size), sampleSession())
        }
    }

    fun onResume() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        refresh()
    }

    fun onPause() {
        leaveVault()
    }

    /** Reads the vault again, so edits from other apps show up. Unsaved edits win. */
    private fun refresh() {
        val f = fs ?: return
        val v = vault ?: return
        if (screen != Screen.Vault) return
        viewModelScope.launch {
            val loaded = try {
                withContext(io) { f.load() }
            } catch (e: Exception) {
                return@launch
            }
            if (vault?.id != v.id) return@launch
            val merged = LinkedHashMap(loaded.files)
            for (p in dirty) files[p]?.let { merged[p] = it }
            if (merged == files && loaded.folders == folders) return@launch
            setStructure(merged, loaded.folders)
            tabs = tabs.filter { it in merged }
            recent = recent.filter { it in merged }
            val a = active
            if (a == null || a !in merged) {
                active = tabs.firstOrNull()
                loadEditor(active, null)
            } else if (editor.text != merged[a]) {
                val t = merged.getValue(a)
                editor = TextFieldValue(t, shiftSelection(editor, t))
            }
            updateCount()
        }
    }

    private fun updateCount() {
        val v = vault ?: return
        if (v.count != files.size) {
            vault = v.copy(count = files.size)
            putVault(vault!!)
        }
    }

    // ---- Files -----------------------------------------------------------------------------

    private fun setStructure(f: Map<String, String>, dirs: Set<String> = folders) {
        files = f
        folders = dirs
        names = Md.buildNames(f.keys)
    }

    fun folderList(): List<String> = Md.folderList(folders, files.keys)

    private fun setDoc(path: String, text: String) {
        files = files + (path to text)
        dirty.add(path)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            flushAll()
        }
    }

    /** Writes every unsaved note on the background thread. */
    fun flushAll() {
        saveJob?.cancel()
        val f = fs ?: return
        if (dirty.isEmpty()) return
        val batch = dirty.filter { it !in moving }.mapNotNull { p -> files[p]?.let { p to it } }
        dirty.removeAll { it !in moving }
        ioScope.launch {
            for ((p, t) in batch) {
                try {
                    f.write(p, t)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { flash("Couldn't save ${Md.base(p)}. ${e.message ?: ""}".trim()) }
                }
            }
        }
    }

    private fun disk(what: String, op: VaultFs.() -> Unit) {
        val f = fs ?: return
        ioScope.launch {
            try {
                f.op()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { flash("Couldn't $what. ${e.message ?: ""}".trim()) }
            }
        }
    }

    fun flash(msg: String) {
        notice = msg
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(6000)
            notice = null
        }
    }

    private fun persistSession() {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            delay(300)
            saveSession()
        }
    }

    private fun saveSession() {
        val v = vault ?: return
        store.saveSession(v.id, Session(tabs, active, recent, expanded))
    }

    private fun loadEditor(path: String?, sel: OpenSel?) {
        val t = path?.let { files[it] } ?: ""
        val range = if (sel != null) TextRange(sel.start.coerceIn(0, t.length), sel.end.coerceIn(0, t.length)) else TextRange(0)
        editor = TextFieldValue(t, range)
        docKey++
        hit = if (sel?.hit == true && range.length > 0) range else null
        scrollReq = sel?.line?.let { ScrollReq(it) }
        if (sel?.focus == true) pendingEditorFocus = true
    }

    private fun ancestors(path: String): Set<String> {
        val out = HashSet<String>()
        var d = Md.dir(path)
        while (d.isNotEmpty()) {
            out.add(d)
            d = Md.dir(d)
        }
        return out
    }

    fun openFile(path: String, sel: OpenSel? = null) {
        if (path !in files) return
        if (path !in tabs) tabs = tabs + path
        expanded = expanded + ancestors(path)
        recent = (listOf(path) + recent.filter { it != path }).take(30)
        palette = null
        renaming = false
        vaultMenu = false
        drawerOpen = false
        outlineOpen = false
        if (sel?.forceSource == true) setModeInternal(Mode.Source)
        prevHead = 0
        if (sel != null || path != active) {
            active = path
            loadEditor(path, sel)
        }
        persistSession()
    }

    fun closeTab(p: String) {
        val i = tabs.indexOf(p)
        val nt = tabs.filter { it != p }
        tabs = nt
        if (active == p) {
            val na = if (nt.isEmpty()) null else nt[min(max(i, 0), nt.size - 1)]
            active = na
            loadEditor(na, null)
        }
        renaming = false
        if (active == null) focus = false
        persistSession()
    }

    fun toggleFolder(path: String) {
        expanded = if (path in expanded) expanded - path else expanded + path
        persistSession()
    }

    /**
     * Creates a note. [inFolder] puts it in that folder and makes the folder when it is missing.
     * Without it, the note goes in the folder from settings, like the prototype.
     */
    fun newNote(name: String? = null, inFolder: String? = null) {
        val f = fs ?: return
        var folder = inFolder ?: settings.newFolder
        if (inFolder == null && folder.isNotEmpty() && folder !in folderList()) folder = ""
        val named = !name.isNullOrEmpty()
        val bn = Md.safeName(if (named) name else "Untitled").ifEmpty { "Untitled" }
        var p = Md.join(folder, "$bn.md")
        var n = 2
        while (p in files) p = Md.join(folder, "$bn ${n++}.md")
        val content = if (named) "# $bn\n\n" else ""
        setStructure(files + (p to content), folders + ancestors(p))
        val path = p
        disk("create $bn") { write(path, content) }
        screen = Screen.Vault
        settingsOpen = false
        openFile(p, OpenSel(content.length, content.length, focus = named && mode == Mode.Source))
        if (!named) {
            renaming = true
            renameVal = TextFieldValue(bn, TextRange(0, bn.length))
            setModeInternal(Mode.Source)
            focus = false
        }
        updateCount()
    }

    /** Creates a note from a typed path such as "Drafts/Idea". Missing folders are made too. */
    fun newNoteAtPath(typed: String) {
        val parts = typed.split('/').map { Md.safeName(it) }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val name = parts.last().replace(Regex("""\.md$""", RegexOption.IGNORE_CASE), "").ifEmpty { return }
        newNote(name, parts.dropLast(1).joinToString("/"))
    }

    private fun where(dir: String) = if (dir.isEmpty()) "the folder root" else "$dir/"

    // ---- Folders -------------------------------------------------------------------------

    /** Shows the inline name field for a new folder in [parent]. "" is the root. */
    fun startNewFolder(parent: String) {
        if (parent.isNotEmpty()) expanded = expanded + parent + ancestors(parent)
        newFolderName = TextFieldValue("")
        newFolderParent = parent
        leftPanel = Panel.Files
        palette = null
        settingsOpen = false
        drawerOpen = true
    }

    fun cancelNewFolder() {
        newFolderParent = null
    }

    /** Makes the folder from the inline field as a real, empty directory. */
    fun commitNewFolder() {
        val parent = newFolderParent ?: return
        newFolderParent = null
        val name = Md.safeName(newFolderName.text)
        if (name.isEmpty()) return
        if (name.startsWith(".")) {
            flash("Quire hides folders that start with a dot. Pick another name.")
            return
        }
        val path = Md.join(parent, name)
        if (folderList().any { it.equals(path, ignoreCase = true) }) {
            flash("A folder named $name is already in ${where(parent)}.")
            return
        }
        setStructure(files, folders + path)
        expanded = expanded + path
        disk("create the folder $name") { mkdirs(path) }
        persistSession()
    }

    // ---- Moving notes --------------------------------------------------------------------

    /**
     * Moves a note into [dir] ("" is the root). The note keeps its tab, caret and undo history.
     * Links that spell out the old path change to the new path. Links by name keep working as they are.
     */
    fun moveNote(path: String, dir: String) {
        val f = fs ?: return
        if (path !in files) return
        val np = Md.join(dir, path.substringAfterLast('/'))
        if (np == path) return
        if (files.keys.any { it != path && it.equals(np, ignoreCase = true) }) {
            flash("${Md.base(np)} is already in ${where(dir)}. Quire did not move the note.")
            return
        }
        flushAll()
        moving.add(path)
        viewModelScope.launch {
            try {
                withContext(io) { f.move(path, np) }
            } catch (e: Exception) {
                moving.remove(path)
                flash("Couldn't move ${Md.base(path)}. ${e.message ?: ""}".trim())
                return@launch
            }
            moving.remove(path)
            applyMove(path, np)
        }
    }

    private fun applyMove(old: String, np: String) {
        if (old !in files) return
        val oldFull = Md.stripExt(old)
        val newFull = Md.stripExt(np)
        // A link by name still resolves after the move. Only a link with the folder in it needs a new path.
        val rewrite = settings.updateLinks && oldFull.contains('/')
        val re = Regex("""\[\[""" + Regex.escape(oldFull) + """(\|[^\]]*)?\]\]""", RegexOption.IGNORE_CASE)
        val nf = LinkedHashMap<String, String>()
        val changed = ArrayList<String>()
        for ((p, t) in files) {
            val t2 = if (rewrite) re.replace(t) { "[[" + newFull + (it.groups[1]?.value ?: "") + "]]" } else t
            val key = if (p == old) np else p
            nf[key] = t2
            if (t2 != t) changed.add(key)
        }
        val wasDirty = dirty.remove(old)
        setStructure(nf, folders + ancestors(np))
        tabs = tabs.map { if (it == old) np else it }
        recent = recent.map { if (it == old) np else it }
        expanded = expanded + ancestors(np)
        if (active == old) active = np
        // The open note may be the moved one or one whose links changed. The editor must show the new text.
        nf[active]?.let { t -> if (editor.text != t) editor = TextFieldValue(t, shiftSelection(editor, t)) }
        if (wasDirty) dirty.add(np)
        dirty.addAll(changed)
        flushAll()
        persistSession()
    }

    /** Keeps the caret on the same text when a rewrite changes the note before or after it. */
    private fun shiftSelection(v: TextFieldValue, t: String): TextRange {
        val a = v.text
        var pre = 0
        while (pre < a.length && pre < t.length && a[pre] == t[pre]) pre++
        var suf = 0
        while (suf < a.length - pre && suf < t.length - pre && a[a.length - 1 - suf] == t[t.length - 1 - suf]) suf++
        fun map(i: Int) = when {
            i <= pre -> i
            i >= a.length - suf -> i + (t.length - a.length)
            else -> pre
        }.coerceIn(0, t.length)
        return TextRange(map(v.selection.start), map(v.selection.end))
    }

    fun deleteNote() {
        val p = active ?: return
        dirty.remove(p)
        val i = tabs.indexOf(p)
        val nt = tabs.filter { it != p }
        setStructure(files - p)
        tabs = nt
        recent = recent.filter { it != p }
        focus = false
        val na = if (nt.isEmpty()) null else nt[min(max(i, 0), nt.size - 1)]
        active = na
        loadEditor(na, null)
        disk("delete ${Md.base(p)}") { delete(p) }
        updateCount()
        persistSession()
    }

    fun openToday() {
        val d = LocalDate.now()
        val p = "Journal/$d.md"
        val existing = files[p]
        if (existing == null) {
            val c = "# ${d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK))}\n\n#journal\n\n"
            setStructure(files + (p to c), folders + "Journal")
            disk("create today's entry") { write(p, c) }
            openFile(p, OpenSel(c.length, c.length, forceSource = true, focus = true))
            updateCount()
        } else {
            openFile(p, OpenSel(existing.length, existing.length, forceSource = true, focus = true))
        }
    }

    fun openLink(name: String) {
        val t = name.split('|')[0].trim()
        if (t.isEmpty()) return
        val p = Md.resolve(names, t)
        if (p != null) openFile(p) else newNote(t)
    }

    fun startRename() {
        val a = active ?: return
        val b = Md.base(a)
        renameVal = TextFieldValue(b, TextRange(0, b.length))
        renaming = true
    }

    fun cancelRename() {
        renaming = false
    }

    fun commitRename() {
        if (!renaming) return
        val old = active
        if (old == null) {
            renaming = false
            return
        }
        val v = Md.safeName(renameVal.text)
        val np = Md.join(Md.dir(old), "$v.md")
        if (v.isEmpty() || np == old || np in files) {
            renaming = false
            return
        }
        val oldBase = Md.base(old)
        val nf = LinkedHashMap<String, String>()
        val changed = ArrayList<String>()
        for ((p, t) in files) {
            val t2 = if (settings.updateLinks) Md.rewriteLinks(t, oldBase, v) else t
            val key = if (p == old) np else p
            nf[key] = t2
            if (p != old && t2 != t) changed.add(key)
        }
        val activeText = nf.getValue(np)
        val writeActive = dirty.remove(old) || activeText != files[old]
        setStructure(nf)
        tabs = tabs.map { if (it == old) np else it }
        recent = recent.map { if (it == old) np else it }
        active = np
        renaming = false
        if (editor.text != activeText) {
            editor = TextFieldValue(activeText, shiftSelection(editor, activeText))
        }
        disk("rename $oldBase") {
            rename(old, np)
            if (writeActive) write(np, activeText)
        }
        dirty.addAll(changed)
        flushAll()
        persistSession()
    }

    // ---- Editor ----------------------------------------------------------------------------

    /**
     * Takes an edit from the editor that shows document [key]. A field for an older document can
     * still send a late selection change after a link opens another note. Quire ignores it, so it
     * can never write one note's text into another.
     */
    fun onEditorChange(key: Long, v: TextFieldValue) {
        if (key != docKey) return
        val p = active ?: return
        val old = editor
        if (v.text != old.text) {
            var nv = v
            if (settings.continueLists && isEnterInsert(old, v)) {
                Md.continueList(old.text, old.selection.start)?.let { (t, caret) -> nv = TextFieldValue(t, TextRange(caret)) }
            }
            editor = nv
            hit = null
            setDoc(p, nv.text)
        } else {
            if (v.selection != old.selection) hit = null
            editor = v
        }
    }

    private fun isEnterInsert(o: TextFieldValue, n: TextFieldValue): Boolean {
        if (!o.selection.collapsed) return false
        val pos = o.selection.start
        if (n.text.length != o.text.length + 1 || n.selection != TextRange(pos + 1)) return false
        if (pos >= n.text.length || n.text[pos] != '\n') return false
        return n.text.regionMatches(0, o.text, 0, pos) && n.text.regionMatches(pos + 1, o.text, pos, o.text.length - pos)
    }

    private fun applyEdit(nv: TextFieldValue) {
        val p = active ?: return
        editor = nv
        hit = null
        setDoc(p, nv.text)
    }

    fun wrapSelection(mk: String) {
        val v = editor
        val a = v.selection.min
        val b = v.selection.max
        val sel = v.text.substring(a, b)
        applyEdit(TextFieldValue(v.text.substring(0, a) + mk + sel + mk + v.text.substring(b), TextRange(a + mk.length, a + mk.length + sel.length)))
    }

    fun insertText(s: String) {
        val v = editor
        val a = v.selection.min
        val b = v.selection.max
        applyEdit(TextFieldValue(v.text.substring(0, a) + s + v.text.substring(b), TextRange(a + s.length)))
    }

    fun caretToEnd() {
        editor = editor.copy(selection = TextRange(editor.text.length))
        pendingEditorFocus = true
    }

    fun toggleTask(line: Int) {
        val p = active ?: return
        val t = files[p] ?: return
        val lines = t.split('\n').toMutableList()
        if (line !in lines.indices) return
        lines[line] = Md.toggleTaskLine(lines[line])
        val nt = lines.joinToString("\n")
        if (nt == t) return
        editor = TextFieldValue(nt, TextRange(min(editor.selection.start, nt.length), min(editor.selection.end, nt.length)))
        setDoc(p, nt)
    }

    /** Moves to a heading line. In source mode the caret goes there too. */
    fun jump(line: Int) {
        outlineOpen = false
        if (mode == Mode.Source) {
            val v = editor.text
            var pos = 0
            for (i in 0 until line) {
                val n = v.indexOf('\n', pos)
                if (n < 0) break
                pos = n + 1
            }
            editor = editor.copy(selection = TextRange(pos))
        }
        scrollReq = ScrollReq(line)
    }

    /** The link under the caret, as the status bar hint shows it. */
    fun caretLink(): Pair<IntRange, String>? {
        if (mode != Mode.Source || active == null) return null
        val doc = editor.text
        val pos = editor.selection.start.coerceIn(0, doc.length)
        val ls = if (pos == 0) 0 else doc.lastIndexOf('\n', pos - 1) + 1
        val le = doc.indexOf('\n', pos).let { if (it < 0) doc.length else it }
        val r = Md.linkAt(doc.substring(ls, le), pos - ls) ?: return null
        return IntRange(r.first.first + ls, r.first.last + ls) to r.second
    }

    // ---- Modes and panels ------------------------------------------------------------------

    private fun setModeInternal(m: Mode) {
        mode = m
        store.mode = if (m == Mode.Preview) "preview" else "source"
    }

    fun setModeTo(m: Mode) {
        if (m != mode) toggleMode()
    }

    fun toggleMode(focusEditor: Boolean = false) {
        if (active == null) return
        val toSource = mode == Mode.Preview
        setModeInternal(if (mode == Mode.Source) Mode.Preview else Mode.Source)
        if (toSource && focusEditor) pendingEditorFocus = true
    }

    fun toggleFocus() {
        if (active == null) return
        focus = !focus
        palette = null
        if (focus && mode == Mode.Source) pendingEditorFocus = true
    }

    fun leaveFocus() {
        focus = false
    }

    fun openSearch() {
        searchFocusPending = true
        leftPanel = Panel.Search
        drawerOpen = true
        focus = false
        palette = null
        settingsOpen = false
    }

    fun searchTag(tag: String) {
        leftPanel = Panel.Search
        query = "#$tag"
        drawerOpen = true
        focus = false
        palette = null
        outlineOpen = false
    }

    fun openPalette(m: String) {
        palette = PaletteState(m)
        vaultMenu = false
        drawerOpen = false
        settingsOpen = false
        outlineOpen = false
    }

    fun closePalette() {
        palette = null
    }

    fun openSettings() {
        settingsOpen = true
        vaultMenu = false
        drawerOpen = false
    }

    fun isDark(): Boolean = settings.theme == "dark" || (settings.theme == "system" && systemDark)

    // ---- Settings --------------------------------------------------------------------------

    fun updateSettings(f: (Settings) -> Settings) {
        val before = settings
        settings = f(settings)
        store.saveSettings(settings)
        if (settings.sync != before.sync) {
            val snap = secretMap(settings)
            ioScope.launch { secrets.save(snap) }
        }
    }

    private fun secretMap(s: Settings): Map<String, String> {
        val out = HashMap<String, String>()
        for ((pid, m) in s.sync.cfg) for ((k, v) in m) if (k in Sync.secretKeys) out["$pid.$k"] = v
        if (s.sync.pass.isNotEmpty()) out["sync.pass"] = s.sync.pass
        return out
    }

    private fun mergeSecrets(s: Settings, sec: Map<String, String>): Settings {
        val cfg = s.sync.cfg.mapValues { it.value.toMutableMap() }.toMutableMap()
        for ((k, v) in sec) {
            if (k == "sync.pass") continue
            val pid = k.substringBefore('.')
            val key = k.substringAfter('.')
            cfg.getOrPut(pid) { HashMap() }[key] = v
        }
        return s.copy(sync = s.sync.copy(cfg = cfg, pass = sec["sync.pass"] ?: ""))
    }

    private fun setSync(f: (SyncSettings) -> SyncSettings) = updateSettings { it.copy(sync = f(it.sync)) }

    fun syncCfg(): Map<String, String> = settings.sync.cfg[settings.sync.provider] ?: emptyMap()

    fun selectProvider(id: String) {
        if (id == settings.sync.provider) return
        setSync { it.copy(provider = id) }
        syncStatus = SyncStatus()
    }

    fun setCfg(key: String, v: String) {
        setSync { sy ->
            val pid = sy.provider
            val m = (sy.cfg[pid] ?: emptyMap()) + (key to v)
            sy.copy(cfg = sy.cfg + (pid to m))
        }
        val st = syncStatus
        syncStatus = if (st.state == "error") st.copy(missing = st.missing - key) else SyncStatus()
    }

    fun setWhen(v: String) = setSync { it.copy(whenSync = v) }
    fun toggleEncrypt() = setSync { it.copy(encrypt = !it.encrypt) }
    fun setPass(v: String) = setSync { it.copy(pass = v) }

    /**
     * Checks the required fields like testSync() in the prototype. Quire has no sync engine yet,
     * so a complete form only confirms that the settings are saved. No network call happens.
     */
    fun testSync() {
        val sy = settings.sync
        val cfg = syncCfg()
        val spec = Sync.spec(sy.provider, cfg, vault?.name ?: "")
        val missing = Sync.missing(spec, cfg)
        syncStatus = if (missing.isNotEmpty()) {
            SyncStatus("error", "Fill in " + missing.joinToString(", ") { it.label.lowercase() } + ".", missing.map { it.key })
        } else {
            SyncStatus("ok", "Sync isn't built into this version yet. Your settings are saved.")
        }
    }

    // ---- Command palette and quick switcher -------------------------------------------------

    fun commands(): List<Cmd> {
        val has = active != null
        val dark = isDark()
        return listOfNotNull(
            Cmd("New note") { newNote() },
            Cmd("New folder") { startNewFolder("") },
            Cmd("Open today's journal entry") { openToday() },
            Cmd("Open quick switcher") { openPalette("files") },
            if (has) Cmd(if (mode == Mode.Source) "Show preview" else "Show source") { toggleMode() } else null,
            Cmd("Search all notes") { openSearch() },
            if (has) Cmd(if (focus) "Leave focus mode" else "Enter focus mode") { toggleFocus() } else null,
            Cmd("Show sidebar") { drawerOpen = true },
            if (has) Cmd("Show outline") { outlineOpen = true } else null,
            Cmd(if (dark) "Use light theme" else "Use dark theme") { updateSettings { it.copy(theme = if (dark) "light" else "dark") } },
            if (has) Cmd("Rename note") { startRename() } else null,
            if (has) Cmd("Delete note") { deleteNote() } else null,
            if (has) Cmd("Close tab") { active?.let { closeTab(it) } } else null,
            Cmd("Open settings") { openSettings() },
            Cmd("Switch folder…") { goWelcome() },
            Cmd("Restore sample notes") { resetSample() },
        )
    }

    private fun segs(s: String, idx: List<Int>): List<Seg> {
        val set = idx.toHashSet()
        val out = ArrayList<Seg>()
        val cur = StringBuilder()
        var curHit: Boolean? = null
        for (i in s.indices) {
            val h = i in set
            if (curHit != null && curHit != h) {
                out.add(Seg(cur.toString(), curHit))
                cur.clear()
            }
            curHit = h
            cur.append(s[i])
        }
        if (curHit != null) out.add(Seg(cur.toString(), curHit))
        return out
    }

    fun palData(): PalData? {
        val p = palette ?: return null
        var m = p.mode
        var q = p.q
        if (m == "files" && q.startsWith(">")) {
            m = "commands"
            q = q.substring(1)
        }
        q = q.trim()
        val items = ArrayList<PalItem>()
        if (m == "files") {
            val paths = files.keys
            val list: List<Pair<String, List<Int>>> = if (q.isEmpty()) {
                val rec = recent.filter { it in files }
                (rec + paths.filter { it !in rec }.sorted()).map { it to emptyList() }
            } else {
                paths.mapNotNull { x ->
                    val f = Md.fuzzy(q, Md.base(x))
                    if (f != null) {
                        Triple(x, f.idx, f.score + 500)
                    } else {
                        Md.fuzzy(q, Md.stripExt(x))?.let { Triple(x, emptyList<Int>(), it.score) }
                    }
                }.sortedByDescending { it.third }.map { it.first to it.second }
            }
            list.take(50).forEach { (x, idx) -> items.add(PalItem(segs(Md.base(x), idx), Md.dir(x)) { openFile(x) }) }
            val typedPath = q.contains('/')
            val exists = if (typedPath) {
                val want = q.trim('/').removeSuffix(".md")
                paths.any { Md.stripExt(it).equals(want, ignoreCase = true) }
            } else {
                paths.any { Md.base(it).equals(q, ignoreCase = true) }
            }
            if (q.isNotEmpty() && !exists) {
                val name = q
                items.add(PalItem(listOf(Seg("Create note “$name”", false)), "new") {
                    palette = null
                    if (typedPath) newNoteAtPath(name) else newNote(name)
                })
            }
        } else {
            val cmds = commands()
            val list = if (q.isNotEmpty()) {
                cmds.mapNotNull { c -> Md.fuzzy(q, c.label)?.let { c to it.idx to it.score } }
                    .sortedByDescending { it.second }.map { it.first }
            } else {
                cmds.map { it to emptyList<Int>() }
            }
            list.forEach { (c, idx) ->
                items.add(PalItem(segs(c.label, idx), "") {
                    palette = null
                    c.run()
                })
            }
        }
        return PalData(m, items, p.idx.coerceIn(0, max(0, items.size - 1)))
    }

    fun setPaletteQuery(q: String) {
        palette = palette?.copy(q = q, idx = 0)
    }

    fun runPaletteItem() {
        val d = palData() ?: return
        d.items.getOrNull(d.idx)?.run?.invoke()
    }

    // ---- Back and hardware keys -------------------------------------------------------------

    /** Closes the top layer of the vault screen. Returns false when nothing was open. */
    fun onBack(): Boolean {
        when {
            vaultMenu -> vaultMenu = false
            newFolderParent != null -> newFolderParent = null
            drawerOpen -> drawerOpen = false
            outlineOpen -> outlineOpen = false
            renaming -> renaming = false
            focus -> focus = false
            else -> return false
        }
        return true
    }

    /** Handles the shortcuts from the prototype when a hardware keyboard is attached. */
    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_ESCAPE) {
            when {
                palette != null -> {
                    palette = null
                    if (active != null && mode == Mode.Source) pendingEditorFocus = true
                }
                settingsOpen -> settingsOpen = false
                vaultMenu -> vaultMenu = false
                newFolderParent != null -> newFolderParent = null
                drawerOpen -> drawerOpen = false
                outlineOpen -> outlineOpen = false
                renaming -> renaming = false
                creating != null -> creating = null
                focus -> focus = false
                else -> return false
            }
            return true
        }
        val p = palette
        if (p != null) {
            val d = palData() ?: return false
            val n = max(1, d.items.size)
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    palette = p.copy(idx = (d.idx + 1) % n)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    palette = p.copy(idx = (d.idx - 1 + n) % n)
                    return true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    d.items.getOrNull(d.idx)?.run?.invoke()
                    return true
                }
                KeyEvent.KEYCODE_DEL -> if (p.q.isEmpty() && p.mode == "commands") {
                    palette = p.copy(mode = "files", idx = 0)
                    return true
                }
            }
        }
        val mod = e.isCtrlPressed || e.isMetaPressed
        val inEditor = editorFocused && screen == Screen.Vault && mode == Mode.Source && palette == null && !settingsOpen
        if (!mod) {
            if (code == KeyEvent.KEYCODE_TAB && inEditor) {
                if (!e.isShiftPressed) insertText("  ")
                return true
            }
            return false
        }
        if (screen != Screen.Vault) return false
        if (e.isShiftPressed && code == KeyEvent.KEYCODE_F) {
            openSearch()
            return true
        }
        if (e.isShiftPressed || e.isAltPressed) return false
        when (code) {
            KeyEvent.KEYCODE_O -> openPalette("files")
            KeyEvent.KEYCODE_P -> openPalette("commands")
            KeyEvent.KEYCODE_E -> toggleMode(focusEditor = true)
            KeyEvent.KEYCODE_COMMA -> {
                settingsOpen = !settingsOpen
                palette = null
            }
            KeyEvent.KEYCODE_PERIOD -> toggleFocus()
            KeyEvent.KEYCODE_BACKSLASH -> drawerOpen = !drawerOpen
            KeyEvent.KEYCODE_N -> newNote()
            KeyEvent.KEYCODE_B -> if (inEditor) wrapSelection("**") else return false
            KeyEvent.KEYCODE_I -> if (inEditor) wrapSelection("*") else return false
            else -> return false
        }
        return true
    }
}
