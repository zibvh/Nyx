package app.nyxvault;

import android.content.Context;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.json.JSONObject;

/** Runs Cloudinary uploads independently of the WebView/app UI. */
public class NyxUploadWorker extends Worker {
    private static final String CLOUDINARY_CLOUD_NAME = "dpinyff2";
    private static final String CLOUDINARY_API_KEY = "731819118728455";
    private static final String CLOUDINARY_API_SECRET = "KyDKRfs_eY0i1c3r6QsXTHUrJu4";
    private static final String ROOT = "nyx-media";
    private static final String META = "nyx-media.json";

    public NyxUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

    @NonNull @Override public Result doWork() {
        String id = getInputData().getString("media_id");
        if (id == null || id.trim().isEmpty()) return Result.failure();
        try {
            JSONObject meta = findMeta(id);
            if (meta == null) return Result.failure();
            if (meta.optBoolean("uploaded", false)) return Result.success();
            File file = mediaFile(meta);
            if (!file.isFile() || !file.canRead()) return Result.failure();
            String mime = meta.optString("mime", "application/octet-stream");
            String resource = mime.startsWith("video/") ? "video" : (mime.startsWith("audio/") ? "video" : "image");
            String publicId = id;
            long size = file.length();
            if (size > 100L * 1024L * 1024L) uploadLarge(id, file, resource, publicId, size);
            else uploadMultipart(id, file, resource, publicId, size);
            return Result.success();
        } catch (Exception e) {
            return isRetryable(e) ? Result.retry() : Result.failure();
        }
    }

    private boolean isRetryable(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return m.contains("timeout") || m.contains("connection") || m.contains("network") || m.contains("reset") || m.contains("503") || m.contains("502") || m.contains("429");
    }

    private void uploadMultipart(String id, File file, String resource, String publicId, long size) throws Exception {
        String endpoint = "https://api.cloudinary.com/v1_1/" + CLOUDINARY_CLOUD_NAME + "/" + resource + "/upload";
        String boundary = "----NYX" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setConnectTimeout(30000); c.setReadTimeout(120000); c.setDoOutput(true); c.setRequestMethod("POST");
        applyAuth(c); c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (java.io.OutputStream out = c.getOutputStream()) {
            writeField(out, boundary, "folder", "nyx-vault");
            writeField(out, boundary, "public_id", publicId);
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            try (InputStream in = new FileInputStream(file)) { byte[] buf = new byte[128 * 1024]; int n; while ((n = in.read(buf)) != -1) out.write(buf, 0, n); }
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode(); String response = read(c); c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("Cloudinary HTTP " + code + " " + response);
        JSONObject j = new JSONObject(response);
        String returnedId = j.optString("public_id"); if (returnedId.isEmpty()) throw new Exception("Cloudinary returned no public_id");
        markUploaded(id, returnedId, j.optString("secure_url", ""));
    }

    private void uploadLarge(String id, File file, String resource, String publicId, long size) throws Exception {
        String endpoint = "https://api.cloudinary.com/v1_1/" + CLOUDINARY_CLOUD_NAME + "/" + resource + "/upload";
        String uploadId = UUID.randomUUID().toString();
        final long chunk = 20L * 1024L * 1024L;
        long offset = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (offset < size) {
                long length = Math.min(chunk, size - offset);
                byte[] data = new byte[(int) length];
                int got = 0;
                while (got < data.length) { int n = in.read(data, got, data.length - got); if (n < 0) break; got += n; }
                if (got != data.length) throw new Exception("Could not read upload chunk");
                HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
                c.setConnectTimeout(30000); c.setReadTimeout(180000); c.setDoOutput(true); c.setRequestMethod("POST");
                applyAuth(c); c.setRequestProperty("Content-Type", "application/octet-stream");
                c.setRequestProperty("X-Unique-Upload-Id", uploadId);
                c.setRequestProperty("Content-Range", "bytes " + offset + "-" + (offset + length - 1) + "/" + size);
                c.setRequestProperty("Content-Disposition", "form-data; name=\"file\"; filename=\"" + file.getName() + "\"");
                if (offset == 0) c.setRequestProperty("X-Unique-Upload-Id", uploadId);
                try (java.io.OutputStream out = c.getOutputStream()) { out.write(data); }
                int code = c.getResponseCode(); String response = read(c); c.disconnect();
                if (code < 200 || code >= 300) throw new Exception("Cloudinary HTTP " + code + " " + response);
                if (offset + length >= size) {
                    JSONObject j = new JSONObject(response);
                    String returnedId = j.optString("public_id");
                    if (returnedId.isEmpty()) throw new Exception("Cloudinary returned no public_id");
                    markUploaded(id, returnedId, j.optString("secure_url", ""));
                }
                offset += length;
            }
        }
    }

