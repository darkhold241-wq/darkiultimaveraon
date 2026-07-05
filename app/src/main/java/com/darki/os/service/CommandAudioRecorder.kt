package com.darki.os.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Graba audio crudo PCM16 mono a 16kHz por una duracion fija. Se usa
 * para dos cosas: (1) enrolar la voz del dueno desde MainActivity, y
 * (2) capturar el audio de cada comando en WakeWordEngine para poder
 * verificar quien habla ademas de transcribir que dijo.
 *
 * Nota: esto abre su propio AudioRecord, separado del que usa Vosk
 * internamente. Por eso WakeWordEngine detiene su SpeechService antes
 * de llamar a esto (Android no deja tener dos capturas de microfono
 * activas al mismo tiempo de forma confiable en todos los fabricantes).
 */
class CommandAudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
    }

    @SuppressLint("MissingPermission")
    suspend fun recordPcm(durationMs: Long = 3000): ShortArray = withContext(Dispatchers.IO) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return@withContext ShortArray(0)

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) return@withContext ShortArray(0)

        val bufferSize = minBufferSize * 2
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext ShortArray(0)
        }

        val totalSamples = (SAMPLE_RATE * durationMs / 1000L).toInt()
        val output = ShortArray(totalSamples)
        var written = 0

        try {
            recorder.startRecording()
            val chunk = ShortArray(bufferSize / 2)
            while (written < totalSamples) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) break
                val toCopy = minOf(read, totalSamples - written)
                System.arraycopy(chunk, 0, output, written, toCopy)
                written += toCopy
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        if (written < totalSamples) output.copyOf(written) else output
    }
}
