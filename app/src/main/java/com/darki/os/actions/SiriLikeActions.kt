package com.darki.os.actions

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.darki.os.service.DarkiAccessibilityService
import java.text.Normalizer
import java.util.Calendar

/**
 * Acciones estilo "OK Google / Siri" que funcionan SIN ninguna llave
 * de API: todo se resuelve con Intents e APIs nativas de Android. La
 * comprension de lenguaje es por patrones de texto (regex simples),
 * no por un modelo de IA: entiende bien frases concretas y comunes,
 * pero no frases ambiguas o raras (para eso hace falta el modo
 * conversacion con Claude, que si necesita API key).
 */

// ---------------------------------------------------------------------
// ALARMAS Y TEMPORIZADORES
// ---------------------------------------------------------------------

/** "Darki, pon una alarma a las 7" / "...a las 7:30" / "...a las 7 de la tarde" */
class PonerAlarmaAction : DarkiAction {
    override fun matches(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("alarma") && !c.contains("temporizador")
    }

    override suspend fun execute(context: Context, command: String): String {
        val hora = parseHoraDelDia(command)
            ?: return "Decime a que hora, por ejemplo \"pon una alarma a las 7 de la manana\"."

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hora.first)
            putExtra(AlarmClock.EXTRA_MINUTES, hora.second)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            "Listo, alarma puesta a las %02d:%02d.".format(hora.first, hora.second)
        } else {
            "No encontre una app de reloj/alarma en el telefono."
        }
    }
}

/** "Darki, pon un temporizador de 10 minutos" / "...de 30 segundos" */
class PonerTemporizadorAction : DarkiAction {
    override fun matches(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("temporizador") || c.contains("cronometro")
    }

    override suspend fun execute(context: Context, command: String): String {
        val segundos = parseDuracionEnSegundos(command)
            ?: return "Decime cuanto tiempo, por ejemplo \"pon un temporizador de 10 minutos\"."

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, segundos)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            "Temporizador andando."
        } else {
            "No encontre una app de reloj en el telefono."
        }
    }
}

// ---------------------------------------------------------------------
// LLAMADAS Y MENSAJES
// ---------------------------------------------------------------------

/** "Darki, llama a Juan" / "Darki, marca a mama" */
class LlamarContactoAction : DarkiAction {
    private val prefixes = listOf("llama a", "llamar a", "marca a", "marcar a")

    override fun matches(command: String): Boolean {
        val c = command.lowercase().trim()
        return prefixes.any { c.startsWith(it) }
    }

    override suspend fun execute(context: Context, command: String): String {
        val c = command.lowercase().trim()
        val prefix = prefixes.filter { c.startsWith(it) }.maxByOrNull { it.length }
            ?: return "No entendi a quien queres llamar."
        val nombre = c.removePrefix(prefix).trim()
        if (nombre.isBlank()) return "No entendi a quien queres llamar."

        val hasContactsPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPerm) {
            return "Necesito permiso para leer tus contactos, dame ese permiso desde la app."
        }

        val numero = buscarNumeroDeContacto(context, nombre)
            ?: return "No encontre ningun contacto parecido a \"$nombre\"."

        val hasCallPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val intent = if (hasCallPerm) {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$numero"))
        } else {
            // Sin permiso de llamar directo, al menos dejamos el numero
            // marcado y listo para que toques "llamar" vos mismo.
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$numero"))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        return if (hasCallPerm) "Llamando a $nombre." else "Te dejo marcado el numero de $nombre, tocá llamar."
    }
}

/**
 * "Darki, manda un mensaje a Juan diciendo ya llego" / "...que diga..."
 * Si tenes el permiso de SMS concedido lo manda directo; si no, lo deja
 * prellenado en tu app de mensajes para que solo toques enviar.
 */
class EnviarMensajeAction : DarkiAction {
    private val patron = Regex(
        "(manda|mandale|envia|enviale|escribele)\\s+(un\\s+)?mensaje\\s+a\\s+(.+?)\\s+(diciendo|que diga|con el texto)\\s+(.+)"
    )

    override fun matches(command: String): Boolean = patron.containsMatchIn(command.lowercase())

    override suspend fun execute(context: Context, command: String): String {
        val match = patron.find(command.lowercase())
            ?: return "Decime algo como \"mandale un mensaje a Juan diciendo ya llego\"."

        val nombre = match.groupValues[3].trim()
        val texto = match.groupValues[5].trim()
        if (nombre.isBlank() || texto.isBlank()) {
            return "No entendi bien a quien o que mensaje mandar."
        }

        val hasContactsPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasContactsPerm) return "Necesito permiso de contactos para buscar a $nombre."

        val numero = buscarNumeroDeContacto(context, nombre)
            ?: return "No encontre ningun contacto parecido a \"$nombre\"."

