package dev.morsebuzz;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Foreground service that keeps an MQTT connection open and drives the vibration motor.
 *
 * Message protocol on topic morsebuzz/v1/CODE (QoS 0):
 *   "1" = key down (repeated every ~400 ms while held, acts as a heartbeat)
 *   "0" = key up
 * Presence on morsebuzz/v1/CODE/status (retained): "1" online, "0" offline (also the MQTT will).
 */
public class BuzzService extends Service {

    static final String ACTION_RELOAD = "dev.morsebuzz.RELOAD";

    enum State { OFF, CONNECTING, CONNECTED }

    interface Listener {
        void onStateChanged();
    }

    // Read by MainActivity. Only one instance of the service can exist per process.
    static volatile State state = State.OFF;
    static volatile String detail = "";
    static volatile boolean buzzing = false;
    static final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private static final String CHANNEL_ID = "link";
    private static final int NOTIF_ID = 1;
    private static final long RETRY_MS = 4000;

    // One worker thread for the whole process, never quit. A new service instance (quick
    // Stop -> Start) then queues its connect() behind the old instance's teardown, so the
    // old "offline" presence can't land after the new "online" one.
    private static HandlerThread sThread;

    private Handler handler;
    private Buzzer buzzer;
    private volatile MqttAsyncClient client;
    private MqttConnectOptions options;
    private String code;
    private String topic;
    private String statusTopic;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean destroyed;
    private BroadcastReceiver screenOffReceiver;

    private static synchronized HandlerThread worker() {
        if (sThread == null) {
            sThread = new HandlerThread("morsebuzz", Process.THREAD_PRIORITY_URGENT_DISPLAY);
            sThread.start();
        }
        return sThread;
    }

    static boolean isRunning() {
        return state != State.OFF;
    }

    static void start(Context c) {
        Prefs.setEnabled(c, true);
        c.startForegroundService(new Intent(c, BuzzService.class));
    }

    static void reload(Context c) {
        if (!isRunning()) return;
        c.startForegroundService(new Intent(c, BuzzService.class).setAction(ACTION_RELOAD));
    }

    static void stop(Context c) {
        Prefs.setEnabled(c, false);
        c.stopService(new Intent(c, BuzzService.class));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        handler = new Handler(worker().getLooper());
        buzzer = new Buzzer(this, worker().getLooper(), new Buzzer.Listener() {
            @Override
            public void onBuzz(boolean on) {
                buzzing = on;
                notifyListeners();
            }

            @Override
            public void onActivity() {
                notifyListeners();
            }
        });

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MorseBuzz:link");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        // Keeps Wi-Fi out of its deepest power-save mode with the screen off, which otherwise
        // adds a few hundred ms of delay to incoming packets.
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            @SuppressWarnings("deprecation")
            int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "MorseBuzz:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }

        // Reconnect immediately when a network comes back instead of waiting for backoff.
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.post(BuzzService.this::kick);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            networkCallback = null;
        }

