package com.darki.os

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.darki.os.data.SecurePrefs
import com.darki.os.service.CommandAudioRecorder
import com.darki.os.service.DarkiAccessibilityService
import com.darki.os.service.DarkiForegroundService
import com.darki.os.ui.theme.DarkiOSTheme
import com.darki.os.voice.SpeakerVerifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DarkiOSTheme {
                SetupScreen()
            }
        }
    }
}

@Composable
fun SetupScreen() {
    val context = LocalContext.current

    var apiKey by remember { mutableStateOf(SecurePrefs.getApiKey(context) ?: "") }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var accessibilityGranted by remember { mutableStateOf(DarkiAccessibilityService.isRunning()) }
    var batteryOptimizationIgnored by remember {
        mutableStateOf(
            (context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        )
    }

    var voiceEnrolled by remember { mutableStateOf(SpeakerVerifier.isEnrolled(context)) }
    var enrolling by remember { mutableStateOf(false) }
    var enrollmentStatus by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val audioRecorder = remember { CommandAudioRecorder(context) }

    var contactsCallSmsGranted by remember {
        mutableStateOf(
            listOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS
            ).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    var writeSettingsGranted by remember { mutableStateOf(Settings.System.canWrite(context)) }
    var dndAccessGranted by remember {
        mutableStateOf(
            (context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .isNotificationPolicyAccessGranted
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityGranted = DarkiAccessibilityService.isRunning()
                batteryOptimizationIgnored =
                    (context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager)
                        .isIgnoringBatteryOptimizations(context.packageName)
                writeSettingsGranted = Settings.System.canWrite(context)
                dndAccessGranted =
                    (context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                        .isNotificationPolicyAccessGranted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val contactsCallSmsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> contactsCallSmsGranted = results.values.all { it } }


    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: sin notificaciones el servicio igual funciona en Android < 13 */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D12))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text("DARKI", color = Color(0xFF33E1FF), fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text("Nucleo: activacion + conversacion", color = Color.Gray, fontSize = 14.sp)

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Anthropic API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { SecurePrefs.saveApiKey(context, apiKey) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Guardar llave") }

        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            enabled = !micGranted,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (micGranted) "Microfono: concedido" else "Conceder microfono") }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            },
            enabled = !overlayGranted,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (overlayGranted) "Overlay: concedido" else "Conceder 'mostrar sobre otras apps'") }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Conceder notificaciones") }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            enabled = !accessibilityGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (accessibilityGranted) "Control del telefono: activado"
                else "Activar control del telefono (Accesibilidad)"
            )
        }
        Text(
            "Busca \"DARKI\" en la lista y actívalo ahi. Le da a Darki la " +
                "capacidad de tocar, escribir y navegar por tu telefono.",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            },
            enabled = !batteryOptimizationIgnored,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (batteryOptimizationIgnored) "Escucha con pantalla apagada: activada"
                else "Permitir que escuche con la pantalla apagada"
            )
        }
        Text(
            "Si tu telefono es Xiaomi, Realme, Oppo o similar, ademas busca " +
                "\"Inicio automatico\" en los ajustes de bateria para DARKI: " +
                "eso Android no lo deja activar desde la app.",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Permisos para comandos tipo Siri (todo sin API)",
            color = Color(0xFF33E1FF),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                contactsCallSmsLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.SEND_SMS
                    )
                )
            },
            enabled = !contactsCallSmsGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (contactsCallSmsGranted) "Llamadas y mensajes: concedido"
                else "Conceder llamar y mandar mensajes por voz"
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            },
            enabled = !writeSettingsGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (writeSettingsGranted) "Brillo de pantalla: concedido" else "Conceder ajuste de brillo")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
            enabled = !dndAccessGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (dndAccessGranted) "Modo silencio/vibrar: concedido" else "Conceder modo silencio/vibrar")
        }
        Text(
            "Con estos permisos DARKI puede poner alarmas y temporizadores, " +
                "llamar y mandar mensajes por nombre de contacto, buscar en " +
                "internet, mover el volumen, cambiar el modo de sonido, ajustar " +
                "el brillo, tomar capturas de pantalla y agendar eventos — todo " +
                "sin necesitar la API de Claude.",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Reconocimiento de voz del dueno",
            color = Color(0xFF33E1FF),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Grabá tu voz 3 veces diciendo frases distintas en un lugar " +
                "sin mucho ruido. Con esto, DARKI va a comparar cada orden " +
                "contra tu voz y va a ignorar comandos sensibles si no sos vos.",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )

        if (enrolling) {
            CircularProgressIndicator(color = Color(0xFF33E1FF))
            Spacer(Modifier.height(8.dp))
            Text(enrollmentStatus, color = Color.Gray, fontSize = 13.sp)
        } else {
            OutlinedButton(
                onClick = {
                    enrolling = true
                    coroutineScope.launch {
                        val frases = listOf(
                            "Frase 1 de 3: decí en voz alta \"Darki, este soy yo\"...",
                            "Frase 2 de 3: decí cualquier frase corta, la que quieras...",
                            "Frase 3 de 3: por ultima vez, hablá normal por 3 segundos..."
                        )
                        val muestras = mutableListOf<ShortArray>()
                        for (frase in frases) {
                            enrollmentStatus = frase
                            delay(1200)
                            enrollmentStatus = "Grabando... hablá ahora"
                            val pcm = audioRecorder.recordPcm(durationMs = 3000)
                            muestras.add(pcm)
                        }
                        enrollmentStatus = "Procesando tu voz..."
                        val ok = SpeakerVerifier.enroll(context, muestras)
                        voiceEnrolled = ok
                        enrollmentStatus = if (ok) {
                            "Listo, tu voz quedo guardada."
                        } else {
                            "No se pudo procesar bien el audio. Probá en un lugar mas silencioso."
                        }
                        delay(1500)
                        enrolling = false
                    }
                },
                enabled = micGranted,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (voiceEnrolled) "Volver a grabar mi voz" else "Grabar mi voz (3 frases)")
            }

            if (voiceEnrolled) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        SpeakerVerifier.clearEnrollment(context)
                        voiceEnrolled = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Borrar mi voz guardada") }
            }
        }

        Text(
            if (voiceEnrolled) {
                "Tu voz ya esta enrolada: los comandos sensibles solo funcionan si hablas vos."
            } else {
                "Sin enrolar tu voz, DARKI todavia ejecuta cualquier orden sin " +
                    "verificar quien habla (modo abierto por defecto hasta que grabes tu voz)."
            },
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                val intent = Intent(context, DarkiForegroundService::class.java)
                ContextCompat.startForegroundService(context, intent)
            },
            enabled = micGranted,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Activar DARKI") }

        Text(
            if (apiKey.isBlank()) {
                "Sin API key, DARKI ya hace casi todo lo de un asistente tipo " +
                    "Siri: abrir apps y juegos, linterna, wifi, bluetooth, " +
                    "camara, alarmas, temporizadores, llamadas, mensajes, " +
                    "busquedas en internet, volumen, modo silencio, brillo, " +
                    "capturas de pantalla, eventos de calendario y recordar " +
                    "cosas. Solo la conversacion libre (charlar de temas que " +
                    "no son comandos) y el control avanzado de pantalla para " +
                    "ordenes que ninguna regla reconozca necesitan una llave."
            } else {
                "Con tu API key, ademas puede conversar libremente y " +
                    "controlar la pantalla para ordenes que ninguna regla " +
                    "de las de arriba reconozca."
            },
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
    }
}
