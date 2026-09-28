package app.tembr;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Системный будильник: в назначенное время будит телефон и просит Тембр включить радио. */
public class AlarmReceiver extends BroadcastReceiver {

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction("app.tembr.ALARM");
        return PendingIntent.getBroadcast(c, 42, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void schedule(Context c, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent p = pi(c);
        try {
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            PendingIntent show = PendingIntent.getActivity(c, 43, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), p);
        } catch (SecurityException e) {
            // нет разрешения на точные будильники — ставим почти точный
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
            else am.set(AlarmManager.RTC_WAKEUP, at, p);
        }
    }

    static void save(Context c, long at, String urls, String title, boolean daily) {
        c.getSharedPreferences("alarm", Context.MODE_PRIVATE).edit()
                .putLong("at", at).putString("urls", urls).putString("title", title).putBoolean("daily", daily).apply();
        schedule(c, at);
    }

    static void cancel(Context c) {
        c.getSharedPreferences("alarm", Context.MODE_PRIVATE).edit().clear().apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi(c));
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        android.content.SharedPreferences sp = c.getSharedPreferences("alarm", Context.MODE_PRIVATE);
        if (sp.getString("urls", "").isEmpty()) return;
        if (sp.getBoolean("daily", false)) {
            long next = sp.getLong("at", System.currentTimeMillis()) + 24L * 60 * 60 * 1000;
            while (next < System.currentTimeMillis() + 60000) next += 24L * 60 * 60 * 1000;
            sp.edit().putLong("at", next).apply();
            schedule(c, next);
        }
        Intent svc = new Intent(c, PlaybackService.class).setAction("alarm");
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(svc); else c.startService(svc);
        } catch (Exception ignored) {}
    }
}
