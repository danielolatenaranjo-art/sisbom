import fs from 'fs';
import path from 'path';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const args = process.argv.slice(2).map(a => a.toLowerCase());
const onlyPrepareWww = args.includes('--prepare-only') || args.includes('--sync-www') || args.includes('--both-www');
const onlyApk = args.includes('--apk-only');
const shouldUploadPlayStore = args.includes('--upload-playstore') || args.includes('--playstore');

const distDir = path.join(__dirname, 'dist');
const androidDir = path.join(__dirname, 'android');
const assetsWwwDir = path.join(androidDir, 'app', 'src', 'main', 'assets', 'www');
const iosWwwDir = path.join(__dirname, 'ios', 'Resources', 'www');
const finalDir = path.resolve(__dirname, '..', 'FINAL');
const terminadosDir = path.resolve(__dirname, '..', '..', 'TERMINADOS');
const logosDir = path.resolve(__dirname, '..', '..', 'LOGOS DE SENTINEL');

const medDevDir = path.resolve(__dirname, '..', '..', 'SENTINEL MED', 'DEV');
const navDevDir = path.resolve(__dirname, '..', '..', 'SENTINEL NAV', 'DEV');
const crewDevDir = path.resolve(__dirname, '..', '..', 'SENTINEL CREW', 'DEV');

const buildEnv = { ...process.env, EXPO_NO_TELEMETRY: '1' };
const defaultJbr = 'C:\\Program Files\\Android\\Android Studio\\jbr';
if (fs.existsSync(defaultJbr)) {
  buildEnv.JAVA_HOME = defaultJbr;
}

function cleanOldFiles() {
  // Sincronizar automáticamente la carpeta oficial de sonidos si existe
  const sonidosSrcDir = path.join(__dirname, 'sonidos');
  if (fs.existsSync(sonidosSrcDir)) {
    const assetsSoundsDir = path.join(__dirname, 'assets', 'sounds');
    const androidRawDir = path.join(androidDir, 'app', 'src', 'main', 'res', 'raw');
    const iosResDir = path.join(__dirname, 'ios', 'Resources');
    [assetsSoundsDir, androidRawDir, iosResDir].forEach((d) => fs.mkdirSync(d, { recursive: true }));

    const mp3s = fs.readdirSync(sonidosSrcDir).filter((f) => /\.(mp3|wav)$/i.test(f));
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

function copyRecursiveSync(src, dest, skipTopDirs = new Set()) {
  const exists = fs.existsSync(src);
  const stats = exists && fs.statSync(src);
  const isDirectory = exists && stats.isDirectory();
  if (isDirectory) {
    fs.mkdirSync(dest, { recursive: true });
    fs.readdirSync(src).forEach((childItemName) => {
      if (skipTopDirs.has(childItemName)) return;
      const targetName = childItemName === '_expo' ? 'expo_bundle' : childItemName;
      copyRecursiveSync(path.join(src, childItemName), path.join(dest, targetName));
    });
  } else if (exists) {
    fs.copyFileSync(src, dest);
  }
}

function rewritePathsInTextFiles(dir, replacements) {
  if (!fs.existsSync(dir)) return;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      rewritePathsInTextFiles(fullPath, replacements);
    } else if (/\.(html|js|mjs|css|json)$/i.test(entry.name)) {
      let content = fs.readFileSync(fullPath, 'utf8');
      let changed = false;
      for (const [fromStr, toStr] of replacements) {
        if (content.includes(fromStr)) {
          content = content.split(fromStr).join(toStr);
          changed = true;
        }
      }
      if (changed) {
        fs.writeFileSync(fullPath, content, 'utf8');
      }
    }
  }
}

function injectMobileScreenLockIntoHtml(htmlFilePath) {
  if (!fs.existsSync(htmlFilePath)) return;
  let html = fs.readFileSync(htmlFilePath, 'utf8');
  if (html.includes('id="sentinel-mobile-lock"')) return;
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
        position: fixed !important;
        inset: 0 !important;
        width: 100% !important;
        height: 100% !important;
        margin: 0 !important;
        padding: 0 !important;
        overflow: hidden !important;
        overscroll-behavior: none !important;
        touch-action: pan-y !important;
        background-color: #060911 !important;
      }
      .leaflet-container, .leaflet-container * {
        touch-action: none !important;
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
  fs.writeFileSync(htmlFilePath, html, 'utf8');
}

