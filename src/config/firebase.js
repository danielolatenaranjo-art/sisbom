import { initializeApp, getApps, getApp } from 'firebase/app';
import { initializeFirestore, getFirestore } from 'firebase/firestore';
import {
  initializeAuth,
  getAuth,
  browserLocalPersistence,
  inMemoryPersistence,
  signInWithEmailAndPassword,
  signInAnonymously
} from 'firebase/auth';
import { getDatabase } from 'firebase/database';

export const CENTRAL_FIREBASE_CONFIG = {
  projectId: "sisbom-central",
  apiKey: "AIzaSyBjjSBwmUevGC0vT0GdxtQUw-yvawdrH44",
  authDomain: "sisbom-central.firebaseapp.com",
  storageBucket: "sisbom-central.firebasestorage.app",
  messagingSenderId: "432594591351"
};

// Prohibido tener un proyecto de cliente o de pruebas por defecto ni usuarios preconfigurados (Ley #5).
export const DEFAULT_FIREBASE_CONFIG = null;

let app = null;
let db = null;
let auth = null;
let rtdb = null;
let authPromise = null;

function resolveCachedTenantConfig() {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const raw =
        window.localStorage.getItem('@sentinel_one_v3_license_cache') ||
        window.localStorage.getItem('saas_firebase_config') ||
        window.localStorage.getItem('firebaseConfig');
      if (raw) {
        const parsed = JSON.parse(raw);
        const cfg = parsed.firebaseConfig || (parsed.projectId ? parsed : null);
        if (cfg && cfg.projectId && cfg.apiKey) {
          return cfg;
        }
      }
    }
  } catch (_) {}
  return null;
}

function resolveCachedUserAuthCredentials() {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const savedId = String(window.localStorage.getItem('authUserId') || '').trim();
      const savedPass = String(window.localStorage.getItem('authPasswordSecure') || '').trim();
      if (savedId && savedPass) {
        return {
          email: savedId.includes('@') ? savedId : `${savedId}@sisbom.com`,
          password: savedPass.endsWith('_secure_sisbom') ? savedPass : `${savedPass}_secure_sisbom`,
          rawPassword: savedPass.replace(/_secure_sisbom$/, '')
        };
      }
      const rawSession = window.localStorage.getItem('@sentinel_one_v3_user_session');
      if (rawSession) {
        const parsed = JSON.parse(rawSession);
        const idReg = String(parsed.idRegistro || parsed.id || '').trim();
        const pass = String(parsed.contrasena || parsed.password || parsed.claveAcceso || '').trim();
        if (idReg && pass) {
          return {
            email: idReg.includes('@') ? idReg : `${idReg}@sisbom.com`,
            password: pass.endsWith('_secure_sisbom') ? pass : `${pass}_secure_sisbom`,
            rawPassword: pass.replace(/_secure_sisbom$/, '')
          };
        }
      }
    }
  } catch (_) {}
  return null;
}

function getOrInitFirestore(firebaseApp) {
  if (!firebaseApp) return null;
  try {
    return initializeFirestore(firebaseApp, {
      experimentalForceLongPolling: true,
      useFetchStreams: false
    });
  } catch {
    return getFirestore(firebaseApp);
  }
}

function getOrInitAuth(firebaseApp) {
  if (!firebaseApp) return null;
  try {
    return initializeAuth(firebaseApp, {
      persistence: [browserLocalPersistence, inMemoryPersistence]
    });
  } catch {
    return getAuth(firebaseApp);
  }
}

function getOrCreateFirebaseApp(config) {
  const cfg = config || resolveCachedTenantConfig();
  if (!cfg || !cfg.projectId || !cfg.apiKey) return null;
  const targetProjectId = String(cfg.projectId).trim();
  const appName = `tenant_${targetProjectId}`;
  const existing = getApps().find((a) => a.name === appName || a.options?.projectId === targetProjectId);
  if (existing) return existing;
  return initializeApp(cfg, appName);
}

