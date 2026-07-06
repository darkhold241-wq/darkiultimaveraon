package com.darki.os.voice

import android.content.Context
import com.darki.os.data.SecurePrefs

/** Resultado de comparar un audio nuevo contra la huella de voz guardada. */
data class VerificationResult(
    val similarity: Float,
    val isOwner: Boolean
)

/**
 * Enrola y verifica la voz del dueno de DARKI. Ver VoiceprintExtractor
 * para el detalle tecnico de como se arma la huella.
 *
 * El umbral (THRESHOLD) es el numero mas importante de este archivo:
 * mas alto = mas dificil hacerse pasar por vos, pero mas riesgo de que
 * DARKI no te reconozca a vos mismo (por un resfriado, mucho ruido de
 * fondo, etc). 0.83 es un punto de partida razonable; si notas que te
 * rechaza seguido, bajalo un poco (ej. 0.78). Si notas que otra persona
 * logra pasar, subilo (ej. 0.88).
 */
object SpeakerVerifier {

    const val THRESHOLD = 0.83f
    private const val MIN_ENROLLMENT_SAMPLES = 3

    /**
     * Enrola al dueno a partir de varias grabaciones (idealmente 3-5,
     * diciendo frases distintas, en un ambiente sin mucho ruido). Se
     * promedian las huellas de cada grabacion para que el resultado
     * sea mas estable que con una sola muestra.
     *
     * Devuelve true si se guardo correctamente.
     */
    fun enroll(context: Context, samples: List<ShortArray>): Boolean {
        if (samples.size < MIN_ENROLLMENT_SAMPLES) return false

        val embeddings = samples.mapNotNull { VoiceprintExtractor.extractEmbedding(it) }
        if (embeddings.size < MIN_ENROLLMENT_SAMPLES) return false

        val size = VoiceprintExtractor.EMBEDDING_SIZE
        val avg = FloatArray(size)
        for (emb in embeddings) for (i in 0 until size) avg[i] += emb[i]
        for (i in 0 until size) avg[i] = avg[i] / embeddings.size

        val normalized = VoiceprintExtractor.l2Normalize(avg)
        SecurePrefs.saveVoiceprint(context, normalized)
        return true
    }

    fun isEnrolled(context: Context): Boolean = SecurePrefs.getVoiceprint(context) != null

    fun clearEnrollment(context: Context) = SecurePrefs.clearVoiceprint(context)

    /**
     * Compara un audio recien capturado contra la huella guardada.
     * Si todavia no hay huella enrolada, devuelve isOwner = true por
     * defecto (para no bloquear a alguien que recien esta instalando
     * DARKI y no enrolo su voz aun); una vez que enrolas, se empieza
     * a verificar de verdad.
     */
    fun verify(context: Context, sample: ShortArray): VerificationResult {
        val enrolled = SecurePrefs.getVoiceprint(context)
            ?: return VerificationResult(similarity = 1f, isOwner = true)

        val embedding = VoiceprintExtractor.extractEmbedding(sample)
            ?: return VerificationResult(similarity = 0f, isOwner = false)

        val similarity = VoiceprintExtractor.cosineSimilarity(enrolled, embedding)
        return VerificationResult(similarity = similarity, isOwner = similarity >= THRESHOLD)
    }
}
