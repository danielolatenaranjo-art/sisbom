import fs from 'fs';
import path from 'path';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const args = process.argv.slice(2).map(a => a.toLowerCase());
const onlyPrepareWww = args.includes('--prepare-only') || args.includes('--sync-www') || args.includes('--both-www');
const shouldUploadPlayStore = args.includes('--upload-playstore') || args.includes('--playstore');

const distDir = path.join(__dirname, 'dist');
const androidDir = path.join(__dirname, 'android');
const assetsWwwDir = path.join(androidDir, 'app', 'src', 'main', 'assets', 'www');
const iosWwwDir = path.join(__dirname, 'ios', 'Resources', 'www');
const finalDir = path.resolve(__dirname, '..', 'FINAL');
const terminadosDir = path.resolve(__dirname, '..', '..', 'TERMINADOS');

function cleanOldFiles() {
  // Sincronizar automáticamente la carpeta oficial de sonidos si existe
  const sonidosSrcDir = path.join(__dirname, 'sonidos');
  if (fs.existsSync(sonidosSrcDir)) {
    const assetsSoundsDir = path.join(__dirname, 'assets', 'sounds');
    const androidRawDir = path.join(androidDir, 'app', 'src', 'main', 'res', 'raw');
    const iosResDir = path.join(__dirname, 'ios', 'Resources');
    [assetsSoundsDir, androidRawDir, iosResDir].forEach((d) => fs.mkdirSync(d, { recursive: true }));

    const mp3s = fs.readdirSync(sonidosSrcDir).filter((f) => f.toLowerCase().endsWith('.mp3'));
    for (const f of mp3s) {
      const src = path.join(sonidosSrcDir, f);
      const lower = f.toLowerCase();
      fs.copyFileSync(src, path.join(assetsSoundsDir, f));
      fs.copyFileSync(src, path.join(assetsSoundsDir, lower));
      fs.copyFileSync(src, path.join(iosResDir, f));
      fs.copyFileSync(src, path.join(iosResDir, lower));
      fs.copyFileSync(src, path.join(androidRawDir, lower));
    }
    const copyAlias = (srcName, aliasName) => {
      const src = path.join(sonidosSrcDir, srcName);
      if (fs.existsSync(src)) {
        fs.copyFileSync(src, path.join(assetsSoundsDir, aliasName));
        fs.copyFileSync(src, path.join(iosResDir, aliasName));
        fs.copyFileSync(src, path.join(androidRawDir, aliasName.toLowerCase()));
      }
    };
    copyAlias('alertas.mp3', 'alerta.mp3');
    copyAlias('c_llamadoComandancia.mp3', 'llamado_comandancia.mp3');
    copyAlias('c_llamadoComandancia.mp3', 'llamadocomandancia.mp3');
    copyAlias('c10_0.mp3', 'despacho.mp3');
  }

  const toRemove = [
    path.join(androidDir, 'build.gradle'),
    path.join(androidDir, 'settings.gradle'),
    path.join(androidDir, 'app', 'build.gradle'),
    path.join(androidDir, 'app', 'src', 'main', 'java', 'com', 'sisbom', 'misisbom', 'MainApplication.kt'),
    path.join(androidDir, 'app', 'src', 'main', 'java', 'com', 'sisbom', 'misisbom', 'SentinelSoundModule.kt'),
    path.join(androidDir, 'app', 'src', 'main', 'java', 'com', 'sisbom', 'misisbom', 'SentinelSoundPackage.kt'),
    path.join(androidDir, 'app', 'src', 'main', 'res', 'drawable', 'rn_edit_text_material.xml')
  ];
  for (const f of toRemove) {
    if (fs.existsSync(f)) {
      fs.rmSync(f, { force: true });
    }
  }
}

function copyRecursiveSync(src, dest) {
  const exists = fs.existsSync(src);
  const stats = exists && fs.statSync(src);
  const isDirectory = exists && stats.isDirectory();
  if (isDirectory) {
    fs.mkdirSync(dest, { recursive: true });
    fs.readdirSync(src).forEach((childItemName) => {
      const targetName = childItemName === '_expo' ? 'expo_bundle' : childItemName;
      copyRecursiveSync(path.join(src, childItemName), path.join(dest, targetName));
    });
  } else {
    fs.copyFileSync(src, dest);
  }
}

function rewriteUnderscorePathsInTextFiles(dir) {
  if (!fs.existsSync(dir)) return;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      rewriteUnderscorePathsInTextFiles(fullPath);
    } else if (/\.(html|js|css|json)$/i.test(entry.name)) {
      let content = fs.readFileSync(fullPath, 'utf8');
      if (content.includes('/_expo/')) {
        content = content.split('/_expo/').join('/expo_bundle/');
        fs.writeFileSync(fullPath, content, 'utf8');
      }
    }
  }
}

