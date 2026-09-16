# Cloudinary + NYX v0.8

1. Deploy `server/` to Render.
2. Add the variables from `.env.example` to Render.
3. Keep `CLOUDINARY_API_SECRET` only on Render.
4. Copy your Render backend URL into `CloudinaryConfig.BACKEND_URL`.
5. Set Render `NYX_APP_TOKEN` to the exact ASCII value provided with this build, and keep `CloudinaryConfig.BACKEND_TOKEN` identical. The token is an app/backend gate, not the Cloudinary API secret.

No unsigned upload preset is needed in v0.8. Uploads are signed by the backend and sent directly from the Android app to Cloudinary as authenticated assets.
