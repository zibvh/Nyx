# NYX Backblaze B2 test build

The Android test build uploads vault media directly to Backblaze B2 in a WorkManager background job.

The requested B2 credentials are embedded in `NyxUploadWorker.java` for this test build only. This is NOT production-safe because an APK can be reverse-engineered.

Before building, replace `nyxoria` in both `NyxUploadWorker.java` copies with the exact Backblaze bucket name.
