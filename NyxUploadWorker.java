package app.nyxvault;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

public class NyxUploadWorker extends Worker {
  private static final int CHUNK = 6 * 1024 * 1024;
  public NyxUploadWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}
  private File root(){ File d=new File(getApplicationContext().getExternalFilesDir(null),"NYX"); if(!d.exists())d.mkdirs(); new File(d,".nomedia"); return d; }
  private File meta(){ return new File(root(),"nyx-media.json"); }
  private File status(){ return new File(root(),"upload-status.json"); }
  private File debug(){ return new File(root(),"nyx-debug.log"); }
  private synchronized void log(String level,String msg){try{String ts=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(new Date());try(FileWriter w=new FileWriter(debug(),true)){w.write(ts+" ["+level+"] "+msg.replace("\n"," ")+"\n");}}catch(Exception ignored){}}
  private synchronized void status(String id,String name,String state,long done,long total,String err){try{JSONArray a=new JSONArray();if(status().exists())for(String x:read(status()).split("\n"))if(!x.trim().isEmpty()&&!id.equals(new JSONObject(x).optString("id")))a.put(new JSONObject(x));JSONObject o=new JSONObject();o.put("id",id);o.put("name",name);o.put("state",state);o.put("done",done);o.put("total",total);o.put("percent",total>0?Math.min(100,(done*100)/total):0);if(err!=null)o.put("error",err);a.put(o);try(FileWriter w=new FileWriter(status(),false)){for(int i=0;i<a.length();i++){w.write(a.getJSONObject(i).toString());w.write("\n");}}}catch(Exception ignored){}}
  @NonNull @Override public Result doWork(){
    if(!meta().exists()){log("INFO","No media metadata found");return Result.success();}
    boolean retry=false, changed=false;
    try{JSONArray a=new JSONArray("["+join(meta())+"]");for(int i=0;i<a.length();i++){JSONObject item=a.getJSONObject(i);if(item.optBoolean("uploaded",false))continue;String id=item.optString("id"),name=item.optString("name","media"),path=item.optString("path","");File f=path.isEmpty()?new File(root(),id+ext(item)):new File(path);if(!f.exists()){status(id,name,"failed",0,0,"Local media missing");continue;}try{long total=f.length();status(id,name,"uploading",0,total,null);log("INFO","Direct Cloudinary upload started: "+name+" ("+total+" bytes)");JSONObject result=upload(f,item,id,name);item.put("uploaded",true);item.put("cloud_public_id",result.optString("public_id"));item.put("cloud_resource_type",result.optString("resource_type",resourceType(item.optString("mime"))));status(id,name,"uploaded",total,total,null);log("INFO","Cloudinary upload complete: "+name+" public_id="+result.optString("public_id"));changed=true;}catch(Exception e){String m=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();status(id,name,"failed",0,0,m);log("ERROR","Upload failed for "+name+": "+m);retry=true;}}if(changed)write(meta(),a);return retry?Result.retry():Result.success();}catch(Exception e){log("ERROR","Worker crashed: "+e.getMessage());return Result.retry();}
  }
  private JSONObject upload(File file,JSONObject item,String id,String name)throws Exception{
    String mime=item.optString("mime","application/octet-stream");String type=resourceType(mime);long ts=System.currentTimeMillis()/1000;String publicId="media_"+id;Map<String,String> p=new TreeMap<>();p.put("folder",CloudinaryConfig.FOLDER);p.put("public_id",publicId);p.put("timestamp",String.valueOf(ts));String sig=sign(p);
    if(file.length()<=CHUNK)return post(file,mime,type,p,sig);
    return chunked(file,mime,type,p,sig,id,name);
  }
  private String resourceType(String mime){if(mime!=null&&mime.startsWith("video/"))return "video";if(mime!=null&&mime.startsWith("audio/"))return "video";return "image";}
  private JSONObject chunked(File file,String mime,String type,Map<String,String> p,String sig,String id,String name)throws Exception{String uploadId=UUID.randomUUID().toString();long total=file.length(),start=0;JSONObject last=null;try(FileInputStream in=new FileInputStream(file)){byte[]buf=new byte[CHUNK];int n;while((n=readChunk(in,buf))>0){long end=start+n-1;last=postChunk(buf,n,start,end,total,uploadId,mime,type,p,sig);start=end+1;status(id,name,"uploading",start,total,null);}}if(last==null)throw new IOException("Cloudinary returned no completion response");return last;}
  private JSONObject post(File file,String mime,String type,Map<String,String> p,String sig)throws Exception{String boundary="----NYX"+UUID.randomUUID();URL u=new URL("https://api.cloudinary.com/v1_1/"+CloudinaryConfig.CLOUD_NAME+"/"+type+"/upload");HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setDoOutput(true);c.setRequestMethod("POST");c.setConnectTimeout(30000);c.setReadTimeout(180000);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);try(OutputStream o=c.getOutputStream()){fields(o,boundary,p,sig);partFile(o,boundary,file,mime);o.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));}return response(c);}
  private JSONObject postChunk(byte[]data,int len,long start,long end,long total,String uploadId,String mime,String type,Map<String,String>p,String sig)throws Exception{String boundary="----NYX"+UUID.randomUUID();URL u=new URL("https://api.cloudinary.com/v1_1/"+CloudinaryConfig.CLOUD_NAME+"/"+type+"/upload");HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setDoOutput(true);c.setRequestMethod("POST");c.setConnectTimeout(30000);c.setReadTimeout(180000);c.setRequestProperty("X-Unique-Upload-Id",uploadId);c.setRequestProperty("Content-Range","bytes "+start+"-"+end+"/"+total);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);try(OutputStream o=c.getOutputStream()){fields(o,boundary,p,sig);o.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk.bin\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));o.write(data,0,len);o.write("\r\n".getBytes(StandardCharsets.UTF_8));o.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));}return response(c);}
  private void fields(OutputStream o,String b,Map<String,String>p,String sig)throws Exception{field(o,b,"api_key",CloudinaryConfig.API_KEY);field(o,b,"timestamp",p.get("timestamp"));field(o,b,"folder",p.get("folder"));field(o,b,"public_id",p.get("public_id"));field(o,b,"signature",sig);}
  private void field(OutputStream o,String b,String n,String v)throws Exception{o.write(("--"+b+"\r\nContent-Disposition: form-data; name=\""+n+"\"\r\n\r\n"+v+"\r\n").getBytes(StandardCharsets.UTF_8));}
  private void partFile(OutputStream o,String b,File f,String mime)throws Exception{String name=f.getName().replaceAll("[^A-Za-z0-9._-]","_");o.write(("--"+b+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));try(FileInputStream in=new FileInputStream(f)){byte[]buf=new byte[64*1024];int n;while((n=in.read(buf))!=-1)o.write(buf,0,n);}o.write("\r\n".getBytes(StandardCharsets.UTF_8));}
  private JSONObject response(HttpURLConnection c)throws Exception{int code=c.getResponseCode();String body=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());if(code<200||code>=300)throw new IOException("Cloudinary "+code+(body.isEmpty()?"":" — "+body));if(body.isEmpty())throw new IOException("Cloudinary returned an empty response");c.disconnect();return new JSONObject(body);}
  private String sign(Map<String,String>p)throws Exception{StringBuilder s=new StringBuilder();for(Map.Entry<String,String>e:p.entrySet()){if(s.length()>0)s.append('&');s.append(e.getKey()).append('=').append(e.getValue());}s.append(CloudinaryConfig.API_SECRET);MessageDigest md=MessageDigest.getInstance("SHA-1");byte[]d=md.digest(s.toString().getBytes(StandardCharsets.UTF_8));StringBuilder h=new StringBuilder();for(byte x:d)h.append(String.format(Locale.US,"%02x",x));return h.toString();}
  private int readChunk(InputStream in,byte[]b)throws Exception{int off=0,n;while(off<b.length&&(n=in.read(b,off,b.length-off))!=-1)off+=n;return off;}
  private String join(File f)throws Exception{String s=read(f).trim();return s;}
  private String read(File f)throws Exception{if(!f.exists())return "";StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new FileReader(f))){String x;while((x=r.readLine())!=null){if(!x.trim().isEmpty()){if(s.length()>0)s.append(',');s.append(x);}}}return s.toString();}
  private void write(File f,JSONArray a)throws Exception{try(BufferedWriter w=new BufferedWriter(new FileWriter(f,false))){for(int i=0;i<a.length();i++){w.write(a.getJSONObject(i).toString());w.newLine();}}}
  private String ext(JSONObject i){String n=i.optString("name","media");int d=n.lastIndexOf('.');return d>0?n.substring(d):".bin";}
}