try {
  console.log('====================================================');
  console.log(' EMPACANDO APK: SENTINEL ONE V.3.0.4');
  console.log('====================================================');

  cleanOldFiles();

  console.log('\n[1/4] Exportando Bundle React Native + Expo...');
  if (fs.existsSync(distDir)) {
    fs.rmSync(distDir, { recursive: true, force: true });
  }
  execSync('npx expo export --platform web --output-dir dist --clear', {
    cwd: __dirname,
    stdio: 'inherit'
  });

  console.log('\n[2/4] Sincronizando assets hacia Android WebViewAssetLoader...');
  if (fs.existsSync(assetsWwwDir)) {
    fs.rmSync(assetsWwwDir, { recursive: true, force: true });
  }
  copyRecursiveSync(distDir, assetsWwwDir);
  rewriteUnderscorePathsInTextFiles(assetsWwwDir);

  const indexHtmlPath = path.join(assetsWwwDir, 'index.html');
  if (fs.existsSync(indexHtmlPath)) {
    let html = fs.readFileSync(indexHtmlPath, 'utf8');
    html = html.replace(
      /<meta name="viewport"[^>]*>/i,
      '<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0, user-scalable=no, viewport-fit=cover" />'
    );
    const lockStylesAndScript = `
    <style id="sentinel-mobile-lock">
      *, *::before, *::after {
        -webkit-user-select: none !important;
        user-select: none !important;
        -webkit-touch-callout: none !important;
        -webkit-user-drag: none !important;
        -webkit-tap-highlight-color: transparent !important;
        outline: none !important;
        outline-width: 0 !important;
        outline-style: none !important;
        outline-color: transparent !important;
      }
      input, textarea, input:focus, textarea:focus, input:focus-visible, textarea:focus-visible {
        -webkit-user-select: text !important;
        user-select: text !important;
        outline: none !important;
        outline-width: 0 !important;
        outline-style: none !important;
        outline-color: transparent !important;
        box-shadow: none !important;
      }
      html, body, #root {
        width: 100% !important;
        height: 100% !important;
        margin: 0 !important;
        padding: 0 !important;
        overflow: hidden !important;
        overscroll-behavior: none !important;
        touch-action: pan-y !important;
        background-color: #060911 !important;
      }
    </style>
    <script>
      document.addEventListener('contextmenu', function(e) { e.preventDefault(); }, { passive: false });
      document.addEventListener('selectstart', function(e) {
        var tag = e.target && e.target.tagName;
        if (tag !== 'INPUT' && tag !== 'TEXTAREA') e.preventDefault();
      }, { passive: false });
      document.addEventListener('dragstart', function(e) { e.preventDefault(); }, { passive: false });
    </script></head>`;
    html = html.replace('</head>', lockStylesAndScript);
    fs.writeFileSync(indexHtmlPath, html, 'utf8');
  }

  // Sincronizar también hacia el bundle iOS (ios/Resources/www)
  try {
    if (fs.existsSync(iosWwwDir)) {
      fs.rmSync(iosWwwDir, { recursive: true, force: true });
    }
    copyRecursiveSync(assetsWwwDir, iosWwwDir);
    console.log('✅ Bundle sincronizado hacia Android (assets/www) e iOS (ios/Resources/www).');
  } catch (_) {}

  if (onlyPrepareWww) {
    console.log('\n====================================================');
    console.log(' CONSTRUCCIÓN LISTA PARA iOS Y ANDROID (SIN COMPILAR BINARIO)');
    console.log('====================================================');
    process.exit(0);
  }

  console.log('\n[3/4] Compilando APK y AAB con Gradle (assembleRelease bundleRelease)...');
  const gradlewCmd = process.platform === 'win32'
    ? '.\\gradlew.bat assembleRelease bundleRelease'
    : './gradlew assembleRelease bundleRelease';
  execSync(gradlewCmd, {
    cwd: androidDir,
    stdio: 'inherit'
  });

  console.log('\n[4/4] Copiando APK y AAB compilados a TERMINADOS y FINAL...');
  const releaseApk = path.join(androidDir, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
  const debugApk = path.join(androidDir, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
  const sourceApk = fs.existsSync(releaseApk) ? releaseApk : debugApk;

  if (!fs.existsSync(sourceApk)) {
    throw new Error('No se encontró el archivo APK generado.');
  }

  fs.mkdirSync(finalDir, { recursive: true });
  fs.mkdirSync(terminadosDir, { recursive: true });

  const destApk = path.join(finalDir, 'SENTINEL_ONE_V3.apk');
  const destTerminadosApk = path.join(terminadosDir, 'SENTINEL_ONE_V3.apk');
  fs.copyFileSync(sourceApk, destApk);
  fs.copyFileSync(sourceApk, destTerminadosApk);

  const releaseAab = path.join(androidDir, 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');
  const destAab = path.join(finalDir, 'SENTINEL_ONE_V3.aab');
  const destTerminadosAab = path.join(terminadosDir, 'SENTINEL_ONE_V3.aab');
  if (fs.existsSync(releaseAab)) {
    fs.copyFileSync(releaseAab, destAab);
    fs.copyFileSync(releaseAab, destTerminadosAab);
  }

  const sizeMb = (fs.statSync(destTerminadosApk).size / (1024 * 1024)).toFixed(2);
  console.log('\n====================================================');
  console.log(' COMPILACIÓN EXITOSA');
  console.log(` Archivo APK (TERMINADOS): ${destTerminadosApk}`);
  if (fs.existsSync(destTerminadosAab)) {
    console.log(` Archivo AAB (TERMINADOS): ${destTerminadosAab}`);
  }
  console.log(` Archivo APK (FINAL):      ${destApk}`);
  console.log(` Tamaño APK: ${sizeMb} MB`);
  console.log('====================================================');

  if (shouldUploadPlayStore) {
    console.log('\n[5/5] Subiendo AAB directo a Google Play Console...');
    execSync('node publish-playstore.mjs', {
      cwd: __dirname,
      stdio: 'inherit'
    });
  }
} catch (err) {
  console.error('\n[ERROR DE COMPILACIÓN]:', err.message);
  process.exit(1);
}
