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

/** Фоновая служба: уведомление с кнопками и защита от засыпания, пока играет музыка. */
public class PlaybackService extends Service {

    static final String CHANNEL = "playback";
    static final int ID = 7;
    static volatile String title = "Тембр";
    static volatile String artist = "";
    static volatile boolean playing = false;
    static PlaybackService instance;

    private PowerManager.WakeLock wake;
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
        if (a != null) {
            if (a.equals("close")) {
                if (MainActivity.current != null) MainActivity.current.command("pause");
                playing = false;
                stopSelf();
                return START_NOT_STICKY;
            }
            if (MainActivity.current != null) MainActivity.current.command(a);
        }
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ID, n);
        }
        locks();
        return START_NOT_STICKY;
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
         .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev), "Назад", action("prev", 1)).build())
         .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play), playing ? "Пауза" : "Играть", action("toggle", 2)).build())
         .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), "Дальше", action("next", 3)).build())
         .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
