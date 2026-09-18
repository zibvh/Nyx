package app.nyxvault;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

public class NyxMediaViewerActivity extends Activity {
    private static final String KEY_ALIAS = "nyx-vault-key-v1";
    private static final int GCM_TAG_BITS = 128;
    private ExoPlayer player;
    private File tempFile;

    private int dp(float v){ return (int)(v*getResources().getDisplayMetrics().density+.5f); }
    private TextView text(String v,float s,int c){ TextView t=new TextView(this); t.setText(v); t.setTextSize(s); t.setTextColor(c); return t; }

    @Override public void onCreate(@Nullable Bundle saved){
        super.onCreate(saved);
        if(getSharedPreferences("nyx-secure", MODE_PRIVATE).getBoolean("blockScreenCapture", true)){
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }
        getWindow().setStatusBarColor(Color.rgb(7,7,7));
        getWindow().setNavigationBarColor(Color.rgb(7,7,7));
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        String id = getIntent().getStringExtra("media_id");
        if(id == null || id.isEmpty()){ finish(); return; }

        // Decrypt and render on a background thread so we don't block the UI
        new Thread(() -> {
            try {
                JSONObject meta = findMeta(id);
                if(meta == null) throw new Exception("missing metadata");
                File decrypted = decryptToTemp(meta);
                tempFile = decrypted;
                new Handler(Looper.getMainLooper()).post(() -> {
                    try { render(meta, decrypted); }
                    catch(Exception e){ showError(); }
                });
            } catch(Exception e){
                new Handler(Looper.getMainLooper()).post(this::showError);
            }
        }).start();
    }

    private File decryptToTemp(JSONObject meta) throws Exception {
        File encrypted = mediaFile(meta);
        boolean isEncrypted = meta.optBoolean("encrypted", true);

        File tmpDir = new File(getCacheDir(), "nyx-tmp");
        if(!tmpDir.exists()) tmpDir.mkdirs();
        // Use mime to give the temp file a real extension so ExoPlayer can detect format
        String mime = meta.optString("mime","").toLowerCase();
        String ext = mime.startsWith("image/png") ? ".png"
                : mime.startsWith("image/webp") ? ".webp"
                : mime.startsWith("image/") ? ".jpg"
                : mime.startsWith("video/mp4") ? ".mp4"
                : mime.startsWith("video/") ? ".mp4"
                : ".tmp";
        File tmp = new File(tmpDir, meta.optString("id","tmp") + ext);

        // Don't redecrypt if we already have a valid temp file from this session
        if(tmp.exists() && tmp.length() > 0) return tmp;

        try(InputStream in = isEncrypted ? openDecryptStream(encrypted) : new FileInputStream(encrypted);
            FileOutputStream out = new FileOutputStream(tmp)){
            byte[] buf = new byte[128*1024]; int n;
            while((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return tmp;
    }

    private InputStream openDecryptStream(File encryptedFile) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if(!ks.containsAlias(KEY_ALIAS)) throw new Exception("Keystore key not found");
        SecretKey key = ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        FileInputStream fis = new FileInputStream(encryptedFile);
        byte[] iv = new byte[12];
        int read = fis.read(iv);
        if(read != 12){ fis.close(); throw new Exception("Corrupt vault file (missing IV)"); }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new CipherInputStream(fis, cipher);
    }

    private void render(JSONObject meta, File file) throws Exception {
        String mime = meta.optString("mime","").toLowerCase();
        String name = meta.optString("name","Media");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(7,7,7));

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        root.addView(stage, new FrameLayout.LayoutParams(-1,-1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10),dp(10),dp(10),dp(10));
        TextView back = text("‹",38,Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(54),dp(54)));
        TextView title = text(name,15,Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(title, new LinearLayout.LayoutParams(0,dp(54),1));
        root.addView(top, new FrameLayout.LayoutParams(-1,dp(74),Gravity.TOP));

        setContentView(root);
        if(mime.startsWith("image/")) buildImage(stage, file);
        else if(mime.startsWith("video/")) buildVideo(stage, file);
        else showUnsupported(stage);
    }

    private void buildImage(FrameLayout stage, File file){
        ImageView image = new ImageView(this);
        image.setBackgroundColor(Color.BLACK);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        o.inSampleSize = sampleSize(o.outWidth,o.outHeight,2200,2200); o.inJustDecodeBounds = false;
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        if(bmp == null){ showUnsupported(stage); return; }
        image.setImageBitmap(bmp);
        stage.addView(image, new FrameLayout.LayoutParams(-1,-1));
    }

    private void buildVideo(FrameLayout stage, File file){
        PlayerView pv = new PlayerView(this);
        pv.setUseController(true);
        pv.setControllerShowTimeoutMs(2500);
        pv.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        pv.setBackgroundColor(Color.BLACK);
        stage.addView(pv, new FrameLayout.LayoutParams(-1,-1));
        player = new ExoPlayer.Builder(this).build();
        pv.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)));
        player.prepare();
        player.setPlayWhenReady(false);
        player.addListener(new Player.Listener(){
            @Override public void onPlayerError(PlaybackException error){
                runOnUiThread(() -> {
                    TextView e = text("This video could not be played",15,Color.WHITE);
                    e.setGravity(Gravity.CENTER);
                    stage.addView(e, new FrameLayout.LayoutParams(-1,-1));
                });
            }
        });
    }

    private void showUnsupported(FrameLayout stage){
        TextView t = text("This media could not be opened",15,Color.WHITE);
        t.setGravity(Gravity.CENTER);
        stage.addView(t, new FrameLayout.LayoutParams(-1,-1));
    }
    private void showError(){
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL); r.setGravity(Gravity.CENTER);
        r.setBackgroundColor(Color.rgb(7,7,7));
        TextView t = text("Media unavailable",16,Color.WHITE);
        t.setGravity(Gravity.CENTER); r.addView(t, new LinearLayout.LayoutParams(-1,dp(60)));
        TextView b = text("‹ Back",15,Color.WHITE);
        b.setGravity(Gravity.CENTER); b.setOnClickListener(v -> finish());
        r.addView(b, new LinearLayout.LayoutParams(-1,dp(54)));
        setContentView(r);
    }

    private int sampleSize(int w,int h,int maxW,int maxH){
        int s=1; while(w/(s*2)>=maxW && h/(s*2)>=maxH) s*=2; return s;
    }

    private JSONObject findMeta(String id) throws Exception {
        File f = new File(new File(getFilesDir(),"nyx-media"),"nyx-media.json");
        if(!f.exists()) return null;
        String raw = readText(f);
        for(String x : raw.split("\n")){
            if(x.trim().isEmpty()) continue;
            try{ JSONObject o = new JSONObject(x); if(id.equals(o.optString("id"))) return o; }
            catch(Exception ignored){}
        }
        return null;
    }

    private File mediaFile(JSONObject meta) throws Exception {
        String path = meta.optString("path","");
        if(!path.isEmpty()){ File f = new File(path); if(f.isFile() && f.canRead()) return f; }
        throw new Exception("vault file not found");
    }

    private String readText(File f) throws Exception {
        try(InputStream in = new FileInputStream(f);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()){
            byte[] b = new byte[8192]; int n;
            while((n = in.read(b)) != -1) out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Override protected void onDestroy(){
        if(player != null){ player.release(); player = null; }
        // Clean up temp file when viewer closes
        if(tempFile != null && tempFile.exists()) tempFile.delete();
        super.onDestroy();
    }
}
