package com.darki.os.network

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Cliente minimo para la API de mensajes de Anthropic (Claude).
 * Mantiene el historial de la conversacion en memoria (se pierde si se
 * mata el proceso; la persistencia real llega con el bloque de Memoria).
 *
 * Nota sobre el modelo: usa el identificador de modelo actual de la API
 * de Anthropic. Si al compilar aparece un error de "modelo no encontrado",
 * revisa el mas reciente en https://docs.claude.com y actualizalo aqui.
 */
class ClaudeApiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<JSONObject>()

    private fun buildSystemPrompt(memories: List<String> = emptyList()): String {
        val base = """
            Sos DARKI, el asistente de IA personal de Jostin. Hablas espanol
            colombiano, relajado, breve y natural, como un parce cercano.
            Usas expresiones como "de una", "listo", "ya quedo", "que locura"
            cuando encajan de forma natural, sin forzarlas en cada frase.
            Tus respuestas se leen en voz alta con texto a voz: evita listas,
            markdown, emojis o caracteres especiales, y se conciso.
        """.trimIndent()

        if (memories.isEmpty()) return base
        val memoryBlock = memories.joinToString("\n") { "- $it" }
        return "$base\n\nCosas que Jostin te ha pedido recordar (usalas si son relevantes, no las repitas todas de una):\n$memoryBlock"
    }

    suspend fun sendMessage(userText: String, memories: List<String> = emptyList()): String = suspendCoroutine { cont ->
        val userMsg = JSONObject().put("role", "user").put("content", userText)
        history.add(userMsg)

        val body = JSONObject().apply {
            put("model", "claude-sonnet-5")
            put("max_tokens", 400)
            put("system", buildSystemPrompt(memories))
            put("messages", JSONArray(history))
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                history.removeAt(history.lastIndex)
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        history.removeAt(history.lastIndex)
                        cont.resumeWithException(
                            IOException("Error de la API: ${res.code} ${res.message}")
                        )
                        return
                    }
                    val json = JSONObject(res.body?.string().orEmpty())
                    val contentArray = json.getJSONArray("content")
                    val text = (0 until contentArray.length())
                        .map { contentArray.getJSONObject(it) }
                        .filter { it.optString("type") == "text" }
                        .joinToString(" ") { it.getString("text") }

                    history.add(JSONObject().put("role", "assistant").put("content", text))
                    cont.resume(text)
                }
            }
        })
    }

    fun resetHistory() = history.clear()

    // -----------------------------------------------------------------
    // MODO AGENTE (control del telefono via Accessibility Service)
    // -----------------------------------------------------------------
    // No usa el "history" de la conversacion normal: cada paso manda el
    // estado actual de la pantalla completo, porque la pantalla cambia
    // entre paso y paso.

    private val agentSystemPrompt = """
        Sos DARKI controlando el telefono Android de Jostin como si fueras
        una persona operandolo con los dedos. Recibis la orden del usuario,
        una descripcion en texto de lo que hay en pantalla ahora mismo, y
        los pasos que ya ejecutaste en este intento.

        Respondes SOLO un objeto JSON, sin texto antes ni despues, sin
        backticks ni markdown, con esta forma exacta segun la accion:

        {"action":"click_text","target":"texto visible del boton o item"}
        {"action":"type_text","text":"texto a escribir en el campo con foco"}
        {"action":"scroll_down"}
        {"action":"scroll_up"}
        {"action":"open_app","app":"nombre de la app o su paquete"}
        {"action":"back"}
        {"action":"home"}
        {"action":"recents"}
        {"action":"notifications"}
        {"action":"wait"}
        {"action":"speak","text":"algo que decirle a Jostin sin tocar nada"}
        {"action":"done","text":"frase final breve para decirle a Jostin"}

        Reglas: "target" debe ser un texto que aparezca LITERAL en la
        lista de elementos de pantalla que te paso, no lo inventes. Si la
        orden ya se cumplio segun lo que ves, usa "done". Si es solo
        conversacion y no requiere tocar nada, usa "speak". Resuelve de
        a un toque por respuesta, vas a recibir la pantalla actualizada
        despues de cada accion. Si no sabes que hacer, usa "speak"
        explicando el problema en vez de adivinar.
    """.trimIndent()

    suspend fun planAction(
        userCommand: String,
        screenDump: String,
        previousSteps: List<String>
    ): JSONObject = suspendCoroutine { cont ->
        val prompt = buildString {
            append("Orden de Jostin: \"$userCommand\"\n\n")
            append("Pantalla actual:\n")
            append(screenDump)
            if (previousSteps.isNotEmpty()) {
                append("\nPasos ya hechos en este intento:\n")
                previousSteps.forEachIndexed { i, step -> append("${i + 1}. $step\n") }
            }
            append("\nSiguiente accion (solo el JSON):")
        }

        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", prompt)
        )

        val body = JSONObject().apply {
            put("model", "claude-sonnet-5")
            put("max_tokens", 300)
            put("system", agentSystemPrompt)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        cont.resumeWithException(
                            IOException("Error de la API: ${res.code} ${res.message}")
                        )
                        return
                    }
                    val json = JSONObject(res.body?.string().orEmpty())
                    val contentArray = json.getJSONArray("content")
                    val rawText = (0 until contentArray.length())
                        .map { contentArray.getJSONObject(it) }
                        .filter { it.optString("type") == "text" }
                        .joinToString(" ") { it.getString("text") }
                        .trim()
                        .removePrefix("```json").removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    val action = runCatching { JSONObject(rawText) }.getOrElse {
                        JSONObject()
                            .put("action", "speak")
                            .put("text", "Se me revolvio la respuesta, parce. Repiteme la orden.")
                    }
                    cont.resume(action)
                }
            }
        })
    }
}
