package app.usagewidget;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i) {
        if(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
            RefreshJob.schedule(c); Notifications.createChannels(c); RefreshJob.scheduleUpdateCheck(c); UsageWidget.updateAll(c);
        }
    }
}
