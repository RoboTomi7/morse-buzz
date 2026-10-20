package dev.morsebuzz;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Turns "down" / "up" key events into continuous vibration.
 *
 * The sender repeats "down" every ~400 ms while the key is held. If those heartbeats stop
 * (lost "up" message, dropped connection, sender closed the tab), the watchdog stops the
 * motor after TIMEOUT_MS so the phone can never get stuck buzzing.
 */
final class Buzzer {

    private static final long TIMEOUT_MS = 1500;
    private static final long WATCHDOG_TICK_MS = 200;
    // One long "on" segment, repeated from index 0 until cancel(): effectively infinite.
    private static final long[] CONTINUOUS = {0, 5000};

    interface Listener {
        void onBuzz(boolean on);
    }

    private final Vibrator vibrator;
    private final Handler handler;
    private final Listener listener;
    private boolean on;
    private boolean closed;
    private long lastBeat;

    Buzzer(Context ctx, Looper looper, Listener listener) {
        this.vibrator = vibrator(ctx);
        this.handler = new Handler(looper);
        this.listener = listener;
    }

    /** Key pressed, or heartbeat while still held. Safe to call from any thread. */
    void down() {
        handler.post(() -> {
            if (closed) return;
            lastBeat = SystemClock.uptimeMillis();
            if (!on) {
                on = true;
                vibrate(vibrator, VibrationEffect.createWaveform(CONTINUOUS, 0));
                handler.postDelayed(watchdog, WATCHDOG_TICK_MS);
                if (listener != null) listener.onBuzz(true);
            }
        });
    }

    /** Key released. Safe to call from any thread. */
    void up() {
        handler.post(this::stopNow);
    }

    /** Re-start the motor if a key is still held (after the system cancelled it). */
    void resumeIfOn(long delayMs) {
        handler.postDelayed(() -> {
            if (on && !closed) vibrate(vibrator, VibrationEffect.createWaveform(CONTINUOUS, 0));
        }, delayMs);
    }

    /** Permanently stop; later down() calls (e.g. from a closing connection) are ignored. Call on the looper thread. */
    void shutdown() {
        closed = true;
        stopNow();
    }

    private void stopNow() {
        handler.removeCallbacks(watchdog);
        if (on) {
            on = false;
            vibrator.cancel();
            if (listener != null) listener.onBuzz(false);
        }
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!on) return;
            if (SystemClock.uptimeMillis() - lastBeat > TIMEOUT_MS) {
                stopNow();
            } else {
                handler.postDelayed(this, WATCHDOG_TICK_MS);
            }
        }
    };

    // ---- helpers shared with MainActivity ----

    static Vibrator vibrator(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm.getDefaultVibrator();
        }
        return (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
    }

    /**
     * Uses the ALARM usage so the buzz still comes through when the phone is on silent and
     * when touch/notification vibration is turned off in system settings.
     */
    @SuppressWarnings("deprecation")
    static void vibrate(Vibrator v, VibrationEffect effect) {
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
        } else {
            v.vibrate(effect, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
        }
    }

    static void pulse(Context ctx, long ms) {
        vibrate(vibrator(ctx), VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }
}
