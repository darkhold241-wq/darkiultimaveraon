package com.darki.os.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Envuelve TextToSpeech de Android. Expone speakAndWait() como funcion
 * suspend que solo continua cuando el audio termino de sonar de
 * verdad (via UtteranceProgressListener), no con un timer inventado.
 * Esto es lo que permite que la animacion de "hablando" del overlay
 * dure exactamente lo que dura el audio real.
 */
class TextToSpeechManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pendingQueue = mutableListOf<Pair<String, () -> Unit>>()
    private var currentOnDone: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "CO")
                tts?.setSpeechRate(1.05f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            currentOnDone?.invoke()
                            currentOnDone = null
                        }
                    }

                    @Deprecated("Deprecated en la API de Android, se mantiene por compatibilidad")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            currentOnDone?.invoke()
                            currentOnDone = null
                        }
                    }
                })
                ready = true
                pendingQueue.forEach { (text, onDone) -> speakInternal(text, onDone) }
                pendingQueue.clear()
            }
        }
    }

    /** Version "fire and forget", por si algun dia no importa esperar. */
    fun speak(text: String, onDone: () -> Unit = {}) {
        if (text.isBlank()) {
            onDone()
            return
        }
        if (ready) speakInternal(text, onDone) else pendingQueue.add(text to onDone)
    }

    /** Suspende la corrutina hasta que el audio termina de reproducirse. */
    suspend fun speakAndWait(text: String) {
        if (text.isBlank()) return
        suspendCancellableCoroutine<Unit> { cont ->
            speak(text) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private fun speakInternal(text: String, onDone: () -> Unit) {
        currentOnDone = onDone
        val id = "darki_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
