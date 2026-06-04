const sharp = require('sharp');
const path = require('path');
const fs = require('fs');

const sourcePath = path.join(__dirname, 'assets', '.aistudio', 'logo.png');
const resDir = path.join(__dirname, 'app', 'src', 'main', 'res');

// Mipmap sizes for Android launcher icons
const sizes = [
    { name: 'mdpi',    w: 48,  h: 48  },
    { name: 'hdpi',    w: 72,  h: 72  },
    { name: 'xhdpi',   w: 96,  h: 96  },
    { name: 'xxhdpi',  w: 144, h: 144 },
    { name: 'xxxhdpi', w: 192, h: 192 },
];

// Adaptive icon foreground needs 108x108dp = 432x432px at xxxhdpi ref
// But for the adaptive foreground vector, we'll use a 108dp = 432px PNG
// and define it as a drawable. For simplicity, generate a 432x432 foreground
// and use it in the adaptive icon XML.
const adaptiveFgSize = { w: 432, h: 432 };
const adaptiveBgSize = { w: 432, h: 432 };

async function generate() {
    console.log('Generating icons from:', sourcePath);

    // Ensure directories exist
    for (const size of sizes) {
        const dir = path.join(resDir, `mipmap-${size.name}`);
        fs.mkdirSync(dir, { recursive: true });
    }

    // Generate mipmap icons (square crop, PNG format)
    for (const size of sizes) {
        const outputPath = path.join(resDir, `mipmap-${size.name}`, 'ic_launcher.png');
        await sharp(sourcePath)
            .resize(size.w, size.h, { fit: 'contain', background: { r: 79, g: 70, b: 229, alpha: 1 } })
            .png()
            .toFile(outputPath);
        console.log(`  Created mipmap-${size.name}: ${size.w}x${size.h}`);
    }

    // Also write ic_launcher_round.png (same logo, same sizes)
    for (const size of sizes) {
        const outputPath = path.join(resDir, `mipmap-${size.name}`, 'ic_launcher_round.png');
        await sharp(sourcePath)
            .resize(size.w, size.h, { fit: 'contain', background: { r: 79, g: 70, b: 229, alpha: 1 } })
            .png()
            .toFile(outputPath);
        console.log(`  Created mipmap-${size.name} round: ${size.w}x${size.h}`);
    }

    // Generate adaptive icon foreground (432x432 on 108dp canvas, logo centered at ~66% of safe zone)
    // Adaptive icon: 108x108dp safe zone, inner 66dp is the visual area
    // So logo should be ~72dp wide on 108dp canvas = 288px on 432px canvas
    await sharp(sourcePath)
        .resize(288, 288, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
        .extend({ top: 72, bottom: 72, left: 72, right: 72, background: { r: 0, g: 0, b: 0, alpha: 0 } })
        .png()
        .toFile(path.join(resDir, 'drawable', 'ic_launcher_foreground.png'));
    console.log('  Created adaptive icon foreground (432x432)');

    console.log('Done! All icons generated.');
}

generate().catch(err => { console.error(err); process.exit(1); });
