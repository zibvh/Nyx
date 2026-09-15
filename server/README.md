# NYX Backend

This backend exists so NYX can use **signed Cloudinary uploads** without putting the
Cloudinary API secret inside the Android APK.

## What it does

- Keeps `CLOUDINARY_API_SECRET` on the server.
- Signs upload parameters for NYX.
- Tells NYX to upload directly to Cloudinary.
- Uses Cloudinary `authenticated` delivery type for vault media.
- Creates signed delivery URLs when NYX needs to display media.
- Does not receive/store the media itself.

This is intentional: large photos/videos can go directly from the phone to Cloudinary.

## Local setup

1. Copy `.env.example` to `.env`.
2. Fill in your Cloudinary credentials.
3. Generate a strong `NYX_APP_TOKEN`.
4. Run:

```bash
npm install
npm start
```

Health check:

`GET /health`

## Render

Create a new **Web Service** from the repository.

- Root directory: `server`
- Build command: `npm install`
- Start command: `npm start`

Add the same variables from the root `.env.example` to Render's Environment Variables.

Do not commit `.env`.

## Cloudinary privacy

NYX uploads vault media with delivery type `authenticated`, so the normal public
Cloudinary delivery URL should not expose the asset. The backend signs access when
the app requests a media URL.

### Endpoints
- `GET /health`
- `POST /api/cloudinary/sign-upload` — signs authenticated uploads
- `POST /api/cloudinary/delete` — deletes an authenticated Cloudinary asset
- `POST /api/cloudinary/delivery-url` — generates a signed delivery URL (not wired into the v0.9 app yet)
