package app.nyxvault;

import android.content.Context;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;


public class NyxUploadWorker extends Worker {
  private static final int CHUNK = 6 * 1024 * 1024;
  private static final int TAG_BITS = 128;
  private static final String KEY_ALIAS = "nyx_media_aes_key_v2";

  public NyxUploadWorker(@NonNull Context context, @NonNull WorkerParameters params){ super(context, params); }

  private File root(){ return new File(getApplicationContext().getFilesDir(), "nyx-media"); }
  private File statusFile(){ return new File(root(), "upload-status.json"); }
  private File debugFile(){ return new File(root(), "nyx-debug.log"); }
  private synchronized void debug(String level, String message){
    try {
      root().mkdirs();
      String ts=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
      try(FileWriter w=new FileWriter(debugFile(), true)){ w.write(ts+" ["+level+"] "+message.replace("\n"," ")+"\n"); }
    } catch(Exception ignored) {}
  }
  private synchronized void status(String id,String name,String state,long done,long total,String error){
    try {
      root().mkdirs();
      JSONObject o=new JSONObject(); o.put("id",id); o.put("name",name); o.put("state",state); o.put("done",done); o.put("total",total); o.put("percent",total>0?Math.min(100,(done*100)/total):0); o.put("updated",System.currentTimeMillis()); if(error!=null)o.put("error",error);
      java.util.List<String> rows=new java.util.ArrayList<>();
      if(statusFile().exists()){try(BufferedReader r=new BufferedReader(new FileReader(statusFile()))){String line;while((line=r.readLine())!=null){if(!line.trim().isEmpty()){try{if(id.equals(new JSONObject(line).optString("id")))continue;}catch(Exception ignored){}rows.add(line);}}}}
      rows.add(o.toString());
      try(FileWriter w=new FileWriter(statusFile(),false)){for(String x:rows){w.write(x);w.write("\n");}}
    } catch(Exception ignored) {}
  }

