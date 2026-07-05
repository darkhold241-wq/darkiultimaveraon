package com.darki.os.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import com.darki.os.R

/**
 * Controla la ventana flotante que muestra el logo de DARKI sobre
 * cualquier app abierta. Tiene tres animaciones distintas segun el
 * estado, para que se sienta vivo en vez de un simple pulso repetido:
 *
 *  - LISTENING: pulso suave, esperando que termines de hablar el comando.
 *  - THINKING: pulso rapido y chico, como "procesando".
 *  - SPEAKING: crece bastante mas (estilo el orbe de Siri cuando habla)
 *    y se activa exactamente mientras dura el audio real del TTS
 *    (ver TextToSpeechManager.speakAndWait).
 *
 * Requiere el permiso "Mostrar sobre otras apps" (SYSTEM_ALERT_WINDOW).
 *
 * Nota honesta: el crecimiento esta sincronizado con el INICIO y FIN
 * del audio (por eso se ve natural), pero no analiza el volumen/
 * amplitud de cada silaba en tiempo real como el orbe de Siri de
 * verdad. Eso requeriria enganchar un AudioRecord/Visualizer al
 * stream de audio del TTS, que es un feature aparte si lo queres mas
 * adelante.
 */
class OverlayManager(private val context: Context) {

    private enum class OrbState { LISTENING, THINKING, SPEAKING }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var logoView: ImageView? = null
    private var animator: ValueAnimator? = null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun showListening() = setState(OrbState.LISTENING)
    fun showThinking() = setState(OrbState.THINKING)
    fun showSpeaking() = setState(OrbState.SPEAKING)

    private fun setState(state: OrbState) {
        if (!canDrawOverlays()) return
        ensureViewAdded()
        animate(state)
    }

    private fun ensureViewAdded() {
        if (logoView != null) return

        val view = ImageView(context).apply {
            setImageResource(R.drawable.ic_darki_logo)
            alpha = 0.95f
        }

        // Sombra morada con elevacion real (API 28+), para reforzar el
        // efecto "epico" del logo sin depender solo del gradiente.
        // Ojo: sin un outlineProvider explicito, Android no dibuja
        // sombra para una vista sin background propio (el contorno por
        // defecto sale vacio), por eso se fuerza un contorno ovalado
        // del mismo tamano que la ventana flotante (180x180).
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, 180, 180)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.outlineSpotShadowColor = Color.parseColor("#7B2FFF")
            view.outlineAmbientShadowColor = Color.parseColor("#7B2FFF")
        }
        view.elevation = 24f

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            180, 180,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        windowManager.addView(view, params)
        logoView = view
    }

    private fun animate(state: OrbState) {
        animator?.cancel()
        val view = logoView ?: return

        val (minScale, maxScale, durationMs) = when (state) {
            OrbState.LISTENING -> Triple(0.9f, 1.05f, 800L)
            OrbState.THINKING -> Triple(0.95f, 1.08f, 350L)
            OrbState.SPEAKING -> Triple(1.0f, 1.4f, 320L)
        }

        animator = ValueAnimator.ofFloat(minScale, maxScale).apply {
            duration = durationMs
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val scale = it.animatedValue as Float
                view.scaleX = scale
                view.scaleY = scale
            }
            start()
        }
    }

    fun hide() {
        animator?.cancel()
        animator = null
        logoView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        logoView = null
    }
}
