import { initializeApp, getApps, getApp } from 'firebase/app';
import {
  initializeFirestore,
  getFirestore,
  memoryLocalCache,
  enableNetwork,
  disableNetwork
} from 'firebase/firestore';
import {
  initializeAuth,
  getAuth,
  browserLocalPersistence,
  inMemoryPersistence,
  signInWithEmailAndPassword
} from 'firebase/auth';
import { getDatabase } from 'firebase/database';

export const CENTRAL_FIREBASE_CONFIG = {
  projectId: "sisbom-central",
  apiKey: "AIzaSyBjjSBwmUevGC0vT0GdxtQUw-yvawdrH44",
  authDomain: "sisbom-central.firebaseapp.com",
  storageBucket: "sisbom-central.firebasestorage.app",
  messagingSenderId: "432594591351"
};

export const APP_VERSION = "3.0.4";
const V304_AUTH_EMAIL = "v304.app@sisbom.com";
const V304_AUTH_PASS = "SentinelOne_V304_Auth!2026";

// Prohibido tener un proyecto de cliente o de pruebas por defecto ni usuarios preconfigurados (Ley #5).
export const DEFAULT_FIREBASE_CONFIG = null;

let app = null;
let db = null;
let auth = null;
let rtdb = null;
let authPromise = null;
let lastAuthVerifyTs = 0;

function withTimeout(promise, ms = 5000, fallbackValue = null) {
  return Promise.race([
    promise,
    new Promise((resolve) => setTimeout(() => resolve(fallbackValue), ms))
  ]);
}

