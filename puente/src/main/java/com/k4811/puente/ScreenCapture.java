package com.k4811.puente;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Captura de pantalla con MediaProjection (Android 5+). El permiso lo da el usuario en la radio
 * (diálogo del sistema) y dura hasta que la radio se apaga o se toca "Detener captura".
 */
@TargetApi(21)
final class ScreenCapture {
    private ScreenCapture() {
    }

    private static MediaProjection projection;
    private static VirtualDisplay display;
    private static ImageReader reader;
    private static HandlerThread thread;
    private static int width, height;

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 21;
    }

    static synchronized boolean active() {
        return projection != null;
    }

    static synchronized void start(Context c, int resultCode, Intent data) {
        stop();
        MediaProjectionManager mpm = (MediaProjectionManager) c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) return;
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        width = m.widthPixels;
        height = m.heightPixels;
        thread = new HandlerThread("puente-capture");
        thread.start();
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("puente", width, height, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, new Handler(thread.getLooper()));
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stop();
            }
        }, new Handler(thread.getLooper()));
    }

    static synchronized void stop() {
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
        }
        if (thread != null) thread.quitSafely();
        display = null;
        reader = null;
        projection = null;
        thread = null;
    }

    /** PNG de la pantalla actual, o null si no hay permiso. */
    static synchronized byte[] png() {
        if (reader == null) return null;
        Image img = null;
        for (int i = 0; i < 20 && img == null; i++) {
            img = reader.acquireLatestImage();
            if (img == null) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (img == null) return null;
        try {
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int rowPad = p.getRowStride() - p.getPixelStride() * width;
            Bitmap bmp = Bitmap.createBitmap(width + rowPad / p.getPixelStride(), height, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            Bitmap crop = Bitmap.createBitmap(bmp, 0, 0, width, height);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            crop.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } finally {
            img.close();
        }
    }
}
