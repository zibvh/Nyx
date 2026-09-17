package app.nyxvault;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Append-only local debug log for the upload pipeline. Never sent anywhere,
 * never shown automatically in the UI — just written to a private file so
 * upload failures are inspectable instead of silent. Capped in size so it
 * can't grow unbounded on a device with lots of failed attempts.
 */
final class NyxUploadDebug {
    private static final String FILE_NAME = "nyx-upload-debug.log";
    private static final long MAX_BYTES = 512L * 1024L; // 512 KB cap, then trims oldest half

    private NyxUploadDebug() {}

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    static synchronized void log(Context ctx, String mediaId, String event, String detail) {
        try {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String line = ts + " | " + safe(mediaId) + " | " + safe(event) + " | " + safe(detail) + "\n";
            File f = file(ctx);
            if (f.exists() && f.length() > MAX_BYTES) trim(f);
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Debug logging must never crash or block an upload.
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ");
    }

    /** Keeps the log bounded by dropping roughly the oldest half when it gets big. */
    private static void trim(File f) {
        try {
            String all = readAll(f);
            int cut = all.length() / 2;
            int nl = all.indexOf('\n', cut);
            String kept = nl >= 0 ? all.substring(nl + 1) : "";
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(kept.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static String readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    static String readLog(Context ctx) {
        try {
            File f = file(ctx);
            if (!f.exists()) return "";
            return readAll(f);
        } catch (Exception e) {
            return "";
        }
    }

    static void clear(Context ctx) {
        try {
            File f = file(ctx);
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
    }
}
