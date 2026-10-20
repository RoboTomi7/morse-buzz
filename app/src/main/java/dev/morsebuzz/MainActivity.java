package dev.morsebuzz;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

public class MainActivity extends Activity implements BuzzService.Listener {

    private TextView status;
    private TextView detail;
    private TextView code;
    private View buzzDot;
    private Button toggle;
    private Button battery;
    private Spinner brokerSpinner;
    private EditText senderUrl;
    private TextView activityLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        detail = findViewById(R.id.detail);
        code = findViewById(R.id.code);
        buzzDot = findViewById(R.id.buzz_dot);
        toggle = findViewById(R.id.toggle);
        battery = findViewById(R.id.battery);
        brokerSpinner = findViewById(R.id.broker);
        senderUrl = findViewById(R.id.sender_url);
        activityLog = findViewById(R.id.activity_log);
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.version)).setText(getString(R.string.version_label, v));
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        toggle.setOnClickListener(v -> {
            if (BuzzService.isRunning()) {
                BuzzService.stop(this);
            } else {
                askForNotifications();
                BuzzService.start(this);
            }
        });

        findViewById(R.id.share).setOnClickListener(v -> share());

        findViewById(R.id.new_code).setOnClickListener(v -> {
            Prefs.newCode(this);
            BuzzService.reload(this);
            refresh();
        });

        findViewById(R.id.test).setOnClickListener(v -> Buzzer.pulse(this, 400));

        battery.setOnClickListener(v -> requestBatteryExemption());

        String[] names = new String[Prefs.BROKERS.length];
        for (int i = 0; i < names.length; i++) names[i] = Prefs.BROKERS[i].name;
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        brokerSpinner.setAdapter(adapter);
        brokerSpinner.setSelection(Prefs.brokerIndex(this), false);
        brokerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == Prefs.brokerIndex(MainActivity.this)) return;
                Prefs.setBrokerIndex(MainActivity.this, position);
                BuzzService.reload(MainActivity.this);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        senderUrl.setText(Prefs.senderUrl(this));
        senderUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                Prefs.setSenderUrl(MainActivity.this, s.toString());
            }
        });

        askForNotifications();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BuzzService.listeners.add(this);
        refresh();
    }

    @Override
    protected void onPause() {
        BuzzService.listeners.remove(this);
        super.onPause();
    }

    @Override
    public void onStateChanged() {
        runOnUiThread(this::refresh);
    }

    private void refresh() {
        code.setText(Prefs.pretty(Prefs.code(this)));
        BuzzService.State s = BuzzService.state;
        switch (s) {
            case CONNECTED:
                status.setText(R.string.status_connected);
                break;
            case CONNECTING:
                status.setText(R.string.status_connecting);
                break;
            default:
                status.setText(R.string.status_off);
        }
        detail.setText(s == BuzzService.State.OFF ? getString(R.string.detail_off) : BuzzService.detail);
        toggle.setText(s == BuzzService.State.OFF ? R.string.start : R.string.stop);
        buzzDot.setAlpha(BuzzService.buzzing ? 1f : 0.15f);
        activityLog.setText(Buzzer.logText());

        PowerManager pm = getSystemService(PowerManager.class);
        boolean exempt = pm.isIgnoringBatteryOptimizations(getPackageName());
        battery.setVisibility(exempt ? View.GONE : View.VISIBLE);
    }

    private void share() {
        String c = Prefs.pretty(Prefs.code(this));
        String link = Prefs.senderLink(this);
        String text = link != null
                ? getString(R.string.share_with_link, link, c)
                : getString(R.string.share_code_only, c, Prefs.broker(this).name);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, getString(R.string.share)));
    }

    private void askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void requestBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
                // Some builds hide both screens; nothing more we can do from here.
            }
        }
    }
}
