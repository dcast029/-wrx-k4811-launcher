package com.k4811.puente;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

/** Pide al sistema permiso para capturar la pantalla y se cierra. */
@TargetApi(21)
public class CaptureActivity extends Activity {
    private static final int REQ = 7;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            ScreenCapture.start(this, resultCode, data);
            PuenteService.addLog("captura de pantalla permitida");
            Toast.makeText(this, "Captura de pantalla permitida", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Captura no permitida", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
