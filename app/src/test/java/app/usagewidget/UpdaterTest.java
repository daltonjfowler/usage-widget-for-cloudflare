package app.usagewidget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The in-app updater at SDK 31 and 35, with a fake Source (no socket) and a swapped
 * certificate reader (no real signed APK, no Keystore). Covers the check states, the
 * checksum and signer verifications that delete the file and never install, the one
 * notification per version_code, and the daily job's network and period.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {31, 35})
public final class UpdaterTest {

    private static final String BASE = "https://usagewidget-updates.example.dev";

    private Context ctx;
    private Store store;
    private Updater.CertReader realCertReader;

    @Before public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        store = new Store(ctx);
        store.prefs.edit().clear().commit();
        realCertReader = Updater.certReader;
        Notifications.createChannels(ctx);
        updateApk().delete();
    }

    @After public void tearDown() {
        Updater.certReader = realCertReader;
        updateApk().delete();
    }

    // ---- check ---------------------------------------------------------------

    @Test public void newerVersionCodeIsAvailable() {
        Updater.CheckResult r = Updater.check(3, BASE,
            textSource(json(4, "1.2", "ABC", 4200, "UsageWidget.apk", "In-app updates")));
        assertEquals(Updater.Status.AVAILABLE, r.status);
        assertNotNull(r.info);
        assertEquals(4, r.info.versionCode);
        assertEquals("1.2", r.info.versionName);
        assertEquals(4200, r.info.size);
    }

    @Test public void equalVersionCodeIsUpToDate() {
        Updater.CheckResult r = Updater.check(3, BASE,
            textSource(json(3, "1.1", "ABC", 4200, "UsageWidget.apk", "")));
        assertEquals(Updater.Status.UP_TO_DATE, r.status);
        assertNull(r.info);
    }

    @Test public void malformedJsonIsBadShape() {
        Updater.CheckResult r = Updater.check(3, BASE, textSource("this is not json"));
        assertEquals(Updater.Status.BAD_SHAPE, r.status);
        assertNull(r.info);
    }

    @Test public void transportFailureIsNoNetwork() {
        Updater.CheckResult r = Updater.check(3, BASE, throwingSource());
        assertEquals(Updater.Status.NO_NETWORK, r.status);
        assertNull(r.info);
    }

    // ---- download and verify -------------------------------------------------

    @Test public void checksumMismatchDeletesFileAndReports() {
        byte[] apk = "pretend-apk-bytes".getBytes(StandardCharsets.UTF_8);
        Updater.certReader = fakeCert("AA", "AA");   // would match, but is never reached
        Updater.Info info = info(4, "NOTTHERIGHTHASH", apk.length);

        Updater.DownloadResult dr = Updater.download(ctx, BASE, info, bytesSource(apk), null);

        assertEquals(Updater.DownloadStatus.CHECKSUM_MISMATCH, dr.status);
        assertNull(dr.file);
        assertFalse("the bad download is deleted", updateApk().exists());
    }

    @Test public void signerMismatchDeletesFileAndReports() {
        byte[] apk = "pretend-apk-bytes".getBytes(StandardCharsets.UTF_8);
        String realHash = Updater.sha256HexBytes(apk);
        Updater.certReader = fakeCert("ARCHIVECERT", "RUNNINGCERT");   // different signers
        Updater.Info info = info(4, realHash, apk.length);

        Updater.DownloadResult dr = Updater.download(ctx, BASE, info, bytesSource(apk), null);

        assertEquals(Updater.DownloadStatus.SIGNATURE_MISMATCH, dr.status);
        assertNull(dr.file);
        assertFalse("the unsigned-for-us download is deleted", updateApk().exists());
    }

    @Test public void matchingChecksumAndSignerIsDone() {
        byte[] apk = "pretend-apk-bytes".getBytes(StandardCharsets.UTF_8);
        String realHash = Updater.sha256HexBytes(apk);
        Updater.certReader = fakeCert("SAMECERT", "samecert");   // equalsIgnoreCase matches
        Updater.Info info = info(4, realHash, apk.length);

        Updater.DownloadResult dr = Updater.download(ctx, BASE, info, bytesSource(apk), null);

        assertEquals(Updater.DownloadStatus.DONE, dr.status);
        assertNotNull(dr.file);
        assertTrue(dr.file.exists());
    }

    // ---- notification once per version ---------------------------------------

    @Test public void notifiesOncePerVersionCode() {
        Updater.Info info = info(4, "ABC", 4200);

        assertTrue(Updater.notifyIfNew(ctx, store, info));
        assertNotNull("posted the first time", notification());
        assertEquals(4, store.lastUpdateNotifiedCode());

        // Dismiss it; a second check for the same version must not re-post.
        Notifications.cancel(ctx, Notifications.ID_UPDATE);
        assertFalse(Updater.notifyIfNew(ctx, store, info));
        assertNull("not posted a second time for the same version", notification());
    }

    // ---- daily job -----------------------------------------------------------

    @Test public void checkUpdateJobIsDailyOnAnyNetwork() {
        RefreshJob.scheduleUpdateCheck(ctx);
        JobInfo job = pendingJob(RefreshJob.UPDATE_CHECK);
        assertNotNull(job);
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.getNetworkType());
        assertEquals(24 * 60 * 60 * 1000L, job.getIntervalMillis());
        assertTrue(job.isPersisted());
    }

    // ---- helpers -------------------------------------------------------------

    private File updateApk() {
        return new File(ctx.getCacheDir(), Updater.UPDATE_APK);
    }

    private android.app.Notification notification() {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return shadowOf(nm).getNotification(Notifications.ID_UPDATE);
    }

    private JobInfo pendingJob(int id) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        for (JobInfo j : js.getAllPendingJobs()) {
            if (j.getId() == id) {
                return j;
            }
        }
        return null;
    }

    private static Updater.Source textSource(String body) {
        return url -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static Updater.Source bytesSource(byte[] bytes) {
        return url -> new ByteArrayInputStream(bytes);
    }

    private static Updater.Source throwingSource() {
        return url -> { throw new IOException("no route to host"); };
    }

    private static Updater.CertReader fakeCert(String archive, String running) {
        return new Updater.CertReader() {
            @Override public String archiveSha256(Context c, File f) { return archive; }
            @Override public String runningSha256(Context c) { return running; }
        };
    }

    private static String json(int code, String name, String sha, long size, String apk, String notes) {
        return "{\"version_code\":" + code
            + ",\"version_name\":\"" + name + "\""
            + ",\"sha256\":\"" + sha + "\""
            + ",\"size\":" + size
            + ",\"apk\":\"" + apk + "\""
            + ",\"notes\":\"" + notes + "\"}";
    }

    private static Updater.Info info(int code, String sha, long size) {
        Updater.Info i = new Updater.Info();
        i.versionCode = code;
        i.versionName = "1.2";
        i.sha256 = sha;
        i.size = size;
        i.apk = "UsageWidget.apk";
        i.notes = "notes";
        return i;
    }
}
