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

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Turns key messages from the sender page into vibration.
 *
 * Message format (v2). Every buzz has a session id (one per open sender page) and a number:
 *   "1:SESSION:N:GAP"  buzz N started (repeated every ~400 ms while held, as a heartbeat).
 *                      GAP = minimum silence in ms before this buzz may start.
 *   "0:SESSION:N:DUR"  buzz N ended. DUR = how long buzz N should last in ms.
 * Old pages send plain "1" / "0", which still works.
 *
 * Because every buzz has a number, a duplicated or late message can never make an extra
 * buzz, and because the stop message carries the length, network jitter doesn't change
 * how long each buzz feels. If heartbeats stop (lost "end" message, dropped connection,
 * tab closed) the watchdog stops the motor after TIMEOUT_MS.
 *
 * Everything runs on the handler's thread.
 */
final class Buzzer {

    interface Listener {
        void onBuzz(boolean on);

        void onActivity();
    }

    private static final long TIMEOUT_MS = 1500;
    private static final long WATCHDOG_TICK_MS = 200;
    private static final long MAX_GAP_MS = 5000;
    private static final long MAX_DUR_MS = 120_000;
    // One long "on" segment, repeated from index 0 until cancel(): effectively infinite.
    private static final long[] CONTINUOUS = {0, 5000};

    private final Vibrator vibrator;
    private final Handler handler;
    private final Listener listener;
    private final Object timers = new Object();   // token for every scheduled start/stop

    private boolean closed;

    // Motor
    private boolean motorOn;
    private long motorSeq = -1;     // which buzz the motor is playing
    private long motorOnAt;
    private long offAt = Long.MIN_VALUE / 2;   // when the motor stopped / is scheduled to stop

    // Current buzz from the sender
    private String session = "";
    private long cur = -1;          // number of the newest buzz we've seen start
    private boolean curEnded = true;
    private long curStartAt;        // when buzz `cur` started / is scheduled to start
    private long maxEnded = -1;     // highest buzz number that has ended (ignore anything <= this)
    private long lastBeat;
    private Object curStartToken;

    Buzzer(Context ctx, Looper looper, Listener listener) {
        this.vibrator = vibrator(ctx);
        this.handler = new Handler(looper);
        this.listener = listener;
    }

    // ------------------------------------------------------------------ public, any thread

    void onMessage(String payload) {
        handler.post(() -> handle(payload));
    }

    /** Stop everything right now (connection lost, service stopping). */
    void up() {
        handler.post(this::stopAll);
    }

    /** Re-start the motor if a buzz is playing (after the system cancelled it at screen-off). */
    void resumeIfOn(long delayMs) {
        handler.postDelayed(() -> {
            if (motorOn && !closed) vibrate(vibrator, VibrationEffect.createWaveform(CONTINUOUS, 0));
        }, delayMs);
    }

    /** Permanently stop; later messages are ignored. Call on the looper thread. */
    void shutdown() {
        closed = true;
        stopAll();
    }

    // ------------------------------------------------------------------ message handling

    private void handle(String payload) {
        if (closed) return;
        long now = SystemClock.uptimeMillis();
        String[] f = payload.trim().split(":");
        boolean isStart = f[0].equals("1");

        if (f.length < 3) {
            // Old sender page: plain "1" (down / heartbeat) or "0" (up).
            if (isStart) {
                onStart("legacy", (!curEnded && session.equals("legacy")) ? cur : cur + 1, 0, now);
            } else {
                onEnd("legacy", cur, 0, now);
            }
            return;
        }

        long n = parse(f[2]);
        long arg = f.length > 3 ? parse(f[3]) : 0;
        if (n < 0) return;
        if (isStart) onStart(f[1], n, arg, now);
        else onEnd(f[1], n, arg, now);
    }

