const esbuild = require('esbuild');

esbuild.build({
  entryPoints: ['www/js/app.src.js'],
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: ['es2020'],
  outfile: 'www/js/app.js',
  sourcemap: false,
  minify: false,
}).catch(() => process.exit(1));