    private void writeField(java.io.OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void applyAuth(HttpURLConnection c) {
        String raw = CLOUDINARY_API_KEY + ":" + CLOUDINARY_API_SECRET;
        c.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    private String read(HttpURLConnection c) throws Exception {
        InputStream in;
        try { in = c.getInputStream(); } catch (Exception e) { in = c.getErrorStream(); }
        if (in == null) return "";
        try (InputStream x = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n; while ((n = x.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private JSONObject findMeta(String id) throws Exception {
        File f = new File(new File(getApplicationContext().getFilesDir(), ROOT), META);
        if (!f.exists()) return null;
        String raw = readAll(f).replace("\\n", "\n");
        for (String line : raw.split("\\n")) {
            if (line.trim().isEmpty()) continue;
            try { JSONObject o = new JSONObject(line); if (id.equals(o.optString("id"))) return o; } catch (Exception ignored) {}
        }
        return null;
    }

    private File mediaFile(JSONObject meta) throws Exception {
        String path = meta.optString("path", "");
        if (!path.isEmpty()) { File f = new File(path); if (f.isFile() && f.canRead()) return f; }
        String id = meta.optString("id", ""); String name = meta.optString("name", "media"); String mime = meta.optString("mime", "");
        File d = getApplicationContext().getExternalFilesDir(null);
        if (d != null) {
            File nyx = new File(d, "NYX");
            File exact = new File(nyx, id + suffix(name, mime)); if (exact.isFile() && exact.canRead()) return exact;
            File[] all = nyx.listFiles(); if (all != null) for (File f : all) if (f.isFile() && f.getName().startsWith(id + ".")) return f;
        }
        throw new Exception("Media file is missing");
    }

    private String suffix(String name, String mime) {
        if (name != null) { int d = name.lastIndexOf('.'); if (d > 0 && d < name.length() - 1) return name.substring(d).replaceAll("[^A-Za-z0-9.]", ""); }
        if (mime.equals("image/jpeg")) return ".jpg"; if (mime.equals("image/png")) return ".png"; if (mime.equals("image/webp")) return ".webp"; if (mime.equals("image/gif")) return ".gif";
        if (mime.equals("video/mp4")) return ".mp4"; if (mime.equals("video/webm")) return ".webm"; if (mime.equals("video/3gpp")) return ".3gp"; if (mime.equals("video/quicktime")) return ".mov";
        if (mime.equals("audio/mpeg")) return ".mp3"; if (mime.equals("audio/mp4")) return ".m4a"; if (mime.equals("audio/wav")) return ".wav"; if (mime.equals("audio/ogg")) return ".ogg"; return ".bin";
    }

    private void markUploaded(String id, String publicId, String secureUrl) throws Exception {
        File f = new File(new File(getApplicationContext().getFilesDir(), ROOT), META);
        if (!f.exists()) return;
        List<String> rows = new ArrayList<>();
        for (String x : readAll(f).replace("\\n", "\n").split("\\n")) {
            if (x.trim().isEmpty()) continue;
            JSONObject o = new JSONObject(x);
            if (id.equals(o.optString("id"))) { o.put("uploaded", true); o.put("cloudinary_public_id", publicId); o.put("cloudinary_url", secureUrl); }
            rows.add(o.toString());
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { out.write(String.join("\n", rows).getBytes(StandardCharsets.UTF_8)); }
    }

    private String readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
