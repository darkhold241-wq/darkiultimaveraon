package com.darki.os.service

import android.content.Context
import com.darki.os.voice.SpeakerVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Motor de reconocimiento continuo offline basado en Vosk, con
 * verificacion de voz del dueno integrada.
 *
 * Flujo completo de cada activacion:
 *  1. ESPERANDO_ACTIVACION: SpeechService escucha en loop hasta que el
 *     resultado final de una frase contenga la palabra "darki".
 *  2. Al detectar la palabra de activacion, se DETIENE el SpeechService
 *     (Android no permite dos capturas de microfono simultaneas de
 *     forma confiable) y se graba un clip de audio crudo aparte con
 *     CommandAudioRecorder.
 *  3. Ese mismo clip se usa para DOS cosas:
 *       a) Verificar si la voz coincide con la del dueno (SpeakerVerifier)
 *       b) Transcribirlo a texto con un Recognizer de Vosk "de un solo uso"
 *          (recognizer.acceptWaveForm sobre el buffer ya grabado, en vez
 *          de re-escuchar el microfono con el SpeechService continuo)
 *  4. Se llama a onCommandCaptured(texto, esDueno, similitud) y se
 *     reanuda el loop de escucha de la palabra de activacion.
 *
 * IMPORTANTE - requiere un paso manual antes de compilar:
 * Vosk necesita un modelo de lenguaje en espanol que NO se incluye aqui
 * porque pesa varias decenas de MB (fuera del alcance de este generador
 * de codigo). Pasos:
 *   1. Descargar "vosk-model-small-es-0.42" desde
 *      https://alphacephei.com/vosk/models
 *   2. Descomprimir el contenido dentro de app/src/main/assets/model
 *      (la carpeta "model" debe quedar directamente en assets/model)
 *   3. Vosk lo carga solo en tiempo de ejecucion, no hace falta tocar
 *      este archivo.
 *
 * Sin el modelo, start() llamara a onError() explicando el problema.
 */
class WakeWordEngine(
    private val context: Context,
    private val wakeWord: String = "darki",
    private val onWakeWordDetected: () -> Unit,
    private val onCommandCaptured: (text: String, esDueno: Boolean, similitud: Float) -> Unit,
    private val onError: (String) -> Unit
) {
    private enum class Mode { ESPERANDO_ACTIVACION, CAPTURANDO_COMANDO }

    private var mode = Mode.ESPERANDO_ACTIVACION
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var stopped = false

    private val recorder = CommandAudioRecorder(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        stopped = false
        StorageService.unpack(
            context, "model", "model",
            { loadedModel ->
                model = loadedModel
                startListeningForWakeWord()
            },
            { exception ->
                onError(
                    "Falta el modelo de voz en assets/model. Descargalo de " +
                        "alphacephei.com/vosk/models. Detalle: ${exception.message}"
                )
            }
        )
    }

    private fun startListeningForWakeWord() {
        if (stopped) return
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            speechService = service
            mode = Mode.ESPERANDO_ACTIVACION
            service.startListening(object : RecognitionListener {

                override fun onPartialResult(hypothesis: String?) {
                    // Reservado para una futura mejora: mostrar la
                    // transcripcion en vivo mientras la persona habla.
                }

                override fun onResult(hypothesis: String?) {
                    if (mode != Mode.ESPERANDO_ACTIVACION) return
                    val text = extractFinalText(hypothesis)
                    if (text.isBlank()) return

                    if (text.contains(wakeWord, ignoreCase = true)) {
                        mode = Mode.CAPTURANDO_COMANDO
                        handleWakeWordDetected()
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    // Se dispara al detener el reconocedor; no se usa aqui
                    // porque el ciclo de vida lo maneja onResult.
                }

                override fun onError(exception: Exception?) {
                    onError(exception?.message ?: "Error desconocido reconociendo voz")
                }

                override fun onTimeout() {
                    // Vosk sigue escuchando de forma continua; no se
                    // requiere accion adicional.
                }
            })
        } catch (e: IOException) {
            onError("No se pudo iniciar el reconocedor de voz: ${e.message}")
        }
    }

    /**
     * Al detectar "darki": avisamos al que nos escucha (para el TTS de
     * "Dime, Jostin."), soltamos el SpeechService continuo, grabamos un
     * clip aparte del comando real, lo verificamos + transcribimos, y
     * reanudamos la escucha de la palabra de activacion.
     */
    private fun handleWakeWordDetected() {
        onWakeWordDetected()
        speechService?.stop()
        speechService = null

        scope.launch {
            val pcm = recorder.recordPcm(durationMs = COMMAND_CLIP_MS)

            val verification = SpeakerVerifier.verify(context, pcm)
            val text = transcribeClip(pcm)

            if (!stopped) {
                onCommandCaptured(text, verification.isOwner, verification.similarity)
                startListeningForWakeWord()
            }
        }
    }

    /**
     * Transcribe un buffer de audio ya grabado usando un Recognizer de
     * Vosk "de un solo uso" (no el SpeechService continuo). Esto evita
     * tener que volver a escuchar el microfono dos veces para lo mismo:
     * el mismo clip que usamos para verificar la voz se reutiliza aca.
     */
    private fun transcribeClip(pcm: ShortArray): String {
        if (pcm.isEmpty()) return ""
        val currentModel = model ?: return ""

        return try {
            val recognizer = Recognizer(currentModel, SAMPLE_RATE)
            val bytes = shortsToBytesLE(pcm)
            recognizer.acceptWaveForm(bytes, bytes.size)
            val result = extractFinalText(recognizer.finalResult)
            recognizer.close()
            result
        } catch (e: Exception) {
            ""
        }
    }

    private fun shortsToBytesLE(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val v = shorts[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun extractFinalText(hypothesisJson: String?): String {
        if (hypothesisJson.isNullOrBlank()) return ""
        return try {
            JSONObject(hypothesisJson).optString("text").trim()
        } catch (e: Exception) {
            ""
        }
    }

    fun stop() {
        stopped = true
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
    }

    companion object {
        private const val SAMPLE_RATE = 16000.0f
        private const val COMMAND_CLIP_MS = 3200L
    }
}
