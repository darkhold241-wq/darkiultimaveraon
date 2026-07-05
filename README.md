# DARKI OS — Núcleo (v0.5.0)

## 🆕 Novedad de esta version: comandos tipo Siri/OK Google, SIN API

Se agrego una libreria grande de comandos que funcionan 100% locales,
sin necesitar la llave de Anthropic (ver `actions/SiriLikeActions.kt`):

- **Alarmas y temporizadores**: "pon una alarma a las 7 de la manana",
  "pon un temporizador de 10 minutos"
- **Llamadas**: "llama a mama" (busca el contacto y llama; si no diste
  permiso de llamar directo, deja el numero marcado listo)
- **Mensajes**: "mandale un mensaje a Juan diciendo ya llego" (lo manda
  directo si diste permiso de SMS, si no lo deja prellenado)
- **Busqueda web**: "busca receta de arepas en internet"
- **Volumen**: "sube el volumen", "baja el volumen", "silencia el telefono"
- **Modo de sonido**: "pon modo silencio", "pon modo vibrar", "pon modo normal"
- **Brillo**: "sube el brillo", "baja el brillo", "pon el brillo al 80%"
- **Captura de pantalla**: "toma una captura de pantalla" (necesita
  Accesibilidad activada)
- **Calendario**: "agendame una reunion mañana a las 3 de la tarde"
  (deja el evento prellenado en tu calendario para que confirmes)

### Realidad importante sobre esto (leelo antes de esperar magia)
Esto NO es una IA entendiendo lenguaje natural como Siri o Google
Assistant de verdad — ellos mandan tu voz a servidores con modelos de
lenguaje grandes, cosa que decidiste evitar (sin API). Lo que hay aca
es **coincidencia de patrones de texto** (regex): entiende MUY bien
las frases de arriba tal como estan escritas o parecidas, pero no
va a entender formas raras de decir lo mismo. Si Darki no reconoce
una orden, cae a conversacion con Claude (necesita API key) o te dice
que no la entendio.

Si con el tiempo se te ocurren mas frases que quieras que reconozca,
decime la frase exacta que decis y la agrego como una regla nueva —
es rapido de sumar una por una.

### Permisos nuevos que hacen falta
Todos se piden desde la pantalla principal, seccion "Permisos para
comandos tipo Siri":
- **Contactos + Llamadas + SMS**: para "llama a X" y "mandale un
  mensaje a X". Sin esto, igual busca el contacto pero te deja la
  llamada/mensaje prellenados para que vos completes el ultimo toque.
- **Ajuste de brillo**: permiso especial de Android (WRITE_SETTINGS),
  se concede desde una pantalla de Ajustes que la app te abre sola.
- **Modo silencio/vibrar**: permiso de "No molestar" (acceso a la
  politica de notificaciones), tambien se concede desde una pantalla
  especial.

Sobre almacenamiento: nada de esto pesa nada notable (los comandos
son texto, no modelos); tus 250GB de espacio no son un limitante en
ningun momento de este proyecto.

---

# DARKI OS — Núcleo (v0.4.0)

## 🆕 Novedad de esta version: verificacion de voz del dueño

Ahora DARKI compara CADA orden contra la voz de su dueño antes de
ejecutar cualquier cosa "sensible" (abrir apps, tocar ajustes, modo
agente de accesibilidad, apagarse, etc). La conversacion libre con
Claude sigue funcionando para cualquiera, porque no controla nada.

### Como funciona (version honesta, sin exagerar)
No existe una API publica de Android para "voice match" como la de
Google Assistant (es propietaria de Google, no esta disponible para
apps de terceros). Meter un modelo de deep learning para esto
requeriria descargar un archivo `.tflite` de decenas de MB, el mismo
problema que ya tuviste con el modelo de Vosk.

Por eso implemente un verificador basado en **MFCC + similitud
coseno** (`app/src/main/java/com/darki/os/voice/`), una tecnica
clasica de procesamiento de audio que no necesita descargar nada:
identifica el timbre general de tu voz. Es bueno para distinguir "sos
vos" de "es otra persona" en el uso diario, pero **no es a prueba de
alguien reproduciendo una grabacion tuya** — para un asistente
personal en tu propio celular es un balance razonable.

### Como enrolar tu voz
1. Abrí la app, dale permiso de microfono si todavia no lo hiciste.
2. En la seccion "Reconocimiento de voz del dueño", tocá "Grabar mi voz
   (3 frases)" y segui las indicaciones (3 grabaciones cortas).
3. Listo — desde ahi, si otra persona dice "darki" y da una orden,
   DARKI va a decir "No te reconozco la voz para eso, parce." y no va
   a ejecutar nada sensible.
4. Si notás que te rechaza seguido a vos mismo (por un resfriado, un
   lugar ruidoso), volvé a grabar tu voz ahi mismo, o ajustá
   `SpeakerVerifier.THRESHOLD` en el codigo (0.83 por defecto; bajalo
   a ~0.78 si te rechaza de mas, subilo a ~0.88 si otra persona logra
   pasar).

### Sin enrolar tu voz
Si todavia no grabaste tu voz, DARKI queda en "modo abierto": ejecuta
cualquier orden sin verificar quien habla (para no bloquearte apenas
instalas la app). En cuanto enrolas, se activa la verificacion real.

### Que se considera "sensible"
Por diseño, TODAS las acciones son sensibles por defecto (opcion
seguro por defecto) — ver `DarkiAction.sensitive` en
`actions/DarkiAction.kt`. Si mas adelante queres permitir alguna
accion inofensiva para cualquiera (ej. "que hora es"), se puede
sobreescribir esa propiedad a `false` en esa accion puntual.

