package app.nyxvault;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Size;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

@CapacitorPlugin(name = "NyxVault")
public class NyxVaultPlugin extends Plugin {
    private static final ExecutorService IO_EXECUTOR = Executors.newCachedThreadPool();
    private static final String ROOT = "nyx-media";
    private static final String META = "nyx-media.json";
    private static final String PREFS = "nyx-secure";
    private static final String KEY_ALIAS = "nyx_media_aes_key_v2";
    private static final int PBKDF2_ITERATIONS = 150000;
    private static final int GCM_TAG_BITS = 128;
    private static final int PICK_CODE = 7137;

    private File root() { File d = new File(getContext().getExternalFilesDir(null), "NYX"); if (!d.exists()) d.mkdirs(); ensureNyxNoMediaMarker(); return d; }
    private File metaFile() { return new File(root(), META); }
    private android.content.SharedPreferences prefs() { return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    @PluginMethod
    public void ping(PluginCall call) {
        JSObject ret = new JSObject(); ret.put("ok", true); call.resolve(ret);
    }

    @PluginMethod
    public void saveCredential(PluginCall call) {
        String secret = call.getString("secret", "").trim();
        String pin = call.getString("pin", "");
        if (secret.isEmpty() || !pin.matches("[0-9]{6}")) { call.reject("Invalid credential"); return; }
        try {
            SecureRandom r = new SecureRandom();
            byte[] salt = new byte[16]; r.nextBytes(salt);
            String verifier = hash(pin + ":" + secret, salt);
            String secretVerifier = hash(secret, salt);
            prefs().edit()
                    .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString("verifier", verifier)
                    .putString("secretVerifier", secretVerifier)
                    .putBoolean("biometricEnabled", false)
                    .putBoolean("removeOriginal", false)
                    .apply();
            ensureNyxNoMediaMarker();
            call.resolve();
        } catch (Exception e) { call.reject("Credential setup failed"); }
    }

    @PluginMethod
    public void changeCredential(PluginCall call) {
        String oldSecret = call.getString("oldSecret", "").trim();
        String oldPin = call.getString("oldPin", "");
        String newSecret = call.getString("newSecret", "").trim();
        String newPin = call.getString("newPin", "");
        if (oldSecret.isEmpty() || !oldPin.matches("[0-9]{6}") || newSecret.isEmpty() || !newPin.matches("[0-9]{6}")) { call.reject("Invalid credential"); return; }
        try {
            if (!verify(oldSecret, oldPin)) { call.reject("Current credential is wrong"); return; }
            byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
            prefs().edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString("verifier", hash(newPin + ":" + newSecret, salt))
                    .putString("secretVerifier", hash(newSecret, salt)).apply();
            call.resolve();
        } catch (Exception e) { call.reject("Could not change credential"); }
    }

    private String hash(String value, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(value.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
        byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        spec.clearPassword();
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private boolean verify(String secret, String pin) throws Exception {
        String saltB64 = prefs().getString("salt", null);
        String verifier = prefs().getString("verifier", null);
        if (saltB64 == null || verifier == null || !pin.matches("[0-9]{6}")) return false;
        return MessageDigest.isEqual(Base64.decode(verifier, Base64.NO_WRAP), Base64.decode(hash(pin + ":" + secret, Base64.decode(saltB64, Base64.NO_WRAP)), Base64.NO_WRAP));
    }

    @PluginMethod
    public void verifySecret(PluginCall call) {
        String secret = call.getString("secret", "");
        try {
            String saltB64 = prefs().getString("salt", null), verifier = prefs().getString("secretVerifier", null);
            boolean ok = saltB64 != null && verifier != null && MessageDigest.isEqual(Base64.decode(verifier, Base64.NO_WRAP), Base64.decode(hash(secret, Base64.decode(saltB64, Base64.NO_WRAP)), Base64.NO_WRAP));
            JSObject ret = new JSObject(); ret.put("ok", ok); call.resolve(ret);
        } catch (Exception e) { call.reject("Verification failed"); }
    }

    @PluginMethod
    public void verifyCredential(PluginCall call) {
        try { JSObject ret = new JSObject(); ret.put("ok", verify(call.getString("secret", ""), call.getString("pin", ""))); call.resolve(ret); }
        catch (Exception e) { call.reject("Verification failed"); }
    }

    @PluginMethod
    public void setSecureScreen(PluginCall call) { call.resolve(); }

    @PluginMethod
    public void clearTempCache(PluginCall call) {
        try {
            File cache = getContext().getCacheDir();
            File[] files = cache.listFiles();
            if (files != null) for (File f : files) if (f.getName().startsWith("nyx_open_") || f.getName().startsWith("nyx_thumb_")) f.delete();
            call.resolve();
        } catch (Exception e) { call.reject("Could not clear temporary files"); }
    }

    @PluginMethod
    public void getPrivateSettings(PluginCall call) {
        JSObject ret = new JSObject();
        int can = BiometricManager.from(getContext()).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        ret.put("biometricAvailable", can == BiometricManager.BIOMETRIC_SUCCESS);
        ret.put("biometricEnabled", prefs().getBoolean("biometricEnabled", false));
        ret.put("removeOriginal", false);
        call.resolve(ret);
    }

    @PluginMethod
    public void setPrivateSettings(PluginCall call) {
        prefs().edit()
                .putBoolean("biometricEnabled", call.getBoolean("biometricEnabled", false))
                .putBoolean("removeOriginal", false)
                .apply();
        call.resolve();
    }

    @PluginMethod
    public void authenticateBiometric(PluginCall call) {
        if (!prefs().getBoolean("biometricEnabled", false)) { call.reject("Biometric unlock is disabled"); return; }
        int can = BiometricManager.from(getContext()).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        if (can != BiometricManager.BIOMETRIC_SUCCESS) { call.reject("Biometric authentication is unavailable"); return; }
        getActivity().runOnUiThread(() -> {
            BiometricPrompt prompt = new BiometricPrompt(getActivity(), ContextCompat.getMainExecutor(getContext()), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) { call.resolve(); }
                @Override public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) { call.reject(errString.toString()); }
                @Override public void onAuthenticationFailed() { }
            });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder().setTitle("Unlock NYX").setSubtitle("Confirm your identity").setNegativeButtonText("Use PIN").build();
            prompt.authenticate(info);
        });
    }

