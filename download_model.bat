#!/bin/bash

# Script para descargar el modelo de Vosk desde Google Drive (Windows batch)
# Uso: download_model.bat

@echo off
setlocal enabledelayedexpansion

set MODEL_DIR=app\src\main\assets\model
set DRIVE_FILE_ID=13D21VxIymbh-Fdi2D-jA45fDgcCnZEF6
set ZIP_FILE=%TEMP%\vosk-model.zip

echo 📥 Descargando modelo de Vosk desde Google Drive...

REM Descargar desde Google Drive usando PowerShell
powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object System.Net.WebClient).DownloadFile('https://drive.google.com/uc?id=13D21VxIymbh-Fdi2D-jA45fDgcCnZEF6^&export=download', '%ZIP_FILE%')}"

if not exist "%ZIP_FILE%" (
    echo ❌ Error: No se pudo descargar el archivo.
    exit /b 1
)

echo 📦 Descomprimiendo modelo...

REM Descomprimir
powershell -Command "Expand-Archive -Path '%ZIP_FILE%' -DestinationPath '%TEMP%\vosk_temp' -Force"

if not exist "%TEMP%\vosk_temp" (
    echo ❌ Error: No se pudo descomprimir el archivo.
    del "%ZIP_FILE%"
    exit /b 1
)

REM Crear directorio si no existe
if not exist "%MODEL_DIR%" mkdir "%MODEL_DIR%"

REM Copiar archivos
if exist "%TEMP%\vosk_temp\vosk-model-small-es-0.42" (
    xcopy "%TEMP%\vosk_temp\vosk-model-small-es-0.42\*" "%MODEL_DIR%\" /E /I /Y
) else (
    xcopy "%TEMP%\vosk_temp\*" "%MODEL_DIR%\" /E /I /Y
)

REM Limpiar
rmdir /s /q "%TEMP%\vosk_temp"
del "%ZIP_FILE%"

echo ✅ Modelo descargado correctamente en %MODEL_DIR%
echo 🎉 Ahora ejecuta: gradlew clean build