  @NonNull @Override public Result doWork(){
    if (CloudinaryConfig.BACKEND_URL.startsWith("https://YOUR-") || CloudinaryConfig.BACKEND_TOKEN.startsWith("PASTE_")) return Result.retry();
    File root = new File(getApplicationContext().getFilesDir(), "nyx-media");
    File meta = new File(root, "nyx-media.json");
    if (!meta.exists()) return Result.success();
    boolean retry = false;
    debug("INFO", "Upload worker started");
    try {
      JSONArray arr = new JSONArray("[" + joinLines(meta) + "]");
      boolean changed = false;
      for (int i=0;i<arr.length();i++) {
        JSONObject item=arr.getJSONObject(i);
        if (item.optBoolean("uploaded", false)) { status(item.optString("id"),item.optString("name","media"),"uploaded",1,1,null); continue; }
        File encrypted=new File(root,item.optString("id")+".nyx");
        if (!encrypted.exists()) continue;
        try {
          String id=item.optString("id"), name=item.optString("name","media");
          long total=plaintextSize(encrypted);
          status(id,name,"uploading",0,total,null);
          debug("INFO", "Starting upload: "+name+" ("+total+" bytes)");
          JSONObject signed = signUpload(item);
          uploadChunked(encrypted, item.optString("mime","application/octet-stream"), name, signed, id);
          item.put("uploaded", true);
          item.put("cloud_public_id", signed.optString("public_id"));
          status(id,name,"uploaded",total,total,null);
          debug("INFO", "Upload complete: "+name+" public_id="+signed.optString("public_id"));
          changed = true;
        } catch (Exception ex) {
          String msg=ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();
          status(item.optString("id"),item.optString("name","media"),"failed",0,0,msg);
          debug("ERROR", "Upload failed for "+item.optString("name","media")+": "+msg);
          retry = true;
        }
      }
      if (changed) writeLines(meta, arr);
      return retry ? Result.retry() : Result.success();
    } catch (Exception e) { debug("ERROR", "Worker crashed: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())); return Result.retry(); }
  }

  private JSONObject signUpload(JSONObject item) throws Exception {
    URL url = new URL(CloudinaryConfig.BACKEND_URL.replaceAll("/$", "") + "/api/cloudinary/sign-upload");
    HttpURLConnection c=(HttpURLConnection)url.openConnection();
    c.setDoOutput(true); c.setRequestMethod("POST"); c.setConnectTimeout(20000); c.setReadTimeout(30000);
    c.setRequestProperty("Authorization","Bearer "+CloudinaryConfig.BACKEND_TOKEN);
    c.setRequestProperty("Content-Type","application/json");
    JSONObject body=new JSONObject(); body.put("public_id","media_"+item.optString("id"));
    try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
    int code=c.getResponseCode();
    if(code<200||code>=300){String detail="";try{detail=read(c.getErrorStream());}catch(Exception ignored){}throw new IOException("Backend "+code+(detail.isEmpty()?"":" — "+detail));}
    String response=read(c.getInputStream());
    debug("INFO", "Signing endpoint OK: "+response);
    c.disconnect(); return new JSONObject(response);
  }

  private String joinLines(File f)throws Exception{BufferedReader r=new BufferedReader(new FileReader(f));StringBuilder s=new StringBuilder();String line;boolean first=true;while((line=r.readLine())!=null){if(line.trim().isEmpty())continue;if(!first)s.append(',');s.append(line);first=false;}r.close();return s.toString();}
  private void writeLines(File f,JSONArray a)throws Exception{try(BufferedWriter w=new BufferedWriter(new FileWriter(f,false))){for(int i=0;i<a.length();i++){w.write(a.getJSONObject(i).toString());w.newLine();}}}

  private void uploadChunked(File encrypted,String mime,String name,JSONObject signed,String id)throws Exception{
    long total=plaintextSize(encrypted);
    String uploadId=UUID.randomUUID().toString();
    String resourceType=mime.startsWith("video/")?"video":"image";
    try(FileInputStream fis=new FileInputStream(encrypted)){
      byte[] iv=new byte[12];if(fis.read(iv)!=12)throw new IOException("bad media");
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(TAG_BITS,iv));
      try(CipherInputStream in=new CipherInputStream(fis,cipher)){
        byte[] buf=new byte[CHUNK];long start=0;int n;
        do{n=readChunk(in,buf);if(n<=0)break;long end=start+n-1;boolean done=end==total-1;postChunk(buf,n,start,end,total,uploadId,signed,mime,name,resourceType,done);start=end+1; status(id,name,"uploading",start,total,null); debug("INFO", "Progress "+name+": "+Math.min(100,(start*100)/total)+"%");}while(start<total);
      }
    }
  }

  private int readChunk(InputStream in,byte[] b)throws Exception{int off=0,n;while(off<b.length&&(n=in.read(b,off,b.length-off))!=-1)off+=n;return off;}
  private long plaintextSize(File encrypted)throws Exception{try(FileInputStream fis=new FileInputStream(encrypted)){byte[] iv=new byte[12];if(fis.read(iv)!=12)throw new IOException();Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(TAG_BITS,iv));try(CipherInputStream in=new CipherInputStream(fis,c)){long n=0;byte[] b=new byte[64*1024];int x;while((x=in.read(b))!=-1)n+=x;return n;}}}
  private SecretKey key()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);return ((KeyStore.SecretKeyEntry)ks.getEntry(KEY_ALIAS,null)).getSecretKey();}

  private void postChunk(byte[] data,int len,long start,long end,long total,String uploadId,JSONObject signed,String mime,String name,String resourceType,boolean done)throws Exception{
    String boundary="----NYX"+UUID.randomUUID();
    URL url=new URL("https://api.cloudinary.com/v1_1/"+signed.getString("cloud_name")+"/"+resourceType+"/upload");
    HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setDoOutput(true);c.setRequestMethod("POST");c.setConnectTimeout(30000);c.setReadTimeout(120000);
    c.setRequestProperty("X-Unique-Upload-Id",uploadId);c.setRequestProperty("Content-Range","bytes "+start+"-"+end+"/"+total);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
    try(OutputStream out=c.getOutputStream()){
      field(out,boundary,"api_key",signed.getString("api_key"));field(out,boundary,"timestamp",String.valueOf(signed.getLong("timestamp")));field(out,boundary,"signature",signed.getString("signature"));field(out,boundary,"folder",signed.getString("folder"));field(out,boundary,"public_id",signed.getString("public_id"));field(out,boundary,"type",signed.getString("type"));
      out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+safe(name)+"\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(data,0,len);out.write("\r\n".getBytes(StandardCharsets.UTF_8));out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
    }
    int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("Cloudinary "+code);c.disconnect();
  }
  private void field(OutputStream out,String b,String k,String v)throws Exception{out.write(("--"+b+"\r\nContent-Disposition: form-data; name=\""+k+"\"\r\n\r\n"+v+"\r\n").getBytes(StandardCharsets.UTF_8));}
  private String safe(String s){return s.replace("\"","_").replace("\r","").replace("\n","");}
  private String read(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=x.read(buf))!=-1)b.write(buf,0,n);return b.toString("UTF-8");}}
}