    @PluginMethod
    public void pickMedia(PluginCall call) {
        String source = call.getString("source", "files");
        Intent i;

        if ("photos".equals(source)) {
            // Use the system media chooser instead of Photo Picker. This gives NYX a
            // normal content URI and lets Android apply its user-confirmed MediaStore
            // deletion flow when the selected item belongs to the device gallery.
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*", "audio/*"});
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        } else {
            // Android Files/document picker. Multiple image/video files are supported.
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*", "audio/*"});
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        startActivityForResult(call, i, "mediaPickerResult");
    }

    @ActivityCallback
    private void mediaPickerResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        try {
            if (result == null || result.getResultCode() != android.app.Activity.RESULT_OK) {
                call.reject("Media picker cancelled");
                return;
            }
            Intent data = result.getData();
            if (data == null) {
                call.reject("No media was returned by the picker");
                return;
            }

            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) {
                call.reject("No media was selected");
                return;
            }

            final ArrayList<Uri> importUris = uris;
            final String pickerSource = call.getString("source", "files");
            IO_EXECUTOR.execute(() -> {
                int ok=0; String firstError=null;
                for(Uri u:importUris){
                    try{
                        String pickedMime=getContext().getContentResolver().getType(u);
                        if(pickedMime==null||!(pickedMime.startsWith("image/")||pickedMime.startsWith("video/"))){if(firstError==null)firstError="Selected file is not an image or video";continue;}
                        try{if(Build.VERSION.SDK_INT>=19&&"files".equals(pickerSource))getContext().getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignoredPermission){}
                        saveUri(u); ok++;
                    }catch(Exception ex){if(firstError==null)firstError=ex.getMessage();}
                }
                final int imported=ok; final String error=firstError;
                getActivity().runOnUiThread(() -> {
                    if(imported==0){call.reject(error==null?"Could not import selected media":error);return;}
                    scheduleUploadWorker();
                    JSObject ret=new JSObject();ret.put("imported",imported);if(error!=null)ret.put("warning",error);call.resolve(ret);
                });
            });
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? "Could not import media" : e.getMessage());
        }
    }

    private void saveUri(Uri uri) throws Exception {
        String name=queryName(uri), mime=getContext().getContentResolver().getType(uri);
        if(mime==null)mime="application/octet-stream";
        String id=System.currentTimeMillis()+"_"+Math.abs(new SecureRandom().nextInt());
        String clean=name==null?"media":name.replaceAll("[^A-Za-z0-9._-]","_");
        if(clean.isEmpty())clean="media";
        File out=new File(root(),id+"_"+clean);
        long copied=0;
        try(InputStream in=getContext().getContentResolver().openInputStream(uri);FileOutputStream fos=new FileOutputStream(out)){
            if(in==null)throw new Exception("No input");
            byte[]buf=new byte[64*1024];int n;while((n=in.read(buf))!=-1){fos.write(buf,0,n);copied+=n;}
        }
        long expected=querySize(uri);if(expected>=0&&expected!=copied){out.delete();throw new Exception("Size verification failed");}
        ensureNyxNoMediaMarker();
        String resource=mime.startsWith("video/")?"video":(mime.startsWith("audio/")?"video":"image");
        appendMeta(id,name,mime,copied,false,resource,uri.toString(),false,out.getAbsolutePath());
    }

    private long querySize(Uri uri) {
        try (android.database.Cursor c = getContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.SIZE); if (i >= 0 && !c.isNull(i)) return c.getLong(i); }
        } catch (Exception ignored) {}
        return -1;
    }

    private void ensureNyxNoMediaMarker() {
        try {
            File external = getContext().getExternalFilesDir(null);
            if (external == null) return;
            File nyx = new File(external, "NYX");
            if (!nyx.exists()) nyx.mkdirs();
            File marker = new File(nyx, ".nomedia");
            if (!marker.exists()) marker.createNewFile();
            appendDebug("INFO", "NYX .nomedia marker ready");
        } catch (Exception e) { appendDebug("WARN", "Could not create NYX .nomedia marker: " + e.getMessage()); }
    }

    private void appendDebug(String level, String message) {
        try {
            File f = new File(root(), "nyx-debug.log");
            String line = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " [" + level + "] " + message + "\n";
            try (FileOutputStream out = new FileOutputStream(f, true)) { out.write(line.getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) {}
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i); }
        } catch (Exception ignored) {}
        return "media";
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setRandomizedEncryptionRequired(false).build());
        return kg.generateKey();
    }

    @PluginMethod
    public void getUploadStatus(PluginCall call) {
        JSObject ret = new JSObject();
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            File f = new File(root(), "upload-status.json");
            if (f.exists()) {
                String text = readAll(f);
                for (String line : text.split("\\n")) if (!line.trim().isEmpty()) arr.put(new org.json.JSONObject(line));
            }
        } catch (Exception ignored) {}
        ret.put("items", arr); call.resolve(ret);
    }

    @PluginMethod
    public void getDebugLog(PluginCall call) {
        JSObject ret = new JSObject();
        try { ret.put("text", new File(root(), "nyx-debug.log").exists() ? readAll(new File(root(), "nyx-debug.log")) : "No debug events yet."); }
        catch (Exception e) { ret.put("text", "Could not read debugger log: " + e.getMessage()); }
        call.resolve(ret);
    }

    @PluginMethod
    public void clearDebugLog(PluginCall call) {
        try { File f=new File(root(), "nyx-debug.log"); if(f.exists()) f.delete(); call.resolve(); }
        catch(Exception e){ call.reject("Could not clear debugger log"); }
    }

    @PluginMethod
    public void retryUploads(PluginCall call) {
        scheduleUploadWorker();
        JSObject ret=new JSObject(); ret.put("ok",true); call.resolve(ret);
    }

    @PluginMethod
    public void listMedia(PluginCall call) {
        IO_EXECUTOR.execute(() -> {
            JSObject ret=new JSObject(); org.json.JSONArray arr=new org.json.JSONArray();
            try {
                if(metaFile().exists()){String s=readAll(metaFile());for(String x:s.split("\n")){if(!x.trim().isEmpty())arr.put(new org.json.JSONObject(x));}}
                ret.put("items",arr);call.resolve(ret);
            }catch(Exception e){call.reject("Could not list private media");}
        });
    }

    @PluginMethod
    public void getThumbnail(PluginCall call) {
        String id=call.getString("id","");if(id.isEmpty()){call.reject("Missing id");return;}
        IO_EXECUTOR.execute(() -> {try{org.json.JSONObject target=findMeta(id);if(target==null)throw new Exception();File file=mediaFile(target);if(file==null||!file.exists())throw new Exception();Bitmap bmp;String mime=target.optString("mime","");if(mime.startsWith("video/")){if(Build.VERSION.SDK_INT>=29)bmp=android.media.ThumbnailUtils.createVideoThumbnail(file,new Size(640,640),null);else{MediaMetadataRetriever mmr=new MediaMetadataRetriever();mmr.setDataSource(file.getAbsolutePath());bmp=mmr.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);mmr.release();}}else if(mime.startsWith("image/")){bmp=BitmapFactory.decodeFile(file.getAbsolutePath());}else{throw new Exception();}if(bmp==null)throw new Exception();int max=360;float scale=Math.min(1f,max/(float)Math.max(bmp.getWidth(),bmp.getHeight()));if(scale<1f)bmp=Bitmap.createScaledBitmap(bmp,Math.max(1,(int)(bmp.getWidth()*scale)),Math.max(1,(int)(bmp.getHeight()*scale)),true);ByteArrayOutputStream bos=new ByteArrayOutputStream();bmp.compress(Bitmap.CompressFormat.JPEG,82,bos);bmp.recycle();JSObject ret=new JSObject();ret.put("data",Base64.encodeToString(bos.toByteArray(),Base64.NO_WRAP));ret.put("mime","image/jpeg");call.resolve(ret);}catch(Exception e){call.reject("Thumbnail unavailable");}});
    }

    @PluginMethod
    public void openMedia(PluginCall call) {
        String id = call.getString("id", "");
        if (id.isEmpty()) { call.reject("Missing id"); return; }
        try {
            if (findMeta(id) == null) throw new Exception("Media not found");
            Intent i = new Intent(getContext(), NyxMediaViewerActivity.class);
            i.putExtra("media_id", id);
            getActivity().startActivity(i);
            JSObject ret = new JSObject(); ret.put("opened", true); call.resolve(ret);
        } catch (Exception e) {
            call.reject("Could not open media: " + (e.getMessage() == null ? "Media unavailable" : e.getMessage()));
        }
    }

    @PluginMethod
    public void deleteMedia(PluginCall call) {
        String id=call.getString("id","");if(id.isEmpty()){call.reject("Missing id");return;}
        IO_EXECUTOR.execute(() -> {try{org.json.JSONObject target=findMeta(id);if(target==null){call.reject("Not found");return;}File f=mediaFile(target);if(f!=null&&f.exists())f.delete();String legacy=new File(getContext().getFilesDir(),ROOT+File.separator+id+".nyx").getAbsolutePath();File lf=new File(legacy);if(lf.exists())lf.delete();if(target.optBoolean("uploaded",false)&&!target.optString("cloud_public_id","").isEmpty()){try{deleteCloudCopy(target);}catch(Exception e){appendDebug("WARN","Cloud copy was not deleted: "+e.getMessage());}}List<String>keep=new ArrayList<>();if(metaFile().exists())for(String x:readAll(metaFile()).split("\n"))if(!x.trim().isEmpty()&&!id.equals(new org.json.JSONObject(x).optString("id")))keep.add(x);writeAll(metaFile(),String.join("
",keep));call.resolve();}catch(Exception e){call.reject("Could not delete media");}});
    }

    private void deleteCloudCopy(org.json.JSONObject target) throws Exception {
        String publicId=target.optString("cloud_public_id","");if(publicId.isEmpty())return;String resource=target.optString("cloud_resource_type",target.optString("resource_type","image"));long ts=System.currentTimeMillis()/1000;java.util.Map<String,String>p=new java.util.TreeMap<>();p.put("public_id",publicId);p.put("timestamp",String.valueOf(ts));String sig=signCloud(p);URL u=new URL("https://api.cloudinary.com/v1_1/"+CloudinaryConfig.CLOUD_NAME+"/"+resource+"/destroy");HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setDoOutput(true);c.setRequestMethod("POST");c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");String body="public_id="+java.net.URLEncoder.encode(publicId,"UTF-8")+"&timestamp="+ts+"&api_key="+CloudinaryConfig.API_KEY+"&signature="+sig;try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}int code=c.getResponseCode();if(code<200||code>=300)throw new Exception("Cloudinary delete "+code+": "+readStream(c.getErrorStream()));}
    private String signCloud(java.util.Map<String,String>p)throws Exception{StringBuilder s=new StringBuilder();for(java.util.Map.Entry<String,String>e:p.entrySet()){if(s.length()>0)s.append('&');s.append(e.getKey()).append('=').append(e.getValue());}s.append(CloudinaryConfig.API_SECRET);byte[]d=MessageDigest.getInstance("SHA-1").digest(s.toString().getBytes(StandardCharsets.UTF_8));StringBuilder h=new StringBuilder();for(byte x:d)h.append(String.format(java.util.Locale.US,"%02x",x));return h.toString();}
    private String readStream(InputStream in)throws Exception{if(in==null)return "";try(java.util.Scanner sc=new java.util.Scanner(in,StandardCharsets.UTF_8.name()).useDelimiter("\A")){return sc.hasNext()?sc.next():"";}}
    private File mediaFile(org.json.JSONObject meta){String path=meta.optString("path","");if(!path.isEmpty())return new File(path);String id=meta.optString("id","");File f=new File(root(),id+".nyx");return f.exists()?f:null;}

    private org.json.JSONObject findMeta(String id) throws Exception {
        if (!metaFile().exists()) return null; for (String x : readAll(metaFile()).split("\\n")) { if (x.trim().isEmpty()) continue; org.json.JSONObject o = new org.json.JSONObject(x); if (id.equals(o.optString("id"))) return o; } return null;
    }

    private String readAll(File f) throws Exception { return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    private void writeAll(File f, String s) throws Exception { try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); } }
    private void copy(InputStream in, OutputStream out) throws Exception { byte[] buf = new byte[64 * 1024]; int n; while ((n = in.read(buf)) != -1) out.write(buf, 0, n); }

    private synchronized void appendMeta(String id, String name, String mime, long size, boolean uploaded, String resourceType, String originalUri, boolean originalRemoved, String path) throws Exception {
        List<String> rows = new ArrayList<>();
        if (metaFile().exists()) { String s = readAll(metaFile()); if (!s.trim().isEmpty()) for (String x : s.split("\\n")) if (!x.trim().isEmpty()) rows.add(x); }
        JSObject o = new JSObject(); o.put("id", id); o.put("name", name); o.put("mime", mime); o.put("size", size); o.put("uploaded", uploaded); o.put("resource_type", resourceType); o.put("original_uri", originalUri); o.put("original_removed", originalRemoved); o.put("path", path); rows.add(o.toString());
        writeAll(metaFile(), String.join("\n", rows));
    }

    private void scheduleUploadWorker() {
        Constraints c = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(NyxUploadWorker.class).setConstraints(c).build();
        WorkManager.getInstance(getContext()).enqueueUniqueWork("nyx-cloudinary", ExistingWorkPolicy.REPLACE, req);
    }
}
