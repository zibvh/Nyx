# NYX iPhone native layer

This folder is copied into the Capacitor-generated `ios/App` project by `scripts_setup_ios.js`.

NYX uses the iOS Photos picker and Files picker, stores private media under Application Support/NYX, provides a custom photo/video viewer, and uses a background URLSession for Cloudinary uploads.

A physical iPhone build still requires macOS/Xcode and an Apple signing identity. GitHub Actions can be used with a macOS runner once Apple signing secrets are configured.
