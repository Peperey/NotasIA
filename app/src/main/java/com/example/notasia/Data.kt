package com.example.notasia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Si un modelo deja de funcionar, mira la lista actual en console.groq.com/docs/models
const val BASE = "https://api.groq.com/openai/v1"
const val STT_MODEL = "whisper-large-v3-turbo"
const val LLM_MODEL = "openai/gpt-oss-120b"

data class Task(val text: String, val done: Boolean)
data class Note(
    val id: Long, val title: String, val summary: String, val category: String,
    val transcript: String, val tasks: List<Task>, val created: Long,
    val images: List<String> = emptyList()
)
data class Organized(val title: String, val summary: String, val category: String, val tasks: List<String>)

fun Note.toJson(): JSONObject = JSONObject()
    .put("id", id).put("title", title).put("summary", summary).put("category", category)
    .put("transcript", transcript).put("created", created).put("images", JSONArray(images))
    .put("tasks", JSONArray(tasks.map { JSONObject().put("text", it.text).put("done", it.done) }))

fun noteFrom(o: JSONObject): Note {
    val ts = o.optJSONArray("tasks") ?: JSONArray()
    val im = o.optJSONArray("images") ?: JSONArray()
    return Note(
        o.getLong("id"), o.getString("title"), o.optString("summary"),
        o.optString("category", "General"), o.optString("transcript"),
        (0 until ts.length()).map {
            val t = ts.getJSONObject(it)
            Task(t.getString("text"), t.optBoolean("done"))
        },
        o.optLong("created", o.getLong("id")),
        (0 until im.length()).map { im.getString(it) }
    )
}

object Store {
    private fun file(ctx: Context) = File(ctx.filesDir, "notes.json")

    fun load(ctx: Context): List<Note> = try {
        val arr = JSONArray(file(ctx).readText())
        (0 until arr.length()).map { noteFrom(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(ctx: Context, notes: List<Note>) {
        file(ctx).writeText(JSONArray(notes.map { it.toJson() }).toString())
    }
}

private fun readResponse(c: HttpURLConnection): String {
    val ok = c.responseCode in 200..299
    val txt = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
    if (!ok) throw Exception("Error ${c.responseCode}: " + txt.take(200))
    return txt
}

fun transcribe(key: String, file: File): String {
    val boundary = "----notas" + System.currentTimeMillis()
    val c = URL("$BASE/audio/transcriptions").openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 30000
        c.readTimeout = 120000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { out ->
            fun field(n: String, v: String) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
            }
            field("model", STT_MODEL)
            field("response_format", "json")
            out.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"nota.m4a\"\r\n" +
                    "Content-Type: audio/mp4\r\n\r\n").toByteArray()
            )
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return JSONObject(readResponse(c)).getString("text").trim()
    } finally {
        c.disconnect()
    }
}

private fun post(key: String, body: JSONObject): String {
    val c = URL("$BASE/chat/completions").openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 30000
        c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return JSONObject(readResponse(c)).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    } finally {
        c.disconnect()
    }
}

fun chat(key: String, system: String, user: String, json: Boolean): String {
    val body = JSONObject().put("model", LLM_MODEL).put("temperature", 0.2)
        .put(
            "messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))
        )
    if (json) body.put("response_format", JSONObject().put("type", "json_object"))
    return post(key, body)
}

