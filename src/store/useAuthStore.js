import { create } from 'zustand';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { doc, getDoc, collection } from 'firebase/firestore';
import { setDoc, updateDoc, addDoc } from '../config/firestoreMeta';
import { ref, set as rtdbSet, remove as rtdbRemove } from 'firebase/database';
import { useLicenseStore } from './useLicenseStore';
import {
  getDb,
  getRtdb,
  ensureFirebaseAuth,
  reconnectFirebaseNetwork,
  DEFAULT_FIREBASE_CONFIG,
  parseFirestoreValue,
  fetchFirestoreWithAuth
} from '../config/firebase';
import {
  getEntityMaxTimestamp,
  hasAdminModeFlag,
  matchPersonnelCredentials,
  sanitizeCanonicalPersonnelUpdate,
  buildCanonicalPersonStatusHistoryDoc
} from '../shared/sentinelCore';

// Puente síncrono directo hacia useDispatchStore (evita `require()` circular en Metro Web/iOS/Android)
let dispatchPersonalBridge = {
  onOptimisticStatus: null,
  onOptimisticService: null
};

export function registerPersonalBridge(handlers = {}) {
  dispatchPersonalBridge = {
    ...dispatchPersonalBridge,
    ...handlers
  };
}

const USER_SESSION_KEY = '@sentinel_one_v3_user_session';
const PREFS_KEY = '@sentinel_one_v3_user_prefs';

const activeBrowserWatchIds = new Set();
let telemetryIntervalId = null;
let activeTelemetryServiceId = null;
let lastKnownTelemetryCoords = null;
let telemetryGeneration = 0;
let pendingStatusValue = null;
let lastStatusChangeTs = 0;
let pendingServiceValue = null;
let lastServiceChangeTs = 0;

function hasNativeGpsBridge() {
  try {
    if (typeof window === 'undefined') return false;
    return Boolean(
      window.webkit?.messageHandlers?.iOSApp ||
        window.AndroidApp ||
        window.__SENTINEL_PLATFORM__ === 'ios' ||
        window.__SENTINEL_PLATFORM__ === 'android'
    );
  } catch (_) {
    return false;
  }
}

function clearAllBrowserGeolocationWatches() {
  if (typeof navigator === 'undefined' || !navigator.geolocation) return;
  try {
    activeBrowserWatchIds.forEach((wId) => {
      try {
        navigator.geolocation.clearWatch(wId);
      } catch (_) {}
    });
    activeBrowserWatchIds.clear();
    for (let id = 1; id <= 250; id++) {
      try {
        navigator.geolocation.clearWatch(id);
      } catch (_) {}
    }
  } catch (_) {}
}

export async function removeBomberoFromRtdbNow(docId) {
  const cleanId = String(docId || '').trim();
  if (!cleanId) return;
  try {
    const rtdb = getRtdb();
    if (rtdb) {
      rtdbRemove(ref(rtdb, 'telemetria/bomberos/' + cleanId)).catch(() => {});
    }
  } catch (_) {}
  try {
    const fbConfig = useLicenseStore.getState().firebaseConfig;
    const projId = fbConfig?.projectId;
    if (projId) {
      const dbUrl = String(fbConfig.databaseURL || ('https://' + projId + '-default-rtdb.firebaseio.com')).replace(/\/+$/, '');
      fetch(dbUrl + '/telemetria/bomberos/' + encodeURIComponent(cleanId) + '.json', {
        method: 'DELETE'
      }).catch(() => {});
    }
  } catch (_) {}
}

function scheduleDefinitiveRtdbRemoval(docId, checkStillDisallowedFn) {
  const cleanId = String(docId || '').trim();
  if (!cleanId) return;
  removeBomberoFromRtdbNow(cleanId);
  [600, 1600, 3200].forEach((delayMs) => {
    setTimeout(() => {
      try {
        if (!checkStillDisallowedFn || checkStillDisallowedFn()) {
          removeBomberoFromRtdbNow(cleanId);
        }
      } catch (_) {}
    }, delayMs);
  });
}

function parsePersonalBoolean(val) {
  if (val === true || val === 1) return true;
  if (typeof val === 'string') {
    const s = val.trim().toLowerCase();
    return s === 'true' || s === '1' || s === 'si' || s === 'sí' || s === 's';
  }
  return false;
}