function copyOfficialRootLogos(targetRootDir) {
  if (!fs.existsSync(logosDir)) return;
  fs.mkdirSync(targetRootDir, { recursive: true });
  const logoPairs = [
    ['logo 1.png', 'LOGO_SENTINEL.png'],
    ['logo 2.png', 'Logo.png'],
    ['SENTINEL MED.png', 'SENTINEL MED.png'],
    ['SENTINEL MED.png', 'SENTINEL_MED.png'],
    ['SENTINEL NAV.png', 'SENTINEL NAV.png'],
    ['SENTINEL CREW.png', 'SENTINEL CREW.png'],
    ['SENTINEL CREW.png', 'SENTINEL_CREW.png'],
    ['SENTINEL ONE.png', 'SENTINEL ONE.png'],
    ['SENTINEL ONE.png', 'SENTINEL_ONE.png']
  ];
  for (const [srcName, destName] of logoPairs) {
    const srcPath = path.join(logosDir, srcName);
    if (fs.existsSync(srcPath)) {
      const existingMatch = fs
        .readdirSync(targetRootDir)
        .find((f) => f.toLowerCase() === destName.toLowerCase());
      if (existingMatch && existingMatch !== destName) {
        fs.rmSync(path.join(targetRootDir, existingMatch), { force: true });
      }
      fs.copyFileSync(srcPath, path.join(targetRootDir, destName));
    }
  }
}

