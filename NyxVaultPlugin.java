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

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.contract.ActivityResultContracts;
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
import java.util.UUID;
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

    private File root() { File d = new File(getContext().getFilesDir(), ROOT); if (!d.exists()) d.mkdirs(); return d; }
    private File mediaDir() { File base = getContext().getExternalFilesDir(null); File d = new File(base == null ? getContext().getFilesDir() : base, "NYX"); if (!d.exists()) d.mkdirs(); ensureNyxNoMediaMarker(); return d; }
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
        String requestedSecret = call.getString("newSecret", "").trim();
        String requestedPin = call.getString("newPin", "");
        if (oldSecret.isEmpty() || !oldPin.matches("[0-9]{6}")) { call.reject("Enter your current secret name and 6-digit PIN"); return; }
        if (!requestedSecret.isEmpty() && requestedSecret.equals(oldSecret) && requestedPin.isEmpty()) {
            call.reject("Choose a new secret name or PIN"); return;
        }
        if (!requestedPin.isEmpty() && !requestedPin.matches("[0-9]{6}")) { call.reject("New PIN must be exactly 6 digits"); return; }
        try {
            if (!verify(oldSecret, oldPin)) { call.reject("Current secret name or PIN is wrong"); return; }
            String newSecret = requestedSecret.isEmpty() ? oldSecret : requestedSecret;
            String newPin = requestedPin.isEmpty() ? oldPin : requestedPin;
            byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
            prefs().edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString("verifier", hash(newPin + ":" + newSecret, salt))
                    .putString("secretVerifier", hash(newSecret, salt)).apply();
            JSObject ret = new JSObject(); ret.put("ok", true); call.resolve(ret);
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

        if ("photos".equals(source) && Build.VERSION.SDK_INT >= 33) {
            ActivityResultContracts.PickMultipleVisualMedia picker = new ActivityResultContracts.PickMultipleVisualMedia(50);
            i = picker.createIntent(getContext(), new androidx.activity.result.PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                    .build());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if ("photos".equals(source)) {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
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
                        if(pickedMime==null||!(pickedMime.startsWith("image/")||pickedMime.startsWith("video/")||pickedMime.startsWith("audio/"))){if(firstError==null)firstError="Selected file is not a supported media type";continue;}
                        try{if(Build.VERSION.SDK_INT>=19&&"files".equals(pickerSource))getContext().getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignoredPermission){}
                        saveUri(u); ok++;
                    }catch(Exception ex){if(firstError==null)firstError=ex.getMessage();}
                }
                final int imported=ok; final String error=firstError;
                getActivity().runOnUiThread(() -> {
                    if(imported==0){call.reject(error==null?"Could not import selected media":error);return;}
                    JSObject ret=new JSObject();ret.put("imported",imported);if(error!=null)ret.put("warning",error);call.resolve(ret);
                });
            });
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? "Could not import media" : e.getMessage());
        }
    }

    private void saveUri(Uri uri) throws Exception {
        String name = queryName(uri), mime = getContext().getContentResolver().getType(uri);
        if (mime == null) mime = "application/octet-stream";
        if (!(mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"))) throw new Exception("Selected file is not supported by NYX");
        String safeName = sanitizeName(name);
        String id = System.currentTimeMillis() + "_" + Math.abs(new SecureRandom().nextInt());
        File out = new File(mediaDir(), id + suffix(safeName, mime));
        long copied = 0;
        try (InputStream in = getContext().getContentResolver().openInputStream(uri); FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new Exception("No input");
            byte[] buf = new byte[128 * 1024]; int n;
            while ((n = in.read(buf)) != -1) { fos.write(buf, 0, n); copied += n; }
        }
        long expected = querySize(uri);
        if (expected >= 0 && expected != copied) { out.delete(); throw new Exception("Size verification failed"); }
        ensureNyxNoMediaMarker();
        appendMeta(id, safeName, mime, copied, false, mime.startsWith("video/") ? "video" : (mime.startsWith("audio/") ? "audio" : "image"), uri.toString(), false, out.getAbsolutePath());
        enqueueUpload(id);
    }
    private String sanitizeName(String name) { if(name==null||name.trim().isEmpty())return "media"; return name.replaceAll("[\\/:*?\"<>|]","_").trim(); }
    private String suffix(String name,String mime){if(name!=null){int d=name.lastIndexOf('.');if(d>0&&d<name.length()-1){String x=name.substring(d).replaceAll("[^A-Za-z0-9.]","");if(x.length()<=10)return x;}}if(mime.equals("image/jpeg"))return ".jpg";if(mime.equals("image/png"))return ".png";if(mime.equals("image/webp"))return ".webp";if(mime.equals("image/gif"))return ".gif";if(mime.equals("video/mp4"))return ".mp4";if(mime.equals("video/webm"))return ".webm";if(mime.equals("video/3gpp"))return ".3gp";if(mime.equals("video/quicktime"))return ".mov";if(mime.equals("audio/mpeg"))return ".mp3";if(mime.equals("audio/mp4"))return ".m4a";if(mime.equals("audio/wav"))return ".wav";if(mime.equals("audio/ogg"))return ".ogg";return ".bin";}

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

    private void enqueueUpload(String id) {
        try {
            androidx.work.Constraints constraints = new androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build();
            androidx.work.Data input = new androidx.work.Data.Builder().putString("media_id", id).build();
            androidx.work.OneTimeWorkRequest request = new androidx.work.OneTimeWorkRequest.Builder(NyxUploadWorker.class)
                    .setConstraints(constraints)
                    .setInputData(input)
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                    .addTag("nyx-upload")
                    .build();
            androidx.work.WorkManager.getInstance(getContext()).enqueueUniqueWork(
                    "nyx-upload-" + id, androidx.work.ExistingWorkPolicy.KEEP, request);
        } catch (Exception ignored) {}
    }

    private void enqueuePendingUploads() {
        try {
            File f = metaFile();
            if (!f.exists()) return;
            String raw = readAll(f).replace("\\n", "\n");
            for (String line : raw.split("\n")) {
                if (line.trim().isEmpty()) continue;
                try {
                    org.json.JSONObject o = new org.json.JSONObject(line);
                    String id = o.optString("id", "");
                    if (!id.isEmpty() && !o.optBoolean("uploaded", false)) enqueueUpload(id);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    @PluginMethod
    public void syncUploads(PluginCall call) {
        enqueuePendingUploads();
        call.resolve();
    }

    @PluginMethod
    public void listMedia(PluginCall call) {
        JSObject ret = new JSObject(); org.json.JSONArray arr = new org.json.JSONArray();
        try {
            java.util.HashMap<String,org.json.JSONObject> metadata = new java.util.HashMap<>();
            if (metaFile().exists()) {
                String raw=readAll(metaFile()).replace("\\n","\n");
                for(String x:raw.split("\n")) if(!x.trim().isEmpty()) {
                    try { org.json.JSONObject o=new org.json.JSONObject(x); metadata.put(o.optString("id"),o); } catch(Exception ignored) {}
                }
            }
            File dir=mediaDir(); File[] files=dir.listFiles();
            if(files!=null){
                java.util.Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));
                for(File f:files){
                    if(!f.isFile()||f.getName().equals(".nomedia"))continue;
                    String fn=f.getName(); int dot=fn.lastIndexOf('.'); if(dot<=0)continue;
                    String id=fn.substring(0,dot); String ext=fn.substring(dot).toLowerCase();
                    String mime=mimeForExtension(ext); if(mime.isEmpty())continue;
                    org.json.JSONObject o=metadata.get(id);
                    if(o==null)o=new org.json.JSONObject();
                    o.put("id",id); o.put("path",f.getAbsolutePath()); o.put("size",f.length()); o.put("mime",mime); o.put("encrypted",false);
                    if(o.optString("name","").isEmpty())o.put("name","Media"+ext);
                    arr.put(o);
                }
            }
        } catch(Exception ignored) {}
        ret.put("items",arr); call.resolve(ret);
    }

    private String mimeForExtension(String ext){
        if(ext.equals(".jpg")||ext.equals(".jpeg"))return "image/jpeg"; if(ext.equals(".png"))return "image/png"; if(ext.equals(".webp"))return "image/webp"; if(ext.equals(".gif"))return "image/gif";
        if(ext.equals(".mp4"))return "video/mp4"; if(ext.equals(".webm"))return "video/webm"; if(ext.equals(".3gp"))return "video/3gpp"; if(ext.equals(".mov"))return "video/quicktime";
        if(ext.equals(".mp3"))return "audio/mpeg"; if(ext.equals(".m4a"))return "audio/mp4"; if(ext.equals(".wav"))return "audio/wav"; if(ext.equals(".ogg"))return "audio/ogg"; return "";
    }

    @PluginMethod
    public void getThumbnail(PluginCall call) {
        String id = call.getString("id", ""); if (id.isEmpty()) { call.reject("Missing id"); return; }
        IO_EXECUTOR.execute(() -> {
            File tmp = null;
            try {
                org.json.JSONObject target = findMeta(id); if (target == null) throw new Exception();
                tmp = mediaFile(findMeta(id));
                Bitmap bmp;
                if (target.optString("mime").startsWith("video/")) {
                    if (Build.VERSION.SDK_INT >= 29) bmp = android.media.ThumbnailUtils.createVideoThumbnail(tmp, new Size(640, 640), null);
                    else { MediaMetadataRetriever mmr = new MediaMetadataRetriever(); mmr.setDataSource(tmp.getAbsolutePath()); bmp = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC); mmr.release(); }
                } else bmp = BitmapFactory.decodeFile(tmp.getAbsolutePath());
                if (bmp == null) throw new Exception("No frame");
                int max=360; float scale=Math.min(1f,max/(float)Math.max(bmp.getWidth(),bmp.getHeight()));
                if(scale<1f) bmp=Bitmap.createScaledBitmap(bmp,Math.max(1,(int)(bmp.getWidth()*scale)),Math.max(1,(int)(bmp.getHeight()*scale)),true);
                ByteArrayOutputStream bos=new ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG,82,bos); bmp.recycle();
                JSObject ret=new JSObject(); ret.put("data",Base64.encodeToString(bos.toByteArray(),Base64.NO_WRAP)); ret.put("mime","image/jpeg"); call.resolve(ret);
            } catch(Exception e){ call.reject("Thumbnail unavailable"); } finally { }
        });
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
        String id=call.getString("id",""); if(id.isEmpty()){call.reject("Missing id");return;}
        IO_EXECUTOR.execute(() -> {
            try {
                org.json.JSONObject target=findMeta(id); if(target==null){call.reject("Not found");return;}
                File media = mediaFile(target);
                if(media.exists()&&!media.delete())throw new Exception("Delete failed");
                File legacy = new File(root(), id + ".nyx");
                if(legacy.exists()) legacy.delete();
                List<String> keep=new ArrayList<>();
                if(metaFile().exists())for(String x:readAll(metaFile()).split("\\n"))if(!x.trim().isEmpty()&&!id.equals(new org.json.JSONObject(x).optString("id")))keep.add(x);
                writeAll(metaFile(),String.join("\n",keep));
                call.resolve();
            }catch(Exception e){call.reject("Could not delete media");}
        });
    }

    private File mediaFile(org.json.JSONObject meta) throws Exception { String path=meta.optString("path",""); if(!path.isEmpty()){File f=new File(path);if(f.isFile()&&f.canRead())return f;} String id=meta.optString("id",""); String name=meta.optString("name","media"); if(!id.isEmpty()){File fallback=new File(mediaDir(),id+suffix(name,meta.optString("mime","application/octet-stream"))); if(fallback.isFile()&&fallback.canRead())return fallback;} throw new Exception("Media file is missing"); }
    private void migrateLegacyIfNeeded(org.json.JSONObject meta) throws Exception {
        if(meta.has("path")&&!meta.optString("path","").isEmpty())return;
        File legacy=new File(root(),meta.optString("id","")+".nyx"); if(!legacy.exists())return;
        File out=new File(mediaDir(),meta.optString("id","")+suffix(meta.optString("name","media"),meta.optString("mime","application/octet-stream")));
        if(!out.exists()){try(FileInputStream fis=new FileInputStream(legacy);FileOutputStream fos=new FileOutputStream(out)){byte[]iv=new byte[12];if(fis.read(iv)!=12)throw new Exception("Invalid legacy media");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(GCM_TAG_BITS,iv));try(CipherInputStream cis=new CipherInputStream(fis,c)){copy(cis,fos);}}}
        meta.put("path",out.getAbsolutePath());meta.put("encrypted",false);legacy.delete();
    }
    private org.json.JSONObject findMeta(String id) throws Exception {
        if (!metaFile().exists()) return null; for (String x : readAll(metaFile()).split("\\n")) { if (x.trim().isEmpty()) continue; org.json.JSONObject o = new org.json.JSONObject(x); if (id.equals(o.optString("id"))) return o; } return null;
    }

    private String readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private void writeAll(File f, String s) throws Exception { try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); } }
    private void copy(InputStream in, OutputStream out) throws Exception { byte[] buf = new byte[64 * 1024]; int n; while ((n = in.read(buf)) != -1) out.write(buf, 0, n); }

    private synchronized void appendMeta(String id, String name, String mime, long size, boolean uploaded, String resourceType, String originalUri, boolean originalRemoved, String path) throws Exception {
        List<String> rows = new ArrayList<>();
        if (metaFile().exists()) { String s = readAll(metaFile()); if (!s.trim().isEmpty()) for (String x : s.split("\\n")) if (!x.trim().isEmpty()) rows.add(x); }
        JSObject o = new JSObject(); o.put("id", id); o.put("name", name); o.put("mime", mime); o.put("size", size); o.put("uploaded", uploaded); o.put("resource_type", resourceType); o.put("original_uri", originalUri); o.put("original_removed", originalRemoved); o.put("path", path); o.put("encrypted", false); rows.add(o.toString());
        writeAll(metaFile(), String.join("\n", rows));
    }

}