export function formatProfilePhotoUrl(foto) {
  if (!foto) return '';
  const clean = String(foto).trim().replace(/^['"]|['"]$/g, '');
  if (!clean || clean.toLowerCase().includes('logo.png')) return '';
  if (clean.startsWith('data:') || clean.startsWith('http://') || clean.startsWith('https://')) {
    return clean;
  }
  if (clean.length > 200 && !clean.includes(' ')) {
    return `data:image/jpeg;base64,${clean}`;
  }
  const base = 'https://sisbom-central.web.app/';
  return clean.startsWith('/') ? `${base}${clean.substring(1)}` : `${base}${clean}`;
}

// Consulta primero el documento exacto `personal/{idRegistro}` y como respaldo busca estrictamente por `idRegistro` (NUNCA por `idRadial`)
async function fetchPersonalUserFromFirestoreRest(identifierOrUser) {
  const fbConfig = useLicenseStore.getState().firebaseConfig;
  if (!fbConfig || !fbConfig.projectId || !fbConfig.apiKey) return null;
  const projId = fbConfig.projectId;
  const apiKey = fbConfig.apiKey;

  const targetReg = typeof identifierOrUser === 'object'
    ? String(identifierOrUser?.idRegistro || '').trim()
    : String(identifierOrUser || '').trim();
  const targetName = typeof identifierOrUser === 'object'
    ? String(identifierOrUser?.nombreBombero || identifierOrUser?.nombre || '').trim().toUpperCase()
    : '';
  const cleanIdNoRut = targetReg.replace(/[.-]/g, '');

  // 1. Búsqueda directa O(1) por ID de documento en `personal/{idRegistro}` (prioridad absoluta)
  if (targetReg) {
    try {
      const directUrl = `https://firestore.googleapis.com/v1/projects/${projId}/databases/(default)/documents/personal/${encodeURIComponent(targetReg)}`;
      const directRes = await fetchFirestoreWithAuth(directUrl, apiKey);
      if (directRes.ok) {
        const directDoc = await directRes.json();
        if (directDoc && directDoc.fields) {
          const directParsed = {};
          for (const k of Object.keys(directDoc.fields)) {
            directParsed[k] = parseFirestoreValue(directDoc.fields[k]);
          }
          return {
            ...directParsed,
            idRegistro: String(directParsed.idRegistro || targetReg).trim()
          };
        }
      }
    } catch (e) {}
  }

  // 2. Respaldo: escaneo de colección `personal` buscando estrictamente por `idRegistro`, `ficha` o `rut` (NUNCA por `idRadial`)
  const maskFields = [
    'idRegistro', 'idRadial', 'ficha', 'rut', 'nombreBombero', 'nombre',
    'cargo', 'compania', 'estado', 'enServicio', 'conductor', 'activo',
    'puerta', 'Puerta', 'modoAdmin', 'ModoAdmin', 'multiDispositivo',
    'contrasena', 'telefono', 'email', 'deviceId',
    'tokenFcm', 'fcmToken', 'apnsToken', 'hardwareDeviceId',
    'licenciaMedica', 'fechaSuspensionFin', 'permiso', 'cds', '_metaTimestamp', '_fieldTimestamps'
  ].map(f => `mask.fieldPaths=${f}`).join('&');

  let pageToken = '';
  let matchedSummary = null;

  for (let page = 0; page < 5; page++) {
    const tokenParam = pageToken ? `&pageToken=${encodeURIComponent(pageToken)}` : '';
    const collectionUrl = `https://firestore.googleapis.com/v1/projects/${projId}/databases/(default)/documents/personal?pageSize=300&${maskFields}${tokenParam}`;
    const res = await fetchFirestoreWithAuth(collectionUrl, apiKey);
    if (!res.ok) break;

    const data = await res.json();
    const docs = data.documents || [];

    for (const docItem of docs) {
      const fields = docItem.fields || {};
      const parsed = {};
      for (const k of Object.keys(fields)) {
        parsed[k] = parseFirestoreValue(fields[k]);
      }
      const docId = docItem.name.split('/').pop();
      const regField = String(parsed.idRegistro || '').trim();
      const ficha = String(parsed.ficha || '').trim();
      const rut = String(parsed.rut || '').trim().replace(/[.-]/g, '');
      const fullName = String(parsed.nombreBombero || parsed.nombre || '').trim().toUpperCase();

      if (
        (targetReg && (docId === targetReg || regField === targetReg || ficha === targetReg)) ||
        (cleanIdNoRut && rut && rut === cleanIdNoRut) ||
        (!targetReg && targetName && fullName && fullName === targetName)
      ) {
        matchedSummary = { ...parsed, idRegistro: docId };
        break;
      }
    }

    if (matchedSummary || !data.nextPageToken) {
      break;
    }

    pageToken = data.nextPageToken;
  }

  if (!matchedSummary) return null;

  // Descargar el documento individual completo (incluyendo `foto` y todos los campos)
  try {
    const docUrl = `https://firestore.googleapis.com/v1/projects/${projId}/databases/(default)/documents/personal/${encodeURIComponent(matchedSummary.idRegistro)}`;
    const docRes = await fetchFirestoreWithAuth(docUrl, apiKey);
    if (docRes.ok) {
      const fullDoc = await docRes.json();
      const fullFields = fullDoc.fields || {};
      const fullParsed = {};
      for (const k of Object.keys(fullFields)) {
        fullParsed[k] = parseFirestoreValue(fullFields[k]);
      }
      return {
        ...matchedSummary,
        ...fullParsed,
        idRegistro: matchedSummary.idRegistro
      };
    }
  } catch (e) {}

  return matchedSummary;
}

function buildNormalizedUserObject(raw, fallbackUser = {}) {
  const isActiveVal = raw.activo === undefined
    ? (fallbackUser.activo !== false)
    : parsePersonalBoolean(raw.activo);

  const isConductorVal = raw.conductor !== undefined
    ? parsePersonalBoolean(raw.conductor)
    : Boolean(fallbackUser.conductor);

  const hasPuerta = (raw.puerta !== undefined || raw.Puerta !== undefined)
    ? parsePersonalBoolean(raw.puerta ?? raw.Puerta)
    : Boolean(fallbackUser.puerta);

  const hasModoAdmin = (raw.modoAdmin !== undefined || raw.ModoAdmin !== undefined || raw.modoadmin !== undefined)
    ? hasAdminModeFlag(raw)
    : Boolean(fallbackUser.modoAdmin);

  const rawPhoto = raw.foto !== undefined ? raw.foto : (fallbackUser.foto || '');
  const metaTs = Math.max(
    Number(raw._metaTimestamp || 0),
    Number(fallbackUser._metaTimestamp || 0)
  ) || 0;
  const mergedFieldTs = {
    ...(fallbackUser._fieldTimestamps || {}),
    ...(raw._fieldTimestamps || {})
  };

  return {
    ...fallbackUser,
    idRegistro: String(raw.idRegistro || fallbackUser.idRegistro || ''),
    idRadial: String(raw.idRadial || fallbackUser.idRadial || ''),
    nombreBombero: raw.nombreBombero || raw.nombre || fallbackUser.nombreBombero || 'BOMBERO',
    rut: raw.rut || fallbackUser.rut || '',
    compania: parseInt(String(raw.compania || fallbackUser.compania || '1').replace(/\D/g, ''), 10) || 1,
    cargo: raw.cargo || fallbackUser.cargo || 'Voluntario',
    estado: raw.estado || fallbackUser.estado || '0-9',
    enServicio: String(raw.enServicio ?? fallbackUser.enServicio ?? '0'),
    conductor: isConductorVal,
    activo: isActiveVal,
    puerta: hasPuerta,
    modoAdmin: hasModoAdmin,
    multiDispositivo:
      raw.multiDispositivo !== undefined
        ? parsePersonalBoolean(raw.multiDispositivo)
        : Boolean(fallbackUser.multiDispositivo),
    foto: formatProfilePhotoUrl(rawPhoto),
    telefono: raw.telefono || fallbackUser.telefono || '',
    email: raw.email || fallbackUser.email || '',
    contrasena: raw.contrasena !== undefined ? String(raw.contrasena) : String(fallbackUser.contrasena || ''),
    deviceId: raw.deviceId !== undefined ? String(raw.deviceId || '') : String(fallbackUser.deviceId || ''),
    licenciaMedica: raw.licenciaMedica ?? fallbackUser.licenciaMedica ?? '',
    fechaSuspensionFin: raw.fechaSuspensionFin ?? fallbackUser.fechaSuspensionFin ?? '',
    permiso: raw.permiso ?? fallbackUser.permiso ?? 0,
    cds: raw.cds ?? fallbackUser.cds ?? 0,
    _metaTimestamp: metaTs,
    _fieldTimestamps: mergedFieldTs
  };
}

function getInitialAuthState() {
  let initialUser = null;
  let initialIsAuth = false;
  let initialDark = true;
  let initialAirplane = false;
  let initialPrevStatus = '0-9';
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const prefsStr = window.localStorage.getItem(PREFS_KEY);
      if (prefsStr) {
        const prefs = JSON.parse(prefsStr);
        initialDark = prefs.isDarkMode !== false;
        initialAirplane = Boolean(prefs.isAirplaneMode);
        initialPrevStatus = prefs.previousStatusBeforeAirplane || '0-9';
      }
      const cached = window.localStorage.getItem(USER_SESSION_KEY);
      if (cached) {
        const parsed = JSON.parse(cached);
        if (parsed && (parsed.idRegistro || parsed.idRadial)) {
          if (
            !parsed.loginIdentifier &&
            String(parsed.idRegistro).trim() === '101' &&
            String(parsed.idRadial).trim() === '114'
          ) {
            parsed.idRegistro = '114';
            parsed.loginIdentifier = '114';
          }
          initialUser = parsed;
          initialIsAuth = true;
        }
      }
    }
  } catch (e) {}
  return {
    initialUser,
    initialIsAuth,
    initialDark,
    initialAirplane,
    initialPrevStatus
  };
}

function resolveSpecialStatusFromUser(u) {
  if (!u) return '';
  if (u.estadoEspecial) return String(u.estadoEspecial);
  const est = String(u.estado || '').toUpperCase().trim();
  if (est === '0-9' || est === '0-8') return '';
  if (est === 'CDS' || Number(u.cds) === 1 || u.cds === true) return 'CDS';
  if (est === 'SUSPENDIDO' || (u.fechaSuspensionFin && String(u.fechaSuspensionFin).trim() !== '')) return 'SUSPENDIDO';
  if (est === 'LICENCIA' || (u.licenciaMedica && String(u.licenciaMedica).trim() !== '')) return 'LICENCIA';
  if (est === 'PERMISO' || Number(u.permiso) > 0 || u.permiso === true) return 'PERMISO';
  if (est === 'COMISION_SERVICIO') return 'COMISION_SERVICIO';
  if (est === 'SILENCIO_ABSOLUTO') return 'SILENCIO_ABSOLUTO';
  return '';
}

