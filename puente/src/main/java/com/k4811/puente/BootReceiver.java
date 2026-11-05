package com.k4811.puente;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Si el usuario marcó "Encender al prender la radio", arranca el servidor. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (c.getSharedPreferences("puente", Context.MODE_PRIVATE).getBoolean("auto", false)) {
            PuenteService.start(c);
        }
    }
}
