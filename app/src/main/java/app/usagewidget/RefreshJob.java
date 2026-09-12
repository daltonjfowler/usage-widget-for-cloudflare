package app.usagewidget;

import android.app.job.*;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ConcurrentHashMap;

public final class RefreshJob extends JobService {
    private final ConcurrentHashMap<JobParameters,FutureTask<Void>> tasks=new ConcurrentHashMap<>();
    public static void schedule(Context c) {
        if(new AppWidgetManagerHelper(c).count()==0) return;
        c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(301,new ComponentName(c,RefreshJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setPeriodic(3*60*60*1000L,30*60*1000L).setBackoffCriteria(30*60*1000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }
    public static void now(Context c) {
        c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(302,new ComponentName(c,RefreshJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0)
            .setBackoffCriteria(30*60*1000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }
    public static void cancel(Context c) { c.getSystemService(JobScheduler.class).cancelAll(); }
    @Override public boolean onStartJob(JobParameters p) {
        FutureTask<Void> task=new FutureTask<>(()->{
            boolean ok=Repository.refresh(getApplicationContext());
            if(tasks.remove(p)!=null) jobFinished(p,!ok);
            return null;
        });
        tasks.put(p,task); Repository.IO.execute(task); return true;
    }
    @Override public boolean onStopJob(JobParameters p) { FutureTask<Void> task=tasks.remove(p); if(task!=null) task.cancel(true); return true; }
    private static final class AppWidgetManagerHelper {
        final Context c; AppWidgetManagerHelper(Context c) { this.c=c; }
        int count() { return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c,UsageWidget.class)).length; }
    }
}