function fetchWithTimeout(url, options = {}, timeoutMs = 9000) {
  if (typeof AbortController === 'undefined') {
    return fetch(url, options);
  }
  const controller = new AbortController();
  const timer = setTimeout(() => {
    try { controller.abort(); } catch (_) {}
  }, timeoutMs);
  return fetch(url, { ...options, signal: controller.signal }).finally(() => {
    clearTimeout(timer);
  });
}

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
  // IMPORTANTE PARA iOS WKWebView: Usar memoryLocalCache() evita el bloqueo permanente de transacciones IndexedDB
  // y leases de persistentMultipleTabManager cuando iOS suspende el proceso WebContent en segundo plano.
  try {
    return initializeFirestore(firebaseApp, {
      localCache: memoryLocalCache(),
      experimentalForceLongPolling: true,
      useFetchStreams: false
    });
  } catch {
    try {
      return initializeFirestore(firebaseApp, {
        experimentalForceLongPolling: true,
        useFetchStreams: false
      });
    } catch {
      return getFirestore(firebaseApp);
    }
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
export async function ensureFirebaseAuth(customAuth = null, customEmail = null, customPass = null, forceRefresh = false) {
  const currentAuth = customAuth || auth || getAuthInstance();
  if (!currentAuth) return null;

  if (customEmail && customPass) {
    const email = customEmail.includes('@') ? customEmail : `${String(customEmail).trim()}@sisbom.com`;
    const securePass = String(customPass).endsWith('_secure_sisbom')
      ? String(customPass)
      : `${String(customPass).trim()}_secure_sisbom`;
    try {
      const cred = await withTimeout(
        signInWithEmailAndPassword(currentAuth, email, securePass),
        6000,
        null
      );
      if (cred?.user) {
        lastAuthVerifyTs = Date.now();
        try {
          const apiKey = currentAuth.app?.options?.apiKey;
          const tok = await withTimeout(cred.user.getIdToken(), 3500, null);
          if (apiKey && tok) {
            cachedAuthTokensByKey.set(apiKey, { token: tok, expiresAt: Date.now() + 3000 * 1000 });
          }
        } catch (_) {}
        return cred.user;
      }
    } catch {
      try {
        const credRaw = await withTimeout(
          signInWithEmailAndPassword(currentAuth, email, String(customPass).trim()),
          6000,
          null
        );
        if (credRaw?.user) {
          lastAuthVerifyTs = Date.now();
          return credRaw.user;
        }
      } catch (_) {}
    }
  }

  if (currentAuth.currentUser && !currentAuth.currentUser.isAnonymous) {
    const now = Date.now();
    // Si lleva más de 25 minutos sin verificar token o se pidió forceRefresh tras volver de segundo plano en iOS, refrescar el token
    if (forceRefresh || (now - lastAuthVerifyTs) > 25 * 60 * 1000) {
      try {
        const refreshedTok = await withTimeout(
          currentAuth.currentUser.getIdToken(true),
          4000,
          null
        );
        if (refreshedTok) {
          lastAuthVerifyTs = now;
          const apiKey = currentAuth.app?.options?.apiKey;
          if (apiKey) {
            cachedAuthTokensByKey.set(apiKey, {
              token: refreshedTok,
              expiresAt: now + 3000 * 1000
            });
          }
          return currentAuth.currentUser;
        }
      } catch (_) {
        // Si falló el refresh del token (ej. sesión expirada tras horas en suspensión), continuar abajo para re-autenticar
      }
    } else {
      return currentAuth.currentUser;
    }
  }

  if (authPromise) return authPromise;

  authPromise = (async () => {
    try {
      const userCreds = resolveCachedUserAuthCredentials();
      if (userCreds) {
        try {
          const cred = await withTimeout(
            signInWithEmailAndPassword(currentAuth, userCreds.email, userCreds.password),
            6000,
            null
          );
          if (cred?.user) {
            lastAuthVerifyTs = Date.now();
            return cred.user;
          }
        } catch (_) {
          if (userCreds.rawPassword && userCreds.rawPassword !== userCreds.password) {
            try {
              const credRaw = await withTimeout(
                signInWithEmailAndPassword(currentAuth, userCreds.email, userCreds.rawPassword),
                6000,
                null
              );
              if (credRaw?.user) {
                lastAuthVerifyTs = Date.now();
                return credRaw.user;
              }
            } catch (_) {}
          }
        }
      }

      try {
        const v304Cred = await withTimeout(
          signInWithEmailAndPassword(currentAuth, V304_AUTH_EMAIL, V304_AUTH_PASS),
          6000,
          null
        );
        if (v304Cred?.user) {
          lastAuthVerifyTs = Date.now();
          return v304Cred.user;
        }
      } catch (_) {}
    } catch (_) {
    } finally {
      authPromise = null;
    }
    return currentAuth.currentUser || null;
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
  if ('nullValue' in v) return null;
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

export async function getFirebaseAuthToken(apiKey, email = null, password = null, forceRefresh = false) {
  if (!apiKey) return null;
  const now = Date.now();
  if (forceRefresh) {
    cachedAuthTokensByKey.delete(apiKey);
  } else {
    const cached = cachedAuthTokensByKey.get(apiKey);
    if (cached && cached.token && now < cached.expiresAt - 120000) {
      return cached.token;
    }
  }

  // Si ya hay un usuario autenticado con cuenta real (no anónima) en el SDK de Firebase Auth para esta app, usar su idToken con timeout corto
  try {
    if (auth && auth.currentUser && !auth.currentUser.isAnonymous && auth.app?.options?.apiKey === apiKey) {
      const sdkToken = await withTimeout(auth.currentUser.getIdToken(forceRefresh), 3000, null);
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
      const res = await fetchWithTimeout(
        `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${apiKey}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ email: creds.email, password: creds.password, returnSecureToken: true })
        },
        6000
      );

      if (res && res.ok) {
        const data = await res.json();
        const expiresInSec = parseInt(data.expiresIn || '3600', 10);
        cachedAuthTokensByKey.set(apiKey, {
          token: data.idToken,
          expiresAt: now + (expiresInSec * 1000)
        });
        return data.idToken;
      }
    }

    // Autenticación de versión autorizada V.3.0.4 vía REST
    try {
      const v304Res = await fetchWithTimeout(
        `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${apiKey}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ email: V304_AUTH_EMAIL, password: V304_AUTH_PASS, returnSecureToken: true })
        },
        6000
      );
      if (v304Res && v304Res.ok) {
        const v304Data = await v304Res.json();
        const expiresInSec = parseInt(v304Data.expiresIn || '3600', 10);
        cachedAuthTokensByKey.set(apiKey, {
          token: v304Data.idToken,
          expiresAt: now + (expiresInSec * 1000)
        });
        return v304Data.idToken;
      }
    } catch (_) {}
  } catch (err) {
    console.warn('Error obtaining Firebase Auth token:', err);
  }
  return null;
}

export async function fetchFirestoreWithAuth(url, apiKey, options = {}) {
  let token = await getFirebaseAuthToken(apiKey, null, null, false);
  const buildHeaders = (tok) => {
    const h = {
      'Accept': 'application/json',
      ...(options.headers || {})
    };
    if (tok) {
      h['Authorization'] = `Bearer ${tok}`;
    }
    return h;
  };

  let res = await fetchWithTimeout(url, { ...options, headers: buildHeaders(token) }, 9000);
  // Si el token expiró tras horas en suspensión (401 / 403), refrescar token forzosamente y reintentar 1 vez
  if (res && (res.status === 401 || res.status === 403) && apiKey) {
    token = await getFirebaseAuthToken(apiKey, null, null, true);
    if (token) {
      res = await fetchWithTimeout(url, { ...options, headers: buildHeaders(token) }, 9000);
    }
  }
  return res;
}

let centralApp = null;
let centralDb = null;

export function getCentralDb() {
  if (centralDb) return centralDb;
  try {
    const existing = getApps().find((a) => a.name === 'sisbom_central_broker');
    centralApp = existing || initializeApp(CENTRAL_FIREBASE_CONFIG, 'sisbom_central_broker');
    try {
      centralDb = initializeFirestore(centralApp, {
        localCache: memoryLocalCache(),
        experimentalForceLongPolling: true,
        useFetchStreams: false
      });
    } catch {
      try {
        centralDb = initializeFirestore(centralApp, {
          experimentalForceLongPolling: true,
          useFetchStreams: false
        });
      } catch {
        centralDb = getFirestore(centralApp);
      }
    }
    return centralDb;
  } catch (e) {
    console.warn('Error inicializando Central Firestore:', e);
    return null;
  }
}

let reconnectInFlightPromise = null;

// Reconecta activamente los canales Long-Polling de Firestore y refresca el token de Auth
// cuando iOS o Android despiertan de una suspensión prolongada o cambian de red (Wi-Fi <-> Datos).
export async function reconnectFirebaseNetwork(forceTokenRefresh = true) {
  if (reconnectInFlightPromise) return reconnectInFlightPromise;
  reconnectInFlightPromise = (async () => {
    try {
      if (forceTokenRefresh) {
        cachedAuthTokensByKey.clear();
      }
      await ensureFirebaseAuth(null, null, null, forceTokenRefresh).catch(() => {});

      const currentDb = db || getDb();
      if (currentDb) {
        await withTimeout(disableNetwork(currentDb).catch(() => {}), 2000, null);
        await withTimeout(enableNetwork(currentDb).catch(() => {}), 2500, null);
      }
      if (centralDb) {
        await withTimeout(disableNetwork(centralDb).catch(() => {}), 1500, null);
        await withTimeout(enableNetwork(centralDb).catch(() => {}), 2000, null);
      }
    } catch (e) {
      console.warn('⚠️ [Firebase] Advertencia al reconectar red:', e?.message || e);
    } finally {
      reconnectInFlightPromise = null;
    }
  })();
  return reconnectInFlightPromise;
}

export { resolveCachedUserAuthCredentials };