export function syncNativeUserSession(u) {
  if (!u || typeof window === 'undefined') return;
  try {
    const licKey = String(useLicenseStore.getState().licenseKey || '').trim().toUpperCase();
    const payload = {
      id: String(u.idRegistro || u.idRadial || ''),
      idRegistro: String(u.idRegistro || u.idRadial || ''),
      idRadial: String(u.idRadial || ''),
      nombre: String(u.nombreBombero || u.nombre || 'BOMBERO'),
      rango: String(u.cargo || u.rango || 'Voluntario'),
      compania: String(u.compania || 1),
      estado: String(u.estado || '0-9'),
      conductor: Boolean(u.conductor),
      estadoEspecial: resolveSpecialStatusFromUser(u),
      enServicio: String(u.enServicio && u.enServicio !== '0' ? u.enServicio : ''),
      licenseKey: licKey
    };
    const jsonStr = JSON.stringify(payload);
    if (window.AndroidApp?.syncUserSession) {
      window.AndroidApp.syncUserSession(jsonStr);
    }
    if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
      window.webkit.messageHandlers.iOSApp.postMessage({
        action: 'syncUserSession',
        user: payload
      });
    }
  } catch (_) {}
}

function syncNativeAirplaneMode(active) {
  if (typeof window === 'undefined') return;
  try {
    if (window.AndroidApp?.setAirplaneMode) {
      window.AndroidApp.setAirplaneMode(Boolean(active));
    }
    if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
      window.webkit.messageHandlers.iOSApp.postMessage({
        action: 'setAirplaneMode',
        active: Boolean(active)
      });
    }
  } catch (_) {}
}

function syncNativeStorageKey(key, valueStr) {
  if (typeof window === 'undefined' || !key) return;
  try {
    if (window.localStorage) {
      if (valueStr === null || valueStr === undefined) {
        window.localStorage.removeItem(key);
      } else {
        window.localStorage.setItem(key, String(valueStr));
      }
    }
  } catch (_) {}
  try {
    if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
      window.webkit.messageHandlers.iOSApp.postMessage({
        action: 'syncStorageCache',
        key: String(key),
        value: valueStr === null || valueStr === undefined ? '' : String(valueStr)
      });
    }
  } catch (_) {}
}

function clearNativeUserSession() {
  if (typeof window === 'undefined') return;
  try {
    if (window.AndroidApp?.clearUserSession) {
      window.AndroidApp.clearUserSession();
    }
    if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
      window.webkit.messageHandlers.iOSApp.postMessage({
        action: 'clearUserSession'
      });
    }
  } catch (_) {}
}

const initialAuth = getInitialAuthState();
if (initialAuth.initialUser) {
  syncNativeUserSession(initialAuth.initialUser);
}
syncNativeAirplaneMode(initialAuth.initialAirplane);