    private void onStart(String s, long n, long gap, long now) {
        newSessionIfNeeded(s, now);
        if (n <= maxEnded || n < cur) {
            if (n != maxEnded) log("ignored late start #" + n);   // n == maxEnded: normal late heartbeat
            return;
        }
        if (n == cur) {              // heartbeat for the buzz that's playing
            lastBeat = now;
            return;
        }
        // A new buzz. If the previous one never got its end message, end it now.
        if (!curEnded) endCurrentNow(now, "no end message for #" + cur);

        cur = n;
        curEnded = false;
        lastBeat = now;
        gap = clamp(gap, 0, MAX_GAP_MS);
        curStartAt = Math.max(now, offAt + gap);
        log("← start #" + n + (curStartAt > now ? "  (waits " + (curStartAt - now) + " ms for gap)" : ""));

        final long seq = n;
        curStartToken = new Object();
        handler.postAtTime(() -> motorStart(seq), curStartToken, curStartAt);
        armWatchdog();
    }

    private void onEnd(String s, long n, long dur, long now) {
        newSessionIfNeeded(s, now);
        if (n <= maxEnded) {
            log("ignored repeated end #" + n);
            return;
        }
        maxEnded = n;
        if (n != cur) {              // end for a buzz whose start we never saw
            log("ignored end #" + n + " (never started)");
            return;
        }
        curEnded = true;
        dur = clamp(dur, 0, MAX_DUR_MS);
        // Play exactly `dur` from when the buzz really started, unless this message came late.
        long stopAt = Math.max(now, curStartAt + dur);
        log("← end #" + n + "  " + dur + " ms");
        boolean started = motorOn && motorSeq == n;
        if (!started && stopAt <= curStartAt) {  // zero-length buzz that hasn't started: drop it
            handler.removeCallbacksAndMessages(curStartToken);
            return;
        }
        offAt = stopAt;
        final long seq = n;
        handler.postAtTime(() -> motorStop(seq), timers, stopAt);
    }

    private void newSessionIfNeeded(String s, long now) {
        if (s.equals(session)) return;
        if (!curEnded) endCurrentNow(now, "new sender");
        session = s;
        cur = -1;
        maxEnded = -1;
        curEnded = true;
    }

    /** End the current buzz immediately (watchdog, missing end message, new sender). */
    private void endCurrentNow(long now, String why) {
        log("stopped #" + cur + ": " + why);
        handler.removeCallbacksAndMessages(curStartToken);
        curEnded = true;
        maxEnded = Math.max(maxEnded, cur);
        motorStop(cur);
        offAt = Math.max(offAt, now);
    }

    private void stopAll() {
        handler.removeCallbacksAndMessages(timers);
        if (curStartToken != null) handler.removeCallbacksAndMessages(curStartToken);
        handler.removeCallbacks(watchdog);
        if (!curEnded) {
            curEnded = true;
            maxEnded = Math.max(maxEnded, cur);
        }
        motorStop(motorSeq);
    }

    // ------------------------------------------------------------------ motor

    private void motorStart(long seq) {
        if (closed) return;
        if (motorOn) motorStop(motorSeq);    // shouldn't happen, but never overlap two buzzes
        motorOn = true;
        motorSeq = seq;
        motorOnAt = SystemClock.uptimeMillis();
        vibrate(vibrator, VibrationEffect.createWaveform(CONTINUOUS, 0));
        if (listener != null) listener.onBuzz(true);
    }

    private void motorStop(long seq) {
        if (!motorOn || seq != motorSeq) return;
        motorOn = false;
        vibrator.cancel();
        long now = SystemClock.uptimeMillis();
        offAt = Math.max(offAt, now);
        log("   buzzed " + (now - motorOnAt) + " ms");
        if (listener != null) listener.onBuzz(false);
    }

    private void armWatchdog() {
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_TICK_MS);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (curEnded) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastBeat > TIMEOUT_MS) {
                endCurrentNow(now, "sender went quiet");
            } else {
                handler.postDelayed(this, WATCHDOG_TICK_MS);
            }
        }
    };

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ activity log

    private static final int LOG_LINES = 40;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    private void log(String line) {
        String t = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        synchronized (LOG) {
            LOG.addFirst(t + "  " + line);
            while (LOG.size() > LOG_LINES) LOG.removeLast();
        }
        if (listener != null) listener.onActivity();
    }

    /** Newest first. */
    static String logText() {
        synchronized (LOG) {
            return LOG.isEmpty() ? "Nothing received yet." : String.join("\n", LOG);
        }
    }

    // ------------------------------------------------------------------ helpers shared with MainActivity

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
