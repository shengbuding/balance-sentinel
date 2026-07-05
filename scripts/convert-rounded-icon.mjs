import sharp from 'sharp';
import { mkdirSync, existsSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const projectRoot = join(__dirname, '..');
const srcImage = join(projectRoot, '..', '.cc-connect', 'attachments', 'img_1783126864056_0.jpg');
const resDir = join(projectRoot, 'app', 'src', 'main', 'res');

// Android mipmap densities — apply rounded corners to the final PNG
const densities = [
  { name: 'mipmap-mdpi',    size: 48 },
  { name: 'mipmap-hdpi',    size: 72 },
  { name: 'mipmap-xhdpi',   size: 96 },
  { name: 'mipmap-xxhdpi',  size: 144 },
  { name: 'mipmap-xxxhdpi', size: 192 },
];

// Rounded corner radius as percentage of icon size (~22% ≈ Pixel-style squircle)
const RADIUS_RATIO = 0.22;

async function roundedRect(size, radius) {
  // Build an SVG mask with rounded corners
  const svg = `<svg width="${size}" height="${size}" xmlns="http://www.w3.org/2000/svg">
    <rect x="0" y="0" width="${size}" height="${size}" rx="${radius}" ry="${radius}" fill="white"/>
  </svg>`;
  return Buffer.from(svg);
}

async function main() {
  console.log('Source:', srcImage);
  console.log(`Corner radius: ${RADIUS_RATIO * 100}% of icon size\n`);

  for (const { name, size } of densities) {
    const dir = join(resDir, name);
    if (!existsSync(dir)) mkdirSync(dir, { recursive: true });

    const radius = Math.round(size * RADIUS_RATIO);
    const mask = await roundedRect(size, radius);

    // ic_launcher.png
    const outFile = join(dir, 'ic_launcher.png');
    await sharp(srcImage)
      .resize(size, size, { fit: 'cover', position: 'center' })
      .composite([{ input: mask, blend: 'dest-in' }])
      .png()
      .toFile(outFile);
    console.log(`  ${name}/ic_launcher.png — ${size}×${size}, radius=${radius}px`);

    // ic_launcher_round.png (same but fully round — circle mask)
    const roundMask = await roundedRect(size, size / 2); // rx = half → full circle
    const roundFile = join(dir, 'ic_launcher_round.png');
    await sharp(srcImage)
      .resize(size, size, { fit: 'cover', position: 'center' })
      .composite([{ input: roundMask, blend: 'dest-in' }])
      .png()
      .toFile(roundFile);
    console.log(`  ${name}/ic_launcher_round.png — ${size}×${size}, circular`);
  }

  console.log('\nDone!');
}

main().catch(err => { console.error(err); process.exit(1); });
