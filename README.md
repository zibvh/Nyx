# NYX v0.9

NYX is a deliberately boring Notes-style Android app with a private encrypted media vault.

## v0.9 changes
- Encrypted media stays in app-private storage using an Android Keystore AES-256-GCM key.
- Vault thumbnails for images and video frames.
- Tap a vault item to preview it; Open decrypts a temporary copy and hands it to Android's viewer through FileProvider.
- Delete private media and its encrypted local copy.
- Optional automatic removal of the original selected media after successful size-verified encryption. This is controlled during setup and in Private settings.
- Original document URI permission is persisted where Android permits it.
- Private settings can enable/disable biometric unlock and change the secret + 6-digit PIN.
- Vault mode enables FLAG_SECURE to reduce screenshots/recents leakage and clears NYX temporary preview/open cache files when locking.
- Uploads remain backend-signed and direct to Cloudinary; the API secret is never bundled in the APK.

## Intentionally not included in v0.9
- Cloudinary restore/view integration (server-side delivery URL flow is not wired into the app yet).
- Recovery/destruction/uninstall restoration behavior.

## Backend
See `server/README.md` and `.env.example`. Put Cloudinary credentials and `NYX_APP_TOKEN` only in the backend environment.

## Build
GitHub Actions generates the Capacitor Android project, applies the native NYX plugin, FileProvider configuration, launcher logo, and dependencies, then builds the debug APK.

## Updates without uninstalling

NYX v23 uses a stable **release signing key** and automatically increases Android `versionCode` on each GitHub Actions run. Future signed builds can therefore be installed over the existing app without uninstalling it, as long as the signing key is preserved.

Before running the first v23 build, create these four GitHub Actions repository secrets:

- `NYX_KEYSTORE_BASE64` — base64 contents of the permanent NYX release keystore
- `NYX_STORE_PASSWORD` — keystore password
- `NYX_KEY_ALIAS` — key alias
- `NYX_KEY_PASSWORD` — key password

**Never commit the keystore or passwords to the repository.** Keep the same keystore and passwords for every future NYX release.

Important: an APK signed with a different key cannot update an already-installed APK. If the v21 debug APK is already installed, it may need to be uninstalled once before installing the first v23 release-signed APK. After that first release-signed install, future NYX releases will update normally without uninstalling.


### v23 signing path fix
The GitHub Actions workflow writes the keystore to `android/app/signing/nyx-release.keystore` and configures Gradle with the app-module-relative path `signing/nyx-release.keystore`, avoiding the erroneous `android/app/app/signing/...` path.
