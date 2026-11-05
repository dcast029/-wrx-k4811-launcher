package com.huiying.cameramjpeg;

import android.util.Log;

/**
 * Enlace JNI con libuvccamera-lib.so (extraído de Traffic Safety Guard / com.fvision.camera).
 * Debe mantener el paquete com.huiying.cameramjpeg y el nombre UvcCamera para que el runtime
 * de Android enlace correctamente las funciones nativas.
 */
public class UvcCamera {
    private static final String TAG = "UvcCamera";

    static {
        try {
            System.loadLibrary("uvccamera-lib");
            Log.d(TAG, "libuvccamera-lib.so cargada con éxito");
        } catch (Throwable t) {
            Log.e(TAG, "Error cargando libuvccamera-lib: " + t.getMessage());
        }
    }

    public static final int USB_PROTOCOL_NOR = 100;
    public static final int USB_PROTOCOL_ENDPOINT = 200;

    private static volatile UvcCamera _instance;

    public String fd_error = "";
    public String cmd_fd_error = "";
    private int nativeCmdFd = 0;
    private int usbProtocol = USB_PROTOCOL_NOR;

    public static UvcCamera getInstance() {
        if (_instance == null) {
            synchronized (UvcCamera.class) {
                if (_instance == null) {
                    _instance = new UvcCamera();
                }
            }
        }
        return _instance;
    }

    // Callbacks requeridos por la librería nativa
    public static void onFrameData(byte[] data) {}
    public static void onFrame(byte[] data) {}
    public static void onEvent(int event) {}

    // Métodos nativos exportados por el .so
    public native String initUvcCmd(String path);
    public native int nativeSendCmd(int fd, int requesttype, int request, int value, int index, int length, byte[] buf);
    public native int nativeGetFile(int fd, byte[] buf, int length);
    public native int releaseUvcCamera(int fd);

    public synchronized int openCmd(String path) {
        if (nativeCmdFd > 0) {
            close();
        }
        String res = initUvcCmd(path);
        Log.d(TAG, "initUvcCmd(" + path + ") -> " + res);
        if (res != null && res.contains("_")) {
            String[] parts = res.split("_");
            try {
                nativeCmdFd = Integer.parseInt(parts[1]);
                return nativeCmdFd;
            } catch (Exception e) {
                Log.e(TAG, "Error parseando fd: " + res, e);
            }
        }
        return -1;
    }

    public synchronized int sendCmd(int requesttype, int request, int value, int index, int length, byte[] buf) {
        if (nativeCmdFd > 0) {
            return nativeSendCmd(nativeCmdFd, requesttype, request, value, index, length, buf);
        }
        return -1;
    }

    public synchronized int getFile(byte[] buf) {
        if (nativeCmdFd > 0) {
            return nativeGetFile(nativeCmdFd, buf, buf.length);
        }
        return -1;
    }

    public synchronized void close() {
        if (nativeCmdFd > 0) {
            releaseUvcCamera(nativeCmdFd);
            nativeCmdFd = 0;
        }
    }

    public int getNativeCmdFd() {
        return nativeCmdFd;
    }
}

