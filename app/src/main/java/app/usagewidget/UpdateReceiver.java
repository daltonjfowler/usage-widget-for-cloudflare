package app.usagewidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/**
 * Receives the PackageInstaller session status for a self-update. The system sends
 * STATUS_PENDING_USER_ACTION first: it carries the confirm Intent to show, so this
 * launches it (with FLAG_ACTIVITY_NEW_TASK, because a receiver has no activity
 * context). The final status (STATUS_SUCCESS or a failure code) carries no account
 * data, so it is only logged.
 */
public final class UpdateReceiver extends BroadcastReceiver {

    public static final String ACTION_STATUS = "app.usagewidget.UPDATE_STATUS";

    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation")
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }

        Log.i("UsageWidget", "update install status=" + status);
    }
}
