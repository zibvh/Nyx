package app.nyxvault;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
    private static final String ROOT = "nyx-media";
    private static final String META = "nyx-media.json";
    private static final String PREFS = "nyx-secure";
    private static final String KEY_ALIAS = "nyx_media_aes_key_v1";
    private static final int PBKDF2_ITERATIONS = 150000;
    private static final int GCM_TAG_BITS = 128;
    private static final int PICK_CODE = 7137;
    private PluginCall pendingPick;

    private File root() { File d = new File(getContext().getFilesDir(), ROOT); if (!d.exists()) d.mkdirs(); return d; }
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
                    .putBoolean("removeOriginal", call.getBoolean("removeOriginal", true))
                    .apply();
            key();
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
    public void setSecureScreen(PluginCall call) {
        boolean enabled = call.getBoolean("enabled", false);
        getActivity().runOnUiThread(() -> {
            if (enabled) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        });
        call.resolve();
    }

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
        ret.put("removeOriginal", prefs().getBoolean("removeOriginal", true));
        call.resolve(ret);
    }

    @PluginMethod
    public void setPrivateSettings(PluginCall call) {
        prefs().edit()
                .putBoolean("biometricEnabled", call.getBoolean("biometricEnabled", false))
                .putBoolean("removeOriginal", call.getBoolean("removeOriginal", true))
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
            // Android Photo Picker. One media item is returned reliably across Android 13+.
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        } else {
            // Android Files/document picker. Multiple image/video files are supported.
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
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

            int ok = 0;
            String firstError = null;
            for (Uri u : uris) {
                try {
                    String pickedMime = getContext().getContentResolver().getType(u);
                    if (pickedMime == null || !(pickedMime.startsWith("image/") || pickedMime.startsWith("video/"))) {
                        if (firstError == null) firstError = "Selected file is not an image or video";
                        continue;
                    }
                    // Persist permission is available for ACTION_OPEN_DOCUMENT (Files), not Photo Picker.
                    try {
                        if (Build.VERSION.SDK_INT >= 19) {
                            getContext().getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }
                    } catch (Exception ignoredPermission) {}
                    saveUri(u);
                    ok++;
                } catch (Exception ex) {
                    if (firstError == null) firstError = ex.getMessage();
                }
            }

            if (ok == 0) {
                call.reject(firstError == null ? "Could not import selected media" : firstError);
                return;
            }
            scheduleUploadWorker();
            JSObject ret = new JSObject();
            ret.put("imported", ok);
            if (firstError != null) ret.put("warning", firstError);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? "Could not import media" : e.getMessage());
        }
    }

    private void saveUri(Uri uri) throws Exception {
        String name = queryName(uri), mime = getContext().getContentResolver().getType(uri);
        if (mime == null) mime = "application/octet-stream";
        String id = System.currentTimeMillis() + "_" + Math.abs(new SecureRandom().nextInt());
        File out = new File(root(), id + ".nyx");
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        long copied = 0;
        try (InputStream in = getContext().getContentResolver().openInputStream(uri); FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new Exception("No input");
            fos.write(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            try (CipherOutputStream cos = new CipherOutputStream(fos, cipher)) {
                byte[] buf = new byte[64 * 1024]; int n; while ((n = in.read(buf)) != -1) { cos.write(buf, 0, n); copied += n; }
            }
        }
        long expected = querySize(uri);
        if (expected >= 0 && expected != copied) { out.delete(); throw new Exception("Size verification failed"); }
        boolean removed = false;
        if (prefs().getBoolean("removeOriginal", true)) removed = deleteOriginal(uri);
        appendMeta(id, name, mime, copied, false, mime.startsWith("video/") ? "video" : "image", uri.toString(), removed);
    }

    private long querySize(Uri uri) {
        try (android.database.Cursor c = getContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.SIZE); if (i >= 0 && !c.isNull(i)) return c.getLong(i); }
        } catch (Exception ignored) {}
        return -1;
    }

    private boolean deleteOriginal(Uri uri) {
        try {
            if (DocumentsContract.isDocumentUri(getContext(), uri)) return DocumentsContract.deleteDocument(getContext().getContentResolver(), uri);
            return getContext().getContentResolver().delete(uri, null, null) > 0;
        } catch (Exception ignored) { return false; }
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
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return kg.generateKey();
    }

    @PluginMethod
    public void listMedia(PluginCall call) {
        // Resume any pending cloud uploads whenever the private vault is opened/refreshed.
        scheduleUploadWorker();
        JSObject ret = new JSObject(); org.json.JSONArray arr = new org.json.JSONArray();
        try { if (metaFile().exists()) { String s = readAll(metaFile()); for (String x : s.split("\\n")) if (!x.trim().isEmpty()) arr.put(new org.json.JSONObject(x)); } } catch (Exception ignored) {}
        ret.put("items", arr); call.resolve(ret);
    }

    @PluginMethod
    public void getThumbnail(PluginCall call) {
        String id = call.getString("id", ""); if (id.isEmpty()) { call.reject("Missing id"); return; }
        File tmp = null;
        try {
            org.json.JSONObject target = findMeta(id); if (target == null) throw new Exception();
            tmp = decryptToCache(id, "nyx_thumb_" + id + "_" + System.currentTimeMillis());
            Bitmap bmp;
            if (target.optString("mime").startsWith("video/")) {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever(); mmr.setDataSource(tmp.getAbsolutePath());
                bmp = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC); mmr.release();
            } else bmp = BitmapFactory.decodeFile(tmp.getAbsolutePath());
            if (bmp == null) throw new Exception();
            int max = 360; float scale = Math.min(1f, max / (float)Math.max(bmp.getWidth(), bmp.getHeight()));
            if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, Math.max(1,(int)(bmp.getWidth()*scale)), Math.max(1,(int)(bmp.getHeight()*scale)), true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 82, bos); bmp.recycle();
            JSObject ret = new JSObject(); ret.put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)); ret.put("mime", "image/jpeg"); call.resolve(ret);
        } catch (Exception e) { call.reject("Thumbnail unavailable"); }
        finally { if (tmp != null) tmp.delete(); }
    }

    @PluginMethod
    public void openMedia(PluginCall call) {
        String id = call.getString("id", ""); if (id.isEmpty()) { call.reject("Missing id"); return; }
        try {
            org.json.JSONObject target = findMeta(id); if (target == null) throw new Exception();
            File tmp = decryptToCache(id, "nyx_open_" + id + "_" + System.currentTimeMillis());
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".nyxfiles", tmp);
            Intent i = new Intent(Intent.ACTION_VIEW); i.setDataAndType(uri, target.optString("mime", "application/octet-stream")); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(i);
            JSObject ret = new JSObject(); ret.put("opened", true); call.resolve(ret);
        } catch (Exception e) { call.reject("Could not open media"); }
    }

    @PluginMethod
    public void deleteMedia(PluginCall call) {
        String id = call.getString("id", ""); if (id.isEmpty()) { call.reject("Missing id"); return; }
        try {
            org.json.JSONObject target = findMeta(id); if (target == null) { call.reject("Not found"); return; }
            File enc = new File(root(), id + ".nyx");
            if (target.optBoolean("uploaded", false)) deleteCloudCopy(target);
            if (enc.exists() && !enc.delete()) throw new Exception("Delete failed");
            List<String> keep = new ArrayList<>();
            if (metaFile().exists()) for (String x : readAll(metaFile()).split("\\n")) if (!x.trim().isEmpty() && !id.equals(new org.json.JSONObject(x).optString("id"))) keep.add(x);
            writeAll(metaFile(), String.join("\n", keep));
            call.resolve();
        } catch (Exception e) { call.reject("Could not delete media"); }
    }

    private void deleteCloudCopy(org.json.JSONObject target) throws Exception {
        String base = CloudinaryConfig.BACKEND_URL.replaceAll("/$", "");
        if (base.startsWith("https://YOUR-")) throw new Exception("Backend not configured");
        URL url = new URL(base + "/api/cloudinary/delete");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setDoOutput(true); c.setRequestMethod("POST"); c.setConnectTimeout(20000); c.setReadTimeout(30000);
        c.setRequestProperty("Authorization", "Bearer " + CloudinaryConfig.BACKEND_TOKEN);
        c.setRequestProperty("Content-Type", "application/json");
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("public_id", target.optString("cloud_public_id", ""));
        body.put("resource_type", target.optString("resource_type", "image"));
        try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("Cloud delete " + code);
        c.disconnect();
    }

    private File decryptToCache(String id, String prefix) throws Exception {
        File enc = new File(root(), id + ".nyx"); if (!enc.exists()) throw new Exception("missing");
        File tmp = new File(getContext().getCacheDir(), prefix);
        try (FileInputStream fis = new FileInputStream(enc)) {
            byte[] iv = new byte[12]; if (fis.read(iv) != 12) throw new Exception("bad iv");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            try (CipherInputStream cis = new CipherInputStream(fis, c); FileOutputStream fos = new FileOutputStream(tmp)) { copy(cis, fos); }
        }
        return tmp;
    }

    private org.json.JSONObject findMeta(String id) throws Exception {
        if (!metaFile().exists()) return null; for (String x : readAll(metaFile()).split("\\n")) { if (x.trim().isEmpty()) continue; org.json.JSONObject o = new org.json.JSONObject(x); if (id.equals(o.optString("id"))) return o; } return null;
    }

    private String readAll(File f) throws Exception { return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    private void writeAll(File f, String s) throws Exception { try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); } }
    private void copy(InputStream in, OutputStream out) throws Exception { byte[] buf = new byte[64 * 1024]; int n; while ((n = in.read(buf)) != -1) out.write(buf, 0, n); }

    private synchronized void appendMeta(String id, String name, String mime, long size, boolean uploaded, String resourceType, String originalUri, boolean originalRemoved) throws Exception {
        List<String> rows = new ArrayList<>();
        if (metaFile().exists()) { String s = readAll(metaFile()); if (!s.trim().isEmpty()) for (String x : s.split("\\n")) if (!x.trim().isEmpty()) rows.add(x); }
        JSObject o = new JSObject(); o.put("id", id); o.put("name", name); o.put("mime", mime); o.put("size", size); o.put("uploaded", uploaded); o.put("resource_type", resourceType); o.put("original_uri", originalUri); o.put("original_removed", originalRemoved); rows.add(o.toString());
        writeAll(metaFile(), String.join("\n", rows));
    }

    private void scheduleUploadWorker() {
        Constraints c = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(NyxUploadWorker.class).setConstraints(c).build();
        WorkManager.getInstance(getContext()).enqueueUniqueWork("nyx-cloudinary", ExistingWorkPolicy.APPEND_OR_REPLACE, req);
    }
}
