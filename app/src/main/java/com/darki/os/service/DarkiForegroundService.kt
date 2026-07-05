package com.darki.os.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.darki.os.MainActivity
import com.darki.os.R
import com.darki.os.actions.DarkiActionRegistry
import com.darki.os.data.SecurePrefs
import com.darki.os.data.memory.MemoryManager
import com.darki.os.network.ClaudeApiClient
import com.darki.os.overlay.OverlayManager
import com.darki.os.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano que mantiene a DARKI escuchando "Darki" de
 * forma continua, incluso con la app en segundo plano o la pantalla
 * apagada (ver acquireWakeLock).
 *
 * Flujo de cada comando:
 *   1. WakeWordEngine detecta "darki" -> "Dime, Jostin." -> graba el
 *      comando Y VERIFICA DE QUIEN ES LA VOZ al mismo tiempo.
 *   2. Si la orden es de apagado o toca una accion marcada como
 *      "sensible" (todas por defecto, ver DarkiAction.sensitive) y
 *      quien hablo NO es el dueno segun SpeakerVerifier -> se rechaza,
 *      no se ejecuta nada.
 *   3. Accion local registrada (DarkiActionRegistry: apps, linterna,
 *      wifi, memoria, etc) -> se ejecuta directo, sin gastar la API.
 *   4. Si no hay accion local Y el Accessibility Service esta activo
 *      -> modo agente (SIEMPRE requiere dueno verificado, porque puede
 *      tocar y escribir cualquier cosa en el telefono).
 *   5. Si no hay accion local NI Accessibility activo -> conversacion
 *      normal con Claude (no se considera sensible: es solo charla).
 */
class DarkiForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var wakeWordEngine: WakeWordEngine
    private lateinit var tts: TextToSpeechManager
    private lateinit var overlay: OverlayManager
    private lateinit var memoryManager: MemoryManager
    private var claude: ClaudeApiClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(buildNotification("Esperando 'Darki'..."))

        tts = TextToSpeechManager(this)
        overlay = OverlayManager(this)
        memoryManager = MemoryManager(this)
        claude = SecurePrefs.getApiKey(this)?.let { ClaudeApiClient(it) }
        acquireWakeLock()

        wakeWordEngine = WakeWordEngine(
            context = this,
            onWakeWordDetected = ::handleWakeWordDetected,
            onCommandCaptured = ::handleCommand,
            onError = { msg -> updateNotification("Error: $msg") }
        )
        wakeWordEngine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun handleWakeWordDetected() {
        overlay.showSpeaking()
        updateNotification("Hablando...")
        scope.launch {
            tts.speakAndWait("Dime, Jostin.")
            overlay.showListening()
            updateNotification("Escuchando tu orden...")
        }
    }

    private fun handleCommand(commandText: String, esDueno: Boolean, similitud: Float) {
        val esOrdenDeApagado = commandText.contains("darki", ignoreCase = true) &&
            commandText.contains("apaga", ignoreCase = true)

        if (esOrdenDeApagado) {
            if (!esDueno) {
                rechazarPorVoz()
                return
            }
            overlay.showSpeaking()
            scope.launch {
                tts.speakAndWait("Hasta luego, Jostin.")
                overlay.hide()
                stopSelf()
            }
            return
        }

        scope.launch {
            // 1. Accion local registrada (instantanea, sin gastar la API).
            val localAction = DarkiActionRegistry.findMatch(commandText)
            if (localAction != null) {
                if (localAction.sensitive && !esDueno) {
                    rechazarPorVoz()
                    return@launch
                }
                val respuesta = localAction.execute(this@DarkiForegroundService, commandText)
                overlay.showSpeaking()
                updateNotification("Hablando...")
                tts.speakAndWait(respuesta)
                overlay.hide()
                updateNotification("Esperando 'Darki'...")
                return@launch
            }

            // 2. Sin accion local: si Accessibility esta activo, modo agente.
            //    Esto SIEMPRE requiere dueno verificado: puede tocar y
            //    escribir cualquier cosa en el telefono.
            if (DarkiAccessibilityService.isRunning()) {
                if (!esDueno) {
                    rechazarPorVoz()
                    return@launch
                }
                runAgentLoop(commandText)
                return@launch
            }

            // 3. Conversacion normal con Claude (con contexto de memoria).
            //    No se considera sensible: es solo charla, no controla nada.
            val client = claude
            if (client == null) {
                overlay.showSpeaking()
                tts.speakAndWait(
                    "No reconoci esa orden y todavia no configuraste tu llave de " +
                        "API para que te entienda mejor, parce. Abreme y agregala."
                )
                overlay.hide()
                updateNotification("Esperando 'Darki'...")
                return@launch
            }

            overlay.showThinking()
            updateNotification("Pensando...")
            val respuesta = try {
                val memorias = memoryManager.recallRelevant(commandText)
                client.sendMessage(commandText, memorias)
            } catch (e: Exception) {
                "Se me cayo la conexion, parce. Intenta de nuevo."
            }
            overlay.showSpeaking()
            updateNotification("Hablando...")
            tts.speakAndWait(respuesta)
            overlay.hide()
            updateNotification("Esperando 'Darki'...")
        }
    }

    /** Respuesta corta cuando la voz no coincide con la del dueno. */
    private fun rechazarPorVoz() {
        overlay.showSpeaking()
        updateNotification("Voz no reconocida")
        scope.launch {
            tts.speakAndWait("No te reconozco la voz para eso, parce.")
            overlay.hide()
            updateNotification("Esperando 'Darki'...")
        }
    }

    /**
     * Modo agente: en cada paso le manda a Claude lo que hay en pantalla
     * (via DarkiAccessibilityService) mas la orden original, ejecuta la
     * accion que responde, y repite hasta que Claude diga "done"/"speak"
     * o se llegue al limite de pasos.
     */
    private suspend fun runAgentLoop(command: String) {
        val client = claude
        if (client == null) {
            overlay.showSpeaking()
            tts.speakAndWait("Necesito tu API key para controlar el telefono con esta orden, parce.")
            overlay.hide()
            updateNotification("Esperando 'Darki'...")
            return
        }

        val service = DarkiAccessibilityService.get()
        if (service == null) {
            overlay.showSpeaking()
            tts.speakAndWait("Se desactivo el servicio de accesibilidad, actívalo en Ajustes.")
            overlay.hide()
            updateNotification("Esperando 'Darki'...")
            return
        }

        val steps = mutableListOf<String>()
        overlay.showThinking()

        repeat(MAX_AGENT_STEPS) {
            val screenDump = service.dumpScreen()
            updateNotification("Ejecutando: $command")

            val plan = client.planAction(command, screenDump, steps)
            when (plan.optString("action", "speak")) {
                "click_text" -> {
                    val target = plan.optString("target")
                    val ok = service.clickByText(target)
                    steps.add("toque \"$target\" -> ${if (ok) "hecho" else "no lo encontre"}")
                }
                "type_text" -> {
                    val text = plan.optString("text")
                    val ok = service.typeIntoFocused(text)
                    steps.add("escribi \"$text\" -> ${if (ok) "hecho" else "no habia campo activo"}")
                }
                "scroll_down" -> {
                    service.scroll(forward = true)
                    steps.add("hice scroll hacia abajo")
                }
                "scroll_up" -> {
                    service.scroll(forward = false)
                    steps.add("hice scroll hacia arriba")
                }
                "open_app" -> {
                    val app = plan.optString("app")
                    val ok = service.openApp(app)
                    steps.add("abri \"$app\" -> ${if (ok) "hecho" else "no la encontre instalada"}")
                    delay(900)
                }
                "back" -> { service.back(); steps.add("volvi atras") }
                "home" -> { service.home(); steps.add("fui al inicio") }
                "recents" -> { service.recents(); steps.add("abri apps recientes") }
                "notifications" -> { service.notifications(); steps.add("abri notificaciones") }
                "wait" -> { steps.add("espere un momento") }
                "speak" -> {
                    overlay.showSpeaking()
                    tts.speakAndWait(plan.optString("text", "Listo."))
                    overlay.hide()
                    updateNotification("Esperando 'Darki'...")
                    return
                }
                "done" -> {
                    overlay.showSpeaking()
                    tts.speakAndWait(plan.optString("text").ifBlank { "Listo, ya quedo." })
                    overlay.hide()
                    updateNotification("Esperando 'Darki'...")
                    return
                }
                else -> {
                    overlay.hide()
                    updateNotification("Esperando 'Darki'...")
                    return
                }
            }
            delay(400)
        }

        overlay.showSpeaking()
        tts.speakAndWait("No pude terminar esa orden completa, parce. Intenta ser mas especifico.")
        overlay.hide()
        updateNotification("Esperando 'Darki'...")
    }

    /**
     * Mantiene la CPU despierta lo suficiente para que Vosk siga
     * escuchando con la pantalla apagada. El wakelock NO evita que
     * Android mate el proceso si el usuario no exime a DARKI de la
     * optimizacion de bateria desde Ajustes (paso manual, ver MainActivity).
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DarkiOS::WakeWordListening"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "darki_service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "DARKI en segundo plano",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("DARKI")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_darki_logo)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        wakeWordEngine.stop()
        tts.shutdown()
        overlay.hide()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val MAX_AGENT_STEPS = 6
    }
}
