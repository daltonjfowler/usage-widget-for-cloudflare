package app.usagewidget;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Self-hosted, sideload-safe updates for a phone with no Play Store. check() reads
 * latest.json from the update host over HTTPS and compares its version_code to the
 * running build. download() streams the APK into the cache, verifies its SHA-256
 * against latest.json, then verifies that the downloaded APK is signed with the
 * same certificate as the running app; on any mismatch it deletes the file and
 * reports, and never installs. install() hands the verified APK to the platform
 * PackageInstaller, which shows the system install dialog. Nothing downloads or
 * installs on its own; that happens only on a tap.
 *
 * Privacy: the check and the download are plain GETs with no query string and no
 * header carrying any device id or account data. Nothing about the Cloudflare
 * account ever reaches this code, and the host keeps no logs.
 *
 * Seams for tests: check() and download() take a Source (so no real socket is
 * opened), and the certificate reader is a swappable static (so the signer
 * comparison is exercised without a real signed APK or a Keystore).
 */
public final class Updater {

    /** The outcome of a check against latest.json. */
    public enum Status { UP_TO_DATE, AVAILABLE, NO_NETWORK, BAD_SHAPE }

    /** The outcome of a download plus its two verifications. */
    public enum DownloadStatus { DONE, NO_NETWORK, CHECKSUM_MISMATCH, SIGNATURE_MISMATCH }

    static final String LATEST = "latest.json";
    static final String UPDATE_APK = "update.apk";

    private static final int CONNECT_MS = 15000;
    private static final int READ_MS = 30000;
    private static final int MAX_JSON = 64 * 1024;

    private Updater() { }

    /** The parsed latest.json for an available update. */
    public static final class Info {
        public int versionCode;
        public String versionName;
        public String sha256;
        public long size;
        public String apk;
        public String notes;
    }

    public static final class CheckResult {
        public final Status status;
        public final Info info;    // AVAILABLE only, else null

        CheckResult(Status status, Info info) {
            this.status = status;
            this.info = info;
        }
    }

    public static final class DownloadResult {
        public final DownloadStatus status;
        public final File file;    // DONE only, else null

        DownloadResult(DownloadStatus status, File file) {
            this.status = status;
            this.file = file;
        }
    }

    /** The network seam. Production opens an HttpURLConnection; tests return canned
     *  bytes. open() returns a stream the caller closes. */
    public interface Source {
        InputStream open(String url) throws IOException;
    }

    /** A download progress callback, invoked on the calling (Repository.IO) thread. */
    public interface Progress {
        void onProgress(int percent);
    }

    /** The signer-certificate seam, swapped in tests. */
    interface CertReader {
        String archiveSha256(Context ctx, File apk);
        String runningSha256(Context ctx);
    }

    static CertReader certReader = new PlatformCertReader();

    // ---- check ---------------------------------------------------------------

    /**
     * Read latest.json and compare its version_code to the running build. A transport
     * failure is NO_NETWORK; a body that is not the expected JSON shape is BAD_SHAPE;
     * a newer version_code is AVAILABLE with the parsed Info, otherwise UP_TO_DATE.
     * Runs on Repository.IO.
     */
    public static CheckResult check(int currentCode, String base, Source source) {
        String text;
        try (InputStream in = source.open(join(base, LATEST))) {
            text = readCapped(in);
        } catch (IOException e) {
            return new CheckResult(Status.NO_NETWORK, null);
        }
        try {
            JSONObject o = new JSONObject(text);
            Info info = new Info();
            info.versionCode = o.getInt("version_code");
            info.versionName = o.getString("version_name");
            info.sha256 = o.getString("sha256");
            info.size = o.getLong("size");
            info.apk = o.getString("apk");
            info.notes = o.optString("notes", "");
            if (info.versionCode > currentCode) {
                return new CheckResult(Status.AVAILABLE, info);
            }
            return new CheckResult(Status.UP_TO_DATE, null);
        } catch (JSONException e) {
            return new CheckResult(Status.BAD_SHAPE, null);
        }
    }

    /**
     * The daily job's whole job: check latest.json against the running build and,
     * for a genuinely newer version_code not notified before, post one notification.
     * Nothing downloads or installs. Runs on Repository.IO. Returns the check status.
     */
    public static Status checkAndNotify(Context ctx, int currentCode) {
        Store store = new Store(ctx);
        CheckResult r = check(currentCode, store.updateUrl(), new HttpSource());
        if (r.status == Status.AVAILABLE) {
            notifyIfNew(ctx, store, r.info);
        }
        return r.status;
    }

    // ---- download and verify -------------------------------------------------

