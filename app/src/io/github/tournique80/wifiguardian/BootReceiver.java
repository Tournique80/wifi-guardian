package io.github.tournique80.wifiguardian;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Arranca la protección al encender el teléfono (o al actualizar la app) si estaba activada. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Registro.evento(c, "ARRANQUE_TELEFONO", intent.getAction());
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Se reinició: la espera de enfriamiento de una caída anterior ya no aplica.
            GuardService.prefs(c).edit()
                    .putBoolean(GuardService.PREF_ESPERANDO_ENFRIAR, false)
                    .putBoolean(GuardService.PREF_AVISO_FRIO, false)
                    .apply();
        }
        if (GuardService.prefs(c).getBoolean(GuardService.PREF_ACTIVA, true)) {
            GuardService.iniciar(c);
        }
    }
}
