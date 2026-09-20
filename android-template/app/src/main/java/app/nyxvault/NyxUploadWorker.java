package app.nyxvault;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Background Backblaze B2 uploader. TEST BUILD ONLY: credentials are embedded in the APK. */
public class NyxUploadWorker extends Worker {
    private static final String B2_KEY_ID = "00334f3ef70d4020000000003";
    private static final String B2_APPLICATION_KEY = "K003DHluvEsxXpIW9lvhB6uTrMYNEKc";
    private static final String B2_BUCKET_NAME = "Nyxoria";
    private static final String ROOT = "nyx-media";
    private static final String META = "nyx-media.json";
    public NyxUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }
    @NonNull @Override public Result doWork() {
        String id=getInputData().getString("media_id"); if(id==null||id.trim().isEmpty()) return Result.failure();
        try { if(B2_BUCKET_NAME.startsWith("YOUR_")) return Result.failure(); JSONObject meta=findMeta(id); if(meta==null)return Result.retry(); if(meta.optBoolean("uploaded",false))return Result.success(); File file=mediaFile(meta); if(!file.isFile()||!file.canRead())return Result.retry(); upload(id,file,meta.optString("mime","application/octet-stream")); return Result.success(); }
        catch(Exception e){return isRetryable(e)?Result.retry():Result.failure();}
    }
    private void upload(String id,File file,String mime)throws Exception{
        JSONObject auth=postJson("https://api.backblazeb2.com/b2api/v2/b2_authorize_account",null,basic(B2_KEY_ID,B2_APPLICATION_KEY));
        String apiUrl=auth.getString("apiUrl"),authToken=auth.getString("authorizationToken"),accountId=auth.getString("accountId");
        JSONObject lb=postJson(apiUrl+"/b2api/v2/b2_list_buckets",new JSONObject().put("accountId",accountId).put("bucketName",B2_BUCKET_NAME).toString(),"Bearer "+authToken);
        JSONArray buckets=lb.optJSONArray("buckets");String bucketId=null;if(buckets!=null)for(int i=0;i<buckets.length();i++){JSONObject b=buckets.getJSONObject(i);if(B2_BUCKET_NAME.equals(b.optString("bucketName"))){bucketId=b.optString("bucketId");break;}}
        if(bucketId==null||bucketId.isEmpty())throw new Exception("B2 bucket not found");
        JSONObject up=postJson(apiUrl+"/b2api/v2/b2_get_upload_url",new JSONObject().put("bucketId",bucketId).toString(),"Bearer "+authToken);
        String sha1=sha1(file),fileName="nyx-vault/"+id+".bin"; HttpURLConnection c=(HttpURLConnection)new URL(up.getString("uploadUrl")).openConnection();
        c.setDoOutput(true);c.setRequestMethod("POST");c.setConnectTimeout(30000);c.setReadTimeout(180000);c.setFixedLengthStreamingMode(file.length());
        c.setRequestProperty("Authorization",up.getString("authorizationToken"));c.setRequestProperty("X-Bz-File-Name",URLEncoder.encode(fileName,"UTF-8").replace("+","%20"));c.setRequestProperty("Content-Type",mime);c.setRequestProperty("X-Bz-Content-Sha1",sha1);
        try(OutputStream out=c.getOutputStream();InputStream in=new FileInputStream(file)){byte[] buf=new byte[128*1024];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
        int code=c.getResponseCode();String response=read(c);c.disconnect();if(code<200||code>=300)throw new Exception("Backblaze HTTP "+code+" "+response);JSONObject j=new JSONObject(response);markUploaded(id,j.optString("fileId"),fileName);
    }
    private JSONObject postJson(String url,String body,String auth)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setDoOutput(true);c.setRequestMethod("POST");c.setConnectTimeout(30000);c.setReadTimeout(60000);c.setRequestProperty("Content-Type","application/json");if(auth!=null)c.setRequestProperty("Authorization",auth);if(body!=null){byte[] b=body.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(b.length);try(OutputStream out=c.getOutputStream()){out.write(b);}}else c.connect();int code=c.getResponseCode();String response=read(c);c.disconnect();if(code<200||code>=300)throw new Exception("Backblaze HTTP "+code+" "+response);return new JSONObject(response);}
    private String basic(String a,String b){return "Basic "+android.util.Base64.encodeToString((a+":"+b).getBytes(StandardCharsets.UTF_8),android.util.Base64.NO_WRAP);}
    private String sha1(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-1");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[128*1024];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format(Locale.US,"%02x",x));return s.toString();}
    private boolean isRetryable(Exception e){String m=e.getMessage()==null?"":e.getMessage().toLowerCase(Locale.US);return m.contains("timeout")||m.contains("connection")||m.contains("network")||m.contains("reset")||m.contains("broken pipe")||m.contains("503")||m.contains("502")||m.contains("500")||m.contains("429");}
    private String read(HttpURLConnection c)throws Exception{InputStream in;try{in=c.getInputStream();}catch(Exception e){in=c.getErrorStream();}if(in==null)return "";try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=x.read(b))!=-1)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}}
    private JSONObject findMeta(String id)throws Exception{File f=new File(new File(getApplicationContext().getFilesDir(),ROOT),META);if(!f.exists())return null;String raw=readAll(f);for(String line:raw.split("\n")){if(line.trim().isEmpty())continue;try{JSONObject o=new JSONObject(line);if(id.equals(o.optString("id")))return o;}catch(Exception ignored){}}return null;}
    private File mediaFile(JSONObject meta)throws Exception{String path=meta.optString("path","");if(!path.isEmpty()){File f=new File(path);if(f.isFile()&&f.canRead())return f;}String id=meta.optString("id","");String name=meta.optString("name","media");String mime=meta.optString("mime","");File d=getApplicationContext().getExternalFilesDir(null);if(d!=null){File nyx=new File(d,"NYX");File exact=new File(nyx,id+suffix(name,mime));if(exact.isFile()&&exact.canRead())return exact;File[] all=nyx.listFiles();if(all!=null)for(File f:all)if(f.isFile()&&f.getName().startsWith(id+"."))return f;}throw new Exception("Media file is missing");}
    private String suffix(String name,String mime){if(name!=null){int d=name.lastIndexOf('.');if(d>0&&d<name.length()-1)return name.substring(d).replaceAll("[^A-Za-z0-9.]","");}if(mime.equals("image/jpeg"))return ".jpg";if(mime.equals("image/png"))return ".png";if(mime.equals("image/webp"))return ".webp";if(mime.equals("video/mp4"))return ".mp4";return ".bin";}
    private void markUploaded(String id,String fileId,String fileName)throws Exception{File f=new File(new File(getApplicationContext().getFilesDir(),ROOT),META);if(!f.exists())return;List<String> rows=new ArrayList<>();for(String x:readAll(f).split("\n")){if(x.trim().isEmpty())continue;JSONObject o=new JSONObject(x);if(id.equals(o.optString("id"))){o.put("uploaded",true);o.put("b2_file_id",fileId);o.put("b2_file_name",fileName);}rows.add(o.toString());}try(FileOutputStream out=new FileOutputStream(f)){out.write(String.join("\n",rows).getBytes(StandardCharsets.UTF_8));}}
    private String readAll(File f)throws Exception{try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}}
}