        val hasSmsPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        return if (hasSmsPerm) {
            try {
                val smsManager = context.getSystemService(SmsManager::class.java)
                    ?: SmsManager.getDefault()
                smsManager.sendTextMessage(numero, null, texto, null, null)
                "Listo, le mande el mensaje a $nombre."
            } catch (e: Exception) {
                "No pude mandar el mensaje: ${e.message}"
            }
        } else {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$numero")).apply {
                putExtra("sms_body", texto)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Te dejo el mensaje para $nombre listo, solo falta que toques enviar."
        }
    }
}

// ---------------------------------------------------------------------
// BUSQUEDA WEB
// ---------------------------------------------------------------------

/** "Darki, busca receta de arepas en internet" / "...en google" */
class BuscarEnInternetAction : DarkiAction {
    private val patron = Regex("busca(r)?\\s+(.+?)\\s+en\\s+(internet|google|la web)")

    override fun matches(command: String): Boolean = patron.containsMatchIn(command.lowercase())

    override suspend fun execute(context: Context, command: String): String {
        val match = patron.find(command.lowercase())
            ?: return "Decime que queres buscar, por ejemplo \"busca receta de arepas en internet\"."
        val consulta = match.groupValues[2].trim()
        if (consulta.isBlank()) return "No entendi que queres que busque."

        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, consulta)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            "Ahi te busco \"$consulta\"."
        } else {
            val fallback = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(consulta))
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
            "Ahi te busco \"$consulta\"."
        }
    }
}

// ---------------------------------------------------------------------
// VOLUMEN Y MODO DE SONIDO
// ---------------------------------------------------------------------

/** "Darki, sube el volumen" / "baja el volumen" / "silencia el telefono" */
class VolumenAction : DarkiAction {
    override fun matches(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("volumen") || ((c.contains("silencia") || c.contains("mutea")) && c.contains("telefono"))
    }

    override suspend fun execute(context: Context, command: String): String {
        val c = command.lowercase()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val direction = when {
            c.contains("sube") || c.contains("subir") || c.contains("aumenta") -> AudioManager.ADJUST_RAISE
            c.contains("baja") || c.contains("bajar") || c.contains("disminuye") -> AudioManager.ADJUST_LOWER
            c.contains("silencia") || c.contains("mutea") || c.contains("mute") -> AudioManager.ADJUST_MUTE
            else -> return "Decime si queres subir, bajar o silenciar el volumen."
        }

        return try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            when (direction) {
                AudioManager.ADJUST_RAISE -> "Subi el volumen."
                AudioManager.ADJUST_LOWER -> "Baje el volumen."
                else -> "Volumen silenciado."
            }
        } catch (e: Exception) {
            "No pude mover el volumen: ${e.message}"
        }
    }
}

/** "Darki, pon modo silencio" / "pon modo vibrar" / "pon modo normal" */
class ModoSonidoAction : DarkiAction {
    override fun matches(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("modo silencio") || c.contains("modo vibrar") || c.contains("modo normal")
    }

    override suspend fun execute(context: Context, command: String): String {
        val c = command.lowercase()
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        val hasDndAccess = notificationManager.isNotificationPolicyAccessGranted
        if (!hasDndAccess) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Necesito permiso de \"No molestar\" para cambiar el modo de sonido, te abro esa pantalla."
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when {
            c.contains("modo silencio") -> {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                "Modo silencio activado."
            }
            c.contains("modo vibrar") -> {
                audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                "Modo vibrar activado."
            }
            else -> {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                "Modo normal activado."
            }
        }
    }
}

// ---------------------------------------------------------------------
// BRILLO DE PANTALLA
// ---------------------------------------------------------------------

/** "Darki, sube el brillo" / "baja el brillo" / "pon el brillo al 80%" */
class BrilloAction : DarkiAction {
    override fun matches(command: String) = command.lowercase().contains("brillo")

    override suspend fun execute(context: Context, command: String): String {
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Necesito permiso para cambiar ajustes del sistema, te abro esa pantalla."
        }

        val c = command.lowercase()
        val resolver = context.contentResolver
        val actual = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)

        val porcentaje = Regex("(\\d{1,3})\\s*%").find(c)?.groupValues?.get(1)?.toIntOrNull()

        val nuevo = when {
            porcentaje != null -> (porcentaje.coerceIn(1, 100) * 255 / 100)
            c.contains("sube") || c.contains("subir") || c.contains("aumenta") -> (actual + 60).coerceAtMost(255)
            c.contains("baja") || c.contains("bajar") || c.contains("disminuye") -> (actual - 60).coerceAtLeast(10)
            else -> return "Decime si queres subir, bajar, o un porcentaje de brillo."
        }

        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, nuevo)
        return "Brillo ajustado."
    }
}

// ---------------------------------------------------------------------
// CAPTURA DE PANTALLA
// ---------------------------------------------------------------------

