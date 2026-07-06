#!/bin/bash

# Script para descargar el modelo de Vosk en Android (Termux)
# Uso: bash download_model_android.sh

MODEL_DIR="app/src/main/assets/model"
DRIVE_FILE_ID="13D21VxIymbh-Fdi2D-jA45fDgcCnZEF6"
ZIP_FILE="/data/data/com.termux/files/tmp/vosk-model.zip"
TEMP_DIR="/data/data/com.termux/files/tmp/vosk_temp"

echo "📥 Descargando modelo de Vosk desde Google Drive (Termux)..."

# Instalar herramientas necesarias si no existen
if ! command -v curl &> /dev/null; then
    echo "⚠️ Instalando curl..."
    apt update && apt install -y curl
fi

if ! command -v unzip &> /dev/null; then
    echo "⚠️ Instalando unzip..."
    apt install -y unzip
fi

# Descargar desde Google Drive
curl -L "https://drive.google.com/uc?id=${DRIVE_FILE_ID}&export=download" -o "${ZIP_FILE}" 2>&1 | grep -E "^\s*[0-9]" || echo "Descargando..."

if [ ! -f "${ZIP_FILE}" ]; then
    echo "❌ Error: No se pudo descargar el archivo."
    echo "Verifica que tengas conexión a internet y que el link sea válido."
    exit 1
fi

FILE_SIZE=$(du -h "${ZIP_FILE}" | cut -f1)
echo "✅ Descargado: $FILE_SIZE"

echo "📦 Descomprimiendo modelo..."

# Crear directorio temporal
mkdir -p "${TEMP_DIR}"

# Descomprimir
unzip -q "${ZIP_FILE}" -d "${TEMP_DIR}"

if [ ! -d "${TEMP_DIR}" ] || [ -z "$(ls -A ${TEMP_DIR})" ]; then
    echo "❌ Error: No se pudo descomprimir el archivo."
    rm -f "${ZIP_FILE}"
    rm -rf "${TEMP_DIR}"
    exit 1
fi

# Copiar al directorio correcto del proyecto
mkdir -p "${MODEL_DIR}"

# Detectar estructura del ZIP y copiar correctamente
if [ -d "${TEMP_DIR}/vosk-model-small-es-0.42" ]; then
    echo "📂 Encontrada carpeta: vosk-model-small-es-0.42"
    cp -r "${TEMP_DIR}/vosk-model-small-es-0.42/"* "${MODEL_DIR}/" 2>/dev/null || cp -r "${TEMP_DIR}/vosk-model-small-es-0.42/." "${MODEL_DIR}/"
else
    # Buscar cualquier carpeta que empiece con vosk-model-small-es
    VOSK_DIR=$(find "${TEMP_DIR}" -maxdepth 1 -type d -name "vosk-model-small-es*" | head -1)
    if [ -n "$VOSK_DIR" ]; then
        echo "📂 Encontrada carpeta: $(basename $VOSK_DIR)"
        cp -r "$VOSK_DIR/"* "${MODEL_DIR}/" 2>/dev/null || cp -r "$VOSK_DIR/." "${MODEL_DIR}/"
    else
        # Si el contenido está directamente en la raíz del ZIP
        echo "📂 Copiando contenido directamente..."
        cp -r "${TEMP_DIR}/"* "${MODEL_DIR}/" 2>/dev/null || cp -r "${TEMP_DIR}/." "${MODEL_DIR}/"
    fi
fi

# Verificar que se copiaron archivos
if [ -z "$(ls -A ${MODEL_DIR})" ]; then
    echo "❌ Error: No se copiaron archivos al modelo."
    rm -rf "${TEMP_DIR}" "${ZIP_FILE}"
    exit 1
fi

# Contar archivos copiados
FILE_COUNT=$(find "${MODEL_DIR}" -type f | wc -l)
DIR_COUNT=$(find "${MODEL_DIR}" -type d | wc -l)

# Limpiar temporales
rm -rf "${TEMP_DIR}" "${ZIP_FILE}"

echo "✅ Modelo descargado y descomprimido correctamente"
echo "📊 Archivos copiados: $FILE_COUNT archivos en $DIR_COUNT carpetas"
echo "📁 Ubicación: $MODEL_DIR"
echo ""
echo "🎉 Ahora ejecuta: ./gradlew clean build"
