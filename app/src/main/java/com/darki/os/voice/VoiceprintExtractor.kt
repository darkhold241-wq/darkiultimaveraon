package com.darki.os.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Extrae una "huella de voz" (voiceprint) de un audio crudo PCM16 mono,
 * sin depender de ningun modelo externo (a diferencia de Vosk, esto no
 * necesita descargar nada: es matematica pura sobre la senal).
 *
 * Tecnica: MFCC (Mel-Frequency Cepstral Coefficients) clasico, el mismo
 * tipo de features que se usaban para reconocimiento de voz antes de
 * las redes neuronales. Se calculan cuadro por cuadro (frames de 25ms)
 * y se resumen en un solo vector fijo con la media y el desvio de cada
 * coeficiente a lo largo del tiempo.
 *
 * IMPORTANTE - limitacion honesta: esto identifica el TIMBRE general de
 * una voz (tono, resonancia), no es tan preciso como un modelo de deep
 * learning entrenado especificamente para verificacion de hablante
 * (como los que usa Google Assistant para "Voice Match", que son
 * propietarios y no estan disponibles para apps de terceros). Para un
 * asistente personal alcanza para distinguir "sos vos" de "es otra
 * persona" en la mayoria de los casos, pero no es a prueba de una
 * grabacion tuya reproducida por otro parlante.
 */
object VoiceprintExtractor {

    private const val SAMPLE_RATE = 16000
    private const val FRAME_SIZE = 400   // 25ms a 16kHz
    private const val FRAME_HOP = 160    // 10ms a 16kHz
    private const val FFT_SIZE = 512     // proxima potencia de 2 >= FRAME_SIZE
    private const val MEL_FILTERS = 26
    private const val MFCC_COUNT = 13

    /** Dimension final del vector devuelto por extractEmbedding(). */
    const val EMBEDDING_SIZE = MFCC_COUNT * 2 // media + desvio de cada coeficiente

    /**
     * Convierte un audio crudo (PCM16, 16kHz, mono) en un vector fijo de
     * [EMBEDDING_SIZE] numeros que representa la huella de esa voz.
     * Devuelve null si el audio es demasiado corto o es puro silencio.
     */
    fun extractEmbedding(pcm: ShortArray): FloatArray? {
        if (pcm.size < FRAME_SIZE) return null

        val samples = DoubleArray(pcm.size) { pcm[it] / 32768.0 }
        val hamming = DoubleArray(FRAME_SIZE) { i ->
            0.54 - 0.46 * cos(2.0 * PI * i / (FRAME_SIZE - 1))
        }
        val melFilterBank = buildMelFilterBank()

        // Umbral de energia para descartar cuadros de silencio/ruido de
        // fondo: si no filtramos esto, un audio con mucho silencio al
        // principio o al final diluye la huella con "nada".
        val frameEnergies = mutableListOf<Double>()
        val allMfccFrames = mutableListOf<DoubleArray>()

        var offset = 0
        while (offset + FRAME_SIZE <= samples.size) {
            val frame = DoubleArray(FRAME_SIZE) { samples[offset + it] * hamming[it] }
            val energy = frame.sumOf { it * it } / FRAME_SIZE
            frameEnergies.add(energy)

            val padded = DoubleArray(FFT_SIZE)
            System.arraycopy(frame, 0, padded, 0, FRAME_SIZE)
            val (real, imag) = fft(padded)

            val powerSpectrum = DoubleArray(FFT_SIZE / 2 + 1) { i ->
                (real[i] * real[i] + imag[i] * imag[i]) / FFT_SIZE
            }

            val melEnergies = DoubleArray(MEL_FILTERS) { m ->
                var sum = 0.0
                val filter = melFilterBank[m]
                for (bin in filter.indices) sum += filter[bin] * powerSpectrum[bin]
                ln(max(sum, 1e-10))
            }

            allMfccFrames.add(dct(melEnergies, MFCC_COUNT))
            offset += FRAME_HOP
        }

        if (allMfccFrames.isEmpty()) return null

        // Descartamos el 30% de cuadros con menos energia (silencio),
        // salvo que eso deje muy pocos cuadros utiles.
        val energyThreshold = frameEnergies.sorted()
            .getOrElse((frameEnergies.size * 0.3).toInt()) { 0.0 }
        val voicedFrames = allMfccFrames.filterIndexed { idx, _ ->
            frameEnergies[idx] >= energyThreshold
        }.ifEmpty { allMfccFrames }

        if (voicedFrames.size < 3) return null

        val mean = DoubleArray(MFCC_COUNT)
        for (frame in voicedFrames) for (i in 0 until MFCC_COUNT) mean[i] += frame[i]
        for (i in 0 until MFCC_COUNT) mean[i] /= voicedFrames.size

        val std = DoubleArray(MFCC_COUNT)
        for (frame in voicedFrames) for (i in 0 until MFCC_COUNT) {
            val d = frame[i] - mean[i]
            std[i] += d * d
        }
        for (i in 0 until MFCC_COUNT) std[i] = sqrt(std[i] / voicedFrames.size)

        val embedding = FloatArray(EMBEDDING_SIZE)
        for (i in 0 until MFCC_COUNT) {
            embedding[i] = mean[i].toFloat()
            embedding[MFCC_COUNT + i] = std[i].toFloat()
        }
        return l2Normalize(embedding)
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        var normSq = 0.0
        for (x in v) normSq += x.toDouble() * x.toDouble()
        val norm = sqrt(normSq).toFloat()
        if (norm < 1e-8f) return v
        return FloatArray(v.size) { v[it] / norm }
    }

