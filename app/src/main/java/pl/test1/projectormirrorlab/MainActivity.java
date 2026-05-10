package pl.test1.projectormirrorlab;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.nio.file.Files;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "pl.test1.projectormirrorlab.USB_PERMISSION";
    private static final int REQ_MEDIA_PROJECTION = 2001;
    private static final int REQ_NOTIFICATIONS = 2002;

    private UsbManager usbManager;
    private UsbAccessory currentAccessory;
    private MediaProjectionManager projectionManager;
    private TextView logView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Spinner resolutionSpin, fpsSpin, bitrateSpin, waitSpin, heartbeatSpin, packetSpin, csdSpin;
    private CheckBox echoFirstPacketCheck, delayVideoUntilHandshakeCheck, writeConfigFirstCheck;

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if ("pl.test1.projectormirrorlab.LOG".equals(intent.getAction())) {
                appendLog(intent.getStringExtra("line"));
            }
        }
    };

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbAccessory a = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            appendLog("USB permission result: granted=" + granted + ", accessory=" + describeAccessory(a));
            if (granted && a != null) currentAccessory = a;
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        usbManager = (UsbManager)getSystemService(USB_SERVICE);
        projectionManager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);

        registerReceivers();
        buildUi();

        appendLog("ProjectorMirrorLab 1.1 Activity start");
        appendLog("Intent action: " + getIntent().getAction());

        UsbAccessory fromIntent = getIntent().getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        if (fromIntent != null) {
            currentAccessory = fromIntent;
            appendLog("Accessory from launch intent: " + describeAccessory(fromIntent));
        }

        requestNotificationsIfNeeded();
        detectUsb();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        appendLog("onNewIntent action: " + intent.getAction());
        UsbAccessory fromIntent = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        if (fromIntent != null) {
            currentAccessory = fromIntent;
            appendLog("Accessory from new intent: " + describeAccessory(fromIntent));
        }
        detectUsb();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(logReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
    }

    private void registerReceivers() {
        IntentFilter logFilter = new IntentFilter("pl.test1.projectormirrorlab.LOG");
        IntentFilter usbFilter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logReceiver, logFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(usbPermissionReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, logFilter);
            registerReceiver(usbPermissionReceiver, usbFilter);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));

        TextView title = new TextView(this);
        title.setText("Projector Mirror Lab 1.1");
        title.setTextSize(18);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("Kombajn testowy. 1.1: najpierw uruchamia usługę USB, potem prosi o ekran. To powinno zachować handshake.");
        note.setTextSize(12);
        root.addView(note);

        resolutionSpin = spinner(new String[]{"800x480", "640x360", "480x270"});
        fpsSpin = spinner(new String[]{"30", "24", "15", "10"});
        bitrateSpin = spinner(new String[]{"1200000", "800000", "500000", "300000"});
        waitSpin = spinner(new String[]{"3500", "7000", "1000", "0"});
        heartbeatSpin = spinner(new String[]{"ACK_ZERO", "IGNORE", "ACK_ZERO_ONCE"});
        packetSpin = spinner(new String[]{"RAW", "LEN_LE_TOTAL", "LEN_LE_PAYLOAD", "LEN_BE_TOTAL", "LEN_BE_PAYLOAD"});
        csdSpin = spinner(new String[]{"AS_PACKET_MODE", "RAW_ALWAYS", "SKIP_CSD"});

        addLabel(root, "Rozdzielczość");
        root.addView(resolutionSpin);
        addLabel(root, "FPS");
        root.addView(fpsSpin);
        addLabel(root, "Bitrate");
        root.addView(bitrateSpin);
        addLabel(root, "Czekanie na handshake [ms]");
        root.addView(waitSpin);
        addLabel(root, "Heartbeat 00 00 00 00");
        root.addView(heartbeatSpin);
        addLabel(root, "Pakietowanie wideo");
        root.addView(packetSpin);
        addLabel(root, "SPS/PPS / CSD");
        root.addView(csdSpin);

        echoFirstPacketCheck = new CheckBox(this);
        echoFirstPacketCheck.setText("Echo pierwszego niezerowego pakietu USB");
        echoFirstPacketCheck.setChecked(true);
        root.addView(echoFirstPacketCheck);

        delayVideoUntilHandshakeCheck = new CheckBox(this);
        delayVideoUntilHandshakeCheck.setText("Czekaj z wideo do handshake / timeout");
        delayVideoUntilHandshakeCheck.setChecked(true);
        root.addView(delayVideoUntilHandshakeCheck);

        writeConfigFirstCheck = new CheckBox(this);
        writeConfigFirstCheck.setText("Wyślij SPS/PPS przed klatkami");
        writeConfigFirstCheck.setChecked(true);
        root.addView(writeConfigFirstCheck);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button detect = button("Wykryj USB");
        detect.setOnClickListener(v -> detectUsb());
        row1.addView(detect, weight());

        Button usb = button("Zgoda USB");
        usb.setOnClickListener(v -> requestUsbPermission());
        row1.addView(usb, weight());

        Button prep = button("Przygotuj USB");
        prep.setOnClickListener(v -> prepareUsbService());
        row1.addView(prep, weight());

        Button start = button("START");
        start.setOnClickListener(v -> requestProjection());
        row1.addView(start, weight());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button stop = button("STOP");
        stop.setOnClickListener(v -> stopService(new Intent(this, MirrorLabService.class)));
        row2.addView(stop, weight());

        Button load = button("Wczytaj log");
        load.setOnClickListener(v -> loadServiceLog());
        row2.addView(load, weight());

        Button copy = button("Kopiuj log");
        copy.setOnClickListener(v -> copyLog());
        row2.addView(copy, weight());
        root.addView(row2);

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values);
        s.setAdapter(ad);
        return s;
    }

    private void addLabel(LinearLayout root, String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12);
        v.setPadding(0, dp(5), 0, 0);
        root.addView(v);
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void detectUsb() {
        UsbAccessory[] list = usbManager.getAccessoryList();
        if (list == null || list.length == 0) {
            appendLog("No USB accessories detected.");
            return;
        }
        appendLog("USB accessories detected: " + list.length);
        for (int i = 0; i < list.length; i++) appendLog("[" + i + "] " + describeAccessory(list[i]));
        currentAccessory = chooseAccessory(list);
        appendLog("Selected accessory: " + describeAccessory(currentAccessory));
        if (currentAccessory != null && usbManager.hasPermission(currentAccessory)) appendLog("Already has USB permission.");
    }

    private UsbAccessory chooseAccessory(UsbAccessory[] list) {
        for (UsbAccessory a : list) {
            if ("Mirroring".equalsIgnoreCase(safe(a.getManufacturer()))
                    && "gan mirroring".equalsIgnoreCase(safe(a.getModel()))) return a;
        }
        return list[0];
    }

    private void requestUsbPermission() {
        if (currentAccessory == null) detectUsb();
        if (currentAccessory == null) {
            appendLog("Cannot request USB permission: no accessory.");
            return;
        }
        if (usbManager.hasPermission(currentAccessory)) {
            appendLog("USB permission already granted.");
            return;
        }
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        usbManager.requestPermission(currentAccessory, PendingIntent.getBroadcast(this, 0, intent, flags));
        appendLog("Requested USB permission.");
    }

    private void requestProjection() {
        if (!prepareUsbService()) return;
        appendLog("Requesting MediaProjection permission after USB service prepare...");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    private boolean prepareUsbService() {
        if (currentAccessory == null) detectUsb();
        if (currentAccessory == null) {
            appendLog("USB prepare blocked: no USB accessory.");
            return false;
        }
        if (!usbManager.hasPermission(currentAccessory)) {
            appendLog("USB prepare blocked: no USB permission.");
            requestUsbPermission();
            return false;
        }
        Intent svc = new Intent(this, MirrorLabService.class);
        svc.setAction(MirrorLabService.ACTION_PREPARE);
        fillOptions(svc);
        appendLog("Starting service in USB-only prepare mode...");
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
        return true;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                appendLog("MediaProjection granted. Sending projection to already prepared lab service.");
                Intent svc = new Intent(this, MirrorLabService.class);
                svc.setAction(MirrorLabService.ACTION_START);
                svc.putExtra(MirrorLabService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(MirrorLabService.EXTRA_RESULT_DATA, data);
                fillOptions(svc);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
            } else {
                appendLog("MediaProjection denied/cancelled.");
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void fillOptions(Intent svc) {
        String res = selected(resolutionSpin);
        String[] parts = res.split("x");
        svc.putExtra("width", Integer.parseInt(parts[0]));
        svc.putExtra("height", Integer.parseInt(parts[1]));
        svc.putExtra("fps", Integer.parseInt(selected(fpsSpin)));
        svc.putExtra("bitrate", Integer.parseInt(selected(bitrateSpin)));
        svc.putExtra("waitMs", Integer.parseInt(selected(waitSpin)));
        svc.putExtra("heartbeat", selected(heartbeatSpin));
        svc.putExtra("packet", selected(packetSpin));
        svc.putExtra("csd", selected(csdSpin));
        svc.putExtra("echoFirstPacket", echoFirstPacketCheck.isChecked());
        svc.putExtra("delayVideoUntilHandshake", delayVideoUntilHandshakeCheck.isChecked());
        svc.putExtra("writeConfigFirst", writeConfigFirstCheck.isChecked());

        appendLog("Options: " + res
                + ", fps=" + selected(fpsSpin)
                + ", bitrate=" + selected(bitrateSpin)
                + ", waitMs=" + selected(waitSpin)
                + ", heartbeat=" + selected(heartbeatSpin)
                + ", packet=" + selected(packetSpin)
                + ", csd=" + selected(csdSpin)
                + ", echoFirst=" + echoFirstPacketCheck.isChecked()
                + ", delayVideo=" + delayVideoUntilHandshakeCheck.isChecked()
                + ", writeConfigFirst=" + writeConfigFirstCheck.isChecked());
    }

    private String selected(Spinner s) {
        Object o = s.getSelectedItem();
        return o == null ? "" : o.toString();
    }

    private void loadServiceLog() {
        try {
            File f = new File(getExternalFilesDir(null), "projector_mirror_lab_log.txt");
            if (!f.exists()) {
                appendLog("Service log does not exist yet: " + f.getAbsolutePath());
                return;
            }
            String txt;
            if (Build.VERSION.SDK_INT >= 26) txt = new String(Files.readAllBytes(f.toPath()));
            else txt = "Open by ADB: " + f.getAbsolutePath();
            logView.setText(txt);
        } catch (Exception e) {
            appendLog("loadServiceLog error: " + e);
        }
    }

    private void copyLog() {
        ClipboardManager cb = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("ProjectorMirrorLab log", logView.getText()));
        appendLog("Visible log copied to clipboard.");
    }

    private void appendLog(String line) {
        if (line == null) return;
        handler.post(() -> {
            logView.append(line + "\n");
            int scrollAmount = logView.getLayout() == null ? 0 :
                    logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
            logView.scrollTo(0, Math.max(0, scrollAmount));
        });
    }

    private String describeAccessory(UsbAccessory a) {
        if (a == null) return "<null>";
        return "manufacturer=" + safe(a.getManufacturer())
                + ", model=" + safe(a.getModel())
                + ", description=" + safe(a.getDescription())
                + ", version=" + safe(a.getVersion())
                + ", uri=" + safe(a.getUri())
                + ", serial=" + safe(a.getSerial());
    }

    private String safe(String s) { return s == null ? "" : s; }
}