fun askNotes(key: String, notes: List<Note>, history: List<Pair<Boolean, String>>): String {
    val sb = StringBuilder()
    var used = 0
    for (n in notes) {
        val tasks = if (n.tasks.isEmpty()) "" else
            "Tareas: " + n.tasks.joinToString("; ") { (if (it.done) "[hecha] " else "[pendiente] ") + it.text } + "\n"
        val block = "## ${n.title} [${n.category}] (${fmt(n.created)})\n" +
            "Resumen: ${n.summary}\n" + tasks + "Texto: ${n.transcript.take(400)}\n\n"
        if (sb.length + block.length > 14000) break
        sb.append(block)
        used++
    }
    val system = "Eres el asistente de las notas personales del usuario. Responde en español, breve y claro. " +
        "Si la respuesta está en sus notas, respóndela usando SOLO lo que dicen y empieza con 'Según tus notas:'. " +
        "Si NO está en sus notas y es una pregunta de conocimiento general, empieza con " +
        "'No está en tus notas, pero en general:' y responde con tu conocimiento; si no estás seguro de un dato, dilo. " +
        "Nunca presentes conocimiento general como si estuviera en sus notas. " +
        "Si la pregunta es sobre las cosas del propio usuario (sus tareas, sus pendientes, lo que anotó) y no aparece, " +
        "di que no lo encuentras en sus notas y no inventes. " +
        "Hoy es ${fmt(System.currentTimeMillis())}. Se incluyen $used de ${notes.size} notas (las más recientes); " +
        "si la respuesta podría estar en notas más antiguas, menciónalo.\n\nNOTAS:\n$sb"
    val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
    history.takeLast(10).forEach { (isUser, text) ->
        messages.put(JSONObject().put("role", if (isUser) "user" else "assistant").put("content", text))
    }
    val body = JSONObject().put("model", LLM_MODEL).put("temperature", 0.2).put("messages", messages)
    return post(key, body)
}

fun organize(key: String, text: String, cats: List<String>): Organized {
    val system = """
Eres un asistente que organiza notas de voz transcritas. Responde SOLO con un objeto JSON con estas claves:
"title": título corto de máximo 6 palabras.
"summary": resumen de 1 a 3 frases con lo esencial.
"category": una categoría corta (una o dos palabras). Reutiliza una de las existentes si encaja; si no, crea una nueva.
"tasks": lista de tareas pendientes mencionadas, cada una como frase corta; lista vacía si no hay.
Escribe en el mismo idioma que la nota. No inventes información.
Categorías existentes: ${cats.joinToString(", ").ifEmpty { "ninguna" }}
""".trimIndent()
    val o = JSONObject(chat(key, system, text, true))
    val ts = o.optJSONArray("tasks") ?: JSONArray()
    return Organized(
        o.optString("title", "Nota").ifBlank { "Nota" },
        o.optString("summary", text),
        o.optString("category", "General").ifBlank { "General" },
        (0 until ts.length()).map { ts.optString(it) }.filter { it.isNotBlank() }
    )
}

fun noteLine(n: Note): String {
    val pend = n.tasks.filter { !it.done }.joinToString("; ") { it.text }
    return "- ${n.title} [${n.category}]: ${n.summary}" + if (pend.isNotEmpty()) " Pendientes: $pend" else ""
}

fun digest(key: String, notes: List<Note>): String {
    val system = "Eres un asistente personal. Con las notas del usuario, escribe en español un resumen " +
        "breve y claro: primero lo más importante y luego las tareas pendientes agrupadas por tema. " +
        "No inventes nada."
    return chat(key, system, notes.take(30).joinToString("\n") { noteLine(it) }, false)
}

fun notesToJson(notes: List<Note>): String = JSONArray(notes.map { it.toJson() }).toString(2)

fun notesFromJson(text: String): List<Note> {
    val arr = JSONArray(text)
    return (0 until arr.length()).map { noteFrom(arr.getJSONObject(it)) }
}

// Modelo de visión de Groq. Si deja de funcionar, cambia este nombre (ver console.groq.com/docs/vision).
const val VISION_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct"

fun saveImage(ctx: Context, uri: Uri): String? {
    return try {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rot = cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rot != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
        }
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > 1600) {
            val k = 1600f / longest
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
        val dir = File(ctx.filesDir, "images")
        dir.mkdirs()
        val f = File(dir, "img_" + System.currentTimeMillis() + ".jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        f.name
    } catch (e: Exception) {
        null
    }
}

fun readImage(key: String, file: File): String {
    val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    val prompt = "Transcribe todo el texto visible en esta imagen, en su idioma original y respetando el orden. " +
        "Si no hay texto, describe brevemente lo que se ve. Responde solo con el contenido, sin comentarios."
    val content = JSONArray()
        .put(JSONObject().put("type", "text").put("text", prompt))
        .put(
            JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
        )
    val body = JSONObject().put("model", VISION_MODEL).put("temperature", 0.1)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
    return post(key, body)
}