No hace falta ninguna dependencia nueva en Gradle para esto: todo el
procesamiento de audio (FFT, MFCC, similitud coseno) esta escrito en
Kotlin puro sobre `android.media.AudioRecord`, que ya es parte del
SDK de Android.

---

# DARKI OS — Núcleo (v0.3.0)

## Qué se arregló en esta versión
1. **Carpeta rota**: había una carpeta creada por error con el nombre
   literal `{service,network,overlay,voice,data,ui` (un bug mío al usar
   `mkdir` con llaves). Estaba vacía — todo el código real ya estaba en
   las carpetas correctas (`service/`, `network/`, `overlay/`, etc.) Se
   borró la carpeta basura.
2. **Gradle Wrapper**: te explico esto con cuidado porque es la parte
   más importante.

### Sobre gradlew, gradlew.bat y gradle-wrapper.jar
El `gradle-wrapper.jar` es un archivo binario oficial que firma/descarga
Gradle. Yo no tengo acceso a internet en el entorno donde genero este
proyecto, así que **no puedo fabricar ese binario a mano de forma
confiable** — inventarlo sería peor que no tenerlo (podría corromperse
o quedar desactualizado).

En vez de arriesgarme a darte un jar roto, configuré el repo para que
**se auto-repare usando el Gradle real de GitHub**, que sí tiene
internet:

- `.github/workflows/build-apk.yml`: en cada `git push`, este workflow
  instala un Gradle auténtico, genera el wrapper fresco, compila el
  APK y te lo deja descargable — sin importar si gradlew ya existe en
  el repo o no. **Esto ya resuelve lo de "no puedo generar un APK".**
- `.github/workflows/fix-wrapper.yml`: corré este UNA VEZ a mano desde
  la pestaña Actions (ver pasos abajo) y te deja `gradlew`,
  `gradlew.bat` y `gradle-wrapper.jar` **committeados de verdad en el
  repo**, para que también puedas abrir el proyecto en Android Studio
  o compilar desde Termux localmente sin depender de Actions cada vez.

`gradle-wrapper.properties` sí lo armé yo mismo (es un archivo de texto
plano, no binario) apuntando a Gradle 8.7, que es la versión que
necesita el Android Gradle Plugin 8.5.0 que usa este proyecto.

## Cómo instalar esto, 100% desde el celular (sin PC)

### 1. Subir estos archivos a tu repo
Desde Termux, en la carpeta de tu repo (`Darki1`), reemplazá el
contenido con este ZIP y subilo:
```
git add -A
git commit -m "Reparar estructura y agregar Gradle Wrapper + CI"
git push
```

### 2. Dejar que GitHub compile el APK
Con el push, `build-apk.yml` arranca solo. Para verlo:
- Abrí github.com/darkhold241-wq/Darki1 en el navegador del celular
  (o la app de GitHub)
- Pestaña **Actions** → vas a ver la corrida "Build DARKI APK" en
  progreso (tarda unos 3-6 minutos)
- Si termina en verde ✅, entrá a esa corrida → sección **Artifacts**
  → descargá `darki-debug-apk`
- Si termina en rojo ❌, entrá al log del paso que falló y pegámelo
  acá, lo reviso

### 3. Reparar el wrapper para el futuro (una sola vez)
- Pestaña **Actions** → elegí "Fix Gradle Wrapper" en la lista de la
  izquierda → botón **Run workflow** → Run workflow (confirmar)
- Esto le agrega `gradlew` de verdad a tu repo con un commit automático

  Nota: si el push del workflow falla por permisos, andá a
  Settings → Actions → General → "Workflow permissions" y activá
  "Read and write permissions".

### 4. Instalar el APK en tu Black Z2
- El artifact se descarga como un `.zip` que contiene el `.apk` adentro
  (así empaqueta GitHub los artifacts, no es un error)
- Descomprimilo con cualquier gestor de archivos del celular
- Tocá el `.apk` → si es la primera vez, Android te va a pedir
  permiso para "instalar apps de este origen" (el navegador o el
  gestor de archivos) → lo activás → Instalar

### 5. Configurar dentro de la app
- Abrí DARKI, pegá tu API key de Anthropic, guardá
- Concedé permisos de micrófono, overlay ("mostrar sobre otras apps")
  y notificaciones
- Tocá "Activar DARKI"

Falta un paso manual aparte: el modelo de voz de Vosk (~40MB) no viaja
en el repo por su tamaño. Sin él, la app compila e instala bien, pero
el reconocimiento de voz va a fallar con un mensaje de error claro
hasta que lo agregues (ver README anterior / te lo repito si querés).

## Qué NO alcancé a probar
Soy honesto: no tengo un teléfono ni un emulador Android para probarlo
yo mismo, y mi entorno de trabajo no tiene SDK de Android ni conexión
a internet para compilar localmente. Hice una revisión estática
completa (balance de llaves/paréntesis en cada archivo Kotlin,
validación de sintaxis de cada XML, y verificación cruzada de que
paquetes y nombres de clases coincidan con el Manifest) y todo pasó
limpio — pero la prueba real es la corrida de GitHub Actions. Si falla
ahí, pegame el log y lo arreglo.

## Siguiente paso pendiente
Todavía no metí el Accessibility Service (control de otras apps,
lectura de pantalla, tocar botones). Lo dejamos para la próxima
iteración sobre esta base ya reparada.
