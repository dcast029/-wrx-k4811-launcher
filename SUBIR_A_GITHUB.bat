@echo off
chcp 65001 >nul
title Subir Suite WRX K4811 a un nuevo repositorio de GitHub
echo ==========================================================
echo   Subir cambios a tu nuevo repositorio de GitHub
echo ==========================================================
echo.
echo Crea primero un repositorio vacio en tu cuenta de GitHub:
echo https://github.com/new
echo (no le agregues README ni .gitignore al crearlo, dejalo vacio)
echo.
set /p REPO_URL="Pega aqui la URL de tu repositorio (ej: https://github.com/tu-usuario/tu-repo.git): "
if "%REPO_URL%"=="" goto fin

echo.
echo Cambiando origen a: %REPO_URL%
git remote remove origin 2>nul
git remote add origin %REPO_URL%
git branch -M main

echo.
echo Subiendo ramas y codigo al repositorio...
git push -u origin main

echo.
echo ==========================================================
if %ERRORLEVEL% EQU 0 (
    echo   Codigo subido con exito a GitHub!
) else (
    echo   Hubo un error al subir. Verifica que tengas sesion iniciada
    echo   o que la URL sea correcta.
)
echo ==========================================================
:fin
pause

