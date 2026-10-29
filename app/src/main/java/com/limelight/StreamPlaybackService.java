package com.limelight;

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
import java.lang.ref.WeakReference;

/** Keeps an existing, user-started stream alive. Never starts/reconnects a stream by itself. */
public final class StreamPlaybackService extends Service {
    private static final String CHANNEL = "active_stream";
    private static final String STOP = "com.limelight.STOP_BACKGROUND_STREAM";
    private static final int NOTIFICATION = 1001;
    // All owner access happens on the application main thread. Do not retain an Activity.
    private static WeakReference<Game> owner = new WeakReference<>(null);
    private PowerManager.WakeLock cpuLock;

    public static boolean start(Game game) {
        owner = new WeakReference<>(game);
        try {
            Intent intent = new Intent(game, StreamPlaybackService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) game.startForegroundService(intent);
            else game.startService(intent);
            return true;
        }
        catch (RuntimeException e) {
            owner.clear();
            LimeLog.warning("Background streaming service unavailable: " + e.getClass().getSimpleName());
            return false;
        }
    }

    public static void stop(Game game) {
        if (owner.get() != game) return;
        owner.clear(); game.stopService(new Intent(game, StreamPlaybackService.class));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @android.annotation.SuppressLint("WakelockTimeout")
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Game game = owner.get();
        if (game == null) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && STOP.equals(intent.getAction())) {
            game.stopBackgroundStream(); stopSelf(); return START_NOT_STICKY;
        }
        try {
            NotificationManager manager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                        getString(R.string.background_stream_channel), NotificationManager.IMPORTANCE_LOW));
            }
            int immutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            Intent resume = new Intent(game.getIntent()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent open = PendingIntent.getActivity(this, 0, resume, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, StreamPlaybackService.class).setAction(STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | immutable);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                    new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
            Notification notification = builder.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(getString(R.string.background_stream_title))
                    .setContentText(getString(R.string.background_stream_notification))
                    .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_TRANSPORT)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.background_stream_disconnect), stop).build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            }
            else startForeground(NOTIFICATION, notification);
            if (cpuLock == null) {
                cpuLock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, getPackageName()+":ActiveStream");
                cpuLock.setReferenceCounted(false);
                // Session-scoped, not sticky. stopConnection, explicit notification stop,
                // task removal and onDestroy all terminate the service and release this lock.
                cpuLock.acquire();
            }
        }
        catch (RuntimeException e) {
            LimeLog.warning("Cannot keep background stream active: " + e.getClass().getSimpleName());
            game.backgroundStreamUnavailable(); stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        Game game = owner.get();
        if (game != null) game.stopBackgroundStream();
        stopSelf();
    }

    @Override public void onDestroy() {
        stopForeground(true);
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        Game game = owner.get(); owner.clear();
        if (game != null) game.stopBackgroundStream();
        super.onDestroy();
    }
}
