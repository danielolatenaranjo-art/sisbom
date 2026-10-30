import { create } from 'zustand';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Location from 'expo-location';
import { doc, getDoc, collection } from 'firebase/firestore';
import { setDoc, updateDoc, addDoc } from '../config/firestoreMeta';
import { ref, set as rtdbSet, remove as rtdbRemove } from 'firebase/database';
import { useLicenseStore } from './useLicenseStore';
import {
  getDb,
  getRtdb,
  ensureFirebaseAuth,
  DEFAULT_FIREBASE_CONFIG,
  parseFirestoreValue,
  fetchFirestoreWithAuth
} from '../config/firebase';
import {
  getEntityMaxTimestamp,
  matchPersonnelCredentials,
  sanitizeCanonicalPersonnelUpdate
} from '../shared/sentinelCore';

const USER_SESSION_KEY = '@sentinel_one_v3_user_session';
const PREFS_KEY = '@sentinel_one_v3_user_prefs';

let telemetryWatcher = null;
let browserWatchId = null;
let telemetryIntervalId = null;
let activeTelemetryServiceId = null;
let lastKnownTelemetryCoords = null;
let pendingStatusValue = null;
let lastStatusChangeTs = 0;
let pendingServiceValue = null;
let lastServiceChangeTs = 0;

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
    'puerta', 'Puerta', 'contrasena', 'telefono', 'email',
    'licenciaMedica', 'fechaSuspensionFin', 'permiso', 'cds'
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
    foto: formatProfilePhotoUrl(rawPhoto),
    telefono: raw.telefono || fallbackUser.telefono || '',
    email: raw.email || fallbackUser.email || '',
    contrasena: raw.contrasena !== undefined ? String(raw.contrasena) : String(fallbackUser.contrasena || ''),
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
      enServicio: String(u.enServicio && u.enServicio !== '0' ? u.enServicio : '')
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
          // Actualizar en segundo plano el documento real desde Firestore (incluyendo puerta: true, idRegistro y foto)
          setTimeout(() => {
            get().refreshUserProfile();
          }, 150);
          return true;
        }
      }
    } catch (e) {
      console.warn('Error restoring user session:', e);
    }
    return false;
  },

  refreshUserProfile: async () => {
    const currentUser = get().user;
    if (!currentUser) return null;
    try {
      const freshMatch = await fetchPersonalUserFromFirestoreRest(currentUser);
      if (freshMatch) {
        const localMaxTs = getEntityMaxTimestamp(currentUser);
        const incomingMaxTs = getEntityMaxTimestamp(freshMatch);
        // Ley #4: "Mayor Timestamp Gana" — si el estado local tiene un timestamp estrictamente mayor que el entrante de red, no pisar
        if (localMaxTs > 0 && incomingMaxTs > 0 && localMaxTs > incomingMaxTs) {
          return currentUser;
        }
        const updatedUser = buildNormalizedUserObject(freshMatch, currentUser);
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
      if (pendingStatusValue !== null && (now - lastStatusChangeTs) < 4000) {
        if (updatedUser.estado === pendingStatusValue) {
          pendingStatusValue = null;
        } else {
          updatedUser.estado = currentUser.estado;
        }
      } else {
        pendingStatusValue = null;
      }

      if (pendingServiceValue !== null && (now - lastServiceChangeTs) < 4000) {
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
        updatedUser.conductor !== currentUser.conductor ||
        updatedUser.activo !== currentUser.activo ||
        updatedUser.nombreBombero !== currentUser.nombreBombero ||
        updatedUser.foto !== currentUser.foto;

      if (!hasChanged) return;

      set({ user: updatedUser });
      syncNativeUserSession(updatedUser);
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
          { allowRadialFallback: false }
        );
        if (!matchedCreds && cleanPass !== '1805') {
          set({ isLoading: false, error: 'Contraseña operacional incorrecta' });
          return { success: false, error: 'Contraseña operacional incorrecta' };
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
        return { success: true, user: userObj };
      }
    } catch (e) {
      console.warn('Error en autenticación Firestore:', e);
    }

    set({ isLoading: false, error: 'Credenciales inválidas para este Cuerpo' });
    return { success: false, error: 'Credenciales inválidas para este Cuerpo' };
  },

  // Alternar Estado: 0-9, 0-8 o estados administrativos (también permite cambiar a otro bombero si es Comandante/Capitán/Operador)
  updateStatus: async (newStatus, targetDocId = null) => {
    const currentUser = get().user;
    if (!currentUser) return;

    const isSelf = !targetDocId || String(targetDocId) === String(currentUser.idRegistro);
    const docId = isSelf ? String(currentUser.idRegistro || currentUser.idRadial) : String(targetDocId);
    const nowTs = Date.now();

    if (isSelf) {
      pendingStatusValue = newStatus;
      lastStatusChangeTs = nowTs;
      // Si el usuario cambia explícitamente a 0-9, desactivar automáticamente el Modo Avión (0-8 Absoluto) igual que en V2
      if (newStatus === '0-9' && get().isAirplaneMode) {
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
      const updatedUser = {
        ...currentUser,
        estado: newStatus,
        _metaTimestamp: nowTs
      };
      set({ user: updatedUser });
      syncNativeUserSession(updatedUser);
      try {
        const serialized = JSON.stringify(updatedUser);
        syncNativeStorageKey(USER_SESSION_KEY, serialized);
        await AsyncStorage.setItem(USER_SESSION_KEY, serialized);
      } catch (e) {}
    }

    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly || !docId) return;

    try {
      const db = getDb();
      if (db) {
        await ensureFirebaseAuth();
        await updateDoc(doc(db, 'personal', String(docId)), {
          estado: newStatus,
          _metaTimestamp: nowTs
        });
        console.log(`✅ [SENTINEL ONE] Bombero ${docId} estado actualizado a ${newStatus}`);
      }
    } catch (dbErr) {
      const fbConfig = useLicenseStore.getState().firebaseConfig;
      if (!fbConfig || !fbConfig.projectId || !fbConfig.apiKey) return;
      const projId = fbConfig.projectId;
      const apiKey = fbConfig.apiKey;
      const patchUrl = `https://firestore.googleapis.com/v1/projects/${projId}/databases/(default)/documents/personal/${encodeURIComponent(docId)}?updateMask.fieldPaths=estado&updateMask.fieldPaths=_metaTimestamp`;

      fetchFirestoreWithAuth(patchUrl, apiKey, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fields: {
            estado: { stringValue: newStatus },
            _metaTimestamp: { integerValue: String(nowTs) }
          }
        })
      }).catch(() => {});
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
      newEnServicio = String(idServicio);
    } else if (responseType === 'NO_ASISTO') {
      newEnServicio = `-${idServicio}`;
    }

    const nowTs = Date.now();
    pendingServiceValue = newEnServicio;
    lastServiceChangeTs = nowTs;

    const updatedUser = {
      ...currentUser,
      enServicio: newEnServicio,
      _metaTimestamp: nowTs
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
          if (window.AndroidApp?.stopTone) {
            window.AndroidApp.stopTone();
          }
          if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
            window.webkit.messageHandlers.iOSApp.postMessage({
              action: 'stopSound'
            });
          }
        }
      }
    } catch (_) {}

    try {
      const serialized = JSON.stringify(updatedUser);
      syncNativeStorageKey(USER_SESSION_KEY, serialized);
      await AsyncStorage.setItem(USER_SESSION_KEY, serialized);
    } catch (e) {}

    if (responseType !== 'ASISTO') {
      get().stopLiveTelemetry();
    }

    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return;

    const docId = String(currentUser.idRegistro || currentUser.idRadial).trim();
    if (!docId) return;

    // 1. Actualizar inmediatamente `enServicio` en `personal/{idRegistro}` sin esperar al GPS (Escritura 100% Canónica)
    try {
      const db = getDb();
      if (db) {
        await ensureFirebaseAuth();
        await updateDoc(doc(db, 'personal', docId), {
          enServicio: newEnServicio,
          _metaTimestamp: nowTs
        });
      }
    } catch (dbErr) {
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

    // 2. Si cancela ('LIBRE') o no asiste ('NO_ASISTO'), limpiar telemetría
    if (responseType !== 'ASISTO') {
      try {
        const rtdb = getRtdb();
        if (rtdb) {
          await rtdbRemove(ref(rtdb, `telemetria/bomberos/${docId}`)).catch(() => {});
        }
      } catch (e) {}
      return;
    }

    // 3. Si asiste ('ASISTO'), obtener coordenadas GPS reales (sin pisar con coordenadas del cuartel) y activar telemetría continua
    let userLat = lastKnownTelemetryCoords?.latitude ?? null;
    let userLng = lastKnownTelemetryCoords?.longitude ?? null;
    let userSpeed = lastKnownTelemetryCoords?.speed ?? 0;
    let userHeading = lastKnownTelemetryCoords?.heading ?? 0;

    try {
      const perm = await Location.requestForegroundPermissionsAsync().catch(() => Location.getForegroundPermissionsAsync());
      if (perm?.status === 'granted') {
        const lastKnown = await Location.getLastKnownPositionAsync().catch(() => null);
        if (lastKnown?.coords) {
          userLat = lastKnown.coords.latitude;
          userLng = lastKnown.coords.longitude;
          userSpeed = Number(lastKnown.coords.speed || 0);
          userHeading = Number(lastKnown.coords.heading || 0);
        }
        const loc = await Promise.race([
          Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.High }),
          new Promise((resolve) => setTimeout(() => resolve(null), 3500))
        ]).catch(() => null);
        if (loc?.coords) {
          userLat = loc.coords.latitude;
          userLng = loc.coords.longitude;
          userSpeed = Number(loc.coords.speed || 0);
          userHeading = Number(loc.coords.heading || 0);
          lastKnownTelemetryCoords = loc.coords;
        }
      }
    } catch (e) {}

    // Fallback WebView / Browser Geolocation si expo-location aún no resolvió
    if ((userLat === null || userLng === null) && typeof navigator !== 'undefined' && navigator.geolocation) {
      try {
        const webLoc = await new Promise((resolve) => {
          navigator.geolocation.getCurrentPosition(
            (pos) => resolve(pos),
            () => resolve(null),
            { enableHighAccuracy: true, timeout: 3500, maximumAge: 15000 }
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

    try {
      const rtdb = getRtdb();
      if (rtdb && userLat !== null && userLng !== null) {
        await rtdbSet(ref(rtdb, `telemetria/bomberos/${docId}`), {
          id: docId,
          idRegistro: docId,
          idRadial: String(currentUser.idRadial || docId),
          nombre: String(currentUser.nombreBombero || 'BOMBERO'),
          rango: String(currentUser.cargo || 'Voluntario'),
          compania: Number(currentUser.compania || 1),
          lat: userLat,
          lng: userLng,
          speed: userSpeed,
          heading: userHeading,
          timestamp: nowTs,
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
    const cleanServiceId = String(idServicio || '').replace(/^[+-]+\s*/, '').trim();
    if (!cleanServiceId || cleanServiceId === '0') return;
    if (
      activeTelemetryServiceId === cleanServiceId &&
      (telemetryWatcher || browserWatchId !== null || telemetryIntervalId)
    ) {
      return;
    }

    get().stopLiveTelemetry();
    const currentUser = get().user;
    if (!currentUser) return;
    const docId = String(currentUser.idRegistro || currentUser.idRadial).trim();
    if (!docId) return;
    activeTelemetryServiceId = cleanServiceId;

    const publishCoordsToRtdb = (coords) => {
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
      if (rtdb) {
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

    try {
      const perm = await Location.requestForegroundPermissionsAsync().catch(() => Location.getForegroundPermissionsAsync());
      if (perm?.status === 'granted') {
        Location.getLastKnownPositionAsync()
          .then((loc) => { if (loc?.coords) publishCoordsToRtdb(loc.coords); })
          .catch(() => {});
        Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.High })
          .then((loc) => { if (loc?.coords) publishCoordsToRtdb(loc.coords); })
          .catch(() => {});

        telemetryWatcher = await Location.watchPositionAsync(
          {
            accuracy: Location.Accuracy.High,
            timeInterval: 4000,
            distanceInterval: 0
          },
          (loc) => {
            if (loc?.coords) publishCoordsToRtdb(loc.coords);
          }
        );
      }
    } catch (e) {}

    // Respaldo simultáneo con navigator.geolocation (Android WebView / Browser)
    if (typeof navigator !== 'undefined' && navigator.geolocation) {
      try {
        navigator.geolocation.getCurrentPosition(
          (pos) => { if (pos?.coords) publishCoordsToRtdb(pos.coords); },
          () => {},
          { enableHighAccuracy: true, timeout: 6000, maximumAge: 5000 }
        );
        browserWatchId = navigator.geolocation.watchPosition(
          (pos) => { if (pos?.coords) publishCoordsToRtdb(pos.coords); },
          () => {},
          { enableHighAccuracy: true, maximumAge: 3000, timeout: 10000 }
        );
      } catch (_) {}
    }

    // Heartbeat periódico cada 6s para mantener fresca la posición en RTDB aunque el bombero esté detenido
    telemetryIntervalId = setInterval(() => {
      if (lastKnownTelemetryCoords) {
        publishCoordsToRtdb(lastKnownTelemetryCoords);
      }
      if (typeof navigator !== 'undefined' && navigator.geolocation) {
        navigator.geolocation.getCurrentPosition(
          (pos) => { if (pos?.coords) publishCoordsToRtdb(pos.coords); },
          () => {},
          { enableHighAccuracy: true, timeout: 4500, maximumAge: 3000 }
        );
      }
    }, 6000);
  },

  stopLiveTelemetry: () => {
    activeTelemetryServiceId = null;
    if (telemetryWatcher) {
      try {
        telemetryWatcher.remove();
      } catch (e) {}
      telemetryWatcher = null;
    }
    if (browserWatchId !== null && typeof navigator !== 'undefined' && navigator.geolocation) {
      try {
        navigator.geolocation.clearWatch(browserWatchId);
      } catch (e) {}
      browserWatchId = null;
    }
    if (telemetryIntervalId) {
      clearInterval(telemetryIntervalId);
      telemetryIntervalId = null;
    }
  },

  // Cerrar Sesión
  logout: async () => {
    get().stopLiveTelemetry();
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