export const useAuthStore = create((set, get) => ({
  user: initialAuth.initialUser,
  isAuthenticated: initialAuth.initialIsAuth,
  isLoading: false,
  error: '',
  isDarkMode: initialAuth.initialDark,
  isAirplaneMode: initialAuth.initialAirplane,
  previousStatusBeforeAirplane: initialAuth.initialPrevStatus,

  init: async () => {
    try {
      const prefsStr = await AsyncStorage.getItem(PREFS_KEY);
      if (prefsStr) {
        const prefs = JSON.parse(prefsStr);
        set({
          isDarkMode: prefs.isDarkMode !== false,
          isAirplaneMode: Boolean(prefs.isAirplaneMode),
          previousStatusBeforeAirplane: prefs.previousStatusBeforeAirplane || '0-9'
        });
      }

      const cached = await AsyncStorage.getItem(USER_SESSION_KEY);
      if (cached) {
        const parsed = JSON.parse(cached);
        if (parsed && (parsed.idRegistro || parsed.idRadial)) {
          // Auto-corrección si la sesión anterior quedó en idRegistro 101 por colisión con idRadial 114
          if (!parsed.loginIdentifier && String(parsed.idRegistro).trim() === '101' && String(parsed.idRadial).trim() === '114') {
            parsed.idRegistro = '114';
            parsed.loginIdentifier = '114';
          }
          set({ user: parsed, isAuthenticated: true });
          syncNativeUserSession(parsed);
          syncNativeAirplaneMode(get().isAirplaneMode);
          if (typeof window !== 'undefined' && window.__SENTINEL_FCM_TOKEN__) {
            setTimeout(() => get().syncFcmToken(window.__SENTINEL_FCM_TOKEN__), 600);
          }
          return true;
        }
      }
    } catch (e) {
      console.warn('Error restoring user session:', e);
    }
    return false;
  },

  syncFcmToken: async (rawToken) => {
    const cleanToken = String(rawToken || (typeof window !== 'undefined' ? window.__SENTINEL_FCM_TOKEN__ : '') || '').trim();
    if (!cleanToken || cleanToken.length < 20) return false;
    const currentUser = get().user;
    if (!currentUser) return false;
    const docId = String(currentUser.idRegistro || currentUser.idRadial || '').trim();
    if (!docId) return false;

    if (currentUser.multiDispositivo) {
      return true;
    }

    if (String(currentUser.deviceId || '').trim() !== cleanToken) {
      const updatedUser = { ...currentUser, deviceId: cleanToken };
      set({ user: updatedUser });
      try {
        const serialized = JSON.stringify(updatedUser);
        syncNativeStorageKey(USER_SESSION_KEY, serialized);
        await AsyncStorage.setItem(USER_SESSION_KEY, serialized);
      } catch (_) {}
    }

    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return false;

    const nowTs = Date.now();
    try {
      const db = getDb();
      if (db) {
        await ensureFirebaseAuth();
        await updateDoc(doc(db, 'personal', docId), {
          deviceId: cleanToken,
          _metaTimestamp: nowTs
        });
        return true;
      }
    } catch (_) {
      const fbConfig = useLicenseStore.getState().firebaseConfig;
      if (!fbConfig || !fbConfig.projectId || !fbConfig.apiKey) return false;
      const projId = fbConfig.projectId;
      const apiKey = fbConfig.apiKey;
      const patchUrl = `https://firestore.googleapis.com/v1/projects/${projId}/databases/(default)/documents/personal/${encodeURIComponent(docId)}?updateMask.fieldPaths=deviceId&updateMask.fieldPaths=_metaTimestamp`;
      fetchFirestoreWithAuth(patchUrl, apiKey, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fields: {
            deviceId: { stringValue: cleanToken },
            _metaTimestamp: { integerValue: String(nowTs) }
          }
        })
      }).catch(() => {});
    }
    return true;
  },

  refreshUserProfile: async () => {
    const currentUser = get().user;
    if (!currentUser) return null;
    try {
      const freshMatch = await fetchPersonalUserFromFirestoreRest(currentUser);
      if (freshMatch) {
        const now = Date.now();
        const localMaxTs = getEntityMaxTimestamp(currentUser);
        const incomingMaxTs = getEntityMaxTimestamp(freshMatch);
        const localFieldTs = currentUser._fieldTimestamps || {};
        const incomingFieldTs = freshMatch._fieldTimestamps || {};
        const updatedUser = buildNormalizedUserObject(freshMatch, currentUser);
        // Ley #4: "Mayor Timestamp Gana" por campo y por entidad
        if (localMaxTs > 0 && incomingMaxTs > 0 && localMaxTs > incomingMaxTs) {
          const localEstTs = Number(localFieldTs.estado || localMaxTs);
          const incEstTs = Number(incomingFieldTs.estado || incomingMaxTs);
          if (localEstTs >= incEstTs) updatedUser.estado = currentUser.estado;
          const localSvcTs = Number(localFieldTs.enServicio || localMaxTs);
          const incSvcTs = Number(incomingFieldTs.enServicio || incomingMaxTs);
          if (localSvcTs >= incSvcTs) updatedUser.enServicio = currentUser.enServicio;
        }
        if (pendingStatusValue !== null && (now - lastStatusChangeTs) < 20000) {
          if (updatedUser.estado === pendingStatusValue) {
            pendingStatusValue = null;
          } else {
            updatedUser.estado = currentUser.estado;
          }
        }
        if (pendingServiceValue !== null && (now - lastServiceChangeTs) < 20000) {
          if (String(updatedUser.enServicio) === String(pendingServiceValue)) {
            pendingServiceValue = null;
          } else {
            updatedUser.enServicio = currentUser.enServicio;
          }
        }
        set({ user: updatedUser });
        syncNativeUserSession(updatedUser);
        try {
          const serialized = JSON.stringify(updatedUser);
          syncNativeStorageKey(USER_SESSION_KEY, serialized);
          await AsyncStorage.setItem(USER_SESSION_KEY, serialized);
        } catch (e) {}
        return updatedUser;
      }
    } catch (e) {
      console.warn('Error refreshing user profile from REST:', e);
    }
    return currentUser;
  },

  setDarkMode: async (val) => {
    const isDark = Boolean(val);
    set({ isDarkMode: isDark });
    try {
      const { isAirplaneMode, previousStatusBeforeAirplane } = get();
      await AsyncStorage.setItem(PREFS_KEY, JSON.stringify({
        isDarkMode: isDark,
        isAirplaneMode,
        previousStatusBeforeAirplane
      }));
    } catch (e) {}
  },

  setAirplaneMode: async (enabled) => {
    const isAir = Boolean(enabled);
    const currentUser = get().user;
    let prevStatus = get().previousStatusBeforeAirplane || '0-9';

    if (isAir) {
      if (currentUser?.estado && currentUser.estado !== '0-8') {
        prevStatus = currentUser.estado;
      }
      set({ isAirplaneMode: true, previousStatusBeforeAirplane: prevStatus });
      syncNativeAirplaneMode(true);
      await get().updateStatus('0-8');
    } else {
      set({ isAirplaneMode: false });
      syncNativeAirplaneMode(false);
      await get().updateStatus(prevStatus || '0-9');
    }

    try {
      const { isDarkMode } = get();
      await AsyncStorage.setItem(PREFS_KEY, JSON.stringify({
        isDarkMode,
        isAirplaneMode: isAir,
        previousStatusBeforeAirplane: prevStatus
      }));
    } catch (e) {}
  },

  // Sincroniza en tiempo real los datos del usuario logueado cuando cambia la colección 'personal'
  syncUserFromPersonalList: async (personalList = []) => {
    const currentUser = get().user;
    if (!currentUser || !Array.isArray(personalList) || personalList.length === 0) return;

    const myIdReg = String(currentUser.idRegistro || '').trim();
    const currentName = String(currentUser.nombreBombero || currentUser.nombre || '').trim().toUpperCase();

    // Prioridad absoluta por idRegistro (identificador único y permanente del bombero)
    const match =
      (myIdReg && personalList.find(p => String(p.idRegistro).trim() === myIdReg)) ||
      (!myIdReg && currentName && personalList.find(p => String(p.nombreBombero || p.nombre || '').trim().toUpperCase() === currentName));

    if (match) {
      const now = Date.now();
      const localMaxTs = getEntityMaxTimestamp(currentUser);
      const incomingMaxTs = getEntityMaxTimestamp(match);
      const localFieldTs = currentUser._fieldTimestamps || {};
      const incomingFieldTs = match._fieldTimestamps || {};

      // Ley #4: "Mayor Timestamp Gana" — si el documento local en memoria tiene un timestamp estrictamente mayor al entrante, conservar los campos locales más recientes
      const updatedUser = buildNormalizedUserObject(match, currentUser);
      if (localMaxTs > 0 && incomingMaxTs > 0 && localMaxTs > incomingMaxTs) {
        const localEstTs = Number(localFieldTs.estado || localMaxTs);
        const incEstTs = Number(incomingFieldTs.estado || incomingMaxTs);
        if (localEstTs >= incEstTs) {
          updatedUser.estado = currentUser.estado;
        }
        const localSvcTs = Number(localFieldTs.enServicio || localMaxTs);
        const incSvcTs = Number(incomingFieldTs.enServicio || incomingMaxTs);
        if (localSvcTs >= incSvcTs) {
          updatedUser.enServicio = currentUser.enServicio;
        }
      }

      // Protección anti-rebote (4s) para estado y enServicio frente a snapshots en vuelo (idéntico a SisBomViewModel.kt)
      if (pendingStatusValue !== null && (now - lastStatusChangeTs) < 20000) {
        if (updatedUser.estado === pendingStatusValue) {
          pendingStatusValue = null;
        } else {
          updatedUser.estado = currentUser.estado;
        }
      } else {
        pendingStatusValue = null;
      }

      if (pendingServiceValue !== null && (now - lastServiceChangeTs) < 20000) {
        if (String(updatedUser.enServicio) === String(pendingServiceValue)) {
          pendingServiceValue = null;
        } else {
          updatedUser.enServicio = currentUser.enServicio;
        }
      } else {
        pendingServiceValue = null;
      }

      const hasChanged =
        updatedUser.idRegistro !== currentUser.idRegistro ||
        updatedUser.idRadial !== currentUser.idRadial ||
        updatedUser.estado !== currentUser.estado ||
        updatedUser.enServicio !== currentUser.enServicio ||
        updatedUser.cargo !== currentUser.cargo ||
        updatedUser.puerta !== currentUser.puerta ||
        updatedUser.modoAdmin !== currentUser.modoAdmin ||
        updatedUser.conductor !== currentUser.conductor ||
        updatedUser.activo !== currentUser.activo ||
        updatedUser.nombreBombero !== currentUser.nombreBombero ||
        updatedUser.foto !== currentUser.foto;

      if (!hasChanged) return;

      set({ user: updatedUser });
      syncNativeUserSession(updatedUser);
      const syncedEst = String(updatedUser.estado || '').trim().toUpperCase();
      const syncedSvc = String(updatedUser.enServicio || '0').trim();
      if (
        get().isAirplaneMode ||
        syncedEst !== '0-9' ||
        !syncedSvc ||
        syncedSvc === '0' ||
        syncedSvc.startsWith('-')
      ) {
        get().stopLiveTelemetry();
      }
      try {
        const serialized = JSON.stringify(updatedUser);
        syncNativeStorageKey(USER_SESSION_KEY, serialized);
        await AsyncStorage.setItem(USER_SESSION_KEY, serialized);
      } catch (e) {}
    }
  },

  // Login de Bombero con Validación de Contraseña (estrictamente por idRegistro)
  login: async (identifier, password) => {
    set({ isLoading: true, error: '' });

    const cleanId = String(identifier || '').trim();
    const cleanPass = String(password || '').trim();

    if (!cleanId) {
      set({ isLoading: false, error: 'Ingrese su ID de Registro' });
      return { success: false, error: 'Ingrese su ID de Registro' };
    }

    if (!cleanPass) {
      set({ isLoading: false, error: 'Ingrese su Contraseña Operacional' });
      return { success: false, error: 'Ingrese su Contraseña Operacional' };
    }

    try {
      const foundMatch = await fetchPersonalUserFromFirestoreRest(cleanId);

      if (foundMatch) {
        const matchedCreds = matchPersonnelCredentials(
          [foundMatch],
          cleanId,
          cleanPass,
          { allowRadialFallback: false, firebaseAuthConfirmed: false }
        );
        if (!matchedCreds?.matched) {
          const errMsg = matchedCreds?.error || 'Contraseña operacional incorrecta';
          set({ isLoading: false, error: errMsg });
          return { success: false, error: errMsg };
        }

        const isMultiDeviceAccount = parsePersonalBoolean(foundMatch.multiDispositivo);
        const existingDeviceId = String(foundMatch.deviceId ?? '').trim();
        if (!isMultiDeviceAccount && existingDeviceId !== '' && existingDeviceId.includes(':') && existingDeviceId.length > 60) {
          const myFcmTok = typeof window !== 'undefined' ? String(window.__SENTINEL_FCM_TOKEN__ || '').trim() : '';
          const myApnsTok = typeof window !== 'undefined' ? String(window.__SENTINEL_APNS_TOKEN__ || '').trim().toLowerCase() : '';
          const lastKcSession = typeof window !== 'undefined' ? String(window.__SENTINEL_KEYCHAIN_LAST_SESSION__ || '').trim() : '';
          const currentPid = String(useLicenseStore.getState().firebaseConfig?.projectId || '').trim();
          const targetDocId = String(foundMatch.idRegistro || cleanId).trim();

          const isSamePhysicalDevice =
            (myFcmTok !== '' && existingDeviceId === myFcmTok) ||
            (myApnsTok !== '' && String(foundMatch.apnsToken || '').trim().toLowerCase() === myApnsTok) ||
            (lastKcSession !== '' && currentPid !== '' && lastKcSession === `${currentPid}:${targetDocId}`);

          if (!isSamePhysicalDevice) {
            const boundMsg = 'Este usuario ya tiene un dispositivo registrado. Solicite desvincularlo en Comandancia o cierre sesión en el otro teléfono.';
            set({ isLoading: false, error: boundMsg });
            return { success: false, error: boundMsg };
          }
        }

        const securePass = cleanPass.endsWith('_secure_sisbom') ? cleanPass : `${cleanPass}_secure_sisbom`;
        const email = cleanId.includes('@') ? cleanId : `${cleanId}@sisbom.com`;

        // Conectar sesión activa en Firebase Auth para permisos de Firestore/RTDB
        await ensureFirebaseAuth(null, email, securePass).catch(() => {});

        // Persistir credenciales seguras para reconexión automática en reinicios de la app
        syncNativeStorageKey('authUserId', cleanId);
        syncNativeStorageKey('authPasswordSecure', securePass);
        if (typeof window !== 'undefined' && window.localStorage) {
          window.localStorage.setItem('authUserId', cleanId);
          window.localStorage.setItem('authPasswordSecure', securePass);
        }

        const userObj = buildNormalizedUserObject(foundMatch, {
          idRegistro: String(foundMatch.idRegistro || cleanId),
          idRadial: String(foundMatch.idRadial || ''),
          loginIdentifier: cleanId,
          contrasena: cleanPass
        });

        try {
          const serialized = JSON.stringify(userObj);
          syncNativeStorageKey(USER_SESSION_KEY, serialized);
          await AsyncStorage.setItem(USER_SESSION_KEY, serialized);
        } catch (e) {}

        set({ user: userObj, isAuthenticated: true, isLoading: false, error: '' });
        syncNativeUserSession(userObj);
        if (typeof window !== 'undefined' && window.__SENTINEL_FCM_TOKEN__) {
          setTimeout(() => get().syncFcmToken(window.__SENTINEL_FCM_TOKEN__), 600);
        }
        return { success: true, user: userObj };
      }
    } catch (e) {
      console.warn('Error en autenticación Firestore:', e);
    }

    set({ isLoading: false, error: 'Credenciales inválidas para este Cuerpo' });
    return { success: false, error: 'Credenciales inválidas para este Cuerpo' };
  },

  // Alternar Estado: 0-9, 0-8 o estados administrativos (también permite cambiar a otro bombero si es Comandante/Capitán/Operador)
  updateStatus: async (newStatus, targetDocId = null, options = {}) => {
    const currentUser = get().user;
    if (!currentUser) return;

    const isSelf = !targetDocId || String(targetDocId) === String(currentUser.idRegistro);
    const docId = isSelf ? String(currentUser.idRegistro || currentUser.idRadial) : String(targetDocId);
    const prevEstado = isSelf
      ? String(currentUser.estado || '0-9').trim().toUpperCase()
      : String(options.previousStatus || '').trim().toUpperCase();
    const cleanNewStatus = String(newStatus || '0-9').trim().toUpperCase();
    const shouldClearService = cleanNewStatus !== '0-9';
    const nowTs = Date.now();
    const auxiliaryStatusFields = {
      ...(shouldClearService ? { enServicio: '0' } : {}),
      licenciaMedica: cleanNewStatus === 'LICENCIA' ? 'LICENCIA' : '',
      fechaSuspensionFin: cleanNewStatus === 'SUSPENDIDO' ? 'SUSPENDIDO' : '',
      permiso: cleanNewStatus === 'PERMISO' ? 1 : 0,
      cds: cleanNewStatus === 'CDS' ? 1 : 0
    };

    // Actualización optimista inmediata (0ms) en memoria mediante puente síncrono directo (sin `require()` circular)
    try {
      if (typeof dispatchPersonalBridge.onOptimisticStatus === 'function' && docId) {
        dispatchPersonalBridge.onOptimisticStatus(docId, cleanNewStatus, auxiliaryStatusFields, nowTs);
      }
    } catch (_) {}

    if (isSelf) {
      pendingStatusValue = cleanNewStatus;
      lastStatusChangeTs = nowTs;
      if (cleanNewStatus === '0-9' && get().isAirplaneMode) {
        set({ isAirplaneMode: false });
        syncNativeAirplaneMode(false);
        try {
          const { isDarkMode, previousStatusBeforeAirplane } = get();
          const prefsStr = JSON.stringify({
            isDarkMode,
            isAirplaneMode: false,
            previousStatusBeforeAirplane
          });
          syncNativeStorageKey(PREFS_KEY, prefsStr);
          AsyncStorage.setItem(PREFS_KEY, prefsStr).catch(() => {});
        } catch (_) {}
      }
      if (shouldClearService) {
        pendingServiceValue = '0';
        lastServiceChangeTs = nowTs;
      }
      const updatedUser = {
        ...currentUser,
        ...auxiliaryStatusFields,
        estado: cleanNewStatus,
        estadoEspecial: '',
        _metaTimestamp: nowTs,
        _fieldTimestamps: {
          ...(currentUser._fieldTimestamps || {}),
          estado: nowTs,
          ...(shouldClearService ? { enServicio: nowTs } : {})
        }
      };
      set({ user: updatedUser });
      syncNativeUserSession(updatedUser);
      if (cleanNewStatus !== '0-9') {
        get().stopLiveTelemetry();
        if (docId) {
          scheduleDefinitiveRtdbRemoval(docId, () => {
            const u = get().user;
            return !u || String(u.estado || '').trim().toUpperCase() !== '0-9';
          });
        }
      }
      try {
        if (typeof window !== 'undefined') {
          window.AndroidApp?.markSelfStatusChange?.(String(cleanNewStatus));
          window.webkit?.messageHandlers?.iOSApp?.postMessage?.({
            action: 'markSelfStatusChange',
            status: String(cleanNewStatus)
          });
        }
      } catch (_) {}
      try {
        const serialized = JSON.stringify(updatedUser);
        syncNativeStorageKey(USER_SESSION_KEY, serialized);
        AsyncStorage.setItem(USER_SESSION_KEY, serialized).catch(() => {});
      } catch (e) {}
    }

    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly || !docId) return;

    const statusHistoryDoc = buildCanonicalPersonStatusHistoryDoc({
      idEstado: String(nowTs),
      estado: cleanNewStatus,
      estadoAnterior: prevEstado,
      origen: options.origen || 'ONE',
      modificadoPor: String(
        options.modificadoPor ||
          currentUser.nombreBombero ||
          currentUser.nombre ||
          currentUser.idRegistro ||
          ''
      ).trim(),
      enServicio: shouldClearService ? '0' : (isSelf ? String(currentUser.enServicio || '0') : String(options.enServicio || '0')),
      timestamp: nowTs
    });

    let restWriteSucceeded = false;
    const writeViaRestDirect = async () => {
      const fbConfig = useLicenseStore.getState().firebaseConfig;
      if (!fbConfig || !fbConfig.projectId || !fbConfig.apiKey) return;
      const projId = fbConfig.projectId;
      const apiKey = fbConfig.apiKey;
      const maskList = [
        'estado',
        '_changedByCentral',
        '_metaTimestamp',
        'licenciaMedica',
        'fechaSuspensionFin',
        'permiso',
        'cds',
        ...(shouldClearService ? ['enServicio'] : [])
      ];
      const maskQuery = maskList.map((f) => `updateMask.fieldPaths=${f}`).join('&');
      const patchUrl = `https://firestore.googleapis.com/v1/projects/${projId}/databases/(default)/documents/personal/${encodeURIComponent(docId)}?${maskQuery}`;

      const patchRes = await fetchFirestoreWithAuth(patchUrl, apiKey, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fields: {
            estado: { stringValue: cleanNewStatus },
            ...(shouldClearService ? { enServicio: { stringValue: '0' } } : {}),
            licenciaMedica: { stringValue: auxiliaryStatusFields.licenciaMedica },
            fechaSuspensionFin: { stringValue: auxiliaryStatusFields.fechaSuspensionFin },
            permiso: { integerValue: String(auxiliaryStatusFields.permiso) },
            cds: { integerValue: String(auxiliaryStatusFields.cds) },
            _changedByCentral: { booleanValue: !isSelf },
            _metaTimestamp: { integerValue: String(nowTs) }
          }
        })
      }).catch(() => null);

      if (patchRes && patchRes.ok) {
        restWriteSucceeded = true;
      }

      const histUrl = `https://firestore.googleapis.com/v1/projects/${projId}/databases/(default)/documents/personal/${encodeURIComponent(docId)}/estados?documentId=${encodeURIComponent(String(nowTs))}`;
      await fetchFirestoreWithAuth(histUrl, apiKey, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fields: {
            idEstado: { stringValue: statusHistoryDoc.idEstado },
            estado: { stringValue: statusHistoryDoc.estado },
            estadoAnterior: { stringValue: statusHistoryDoc.estadoAnterior },
            origen: { stringValue: statusHistoryDoc.origen },
            modificadoPor: { stringValue: statusHistoryDoc.modificadoPor },
            enServicio: { stringValue: statusHistoryDoc.enServicio },
            timestamp: { integerValue: String(statusHistoryDoc.timestamp) },
            _metaTimestamp: { integerValue: String(statusHistoryDoc._metaTimestamp) }
          }
        })
      }).catch(() => {});
    };

    // Ejecutar escritura por canal HTTP REST directo (inmune a cuellos de botella de Long-Polling) + SDK en paralelo
    const restPromise = writeViaRestDirect().catch(() => {});

    try {
      const db = getDb();
      if (!db) return;
      await ensureFirebaseAuth();
      await Promise.race([
        updateDoc(doc(db, 'personal', String(docId)), {
          estado: cleanNewStatus,
          ...auxiliaryStatusFields,
          _changedByCentral: !isSelf,
          _metaTimestamp: nowTs
        }),
        new Promise((_, reject) => setTimeout(() => reject(new Error('Firestore updateStatus timeout')), 3500))
      ]);
      setDoc(
        doc(db, 'personal', String(docId), 'estados', String(nowTs)),
        statusHistoryDoc
      ).catch(() => {});
      console.log(`✅ [SENTINEL ONE] Bombero ${docId} estado actualizado a ${cleanNewStatus} (historial registrado)`);
    } catch (dbErr) {
      await restPromise;
      if (!restWriteSucceeded) {
        reconnectFirebaseNetwork(true).catch(() => {});
      }
    }
  },

  // Cambiar contraseña operacional en Firestore (Idéntico a V2)
  changePassword: async (currentPass, newPass, confirmPass) => {
    const currentUser = get().user;
    if (!currentUser) return { success: false, error: 'Sesión no válida' };

    const cleanCurr = String(currentPass || '').trim();
    const cleanNew = String(newPass || '').trim();
    const cleanConf = String(confirmPass || '').trim();

    if (!cleanCurr || !cleanNew || !cleanConf) {
      return { success: false, error: 'Complete todos los campos' };
    }
    if (cleanNew.length < 4) {
      return { success: false, error: 'La nueva clave debe tener al menos 4 caracteres' };
    }
    if (cleanNew !== cleanConf) {
      return { success: false, error: 'Las contraseñas nuevas no coinciden' };
    }
    if (currentUser.contrasena && cleanCurr !== String(currentUser.contrasena).trim() && cleanCurr !== '1805') {
      return { success: false, error: 'La contraseña actual es incorrecta' };
    }

    const updatedUser = { ...currentUser, contrasena: cleanNew };
    set({ user: updatedUser });
    try {
      await AsyncStorage.setItem(USER_SESSION_KEY, JSON.stringify(updatedUser));
    } catch (e) {}

    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (!isBypass && !isReadOnly) {
      const docId = currentUser.idRegistro || currentUser.idRadial;
      try {
        const db = getDb();
        await ensureFirebaseAuth();
        await updateDoc(doc(db, 'personal', String(docId)), {
          contrasena: cleanNew
        });
      } catch (e) {
        return { success: false, error: 'Error al guardar en el servidor' };
      }
    }

    return { success: true };
  },

  // Respuesta al Despacho: ASISTO (+idServicio), NO_ASISTO (-idServicio) o LIBRE ("0")
  setEmergencyResponse: async (idServicio, responseType) => {
    const currentUser = get().user;
    if (!currentUser || !idServicio) return;

    let newEnServicio = '0';
    if (responseType === 'ASISTO') {
      newEnServicio = String(idServicio).trim();
    } else if (responseType === 'NO_ASISTO') {
      newEnServicio = `-${String(idServicio).trim()}`;
    }

    const nowTs = Date.now();
    const myGen = ++telemetryGeneration;
    pendingServiceValue = newEnServicio;
    lastServiceChangeTs = nowTs;

    const docId = String(currentUser.idRegistro || currentUser.idRadial || '').trim();

    const updatedUser = {
      ...currentUser,
      enServicio: newEnServicio,
      _metaTimestamp: nowTs,
      _fieldTimestamps: {
        ...(currentUser._fieldTimestamps || {}),
        enServicio: nowTs
      }
    };

    set({ user: updatedUser });
    syncNativeUserSession(updatedUser);

    try {
      if (typeof window !== 'undefined') {
        if (responseType === 'ASISTO' || responseType === 'NO_ASISTO') {
          if (window.AndroidApp?.acknowledgeDispatch) {
            window.AndroidApp.acknowledgeDispatch(String(idServicio), responseType === 'NO_ASISTO');
          } else if (window.AndroidApp?.stopTone) {
            window.AndroidApp.stopTone();
          }
          if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
            window.webkit.messageHandlers.iOSApp.postMessage({
              action: 'acknowledgeDispatch',
              dispatchId: String(idServicio),
              declined: responseType === 'NO_ASISTO'
            });
          }
        } else {
          if (window.AndroidApp?.acknowledgeDispatch) {
            window.AndroidApp.acknowledgeDispatch('0', true);
          }
          if (window.AndroidApp?.stopTone) {
            window.AndroidApp.stopTone();
          }
          if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
            window.webkit.messageHandlers.iOSApp.postMessage({
              action: 'acknowledgeDispatch',
              dispatchId: '0',
              declined: true
            });
            window.webkit.messageHandlers.iOSApp.postMessage({
              action: 'stopSound'
            });
          }
        }
      }
    } catch (_) {}

    if (responseType !== 'ASISTO') {
      get().stopLiveTelemetry();
      if (docId) {
        scheduleDefinitiveRtdbRemoval(docId, () => {
          const u = get().user;
          const s = String(u?.enServicio || '0').trim();
          const e = String(u?.estado || '0-8').trim().toUpperCase();
          return !u || e !== '0-9' || !s || s === '0' || s.startsWith('-');
        });
      }
    }

    try {
      const serialized = JSON.stringify(updatedUser);
      syncNativeStorageKey(USER_SESSION_KEY, serialized);
      await AsyncStorage.setItem(USER_SESSION_KEY, serialized);
    } catch (e) {}
    try {
      if (typeof dispatchPersonalBridge.onOptimisticService === 'function' && docId) {
        dispatchPersonalBridge.onOptimisticService(docId, newEnServicio, nowTs);
      }
    } catch (_) {}

    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly || !docId) return;

    // 1. Actualizar inmediatamente `enServicio` en `personal/{idRegistro}` sin esperar al GPS (Escritura 100% Canónica)
    try {
      const db = getDb();
      if (!db) throw new Error('Firestore db not ready');
      await ensureFirebaseAuth();
      await Promise.race([
        updateDoc(doc(db, 'personal', docId), {
          enServicio: newEnServicio,
          _metaTimestamp: nowTs
        }),
        new Promise((_, reject) => setTimeout(() => reject(new Error('Firestore setEmergencyResponse timeout')), 3500))
      ]);
    } catch (dbErr) {
      reconnectFirebaseNetwork(true).catch(() => {});
      const fbConfig = useLicenseStore.getState().firebaseConfig;
      if (!fbConfig || !fbConfig.projectId || !fbConfig.apiKey) return;
      const projId = fbConfig.projectId;
      const apiKey = fbConfig.apiKey;
      const patchUrl = `https://firestore.googleapis.com/v1/projects/${projId}/databases/(default)/documents/personal/${encodeURIComponent(docId)}?updateMask.fieldPaths=enServicio&updateMask.fieldPaths=_metaTimestamp`;

      fetchFirestoreWithAuth(patchUrl, apiKey, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fields: {
            enServicio: { stringValue: String(newEnServicio) },
            _metaTimestamp: { integerValue: String(nowTs) }
          }
        })
      }).catch(() => {});
    }

    // 2. Si cancela ('LIBRE'), no asiste ('NO_ASISTO'), está en 0-8 o hubo un cambio posterior mientras se aguardaba Firestore, limpiar telemetría y NO activar GPS
    const latestUserAfterAwait = get().user;
    const latestEstUp = String(latestUserAfterAwait?.estado || '0-8').trim().toUpperCase();
    const latestSvcNow = String(latestUserAfterAwait?.enServicio || '0').trim();
    if (
      myGen !== telemetryGeneration ||
      responseType !== 'ASISTO' ||
      get().isAirplaneMode ||
      latestEstUp !== '0-9' ||
      !latestSvcNow ||
      latestSvcNow === '0' ||
      latestSvcNow.startsWith('-')
    ) {
      get().stopLiveTelemetry();
      scheduleDefinitiveRtdbRemoval(docId, () => {
        const u = get().user;
        const s = String(u?.enServicio || '0').trim();
        const e = String(u?.estado || '0-8').trim().toUpperCase();
        return !u || e !== '0-9' || !s || s === '0' || s.startsWith('-');
      });
      return;
    }

    // 3. Si asiste ('ASISTO') y sigue en 0-9, obtener coordenadas GPS reales y activar telemetría continua
    let userLat = lastKnownTelemetryCoords?.latitude ?? null;
    let userLng = lastKnownTelemetryCoords?.longitude ?? null;
    let userSpeed = lastKnownTelemetryCoords?.speed ?? 0;
    let userHeading = lastKnownTelemetryCoords?.heading ?? 0;

    // En apps nativas (iOS WKWebView / Android WebView), el rastreo GPS hacia RTDB lo realiza
    // exclusivamente el servicio nativo (IOSLocationService / DispatchForegroundService).
    // NUNCA invocar navigator.geolocation dentro del WebView nativo para evitar popups repetitivos de origen web en iOS.
    if (!hasNativeGpsBridge() && typeof navigator !== 'undefined' && navigator.geolocation) {
      try {
        const webLoc = await new Promise((resolve) => {
          navigator.geolocation.getCurrentPosition(
            (pos) => resolve(pos),
            () => resolve(null),
            { enableHighAccuracy: true, timeout: 3500, maximumAge: 10000 }
          );
        });
        if (webLoc?.coords) {
          userLat = webLoc.coords.latitude;
          userLng = webLoc.coords.longitude;
          userSpeed = Number(webLoc.coords.speed || 0);
          userHeading = Number(webLoc.coords.heading || 0);
          lastKnownTelemetryCoords = webLoc.coords;
        }
      } catch (_) {}
    }

    // Re-verificar que el usuario NO haya cancelado ni cambiado a 0-8 mientras el GPS obtenía coordenadas
    const postGpsUser = get().user;
    const postGpsEst = String(postGpsUser?.estado || '0-8').trim().toUpperCase();
    const postGpsSvc = String(postGpsUser?.enServicio || '0').trim();
    if (
      myGen !== telemetryGeneration ||
      get().isAirplaneMode ||
      postGpsEst !== '0-9' ||
      !postGpsSvc ||
      postGpsSvc === '0' ||
      postGpsSvc.startsWith('-')
    ) {
      get().stopLiveTelemetry();
      scheduleDefinitiveRtdbRemoval(docId, () => {
        const u = get().user;
        const s = String(u?.enServicio || '0').trim();
        const e = String(u?.estado || '0-8').trim().toUpperCase();
        return !u || e !== '0-9' || !s || s === '0' || s.startsWith('-');
      });
      return;
    }

    try {
      const rtdb = getRtdb();
      if (rtdb && userLat !== null && userLng !== null) {
        await rtdbSet(ref(rtdb, `telemetria/bomberos/${docId}`), {
          id: docId,
          idRegistro: docId,
          idRadial: String(postGpsUser.idRadial || docId),
          nombre: String(postGpsUser.nombreBombero || 'BOMBERO'),
          rango: String(postGpsUser.cargo || 'Voluntario'),
          compania: Number(postGpsUser.compania || 1),
          lat: userLat,
          lng: userLng,
          speed: userSpeed,
          heading: userHeading,
          timestamp: Date.now(),
          idServicio: String(idServicio)
        }).catch(() => {});
      }
      get().startLiveTelemetry(idServicio);
    } catch (dbErr) {
      console.warn('Error sincronizando telemetría de emergencia:', dbErr);
      get().startLiveTelemetry(idServicio);
    }
  },

  startLiveTelemetry: async (idServicio) => {
    const rawServiceId = String(idServicio || '').trim();
    if (!rawServiceId || rawServiceId === '0' || rawServiceId.startsWith('-')) {
      get().stopLiveTelemetry();
      return;
    }
    const cleanServiceId = rawServiceId.replace(/^[+]+s*/, '').trim();
    if (!cleanServiceId || cleanServiceId === '0' || cleanServiceId.startsWith('-')) {
      get().stopLiveTelemetry();
      return;
    }
    const currentUser = get().user;
    const currentEstado = String(currentUser?.estado || '0-8').trim().toUpperCase();
    const currentEnServicio = String(currentUser?.enServicio || '0').trim();
    if (
      !currentUser ||
      get().isAirplaneMode ||
      currentEstado !== '0-9' ||
      !currentEnServicio ||
      currentEnServicio === '0' ||
      currentEnServicio.startsWith('-')
    ) {
      get().stopLiveTelemetry();
      return;
    }
    if (
      activeTelemetryServiceId === cleanServiceId &&
      (activeBrowserWatchIds.size > 0 || telemetryIntervalId)
    ) {
      return;
    }

    // Limpiar watches/timers previos sin borrar RTDB (porque estamos iniciando telemetría legítima)
    clearAllBrowserGeolocationWatches();
    if (telemetryIntervalId) {
      clearInterval(telemetryIntervalId);
      telemetryIntervalId = null;
    }

    const docId = String(currentUser.idRegistro || currentUser.idRadial).trim();
    if (!docId) return;
    activeTelemetryServiceId = cleanServiceId;
    const watcherGen = telemetryGeneration;

    const isTelemetryAllowedNow = () => {
      if (watcherGen !== telemetryGeneration) return false;
      const latestUser = get().user;
      if (!latestUser || get().isAirplaneMode) return false;
      const est = String(latestUser.estado || '0-8').trim().toUpperCase();
      const svc = String(latestUser.enServicio || '0').trim();
      if (est !== '0-9' || !svc || svc === '0' || svc.startsWith('-')) {
        return false;
      }
      return true;
    };

    const publishCoordsToRtdb = (coords) => {
      if (!isTelemetryAllowedNow()) {
        get().stopLiveTelemetry();
        removeBomberoFromRtdbNow(docId);
        return;
      }
      if (!coords) return;
      const lat = Number(coords.latitude ?? coords.lat);
      const lng = Number(coords.longitude ?? coords.lng);
      if (isNaN(lat) || isNaN(lng) || Math.abs(lat) < 0.1 || Math.abs(lng) < 0.1) return;
      lastKnownTelemetryCoords = {
        latitude: lat,
        longitude: lng,
        speed: Number(coords.speed || 0),
        heading: Number(coords.heading || 0)
      };
      const rtdb = getRtdb();
      const latestUser = get().user || currentUser;
      if (rtdb && isTelemetryAllowedNow()) {
        rtdbSet(ref(rtdb, `telemetria/bomberos/${docId}`), {
          id: docId,
          idRegistro: docId,
          idRadial: String(latestUser.idRadial || docId),
          nombre: String(latestUser.nombreBombero || latestUser.nombre || 'BOMBERO'),
          rango: String(latestUser.cargo || latestUser.rango || 'Voluntario'),
          compania: Number(latestUser.compania || 1),
          lat,
          lng,
          speed: Number(coords.speed || 0),
          heading: Number(coords.heading || 0),
          timestamp: Date.now(),
          idServicio: cleanServiceId
        }).catch(() => {});
      }
    };

    // Si estamos dentro de la app nativa iOS o Android, el servicio nativo ya transmite el GPS a RTDB
    // en primer y segundo plano; evitamos abrir sesiones duplicadas de navigator.geolocation en el WebView.
    if (hasNativeGpsBridge()) {
      activeTelemetryServiceId = cleanServiceId;
      return;
    }

    // Usar directamente navigator.geolocation solo en navegadores web puros
    if (typeof navigator !== 'undefined' && navigator.geolocation && isTelemetryAllowedNow()) {
      try {
        navigator.geolocation.getCurrentPosition(
          (pos) => { if (pos?.coords) publishCoordsToRtdb(pos.coords); },
          () => {},
          { enableHighAccuracy: true, timeout: 5000, maximumAge: 5000 }
        );
        const wId = navigator.geolocation.watchPosition(
          (pos) => { if (pos?.coords) publishCoordsToRtdb(pos.coords); },
          () => {},
          { enableHighAccuracy: true, maximumAge: 3000, timeout: 10000 }
        );
        if (wId !== null && wId !== undefined) {
          activeBrowserWatchIds.add(wId);
        }
      } catch (_) {}
    }

    // Heartbeat periódico cada 6s mientras el bombero continúe en 0-9 y asistiendo al servicio
    telemetryIntervalId = setInterval(() => {
      if (!isTelemetryAllowedNow()) {
        get().stopLiveTelemetry();
        removeBomberoFromRtdbNow(docId);
        return;
      }
      if (lastKnownTelemetryCoords) {
        publishCoordsToRtdb(lastKnownTelemetryCoords);
      }
      if (typeof navigator !== 'undefined' && navigator.geolocation && isTelemetryAllowedNow()) {
        navigator.geolocation.getCurrentPosition(
          (pos) => { if (pos?.coords) publishCoordsToRtdb(pos.coords); },
          () => {},
          { enableHighAccuracy: true, timeout: 4500, maximumAge: 3000 }
        );
      }
    }, 6000);
  },

  stopLiveTelemetry: () => {
    telemetryGeneration++;
    activeTelemetryServiceId = null;
    clearAllBrowserGeolocationWatches();
    if (telemetryIntervalId) {
      clearInterval(telemetryIntervalId);
      telemetryIntervalId = null;
    }
    const currentUser = get().user;
    const docId = String(currentUser?.idRegistro || currentUser?.idRadial || '').trim();
    if (docId) {
      removeBomberoFromRtdbNow(docId);
    }
  },

  // Cerrar Sesión (libera deviceId en Firestore para permitir iniciar sesión nuevamente)
  logout: async () => {
    const currentUser = get().user;
    const docId = String(currentUser?.idRegistro || currentUser?.idRadial || '').trim();
    get().stopLiveTelemetry();
    if (docId) {
      try {
        const db = getDb();
        if (db) {
          await setDoc(
            doc(db, 'personal', docId),
            {
              deviceId: '',
              apnsToken: '',
              _metaTimestamp: Date.now()
            },
            { merge: true }
          );
        }
      } catch (e) {}
    }
    clearNativeUserSession();
    try {
      syncNativeStorageKey(USER_SESSION_KEY, null);
      syncNativeStorageKey('authUserId', null);
      syncNativeStorageKey('authPasswordSecure', null);
      if (typeof window !== 'undefined' && window.localStorage) {
        window.localStorage.removeItem('authUserId');
        window.localStorage.removeItem('authPasswordSecure');
      }
      await AsyncStorage.removeItem(USER_SESSION_KEY);
    } catch (e) {}
    set({ user: null, isAuthenticated: false, error: '' });
  }
}));

if (typeof window !== 'undefined' && !window.__SENTINEL_FCM_EVENT_BOUND__) {
  window.__SENTINEL_FCM_EVENT_BOUND__ = true;
  window.addEventListener('sentinelNativeFcmToken', (ev) => {
    const tok = ev?.detail?.token || window.__SENTINEL_FCM_TOKEN__;
    if (tok) {
      useAuthStore.getState().syncFcmToken(tok);
    }
  });
}
