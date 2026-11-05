# Puente PC

Servidor HTTP liviano embebido para Android que permite la comunicación, depuración y transferencia de archivos entre el PC y la radio NWD K4811 vía WiFi sin necesidad de cables USB.

## Características
- **Instalación de APKs por WiFi:** Endpoint `/upload` que recibe archivos APK mediante HTTP POST e invoca al instalador del sistema de Android (`PackageInstaller`).
- **Captura de pantalla en tiempo real:** Endpoint `/screenshot` para inspeccionar la pantalla de la radio remotamente desde el navegador del PC.
- **Explorador de archivos:** Endpoint `/ls` y `/get` para descargar logs de CAN, registros GPS y videos grabados.
- **Acceso protegido:** Requiere una clave de seguridad configurable (`?k=...`) para autorizar cualquier petición.

