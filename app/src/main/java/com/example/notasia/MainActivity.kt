package com.example.notasia

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Purple = Color(0xFF5E35B1)
val Bg = Color(0xFFF7F5FB)
val Ink = Color(0xFF2B2B2B)
val Muted = Color(0xFF777777)
val ChipBg = Color(0xFFEDE7F6)
val RedErr = Color(0xFFC62828)
const val LOOSE_ID = 1L

fun norm(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

fun fmt(ms: Long): String = SimpleDateFormat("d MMM · HH:mm", Locale("es")).format(Date(ms))

@Suppress("DEPRECATION")
fun newRecorder(ctx: Context): MediaRecorder =
    if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

class MainActivity : ComponentActivity() {
    private var shared by mutableStateOf<String?>(null)

    private fun readShare(i: Intent?) {
        if (i != null && i.action == Intent.ACTION_SEND && i.type == "text/plain") {
            shared = i.getStringExtra(Intent.EXTRA_TEXT)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readShare(intent)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Purple)) {
                App(prefs, shared) { shared = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShare(intent)
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.White else Purple, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Purple else ChipBg)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
fun NoteCard(n: Note, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n.title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(fmt(n.created), color = Muted, fontSize = 12.sp)
        }
        Text(n.summary, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                n.category, color = Purple, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(ChipBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
            val pend = n.tasks.count { !it.done }
            if (pend > 0) Text("☐ $pend pendiente" + if (pend > 1) "s" else "", color = Muted, fontSize = 12.sp)
            if (n.images.isNotEmpty()) Text("🖼 " + n.images.size, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = Purple, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
fun NoteImage(ctx: Context, name: String) {
    val bmp = remember(name) {
        BitmapFactory.decodeFile(File(File(ctx.filesDir, "images"), name).path)?.asImageBitmap()
    }
    if (bmp != null) {
        Image(
            bitmap = bmp, contentDescription = null, contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        )
    } else {
        Text("(La foto no está en este teléfono)", color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun Detail(
    n: Note, back: () -> Unit, toggle: (Int) -> Unit,
    addTask: (String) -> Unit, removeTask: (Int) -> Unit,
    onEdit: (String, String, String, String) -> Unit,
    onAddPhoto: () -> Unit, onRemovePhoto: (String) -> Unit, delete: () -> Unit
) {
    var confirm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var eTitle by remember(editing) { mutableStateOf(n.title) }
    var eCat by remember(editing) { mutableStateOf(n.category) }
    var eSum by remember(editing) { mutableStateOf(n.summary) }
    var eText by remember(editing) { mutableStateOf(n.transcript) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", fontSize = 26.sp, color = Ink, modifier = Modifier.clickable { back() })
            Spacer(Modifier.weight(1f))
            if (!editing) TextButton(onClick = { editing = true }) { Text("✎ Editar") }
        }
        if (editing) {
            OutlinedTextField(
                value = eTitle, onValueChange = { eTitle = it }, singleLine = true,
                label = { Text("Título") }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = eCat, onValueChange = { eCat = it }, singleLine = true,
                label = { Text("Categoría") }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = eSum, onValueChange = { eSum = it }, minLines = 2,
                label = { Text("Resumen") }, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = eText, onValueChange = { eText = it }, minLines = 4,
                label = { Text("Texto de la nota") }, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onEdit(eTitle, eCat, eSum, eText); editing = false }) { Text("Guardar cambios") }
                TextButton(onClick = { editing = false }) { Text("Cancelar") }
            }
        } else {
            Text(n.title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                n.category, color = Purple, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(ChipBg)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Text(fmt(n.created), color = Muted, fontSize = 13.sp)
        }
        if (!editing) Section("Resumen") { Text(n.summary, color = Ink) }
        Section("Tareas") {
            n.tasks.forEachIndexed { i, t ->
                Row(Modifier.clickable { toggle(i) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = t.done, onCheckedChange = { toggle(i) })
                    Text(
                        t.text, color = if (t.done) Muted else Ink,
                        textDecoration = if (t.done) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f)
                    )
                    Text("✕", color = Muted, modifier = Modifier.clickable { removeTask(i) }.padding(8.dp))
                }
            }
            var newT by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newT, onValueChange = { newT = it }, singleLine = true,
                    placeholder = { Text("Nueva tarea") }, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { addTask(newT); newT = "" }, enabled = newT.isNotBlank()) { Text("Añadir") }
            }
        }
        Section("Fotos") {
            val ctx2 = LocalContext.current
            n.images.forEach { name ->
                NoteImage(ctx2, name)
                TextButton(onClick = { onRemovePhoto(name) }) { Text("Quitar foto", color = RedErr) }
            }
            TextButton(onClick = onAddPhoto) { Text("📷 Añadir foto") }
        }
        if (!editing) Section("Transcripción") { Text(n.transcript, color = Muted) }
        TextButton(onClick = { confirm = true }) { Text("Eliminar nota", color = RedErr) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Eliminar esta nota?") },
            confirmButton = { TextButton(onClick = { confirm = false; delete() }) { Text("Eliminar", color = RedErr) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
fun AskScreen(
    msgs: List<Pair<Boolean, String>>, loading: Boolean, status: String, error: String,
    recording: Boolean, onSend: (String) -> Unit, onMic: () -> Unit, onClear: () -> Unit, back: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, loading) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", fontSize = 26.sp, color = Ink, modifier = Modifier.clickable { back() }.padding(end = 12.dp))
            Text(
                "Pregúntale a tus notas", color = Purple, fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f)
            )
            if (msgs.isNotEmpty()) {
                Text("🗑", fontSize = 20.sp, modifier = Modifier.clickable { onClear() }.padding(8.dp))
            }
        }
        if (msgs.isEmpty()) {
            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pregunta lo que quieras sobre tus notas. Por ejemplo:", color = Muted)
                listOf(
                    "¿Qué tengo pendiente?",
                    "Resume mis notas de esta semana",
                    "¿Qué categorías tengo y de qué trata cada una?"
                ).forEach { q ->
                    Text(
                        q, color = Purple,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ChipBg)
                            .clickable { onSend(q) }.padding(12.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(msgs.size) { i ->
                    val (mine, text) = msgs[i]
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                    ) {
                        Text(
                            text, color = if (mine) Color.White else Ink,
                            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (mine) Purple else Color.White).padding(12.dp)
                        )
                    }
                }
            }
        }
        if (loading || status.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (status.isNotEmpty()) status else "Pensando…", color = Muted)
            }
        }
        if (error.isNotEmpty()) Text(error, color = RedErr, modifier = Modifier.padding(horizontal = 16.dp))
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text(if (recording) "Escuchando…" else "Escribe tu pregunta") }
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (recording) RedErr else ChipBg)
                    .clickable(enabled = !loading && status.isEmpty()) { onMic() },
                contentAlignment = Alignment.Center
            ) { Text(if (recording) "⏹" else "🎙", fontSize = 20.sp) }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = { onSend(input); input = "" },
                enabled = input.isNotBlank() && !loading && !recording
            ) { Text("➤") }
        }
    }
}

