package dev.morsebuzz;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the receiver back after a reboot or app update, if it was on before. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (!Prefs.enabled(context)) return;
        try {
            BuzzService.start(context);
        } catch (RuntimeException ignored) {
            // Background start not allowed on this device; the user can open the app to start it.
        }
    }
}
