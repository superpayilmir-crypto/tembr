package app.tembr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

/** Фоновая служба: уведомление с кнопками и защита от засыпания, пока играет музыка. */
public class PlaybackService extends Service {

    static final String CHANNEL = "playback";
    static final int ID = 7;
    static volatile String title = "Тембр";
    static volatile String artist = "";
    static volatile boolean playing = false;
    static PlaybackService instance;

    private PowerManager.WakeLock wake;
    // будильник играет сам Android, не встроенный браузер — так он работает и на заблокированном экране
    static MediaPlayer alarmPlayer;
    private String[] alarmUrls = new String[0];
    private int alarmIdx = 0;
    private float alarmVol = 0.15f;
    private final Handler h = new Handler(Looper.getMainLooper());
    static boolean alarmRinging() { return alarmPlayer != null; }
    private WifiManager.WifiLock wifi;

    static void refresh(Context c) {
        PlaybackService s = instance;
        if (s != null) s.update();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Воспроизведение", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tembr:play");
        wake.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tembr:wifi");
            wifi.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if ("alarm".equals(a)) {
            android.content.SharedPreferences sp = getSharedPreferences("alarm", MODE_PRIVATE);
            alarmUrls = sp.getString("urls", "").split("\n");
            title = "Будильник · " + sp.getString("title", "радио");
            artist = "Тембр · нажмите, чтобы открыть";
            playing = true;
            startAsForeground();
            alarmIdx = 0;
            startAlarmStream();
            if (MainActivity.current != null) MainActivity.current.command("alarmui");
            return START_NOT_STICKY;
        }
        if ("alarm_stop".equals(a)) {
            stopAlarmSound();
            playing = false;
            update();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (a != null) {
            if (alarmPlayer != null && ("toggle".equals(a) || "pause".equals(a))) { stopAlarmSound(); playing = false; update(); return START_NOT_STICKY; }
            if (a.equals("close")) {
                if (MainActivity.current != null) MainActivity.current.command("pause");
                playing = false;
                stopSelf();
                return START_NOT_STICKY;
            }
            if (MainActivity.current != null) MainActivity.current.command(a);
        }
        startAsForeground();
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ID, n);
        }
        locks();
    }

    private void startAlarmStream() {
        releasePlayer();
        while (alarmIdx < alarmUrls.length && alarmUrls[alarmIdx].trim().isEmpty()) alarmIdx++;
        if (alarmIdx >= alarmUrls.length) { alarmIdx = 0; if (alarmUrls.length == 0) return; }
        final String url = alarmUrls[alarmIdx].trim();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(url);
            alarmVol = 0.15f;
            mp.setVolume(alarmVol, alarmVol);
            mp.setOnPreparedListener(m -> { m.start(); fadeIn(); });
            mp.setOnErrorListener((m, what, extra) -> { nextAlarmStream(); return true; });
            mp.setOnCompletionListener(m -> nextAlarmStream());
            alarmPlayer = mp;
            mp.prepareAsync();
            // если станция молчит 20 секунд — берём следующую
            h.postDelayed(() -> { if (alarmPlayer == mp && !mp.isPlaying()) nextAlarmStream(); }, 20000);
        } catch (Exception e) {
            nextAlarmStream();
        }
    }

    private int fails = 0;
    private void nextAlarmStream() {
        fails++;
        if (fails > alarmUrls.length * 2 + 2) { releasePlayer(); return; }
        alarmIdx = (alarmIdx + 1) % Math.max(1, alarmUrls.length);
        h.postDelayed(this::startAlarmStream, 800);
    }

    private void fadeIn() {
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (alarmPlayer == null) return;
                alarmVol = Math.min(1f, alarmVol + 0.03f);
                try { alarmPlayer.setVolume(alarmVol, alarmVol); } catch (Exception ignored) {}
                if (alarmVol < 1f) h.postDelayed(this, 2000);
            }
        }, 2000);
    }

    private void releasePlayer() {
        if (alarmPlayer != null) {
            try { alarmPlayer.stop(); } catch (Exception ignored) {}
            try { alarmPlayer.release(); } catch (Exception ignored) {}
            alarmPlayer = null;
        }
    }

    void stopAlarmSound() {
        h.removeCallbacksAndMessages(null);
        fails = 0;
        releasePlayer();
    }

    static void stopAlarm(android.content.Context c) {
        PlaybackService s = instance;
        if (s != null) { s.stopAlarmSound(); playing = false; s.update(); }
    }

    void update() {
        getSystemService(NotificationManager.class).notify(ID, build());
        locks();
    }

    private void locks() {
        if (playing) {
            if (!wake.isHeld()) wake.acquire(6 * 60 * 60 * 1000L);
            if (wifi != null && !wifi.isHeld()) wifi.acquire();
        } else {
            if (wake.isHeld()) wake.release();
            if (wifi != null && wifi.isHeld()) wifi.release();
        }
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(a);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_note)
         .setContentTitle(title)
         .setContentText(artist)
         .setContentIntent(content)
         .setOngoing(playing)
         .setShowWhen(false)
         .setVisibility(Notification.VISIBILITY_PUBLIC)
         .setDeleteIntent(action("close", 9))
         ;
        if (alarmPlayer != null) {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_pause), "Стоп", action("alarm_stop", 5)).build())
             .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0))
             .setCategory(Notification.CATEGORY_ALARM);
            return b.build();
        }
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev), "Назад", action("prev", 1)).build())
         .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play), playing ? "Пауза" : "Играть", action("toggle", 2)).build())
         .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), "Дальше", action("next", 3)).build())
         .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    @Override
    public void onDestroy() {
        stopAlarmSound();
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
