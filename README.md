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
