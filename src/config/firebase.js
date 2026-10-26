import { initializeApp, getApps, getApp, deleteApp } from 'firebase/app';
import { getFirestore } from 'firebase/firestore';
import { getAuth, signInWithEmailAndPassword } from 'firebase/auth';
import { getDatabase } from 'firebase/database';

export const CENTRAL_FIREBASE_CONFIG = {
  projectId: "sisbom-central",
  apiKey: "AIzaSyBjjSBwmUevGC0vT0GdxtQUw-yvawdrH44",
  authDomain: "sisbom-central.firebaseapp.com",
  storageBucket: "sisbom-central.firebasestorage.app",
  messagingSenderId: "432594591351"
};

export const DEFAULT_FIREBASE_CONFIG = {
  projectId: "pruebas-sisbom",
  apiKey: "AIzaSyDyQc_gHlHocYJ5x8Wj0EZdSGSvbt8d9og",
  authDomain: "pruebas-sisbom.firebaseapp.com",
  storageBucket: "pruebas-sisbom.firebasestorage.app",
  messagingSenderId: "306101642399",
  appId: "1:306101642399:web:0f26dd8b37eb90ce91aeaa",
  databaseURL: "https://pruebas-sisbom-default-rtdb.firebaseio.com"
};

let app = null;
let db = null;
let auth = null;
let rtdb = null;
let authPromise = null;

// Asegurar autenticación de Firebase Auth para permisos de lectura/escritura de Firestore y RTDB
export async function ensureFirebaseAuth(customAuth = null) {
  const currentAuth = customAuth || auth || getAuthInstance();
  if (!currentAuth) return null;

  if (currentAuth.currentUser) {
    return currentAuth.currentUser;
  }

  if (authPromise) return authPromise;

  authPromise = (async () => {
    try {
      const cred = await signInWithEmailAndPassword(
        currentAuth,
        '137@sisbom.com',
        'ADASME137_secure_sisbom'
      );
      console.log('🔥 [Firebase Auth] Autenticado exitosamente para Firestore y RTDB.');
      return cred.user;
    } catch (err) {
      console.warn('⚠️ [Firebase Auth] Advertencia autenticando usuario maestro:', err.message);
      return null;
    } finally {
      authPromise = null;
    }
  })();

  return authPromise;
}

export function initFirebase(customConfig = null) {
  const config = customConfig || DEFAULT_FIREBASE_CONFIG;

  try {
    const existingApps = getApps();
    if (existingApps.length === 0) {
      app = initializeApp(config);
    } else {
      app = getApp();
    }

    db = getFirestore(app);
    auth = getAuth(app);

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
    const currentApp = app || (getApps().length ? getApp() : null);
    if (currentApp && currentApp.options && currentApp.options.projectId === newConfig.projectId) {
      if (!db) db = getFirestore(currentApp);
      if (!auth) auth = getAuth(currentApp);
      ensureFirebaseAuth(auth);
      return { app: currentApp, db, auth, rtdb: getRtdb() };
    }

    const existingApps = getApps();
    for (const a of existingApps) {
      try {
        await deleteApp(a);
      } catch (e) {}
    }

    app = initializeApp(newConfig);
    db = getFirestore(app);
    auth = getAuth(app);
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
    const config = app.options || DEFAULT_FIREBASE_CONFIG;
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

export async function getFirebaseAuthToken(apiKey, email = '137@sisbom.com', password = 'ADASME137_secure_sisbom') {
  const now = Date.now();
  const cached = cachedAuthTokensByKey.get(apiKey);
  if (cached && cached.token && now < cached.expiresAt - 60000) {
    return cached.token;
  }

  try {
    const res = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${apiKey}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password, returnSecureToken: true })
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
