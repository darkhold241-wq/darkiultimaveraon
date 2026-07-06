# Guía de Instalación del Modelo de Voz (Vosk)

## ⚠️ IMPORTANTE: El modelo NO está incluido en el repositorio

El modelo Vosk pesa ~40MB y no se incluye en Git. Sin él, la app compila pero falla al iniciar con:
```
Falta el modelo de voz en assets/model
```

## Solución: Descargar e instalar el modelo

### Paso 1: Descargar el modelo
Descarga el modelo pequeño en español desde:
- **URL**: https://alphacephei.com/vosk/models
- **Busca**: `vosk-model-small-es-0.42` (u otra versión reciente en español)
- **Tamaño**: ~40MB (comprimido)

### Paso 2: Descomprimir y colocar

```bash
# Descomprimir el ZIP descargado
unzip vosk-model-small-es-0.42.zip

# Copiar el contenido DENTRO de app/src/main/assets/model/
# IMPORTANTE: los archivos van DENTRO de model/, no como subcarpeta

# La estructura debe quedar así:
app/src/main/assets/model/
├── am/
├── conf/
├── graph/
├── ivector/
├── mfcc.conf          (archivo)
└── README             (archivo)
```

**No hacer esto (INCORRECTO):**
```
app/src/main/assets/model/vosk-model-small-es-0.42/
```

**Hacer esto (CORRECTO):**
```
app/src/main/assets/model/
├── am/
├── conf/
├── graph/
...
```

### Paso 3: Compilar y probar

```bash
# Desde la raíz del proyecto
./gradlew clean build
./gradlew installDebug
```

Luego abre la app. Si el modelo está bien colocado, no verás el error de "Falta el modelo de voz".

## Verificación: ¿Cómo saber si el APK incluye el modelo?

```bash
# Desempaquetar el APK y buscar los archivos del modelo
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "assets/model/"

# Deberías ver algo como:
# assets/model/mfcc.conf
# assets/model/am/
# assets/model/conf/
# assets/model/graph/
# ...
```

Si ves esos archivos, el APK está bien. Si NO ves nada de `assets/model/`, revisa que los archivos estén correctamente copiados a `app/src/main/assets/model/`.

## Troubleshooting

| Problema | Causa | Solución |
|----------|-------|----------|
| "Falta el modelo de voz" al iniciar | Modelo no descargado o mal colocado | Revisar paso 2 (estructura correcta) |
| APK compila pero muy lento | Modelo no está en assets, se carga de otro lado | Ejecutar `unzip -l app-debug.apk \| grep assets/model` |
| Frase de activación "darki" no funciona | Modelo descargado pero con idioma incorrecto (ej. ruso en lugar de español) | Asegurarse de descargar `vosk-model-small-es-0.42` (con `-es-`) |
| Reconocimiento de voz muy impreciso | Modelo correcto pero en ambiente ruidoso | Normal; Vosk es offline pero menos preciso que online. Probar en lugar silencioso |

## Notas

- ✅ **Reconocimiento offline**: Una vez en el APK, NO necesita internet para funcionar
- ✅ **Privacidad**: Tu voz nunca sale del teléfono (a menos que hagas una pregunta a Claude)
- ✅ **Veloz**: Se carga al iniciar la app (~2-3 segundos)
- ❌ **Sin internet**: Si el modelo falta, la app crashea (no hay fallback)

## Referencia para desarrolladores

El código que carga el modelo está en:
- `app/src/main/java/com/darki/os/service/WakeWordEngine.kt` (líneas 58-88)

Usa `StorageService.unpack(context, "model", "model", ...)` de la librería Vosk (`com.alphacephei:vosk-android:0.3.75`), que automáticamente:
1. Busca la carpeta `assets/model/`
2. La descomprime a almacenamiento interno (`/data/data/com.darki.os/files/model`)
3. Carga el modelo para reconocimiento de voz

Sin los archivos en `assets/model/`, el callback de error se activa inmediatamente.
