package com.darki.os.actions

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.Settings
import com.darki.os.data.memory.MemoryManager

/**
 * Abre el menu de ajustes del sistema.
 * Ejemplo pedido: "Darki, abre el menu de configuracion".
 */
class AbrirConfiguracionAction : DarkiAction {
    override fun matches(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("configuracion") || c.contains("ajustes")
    }

    override suspend fun execute(context: Context, command: String): String {
        val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Ahi te abro los ajustes."
    }
}

/**
 * Abre una app instalada por su package name. Si la app no esta
 * instalada, lo dice en vez de crashear.
 */
class AbrirAppAction(
    private val palabraClave: String,
    private val packageName: String,
    private val respuesta: String
) : DarkiAction {
    override fun matches(command: String) = command.lowercase().contains(palabraClave)

    override suspend fun execute(context: Context, command: String): String {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            respuesta
        } else {
            "No encontre esa app instalada, parce."
        }
    }
}

/**
 * Abre la app de camara del telefono via intent implicito, en vez de
 * un package name fijo (el package de la camara varia por fabricante:
 * Samsung, Xiaomi y AOSP usan nombres distintos).
 */
class AbrirCamaraAction : DarkiAction {
    override fun matches(command: String) = command.lowercase().contains("camara")

    override suspend fun execute(context: Context, command: String): String {
        val intent = Intent("android.media.action.STILL_IMAGE_CAMERA")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            "Lista la camara."
        } else {
            "No encontre una app de camara en el telefono."
        }
    }
}

/**
 * Ultimo recurso para "abre X": si ninguna AbrirAppAction curada con
 * package name fijo calzo, busca entre TODAS las apps y juegos
 * instalados por nombre visible (COD Mobile, Free Fire, Minecraft,
 * Brawl Stars, Clash Royale, Roblox, TikTok, lo que sea). Se registra
 * al final de DarkiActionRegistry para no pisar las respuestas
 * curadas de las apps mas usadas.
 */
class AbrirCualquierAppAction : DarkiAction {

    private val openVerbs = listOf(
        "abreme", "abrime", "abre", "abrir", "inicia", "iniciar",
        "ejecuta", "ejecutar", "lanza", "pon", "reproduce", "reproducir"
    )

    override fun matches(command: String): Boolean {
        val c = command.lowercase().trim()
        return openVerbs.any { c.startsWith("$it ") }
    }

    override suspend fun execute(context: Context, command: String): String {
        val c = command.lowercase().trim()
        val verb = openVerbs.filter { c.startsWith("$it ") }.maxByOrNull { it.length }
            ?: return "No entendi que app querias abrir."
        val spokenName = c.removePrefix(verb).trim()
        if (spokenName.isBlank()) return "No entendi que app querias abrir."

        val match = AppCatalog.findBestMatch(context, spokenName)
            ?: return "No encontre ninguna app instalada parecida a \"$spokenName\", parce."

        val launchIntent = context.packageManager.getLaunchIntentForPackage(match.packageName)
            ?: return "Encontre ${match.label} pero no la pude abrir."

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return "Abriendo ${match.label}."
    }
}

/**
 * Prende o apaga la linterna segun si el comando dice "apaga" o no.
 * setTorchMode no pide permiso de camara (a diferencia de abrir la
 * camara para fotos/video); esta pensado justamente para esto.
 */
class LinternaAction : DarkiAction {
    override fun matches(command: String) = command.lowercase().contains("linterna")

    override suspend fun execute(context: Context, command: String): String {
        val encender = !command.lowercase().contains("apaga")

        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camaraConFlash = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }

            if (camaraConFlash == null) {
                "Este telefono no tiene linterna, parce."
            } else {
                cameraManager.setTorchMode(camaraConFlash, encender)
                if (encender) "Linterna encendida." else "Linterna apagada."
            }
        } catch (e: Exception) {
            "No pude mover la linterna: ${e.message}"
        }
    }
}

/**
 * Abre el panel rapido de Wifi (o los ajustes completos en Android
 * viejo, donde el panel rapido no existe todavia).
 */
class WifiSettingsAction : DarkiAction {
    override fun matches(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("wifi") && (c.contains("abre") || c.contains("activa") || c.contains("configuracion") || c.contains("ajustes"))
    }

    override suspend fun execute(context: Context, command: String): String {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Ahi te dejo el wifi, parce."
    }
}

/** Igual que WifiSettingsAction pero para Bluetooth. */
class BluetoothSettingsAction : DarkiAction {
    override fun matches(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("bluetooth") && (c.contains("abre") || c.contains("activa") || c.contains("configuracion") || c.contains("ajustes"))
    }

    override suspend fun execute(context: Context, command: String): String {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Intent(Settings.Panel.ACTION_BLUETOOTH)
        } else {
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Ahi te dejo el bluetooth, parce."
    }
}

/**
 * "Darki recuerda que..." / "Darki aprende que...". Guarda el resto
 * de la frase en Room (memoria persistente, local, sobrevive a
 * reinicios). Se usa MemoryManager(context) directo en vez de
 * inyectarlo por constructor porque DarkiActionRegistry construye la
 * lista de acciones sin Context disponible; Room ya cachea la
 * instancia real de la base de datos por dentro, asi que esto no
 * abre conexiones repetidas.
 */
class RecordarAction : DarkiAction {
    private val prefixes = listOf("recuerda que", "recuerda", "aprende que", "aprende")

    override fun matches(command: String): Boolean {
        val c = command.lowercase().trim()
        return prefixes.any { c.startsWith("$it ") }
    }

    override suspend fun execute(context: Context, command: String): String {
        val c = command.lowercase().trim()
        val prefix = prefixes.filter { c.startsWith("$it ") }.maxByOrNull { it.length } ?: return "No entendi que querias que recordara."
        val contenido = c.removePrefix(prefix).trim()
        if (contenido.isBlank()) return "No entendi que querias que recordara."

        MemoryManager(context).remember(contenido)
        return "Listo, ya lo tengo guardado."
    }
}

/**
 * "Darki olvida que..." / "Darki olvida lo de...". Borra el recuerdo
 * mas parecido a lo que se pide olvidar.
 */
class OlvidarAction : DarkiAction {
    private val prefixes = listOf("olvida que", "olvida lo de", "olvida")

    override fun matches(command: String): Boolean {
        val c = command.lowercase().trim()
        return prefixes.any { c.startsWith("$it ") }
    }

    override suspend fun execute(context: Context, command: String): String {
        val c = command.lowercase().trim()
        val prefix = prefixes.filter { c.startsWith("$it ") }.maxByOrNull { it.length } ?: return "No entendi que querias que olvidara."
        val contenido = c.removePrefix(prefix).trim()
        if (contenido.isBlank()) return "No entendi que querias que olvidara."

        val ok = MemoryManager(context).forget(contenido)
        return if (ok) "Listo, ya lo olvide." else "No tenia guardado nada parecido a eso."
    }
}
