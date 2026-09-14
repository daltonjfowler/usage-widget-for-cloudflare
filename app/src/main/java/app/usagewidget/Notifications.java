package app.usagewidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * The one place notifications are built. This app otherwise has no notifications, so
 * the single low-importance Updates channel is created here, once, before anything
 * posts (creating an existing channel is a no-op). The one notification names an
 * available version and, when tapped, opens the app scrolled to the Updates section.
 * It carries no account data.
 */
final class Notifications {

    static final String CH_STATUS = "status";
    static final int ID_UPDATE = 1001;

    private Notifications() { }

    /** Create the low-importance Updates channel. Idempotent; safe to call every time. */
    static void createChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel status = new NotificationChannel(
            CH_STATUS, ctx.getString(R.string.channel_status),
            NotificationManager.IMPORTANCE_LOW);
        status.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(status);
    }

    /**
     * The one update-available notification, on the low-importance Updates channel. It
     * names the version and, when tapped, opens the app at the Updates section. Posted
     * at most once per version_code by the daily job. It carries no account data.
     */
    static void postUpdate(Context ctx, String versionName, int versionCode) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        createChannels(ctx);
        Notification n = new Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_usage)
            .setContentTitle(ctx.getString(R.string.update_notif_title, versionName, versionCode))
            .setAutoCancel(true)
            .setContentIntent(updateContentIntent(ctx))
            .build();
        nm.notify(ID_UPDATE, n);
    }

    static void cancel(Context ctx, int id) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(id);
        }
    }

    /** Open the app at the Updates section from the update notification tap. */
    private static PendingIntent updateContentIntent(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class)
            .putExtra(MainActivity.EXTRA_SHOW_UPDATES, true)
            .setData(Uri.parse("usagewidget://update"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, ID_UPDATE, i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