// Asegurar autenticación de Firebase Auth para permisos de lectura/escritura de Firestore y RTDB (sin usuarios por defecto)
export async function ensureFirebaseAuth(customAuth = null, customEmail = null, customPass = null) {
  const currentAuth = customAuth || auth || getAuthInstance();
  if (!currentAuth) return null;

  if (customEmail && customPass) {
    const email = customEmail.includes('@') ? customEmail : `${String(customEmail).trim()}@sisbom.com`;
    const securePass = String(customPass).endsWith('_secure_sisbom')
      ? String(customPass)
      : `${String(customPass).trim()}_secure_sisbom`;
    try {
      const cred = await signInWithEmailAndPassword(currentAuth, email, securePass);
      return cred?.user || null;
    } catch {
      try {
        const credRaw = await signInWithEmailAndPassword(currentAuth, email, String(customPass).trim());
        return credRaw?.user || null;
      } catch (_) {}
    }
  }

  if (currentAuth.currentUser) {
    return currentAuth.currentUser;
  }

  if (authPromise) return authPromise;

  authPromise = (async () => {
    try {
      const userCreds = resolveCachedUserAuthCredentials();
      if (userCreds) {
        try {
          const cred = await Promise.race([
            signInWithEmailAndPassword(currentAuth, userCreds.email, userCreds.password),
            new Promise((_, reject) => setTimeout(() => reject(new Error('Auth timeout')), 3500))
          ]);
          if (cred?.user) return cred.user;
        } catch (_) {
          if (userCreds.rawPassword && userCreds.rawPassword !== userCreds.password) {
            try {
              const credRaw = await signInWithEmailAndPassword(currentAuth, userCreds.email, userCreds.rawPassword);
              if (credRaw?.user) return credRaw.user;
            } catch (_) {}
          }
        }
      }

      const anonCred = await Promise.race([
        signInAnonymously(currentAuth),
        new Promise((_, reject) => setTimeout(() => reject(new Error('Anon auth timeout')), 2500))
      ]);
      if (anonCred?.user) return anonCred.user;
    } catch (_) {
    } finally {
      authPromise = null;
    }
    return null;
  })();

  return authPromise;
}

export function initFirebase(customConfig = null) {
  const config = customConfig || resolveCachedTenantConfig();
  if (!config || !config.projectId || !config.apiKey) {
    return { app: null, db: null, auth: null };
  }

  try {
    app = getOrCreateFirebaseApp(config);
    if (!app) return { app: null, db: null, auth: null };
    db = getOrInitFirestore(app);
    auth = getOrInitAuth(app);

    ensureFirebaseAuth(auth);

    return { app, db, auth };
  } catch (err) {
    console.warn('Error inicializando Firebase:', err);
    return { app, db, auth };
  }
}

export async function reinitFirebaseWithConfig(newConfig) {
  if (!newConfig || !newConfig.projectId) return { app, db, auth, rtdb };
  try {
    if (app && app.options && app.options.projectId === newConfig.projectId) {
      if (!db) db = getOrInitFirestore(app);
      if (!auth) auth = getOrInitAuth(app);
      ensureFirebaseAuth(auth);
      return { app, db, auth, rtdb: getRtdb() };
    }

    app = getOrCreateFirebaseApp(newConfig);
    db = getOrInitFirestore(app);
    auth = getOrInitAuth(app);
    rtdb = null;
    ensureFirebaseAuth(auth);
    console.log(`🔥 [Firebase] Inicializado para proyecto: ${newConfig.projectId}`);
    return { app, db, auth, rtdb: getRtdb() };
  } catch (err) {
    console.warn('Error en reinitFirebaseWithConfig:', err);
    return { app, db, auth, rtdb };
  }
}

export function getDb() {
  if (!db) {
    initFirebase();
  }
  return db;
}

