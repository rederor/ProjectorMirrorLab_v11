package pl.test1.projectormirrorlab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjection.Callback;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MirrorLabService extends Service {
    public static final String ACTION_PREPARE = "pl.test1.projectormirrorlab.PREPARE";
    public static final String ACTION_START = "pl.test1.projectormirrorlab.START";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL_ID = "mirror_lab";
    private static final int NOTIFICATION_ID = 701;

    private UsbManager usbManager;
    private ParcelFileDescriptor accessoryFd;
    private FileInputStream usbInput;
    private FileOutputStream usbOutput;

    private MediaProjection mediaProjection;
    private MediaCodec encoder;
    private Surface encoderInputSurface;
    private VirtualDisplay virtualDisplay;

    private volatile boolean running = false;
    private volatile boolean readingUsb = false;
    private Thread readerThread;
    private Thread encoderThread;

    private File logFile;

    private int width, height, fps, bitrate, waitMs;
    private String heartbeatMode, packetMode, csdMode;
    private boolean echoFirstPacket, delayVideoUntilHandshake, writeConfigFirst;

    private volatile boolean firstPacketSeen = false;
    private byte[] firstPacket = null;

    private long zeroHeartbeats = 0;
    private long zeroAcks = 0;
    private long framesWritten = 0;
    private long bytesWritten = 0;

    @Override public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager)getSystemService(USB_SERVICE);
        logFile = new File(getExternalFilesDir(null), "projector_mirror_lab_log.txt");
        createNotificationChannel();
        log("MirrorLabService 1.1 onCreate");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Lab service active"));

        if (intent == null) {
            log("Service started with null intent.");
            return START_STICKY;
        }

        String action = intent.getAction();
        log("onStartCommand action=" + action);

        if (ACTION_PREPARE.equals(action)) {
            readOptions(intent);
            firstPacketSeen = false;
            firstPacket = null;
            if (!openAccessory()) {
                log("PREPARE: cannot open accessory.");
                return START_STICKY;
            }
            log("PREPARE complete: USB open, reader running. Waiting for projection START.");
            updateNotification("USB prepared; waiting for screen permission");
            return START_STICKY;
        }

        if (!ACTION_START.equals(action)) {
            log("Service started without known action.");
            return START_STICKY;
        }

        readOptions(intent);

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == 0 || resultData == null) {
            log("START: missing MediaProjection result data.");
            return START_STICKY;
        }

        try {
            startVideo(resultCode, resultData);
        } catch (Exception e) {
            log("startVideo exception: " + e);
            stopSelf();
        }

        return START_STICKY;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        log("MirrorLabService onDestroy");
        stopWork();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void readOptions(Intent i) {
        width = i.getIntExtra("width", 800);
        height = i.getIntExtra("height", 480);
        fps = i.getIntExtra("fps", 30);
        bitrate = i.getIntExtra("bitrate", 1200000);
        waitMs = i.getIntExtra("waitMs", 3500);
        heartbeatMode = i.getStringExtra("heartbeat");
        packetMode = i.getStringExtra("packet");
        csdMode = i.getStringExtra("csd");
        echoFirstPacket = i.getBooleanExtra("echoFirstPacket", true);
        delayVideoUntilHandshake = i.getBooleanExtra("delayVideoUntilHandshake", true);
        writeConfigFirst = i.getBooleanExtra("writeConfigFirst", true);
        if (heartbeatMode == null) heartbeatMode = "ACK_ZERO";
        if (packetMode == null) packetMode = "RAW";
        if (csdMode == null) csdMode = "AS_PACKET_MODE";

        log("Options in service: " + width + "x" + height
                + " fps=" + fps
                + " bitrate=" + bitrate
                + " waitMs=" + waitMs
                + " heartbeat=" + heartbeatMode
                + " packet=" + packetMode
                + " csd=" + csdMode
                + " echoFirst=" + echoFirstPacket
                + " delayVideo=" + delayVideoUntilHandshake
                + " writeConfigFirst=" + writeConfigFirst);
    }

    private void startVideo(int resultCode, Intent resultData) throws Exception {
        if (running) {
            log("Already running.");
            return;
        }

        if (!openAccessory()) {
            log("Cannot open accessory. Stopping.");
            stopSelf();
            return;
        }

        MediaProjectionManager pm = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = pm.getMediaProjection(resultCode, resultData);
        mediaProjection.registerCallback(new Callback() {
            @Override public void onStop() {
                log("MediaProjection stopped by system.");
                stopSelf();
            }
        }, null);

        if (delayVideoUntilHandshake && waitMs > 0) waitForFirstPacket();

        setupEncoderAndDisplay();

        running = true;
        encoderThread = new Thread(this::encoderLoop, "MirrorLab-Encoder");
        encoderThread.start();

        log("MirrorLab running.");
        updateNotification("Running " + width + "x" + height + " " + packetMode);
    }

    private boolean openAccessory() {
        if (accessoryFd != null && usbInput != null && usbOutput != null) {
            log("openAccessory skipped: USB already open in service.");
            startUsbReader();
            return true;
        }
        UsbAccessory[] list = usbManager.getAccessoryList();
        if (list == null || list.length == 0) {
            log("No USB accessory in service.");
            return false;
        }

        UsbAccessory selected = chooseAccessory(list);
        log("Selected accessory: " + describeAccessory(selected));

        if (!usbManager.hasPermission(selected)) {
            log("Service has no USB permission.");
            return false;
        }

        accessoryFd = usbManager.openAccessory(selected);
        if (accessoryFd == null) {
            log("openAccessory returned null in service.");
            return false;
        }

        usbInput = new FileInputStream(accessoryFd.getFileDescriptor());
        usbOutput = new FileOutputStream(accessoryFd.getFileDescriptor());
        log("openAccessory OK in service.");

        startUsbReader();
        return true;
    }

    private UsbAccessory chooseAccessory(UsbAccessory[] list) {
        for (UsbAccessory a : list) {
            if ("Mirroring".equalsIgnoreCase(safe(a.getManufacturer()))
                    && "gan mirroring".equalsIgnoreCase(safe(a.getModel()))) return a;
        }
        return list[0];
    }

    private void startUsbReader() {
        if (usbInput == null || readingUsb) return;
        readingUsb = true;

        readerThread = new Thread(() -> {
            byte[] buf = new byte[4096];
            log("USB reader started.");

            while (readingUsb) {
                try {
                    int n = usbInput.read(buf);
                    if (n < 0) {
                        log("USB read EOF.");
                        break;
                    }

                    byte[] copy = Arrays.copyOf(buf, n);
                    if (isFourZeros(copy)) {
                        zeroHeartbeats++;
                        log("USB heartbeat 00 00 00 00 #" + zeroHeartbeats);
                        handleHeartbeat();
                    } else {
                        int le = le32(copy, 0);
                        int be = be32(copy, 0);
                        log("USB read " + n + " bytes: " + toHex(copy, Math.min(n, 128))
                                + " le32=" + le + " be32=" + be);
                        if (!firstPacketSeen) {
                            firstPacketSeen = true;
                            firstPacket = copy;
                            if (echoFirstPacket) {
                                try {
                                    writeRaw(copy);
                                    log("Echoed first non-zero packet, " + copy.length + " bytes.");
                                } catch (IOException e) {
                                    log("Echo first packet IOException: " + e);
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    if (readingUsb) log("USB read IOException: " + e);
                    break;
                } catch (Exception e) {
                    log("USB read exception: " + e);
                    break;
                }
            }

            readingUsb = false;
            log("USB reader stopped.");
        }, "MirrorLab-USB-Reader");

        readerThread.start();
    }

    private void handleHeartbeat() {
        try {
            if ("ACK_ZERO".equals(heartbeatMode)) {
                writeRaw(new byte[]{0,0,0,0});
                zeroAcks++;
                log("USB heartbeat ACK #" + zeroAcks);
            } else if ("ACK_ZERO_ONCE".equals(heartbeatMode) && zeroAcks == 0) {
                writeRaw(new byte[]{0,0,0,0});
                zeroAcks++;
                log("USB heartbeat ACK once #" + zeroAcks);
            } else {
                log("USB heartbeat ignored by mode=" + heartbeatMode);
            }
        } catch (IOException e) {
            log("Heartbeat ACK IOException: " + e);
        }
    }

    private void waitForFirstPacket() {
        long start = System.currentTimeMillis();
        log("Waiting for first non-zero USB packet up to " + waitMs + " ms before video START...");
        while (!firstPacketSeen && System.currentTimeMillis() - start < waitMs) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        log("Wait finished: firstPacketSeen=" + firstPacketSeen
                + (firstPacket != null ? " packet=" + toHex(firstPacket, Math.min(firstPacket.length, 64)) : ""));
    }

    private void setupEncoderAndDisplay() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderInputSurface = encoder.createInputSurface();
        encoder.start();

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "lab-mirroring",
                width,
                height,
                1,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                encoderInputSurface,
                null,
                null
        );

        log("Encoder configured and VirtualDisplay created.");
    }

    private void encoderLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean configWritten = false;

        while (running) {
            try {
                int idx = encoder.dequeueOutputBuffer(info, 10_000);

                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue;

                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = encoder.getOutputFormat();
                    log("Output format changed: " + f);
                    if (writeConfigFirst) {
                        writeCsd(f);
                        configWritten = true;
                    }
                    continue;
                }

                if (idx < 0) continue;

                ByteBuffer out = encoder.getOutputBuffer(idx);
                if (out != null && info.size > 0) {
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    byte[] data = new byte[info.size];
                    out.get(data);

                    if (!writeConfigFirst && !configWritten) {
                        log("Skipping explicit CSD because writeConfigFirst=false.");
                        configWritten = true;
                    }

                    writeVideo(data);
                    framesWritten++;
                    if (framesWritten % 30 == 0) {
                        log("Sent frames=" + framesWritten + " bytes=" + bytesWritten + " packet=" + packetMode);
                        updateNotification("Sent frames=" + framesWritten);
                    }
                }

                encoder.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            } catch (Exception e) {
                log("Encoder loop exception: " + e);
                break;
            }
        }

        log("Encoder loop stopped.");
        stopSelf();
    }

    private void writeCsd(MediaFormat f) {
        if ("SKIP_CSD".equals(csdMode)) {
            log("CSD skipped by mode.");
            return;
        }

        try {
            ByteBuffer c0 = f.getByteBuffer("csd-0");
            ByteBuffer c1 = f.getByteBuffer("csd-1");
            if (c0 != null) {
                byte[] b = toBytes(c0);
                if ("RAW_ALWAYS".equals(csdMode)) writeRaw(b);
                else writeVideo(b);
                log("Wrote csd-0 " + b.length + " bytes: " + toHex(b, Math.min(b.length, 64)));
            }
            if (c1 != null) {
                byte[] b = toBytes(c1);
                if ("RAW_ALWAYS".equals(csdMode)) writeRaw(b);
                else writeVideo(b);
                log("Wrote csd-1 " + b.length + " bytes: " + toHex(b, Math.min(b.length, 64)));
            }
        } catch (Exception e) {
            log("writeCsd exception: " + e);
        }
    }

    private void writeVideo(byte[] payload) throws IOException {
        if ("RAW".equals(packetMode)) {
            writeRaw(payload);
            return;
        }

        int len;
        if ("LEN_LE_TOTAL".equals(packetMode) || "LEN_BE_TOTAL".equals(packetMode)) {
            len = payload.length + 4;
        } else {
            len = payload.length;
        }

        byte[] h;
        if ("LEN_BE_TOTAL".equals(packetMode) || "LEN_BE_PAYLOAD".equals(packetMode)) {
            h = new byte[]{(byte)(len >>> 24), (byte)(len >>> 16), (byte)(len >>> 8), (byte)len};
        } else {
            h = new byte[]{(byte)len, (byte)(len >>> 8), (byte)(len >>> 16), (byte)(len >>> 24)};
        }

        writeRaw(h);
        writeRaw(payload);
    }

    private synchronized void writeRaw(byte[] data) throws IOException {
        if (usbOutput == null) throw new IOException("usbOutput is null");
        usbOutput.write(data);
        usbOutput.flush();
        bytesWritten += data.length;
    }

    private void stopWork() {
        running = false;
        readingUsb = false;

        try { if (readerThread != null) readerThread.interrupt(); } catch (Exception ignored) {}
        try { if (encoderThread != null) encoderThread.interrupt(); } catch (Exception ignored) {}

        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;

        try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
        encoder = null;

        try { if (encoderInputSurface != null) encoderInputSurface.release(); } catch (Exception ignored) {}
        encoderInputSurface = null;

        try { if (mediaProjection != null) mediaProjection.stop(); } catch (Exception ignored) {}
        mediaProjection = null;

        try { if (usbInput != null) usbInput.close(); } catch (Exception ignored) {}
        try { if (usbOutput != null) usbOutput.close(); } catch (Exception ignored) {}
        try { if (accessoryFd != null) accessoryFd.close(); } catch (Exception ignored) {}

        usbInput = null;
        usbOutput = null;
        accessoryFd = null;
    }

    private boolean isFourZeros(byte[] b) {
        return b != null && b.length == 4 && b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0;
    }

    private int le32(byte[] b, int off) {
        if (b == null || b.length < off + 4) return -1;
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private int be32(byte[] b, int off) {
        if (b == null || b.length < off + 4) return -1;
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private byte[] toBytes(ByteBuffer bb) {
        ByteBuffer d = bb.duplicate();
        d.position(0);
        byte[] out = new byte[d.remaining()];
        d.get(out);
        return out;
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Projector Mirror Lab")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.notify(701, buildNotification(text));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "Projector Mirror Lab",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(ch);
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

    private String toHex(byte[] data, int maxBytes) {
        if (data == null) return "<null>";
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(data.length, maxBytes);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        if (data.length > limit) sb.append(" ... +").append(data.length - limit).append(" bytes");
        return sb.toString();
    }

    private void log(String msg) {
        String line = timestamp() + "  " + msg;

        try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
            fos.write((line + "\n").getBytes());
        } catch (IOException ignored) {}

        Intent i = new Intent("pl.test1.projectormirrorlab.LOG");
        i.setPackage(getPackageName());
        i.putExtra("line", line);
        sendBroadcast(i);
    }

    private String timestamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
