package app.nyxvault;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** NYX private media viewer. Media never leaves the NYX viewer. */
public class NyxMediaViewerActivity extends Activity {
    private static final String ROOT = "nyx-media";
    private static final String META = "nyx-media.json";
    private File temp;
    private VideoView video;
    private MediaPlayer audioPlayer;
    private TextView status;
    private Button playButton;

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private Button action(String value) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setTextSize(14); b.setBackgroundColor(Color.TRANSPARENT); b.setPadding(dp(14),0,dp(14),0);
        b.setMinHeight(dp(42)); b.setStateListAnimator(null);
        b.setOnTouchListener((v,e)->{ if(e.getAction()==android.view.MotionEvent.ACTION_DOWN){v.animate().scaleX(.96f).scaleY(.96f).setDuration(70).start();}
            else if(e.getAction()==android.view.MotionEvent.ACTION_UP || e.getAction()==android.view.MotionEvent.ACTION_CANCEL){v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();} return false; });
        return b;
    }

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(9,9,9)); getWindow().setNavigationBarColor(Color.rgb(9,9,9));
        String id=getIntent().getStringExtra("media_id"); if(id==null||id.isEmpty()){finish();return;}
        try{render(id);}catch(Exception e){showError(e.getMessage());}
    }

    private void render(String id) throws Exception {
        org.json.JSONObject meta=findMeta(id); if(meta==null)throw new Exception("Media not found");
        String mime=meta.optString("mime","application/octet-stream").toLowerCase();
        String name=meta.optString("name","Private media");
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(9,9,9)); root.setPadding(dp(12),dp(10),dp(12),dp(12));
        LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(4),0,0,dp(8));
        Button close=action("×"); close.setTextSize(27); close.setContentDescription("Close viewer"); close.setOnClickListener(v->finish()); bar.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout titleBox=new LinearLayout(this); titleBox.setOrientation(LinearLayout.VERTICAL); titleBox.setPadding(dp(8),0,dp(8),0);
        TextView title=text(name,16,Color.rgb(242,242,242)); title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.MIDDLE); titleBox.addView(title);
        TextView kind=text(kind(mime),11,Color.rgb(105,105,105)); kind.setPadding(0,dp(2),0,0); titleBox.addView(kind); bar.addView(titleBox,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        TextView lock=text("PRIVATE",10,Color.rgb(110,110,110)); lock.setGravity(Gravity.CENTER); bar.addView(lock,new LinearLayout.LayoutParams(dp(62),dp(38))); root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        FrameLayout stage=new FrameLayout(this); stage.setBackgroundColor(Color.rgb(5,5,5));
        TextView loading=text("Decrypting securely…",13,Color.rgb(120,120,120)); loading.setGravity(Gravity.CENTER); stage.addView(loading,new FrameLayout.LayoutParams(-1,-1)); root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        status=text("Preparing private media…",12,Color.rgb(115,115,115)); status.setGravity(Gravity.CENTER); root.addView(status,new LinearLayout.LayoutParams(-1,dp(34))); setContentView(root);
        stage.animate().alpha(1f).setDuration(120).start();
        new Thread(() -> {
            File decrypted=null; Exception error=null;
            try { decrypted=mediaFile(meta); } catch(Exception e){error=e;}
            File ready=decrypted; Exception err=error;
            runOnUiThread(() -> {
                if(isFinishing()){if(ready!=null)ready.delete();return;}
                if(err!=null){loading.setText(err.getMessage()==null?"Could not open media":err.getMessage());status.setText("Private viewer error");return;}
                temp=ready; loading.setVisibility(View.GONE); buildStage(stage,mime,ready);
            });
        },"nyx-media-decrypt").start();
    }

    private void buildStage(FrameLayout stage,String mime,File file){
        if(mime.startsWith("image/")){
            ImageView image=new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setBackgroundColor(Color.rgb(5,5,5));
            Bitmap bmp=BitmapFactory.decodeFile(file.getAbsolutePath()); if(bmp==null){status.setText("This image format could not be decoded in NYX");return;} image.setImageBitmap(bmp); image.setOnClickListener(v->toggleImageScale(image)); stage.addView(image,new FrameLayout.LayoutParams(-1,-1)); status.setText(kind(mime)+"  •  "+human(file.length()));
        }else if(mime.startsWith("video/")){
            video=new VideoView(this); video.setBackgroundColor(Color.rgb(5,5,5)); video.setMediaController(new MediaController(this)); video.setVideoPath(file.getAbsolutePath());
            video.setOnPreparedListener(mp->{status.setText(kind(mime)+"  •  "+human(file.length()));mp.setLooping(false);}); video.setOnErrorListener((mp,w,e)->{status.setText("NYX cannot play this video format on this device.");return true;}); stage.addView(video,new FrameLayout.LayoutParams(-1,-1));
        }else if(mime.startsWith("audio/")){
            LinearLayout audio=new LinearLayout(this);audio.setOrientation(LinearLayout.VERTICAL);audio.setGravity(Gravity.CENTER);audio.setPadding(dp(20),dp(20),dp(20),dp(20));TextView note=text("♪",72,Color.WHITE);note.setGravity(Gravity.CENTER);audio.addView(note,new LinearLayout.LayoutParams(-1,0,1));playButton=action("Play");audio.addView(playButton,new LinearLayout.LayoutParams(-1,dp(48)));stage.addView(audio,new FrameLayout.LayoutParams(-1,-1));
            try{audioPlayer=new MediaPlayer();audioPlayer.setDataSource(file.getAbsolutePath());audioPlayer.prepare();status.setText(kind(mime)+"  •  "+human(file.length()));playButton.setOnClickListener(v->{if(audioPlayer.isPlaying()){audioPlayer.pause();playButton.setText("Play");}else{audioPlayer.start();playButton.setText("Pause");}});audioPlayer.setOnCompletionListener(mp->playButton.setText("Play"));}catch(Exception e){status.setText("This audio format could not be played in NYX");}
        }else{TextView u=text("This media format isn't supported by NYX yet.\n\n"+mime,14,Color.rgb(170,170,170));u.setGravity(Gravity.CENTER);stage.addView(u,new FrameLayout.LayoutParams(-1,-1));}
    }

    private void toggleImageScale(ImageView image){
        float target=image.getScaleX()>1.01f?1f:1.35f; image.animate().scaleX(target).scaleY(target).setDuration(180).start();
    }
    private String kind(String mime){if(mime.startsWith("image/"))return "IMAGE";if(mime.startsWith("video/"))return "VIDEO";if(mime.startsWith("audio/"))return "AUDIO";return "MEDIA";}
    private String human(long b){if(b<1024)return b+" B";if(b<1048576)return (b/1024)+" KB";return String.format(java.util.Locale.US,"%.1f MB",b/1048576.0);}
    private void showError(String message){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setGravity(Gravity.CENTER);r.setPadding(dp(30),dp(30),dp(30),dp(30));r.setBackgroundColor(Color.rgb(9,9,9));TextView t=text(message==null?"Could not open media":message,15,Color.LTGRAY);t.setGravity(Gravity.CENTER);r.addView(t,new LinearLayout.LayoutParams(-1,-2));Button b=action("Close");b.setOnClickListener(v->finish());r.addView(b,new LinearLayout.LayoutParams(-1,dp(48)));setContentView(r);}
    private org.json.JSONObject findMeta(String id)throws Exception{File f=new File(new File(getFilesDir(),ROOT),META);if(!f.exists())return null;String s=new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);for(String x:s.split("\\n")){if(x.trim().isEmpty())continue;org.json.JSONObject o=new org.json.JSONObject(x);if(id.equals(o.optString("id")))return o;}return null;}
    private File mediaFile(org.json.JSONObject meta)throws Exception{String path=meta.optString("path","");if(!path.isEmpty()){File f=new File(path);if(f.exists())return f;}throw new Exception("Media file is missing");}
    @Override protected void onDestroy(){if(video!=null){video.stopPlayback();video=null;}if(audioPlayer!=null){try{if(audioPlayer.isPlaying())audioPlayer.stop();}catch(Exception ignored){}audioPlayer.release();audioPlayer=null;}/* media is stored directly in NYX and is not a temporary file */super.onDestroy();}
}
