package io.github.daviebaked.kruger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Keeps the app running while a LoRa is connected, so the Bluetooth link and messages carry on
 * with the screen off. Its notification is the LoRa status, which Android shows on the lock screen.
 */
public class StatusService extends Service {
    static final String CHANNEL = "lora_status";
    static final int ID = 4401;
    static volatile String title = "LoRa";
    static volatile String body = "";
    static volatile boolean running = false;
    private PowerManager.WakeLock cpu;

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "LoRa status", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Your LoRa and the starred ones, while connected");
                ch.setShowBadge(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
        }
    }

    static Notification build(Context c) {
        channel(c);
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_lora)
                .setContentTitle(title)
                .setContentText(body.contains("\n") ? body.substring(0, body.indexOf('\n')) : body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build(this);
        try {
            int type = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : 0;
            ServiceCompat.startForeground(this, ID, n, type);
        } catch (Exception e) {
            // Not allowed right now (no Bluetooth permission yet, or started from the background):
            // show it as a normal notification and stop the service.
            try { getSystemService(NotificationManager.class).notify(ID, n); } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        if (cpu == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kruger:lora");
            cpu.setReferenceCounted(false);
        }
        if (!cpu.isHeld()) cpu.acquire(12 * 60 * 60 * 1000L);   // at most a working day
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (cpu != null && cpu.isHeld()) cpu.release();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // app swiped away: the page is gone, so the status would go stale
        stopForeground(true);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