    /** Similitud coseno entre dos huellas ya normalizadas (rango -1..1). */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    // --- DCT-II, usado para pasar de energias mel (dominio frecuencia) a MFCC ---
    private fun dct(input: DoubleArray, outCount: Int): DoubleArray {
        val n = input.size
        return DoubleArray(outCount) { k ->
            var sum = 0.0
            for (i in 0 until n) {
                sum += input[i] * cos(PI / n * (i + 0.5) * k)
            }
            sum
        }
    }

    // --- Banco de filtros mel triangulares, mapeados a bins de la FFT ---
    private fun buildMelFilterBank(): Array<DoubleArray> {
        val numBins = FFT_SIZE / 2 + 1
        val maxHz = SAMPLE_RATE / 2.0
        val maxMel = hzToMel(maxHz)
        val melPoints = DoubleArray(MEL_FILTERS + 2) { i -> maxMel * i / (MEL_FILTERS + 1) }
        val hzPoints = melPoints.map { melToHz(it) }
        val binPoints = hzPoints.map { (it * FFT_SIZE / SAMPLE_RATE).toInt().coerceIn(0, numBins - 1) }

        return Array(MEL_FILTERS) { m ->
            val filter = DoubleArray(numBins)
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]
            for (bin in left until center) {
                if (center > left) filter[bin] = (bin - left).toDouble() / (center - left)
            }
            for (bin in center until right) {
                if (right > center) filter[bin] = (right - bin).toDouble() / (right - center)
            }
            filter
        }
    }

    private fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double) = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    /**
     * FFT radix-2 iterativa (Cooley-Tukey) sobre un arreglo real de
     * tamano potencia de 2. Devuelve (parte real, parte imaginaria).
     * Implementacion propia porque no queremos sumar una dependencia
     * nueva solo para esto.
     */
    private fun fft(input: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = input.size
        val real = input.copyOf()
        val imag = DoubleArray(n)

        // Bit-reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curWr = 1.0
                var curWi = 0.0
                for (k in 0 until len / 2) {
                    val ur = real[i + k]
                    val ui = imag[i + k]
                    val vr = real[i + k + len / 2] * curWr - imag[i + k + len / 2] * curWi
                    val vi = real[i + k + len / 2] * curWi + imag[i + k + len / 2] * curWr
                    real[i + k] = ur + vr
                    imag[i + k] = ui + vi
                    real[i + k + len / 2] = ur - vr
                    imag[i + k + len / 2] = ui - vi
                    val nextWr = curWr * wr - curWi * wi
                    val nextWi = curWr * wi + curWi * wr
                    curWr = nextWr
                    curWi = nextWi
                }
                i += len
            }
            len = len shl 1
        }
        return real to imag
    }
}