@Composable
fun App(prefs: SharedPreferences, shared: String?, onSharedUsed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes = remember { mutableStateListOf<Note>().apply { addAll(Store.load(ctx)) } }
    var key by remember { mutableStateOf(prefs.getString("groq", "") ?: "") }
    var showKey by remember { mutableStateOf(key.isEmpty()) }
    var keyInput by remember { mutableStateOf(key) }
    var tab by remember { mutableStateOf(0) }
    var openId by remember { mutableStateOf<Long?>(null) }
    var filter by remember { mutableStateOf("Todas") }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var pending by remember { mutableStateOf<File?>(null) }
    var digestText by remember { mutableStateOf<String?>(null) }
    var digestLoading by remember { mutableStateOf(false) }
    var newTask by remember { mutableStateOf("") }
    var showText by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }
    var showAsk by remember { mutableStateOf(false) }
    var askLoading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showBackup by remember { mutableStateOf(false) }
    var attachId by rememberSaveable { mutableStateOf<Long?>(null) }
    val askMsgs = remember { mutableStateListOf<Pair<Boolean, String>>() }

    LaunchedEffect(shared) {
        if (shared != null) {
            showAsk = false
            openId = null
            textInput = shared
            showText = true
            onSharedUsed()
        }
    }

    BackHandler(enabled = openId != null) { openId = null }

    fun process(file: File) {
        status = "Transcribiendo…"
        error = ""
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { transcribe(key, file) }
                if (text.isBlank()) throw Exception("No se escuchó nada. Intenta de nuevo.")
                status = "Organizando…"
                val cats = notes.map { it.category }.distinct()
                val o = withContext(Dispatchers.IO) { organize(key, text, cats) }
                val now = System.currentTimeMillis()
                val n = Note(now, o.title, o.summary, o.category, text, o.tasks.map { Task(it, false) }, now)
                notes.add(0, n)
                Store.save(ctx, notes)
                file.delete()
                pending = null
                openId = n.id
            } catch (e: Exception) {
                error = e.message ?: "Error"
                pending = file
            }
            status = ""
        }
    }

    fun ask(q: String) {
        val t = q.trim()
        if (t.isEmpty() || askLoading) return
        askMsgs.add(true to t)
        askLoading = true
        error = ""
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { askNotes(key, notes.toList(), askMsgs.toList()) }
                askMsgs.add(false to r.trim())
            } catch (e: Exception) {
                error = e.message ?: "Error"
            }
            askLoading = false
        }
    }

    fun processAsk(file: File) {
        status = "Transcribiendo…"
        error = ""
        scope.launch {
            try {
                val q = withContext(Dispatchers.IO) { transcribe(key, file) }
                status = ""
                if (q.isBlank()) throw Exception("No se escuchó nada. Intenta de nuevo.")
                ask(q)
            } catch (e: Exception) {
                status = ""
                error = e.message ?: "Error"
            }
            file.delete()
            pending = null
        }
    }

    fun startRec() {
        try {
            val f = File(ctx.cacheDir, "nota_" + System.currentTimeMillis() + ".m4a")
            val r = newRecorder(ctx)
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(48000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            pending = f
            recording = true
            error = ""
        } catch (e: Exception) {
            error = "No pude grabar: " + e.message
        }
    }

    fun stopRec() {
        val r = recorder
        val f = pending
        recording = false
        recorder = null
        try { r?.stop() } catch (e: Exception) { }
        r?.release()
        if (f != null && f.exists() && f.length() > 1000) {
            if (showAsk) processAsk(f) else process(f)
        } else {
            error = "La grabación fue muy corta."
            pending = null
        }
    }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRec() else error = "Sin permiso de micrófono."
    }

    fun onMic() {
        if (key.isEmpty()) { showKey = true; return }
        if (recording) { stopRec(); return }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRec()
        } else {
            perm.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun closeAsk() {
        if (recording) {
            try { recorder?.stop() } catch (e: Exception) { }
            recorder?.release()
            recorder = null
            recording = false
            pending?.delete()
            pending = null
        }
        showAsk = false
        error = ""
    }

    fun updateNote(id: Long, title: String, category: String, summary: String, text: String) {
        val p = notes.indexOfFirst { it.id == id }
        if (p < 0) return
        val n = notes[p]
        notes[p] = n.copy(
            title = title.trim().ifBlank { n.title },
            category = category.trim().ifBlank { "General" },
            summary = summary.trim(),
            transcript = text.trim()
        )
        Store.save(ctx, notes)
    }

    fun addImage(id: Long, name: String) {
        val p = notes.indexOfFirst { it.id == id }
        if (p < 0) return
        notes[p] = notes[p].copy(images = notes[p].images + name)
        Store.save(ctx, notes)
    }

    fun removeImage(id: Long, name: String) {
        val p = notes.indexOfFirst { it.id == id }
        if (p < 0) return
        notes[p] = notes[p].copy(images = notes[p].images.filter { it != name })
        Store.save(ctx, notes)
        File(File(ctx.filesDir, "images"), name).delete()
    }

    fun processPhoto(uri: Uri) {
        status = "Preparando la foto…"
        error = ""
        scope.launch {
            val name = withContext(Dispatchers.IO) { saveImage(ctx, uri) }
            if (name == null) {
                status = ""
                error = "No pude abrir esa imagen."
                return@launch
            }
            val now = System.currentTimeMillis()
            var note = Note(now, "Foto", "Foto sin texto leído.", "Fotos", "", emptyList(), now, listOf(name))
            var fail: String? = null
            var text = ""
            try {
                status = "Leyendo el texto de la imagen…"
                text = withContext(Dispatchers.IO) {
                    readImage(key, File(File(ctx.filesDir, "images"), name))
                }.trim()
            } catch (e: Exception) {
                fail = e.message ?: "Error"
            }
            if (text.isNotBlank()) {
                note = Note(now, "Foto", text.take(200), "Fotos", text, emptyList(), now, listOf(name))
                try {
                    status = "Organizando…"
                    val cats = notes.map { it.category }.distinct()
                    val o = withContext(Dispatchers.IO) { organize(key, text, cats) }
                    note = Note(now, o.title, o.summary, o.category, text, o.tasks.map { Task(it, false) }, now, listOf(name))
                } catch (e: Exception) {
                    fail = e.message ?: "Error"
                }
            }
            notes.add(0, note)
            Store.save(ctx, notes)
            status = ""
            if (fail != null) {
                Toast.makeText(ctx, "No pude leer todo el texto: " + fail + ". Guardé la foto igual.", Toast.LENGTH_LONG).show()
            }
            openId = note.id
        }
    }

    fun toggle(id: Long, idx: Int) {
        val p = notes.indexOfFirst { it.id == id }
        if (p < 0) return
        val n = notes[p]
        notes[p] = n.copy(tasks = n.tasks.mapIndexed { i, t -> if (i == idx) t.copy(done = !t.done) else t })
        Store.save(ctx, notes)
    }

    fun addTask(id: Long, text: String) {
        val t = text.trim()
        val p = notes.indexOfFirst { it.id == id }
        if (t.isEmpty() || p < 0) return
        notes[p] = notes[p].copy(tasks = notes[p].tasks + Task(t, false))
        Store.save(ctx, notes)
    }

    fun removeTask(id: Long, idx: Int) {
        val p = notes.indexOfFirst { it.id == id }
        if (p < 0) return
        notes[p] = notes[p].copy(tasks = notes[p].tasks.filterIndexed { i, _ -> i != idx })
        Store.save(ctx, notes)
    }

    fun addLoose(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (notes.any { it.id == LOOSE_ID }) {
            addTask(LOOSE_ID, t)
        } else {
            notes.add(
                Note(
                    LOOSE_ID, "Tareas sueltas", "Tareas añadidas a mano.", "Tareas", "",
                    listOf(Task(t, false)), System.currentTimeMillis()
                )
            )
            Store.save(ctx, notes)
        }
    }

    fun processText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        status = "Organizando…"
        error = ""
        scope.launch {
            try {
                val cats = notes.map { it.category }.distinct()
                val o = withContext(Dispatchers.IO) { organize(key, t, cats) }
                val now = System.currentTimeMillis()
                val n = Note(now, o.title, o.summary, o.category, t, o.tasks.map { Task(it, false) }, now)
                notes.add(0, n)
                Store.save(ctx, notes)
                textInput = ""
                showText = false
                openId = n.id
            } catch (e: Exception) {
                error = e.message ?: "Error"
            }
            status = ""
        }
    }

    fun saveRaw(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        val n = Note(now, t.lineSequence().first().take(40), t.take(200), "Sin categoría", t, emptyList(), now)
        notes.add(0, n)
        Store.save(ctx, notes)
        textInput = ""
        showText = false
        error = ""
        openId = n.id
    }

    fun makeDigest() {
        digestText = ""
        digestLoading = true
        scope.launch {
            digestText = try {
                withContext(Dispatchers.IO) { digest(key, notes.toList()) }
            } catch (e: Exception) {
                e.message ?: "Error"
            }
            digestLoading = false
        }
    }

    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = attachId
        if (uri != null && id != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { saveImage(ctx, uri) }
                if (name != null) addImage(id, name)
                else Toast.makeText(ctx, "No pude abrir esa imagen.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) processPhoto(uri)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(notesToJson(notes.toList()).toByteArray()) }
                Toast.makeText(ctx, "Respaldo guardado (" + notes.size + " notas)", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "No se pudo guardar: " + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val incoming = notesFromJson(text)
                val have = notes.map { it.id }.toSet()
                val fresh = incoming.filter { it.id !in have }
                if (fresh.isNotEmpty()) {
                    val merged = (notes.toList() + fresh).sortedByDescending { it.created }
                    notes.clear()
                    notes.addAll(merged)
                    Store.save(ctx, notes)
                }
                Toast.makeText(ctx, "Importadas " + fresh.size + " notas nuevas", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Archivo no válido: " + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    BackHandler(enabled = showAsk) { closeAsk() }

    Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        val current = notes.firstOrNull { it.id == openId }
        if (showAsk) {
            AskScreen(
                askMsgs, askLoading, status, error, recording,
                onSend = { ask(it) }, onMic = { onMic() },
                onClear = { askMsgs.clear(); error = "" },
                back = { closeAsk() }
            )
        } else if (current != null) {
            Detail(
                current, back = { openId = null },
                toggle = { idx -> toggle(current.id, idx) },
                addTask = { addTask(current.id, it) },
                removeTask = { idx -> removeTask(current.id, idx) },
                onEdit = { t, c, sm, tx -> updateNote(current.id, t, c, sm, tx) },
                onAddPhoto = { attachId = current.id; attachLauncher.launch("image/*") },
                onRemovePhoto = { removeImage(current.id, it) },
                delete = {
                    current.images.forEach { File(File(ctx.filesDir, "images"), it).delete() }
                    notes.removeAll { it.id == current.id }
                    Store.save(ctx, notes)
                    openId = null
                }
            )
        } else {
            val cats = notes.map { it.category }.distinct()
            val f = if (filter == "Todas" || cats.contains(filter)) filter else "Todas"
            val q = norm(query.trim())
            val shown = notes.filter { n ->
                (f == "Todas" || n.category == f) &&
                    (q.isEmpty() || norm(
                        n.title + " " + n.summary + " " + n.category + " " + n.transcript + " " +
                            n.tasks.joinToString(" ") { it.text }
                    ).contains(q))
            }
            val pendingTasks = notes.flatMap { n ->
                n.tasks.mapIndexedNotNull { i, t -> if (!t.done) Triple(n, i, t) else null }
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Notas IA", color = Purple, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Text(
                        "💬", fontSize = 22.sp,
                        modifier = Modifier.clickable {
                            if (key.isEmpty()) showKey = true else { error = ""; showAsk = true }
                        }.padding(8.dp)
                    )
                    if (notes.isNotEmpty() && key.isNotEmpty()) {
                        Text("✨", fontSize = 24.sp, modifier = Modifier.clickable { makeDigest() }.padding(8.dp))
                    }
                    Text("💾", fontSize = 22.sp, modifier = Modifier.clickable { showBackup = true }.padding(8.dp))
                    Text("🔑", fontSize = 22.sp, modifier = Modifier.clickable { keyInput = key; showKey = true }.padding(8.dp))
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Notas", tab == 0) { tab = 0 }
                    Pill("Tareas (${pendingTasks.size})", tab == 1) { tab = 1 }
                }
                if (tab == 0) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Buscar en tus notas") },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                Text("✕", color = Muted, modifier = Modifier.clickable { query = "" }.padding(12.dp))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                if (tab == 0 && cats.isNotEmpty()) {
                    LazyRow(
                        Modifier.padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item { Pill("Todas", f == "Todas") { filter = "Todas" } }
                        items(cats) { c -> Pill(c, f == c) { filter = c } }
                    }
                }
                if (tab == 0) {
                    if (shown.isEmpty()) {
                        Text(
                            if (notes.isEmpty()) "Aún no hay notas. Toca el micrófono y cuéntame lo que quieras guardar." else "No encontré notas con esa búsqueda.",
                            color = Muted, modifier = Modifier.weight(1f).padding(24.dp)
                        )
                    } else {
                        LazyColumn(
                            Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(shown, key = { it.id }) { n -> NoteCard(n) { openId = n.id } }
                        }
                    }
                } else {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newTask, onValueChange = { newTask = it }, singleLine = true,
                            placeholder = { Text("Nueva tarea") }, modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { addLoose(newTask); newTask = "" },
                            enabled = newTask.isNotBlank()
                        ) { Text("Añadir") }
                    }
                    if (pendingTasks.isEmpty()) {
                        Text("No tienes tareas pendientes. 🎉", color = Muted, modifier = Modifier.weight(1f).padding(24.dp))
                    } else {
                        LazyColumn(
                            Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(pendingTasks) { (n, i, t) ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)
                                        .clickable { toggle(n.id, i) }.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = false, onCheckedChange = { toggle(n.id, i) })
                                    Column {
                                        Text(t.text, color = Ink)
                                        Text(n.title, color = Muted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (status.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(status, color = Ink)
                        }
                    }
                    if (error.isNotEmpty()) {
                        Text(error, color = RedErr)
                        if (pending != null && !recording && status.isEmpty()) {
                            Row {
                                TextButton(onClick = { pending?.let { process(it) } }) { Text("Reintentar") }
                                TextButton(onClick = { pending?.delete(); pending = null; error = "" }) { Text("Descartar") }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(ChipBg)
                                .clickable(enabled = status.isEmpty() && !recording) { showText = true },
                            contentAlignment = Alignment.Center
                        ) { Text("✏️", fontSize = 22.sp) }
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(ChipBg)
                                .clickable(enabled = status.isEmpty() && !recording) {
                                    if (key.isEmpty()) showKey = true else photoLauncher.launch("image/*")
                                },
                            contentAlignment = Alignment.Center
                        ) { Text("📷", fontSize = 22.sp) }
                        Box(
                            Modifier.size(72.dp).clip(CircleShape)
                                .background(if (recording) RedErr else Purple)
                                .clickable(enabled = status.isEmpty()) { onMic() },
                            contentAlignment = Alignment.Center
                        ) { Text(if (recording) "⏹" else "🎙", fontSize = 30.sp) }
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(ChipBg)
                                .clickable(enabled = status.isEmpty() && !recording) {
                                    if (key.isEmpty()) showKey = true else { error = ""; showAsk = true }
                                },
                            contentAlignment = Alignment.Center
                        ) { Text("💬", fontSize = 22.sp) }
                    }
                    Text(if (recording) "Toca para terminar" else "Escribir · Foto · Hablar · Preguntar", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }

    if (showBackup) {
        AlertDialog(
            onDismissRequest = { showBackup = false },
            title = { Text("💾 Respaldo de notas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Guarda tus notas en un archivo para no perderlas, o recupéralas desde uno. " +
                            "No incluye tu clave de Groq.",
                        color = Muted
                    )
                    Button(
                        onClick = { showBackup = false; exportLauncher.launch("notas-ia-respaldo.json") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Exportar notas") }
                    OutlinedButton(
                        onClick = { showBackup = false; importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Importar notas") }
                    Text("Al importar, solo se añaden las notas que no tengas ya.", color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showBackup = false }) { Text("Cerrar") } }
        )
    }

    if (showText) {
        AlertDialog(
            onDismissRequest = { if (status.isEmpty()) { showText = false; error = "" } },
            title = { Text("Nueva nota de texto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = textInput, onValueChange = { textInput = it },
                        placeholder = { Text("Escribe tu nota…") },
                        minLines = 4, modifier = Modifier.fillMaxWidth()
                    )
                    if (status.isNotEmpty()) Text(status, color = Muted)
                    if (error.isNotEmpty()) {
                        Text(error, color = RedErr, fontSize = 13.sp)
                        TextButton(onClick = { saveRaw(textInput) }) { Text("Guardar sin IA") }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { if (key.isEmpty()) showKey = true else processText(textInput) },
                    enabled = textInput.isNotBlank() && status.isEmpty()
                ) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showText = false; error = "" },
                    enabled = status.isEmpty()
                ) { Text("Cancelar") }
            }
        )
    }

    if (showKey) {
        AlertDialog(
            onDismissRequest = { if (key.isNotEmpty()) showKey = false },
            title = { Text("Groq API key") },
            text = {
                OutlinedTextField(
                    value = keyInput, onValueChange = { keyInput = it }, singleLine = true,
                    label = { Text("gsk_...") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    key = keyInput.trim()
                    prefs.edit().putString("groq", key).apply()
                    if (key.isNotEmpty()) showKey = false
                }) { Text("Guardar") }
            }
        )
    }

    if (digestText != null) {
        AlertDialog(
            onDismissRequest = { digestText = null },
            title = { Text("✨ Resumen de tus notas") },
            text = {
                if (digestLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Generando…")
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) { Text(digestText ?: "") }
                }
            },
            confirmButton = { TextButton(onClick = { digestText = null }) { Text("Cerrar") } }
        )
    }
}