    /**
     * Stream the APK into cacheDir/update.apk, then verify: first the SHA-256 against
     * latest.json, then that the downloaded APK's signer certificate matches the
     * running app's. On any failure the file is deleted and nothing is installed.
     * Runs on Repository.IO.
     */
    public static DownloadResult download(Context ctx, String base, Info info,
                                          Source source, Progress progress) {
        File file = new File(ctx.getCacheDir(), UPDATE_APK);
        try (InputStream in = source.open(join(base, info.apk));
             OutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            int lastPct = -1;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (progress != null && info.size > 0) {
                    int pct = (int) Math.min(100L, total * 100L / info.size);
                    if (pct != lastPct) {
                        progress.onProgress(pct);
                        lastPct = pct;
                    }
                }
            }
        } catch (IOException e) {
            deleteQuietly(file);
            return new DownloadResult(DownloadStatus.NO_NETWORK, null);
        }

        String actual = sha256Hex(file);
        if (actual == null || !actual.equalsIgnoreCase(info.sha256)) {
            deleteQuietly(file);
            return new DownloadResult(DownloadStatus.CHECKSUM_MISMATCH, null);
        }

        String archiveCert = certReader.archiveSha256(ctx, file);
        String runningCert = certReader.runningSha256(ctx);
        if (archiveCert == null || runningCert == null || !archiveCert.equalsIgnoreCase(runningCert)) {
            deleteQuietly(file);
            return new DownloadResult(DownloadStatus.SIGNATURE_MISMATCH, null);
        }

        return new DownloadResult(DownloadStatus.DONE, file);
    }

    // ---- install -------------------------------------------------------------

    /** True when the app may request package installs; false sends the user to the
     *  unknown-sources settings screen first (handled by the caller). */
    public static boolean canInstall(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * Hand the verified APK to the platform PackageInstaller. The system shows its own
     * install dialog; STATUS_PENDING_USER_ACTION is delivered to UpdateReceiver, which
     * launches that dialog. This only ever runs on a downloaded, checksum-verified,
     * signer-verified file.
     */
    public static void install(Context ctx, File file) throws IOException {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
            new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            try (OutputStream out = session.openWrite("usagewidget", 0, file.length());
                 InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }
            Intent intent = new Intent(ctx, UpdateReceiver.class)
                .setAction(UpdateReceiver.ACTION_STATUS);
            // FLAG_MUTABLE is required here: PackageInstaller writes EXTRA_STATUS, and
            // for the confirm step EXTRA_INTENT, into this PendingIntent's intent
            // before sending it, so the PendingIntent must be mutable.
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pi.getIntentSender());
        } finally {
            session.close();
        }
    }

    // ---- notification (from the daily job) -----------------------------------

    /**
     * Post the one update notification for a newer version, at most once per
     * version_code (last_update_notified_code guards it). Returns true when it posted.
     */
    public static boolean notifyIfNew(Context ctx, Store store, Info info) {
        if (info == null || info.versionCode <= store.lastUpdateNotifiedCode()) {
            return false;
        }
        Notifications.postUpdate(ctx, info.versionName, info.versionCode);
        store.setLastUpdateNotifiedCode(info.versionCode);
        return true;
    }

    // ---- helpers -------------------------------------------------------------

    /** A short human size for the available line, for display only. */
    public static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    static String join(String base, String path) {
        String b = base == null ? "" : base.trim();
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + "/" + path;
    }

    private static String readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > MAX_JSON) {
                break;
            }
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    static String sha256Hex(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    static String sha256HexBytes(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format(Locale.US, "%02X", x));
        }
        return sb.toString();
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            // A failed delete just leaves a stale cache file; the next download
            // overwrites it. Never fatal.
            file.delete();
        }
    }

    /** Production network: a plain HTTPS GET, no query string, no data-bearing header. */
    public static final class HttpSource implements Source {
        @Override public InputStream open(String url) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setInstanceFollowRedirects(true);
            int status = c.getResponseCode();
            if (status != 200) {
                c.disconnect();
                throw new IOException("HTTP " + status);
            }
            return c.getInputStream();
        }
    }

    /** Production certificate reader: the signer SHA-256 of an APK file and of the
     *  running app, through PackageManager. */
    static final class PlatformCertReader implements CertReader {
        @Override public String archiveSha256(Context ctx, File apk) {
            PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(
                apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
            return certSha256(pi);
        }

        @Override public String runningSha256(Context ctx) {
            try {
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                return certSha256(pi);
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }

        private static String certSha256(PackageInfo pi) {
            if (pi == null || pi.signingInfo == null) {
                return null;
            }
            SigningInfo si = pi.signingInfo;
            Signature[] signers = si.getApkContentsSigners();
            if (signers == null || signers.length == 0) {
                return null;
            }
            return sha256HexBytes(signers[0].toByteArray());
        }
    }
}
