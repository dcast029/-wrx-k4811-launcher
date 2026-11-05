# Grabaciones (Dashcam & DVR)

App para visualizar, ordenar y proteger las grabaciones de la cámara trasera y la dashcam USB en radios NWD K4811.

## Características
- **Cámara Trasera (Streaming HD2):** Lee directamente los videos en el pendrive `udiskh` (`HDDVR/rearcam`), ordenándolos por fecha y hora.
- **Videos Bloqueados (sensorlock):** Pestaña dedicada a eventos de impacto registrados por el sensor G.
- **Dashcam USB (Traffic Safety Guard):** Se comunica con la dashcam USB conectada como disco `udisk` utilizando comandos binarios a través del archivo `HWC` y la librería nativa JNI `libuvccamera-lib.so`. Permite listar, descargar e importar videos al almacenamiento interno sin depender de apps externas chinas.
- **Ubicación GPS sincronizada:** Vincula automáticamente cada video con el registro GPS del launcher (`/sdcard/K4811/gps/AAAAMMDD.csv`), mostrando la calle o enlace en Google Maps del lugar donde se grabó.
- **Protección de grabaciones:** Guarda copias permanentes en `/sdcard/K4811/Protegidas/` para evitar que la radio las sobreescriba en bucle.

