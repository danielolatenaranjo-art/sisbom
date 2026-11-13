import fs from 'fs';
import os from 'os';
import path from 'path';
import crypto from 'crypto';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PACKAGE_NAME = 'com.sisbom.misisbom';
const SERVICE_ACCOUNT_EMAIL = 'play-console-deployer@sisbom-central.iam.gserviceaccount.com';
const DEFAULT_KEY_PATH = path.join(__dirname, 'play-service-account.json');
const TERMINADOS_AAB = path.resolve(__dirname, '..', '..', 'TERMINADOS', 'SENTINEL_ONE_V3.aab');
const FINAL_AAB = path.resolve(__dirname, '..', 'FINAL', 'SENTINEL_ONE_V3.aab');
const GRADLE_AAB = path.join(__dirname, 'android', 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');

const args = process.argv.slice(2);
const trackArg = args.find((a) => a.startsWith('--track='))?.split('=')[1] || 'production';
const statusArg = args.find((a) => a.startsWith('--status='))?.split('=')[1] || 'completed';

function base64UrlEncode(input) {
  const buf = Buffer.isBuffer(input) ? input : Buffer.from(String(input), 'utf8');
  return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

async function getAccessTokenFromJsonKey(serviceAccount) {
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: 'RS256', typ: 'JWT' };
  const payload = {
    iss: serviceAccount.client_email,
    scope: 'https://www.googleapis.com/auth/androidpublisher',
    aud: serviceAccount.token_uri || 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600
  };

  const unsignedJwt = `${base64UrlEncode(JSON.stringify(header))}.${base64UrlEncode(JSON.stringify(payload))}`;
  const signer = crypto.createSign('RSA-SHA256');
  signer.update(unsignedJwt);
  signer.end();
  const signature = signer.sign(serviceAccount.private_key);
  const signedJwt = `${unsignedJwt}.${base64UrlEncode(signature)}`;

  const res = await fetch(payload.aud, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: signedJwt
    })
  });

  const data = await res.json();
  if (!res.ok || !data.access_token) {
    throw new Error(`Error obteniendo token OAuth2 por JSON key: ${JSON.stringify(data)}`);
  }
  return { accessToken: data.access_token, email: serviceAccount.client_email };
}

async function getAccessTokenViaImpersonation() {
  const fbCfgPath = path.join(os.homedir(), '.config', 'configstore', 'firebase-tools.json');
  if (!fs.existsSync(fbCfgPath)) {
    throw new Error('No se encontró sesión local de Google/Firebase ni archivo play-service-account.json.');
  }

  let fbCfg = JSON.parse(fs.readFileSync(fbCfgPath, 'utf8'));
  const expiresAt = Number(fbCfg?.tokens?.expires_at || 0);
  if (!fbCfg?.tokens?.access_token || Date.now() > expiresAt - 300000) {
    try {
      execSync('npx --yes firebase-tools projects:list --json', { stdio: 'ignore' });
      fbCfg = JSON.parse(fs.readFileSync(fbCfgPath, 'utf8'));
    } catch (_) {}
  }

  const userToken = fbCfg?.tokens?.access_token;
  if (!userToken) {
    throw new Error('No se pudo obtener un token activo desde firebase-tools.json.');
  }

  const res = await fetch(
    `https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/${SERVICE_ACCOUNT_EMAIL}:generateAccessToken`,
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${userToken}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        scope: ['https://www.googleapis.com/auth/androidpublisher'],
        lifetime: '3600s'
      })
    }
  );

  const data = await res.json();
  if (!res.ok || !data.accessToken) {
    throw new Error(
      `Error generando token de Google Play (${res.status}): ${data?.error?.message || JSON.stringify(data)}`
    );
  }

  return { accessToken: data.accessToken, email: SERVICE_ACCOUNT_EMAIL };
}

async function getPlayConsoleAuth() {
  if (fs.existsSync(DEFAULT_KEY_PATH)) {
    const sa = JSON.parse(fs.readFileSync(DEFAULT_KEY_PATH, 'utf8'));
    return getAccessTokenFromJsonKey(sa);
  }
  return getAccessTokenViaImpersonation();
}

function resolveAabPath() {
  for (const candidate of [TERMINADOS_AAB, FINAL_AAB, GRADLE_AAB]) {
    if (fs.existsSync(candidate)) return candidate;
  }
  return null;
}

