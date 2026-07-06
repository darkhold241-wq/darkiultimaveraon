#!/bin/bash

# Script para descargar el modelo de Vosk desde Google Drive
# Uso: ./download_model.sh

MODEL_DIR="app/src/main/assets/model"
DRIVE_FILE_ID="13D21VxIymbh-Fdi2D-jA45fDgcCnZEF6"
ZIP_FILE="/tmp/vosk-model.zip"

echo "📥 Descargando modelo de Vosk desde Google Drive..."

# Descargar desde Google Drive
# Nota: Para archivos grandes, usamos gdrive-download o curl con confirmación
curl -L "https://drive.google.com/uc?id=${DRIVE_FILE_ID}&export=download" -o "${ZIP_FILE}" 2>/dev/null

if [ ! -f "${ZIP_FILE}" ]; then
    echo "❌ Error: No se pudo descargar el archivo. Verifica el link."
    exit 1
fi

echo "📦 Descomprimiendo modelo..."

# Descomprimir
unzip -q "${ZIP_FILE}" -d /tmp/vosk_temp/

if [ ! -d "/tmp/vosk_temp" ]; then
    echo "❌ Error: No se pudo descomprimir el archivo."
    rm "${ZIP_FILE}"
    exit 1
fi

# Copiar al directorio correcto
mkdir -p "${MODEL_DIR}"

# Si el ZIP contiene una carpeta raíz (ej: vosk-model-small-es-0.42/), copiar su contenido
if [ -d "/tmp/vosk_temp/vosk-model-small-es-0.42" ]; then
    cp -r /tmp/vosk_temp/vosk-model-small-es-0.42/* "${MODEL_DIR}/"
elif [ -d "/tmp/vosk_temp/vosk-model-small-es"* ]; then
    cp -r /tmp/vosk_temp/vosk-model-small-es*/* "${MODEL_DIR}/"
else
    # Si el contenido está directamente en la raíz del ZIP
    cp -r /tmp/vosk_temp/* "${MODEL_DIR}/"
fi

# Limpiar temporales
rm -rf /tmp/vosk_temp "${ZIP_FILE}"

echo "✅ Modelo descargado correctamente en ${MODEL_DIR}"
echo "🎉 Ahora ejecuta: ./gradlew clean build"