export function getAuthInstance() {
  if (!auth) {
    initFirebase();
  }
  return auth;
}

export function getRtdb() {
  if (!app) {
    initFirebase();
  }
  if (!rtdb && app) {
    const config = app.options || resolveCachedTenantConfig() || {};
    if (!config.projectId) return null;
    const dbUrl = config.databaseURL || `https://${config.projectId}-default-rtdb.firebaseio.com`;
    try {
      rtdb = getDatabase(app, dbUrl);
    } catch {
      try {
        rtdb = getDatabase(app);
      } catch (e) {
        console.warn('⚠️ [Firebase RTDB] No se pudo inicializar Realtime Database:', e.message);
      }
    }
  }
  return rtdb;
}

// Parser canónico para respuestas de Firestore REST (retrocompatibilidad)
export function parseFirestoreValue(v) {
  if (!v || typeof v !== 'object') return v;
  if ('stringValue' in v) return v.stringValue;
  if ('booleanValue' in v) return v.booleanValue;
  if ('integerValue' in v) return parseInt(v.integerValue, 10);
  if ('doubleValue' in v) return parseFloat(v.doubleValue);
  if ('timestampValue' in v) return new Date(v.timestampValue).getTime();
  if ('mapValue' in v) {
    const fields = v.mapValue?.fields || {};
    const res = {};
    for (const k of Object.keys(fields)) {
      res[k] = parseFirestoreValue(fields[k]);
    }
    return res;
  }
  if ('arrayValue' in v) {
    const vals = v.arrayValue?.values || [];
    return vals.map(parseFirestoreValue);
  }
  return v;
}

// Caché de tokens de Firebase Auth en memoria para REST (indexado por apiKey)
const cachedAuthTokensByKey = new Map();

export async function getFirebaseAuthToken(apiKey, email = null, password = null) {
  if (!apiKey) return null;
  const now = Date.now();
  const cached = cachedAuthTokensByKey.get(apiKey);
  if (cached && cached.token && now < cached.expiresAt - 60000) {
    return cached.token;
  }

  // Si ya hay un usuario autenticado en el SDK de Firebase Auth para esta app, usar su idToken directo
  try {
    if (auth && auth.currentUser) {
      const sdkToken = await auth.currentUser.getIdToken();
      if (sdkToken) {
        cachedAuthTokensByKey.set(apiKey, {
          token: sdkToken,
          expiresAt: now + 3000 * 1000
        });
        return sdkToken;
      }
    }
  } catch (_) {}

  const creds = email && password ? { email, password } : resolveCachedUserAuthCredentials();
  try {
    if (creds && creds.email && creds.password) {
      const res = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${apiKey}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: creds.email, password: creds.password, returnSecureToken: true })
      });

      if (res.ok) {
        const data = await res.json();
        const expiresInSec = parseInt(data.expiresIn || '3600', 10);
        cachedAuthTokensByKey.set(apiKey, {
          token: data.idToken,
          expiresAt: now + (expiresInSec * 1000)
        });
        return data.idToken;
      }
    }

    // Respaldo: sesión anónima vía REST (sin usuarios hardcodeados)
    const anonRes = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${apiKey}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ returnSecureToken: true })
    });
    if (anonRes.ok) {
      const anonData = await anonRes.json();
      const expiresInSec = parseInt(anonData.expiresIn || '3600', 10);
      cachedAuthTokensByKey.set(apiKey, {
        token: anonData.idToken,
        expiresAt: now + (expiresInSec * 1000)
      });
      return anonData.idToken;
    }
  } catch (err) {
    console.warn('Error obtaining Firebase Auth token:', err);
  }
  return null;
}

export async function fetchFirestoreWithAuth(url, apiKey, options = {}) {
  const token = await getFirebaseAuthToken(apiKey);
  const headers = {
    'Accept': 'application/json',
    ...(options.headers || {})
  };

  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  return fetch(url, { ...options, headers });
}
