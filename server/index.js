import 'dotenv/config';
import express from 'express';
import cors from 'cors';
import crypto from 'node:crypto';
import { v2 as cloudinary } from 'cloudinary';

const app = express();
const PORT = Number(process.env.PORT || 10000);

const {
  CLOUDINARY_CLOUD_NAME,
  CLOUDINARY_API_KEY,
  CLOUDINARY_API_SECRET,
  NYX_APP_TOKEN,
  ALLOWED_ORIGIN = '*'
} = process.env;

if (!CLOUDINARY_CLOUD_NAME || !CLOUDINARY_API_KEY || !CLOUDINARY_API_SECRET || !NYX_APP_TOKEN) {
  console.error('Missing required environment variables.');
  process.exit(1);
}

cloudinary.config({
  cloud_name: CLOUDINARY_CLOUD_NAME,
  api_key: CLOUDINARY_API_KEY,
  api_secret: CLOUDINARY_API_SECRET,
  secure: true
});

app.use(cors({
  origin: ALLOWED_ORIGIN === '*' ? true : ALLOWED_ORIGIN.split(',').map(x => x.trim()),
  methods: ['GET', 'POST', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization']
}));
app.use(express.json({ limit: '64kb' }));

function authorized(req) {
  const header = req.get('authorization') || '';
  const supplied = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (!supplied || supplied.length !== NYX_APP_TOKEN.length) return false;
  return crypto.timingSafeEqual(
    Buffer.from(supplied),
    Buffer.from(NYX_APP_TOKEN)
  );
}

function requireAuth(req, res, next) {
  if (!authorized(req)) return res.status(401).json({ error: 'Unauthorized' });
  next();
}

function cleanPart(value, fallback = '') {
  return String(value ?? fallback)
    .trim()
    .replace(/[^a-zA-Z0-9._/-]/g, '_')
    .replace(/\.{2,}/g, '.')
    .slice(0, 180);
}

app.get('/health', (_req, res) => {
  res.json({ ok: true, service: 'nyx-backend' });
});

/*
 * The Android app sends only the upload parameters.
 * The API secret stays on this server.
 *
 * Cloudinary's signed-upload flow requires the server to create the
 * signature; the client then uploads directly to Cloudinary.
 */
app.post('/api/cloudinary/sign-upload', requireAuth, (req, res) => {
  const timestamp = Math.floor(Date.now() / 1000);
  const folder = 'nyx-vault';
  const publicId = cleanPart(req.body.public_id, `media_${timestamp}`);
  const type = 'authenticated';

  const paramsToSign = {
    folder,
    public_id: publicId,
    timestamp,
    type
  };

  const signature = cloudinary.utils.api_sign_request(
    paramsToSign,
    CLOUDINARY_API_SECRET
  );

  res.json({
    cloud_name: CLOUDINARY_CLOUD_NAME,
    api_key: CLOUDINARY_API_KEY,
    timestamp,
    signature,
    folder,
    public_id: publicId,
    type
  });
});

/*
 * Creates a short-lived signed delivery URL for an authenticated asset.
 * The media itself remains private in Cloudinary.
 */

app.post('/api/cloudinary/delete', requireAuth, async (req, res) => {
  try {
    const publicId = String(req.body?.public_id || '').trim();
    const resourceType = req.body?.resource_type === 'video' ? 'video' : 'image';
    if (!publicId) return res.status(400).json({ error: 'public_id is required' });
    const result = await cloudinary.uploader.destroy(publicId, { resource_type: resourceType, type: 'authenticated', invalidate: true });
    if (result.result !== 'ok' && result.result !== 'not found') return res.status(502).json({ error: 'Cloudinary delete failed', result: result.result });
    res.json({ ok: true, result: result.result });
  } catch (e) {
    res.status(500).json({ error: 'Cloudinary delete failed' });
  }
});

app.post('/api/cloudinary/delivery-url', requireAuth, (req, res) => {
  const publicId = cleanPart(req.body.public_id);
  const resourceType = ['image', 'video', 'raw'].includes(req.body.resource_type)
    ? req.body.resource_type
    : 'image';
  const format = cleanPart(req.body.format);

  if (!publicId) return res.status(400).json({ error: 'public_id required' });

  const url = cloudinary.url(publicId, {
    secure: true,
    resource_type: resourceType,
    type: 'authenticated',
    sign_url: true,
    ...(format ? { format } : {})
  });

  res.json({ url, expires_in: 3600 });
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`NYX backend listening on ${PORT}`);
});
