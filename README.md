# NYX v0.9.50

Personal NYX build with direct signed Cloudinary uploads.

- No Render/backend dependency for Cloudinary uploads.
- Cloudinary signing happens locally in the Android app.
- Media is stored as normal files inside the app-specific `Android/data/.../files/NYX` folder with `.nomedia`.
- New media is not encrypted.
- No original-media deletion is attempted.
- Background uploads retry independently when network is available.
- Rotate the Cloudinary API secret before the final release build.

This build intentionally accepts the risk of embedding the Cloudinary API secret because it is a personal build.