export async function uploadAabToPlayConsole({
  track = trackArg,
  releaseStatus = statusArg,
  releaseNotes = 'SENTINEL ONE V3.1.0 - GPS de Unidades y Personal en Servicio a pantalla completa con coordenadas exactas de cuartel, sincronización instantánea de estados (incluyendo modoAdmin y doble toque para liberar permisos/licencias/CDS) y mejoras de estabilidad.'
} = {}) {
  console.log('====================================================');
  console.log(' SUBIDA DIRECTA A GOOGLE PLAY CONSOLE (SIN GITHUB)');
  console.log('====================================================');

  const aabPath = resolveAabPath();
  if (!aabPath) {
    throw new Error(
      `No se encontró el archivo SENTINEL_ONE_V3.aab en TERMINADOS ni en android/app/build/outputs.`
    );
  }

  console.log('\n[1/4] Autenticando con Google Play Android Developer API...');
  const { accessToken: token, email: clientEmail } = await getPlayConsoleAuth();

  const aabSizeMb = (fs.statSync(aabPath).size / (1024 * 1024)).toFixed(2);
  console.log(`📦 Paquete:          ${PACKAGE_NAME}`);
  console.log(`🔑 Cuenta Servicio:  ${clientEmail}`);
  console.log(`📁 Archivo AAB:      ${aabPath} (${aabSizeMb} MB)`);
  console.log(`🎯 Canal (Track):    ${track}`);

  const authHeaders = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json'
  };

  const baseUrl = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE_NAME}`;
  const uploadUrl = `https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/${PACKAGE_NAME}`;

  console.log('\n[2/4] Creando sesión de edición en Google Play Console...');
  const editRes = await fetch(`${baseUrl}/edits`, {
    method: 'POST',
    headers: authHeaders,
    body: JSON.stringify({})
  });
  const editData = await editRes.json();

  if (!editRes.ok || !editData.id) {
    const errMsg = editData?.error?.message || JSON.stringify(editData);
    if (editRes.status === 401 || editRes.status === 403) {
      console.error('\n⚠️  FALTA AUTORIZAR LA CUENTA DE SERVICIO EN GOOGLE PLAY CONSOLE (SOLO 1 VEZ):');
      console.error('-------------------------------------------------------------------------------');
      console.error('1. Abre https://play.google.com/console -> "Usuarios y permisos"');
      console.error('2. Haz clic en "Invitar a nuevos usuarios"');
      console.error(`3. Pega este correo exacto:\n   👉 ${clientEmail}`);
      console.error('4. En "Permisos de la aplicación", agrega SENTINEL ONE (com.sisbom.misisbom)');
      console.error('   o marca "Administrador (todos los permisos)" en Permisos de cuenta.');
      console.error('5. Haz clic en "Invitar usuario" -> "Enviar invitación".');
      console.error('6. ¡Listo! Vuelve a ejecutar este comando y subirá directo desde tu PC.');
      console.error('-------------------------------------------------------------------------------\n');
    }
    throw new Error(`No se pudo iniciar sesión de edición (${editRes.status}): ${errMsg}`);
  }

  const editId = editData.id;
  console.log(`✅ Sesión de edición creada (ID: ${editId})`);

  console.log('[3/4] Subiendo SENTINEL_ONE_V3.aab directo a Google Play Console...');
  const aabBuffer = fs.readFileSync(aabPath);
  const bundleRes = await fetch(`${uploadUrl}/edits/${editId}/bundles?uploadType=media`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/octet-stream'
    },
    body: aabBuffer
  });
  const bundleData = await bundleRes.json();

  if (!bundleRes.ok || !bundleData.versionCode) {
    throw new Error(
      `Error al subir el AAB (${bundleRes.status}): ${bundleData?.error?.message || JSON.stringify(bundleData)}`
    );
  }

  const versionCode = bundleData.versionCode;
  console.log(`✅ AAB subido exitosamente -> versionCode: ${versionCode} (SHA256: ${bundleData.sha256?.slice(0, 16)}...)`);

  console.log(`[4/4] Asignando versionCode ${versionCode} al canal "${track}" y confirmando lanzamiento...`);
  const buildTrackPayload = (status) => ({
    track,
    releases: [
      {
        name: `3.1.0 (${versionCode})`,
        versionCodes: [String(versionCode)],
        status,
        releaseNotes: [
          {
            language: 'es-419',
            text: releaseNotes
          }
        ]
      }
    ]
  });

  let trackRes = await fetch(`${baseUrl}/edits/${editId}/tracks/${track}`, {
    method: 'PUT',
    headers: authHeaders,
    body: JSON.stringify(buildTrackPayload(releaseStatus))
  });
  let trackData = await trackRes.json();

  if (!trackRes.ok && releaseStatus !== 'draft') {
    console.log('ℹ️  Reintentando asignación en modo borrador (status: "draft")...');
    trackRes = await fetch(`${baseUrl}/edits/${editId}/tracks/${track}`, {
      method: 'PUT',
      headers: authHeaders,
      body: JSON.stringify(buildTrackPayload('draft'))
    });
    trackData = await trackRes.json();
  }

  if (!trackRes.ok) {
    throw new Error(
      `Error asignando canal "${track}" (${trackRes.status}): ${trackData?.error?.message || JSON.stringify(trackData)}`
    );
  }

  let commitRes = await fetch(`${baseUrl}/edits/${editId}:commit`, {
    method: 'POST',
    headers: authHeaders
  });
  let commitData = await commitRes.json();

  if (!commitRes.ok) {
    commitRes = await fetch(`${baseUrl}/edits/${editId}:commit?changesNotSentForReview=true`, {
      method: 'POST',
      headers: authHeaders
    });
    commitData = await commitRes.json();
  }

  if (!commitRes.ok) {
    throw new Error(
      `Error confirmando publicación (${commitRes.status}): ${commitData?.error?.message || JSON.stringify(commitData)}`
    );
  }

  console.log('\n====================================================');
  console.log(' 🎉 PUBLICACIÓN EN GOOGLE PLAY CONSOLE EXITOSA');
  console.log(` App:         ${PACKAGE_NAME}`);
  console.log(` VersionCode: ${versionCode}`);
  console.log(` Canal:       ${track}`);
  console.log('====================================================');
  return { versionCode, track, editId: commitData.id };
}

if (process.argv[1] && path.resolve(process.argv[1]) === __filename) {
  uploadAabToPlayConsole().catch((err) => {
    console.error('\n[ERROR PLAY CONSOLE]:', err.message);
    process.exit(1);
  });
}
