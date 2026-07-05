package com.darki.os.actions

import android.content.Context

/**
 * Contrato para cualquier accion que DARKI pueda ejecutar directamente
 * en el telefono (sin pasarle el texto a Claude). Estas son las
 * acciones "rapidas": no gastan una llamada a la API y responden al
 * toque.
 *
 * Para agregar una accion nueva no hay que tocar el servicio: alcanza
 * con implementar esta interfaz y registrarla en DarkiActionRegistry.
 * Ejemplo minimo:
 *
 *   class MiAccionNueva : DarkiAction {
 *       override fun matches(command: String) = command.contains("mi frase")
 *       override fun execute(context: Context, command: String): String {
 *           // ... hacer algo con el context ...
 *           return "Listo, ya quedo."
 *       }
 *   }
 *   DarkiActionRegistry.register(MiAccionNueva())
 */
interface DarkiAction {

    /**
     * true si esta accion requiere que la voz verificada sea la del
     * dueno (ver SpeakerVerifier). Por defecto TODAS las acciones son
     * sensibles (opcion segura por defecto): si una persona que no es
     * el dueno dice "darki" y da una orden, no se ejecuta nada.
     *
     * Si en el futuro queres permitir acciones inofensivas para
     * cualquiera (ej. "que hora es"), podes sobreescribir esto a
     * false en esa accion puntual.
     */
    val sensitive: Boolean get() = true

    /** true si el texto del comando deberia disparar esta accion. */
    fun matches(command: String): Boolean

    /**
     * Ejecuta la accion. Recibe el comando completo por si la accion
     * necesita distinguir variantes (ej. "enciende" vs "apaga").
     * Devuelve la frase que DARKI dice en voz alta despues.
     *
     * Es suspend porque algunas acciones (memoria) necesitan tocar la
     * base de datos Room sin bloquear el hilo que las llama. Quien la
     * invoque ya esta dentro de una corrutina (DarkiForegroundService),
     * asi que no hace falta runBlocking en ningun lado.
     */
    suspend fun execute(context: Context, command: String): String
}