        // Android cancels app vibrations when the screen turns off (on 12+, when the power
        // button is pressed). If a key is being held at that moment, start the motor again.
        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                buzzer.resumeIfOn(150);
            }
        };
        registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must be called promptly after startForegroundService(), for every start request.
        if (!goForeground()) return START_NOT_STICKY;
        boolean reload = intent != null && ACTION_RELOAD.equals(intent.getAction());
        handler.post(() -> {
            if (reload || client == null) {
                teardown(true);
                connect();
            }
        });
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (networkCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
        }
        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver);
            } catch (RuntimeException ignored) {
            }
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        handler.post(() -> {
            teardown(true);
            buzzer.shutdown();
            // A Paho callback racing with Stop may have re-posted the notification.
            // (If a new instance is already in the foreground, Android ignores this cancel.)
            nm.cancel(NOTIF_ID);
        });
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        Buzzer.vibrator(this).cancel();
        buzzing = false;
        setState(State.OFF, "");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ MQTT

    /** Runs on the handler thread. */
    private void connect() {
        Prefs.Broker broker = Prefs.broker(this);
        code = Prefs.code(this);
        topic = Prefs.topic(code);
        statusTopic = Prefs.statusTopic(code);

        options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setMaxReconnectDelay(4000); // backoff 1s, 2s, 4s, 4s…
        options.setKeepAliveInterval(30);
        options.setConnectionTimeout(10);
        options.setWill(statusTopic, bytes("0"), 1, true);

        String clientId = "mb-" + code + "-" + Long.toHexString(System.nanoTime());
        try {
            MqttAsyncClient c = new MqttAsyncClient(broker.tcpUri, clientId, new MemoryPersistence());
            c.setCallback(new Callback(c));
            client = c;
        } catch (MqttException e) {
            setState(State.CONNECTING, "Setup failed: " + e.getMessage());
            scheduleRetry();
            return;
        }
        setState(State.CONNECTING, "Connecting to " + broker.name + "…");
        attemptConnect();
    }

    /** Runs on the handler thread. */
    private void attemptConnect() {
        MqttAsyncClient c = client;
        if (c == null || c.isConnected()) return;
        try {
            c.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    // connectComplete() in the callback handles subscribing.
                }

                @Override
                public void onFailure(IMqttToken token, Throwable e) {
                    if (c != client) return;
                    setState(State.CONNECTING, "Can't reach the relay. Retrying…");
                    scheduleRetry();
                }
            });
        } catch (MqttException e) {
            // Usually "connect already in progress"; just try again shortly.
            scheduleRetry();
        }
    }

    private final Runnable retry = this::attemptConnect;

    private void scheduleRetry() {
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_MS);
    }

    /** Network came back: skip any pending backoff. Runs on the handler thread. */
    private void kick() {
        MqttAsyncClient c = client;
        if (c == null || c.isConnected()) return;
        try {
            c.reconnect();
        } catch (MqttException e) {
            attemptConnect();
        }
    }

    /** Runs on the handler thread. */
    private void teardown(boolean announceOffline) {
        handler.removeCallbacks(retry);
        buzzer.up();
        MqttAsyncClient c = client;
        client = null;
        if (c == null) return;
        try {
            if (c.isConnected()) {
                if (announceOffline) {
                    c.publish(statusTopic, bytes("0"), 1, true).waitForCompletion(1500);
                }
                c.disconnect(500).waitForCompletion(1500);
            }
        } catch (MqttException ignored) {
        }
        try {
            c.close();
        } catch (MqttException e) {
            try {
                c.disconnectForcibly(0, 1000); // 0 would mean wait forever
                c.close();
            } catch (MqttException ignored) {
            }
        }
    }

    private final class Callback implements MqttCallbackExtended {
        private final MqttAsyncClient c;

        Callback(MqttAsyncClient c) {
            this.c = c;
        }

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            handler.post(() -> {
                if (c != client) return;
                try {
                    c.subscribe(topic, 0);
                    c.publish(statusTopic, bytes("1"), 1, true);
                    setState(State.CONNECTED, "Ready. Code " + Prefs.pretty(code));
                } catch (MqttException e) {
                    setState(State.CONNECTING, "Subscribe failed. Retrying…");
                    try {
                        c.disconnect();
                    } catch (MqttException ignored) {
                    }
                    scheduleRetry();
                }
            });
        }

        @Override
        public void connectionLost(Throwable cause) {
            if (c != client) return;
            buzzer.up();
            setState(State.CONNECTING, "Connection lost. Reconnecting…");
        }

        @Override
        public void messageArrived(String t, MqttMessage message) {
            // Called on Paho's thread; Buzzer hops to our handler thread itself.
            if (c != client || !t.equals(topic)) return;
            byte[] p = message.getPayload();
            if (p.length == 0) return;
            buzzer.onMessage(new String(p, StandardCharsets.UTF_8));
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ state + notification

    private void setState(State s, String d) {
        // Late callbacks from a closing connection must not resurrect the notification.
        if (destroyed && s != State.OFF) return;
        state = s;
        detail = d;
        if (s != State.OFF) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIF_ID, buildNotification());
        }
        notifyListeners();
    }

    private static void notifyListeners() {
        for (Listener l : listeners) l.onStateChanged();
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private boolean goForeground() {
        if (state == State.OFF) {
            state = State.CONNECTING;
            detail = "Starting…";
        }
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (RuntimeException e) {
            // e.g. ForegroundServiceStartNotAllowedException when restarted from the background.
            // The user can start it again from the app.
            stopSelf();
            return false;
        }
        notifyListeners();
        return true;
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getBroadcast(this, 1,
                new Intent(this, StopReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String title = state == State.CONNECTED
                ? getString(R.string.notif_ready, Prefs.pretty(Prefs.code(this)))
                : getString(R.string.notif_connecting);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build())
                .build();
    }
}
