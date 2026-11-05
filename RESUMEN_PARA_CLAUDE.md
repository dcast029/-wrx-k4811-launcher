# Resumen Técnico Completo para Claude — Proyecto Radio Android Subaru WRX (UIS8581A / NWD K4811)

Ver también copia idéntica en `c:\Users\diego\Desktop\proyecto radio auto\RESUMEN_PARA_CLAUDE.md`.

**Fecha de actualización:** 5 de Noviembre de 2026
**Repositorio / Proyecto Gradle principal:** `c:\Users\diego\gdm-k4811`
**Carpeta de trabajo y volcados de la radio:** `c:\Users\diego\Desktop\proyecto radio auto`
**Carpeta de APKs listos para instalar:** `c:\Users\diego\Desktop\proyecto radio auto\1_INSTALAR_AHORA`

---

## 1. Conexión en Vivo con la Radio (`Puente PC`)

La radio tiene instalada la app **Puente PC** (`com.k4811.puente`), que levanta un servidor HTTP en el puerto `8765`.
- **IP actual de la radio:** `192.168.31.124:8765`
- **Clave actual (`?k=`):** `036617`
- **Versión compilada y enviada a la radio:** `v1.3.0` (`versionCode = 20270004`) y Launcher WRX `v3.1.0-wrx` (`versionCode = 20270101`).

### Endpoints HTTP disponibles en `Puente PC` (`Routes.java`):
- `GET /?k=CLAVE` → Estado general JSON (`version`, `uptime`, etc.).
- `GET /packages?k=CLAVE` → Lista de todos los paquetes instalados con sus `versionCode` y rutas APK.
- `GET /settings?k=CLAVE` → Volcado de `Settings.System`, `Settings.Secure`, `Settings.Global`.
- `GET /props?k=CLAVE` → Volcado de `getprop` de Android.
- `GET /storage?k=CLAVE` → Puntos de montaje (`/proc/mounts`) y espacio libre.
- `GET /ls?k=CLAVE&path=/storage/udiskh/HDDVR` → Listado detallado de cualquier directorio (`d`/`f`/`-`, tamaño, fecha).
- `GET /get?k=CLAVE&path=/ruta/archivo` → Descarga directa de cualquier archivo de la radio al PC.
- `GET /logcat?k=CLAVE&lines=500` → Últimas líneas de `logcat`.
- `GET /put_setting?k=CLAVE&table=system&name=dvr_path&value=/mnt/media_rw/udiskh` → Escribe en `Settings.System`.
- `GET /repair_dvr?k=CLAVE` → Ejecuta la reparación del pendrive DVR `/storage/udiskh/HDDVR`.
- `POST /upload?k=CLAVE&name=ARCHIVO.apk` (body binario del APK) → Sube un APK a `/sdcard/Download/K4811/apks/ARCHIVO.apk`.
- `GET /install?k=CLAVE&name=ARCHIVO.apk` → Abre automáticamente el instalador de paquetes en la pantalla de la radio para el APK subido.

---

## 2. Hallazgos Clave de Hardware y Firmware

1. **Los 2 USBs:**
   - `/storage/udisk`: Es el disco virtual USB de la dashcam **`Traffic Safety Guard` (`com.fvision.camera`)**. Contiene el archivo de protocolo `VSFILE` (196 KB) y `DVR.apk`.
   - `/storage/udiskh`: Es el pendrive FAT32 de 16 GB donde graba **`Streaming HD2` (`com.nwd.carkithd2cam`)** en `/storage/udiskh/HDDVR/`.
2. **Corrupción FAT32 de `rearcam/202611`:**
   - En `/storage/udiskh/HDDVR/rearcam`, la entrada `202611` quedó como un inodo FAT32 corrupto (`- 0 1969-12-31 21:00 202611`, `canRead() == false`, `isDirectory() == false`, `isFile() == false`) que bloqueaba las grabaciones de noviembre.
   - Como `.delete()` sobre ese inodo falla sin root, implementamos en `ExternalApps.repairHd2DvrDisk()` y `Routes.repairDvrUsb()` el renombrado del directorio padre `rearcam` → `.rearcam_corrupt_<timestamp>`, creación de un `rearcam` limpio, rescate automático de `202610` (videos de octubre intactos) hacia el nuevo `rearcam`, y creación de `rearcam/202611/05/HH`.
3. **Lista blanca de cámara en `Streaming HD2` (`SourceUtils.isNwdVideoPackage`):**
   - La cámara frontal está físicamente conectada por RCA al **Canal `"2"` (`AUX-IN`) del chip `tp9950` (`Camera ID 0`)**, el mismo chip cuyo Canal `"0"` usa la cámara trasera.
   - `Streaming HD2` (`HDDVRServiceManager`) mantiene abierta `Camera ID 0` en segundo plano y **solo la libera** cuando la app en primer plano está en `SourceUtils.isNwdVideoPackage`: `"com.nwd.auxin"`, `"com.nwd.dvr.front"`, `"com.nwd.tv"`, `"com.nwd.camera.app"`.
   - Por eso la app **`AUX` (`com.nwd.auxin`)** muestra la cámara frontal sin trabarse jamás y devuelve el control a `Streaming HD2` al cerrarse.

---

## 3. Estado de los Botones del Launcher WRX (`DockSlots.java` y `ExternalApps.java`)

- **Botón `DASHCAM` (`id = "dashcam"`):** Abre directo **`Traffic Safety Guard` (`com.fvision.camera`)**.
- **Botón `CÁM. TRASERA` (`id = "rear_cam"`):** Repara `/storage/udiskh/HDDVR` y abre directo **`Streaming HD2` (`com.nwd.carkithd2cam`)**.
- **Botón `CÁM. FRONTAL` (`id = "front_cam"`):** Abre directo **`AUX` (`com.nwd.auxin`)**.
- **Inicio automático (`CarWatchService.onCreate`):** Repara automáticamente `/storage/udiskh/HDDVR` en segundo plano al encender la radio.