try {
  console.log('====================================================');
  console.log(' EMPACANDO APK UNIFICADO: SENTINEL ONE V.3.2.0');
  console.log(' (ONE + MED Integrado + Modos Android NAV/MED/CREW)');
  console.log('====================================================');

  console.log('\n[0/5] Sincronizando SHARED y ejecutando Auditoría Anti-Bucles...');
  execSync('node "../../SHARED/sync-shared.mjs"', {
    cwd: __dirname,
    stdio: 'inherit',
    env: buildEnv
  });

  cleanOldFiles();

  console.log('\n[1/5] Exportando Bundle Principal SENTINEL ONE (React Native + Expo)...');
  if (fs.existsSync(distDir)) {
    fs.rmSync(distDir, { recursive: true, force: true });
  }
  execSync('npx expo export --platform web --output-dir dist --clear', {
    cwd: __dirname,
    stdio: 'inherit',
    env: buildEnv
  });

  console.log('\n[2/5] Sincronizando SENTINEL ONE hacia Android WebViewAssetLoader...');
  if (fs.existsSync(assetsWwwDir)) {
    fs.rmSync(assetsWwwDir, { recursive: true, force: true });
  }
  copyRecursiveSync(distDir, assetsWwwDir);
  rewritePathsInTextFiles(assetsWwwDir, [['/_expo/', '/expo_bundle/']]);
  injectMobileScreenLockIntoHtml(path.join(assetsWwwDir, 'index.html'));
  copyOfficialRootLogos(assetsWwwDir);

  console.log('\n[2B/5] Compilando e integrando SENTINEL MED (Android + iOS)...');
  execSync('npm run build', {
    cwd: medDevDir,
    stdio: 'inherit',
    env: buildEnv
  });
  const medWwwOut = path.join(assetsWwwDir, 'med');
  copyRecursiveSync(path.join(medDevDir, 'dist'), medWwwOut);
  copyOfficialRootLogos(medWwwOut);

  console.log('\n[2C/5] Compilando e integrando SENTINEL NAV (Modo Android SENTINELNAV)...');
  execSync('npm run build', {
    cwd: navDevDir,
    stdio: 'inherit',
    env: buildEnv
  });
  const navWwwOut = path.join(assetsWwwDir, 'nav');
  copyRecursiveSync(path.join(navDevDir, 'dist'), navWwwOut);
  copyOfficialRootLogos(navWwwOut);

  console.log('\n[2D/5] Compilando e integrando SENTINEL CREW (Modo Android SENTINELCREW)...');
  const crewDistDir = path.join(crewDevDir, 'dist');
  const crewCacheHome = path.join(crewDevDir, '.expo_cache_home');
  fs.mkdirSync(crewCacheHome, { recursive: true });
  if (fs.existsSync(crewDistDir)) {
    fs.rmSync(crewDistDir, { recursive: true, force: true });
  }
  execSync('npx expo export --platform web --output-dir dist --clear', {
    cwd: crewDevDir,
    stdio: 'inherit',
    env: {
      ...buildEnv,
      USERPROFILE: crewCacheHome,
      HOME: crewCacheHome
    }
  });
  try {
    fs.rmSync(crewCacheHome, { recursive: true, force: true });
  } catch (_) {}
  const crewWwwOut = path.join(assetsWwwDir, 'crew');
  copyRecursiveSync(crewDistDir, crewWwwOut);
  rewritePathsInTextFiles(crewWwwOut, [
    ['/_expo/', '/crew/expo_bundle/'],
    ['"/assets/', '"/crew/assets/'],
    ["'/assets/", "'/crew/assets/"]
  ]);
  injectMobileScreenLockIntoHtml(path.join(crewWwwOut, 'index.html'));
  copyOfficialRootLogos(crewWwwOut);

  // Sincronizar hacia el bundle iOS (ios/Resources/www): incluye SENTINEL ONE + SENTINEL MED integrado
  // y excluye nav/ y crew/ (que son exclusivos de Android)
  try {
    if (fs.existsSync(iosWwwDir)) {
      fs.rmSync(iosWwwDir, { recursive: true, force: true });
    }
    copyRecursiveSync(assetsWwwDir, iosWwwDir, new Set(['nav', 'crew']));
    console.log('✅ Bundles sincronizados hacia Android (ONE + MED + NAV + CREW) e iOS (ONE + MED).');
  } catch (_) {}

  if (onlyPrepareWww) {
    console.log('\n====================================================');
    console.log(' CONSTRUCCIÓN LISTA PARA iOS Y ANDROID (SIN COMPILAR BINARIO)');
    console.log('====================================================');
    process.exit(0);
  }

  const gradleTasks = onlyApk ? 'assembleRelease' : 'assembleRelease bundleRelease';
  console.log(`\n[3/5] Compilando ${onlyApk ? 'APK' : 'APK y AAB'} con Gradle (${gradleTasks})...`);
  const gradlewCmd = process.platform === 'win32'
    ? `.\\gradlew.bat ${gradleTasks}`
    : `./gradlew ${gradleTasks}`;
  execSync(gradlewCmd, {
    cwd: androidDir,
    stdio: 'inherit',
    env: buildEnv
  });

  console.log(`\n[4/5] Copiando ${onlyApk ? 'APK' : 'APK y AAB'} compilados a TERMINADOS y FINAL...`);
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
  if (!onlyApk && fs.existsSync(releaseAab)) {
    fs.copyFileSync(releaseAab, destAab);
    fs.copyFileSync(releaseAab, destTerminadosAab);
  }

  const sizeMb = (fs.statSync(destTerminadosApk).size / (1024 * 1024)).toFixed(2);
  console.log('\n====================================================');
  console.log(' COMPILACIÓN EXITOSA');
  console.log(` Archivo APK (TERMINADOS): ${destTerminadosApk}`);
  if (!onlyApk && fs.existsSync(destTerminadosAab)) {
    console.log(` Archivo AAB (TERMINADOS): ${destTerminadosAab}`);
  }
  console.log(` Archivo APK (FINAL):      ${destApk}`);
  console.log(` Tamaño APK: ${sizeMb} MB`);
  console.log('====================================================');

  if (shouldUploadPlayStore) {
    console.log('\n[5/5] Subiendo AAB directo a Google Play Console...');
    execSync('node publish-playstore.mjs', {
      cwd: __dirname,
      stdio: 'inherit',
      env: buildEnv
    });
  }
} catch (err) {
  console.error('\n[ERROR DE COMPILACIÓN]:', err.message);
  process.exit(1);
}