/** "Darki, toma una captura de pantalla" */
class CapturaPantallaAction : DarkiAction {
    override fun matches(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("captura de pantalla") || c.contains("captura la pantalla") ||
            c.contains("toma una captura")
    }

    override suspend fun execute(context: Context, command: String): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            return "Tu version de Android no permite esto automaticamente, parce."
        }
        val service = DarkiAccessibilityService.get()
            ?: return "Necesito el servicio de accesibilidad activado para esto."
        val ok = service.takeScreenshot()
        return if (ok) "Listo, ahi quedo la captura." else "No pude tomar la captura."
    }
}

// ---------------------------------------------------------------------
// CALENDARIO
// ---------------------------------------------------------------------

/**
 * "Darki, agenda una reunion mañana a las 3 de la tarde" — abre el
 * calendario con el evento prellenado (no lo guarda solo, para que
 * confirmes vos: no pedimos permiso de escritura de calendario).
 */
class AgendarEventoAction : DarkiAction {
    private val prefixes = listOf("agenda", "programa una reunion", "crea un evento", "agendame")

    override fun matches(command: String): Boolean {
        val c = command.lowercase()
        return prefixes.any { c.contains(it) }
    }

    override suspend fun execute(context: Context, command: String): String {
        val hora = parseHoraDelDia(command)
        val esManana = command.lowercase().contains("mañana") || command.lowercase().contains("manana")

        val cal = Calendar.getInstance()
        if (esManana) cal.add(Calendar.DAY_OF_MONTH, 1)
        if (hora != null) {
            cal.set(Calendar.HOUR_OF_DAY, hora.first)
            cal.set(Calendar.MINUTE, hora.second)
        }

        val titulo = command
            .replace(Regex("(?i)darki"), "")
            .replace(Regex("(?i)agenda(me)?|programa una reunion|crea un evento"), "")
            .trim()
            .ifBlank { "Evento con DARKI" }

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, titulo)
            if (hora != null) {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cal.timeInMillis + 60 * 60 * 1000)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            "Te dejo el evento listo en el calendario, solo falta que confirmes."
        } else {
            "No encontre una app de calendario en el telefono."
        }
    }
}

// ---------------------------------------------------------------------
// HELPERS COMPARTIDOS
// ---------------------------------------------------------------------

/** Busca el numero de telefono de un contacto por nombre aproximado. */
private fun buscarNumeroDeContacto(context: Context, nombreBuscado: String): String? {
    val query = normalizarTexto(nombreBuscado)
    val cursor = context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        ),
        null, null, null
    ) ?: return null

    var mejorNumero: String? = null
    cursor.use {
        while (it.moveToNext()) {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIdx < 0 || numberIdx < 0) continue
            val nombre = normalizarTexto(it.getString(nameIdx) ?: continue)
            if (nombre.contains(query) || query.contains(nombre)) {
                mejorNumero = it.getString(numberIdx)
                return@use
            }
        }
    }
    return mejorNumero
}

private fun normalizarTexto(texto: String): String {
    val sinAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
    return sinAcentos.lowercase().trim()
}

/** Extrae "hora, minuto" de frases como "a las 7", "a las 7:30", "a las 7 de la tarde". */
private fun parseHoraDelDia(texto: String): Pair<Int, Int>? {
    val c = texto.lowercase()
    val match = Regex("a las\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(de la (manana|tarde|noche))?").find(c)
        ?: return null

    var hora = match.groupValues[1].toIntOrNull() ?: return null
    val minutos = match.groupValues[2].toIntOrNull() ?: 0
    val periodo = match.groupValues[4]

    if (periodo == "tarde" && hora in 1..11) hora += 12
    if (periodo == "noche" && hora in 1..11) hora += 12
    if (hora == 12 && periodo == "manana") hora = 0

    if (hora !in 0..23 || minutos !in 0..59) return null
    return hora to minutos
}

/** Extrae segundos totales de frases como "10 minutos", "30 segundos", "1 minuto y 30 segundos". */
private fun parseDuracionEnSegundos(texto: String): Int? {
    val c = texto.lowercase()
    var totalSegundos = 0
    var encontroAlgo = false

    Regex("(\\d+)\\s*(hora|horas)").find(c)?.let {
        totalSegundos += (it.groupValues[1].toIntOrNull() ?: 0) * 3600
        encontroAlgo = true
    }
    Regex("(\\d+)\\s*(minuto|minutos)").find(c)?.let {
        totalSegundos += (it.groupValues[1].toIntOrNull() ?: 0) * 60
        encontroAlgo = true
    }
    Regex("(\\d+)\\s*(segundo|segundos)").find(c)?.let {
        totalSegundos += (it.groupValues[1].toIntOrNull() ?: 0)
        encontroAlgo = true
    }

    return if (encontroAlgo && totalSegundos > 0) totalSegundos else null
}
