package dev.morsebuzz;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the "Stop" button on the ongoing notification. */
public class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        BuzzService.stop(context);
    }
}
