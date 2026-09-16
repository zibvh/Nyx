package app.nyxvault;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import android.util.Base64;
import android.security.keystore.KeyProperties;

/** NYX's private, in-app media viewer. It never launches another media app. */
public class NyxMediaViewerActivity extends Activity {
    private static final String ROOT = "nyx-media";
    private static final String META = "nyx-media.json";
    private static final String PREFS = "nyx-secure";
    private static final String KEY_ALIAS = "nyx_media_aes_key_v2";
    private static final int GCM_TAG_BITS = 128;
    private File temp;
    private VideoView video;
    private MediaPlayer audioPlayer;
    private TextView status;

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(0xFF090909);
        getWindow().setNavigationBarColor(0xFF090909);
        String id = getIntent().getStringExtra("media_id");
        if (id == null || id.isEmpty()) { finish(); return; }
        try { render(id); } catch (Exception e) { showError(e.getMessage()); }
    }

    private void render(String id) throws Exception {
        org.json.JSONObject meta = findMeta(id);
        if (meta == null) throw new Exception("Media not found");
        String mime = meta.optString("mime", "application/octet-stream").toLowerCase();
        String name = meta.optString("name", "Private media");
        temp = decryptToCache(id, "nyx_view_" + id + "_" + System.currentTimeMillis() + suffix(name, mime));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF090909);
        root.setPadding(18, 18, 18, 18);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(0xFFEFEFEF);
        title.setTextSize(16);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(v -> finish());
        bar.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bar);

        status = new TextView(this);
        status.setTextColor(0xFF777777);
        status.setTextSize(12);
        status.setPadding(0, 6, 0, 8);
        root.addView(status);

        if (mime.startsWith("image/")) {
            ImageView image = new ImageView(this);
            image.setBackgroundColor(0xFF050505);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Bitmap bmp = BitmapFactory.decodeFile(temp.getAbsolutePath());
            if (bmp == null) throw new Exception("This image format could not be decoded");
            image.setImageBitmap(bmp);
            root.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            status.setText(mime);
        } else if (mime.startsWith("video/")) {
            video = new VideoView(this);
            video.setBackgroundColor(0xFF050505);
            video.setMediaController(new MediaController(this));
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".nyxfiles", temp);
            video.setVideoURI(uri);
            video.setOnPreparedListener(mp -> {
                status.setText(formatInfo(mime, temp.length()));
                mp.setLooping(false);
            });
            video.setOnErrorListener((mp, what, extra) -> { status.setText("This video format could not be played in NYX."); return true; });
            root.addView(video, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else if (mime.startsWith("audio/")) {
            TextView icon = new TextView(this);
            icon.setText("♪"); icon.setTextColor(0xFFEFEFEF); icon.setTextSize(72); icon.setGravity(Gravity.CENTER);
            root.addView(icon, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            Button play = new Button(this); play.setText("Play"); root.addView(play);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".nyxfiles", temp);
            audioPlayer = MediaPlayer.create(this, uri);
            if (audioPlayer == null) throw new Exception("This audio format could not be played in NYX");
            status.setText(formatInfo(mime, temp.length()));
            play.setOnClickListener(v -> {
                if (audioPlayer.isPlaying()) { audioPlayer.pause(); play.setText("Play"); }
                else { audioPlayer.start(); play.setText("Pause"); }
            });
            audioPlayer.setOnCompletionListener(mp -> play.setText("Play"));
        } else {
            TextView unsupported = new TextView(this);
            unsupported.setText("NYX does not support this media format yet.\n\n" + mime);
            unsupported.setTextColor(0xFFAAAAAA);
            unsupported.setTextSize(15);
            unsupported.setGravity(Gravity.CENTER);
            root.addView(unsupported, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        setContentView(root);
    }

    private String formatInfo(String mime, long bytes) { return mime + "  •  " + human(bytes); }
    private String human(long b) { if (b < 1024) return b + " B"; if (b < 1048576) return (b/1024) + " KB"; return String.format(java.util.Locale.US, "%.1f MB", b/1048576.0); }

    private void showError(String message) {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER); root.setPadding(30,30,30,30); root.setBackgroundColor(0xFF090909);
        TextView t = new TextView(this); t.setText(message == null ? "Could not open media" : message); t.setTextColor(0xFFCCCCCC); t.setTextSize(15); t.setGravity(Gravity.CENTER); root.addView(t);
        Button b = new Button(this); b.setText("Close"); b.setOnClickListener(v -> finish()); root.addView(b);
        setContentView(root);
    }

    private org.json.JSONObject findMeta(String id) throws Exception {
        File f = new File(new File(getFilesDir(), ROOT), META); if (!f.exists()) return null;
        String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        for (String x : s.split("\\n")) { if (x.trim().isEmpty()) continue; org.json.JSONObject o = new org.json.JSONObject(x); if (id.equals(o.optString("id"))) return o; }
        return null;
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
    }

    private File decryptToCache(String id, String filename) throws Exception {
        File enc = new File(new File(getFilesDir(), ROOT), id + ".nyx");
        if (!enc.exists()) throw new Exception("Encrypted media is missing");
        File out = new File(getCacheDir(), filename);
        try (FileInputStream fis = new FileInputStream(enc)) {
            byte[] iv = new byte[12]; if (fis.read(iv) != 12) throw new Exception("Invalid encrypted media");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            try (CipherInputStream cis = new CipherInputStream(fis, c); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[32768]; int n; while ((n = cis.read(buf)) != -1) fos.write(buf, 0, n);
            }
        }
        return out;
    }

    private String suffix(String name, String mime) {
        if (name != null) { int d = name.lastIndexOf('.'); if (d > 0 && d < name.length()-1) return name.substring(d).replaceAll("[^A-Za-z0-9.]", ""); }
        if (mime.equals("image/jpeg")) return ".jpg"; if (mime.equals("image/png")) return ".png"; if (mime.equals("image/webp")) return ".webp"; if (mime.equals("image/gif")) return ".gif";
        if (mime.equals("video/mp4")) return ".mp4"; if (mime.equals("video/webm")) return ".webm"; if (mime.equals("video/3gpp")) return ".3gp"; if (mime.equals("video/quicktime")) return ".mov";
        if (mime.equals("audio/mpeg")) return ".mp3"; if (mime.equals("audio/mp4")) return ".m4a"; if (mime.equals("audio/wav")) return ".wav"; return ".bin";
    }

    @Override protected void onDestroy() {
        if (video != null) { video.stopPlayback(); video = null; }
        if (audioPlayer != null) { try { audioPlayer.stop(); } catch (Exception ignored) {} audioPlayer.release(); audioPlayer = null; }
        if (temp != null && temp.exists()) temp.delete();
        super.onDestroy();
    }
}
