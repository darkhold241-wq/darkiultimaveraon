package com.darki.os.actions

/**
 * Lista viva de todas las acciones que DARKI puede ejecutar
 * directamente en el telefono. Para agregar una accion nueva:
 *
 *   1. Crear una clase que implemente DarkiAction (ver BasicActions.kt
 *      para ejemplos).
 *   2. Agregarla a la lista de abajo, o llamar a register() desde
 *      cualquier parte del codigo (ej. una futura pantalla de plugins).
 *
 * El orden importa: se usa la primera accion cuyo matches() de true,
 * asi que las mas especificas deberian ir antes que las genericas.
 */
object DarkiActionRegistry {

    private val actions = mutableListOf<DarkiAction>(
        RecordarAction(),
        OlvidarAction(),
        PonerAlarmaAction(),
        PonerTemporizadorAction(),
        LlamarContactoAction(),
        EnviarMensajeAction(),
        BuscarEnInternetAction(),
        AgendarEventoAction(),
        VolumenAction(),
        ModoSonidoAction(),
        BrilloAction(),
        CapturaPantallaAction(),
        AbrirConfiguracionAction(),
        LinternaAction(),
        WifiSettingsAction(),
        BluetoothSettingsAction(),
        AbrirCamaraAction(),
        AbrirAppAction("spotify", "com.spotify.music", "Dale, abriendo Spotify."),
        AbrirAppAction("youtube", "com.google.android.youtube", "Ya quedo, ahi esta YouTube."),
        AbrirAppAction("whatsapp", "com.whatsapp", "De una, abriendo WhatsApp."),
        AbrirAppAction("telegram", "org.telegram.messenger", "Listo, abriendo Telegram."),
        AbrirAppAction("gmail", "com.google.android.gm", "Ahi esta tu Gmail."),
        AbrirAppAction("chrome", "com.android.chrome", "Abriendo Chrome."),
        // Catch-all: cualquier app o juego instalado que no tenga una
        // entrada curada arriba. Debe ir SIEMPRE al final: el orden de
        // la lista es el orden de prioridad de matches().
        AbrirCualquierAppAction()
    )

    fun register(action: DarkiAction) = actions.add(action)

    fun findMatch(command: String): DarkiAction? = actions.firstOrNull { it.matches(command) }
}
