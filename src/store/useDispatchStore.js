import { create } from 'zustand';
import { Platform, NativeModules } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  collection,
  doc,
  query,
  where,
  onSnapshot,
  getDoc,
  getDocs,
  deleteDoc
} from 'firebase/firestore';
import {
  setDoc,
  updateDoc,
  addDoc
} from '../config/firestoreMeta';
import { ref, set as rtdbSet, remove as rtdbRemove } from 'firebase/database';
import { useLicenseStore } from './useLicenseStore';
import { useAuthStore, registerPersonalBridge } from './useAuthStore';
import {
  getDb,
  getRtdb,
  getCentralDb,
  getAuthInstance,
  resolveCachedUserAuthCredentials,
  ensureFirebaseAuth,
  reconnectFirebaseNetwork,
  reinitFirebaseWithConfig,
  DEFAULT_FIREBASE_CONFIG,
  parseFirestoreValue,
  fetchFirestoreWithAuth
} from '../config/firebase';
import {
  formatEpochToHHMM,
  formatEpochToDDMMYYYY,
  getLocalDateString,
  getLocalTimeString,
  resolveCompanyFromVehicleId,
  resolveCoreUnitStatusInfo,
  buildCanonicalCadAlertDoc,
  resolveCommanderIdRegistroFromPersonal,
  isCanonicalCommander,
  hasAdminModeFlag,
  hasCommanderPrivileges,
  formatShortPersonName,
  formatCanonicalChatEntry,
  appendCanonicalChatMessage,
  parseCanonicalChatMessages,
  calculateNextCanonicalSciRequestId,
  buildCanonicalCentralSciRequestDoc,
  buildCanonicalCentralSciIncidentId,
  buildCanonicalCentralSciIncidentDoc,
  normalizeSaasClientDoc,
  getEntityMaxTimestamp,
  mergeServiceUnitByHigherTimestamp
} from '../shared/sentinelCore';
import {
  createExponentialReconnectScheduler,
  generateNextCanonicalDispatchId
} from '../shared/sentinelSyncEngine';

// Mapeo de clave de emergencia / evento a recurso raw nativo de Android/iOS (res/raw/*.mp3)
const ANDROID_RAW_SOUNDS = {
  '10-0': 'c10_0',
  '10-1': 'c10_1',
  '10-2': 'c10_2',
  '10-3': 'c10_3',
  '10-4': 'c10_4',
  '10-5': 'c10_5',
  '10-6': 'c10_6',
  '10-7': 'c10_7',
  '10-8': 'c10_8',
  '10-9': 'c10_9',
  '10-10': 'c10_10',
  '10-12': 'c10_12',
  '10-15': 'c10_15',
  '10-30': 'c10_30',
  'ALARMA FORESTAL': 'c10_2forestal',
  '10-2 ALARMA FORESTAL': 'c10_2forestal',
  '9-0': 'c9_0',
  'alerta': 'alertas',
  'alertas': 'alertas',
  'importante': 'importante',
  'despacho': 'despacho',
  'comandancia': 'c_llamadocomandancia',
  'LLAMADO': 'c_llamadocomandancia',
  'LLAMADO COMANDANCIA': 'c_llamadocomandancia'
};

// Mapeo de archivos de audio locales para entorno Web
const SOUND_FILES = {
  '10-0': require('../../assets/sounds/c10_0.mp3'),
  '10-1': require('../../assets/sounds/c10_1.mp3'),
  '10-2': require('../../assets/sounds/c10_2.mp3'),
  '10-3': require('../../assets/sounds/c10_3.mp3'),
  '10-4': require('../../assets/sounds/c10_4.mp3'),
  '10-5': require('../../assets/sounds/c10_5.mp3'),
  '10-6': require('../../assets/sounds/c10_6.mp3'),
  '10-7': require('../../assets/sounds/c10_7.mp3'),
  '10-8': require('../../assets/sounds/c10_8.mp3'),
  '10-9': require('../../assets/sounds/c10_9.mp3'),
  '10-10': require('../../assets/sounds/c10_10.mp3'),
  '10-12': require('../../assets/sounds/c10_12.mp3'),
  '10-15': require('../../assets/sounds/c10_15.mp3'),
  '10-30': require('../../assets/sounds/c10_30.mp3'),
  'ALARMA FORESTAL': require('../../assets/sounds/c10_2Forestal.mp3'),
  '10-2 ALARMA FORESTAL': require('../../assets/sounds/c10_2Forestal.mp3'),
  '9-0': require('../../assets/sounds/c9_0.mp3'),
  'alerta': require('../../assets/sounds/alertas.mp3'),
  'alertas': require('../../assets/sounds/alertas.mp3'),
  'importante': require('../../assets/sounds/importante.mp3'),
  'despacho': require('../../assets/sounds/despacho.mp3'),
  'comandancia': require('../../assets/sounds/c_llamadoComandancia.mp3'),
  'LLAMADO': require('../../assets/sounds/c_llamadoComandancia.mp3'),
  'LLAMADO COMANDANCIA': require('../../assets/sounds/c_llamadoComandancia.mp3')
};

const PLAYED_SOUNDS_STORAGE_KEY = '@sentinel_one_v3_played_sounds';
const MAX_DISPATCH_AGE_MS = 5 * 60 * 1000; // 5 minutos máximo (Idéntico a TimeValidation.kt V2)

function getPlayedSoundsSet() {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const raw = window.localStorage.getItem(PLAYED_SOUNDS_STORAGE_KEY);
      if (raw) {
        const arr = JSON.parse(raw);
        if (Array.isArray(arr)) return new Set(arr);
      }
    }
  } catch (_) {}
  return new Set();
}

function hasPlayedSoundKey(key) {
  if (!key) return false;
  return getPlayedSoundsSet().has(String(key));
}

function markPlayedSoundKey(key) {
  if (!key) return;
  try {
    const set = getPlayedSoundsSet();
    set.add(String(key));
    const arr = Array.from(set).slice(-300);
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.setItem(PLAYED_SOUNDS_STORAGE_KEY, JSON.stringify(arr));
    }
  } catch (_) {}
}

export function resolveSoundKeyForClave(clave) {
  const c = String(clave || '').toUpperCase().trim();
  if (!c) return 'despacho';
  if (c.includes('FORESTAL')) return 'ALARMA FORESTAL';
  if (c.includes('10-30')) return '10-30';
  if (c.includes('9-0')) return '9-0';
  if (c.includes('LLAMADO') || c.includes('COMANDANCIA')) return 'comandancia';
  const baseMatch = c.match(/^10-\d+/);
  if (baseMatch && ANDROID_RAW_SOUNDS[baseMatch[0]]) {
    return baseMatch[0];
  }
  if (ANDROID_RAW_SOUNDS[c]) return c;
  return 'despacho';
}

export function isDispatchTooOld(dispatch) {
  if (!dispatch) return true;
  const now = Date.now();
  const createdMs = Number(dispatch.createdAt || dispatch.timestamp || dispatch.horaCreacion || 0);
  if (createdMs > 1_000_000_000_000) {
    return (now - createdMs) > MAX_DISPATCH_AGE_MS;
  }
  const fecha = String(dispatch.fechaDespacho || dispatch.fecha || '').trim();
  const hora = String(dispatch.horaDespacho || dispatch.hora || '').trim();
  if (fecha && hora) {
    const cleanDate = fecha.replace(/\//g, '-');
    const parts = cleanDate.split('-');
    const [hh, mm, ss] = hora.split(':').map((n) => parseInt(n, 10) || 0);
    if (parts.length === 3) {
      let d = parseInt(parts[0], 10);
      let m = parseInt(parts[1], 10) - 1;
      let y = parseInt(parts[2], 10);
      if (d > 1900) {
        y = parseInt(parts[0], 10);
        m = parseInt(parts[1], 10) - 1;
        d = parseInt(parts[2], 10);
      }
      const parsed = new Date(y, m, d, hh, mm, ss || 0).getTime();
      if (!isNaN(parsed) && parsed > 0) {
        return (now - parsed) > MAX_DISPATCH_AGE_MS;
      }
    }
  }
  return false;
}

export function hasUserRespondedToDispatch(dispatch, user) {
  if (!dispatch || !user) return false;
  const myId = String(user.idRegistro || user.idRadial || '').trim();
  if (!myId) return false;
  const dispId = String(dispatch.idServicio || dispatch.id || '').trim();
  const mySvc = String(user.enServicio || '0').trim();
  if (mySvc === dispId || mySvc === `-${dispId}`) return true;

  const matchEntry = (item) => {
    if (!item) return false;
    if (typeof item === 'string') return item.trim() === myId;
    if (typeof item === 'object') {
      return String(item.id || item.idRegistro || item.bomberoId || '').trim() === myId;
    }
    return false;
  };
  if (Array.isArray(dispatch.asistentes) && dispatch.asistentes.some(matchEntry)) return true;
  if (Array.isArray(dispatch.noAsistentes) && dispatch.noAsistentes.some(matchEntry)) return true;
  return false;
}

export function isUserAttendingDispatch(dispatch, user) {
  if (!dispatch || !user) return false;
  const myId = String(user.idRegistro || user.idRadial || '').trim();
  const dispId = String(dispatch.idServicio || dispatch.id || '').trim();
  if (String(user.enServicio || '').trim() === dispId) return true;
  if (Array.isArray(dispatch.asistentes)) {
    return dispatch.asistentes.some((item) => {
      if (typeof item === 'string') return item.trim() === myId;
      if (item && typeof item === 'object') {
        return String(item.id || item.idRegistro || item.bomberoId || '').trim() === myId;
      }
      return false;
    });
  }
  return false;
}

// Referencias a listeners onSnapshot en tiempo real
let unsubDespachos = null;
let unsubPersonal = null;
let unsubVehiculos = null;
let unsubAlertas = null;
let unsubCentral = null;
let unsubAsistencia = null;
let unsubProtocols = null;
let unsubCentralInterCorps = null;
let unsubCentralSciRequests = null;
let unsubCentralSciIncidents = null;
let unsubSciIncidentSubcols = new Map(); // idIncidenteSci -> [unsubSolicitudes, unsubMensajes]
let lastInterCorpsAlertTs = 0;
let notifiedSciRequestIds = new Set();
let isFirstSciSnapshot = true;
let lastSciChatMsgCount = -1;
let latestCentralClientsMap = {};
let latestOwnSciSubcolMap = {};
let latestCentralSciIncidentsMap = {};
let hasLoadedCentralSciIncidents = false;
let latestSciIncidentSolicitudesByInc = {};
let latestSciIncidentMensajesByInc = {};

let isFirstDispatchesSnapshot = true;
let notifiedDispatchIds = new Set();
let lastDispatchClaves = {};
let lastUnitConductorReq = {};
let lastUnitPersonalReq = {};
let repeatTimersByKey = {};
let nativeEventsBound = false;

let lastKnownDispatchId = null;
let lastPlayedSolicitudTs = 0;
let knownAlertIds = new Set();
let isFirstAlertsSnapshot = true;
let knownDoorRequestKeys = new Set();
let isFirstCentralSnapshot = true;
let lastSubscribedAttendanceUserId = '';
let lastCentralRawData = null;
let lastSnapshotReceivedTs = 0;
let lastRestHydrationTs = 0;
let activeSubscribedProjectId = '';
let lastAppHiddenTs = 0;
let lastReconnectAttemptTs = 0;
let realtimeWatchdogIntervalId = null;
const REST_HYDRATION_COOLDOWN_MS = 5 * 60 * 1000; // 5 minutos mínimo entre hidrataciones REST
const oneReconnectScheduler = createExponentialReconnectScheduler({ baseDelayMs: 15000, maxDelayMs: 60000 });

// ============================================================================
// LEY #4: CONTROL MONOTÓNICO POR TIMESTAMP ("EL MAYOR TIMESTAMP GANA")
// Ningún snapshot atrasado de red ni caché antigua puede pisar un dato con timestamp mayor
// ============================================================================
const ENTITY_META_TS_KEY = '@sentinel_one_v3_entity_meta_timestamps';
const recentLocalWrites = new Map(); // key: `${col}:${docId}` -> timestamp ms

try {
  if (typeof window !== 'undefined' && window.localStorage) {
    const rawMetaTs = window.localStorage.getItem(ENTITY_META_TS_KEY);
    if (rawMetaTs) {
      const parsedMeta = JSON.parse(rawMetaTs);
      if (parsedMeta && typeof parsedMeta === 'object') {
        Object.entries(parsedMeta).forEach(([k, v]) => {
          const num = Number(v);
          if (!isNaN(num) && num > 0) recentLocalWrites.set(k, num);
        });
      }
    }
  }
} catch (_) {}

function persistEntityMetaTimestamps() {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const obj = {};
      recentLocalWrites.forEach((v, k) => {
        obj[k] = v;
      });
      window.localStorage.setItem(ENTITY_META_TS_KEY, JSON.stringify(obj));
    }
  } catch (_) {}
}

export function markLocalWrite(colName, docId, explicitTs = null) {
  if (!colName || !docId) return 0;
  const key = `${colName}:${String(docId).trim()}`;
  const prevTs = recentLocalWrites.get(key) || 0;
  const nextTs = Math.max(prevTs, Number(explicitTs) || Date.now());
  recentLocalWrites.set(key, nextTs);
  persistEntityMetaTimestamps();
  return nextTs;
}

export function getLocalWriteTimestamp(colName, docId) {
  if (!colName || !docId) return 0;
  return recentLocalWrites.get(`${colName}:${String(docId).trim()}`) || 0;
}

export function shouldKeepLocalByTimestamp(colName, docId, localEntity, incomingEntity, windowMs = 10000) {
  const cleanId = String(docId || '').trim();
  const recordedLocalTs = getLocalWriteTimestamp(colName, cleanId);
  const localMaxTs = Math.max(recordedLocalTs, getEntityMaxTimestamp(localEntity));
  const incomingMaxTs = getEntityMaxTimestamp(incomingEntity);

  // 1. Si el timestamp del estado local es estrictamente MAYOR que el timestamp entrante de red, gana el mayor
  if (localMaxTs > 0 && incomingMaxTs > 0 && localMaxTs > incomingMaxTs) {
    return true;
  }

  // 2. Si el estado local tiene _metaTimestamp V3 (> 0) y el entrante viene sin timestamp (0) dentro de la ventana de protección
  if (localMaxTs > 0 && incomingMaxTs === 0 && recordedLocalTs > 0 && (Date.now() - recordedLocalTs) < windowMs) {
    return true;
  }

  // 3. Ventana de seguridad inmediata tras una escritura local si el entrante no supera al local
  if (recordedLocalTs > 0 && (Date.now() - recordedLocalTs) < windowMs && incomingMaxTs < recordedLocalTs) {
    return true;
  }

  return false;
}

function mergeDispatchEntitiesByHigherTimestamp(localDisp, incomingDisp) {
  if (!localDisp) return incomingDisp;
  if (!incomingDisp) return localDisp;

  const localTs = getEntityMaxTimestamp(localDisp);
  const incomingTs = getEntityMaxTimestamp(incomingDisp);

  const localUnits = (localDisp.unidades && typeof localDisp.unidades === 'object') ? localDisp.unidades : {};
  const incomingUnits = (incomingDisp.unidades && typeof incomingDisp.unidades === 'object') ? incomingDisp.unidades : {};
  const allUnitKeys = new Set([...Object.keys(localUnits), ...Object.keys(incomingUnits)]);
  const mergedUnits = {};

  allUnitKeys.forEach((uKey) => {
    const lU = localUnits[uKey] ? { ...localUnits[uKey], name: uKey } : null;
    const rU = incomingUnits[uKey] ? { ...incomingUnits[uKey], name: uKey } : null;
    if (lU && rU) {
      const mergedU = mergeServiceUnitByHigherTimestamp(lU, rU);
      const lCondTs = Number(lU.solicitudConductorTimestamp || 0);
      const rCondTs = Number(rU.solicitudConductorTimestamp || 0);
      const lPersTs = Number(lU.solicitudPersonalTimestamp || 0);
      const rPersTs = Number(rU.solicitudPersonalTimestamp || 0);
      mergedUnits[uKey] = {
        ...rU,
        ...lU,
        ...mergedU,
        solicitudConductorTimestamp: Math.max(lCondTs, rCondTs) || null,
        solicitudPersonalTimestamp: Math.max(lPersTs, rPersTs) || null,
        _metaTimestamp: Math.max(getEntityMaxTimestamp(lU), getEntityMaxTimestamp(rU)) || Date.now()
      };
    } else {
      mergedUnits[uKey] = rU || lU;
    }
  });

  const winner = localTs > incomingTs ? { ...incomingDisp, ...localDisp } : { ...localDisp, ...incomingDisp };
  return {
    ...winner,
    unidades: mergedUnits,
    _metaTimestamp: Math.max(localTs, incomingTs)
  };
}

export const CHILEAN_DISPATCH_KEYS = [
  { key: '10-0', desc: 'Incendio Estructural' },
  { key: '10-1', desc: 'Incendio Vehicular' },
  { key: '10-2', desc: 'Pastizales / Basura' },
  { key: '10-3', desc: 'Rescate Personas' },
  { key: '10-4', desc: 'Rescate Vehicular' },
  { key: '10-5', desc: 'Hazmat' },
  { key: '10-6', desc: 'Emanación Gas' },
  { key: '10-7', desc: 'Eléctrico' },
  { key: '10-8', desc: 'No Clasificado' },
  { key: '10-9', desc: 'Otros Servicios' },
  { key: '10-10', desc: 'Rebrote' },
  { key: '10-12', desc: 'Apoyo Otros Cuerpos' },
  { key: '10-15', desc: 'Simulacro' },
  { key: '9-0', desc: 'Acuartelamiento' },
  { key: 'LLAMADO', desc: 'Comandancia' }
];

export const SPECIAL_DISPATCH_KEYS = [
  { key: '10-30', title: '10-30 INCENDIO DECLARADO', desc: '10-30 Incendio Declarado', type: 'declarado' },
  { key: 'ALARMA FORESTAL', title: '10-2 ALARMA FORESTAL', desc: '10-2 Alarma Forestal', type: 'forestal' }
];

export const DEFAULT_PROTOCOLS = {
  '10-0': { units: ['B-1', 'R-1'], label: 'Bomba + Rescate' },
  '10-1': { units: ['B-1', 'R-1'], label: 'Bomba + Rescate' },
  '10-2': { units: ['BF-1', 'BT-1'], label: 'Forestal + Cisterna' },
  '10-3': { units: ['R-1', 'B-1'], label: 'Rescate + Bomba' },
  '10-4': { units: ['R-1', 'B-1'], label: 'Rescate + Bomba' },
  '10-5': { units: ['RS-1', 'B-1'], label: 'Hazmat + Bomba' },
  '10-6': { units: ['RS-1', 'B-1'], label: 'Hazmat + Bomba' },
  '10-7': { units: ['B-1'], label: 'Bomba' },
  '10-8': { units: ['B-1'], label: 'Bomba' },
  '10-9': { units: ['B-1'], label: 'Bomba' },
  '10-10': { units: ['B-1', 'BF-1'], label: 'Bomba + Forestal' },
  '10-12': { units: ['B-1'], label: 'Apoyo Bomba' },
  '10-15': { units: ['B-1'], label: 'Simulacro' },
  '9-0': { units: ['K-1'], label: 'Comandancia' },
  'LLAMADO': { units: ['K-1'], label: 'Comandancia' },
  '10-30': { units: ['B-1', 'BF-1', 'BT-1', 'R-1'], label: 'Alarma General' },
  'ALARMA FORESTAL': { units: ['BF-1', 'BT-1', 'B-1'], label: 'Fuerza Forestal' }
};

function normalizeComparableName(str = '') {
  return String(str || '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .trim()
    .toUpperCase();
}

function shortOperatorName(fullName = '') {
  const words = normalizeComparableName(fullName).split(/\s+/).filter(Boolean);
  if (words.length === 0) return '';
  if (words.length <= 2) return words.join(' ');
  return `${words[0]} ${words[words.length - 2]}`;
}

function cleanSheetPrefix(val) {
  return String(val ?? '').replace(/^'+/, '').trim();
}

// Validador de vigencia de alertas (Idéntico a alertas.html y TimeValidation.kt de V2)
export function isAlertActiveByDuration(alert) {
  if (!alert) return false;
  if (alert.tipo === 'orden') return true;

  // Si duracion es explícitamente string vacío "", la alerta fue eliminada
  if (alert.duracion !== undefined && alert.duracion !== null && cleanSheetPrefix(alert.duracion) === '') {
    return false;
  }

  const dur = cleanSheetPrefix(alert.duracion ?? 'i').toLowerCase();
  if (!dur || dur === 'i' || dur === 'c' || dur === 'permanente') return true;

  const now = new Date();

  // Formato fecha límite: DD-MM-YYYY
  if (/^\d{2}-\d{2}-\d{4}$/.test(dur)) {
    const [d, m, y] = dur.split('-').map(Number);
    const exp = new Date(y, m - 1, d, 23, 59, 59);
    return now <= exp;
  }

  // Formato fecha y hora límite: DD-MM-YYYY HH:MM
  if (/^\d{2}-\d{2}-\d{4}\s+\d{2}:\d{2}$/.test(dur)) {
    const [datePart, timePart] = dur.split(/\s+/);
    const [d, m, y] = datePart.split('-').map(Number);
    const [hh, mm] = timePart.split(':').map(Number);
    const exp = new Date(y, m - 1, d, hh, mm, 59);
    return now <= exp;
  }

  // Fallback numérico en horas
  const hours = parseFloat(dur);
  if (isNaN(hours) || hours <= 0) return true;

  let createdMs = Number(alert.timestamp || alert.createdAt || 0);
  if (!createdMs && alert.fechaAlerta) {
    const cleanDate = cleanSheetPrefix(alert.fechaAlerta).replace(/\//g, '-');
    const cleanTime = cleanSheetPrefix(alert.horaAlerta || '00:00');
    const parts = cleanDate.split('-');
    const [hh, mm] = cleanTime.split(':').map(n => parseInt(n, 10) || 0);
    if (parts.length === 3) {
      const d = parseInt(parts[0], 10);
      const m = parseInt(parts[1], 10) - 1;
      const y = parseInt(parts[2], 10);
      const parsed = new Date(y, m, d, hh, mm).getTime();
      if (!isNaN(parsed)) createdMs = parsed;
    }
  }

  if (!createdMs) return true;
  const expiresAt = createdMs + hours * 3600 * 1000;
  return Date.now() <= expiresAt;
}

// Normalizador Dual-Read de Despacho (V2 + V3 Canónico)
function normalizeDispatchDoc(docId, raw = {}) {
  const idServicio = String(raw.idServicio || raw.id || docId || '').trim();
  const clave = String(raw.clave || '10-0').trim();
  const subclave = String(raw.subclave || '').trim();
  const claveApoyo = String(raw.claveApoyo || '').trim();
  const direccion = String(
    raw.direccion || raw.lugar || raw.location || raw.ubicacionTexto || ''
  ).trim();
  const referencia = String(raw.referencia || '').trim();
  const preinforme = String(raw.preinforme || raw.detalle || raw.obs || '').trim();
  const estado = String(raw.estado || '').trim();
  const operadorInicial = String(raw.operadorInicial || raw.quienDespacha || '').trim();
  const operadorFinal = String(raw.operadorFinal || '').trim();
  const visibleMovil = raw.visibleMovil !== false;

  const createdAt = Number(raw.createdAt || raw.timestamp || 0) || 0;
  const horaStr = formatEpochToHHMM(createdAt) || String(raw.horaDespacho || raw.hora || '').replace(/['"]/g, '').trim();
  const fechaStr = formatEpochToDDMMYYYY(createdAt) || String(raw.fechaDespacho || raw.fecha || '').replace(/['"]/g, '').trim();

  const h67 = raw.h67 || null;
  const hora67 = formatEpochToHHMM(h67) || String(raw.hora67 || '').replace(/['"]/g, '').trim();

  // Geolocalización: Prioridad objeto canónico V3 `geo`, con fallback Dual-Read a coords / geolocalizacion / ubicacion / raíz
  const geoObj = (raw.geo && typeof raw.geo === 'object') ? raw.geo : {};
  const coordsObj = (raw.coords && typeof raw.coords === 'object') ? raw.coords : {};
  const geolocObj = (raw.geolocalizacion && typeof raw.geolocalizacion === 'object') ? raw.geolocalizacion : {};
  const ubicObj = (raw.ubicacion && typeof raw.ubicacion === 'object') ? raw.ubicacion : {};
  const alertGeoObj = (raw.alertanteGeo && typeof raw.alertanteGeo === 'object') ? raw.alertanteGeo : {};

  const lat = Number(
    geoObj.lat ??
    coordsObj.lat ??
    geolocObj.lat ??
    geolocObj.latitude ??
    ubicObj.lat ??
    ubicObj.latitude ??
    alertGeoObj.lat ??
    raw.lat ??
    raw.latitud ??
    0
  ) || null;
  const lng = Number(
    geoObj.lng ??
    coordsObj.lng ??
    geolocObj.lng ??
    geolocObj.longitude ??
    ubicObj.lng ??
    ubicObj.longitude ??
    alertGeoObj.lng ??
    raw.lng ??
    raw.longitud ??
    0
  ) || null;
  const geoOrigen = String(geoObj.origen || '').trim();

  // Unidades y Carros
  const unidades = (raw.unidades && typeof raw.unidades === 'object' && !Array.isArray(raw.unidades))
    ? raw.unidades
    : {};

  let carrosList = [];
  if (Array.isArray(raw.carros)) {
    carrosList = raw.carros.map(c => String(c).trim()).filter(Boolean);
  } else if (typeof raw.carros === 'string' && raw.carros.trim() !== '') {
    carrosList = raw.carros.split(/[,/]+/).map(c => c.trim()).filter(Boolean);
  }
  if (carrosList.length === 0 && Object.keys(unidades).length > 0) {
    carrosList = Object.keys(unidades);
  }

  return {
    ...raw,
    id: idServicio,
    idServicio,
    clave,
    subclave,
    claveApoyo,
    direccion,
    lugar: direccion,
    referencia,
    preinforme,
    estado,
    operadorInicial,
    quienDespacha: operadorInicial,
    operadorFinal,
    visibleMovil,
    createdAt,
    horaStr,
    horaDespacho: horaStr,
    fechaDespacho: fechaStr,
    h67,
    hora67,
    geo: {
      lat,
      lng,
      accuracy: Number(geoObj.accuracy || 10),
      origen: geoOrigen
    },
    lat,
    lng,
    unidades,
    carros: carrosList,
    carrosStr: carrosList.join(' / '),
    _metaTimestamp: Number(raw._metaTimestamp || raw.updatedAt || createdAt || 0) || 0,
    _fieldTimestamps: raw._fieldTimestamps && typeof raw._fieldTimestamps === 'object' ? raw._fieldTimestamps : {}
  };
}

// Normalizador de Alerta / Orden del Día (100% compatible con alertas.html y TabScreens.kt)
function normalizeAlertDoc(docId, raw = {}) {
  const idAlerta = cleanSheetPrefix(raw.idAlerta || raw.id || docId);
  const tipo = String(raw.tipo || (raw.numeroOrden ? 'orden' : 'alerta')).trim().toLowerCase();
  const timestamp = Number(raw.timestamp || raw.createdAt || 0) || 0;
  const metaTs = Number(raw._metaTimestamp || raw.updatedAt || timestamp || 0) || 0;
  const fechaAlerta = cleanSheetPrefix(
    raw.fechaAlerta || raw.fecha || formatEpochToDDMMYYYY(timestamp) || getLocalDateString()
  );
  const horaAlerta = cleanSheetPrefix(
    raw.horaAlerta || raw.hora || formatEpochToHHMM(timestamp) || getLocalTimeString()
  );
  const fijarStr = Array.isArray(raw.fijar)
    ? raw.fijar.join(',')
    : cleanSheetPrefix(raw.fijar || '');
  const fijadaBool = Boolean(raw.fijada) || Boolean(fijarStr.trim());
  const quienStr = cleanSheetPrefix(raw.quienAlerta || raw.creadoPor || raw.autor || 'COMANDANCIA');

  // Preservar duracion vacía "" cuando fue eliminada
  const rawDuracion =
    raw.duracion === undefined || raw.duracion === null
      ? 'i'
      : cleanSheetPrefix(raw.duracion);

  return {
    id: idAlerta,
    idAlerta,
    tipo,
    gradoAlerta: cleanSheetPrefix(raw.gradoAlerta || raw.grado || '1') || '1',
    aQuienAlerta: cleanSheetPrefix(raw.aQuienAlerta || raw.destinatarios || 'TC') || 'TC',
    quienAlerta: quienStr,
    creadoPor: quienStr,
    razonAlerta: cleanSheetPrefix(raw.razonAlerta || raw.titulo || ''),
    mensajeAlerta: String(raw.mensajeAlerta || raw.mensaje || raw.cuerpo || '').trim(),
    fechaAlerta,
    horaAlerta,
    fecha: fechaAlerta,
    hora: horaAlerta,
    duracion: rawDuracion,
    conforme: Array.isArray(raw.conforme)
      ? raw.conforme.join(',')
      : cleanSheetPrefix(raw.conforme || ''),
    fijar: fijarStr,
    fijada: fijadaBool,
    numeroOrden: cleanSheetPrefix(raw.numeroOrden || raw.numero || ''),
    fechaOrden: cleanSheetPrefix(raw.fechaOrden || fechaAlerta),
    firmaNombre: cleanSheetPrefix(raw.firmaNombre || ''),
    firmaCargo: cleanSheetPrefix(raw.firmaCargo || ''),
    tipoSolicitud: cleanSheetPrefix(raw.tipoSolicitud || ''),
    licenciaSolicitante: cleanSheetPrefix(raw.licenciaSolicitante || '').toUpperCase(),
    nombreCuerpoSolicitante: cleanSheetPrefix(raw.nombreCuerpoSolicitante || ''),
    claveEmergencia: cleanSheetPrefix(raw.claveEmergencia || ''),
    direccionEmergencia: cleanSheetPrefix(raw.direccionEmergencia || ''),
    idCarroSolicitado: cleanSheetPrefix(raw.idCarroSolicitado || ''),
    sectorDestino: cleanSheetPrefix(raw.sectorDestino || ''),
    instruccionTactica: cleanSheetPrefix(raw.instruccionTactica || ''),
    estadoApoyo: cleanSheetPrefix(raw.estadoApoyo || raw.estadoRespuesta || '').toUpperCase(),
    respondidoPor: cleanSheetPrefix(raw.respondidoPor || ''),
    respondidoAt: Number(raw.respondidoAt || 0) || null,
    timestamp,
    _metaTimestamp: metaTs,
    _fieldTimestamps: raw._fieldTimestamps && typeof raw._fieldTimestamps === 'object' ? raw._fieldTimestamps : {}
  };
}

// Normalizador de Lista de Asistencia (Dual-Read V2 + V3 idéntico a normalizeAttendanceDoc en SHARED/sentinelCore.js)
function formatPersonSummary(val) {
  if (!val) return '';
  if (typeof val === 'object') {
    const rad = String(val.idRadial || val.codigoRadial || val.id || '').trim();
    const nom = String(val.nombre || val.nombreBombero || '').trim();
    if (rad && nom) return `${rad} - ${nom}`;
    return nom || rad;
  }
  const str = String(val).trim();
  if (str === '[object Object]') return '';
  const bracketMatch = str.match(/^\[([^\]]+)\]\s*(.+)$/);
  if (bracketMatch) {
    return `${bracketMatch[1].trim()} - ${bracketMatch[2].trim()}`;
  }
  return str;
}

function normalizeAttendanceSheetDoc(docId, raw = {}) {
  const idLista = String(raw.idLista || raw.id || docId || '').trim();
  const ts = Number(raw.timestamp || raw.createdAt || 0) || 0;
  const metaTs = Number(raw._metaTimestamp || raw.updatedAt || ts || 0) || 0;
  const fecha = String(raw.fecha || formatEpochToDDMMYYYY(ts) || '').replace(/['"]/g, '').trim();
  const hora = String(raw.hora || formatEpochToHHMM(ts) || '').replace(/['"]/g, '').trim();

  const anuladaVal =
    raw.anulada === true ||
    raw.anulada === 1 ||
    String(raw.anulada || '').trim().toUpperCase() === 'SI' ||
    String(raw.anulada || '').trim() === '1';

  const rawAbono = raw.esAbono ?? raw.abono ?? raw.userAbono ?? raw.tipo ?? 'NO';
  const listaPorStr = formatPersonSummary(raw.listaPor || raw.listaPasadaPor || raw.creadoPor || '');
  const obacStr = formatPersonSummary(raw.obac || raw.obacServicio || '');
  const aprobadoPorStr = formatPersonSummary(raw.aprobadoPor || '');

  return {
    id: idLista,
    idLista,
    nroLista: raw.nroLista ?? raw.numeroLista ?? '',
    clave: String(raw.clave || raw.acto || '').trim(),
    tipo: String(raw.tipo || '').trim(),
    fecha,
    hora,
    lugar: String(raw.lugar || raw.direccion || '').trim(),
    aprobadoPor: aprobadoPorStr,
    listaPor: listaPorStr,
    listaPasadaPor: listaPorStr,
    obac: obacStr,
    detalle: String(raw.detalle || raw.detalles || raw.observacion || '').trim(),
    anulada: anuladaVal,
    userEstado: String(raw.userEstado || ''),
    userAbono: rawAbono,
    inlineBomberos: raw.bomberos && typeof raw.bomberos === 'object' ? raw.bomberos : null,
    timestamp: ts,
    _metaTimestamp: metaTs
  };
}

// Identifica cualquier documento legado/residual de SCI para NO mezclarlo jamás en la colección local `alertas`
function isResidualSciAlertDoc(a) {
  if (!a || typeof a !== 'object') return false;
  const id = String(a.idAlerta || a.id || '').trim().toUpperCase();
  if (/^SCI-\d{4}-\d+/.test(id)) return true;
  if (id.startsWith('SCI_ALERTA_') || id.startsWith('SCI_CHAT_')) return true;
  if (Boolean(a.tipoSolicitud || a.licenciaSolicitante)) return true;
  return false;
}

// Filtro estricto Anti-Simulacros SCI: NUNCA mostrar ni alertar simulacros (SIM-SCI-* o isSimulated === true)
function isSimulatedSciPayload(obj, fallbackId = '') {
  if (!obj || typeof obj !== 'object') return false;
  if (obj.isSimulated === true || String(obj.isSimulated || '').trim().toLowerCase() === 'true') {
    return true;
  }
  const svcId = String(obj.idServicio || obj.idServicioOrigen || '').trim().toUpperCase();
  const incId = String(obj.idIncidenteSci || obj.id || fallbackId || '').trim().toUpperCase();
  if (
    svcId.startsWith('SIM-SCI-') ||
    incId.startsWith('SIM-SCI-') ||
    incId.includes('_SIM-SCI-')
  ) {
    return true;
  }
  return false;
}

// Verifica si un documento de incidente `sci_incidentes/{idIncidenteSci}` se encuentra realmente activo
function isSciIncidentDocActive(incData, fallbackId = '') {
  if (!incData || typeof incData !== 'object') return false;
  if (isSimulatedSciPayload(incData, fallbackId)) return false;
  if (incData.activo === false || incData.sciActivo === false || incData.activa === false) {
    return false;
  }
  const st = String(incData.estado || '').trim().toUpperCase();
  if (
    [
      'CERRADO',
      'CERRADA',
      'FINALIZADO',
      'FINALIZADA',
      'CANCELADO',
      'CANCELADA',
      'INACTIVO',
      'INACTIVA'
    ].includes(st)
  ) {
    return false;
  }
  return true;
}

// Helper para separar órdenes, alertas y canal de chat activo (100% libre de documentos SCI)
function splitAlertsAndOrders(allList = [], currentChat = null) {
  const cleanAll = (Array.isArray(allList) ? allList : []).filter(a => !isResidualSciAlertDoc(a));
  const ordersList = cleanAll.filter(a => a.tipo === 'orden');
  const alertsList = cleanAll.filter(a => a.tipo !== 'orden');
  const currentChatIsSci = currentChat && isResidualSciAlertDoc(currentChat);
  const foundChat = currentChatIsSci
    ? currentChat
    : (currentChat && cleanAll.find(a => a.idAlerta === currentChat.idAlerta)) ||
      cleanAll.find(a => String(a.duracion || '').toUpperCase() === 'C') ||
      alertsList[0] ||
      null;

  return {
    allAlertsList: cleanAll,
    ordersList,
    alertsList,
    activeChatAlert: foundChat,
    chatAlert: foundChat
  };
}

// Helper para lectura síncrona inicial de caché local (0ms al abrir la app)
const ACTIVE_DISPATCHES_CACHE_KEY = '@sentinel_one_v3_active_dispatches_cache';

function readInitialActiveDispatchesFromLocalStorage() {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const raw = window.localStorage.getItem(ACTIVE_DISPATCHES_CACHE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) {
          return parsed.filter((d) => {
            if (!d || typeof d !== 'object') return false;
            const st = String(d.estado || '').toLowerCase().trim();
            const rawOpFinal = String(d.operadorFinal || '').trim();
            const opFinal = (rawOpFinal === 'null' || rawOpFinal === '<null>') ? '' : rawOpFinal;
            const isClosed =
              st === 'cancelada' ||
              st === 'cancelado' ||
              st === 'finalizada' ||
              st === 'finalizado' ||
              st === 'cerrada' ||
              st === 'cerrado' ||
              st === 'terminada' ||
              st === 'terminado' ||
              Boolean(opFinal) ||
              Number(d.finalizadoAt) > 0;
            return !isClosed && d.visibleMovil !== false;
          });
        }
      }
    }
  } catch (_) {}
  return [];
}

function persistActiveDispatchesToLocalStorage(list = []) {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.setItem(ACTIVE_DISPATCHES_CACHE_KEY, JSON.stringify(Array.isArray(list) ? list : []));
    }
  } catch (_) {}
}

function readInitialAttendanceFromLocalStorage() {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const rawUser = window.localStorage.getItem('@sentinel_one_v3_user_session');
      if (rawUser) {
        const parsedUser = JSON.parse(rawUser);
        const userId = String(parsedUser?.idRegistro || parsedUser?.idRadial || '').trim();
        if (userId) {
          const rawAtt = window.localStorage.getItem(`@sentinel_one_v3_attendance_v4_canon_${userId}`);
          if (rawAtt) {
            const parsedAtt = JSON.parse(rawAtt);
            if (Array.isArray(parsedAtt)) return parsedAtt;
          }
        }
      }
    }
  } catch (_) {}
  return [];
}

const initialCachedDispatches = readInitialActiveDispatchesFromLocalStorage();

export const useDispatchStore = create((set, get) => ({
  activeDispatch: initialCachedDispatches[0] || null,
  dispatchesList: initialCachedDispatches,
  fullscreenDispatchId: null,
  urgentBannerMessage: null,
  firefightersList: [],
  vehiclesList: [],
  vehiculosList: [],
  allAlertsList: [],
  ordersList: [],
  alertsList: [],
  attendanceList: readInitialAttendanceFromLocalStorage(),
  isLoadingAttendance: false,
  isAttendanceLoading: false,
  centralOperatorName: '',
  centralOperatorId: '',
  centralSessionId: '',
  isCentralActive: false,
  protocols: { ...DEFAULT_PROTOCOLS },
  activeChatAlert: null,
  chatAlert: null,
  interCorpsAlert: null,
  sciRequestsList: [],
  activeSciNeighborCorps: [],
  sciChatMessagesRaw: '',
  sciUnreadCount: 0,
  isSciTabActive: false,
  isRespondingInterCorps: false,
  isLoading: false,
  soundObject: null,
  isAudioPlaying: false,
  isLiveConnected: false,

  setActiveChatAlert: (alertObj) => set({ activeChatAlert: alertObj, chatAlert: alertObj }),

  markSciAsRead: () => {
    const msgs = parseCanonicalChatMessages(get().sciChatMessagesRaw || '');
    lastSciChatMsgCount = msgs.length;
    const pendingReqs = (get().sciRequestsList || []).filter((r) =>
      ['PENDIENTE', 'ALERTA'].includes(String(r.estado || '').trim().toUpperCase())
    ).length;
    set({ sciUnreadCount: pendingReqs });
  },

  clearUrgentBanner: () => set({ urgentBannerMessage: null }),

  dismissFullscreenDispatch: (dispatchId = null) => {
    const targetId = dispatchId || get().fullscreenDispatchId;
    get().stopDispatchSound();
    if (targetId) {
      get().cancelRepeatAlertsForDispatch(targetId);
    }
    set({ fullscreenDispatchId: null });
  },

  cancelRepeatAlertsForDispatch: (dispatchId) => {
    if (!dispatchId) return;
    const prefix = `${dispatchId}`;
    Object.keys(repeatTimersByKey).forEach((k) => {
      if (k === prefix || k.startsWith(`${prefix}_`)) {
        clearTimeout(repeatTimersByKey[k]);
        delete repeatTimersByKey[k];
      }
    });
  },

  scheduleRepeatAlert: (dispatchId, clave) => {
    if (!dispatchId) return;
    get().cancelRepeatAlertsForDispatch(dispatchId);
    repeatTimersByKey[dispatchId] = setTimeout(() => {
      delete repeatTimersByKey[dispatchId];
      const { isAirplaneMode, user } = useAuthStore.getState();
      if (isAirplaneMode || get().isCentralActive || !user) return;
      const userEstado = String(user.estado || '0-9').trim();
      if (userEstado !== '0-9') return;
      const cargoUp = String(user.cargo || '').toUpperCase().trim();
      if (cargoUp === 'BOMBERO HONORARIO') return;

      const disp = (get().dispatchesList || []).find((d) => String(d.idServicio) === String(dispatchId));
      if (!disp) return;
      if (hasUserRespondedToDispatch(disp, user)) return;

      const soundKey = resolveSoundKeyForClave(clave || disp.clave);
      get().playDispatchSound(soundKey, {
        forceMaxVolume: true,
        vibratePatternCsv: '0,1200,400,1200,400,1200,400,1200'
      });
    }, 60000);
  },

  scheduleRepeatUnitRequest: (dispatchId, unitId, is1210, clave) => {
    if (!dispatchId || !unitId) return;
    const timerKey = `${dispatchId}_${unitId}_${is1210 ? '1210' : '66'}`;
    if (repeatTimersByKey[timerKey]) {
      clearTimeout(repeatTimersByKey[timerKey]);
      delete repeatTimersByKey[timerKey];
    }
    repeatTimersByKey[timerKey] = setTimeout(() => {
      delete repeatTimersByKey[timerKey];
      const { isAirplaneMode, user } = useAuthStore.getState();
      if (isAirplaneMode || get().isCentralActive || !user) return;
      if (String(user.estado || '0-9').trim() !== '0-9') return;
      if (is1210 && !user.conductor) return;

      const disp = (get().dispatchesList || []).find((d) => String(d.idServicio) === String(dispatchId));
      if (!disp || isUserAttendingDispatch(disp, user)) return;
      const uObj = disp.unidades?.[unitId];
      if (!uObj) return;
      const uEstado = String(uObj.estado || '').trim();
      if (uEstado === '6-9' || uEstado === '6-10') return;

      if (is1210) {
        const cond = String(uObj.conductor || '').trim();
        if (!cond || cond === 'Sin Conductor') {
          const soundKey = resolveSoundKeyForClave(disp.clave);
          get().playDispatchSound(soundKey, {
            forceMaxVolume: true,
            vibratePatternCsv: '0,1200,400,1200'
          });
        }
      } else {
        const numB = Number(uObj.numBomberos || 0);
        if (numB === 0) {
          const soundKey = resolveSoundKeyForClave(disp.clave);
          get().playDispatchSound(soundKey, {
            forceMaxVolume: true,
            vibratePatternCsv: '0,1200,400,1200'
          });
        }
      }
    }, 60000);
  },

  recomputeCentralState: (rawData = lastCentralRawData) => {
    const data = rawData && typeof rawData === 'object' ? rawData : {};
    const estadoCentral = String(data.estado || '').toLowerCase().trim();
    const isClosed = !estadoCentral || estadoCentral === 'cerrado' || estadoCentral === 'inactivo';
    const opName = isClosed ? '' : String(data.operador || data.nombreBombero || '').trim();
    const opId = isClosed ? '' : String(data.idRegistro || '').trim();
    const sessionId = String(data.idAcceso || data.idInicio || '').trim();

    const currentUser = useAuthStore.getState().user;
    const myReg = String(currentUser?.idRegistro || '').trim();
    const myRad = String(currentUser?.idRadial || '').trim();
    const myNameNorm = normalizeComparableName(currentUser?.nombreBombero || currentUser?.nombre);
    const opNameNorm = normalizeComparableName(opName);

    const isMeCentral = Boolean(
      !isClosed &&
      (opName || opId) &&
      opName.toUpperCase() !== 'SIN OPERADOR' &&
      ((opId && (opId === myReg || opId === myRad)) ||
       (myNameNorm && opNameNorm && opNameNorm !== 'SIN OPERADOR' && (myNameNorm === opNameNorm || shortOperatorName(myNameNorm) === shortOperatorName(opNameNorm))))
    );

    const prevState = get();
    const shouldClearFullscreen = isMeCentral && prevState.fullscreenDispatchId !== null;
    if (
      prevState.centralOperatorName !== opName ||
      prevState.centralOperatorId !== opId ||
      prevState.centralSessionId !== sessionId ||
      prevState.isCentralActive !== isMeCentral ||
      shouldClearFullscreen
    ) {
      set({
        centralOperatorName: opName,
        centralOperatorId: opId,
        centralSessionId: sessionId,
        isCentralActive: isMeCentral,
        ...(isMeCentral ? { fullscreenDispatchId: null } : {})
      });
    }

    if (isMeCentral) {
      get().stopDispatchSound();
    }

    try {
      if (typeof window !== 'undefined') {
        if (window.AndroidApp?.setCentralOperatorMode) {
          window.AndroidApp.setCentralOperatorMode(isMeCentral);
        }
        if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
          window.webkit.messageHandlers.iOSApp.postMessage({
            action: 'setCentralOperatorMode',
            active: isMeCentral
          });
        }
      }
    } catch (_) {}

    return isMeCentral;
  },

  // Inicializar o cargar despacho
  init: () => {
    if (!nativeEventsBound && typeof window !== 'undefined') {
      nativeEventsBound = true;
      window.addEventListener('sentinelStopSound', () => {
        get().stopDispatchSound();
      });
      const handleNativeIntentPayload = (payload) => {
        if (!payload || typeof payload !== 'object') return;
        if (get().isCentralActive) {
          try {
            window.AndroidApp?.clearPendingIntent?.();
            window.__SENTINEL_PENDING_INTENT__ = null;
          } catch (_) {}
          return;
        }
        const dispId = String(payload.dispatchId || '').trim();
        if (dispId) {
          const user = useAuthStore.getState().user;
          const matching = (get().dispatchesList || []).find((d) => String(d.idServicio) === dispId);
          if (!matching || !hasUserRespondedToDispatch(matching, user)) {
            set({ fullscreenDispatchId: dispId });
          }
        }
        try {
          window.AndroidApp?.clearPendingIntent?.();
          window.__SENTINEL_PENDING_INTENT__ = null;
        } catch (_) {}
      };
      window.addEventListener('sentinelNativeIntent', (ev) => {
        handleNativeIntentPayload(ev?.detail);
      });
      const handleNativeFirestoreSnapshot = (msg) => {
        if (!msg || typeof msg !== 'object') return;
        lastSnapshotReceivedTs = Date.now();
        const col = String(msg.collection || '').trim();
        const payload = msg.payload;
        if (col === 'despachos' && Array.isArray(payload)) {
          get().applyDispatchesRawDocs(payload);
        } else if (col === 'central' && payload && typeof payload === 'object') {
          get().applyCentralRawDoc(payload);
        } else if (col === 'vehiculos' && Array.isArray(payload)) {
          get().applyVehiclesRawDocs(payload);
        } else if (col === 'alertas' && Array.isArray(payload)) {
          get().applyAlertsRawDocs(payload);
        } else if (col === 'personal' && Array.isArray(payload)) {
          get().applyPersonalRawDocs(payload);
        } else if (col === 'personalSelf' && payload && typeof payload === 'object') {
          useAuthStore.getState().syncUserFromPersonalList([payload]);
        }
      };
      window.addEventListener('sentinelNativeFirestoreSync', (ev) => {
        handleNativeFirestoreSnapshot(ev?.detail);
      });
      try {
        const cachedSnaps = window.__SENTINEL_NATIVE_SNAPSHOTS__;
        if (cachedSnaps && typeof cachedSnaps === 'object') {
          Object.keys(cachedSnaps).forEach((colKey) => {
            handleNativeFirestoreSnapshot({ collection: colKey, payload: cachedSnaps[colKey] });
          });
        }
      } catch (_) {}
      try {
        if (window.__SENTINEL_PENDING_INTENT__) {
          handleNativeIntentPayload(window.__SENTINEL_PENDING_INTENT__);
        } else if (window.AndroidApp?.getPendingIntentJson) {
          const rawJson = window.AndroidApp.getPendingIntentJson();
          if (rawJson) {
            handleNativeIntentPayload(JSON.parse(rawJson));
          }
        }
      } catch (_) {}

      // Reconexión automática en iOS WKWebView y Android únicamente tras caída real de listeners, cambio a online o retorno >= 30s desde segundo plano
      const handleAppWakeOrNetworkResume = (reason, bgDurationMs = 0) => {
        const missingListeners = !unsubDespachos || !unsubCentral || !unsubPersonal || !unsubVehiculos || !unsubAlertas;
        if (
          missingListeners ||
          reason === 'online' ||
          bgDurationMs >= 30000
        ) {
          get().reconnectRealtimeConnection(reason);
        } else if (bgDurationMs >= 5000) {
          ensureFirebaseAuth(null, null, null, false).catch(() => {});
        }
      };

      if (typeof document !== 'undefined' && document.addEventListener) {
        document.addEventListener('visibilitychange', () => {
          if (document.visibilityState === 'hidden') {
            lastAppHiddenTs = Date.now();
          } else if (document.visibilityState === 'visible') {
            const bgMs = lastAppHiddenTs > 0 ? (Date.now() - lastAppHiddenTs) : 0;
            lastAppHiddenTs = 0;
            handleAppWakeOrNetworkResume('visibilitychange', bgMs);
          }
        });
      }

      window.addEventListener('pageshow', (ev) => {
        const bgMs = lastAppHiddenTs > 0 ? (Date.now() - lastAppHiddenTs) : (ev?.persisted ? 30000 : 0);
        handleAppWakeOrNetworkResume('pageshow', bgMs);
      });

      window.addEventListener('focus', () => {
        const bgMs = lastAppHiddenTs > 0 ? (Date.now() - lastAppHiddenTs) : 0;
        if (bgMs >= 30000) {
          handleAppWakeOrNetworkResume('focus', bgMs);
        }
      });

      window.addEventListener('online', () => {
        handleAppWakeOrNetworkResume('online', 30000);
      });

      window.addEventListener('sentinelAppResume', (ev) => {
        const bgSec = Number(ev?.detail?.backgroundSeconds || 0);
        handleAppWakeOrNetworkResume('native_resume', bgSec * 1000);
      });

      // Watchdog ligero en primer plano (cada 60s): solo verifica que sigan montados los listeners SDK (cero polling REST ni por silencio normal)
      if (realtimeWatchdogIntervalId) {
        clearInterval(realtimeWatchdogIntervalId);
      }
      realtimeWatchdogIntervalId = setInterval(() => {
        try {
          if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
          const fbConfig = useLicenseStore.getState().firebaseConfig;
          if (!fbConfig || !fbConfig.projectId) return;
          const missingCoreListeners = !unsubDespachos || !unsubCentral || !unsubPersonal || !unsubVehiculos || !unsubAlertas;
          if (missingCoreListeners) {
            oneReconnectScheduler.schedule(() => {
              get().subscribeRealtime(false);
            });
          }
        } catch (_) {}
      }, 60000);
    }
    get().subscribeRealtime();
  },

  // Procesadores compartidos entre onSnapshot y REST Hydration (Ley #4: "Mayor Timestamp Gana")
  applyDispatchesRawDocs: (rawDocsArray = []) => {
    const currentDispatches = get().dispatchesList || [];
    const existingById = new Map();
    currentDispatches.forEach((d) => {
      if (d?.idServicio) existingById.set(String(d.idServicio).trim(), d);
    });

    const activeList = [];
    const closedServerIds = new Set();

    rawDocsArray.forEach(({ id, data, hasPendingWrites }) => {
      const rawData = data || {};
      const norm = normalizeDispatchDoc(id, rawData);
      const docId = String(rawData.idServicio || rawData.id || norm.idServicio || id).trim();
      const incomingNorm = {
        ...rawData,
        ...norm,
        id: docId,
        idServicio: docId,
        estado: String(rawData.estado || norm.estado || 'activa').trim(),
        unidades: (rawData.unidades && typeof rawData.unidades === 'object') ? rawData.unidades : (norm.unidades || {}),
        geo: rawData.geo || norm.geo || (rawData.coords ? { lat: Number(rawData.coords.lat), lng: Number(rawData.coords.lng) } : null),
        visibleMovil: rawData.visibleMovil !== false
      };
      const existing = existingById.get(docId);

      // Ley #4: Fusionar unidades y campos garantizando que NUNCA un snapshot atrasado pise un timestamp mayor
      const mergedItem = existing
        ? (shouldKeepLocalByTimestamp('despachos', docId, existing, incomingNorm) || hasPendingWrites
            ? mergeDispatchEntitiesByHigherTimestamp(existing, incomingNorm)
            : mergeDispatchEntitiesByHigherTimestamp(existing, incomingNorm))
        : incomingNorm;

      const st = String(mergedItem.estado || '').toLowerCase().trim();
      const rawOpFinal = String(mergedItem.operadorFinal || '').trim();
      const opFinal = (rawOpFinal === 'null' || rawOpFinal === '<null>') ? '' : rawOpFinal;
      const isClosed =
        st === 'cancelada' ||
        st === 'cancelado' ||
        st === 'finalizada' ||
        st === 'finalizado' ||
        st === 'cerrada' ||
        st === 'cerrado' ||
        st === 'terminada' ||
        st === 'terminado' ||
        Boolean(opFinal) ||
        Number(mergedItem.finalizadoAt) > 0;

      if (isClosed) {
        closedServerIds.add(docId);
        return;
      }

      if (!isClosed && mergedItem.visibleMovil !== false) {
        activeList.push(mergedItem);
      }
    });

    // Preservar despachos locales activos que aún no hayan sido cerrados explícitamente por el servidor
    currentDispatches.forEach((localDisp) => {
      const sId = String(localDisp?.idServicio || '').trim();
      if (
        sId &&
        !closedServerIds.has(sId) &&
        shouldKeepLocalByTimestamp('despachos', sId, localDisp, null) &&
        !activeList.some((a) => String(a.idServicio).trim() === sId)
      ) {
        activeList.unshift(localDisp);
      }
    });

    activeList.sort((a, b) => {
      const tsA = a.createdAt || 0;
      const tsB = b.createdAt || 0;
      if (tsA !== tsB) return tsB - tsA;
      return String(b.idServicio).localeCompare(String(a.idServicio));
    });

    const isAirplane = useAuthStore.getState().isAirplaneMode;
    const currentUser = useAuthStore.getState().user;
    const isMeCentral = get().isCentralActive;
    const userEstado = String(currentUser?.estado || '0-8').trim();
    const is09 = userEstado === '0-9';
    const isHonorario = String(currentUser?.cargo || '').toUpperCase().trim() === 'BOMBERO HONORARIO';
    const isSilentSpecial =
      ['CDS', 'SUSPENDIDO', 'LICENCIA', 'SILENCIO_ABSOLUTO'].includes(userEstado.toUpperCase()) ||
      Number(currentUser?.cds) === 1 ||
      Boolean(currentUser?.licenciaMedica) ||
      Boolean(currentUser?.fechaSuspensionFin);
    const isPartialSpecial =
      ['PERMISO', 'COMISION_SERVICIO'].includes(userEstado.toUpperCase()) ||
      Number(currentUser?.permiso) > 0;

    if (activeList.length > 0) {
      const newest = activeList[0];

      // REGLA V2: En la carga inicial al abrir la app NUNCA hacer sonar ni levantar tonos desde los listeners JS.
      // Todos los tonos y notificaciones son gestionados exclusivamente por la capa Push / Servicio Nativo.
      if (isFirstDispatchesSnapshot) {
        isFirstDispatchesSnapshot = false;
        activeList.forEach((disp) => {
          notifiedDispatchIds.add(disp.idServicio);
          lastDispatchClaves[disp.idServicio] = disp.clave || '';
          markPlayedSoundKey(disp.idServicio);
          Object.entries(disp.unidades || {}).forEach(([uId, uData]) => {
            lastUnitConductorReq[`${disp.idServicio}_${uId}`] = Number(uData?.solicitudConductorTimestamp || 0);
            lastUnitPersonalReq[`${disp.idServicio}_${uId}`] = Number(uData?.solicitudPersonalTimestamp || 0);
          });
        });
        lastKnownDispatchId = newest.idServicio;

        // Si al abrir la app hay un despacho o pre-despacho activo reciente sin responder y está en 0-9, mostrar la pantalla del despacho en silencio (sin sonar)
        if (
          !isAirplane &&
          !isMeCentral &&
          !isHonorario &&
          !isSilentSpecial &&
          !isPartialSpecial &&
          is09 &&
          !isDispatchTooOld(newest) &&
          !hasUserRespondedToDispatch(newest, currentUser)
        ) {
          set({ fullscreenDispatchId: newest.idServicio });
        }
      } else {
        // Procesar cambios en vivo únicamente para actualizar estado UI (los tonos suenan exclusivamente por Push / Servicio Nativo)
        activeList.forEach((disp) => {
          const id = disp.idServicio;
          const clave = String(disp.clave || '10-0').trim();

          // A. Nuevo despacho o pre-despacho en vivo: mostrar overlay de inmediato si el usuario está en 0-9 y no en Modo Avión (0-8 Absoluto)
          // Si es un despacho SCI (idIncidenteSci) y el dispositivo es del Comandante (`idRadial == "1"` o `modoAdmin: true`) o del Operador en turno (isMeCentral), NO abrir pantalla completa ni sonar en su dispositivo
          const isSciCommanderSilent = Boolean(disp.idIncidenteSci && hasCommanderPrivileges(currentUser));
          if (!notifiedDispatchIds.has(id)) {
            notifiedDispatchIds.add(id);
            lastDispatchClaves[id] = clave;

            if (
              !isAirplane &&
              !isMeCentral &&
              !isSciCommanderSilent &&
              !isHonorario &&
              !isSilentSpecial &&
              !isPartialSpecial &&
              !hasUserRespondedToDispatch(disp, currentUser)
            ) {
              if (isDispatchTooOld(disp)) {
                set({
                  urgentBannerMessage: '⚠️ NOTIFICACIÓN TARDÍA POR MALA COBERTURA TELEFÓNICA'
                });
              } else if (is09) {
                set({ fullscreenDispatchId: id });
              }
            }
          } else {
            // B. Detectar escalamiento (10-0 -> 10-30, 10-2 -> ALARMA FORESTAL)
            const oldClave = lastDispatchClaves[id] || '';
            if (oldClave && oldClave !== clave) {
              lastDispatchClaves[id] = clave;
              const cUp = clave.toUpperCase();
              const isEscalation =
                cUp.includes('10-30') ||
                cUp.includes('FORESTAL') ||
                cUp.includes('9-0') ||
                cUp.includes('COMANDANCIA');

              if (
                isEscalation &&
                !isAirplane &&
                !isMeCentral &&
                !isSciCommanderSilent &&
                !isHonorario &&
                !isSilentSpecial &&
                !isUserAttendingDispatch(disp, currentUser)
              ) {
                set({
                  fullscreenDispatchId: id,
                  urgentBannerMessage: `🔥 CAMBIO DE CLAVE A ${clave}: ¡SE REQUIERE PERSONAL!`
                });
              }
            } else {
              lastDispatchClaves[id] = clave;
            }
          }

          // C. Detectar solicitudes urgentes 12-10 (Conductor) y 6-6 (Personal) por unidad (solo banner UI)
          if (
            !isAirplane &&
            !isMeCentral &&
            !isSciCommanderSilent &&
            !isHonorario &&
            !isSilentSpecial &&
            !isUserAttendingDispatch(disp, currentUser)
          ) {
            Object.entries(disp.unidades || {}).forEach(([uId, uData]) => {
              const condTs = Number(uData?.solicitudConductorTimestamp || 0);
              const persTs = Number(uData?.solicitudPersonalTimestamp || 0);
              const mapKey = `${id}_${uId}`;

              const prevCondTs = lastUnitConductorReq[mapKey] || 0;
              if (condTs > prevCondTs && condTs > 0 && (Date.now() - condTs) < 120000) {
                lastUnitConductorReq[mapKey] = condTs;
                if (currentUser?.conductor) {
                  if (is09) set({ fullscreenDispatchId: id });
                  set({
                    urgentBannerMessage: `🚨 SOLICITUD 12-10 (${uId}): Se solicita conductor para unidad ${uId}`
                  });
                }
              } else if (prevCondTs === 0) {
                lastUnitConductorReq[mapKey] = condTs;
              }

              const prevPersTs = lastUnitPersonalReq[mapKey] || 0;
              if (persTs > prevPersTs && persTs > 0 && (Date.now() - persTs) < 120000) {
                lastUnitPersonalReq[mapKey] = persTs;
                if (is09) set({ fullscreenDispatchId: id });
                set({
                  urgentBannerMessage: `🚨 SOLICITUD 6-6 (${uId}): Se solicita personal para unidad ${uId}`
                });
              } else if (prevPersTs === 0) {
                lastUnitPersonalReq[mapKey] = persTs;
              }
            });
          }
        });

        lastKnownDispatchId = newest.idServicio;
      }

      // Si el usuario es el Operador Central activo o el despacho en pantalla completa ya fue respondido o cerrado, cerrarlo automáticamente
      const curFsId = get().fullscreenDispatchId;
      if (curFsId) {
        if (isMeCentral) {
          set({ fullscreenDispatchId: null });
          get().stopDispatchSound();
        } else {
          const fsDisp = activeList.find((d) => String(d.idServicio) === String(curFsId));
          if (!fsDisp || hasUserRespondedToDispatch(fsDisp, currentUser)) {
            set({ fullscreenDispatchId: null });
          }
        }
      }

      persistActiveDispatchesToLocalStorage(activeList);

      set({
        activeDispatch: newest,
        dispatchesList: activeList,
        isLoading: false,
        isLiveConnected: true
      });
    } else {
      isFirstDispatchesSnapshot = false;
      persistActiveDispatchesToLocalStorage([]);
      set({
        activeDispatch: null,
        dispatchesList: [],
        fullscreenDispatchId: null,
        isLoading: false,
        isLiveConnected: true
      });
      // Cuando no existe ningún servicio activo en curso, limpiar registros residuales en RTDB
      try {
        const rtdb = getRtdb();
        if (rtdb) {
          rtdbRemove(ref(rtdb, 'telemetria/bomberos')).catch(() => {});
          rtdbRemove(ref(rtdb, 'telemetria/alertantes')).catch(() => {});
        }
      } catch (_) {}
    }

    // Si el bombero tenía `enServicio` asociado a un despacho que ya se cerró, liberar a "0" (Idéntico a SisBomViewModel.kt líneas 977-989)
    // Regla estricta GPS: El GPS NO puede funcionar si el bombero está 0-8, o enServicio: "0", o con enServicio: "-X"
    const latestUser = useAuthStore.getState().user;
    const latestEstado = String(latestUser?.estado || '').trim().toUpperCase();
    const rawSvc = String(latestUser?.enServicio || '0').trim();
    if (rawSvc && rawSvc !== '0') {
      const cleanSvcId = rawSvc.replace(/^-/, '').trim();
      const stillActive = activeList.some((d) => String(d.idServicio).trim() === cleanSvcId);
      if (!stillActive && cleanSvcId) {
        useAuthStore.getState().setEmergencyResponse(cleanSvcId, 'LIBRE');
      } else if (stillActive && !rawSvc.startsWith('-') && latestEstado === '0-9') {
        useAuthStore.getState().startLiveTelemetry(cleanSvcId);
      } else {
        useAuthStore.getState().stopLiveTelemetry();
      }
    } else {
      useAuthStore.getState().stopLiveTelemetry();
    }
  },

  applyPersonalRawDocs: (rawDocsArray = []) => {
    const existingList = get().firefightersList || [];
    const existingById = new Map();
    existingList.forEach((p) => {
      if (p?.idRegistro) existingById.set(String(p.idRegistro).trim(), p);
    });

    const list = [];
    rawDocsArray.forEach(({ id, data, hasPendingWrites }) => {
      const rawData = data || {};
      const docId = String(id || rawData.idRegistro || '').trim();
      const existing = existingById.get(docId);

      const rawActivoField = rawData.activo ?? rawData.ACTIVO ?? rawData.Activo;
      const rawActivoStr = String(rawActivoField ?? '').trim().toUpperCase();
      const isInactiveRecord =
        rawActivoField !== undefined &&
        rawActivoField !== null &&
        rawActivoStr !== '' &&
        (rawActivoField === false ||
          rawActivoField === 0 ||
          rawActivoStr === '0' ||
          rawActivoStr === 'NO' ||
          rawActivoStr === 'FALSE' ||
          rawActivoStr === 'FALSO' ||
          rawActivoStr === 'INACTIVO');
      const isActiveVal = !isInactiveRecord;
      const isConductorVal = rawData.conductor === true || rawData.conductor === 1 || String(rawData.conductor) === '1' || String(rawData.conductor).toUpperCase() === 'SI';
      const hasPuertaVal =
        rawData.puerta === true ||
        rawData.Puerta === true ||
        rawData.puerta === 1 ||
        String(rawData.puerta ?? rawData.Puerta ?? '').trim().toLowerCase() === 'true' ||
        String(rawData.puerta ?? rawData.Puerta ?? '').trim().toUpperCase() === 'SI' ||
        String(rawData.puerta ?? rawData.Puerta ?? '').trim() === '1';
      const hasModoAdminVal =
        (rawData.modoAdmin !== undefined || rawData.ModoAdmin !== undefined || rawData.modoadmin !== undefined)
          ? hasAdminModeFlag(rawData)
          : Boolean(existing?.modoAdmin);

      const incomingItem = {
        idRegistro: docId,
        idRadial: String(rawData.idRadial || '').trim(),
        nombre: rawData.nombreBombero || rawData.nombre || 'BOMBERO',
        nombreBombero: rawData.nombreBombero || rawData.nombre || 'BOMBERO',
        cargo: rawData.cargo || 'Voluntario',
        estado: rawData.estado || '0-9',
        conductor: isConductorVal,
        activo: isActiveVal,
        puerta: hasPuertaVal,
        modoAdmin: hasModoAdminVal,
        compania: parseInt(String(rawData.compania || '1').replace(/\D/g, ''), 10) || 1,
        telefono: rawData.telefono || '',
        foto: rawData.foto || '',
        enServicio: String(rawData.enServicio ?? '0'),
        contrasena: rawData.contrasena !== undefined ? String(rawData.contrasena) : '',
        licenciaMedica: rawData.licenciaMedica || '',
        fechaSuspensionFin: rawData.fechaSuspensionFin || '',
        permiso: rawData.permiso ?? 0,
        cds: rawData.cds ?? 0,
        _metaTimestamp: Number(rawData._metaTimestamp || rawData.updatedAt || 0) || 0,
        _fieldTimestamps: rawData._fieldTimestamps && typeof rawData._fieldTimestamps === 'object' ? rawData._fieldTimestamps : {}
      };

      const authUserNow = useAuthStore.getState().user;
      const isMyDoc = authUserNow && String(authUserNow.idRegistro || '').trim() === docId;
      const localCandidate =
        isMyDoc && getEntityMaxTimestamp(authUserNow) >= getEntityMaxTimestamp(existing)
          ? { ...(existing || {}), ...authUserNow }
          : existing;

      // Ley #4: Si el registro local en memoria tiene un timestamp mayor al entrante, conservar el local
      if (localCandidate && (shouldKeepLocalByTimestamp('personal', docId, localCandidate, incomingItem) || hasPendingWrites)) {
        list.push({
          ...incomingItem,
          ...localCandidate,
          modoAdmin:
            (rawData.modoAdmin !== undefined || rawData.ModoAdmin !== undefined || rawData.modoadmin !== undefined)
              ? hasModoAdminVal
              : Boolean(localCandidate.modoAdmin),
          foto: incomingItem.foto || localCandidate.foto || '',
          _metaTimestamp: Math.max(getEntityMaxTimestamp(localCandidate), getEntityMaxTimestamp(incomingItem))
        });
      } else {
        list.push(incomingItem);
      }
    });

    list.sort((a, b) => {
      const numA = parseInt(String(a.idRadial).replace(/\D/g, ''), 10) || 9999;
      const numB = parseInt(String(b.idRadial).replace(/\D/g, ''), 10) || 9999;
      return numA - numB;
    });

    set({ firefightersList: list });
    useAuthStore.getState().syncUserFromPersonalList(list);
    get().recomputeCentralState();
    get().recomputeSciState();
    const syncedUser = useAuthStore.getState().user;
    const syncedId = String(syncedUser?.idRegistro || '').trim();
    if (syncedId && syncedId !== lastSubscribedAttendanceUserId) {
      get().subscribeAttendanceRealtime();
    }
    const syncedSvc = String(syncedUser?.enServicio || '0').trim();
    const syncedEst = String(syncedUser?.estado || '').trim().toUpperCase();
    if (syncedEst === '0-9' && syncedSvc && syncedSvc !== '0' && !syncedSvc.startsWith('-')) {
      const activeListNow = get().dispatchesList || [];
      if (activeListNow.some((d) => String(d.idServicio).trim() === syncedSvc)) {
        useAuthStore.getState().startLiveTelemetry(syncedSvc);
      } else {
        useAuthStore.getState().stopLiveTelemetry();
      }
    } else {
      useAuthStore.getState().stopLiveTelemetry();
    }
  },

  // Actualización optimista inmediata (0ms) en memoria al cambiar el estado de cualquier bombero (propio o desde Disponibles)
  optimisticUpdateFirefighterStatus: (docId, newEstado, extraFields = {}, explicitTs = null) => {
    const cleanId = String(docId || '').trim();
    if (!cleanId) return 0;
    const cleanEstado = String(newEstado || '0-8').trim().toUpperCase();
    const nowTs = markLocalWrite('personal', cleanId, explicitTs || Date.now());
    const currentList = get().firefightersList || [];
    let didChange = false;
    const nextList = currentList.map((f) => {
      if (String(f?.idRegistro || '').trim() !== cleanId) return f;
      didChange = true;
      const nextFieldTs = {
        ...(f._fieldTimestamps || {}),
        estado: nowTs
      };
      if (extraFields && extraFields.enServicio !== undefined) {
        nextFieldTs.enServicio = nowTs;
      }
      return {
        ...f,
        ...extraFields,
        estado: cleanEstado,
        _metaTimestamp: nowTs,
        _fieldTimestamps: nextFieldTs
      };
    });
    if (didChange) {
      set({ firefightersList: nextList });
    }
    return nowTs;
  },

  applyVehiclesRawDocs: (rawDocsArray = []) => {
    const existingList = get().vehiclesList || [];
    const existingById = new Map();
    existingList.forEach((v) => {
      if (v?.idCarro) existingById.set(String(v.idCarro).trim().toUpperCase(), v);
    });

    const vList = [];
    rawDocsArray.forEach(({ id, data, hasPendingWrites }) => {
      const rawData = data || {};
      const idCarro = String(rawData.idCarro || id).trim().toUpperCase();
      const existing = existingById.get(idCarro);
      const incomingVeh = {
        id: idCarro,
        nombre: idCarro,
        idCarro,
        compania: resolveCompanyFromVehicleId(idCarro, rawData.compania),
        tipo: String(
          rawData.tipo ||
          (idCarro.startsWith('BF')
            ? 'FORESTAL'
            : idCarro.startsWith('BT')
              ? 'CISTERNA'
              : idCarro.startsWith('RS')
                ? 'RESCATE PESADO'
                : idCarro.startsWith('R')
                  ? 'RESCATE'
                  : idCarro.startsWith('K')
                    ? 'COMANDANCIA'
                    : 'BOMBA')
        ).toUpperCase(),
        conductor: String(rawData.conductor || ''),
        estado: String(rawData.estado ?? '0-9').trim(),
        enServicio: String(rawData.enServicio ?? '0').trim(),
        patente: String(rawData.patente || ''),
        kmActual: Number(rawData.kmActual || 0),
        _metaTimestamp: Number(rawData._metaTimestamp || rawData.updatedAt || 0) || 0,
        _fieldTimestamps: rawData._fieldTimestamps && typeof rawData._fieldTimestamps === 'object' ? rawData._fieldTimestamps : {}
      };

      if (existing && (shouldKeepLocalByTimestamp('vehiculos', idCarro, existing, incomingVeh) || hasPendingWrites)) {
        vList.push({
          ...incomingVeh,
          ...existing,
          _metaTimestamp: Math.max(getEntityMaxTimestamp(existing), getEntityMaxTimestamp(incomingVeh))
        });
      } else {
        vList.push(incomingVeh);
      }
    });

    vList.sort((a, b) => a.idCarro.localeCompare(b.idCarro));
    set({ vehiclesList: vList, vehiculosList: vList });
  },

  applyAlertsRawDocs: (rawDocsArray = []) => {
    const existingAlerts = get().allAlertsList || [];
    const existingById = new Map();
    existingAlerts.forEach((a) => {
      if (a?.idAlerta) existingById.set(String(a.idAlerta).trim(), a);
    });

    const aList = [];
    rawDocsArray.forEach(({ id, data, hasPendingWrites }) => {
      const incomingNorm = normalizeAlertDoc(id, data || {});
      if (isResidualSciAlertDoc(incomingNorm)) {
        // Separación total: ninguna solicitud o chat de SCI se mezcla en `alertas`
        return;
      }
      const docId = String(incomingNorm.idAlerta || id).trim();
      const existing = existingById.get(docId);

      // Ley #4: Mayor Timestamp Gana en alertas y canales de chat
      const finalAlert = (existing && (shouldKeepLocalByTimestamp('alertas', docId, existing, incomingNorm) || hasPendingWrites))
        ? { ...incomingNorm, ...existing, _metaTimestamp: Math.max(getEntityMaxTimestamp(existing), getEntityMaxTimestamp(incomingNorm)) }
        : incomingNorm;

      if (isAlertActiveByDuration(finalAlert)) {
        aList.push(finalAlert);
        if (!knownAlertIds.has(finalAlert.idAlerta)) {
          knownAlertIds.add(finalAlert.idAlerta);
        }
      }
    });

    isFirstAlertsSnapshot = false;

    aList.sort((a, b) => {
      const numA = parseInt(String(a.idAlerta).replace(/\D/g, ''), 10) || 0;
      const numB = parseInt(String(b.idAlerta).replace(/\D/g, ''), 10) || 0;
      if (numB !== numA) return numB - numA;
      return String(b.idAlerta).localeCompare(String(a.idAlerta));
    });

    set(splitAlertsAndOrders(aList, get().activeChatAlert));
  },

  applyCentralRawDoc: (rawData = {}) => {
    const incomingData = rawData && typeof rawData === 'object' ? rawData : {};
    if (lastCentralRawData && shouldKeepLocalByTimestamp('accesos', 'central', lastCentralRawData, incomingData)) {
      get().recomputeCentralState(lastCentralRawData);
      return;
    }
    lastCentralRawData = incomingData;
    get().recomputeCentralState(incomingData);
    isFirstCentralSnapshot = false;
  },

  // Hidratación inicial por Firestore REST (solo 1 vez al abrir la app / iniciar sesión, con cooldown mínimo estricto)
  hydrateFromFirestoreRest: async (force = false) => {
    try {
      const nowTs = Date.now();
      const hasLoadedInitialData = (get().firefightersList || []).length > 0;
      const effectiveCooldownMs = force ? 60000 : REST_HYDRATION_COOLDOWN_MS;
      if (hasLoadedInitialData && lastRestHydrationTs > 0 && (nowTs - lastRestHydrationTs) < effectiveCooldownMs) {
        return;
      }
      const fbConfig = useLicenseStore.getState().firebaseConfig;
      if (!fbConfig || !fbConfig.projectId || !fbConfig.apiKey) return;
      lastRestHydrationTs = nowTs;
      const projId = fbConfig.projectId;
      const apiKey = fbConfig.apiKey;

      const baseUrl = `https://firestore.googleapis.com/v1/projects/${projId}/databases/(default)/documents`;

      const parseRestDocFields = (docObj) => {
        const fields = docObj?.fields || {};
        const parsed = {};
        for (const k of Object.keys(fields)) {
          parsed[k] = parseFirestoreValue(fields[k]);
        }
        return parsed;
      };

      const fetchDespachosRest = async () => {
        try {
          const queryUrl = `https://firestore.googleapis.com/v1/projects/${projId}/databases/(default)/documents:runQuery`;
          const res = await fetchFirestoreWithAuth(queryUrl, apiKey, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              structuredQuery: {
                from: [{ collectionId: 'despachos' }],
                where: {
                  fieldFilter: {
                    field: { fieldPath: 'estado' },
                    op: 'IN',
                    value: {
                      arrayValue: {
                        values: [
                          { stringValue: 'activa' },
                          { stringValue: 'ACTIVA' },
                          { stringValue: 'activo' },
                          { stringValue: 'ACTIVO' },
                          { stringValue: 'pre-despacho' },
                          { stringValue: 'PRE-DESPACHO' },
                          { stringValue: 'en curso' },
                          { stringValue: 'EN CURSO' }
                        ]
                      }
                    }
                  }
                }
              }
            })
          }).catch(() => null);
          if (res && res.ok) {
            const rows = await res.json();
            if (Array.isArray(rows)) {
              return rows
                .filter((r) => r && r.document && r.document.name)
                .map((r) => ({
                  id: r.document.name.split('/').pop(),
                  data: parseRestDocFields(r.document),
                  hasPendingWrites: false
                }));
            }
          }
        } catch (_) {}
        return null;
      };

      const [centralRes, despDocs, vehRes, alertRes] = await Promise.all([
        fetchFirestoreWithAuth(`${baseUrl}/accesos/central`, apiKey).catch(() => null),
        fetchDespachosRest(),
        fetchFirestoreWithAuth(`${baseUrl}/vehiculos?pageSize=100`, apiKey).catch(() => null),
        fetchFirestoreWithAuth(`${baseUrl}/alertas?pageSize=100`, apiKey).catch(() => null)
      ]);

      if (centralRes && centralRes.ok) {
        const cJson = await centralRes.json();
        if (cJson && cJson.fields) {
          get().applyCentralRawDoc(parseRestDocFields(cJson));
        }
      }

      if (Array.isArray(despDocs)) {
        get().applyDispatchesRawDocs(despDocs);
      }

      if (vehRes && vehRes.ok) {
        const vJson = await vehRes.json();
        const docs = (vJson?.documents || []).map((docItem) => ({
          id: docItem.name.split('/').pop(),
          data: parseRestDocFields(docItem),
          hasPendingWrites: false
        }));
        if (docs.length > 0) {
          get().applyVehiclesRawDocs(docs);
        }
      }

      if (alertRes && alertRes.ok) {
        const aJson = await alertRes.json();
        const docs = (aJson?.documents || []).map((docItem) => ({
          id: docItem.name.split('/').pop(),
          data: parseRestDocFields(docItem),
          hasPendingWrites: false
        }));
        get().applyAlertsRawDocs(docs);
      }

      // Si aún no se ha cargado la lista inicial de personal, hidratar resumen ligero por REST una sola vez (luego unsubPersonal transmite deltas)
      if ((get().firefightersList || []).length === 0) {
        const maskFields = [
          'idRegistro', 'idRadial', 'nombreBombero', 'nombre', 'cargo',
          'compania', 'estado', 'enServicio', 'conductor', 'activo', 'ACTIVO', 'Activo',
          'puerta', 'Puerta', 'modoAdmin', 'ModoAdmin', 'telefono', 'licenciaMedica',
          'fechaSuspensionFin', 'permiso', 'cds', '_metaTimestamp', '_fieldTimestamps'
        ].map((f) => `mask.fieldPaths=${f}`).join('&');
        const persRes = await fetchFirestoreWithAuth(`${baseUrl}/personal?pageSize=300&${maskFields}`, apiKey).catch(() => null);
        if (persRes && persRes.ok) {
          const pJson = await persRes.json();
          const pDocs = (pJson?.documents || []).map((docItem) => ({
            id: docItem.name.split('/').pop(),
            data: parseRestDocFields(docItem),
            hasPendingWrites: false
          }));
          if (pDocs.length > 0) {
            get().applyPersonalRawDocs(pDocs);
          }
        }
      }
    } catch (err) {
      console.warn('⚠️ [SENTINEL ONE] Advertencia en hydrateFromFirestoreRest:', err?.message || err);
    }
  },

  // Reconexión limpia de canales Long-Polling + refresco de token + re-suscripción de listeners sin perder estado ni disparar falsas alarmas
  reconnectRealtimeConnection: async (reason = 'resume') => {
    const now = Date.now();
    if ((now - lastReconnectAttemptTs) < 15000) {
      return;
    }
    lastReconnectAttemptTs = now;
    try {
      const fbConfig = useLicenseStore.getState().firebaseConfig;
      if (!fbConfig || !fbConfig.projectId || !fbConfig.apiKey) return;
      console.log(`🔄 [SENTINEL ONE] Reconectando Firebase en vivo (${reason})...`);

      get().hydrateFromFirestoreRest(false);
      useAuthStore.getState().refreshUserProfile?.().catch?.(() => {});

      await reconnectFirebaseNetwork(true);
      await get().subscribeRealtime(true);
    } catch (e) {
      console.warn('⚠️ [SENTINEL ONE] Error en reconnectRealtimeConnection:', e?.message || e);
    }
  },

  // Subscripción en tiempo real con onSnapshot (WebSockets / Long-Polling)
  subscribeRealtime: async (force = false) => {
    try {
      const fbConfig = useLicenseStore.getState().firebaseConfig;
      if (!fbConfig || !fbConfig.projectId || !fbConfig.apiKey) {
        set({ isLoading: false });
        return;
      }

      const hasAllListeners = Boolean(
        unsubDespachos &&
        unsubCentral &&
        unsubPersonal &&
        unsubVehiculos &&
        unsubAlertas
      );
      if (!force && activeSubscribedProjectId === fbConfig.projectId && hasAllListeners) {
        return;
      }

      get().unsubscribeRealtime();
      activeSubscribedProjectId = fbConfig.projectId;
      if (!force) {
        set({ isLoading: true });
      }

      await reinitFirebaseWithConfig(fbConfig);

      // Asegurar autenticación primero para que los tokens REST y onSnapshot tengan permisos válidos
      await ensureFirebaseAuth(null, null, null, force).catch(() => {});

      const db = getDb();
      if (!db) {
        console.warn('⚠️ [useDispatchStore] Firestore db no disponible.');
        set({ isLoading: false });
        get().hydrateFromFirestoreRest(false);
        return;
      }

      console.log(`📡 [SENTINEL ONE] Conectando 6 listeners onSnapshot en vivo a: ${fbConfig.projectId}`);
      get().hydrateFromFirestoreRest(false);

      // =====================================================================
      // 1. LISTENER EN TIEMPO REAL: DESPACHOS ACTIVOS (`despachos`)
      // =====================================================================
      const activeDispatchesQuery = query(
        collection(db, 'despachos'),
        where('estado', 'in', ['activa', 'ACTIVA', 'activo', 'ACTIVO', 'pre-despacho', 'PRE-DESPACHO', 'en curso', 'EN CURSO'])
      );
      unsubDespachos = onSnapshot(activeDispatchesQuery, (snap) => {
        lastSnapshotReceivedTs = Date.now();
        oneReconnectScheduler.reset();
        const rawDocs = [];
        snap.forEach((d) => {
          rawDocs.push({
            id: d.id,
            data: d.data() || {},
            hasPendingWrites: Boolean(d.metadata?.hasPendingWrites)
          });
        });
        get().applyDispatchesRawDocs(rawDocs);
      }, (err) => {
        console.warn('⚠️ [SENTINEL ONE] Error en listener onSnapshot despachos:', err.message);
        unsubDespachos = null;
        activeSubscribedProjectId = '';
        set({ isLoading: false });
        oneReconnectScheduler.schedule(() => {
          get().reconnectRealtimeConnection('despachos_error');
        });
      });

      // =====================================================================
      // 2. LISTENER EN TIEMPO REAL: PERSONAL Y DOTACIÓN (`personal`)
      // =====================================================================
      unsubPersonal = onSnapshot(collection(db, 'personal'), (snap) => {
        lastSnapshotReceivedTs = Date.now();
        const rawDocs = [];
        snap.forEach((d) => {
          rawDocs.push({
            id: d.id,
            data: d.data() || {},
            hasPendingWrites: Boolean(d.metadata?.hasPendingWrites)
          });
        });
        get().applyPersonalRawDocs(rawDocs);
      }, (err) => {
        console.warn('⚠️ [SENTINEL ONE] Error en listener onSnapshot personal:', err.message);
        unsubPersonal = null;
        oneReconnectScheduler.schedule(() => {
          get().reconnectRealtimeConnection('personal_error');
        });
      });

      // =====================================================================
      // 3. LISTENER EN TIEMPO REAL: FLOTA DE VEHÍCULOS (`vehiculos`)
      // =====================================================================
      unsubVehiculos = onSnapshot(collection(db, 'vehiculos'), (snap) => {
        lastSnapshotReceivedTs = Date.now();
        const rawDocs = [];
        snap.forEach((d) => {
          rawDocs.push({
            id: d.id,
            data: d.data() || {},
            hasPendingWrites: Boolean(d.metadata?.hasPendingWrites)
          });
        });
        get().applyVehiclesRawDocs(rawDocs);
      }, (err) => {
        console.warn('⚠️ [SENTINEL ONE] Error en listener onSnapshot vehiculos:', err.message);
        unsubVehiculos = null;
        oneReconnectScheduler.schedule(() => {
          get().reconnectRealtimeConnection('vehiculos_error');
        });
      });

      // Listener en tiempo real de Protocolos de Despacho (`configuracion/protocolos_despacho`)
      unsubProtocols = onSnapshot(doc(db, 'configuracion', 'protocolos_despacho'), (snap) => {
        if (snap && snap.exists()) {
          const pData = snap.data();
          if (pData && typeof pData === 'object') {
            set({ protocols: { ...DEFAULT_PROTOCOLS, ...pData } });
          }
        }
      }, () => {});

      // =====================================================================
      // 4. LISTENER EN TIEMPO REAL: ÓRDENES DEL DÍA Y ALERTAS (`alertas`)
      // =====================================================================
      isFirstAlertsSnapshot = true;
      unsubAlertas = onSnapshot(collection(db, 'alertas'), (snap) => {
        lastSnapshotReceivedTs = Date.now();
        const rawDocs = [];
        snap.forEach((d) => {
          rawDocs.push({
            id: d.id,
            data: d.data() || {},
            hasPendingWrites: Boolean(d.metadata?.hasPendingWrites)
          });
        });
        get().applyAlertsRawDocs(rawDocs);
      }, (err) => {
        console.warn('⚠️ [SENTINEL ONE] Error en listener onSnapshot alertas:', err.message);
        unsubAlertas = null;
        oneReconnectScheduler.schedule(() => {
          get().reconnectRealtimeConnection('alertas_error');
        });
      });

      // =====================================================================
      // 5. LISTENER EN TIEMPO REAL: ESTADO CENTRAL DE ALARMAS (`accesos/central`)
      // =====================================================================
      isFirstCentralSnapshot = true;
      unsubCentral = onSnapshot(doc(db, 'accesos', 'central'), (snap) => {
        lastSnapshotReceivedTs = Date.now();
        const data = snap.exists() ? (snap.data() || {}) : {};
        get().applyCentralRawDoc(data);
      }, (err) => {
        console.warn('⚠️ [SENTINEL ONE] Error en listener accesos/central:', err.message);
        unsubCentral = null;
        oneReconnectScheduler.schedule(() => {
          get().reconnectRealtimeConnection('central_error');
        });
      });

      // =====================================================================
      // 6. LISTENER Y CACHÉ DE ASISTENCIAS (`asistencia`)
      // =====================================================================
      get().subscribeAttendanceRealtime();

      // =====================================================================
      // 7. LISTENER EN TIEMPO REAL: COLECCIÓN `sci_incidentes` Y SUBCOLECCIONES
      //    (`solicitudes` y `mensajes`) EN `sisbom-central`.
      //    Exclusivo para el Comandante (`idRadial == "1"`). 100% separado de `alertas`.
      // =====================================================================
      const ownLicKey = String(useLicenseStore.getState().licenseKey || '').trim().toUpperCase();
      const centralDb = getCentralDb();
      if (ownLicKey && centralDb) {
        if (unsubCentralInterCorps) {
          try { unsubCentralInterCorps(); } catch (_) {}
          unsubCentralInterCorps = null;
        }
        if (unsubCentralSciRequests) {
          try { unsubCentralSciRequests(); } catch (_) {}
          unsubCentralSciRequests = null;
        }
        if (unsubCentralSciIncidents) {
          try { unsubCentralSciIncidents(); } catch (_) {}
          unsubCentralSciIncidents = null;
        }

        // 7A. COLECCIÓN PRINCIPAL: `sisbom-central/sci_incidentes/{idIncidenteSci}`
        //     y sus subcolecciones `solicitudes/{SCI-AAAA-000X}` y `mensajes/{canal}`
        unsubCentralSciIncidents = onSnapshot(
          collection(centralDb, 'sci_incidentes'),
          (snap) => {
            hasLoadedCentralSciIncidents = true;
            const nextIncMap = {};
            const activeIncIds = new Set();

            snap.forEach((d) => {
              const data = d.data() || {};
              const incId = String(d.id || data.idIncidenteSci || '').trim();
              if (!incId || incId === 'init') return;
              // Regla 1: NUNCA mostrar ni suscribir simulacros (SIM-SCI-* o isSimulated === true)
              if (isSimulatedSciPayload(data, incId)) return;
              nextIncMap[incId] = { id: incId, idIncidenteSci: incId, ...data };
              if (!isSciIncidentDocActive(data, incId)) return;
              activeIncIds.add(incId);

              // Suscribir dinámicamente a las subcolecciones `solicitudes` y `mensajes` de cada incidente activo
              // (sin destruir listeners ya activos durante reconexiones para evitar parpadeos)
              if (!unsubSciIncidentSubcols.has(incId)) {
                const unsubSol = onSnapshot(
                  collection(centralDb, 'sci_incidentes', incId, 'solicitudes'),
                  (solSnap) => {
                    const solMap = {};
                    solSnap.forEach((sDoc) => {
                      if (sDoc.id === 'init') return;
                      const sData = sDoc.data() || {};
                      if (isSimulatedSciPayload(sData, sDoc.id)) return;
                      solMap[sDoc.id] = {
                        ...sData,
                        id: sDoc.id,
                        idSolicitud: String(sData.idSolicitud || sDoc.id).trim(),
                        idIncidenteSci: incId
                      };
                    });
                    latestSciIncidentSolicitudesByInc = {
                      ...latestSciIncidentSolicitudesByInc,
                      [incId]: solMap
                    };
                    get().recomputeSciState();
                  },
                  () => {}
                );

                const unsubMsg = onSnapshot(
                  collection(centralDb, 'sci_incidentes', incId, 'mensajes'),
                  (msgSnap) => {
                    const msgMap = {};
                    msgSnap.forEach((mDoc) => {
                      if (mDoc.id === 'init') return;
                      msgMap[String(mDoc.id).trim().toUpperCase()] = {
                        id: mDoc.id,
                        idCanal: String(mDoc.id).trim().toUpperCase(),
                        idIncidenteSci: incId,
                        ...(mDoc.data() || {})
                      };
                    });
                    latestSciIncidentMensajesByInc = {
                      ...latestSciIncidentMensajesByInc,
                      [incId]: msgMap
                    };
                    get().recomputeSciState();
                  },
                  () => {}
                );

                unsubSciIncidentSubcols.set(incId, [unsubSol, unsubMsg]);
              }
            });

            // Desuscribir únicamente incidentes que ya se cerraron (`activo === false`) o desaparecieron
            Array.from(unsubSciIncidentSubcols.keys()).forEach((oldIncId) => {
              if (!activeIncIds.has(oldIncId)) {
                const arr = unsubSciIncidentSubcols.get(oldIncId) || [];
                arr.forEach((u) => {
                  try { u(); } catch (_) {}
                });
                unsubSciIncidentSubcols.delete(oldIncId);
                delete latestSciIncidentSolicitudesByInc[oldIncId];
                delete latestSciIncidentMensajesByInc[oldIncId];
              }
            });

            latestCentralSciIncidentsMap = nextIncMap;
            get().recomputeSciState();
          },
          () => {}
        );
      }

    } catch (e) {
      console.error('❌ [SENTINEL ONE] Error conectando a Firestore onSnapshot:', e);
      set({ isLoading: false });
      get().hydrateFromFirestoreRest(false);
    }
  },

  // Recalcula estado reactivo del SCI exclusivamente desde `sisbom-central/sci_incidentes` y sus subcolecciones (`solicitudes` y `mensajes`)
  // (Exclusivo para Comandante `idRadial == "1"`, 100% separado de `alertas`, sin leer de `saas_clientes` y trabajando siempre con el mismo `idSolicitud`)
  recomputeSciState: () => {
    const ownLicKey = String(useLicenseStore.getState().licenseKey || '').trim().toUpperCase();
    const currentUser = useAuthStore.getState().user;
    const isCmd = hasCommanderPrivileges(currentUser);

    if (!ownLicKey || !isCmd) {
      set({
        interCorpsAlert: null,
        sciRequestsList: [],
        activeSciNeighborCorps: [],
        sciChatMessagesRaw: '',
        sciUnreadCount: 0,
        isSciTabActive: false
      });
      return;
    }

    const isRequestActive = (req) => {
      if (!req || typeof req !== 'object') return false;
      if (isSimulatedSciPayload(req)) return false;
      if (req.sciActivo === false || req.activa === false) return false;
      const st = String(req.estado || 'PENDIENTE').trim().toUpperCase();
      if (['CANCELADA', 'CANCELADO', 'FINALIZADA', 'FINALIZADO'].includes(st)) return false;
      if (req.duracion !== undefined && req.duracion !== null && String(req.duracion).trim() === '') return false;
      return true;
    };

    const isSpecificUnitReq = (req) => {
      const t = String(req?.tipoSolicitud || '').trim().toUpperCase();
      const car = String(req?.idCarroSolicitado || '').trim().toUpperCase();
      return (
        t === 'SOLICITUD_UNIDAD' &&
        Boolean(car) &&
        !['ALERTA GENERAL', 'CUALQUIERA', 'TODOS', 'NINGUNO'].includes(car)
      );
    };

    const getReqTimestamp = (r) =>
      Number(r?._metaTimestamp || r?.updatedAt || r?.timestamp || r?.createdAt || 0);

    // 1. Consolidar solicitudes activas `SCI-AAAA-000X` exclusivamente desde `sci_incidentes/{idIncidenteSci}/solicitudes`
    //    conservando SIEMPRE su `idSolicitud` exacto (sin inventar ni recalcular IDs nuevos).
    const requestsBySlot = new Map();
    const activeIncidentIdsWithRequests = new Set();

    Object.entries(latestSciIncidentSolicitudesByInc || {}).forEach(([incId, solMap]) => {
      const parentInc = latestCentralSciIncidentsMap[incId];
      // Si ya cargó `sci_incidentes` y el incidente padre fue cerrado (`activo === false`) o no existe, descartar
      if (parentInc && !isSciIncidentDocActive(parentInc, incId)) return;
      if (hasLoadedCentralSciIncidents && !parentInc) return;

      Object.entries(solMap || {}).forEach(([solId, solData]) => {
        if (!solData || typeof solData !== 'object' || isSimulatedSciPayload(solData, solId)) return;
        const targetLic = String(solData.licenciaDestino || solData.licenciaApoyo || '').trim().toUpperCase();
        const originLic = String(
          solData.licenciaSolicitante || parentInc?.licenciaSolicitante || ''
        )
          .trim()
          .toUpperCase();
        if (targetLic !== ownLicKey || !originLic || originLic === ownLicKey) return;
        if (!isRequestActive(solData)) return;

        // Conservar SIEMPRE el mismo idSolicitud que viene de SENTINEL SCI
        const exactId = String(solData.idSolicitud || solId || solData.id || '').trim().toUpperCase();
        if (!exactId || exactId === 'INIT') return;

        const isUnit = isSpecificUnitReq(solData);
        const uCar = String(solData.idCarroSolicitado || '').trim().toUpperCase();
        // Agrupar por slot lógico dentro del incidente (1 tarjeta para la alerta general del Cuerpo + 1 tarjeta por cada unidad específica solicitada)
        const slotKey = isUnit ? `${incId}__UNIT__${uCar}` : `${incId}__CORP_ALERT`;
        const existingInSlot = requestsBySlot.get(slotKey);
        const incomingTs = getReqTimestamp(solData);
        const existingTs = existingInSlot ? getReqTimestamp(existingInSlot) : -1;

        if (
          !existingInSlot ||
          incomingTs > existingTs ||
          (incomingTs === existingTs && exactId.localeCompare(String(existingInSlot.idSolicitud || '')) >= 0)
        ) {
          const resolvedSvcId = String(
            solData.idServicioOrigen || solData.idServicio || parentInc?.idServicio || 'SCI-ACTIVO'
          ).trim();
          if (resolvedSvcId.toUpperCase().startsWith('SIM-SCI-')) return;

          const mergedChat = String(
            solData.mensajesChat ||
              solData.chatDirecto?.mensajeAlerta ||
              existingInSlot?.mensajesChat ||
              ''
          ).trim();

          requestsBySlot.set(slotKey, {
            ...(existingInSlot || {}),
            ...solData,
            id: exactId,
            idSolicitud: exactId,
            idAlerta: exactId,
            idIncidenteSci: incId,
            soloComandante: true,
            idRadialDestinatario: '1',
            sciActivo: true,
            tipoSolicitud: isUnit ? 'SOLICITUD_UNIDAD' : 'ALERTA_PREVENTIVA',
            estado: String(solData.estado || 'PENDIENTE').trim().toUpperCase(),
            licenciaSolicitante: originLic,
            cuerpoSolicitante: String(
              solData.cuerpoSolicitante ||
                solData.nombreCuerpoSolicitante ||
                parentInc?.cuerpoSolicitante ||
                'CUERPO DE BOMBEROS VECINO'
            )
              .trim()
              .toUpperCase(),
            comunaSolicitante: String(
              solData.comunaSolicitante || parentInc?.comunaSolicitante || ''
            )
              .trim()
              .toUpperCase(),
            licenciaApoyo: ownLicKey,
            cuerpoApoyo: String(
              solData.cuerpoApoyo ||
                solData.cuerpoDestino ||
                solData.nombreCuerpoApoyo ||
                useLicenseStore.getState().clientName ||
                ownLicKey
            )
              .trim()
              .toUpperCase(),
            idServicioOrigen: resolvedSvcId,
            idServicio: resolvedSvcId,
            claveEmergencia: String(
              solData.claveEmergencia ||
                solData.clave ||
                parentInc?.claveEmergencia ||
                parentInc?.clave ||
                '10-12'
            )
              .trim()
              .toUpperCase(),
            direccionEmergencia: String(
              solData.direccionEmergencia ||
                solData.lugar ||
                parentInc?.direccionEmergencia ||
                parentInc?.lugar ||
                ''
            )
              .trim()
              .toUpperCase(),
            comandanteSolicitante: String(
              solData.comandanteSolicitante ||
                solData.comandanteSci ||
                solData.quienAlerta ||
                parentInc?.comandanteSci ||
                'COMANDANTE SCI'
            )
              .trim()
              .toUpperCase(),
            fechaSolicitud: String(
              solData.fechaSolicitud || solData.fechaAlerta || getLocalDateString()
            )
              .replace(/^'+/, '')
              .trim(),
            horaSolicitud: String(
              solData.horaSolicitud || solData.horaAlerta || getLocalTimeString()
            )
              .replace(/^'+/, '')
              .trim(),
            idCarroSolicitado: uCar,
            sectorDestino: String(solData.sectorDestino || '').trim().toUpperCase(),
            instruccionTactica: String(solData.instruccionTactica || '').trim(),
            dirigirse:
              solData.dirigirse && typeof solData.dirigirse === 'object' ? solData.dirigirse : null,
            geoEmergencia:
              solData.geoEmergencia ||
              solData.geo ||
              parentInc?.geoEmergencia ||
              parentInc?.geo ||
              null,
            mensajesChat: mergedChat,
            updatedAt: incomingTs > 0 ? incomingTs : Date.now(),
            _metaTimestamp: incomingTs > 0 ? incomingTs : Date.now()
          });
          activeIncidentIdsWithRequests.add(incId);
        }
      });
    });

    const sortedRequests = Array.from(requestsBySlot.values()).sort((a, b) =>
      String(b.idSolicitud).localeCompare(String(a.idSolicitud))
    );

    // 2. Poblar información de Cuerpos vecinos solicitantes únicamente para incidentes con solicitudes activas hacia `ownLicKey`
    const neighborCorpsByLic = new Map();
    activeIncidentIdsWithRequests.forEach((incId) => {
      const incData = latestCentralSciIncidentsMap[incId] || {};
      const sampleReq = sortedRequests.find((r) => r.idIncidenteSci === incId) || {};
      const licKey = String(incData.licenciaSolicitante || sampleReq.licenciaSolicitante || '')
        .trim()
        .toUpperCase();
      if (!licKey || licKey === ownLicKey) return;
      const corpsName = String(
        incData.cuerpoSolicitante || sampleReq.cuerpoSolicitante || licKey
      )
        .trim()
        .toUpperCase();
      const cmdName = String(
        incData.comandanteSci || sampleReq.comandanteSolicitante || 'COMANDANTE SCI'
      )
        .trim()
        .toUpperCase();
      const cleanSvcId = String(
        incData.idServicio || sampleReq.idServicioOrigen || 'SCI-ACTIVO'
      ).trim();

      neighborCorpsByLic.set(licKey, {
        idIncidenteSci: incId,
        licencia: licKey,
        licenciaSolicitante: licKey,
        nombreCuerpo: corpsName,
        cuerpoSolicitante: corpsName,
        comunaSolicitante: String(
          incData.comunaSolicitante || sampleReq.comunaSolicitante || ''
        )
          .trim()
          .toUpperCase(),
        logoSolicitante: String(incData.logoSolicitante || '').trim(),
        idServicio: cleanSvcId,
        claveEmergencia: String(
          incData.claveEmergencia || incData.clave || sampleReq.claveEmergencia || '10-12'
        )
          .trim()
          .toUpperCase(),
        direccionEmergencia: String(
          incData.direccionEmergencia || incData.lugar || sampleReq.direccionEmergencia || ''
        )
          .trim()
          .toUpperCase(),
        comandanteSci: cmdName,
        comandanteIncidente: cmdName,
        geoEmergencia:
          incData.geoEmergencia || incData.geo || sampleReq.geoEmergencia || null,
        updatedAt: Number(incData.updatedAt || sampleReq.updatedAt || Date.now())
      });
    });

    const neighborCorps = Array.from(neighborCorpsByLic.values());

    // 3. Resolver mensajes del Chat Directo SCI exclusivamente desde `sci_incidentes/{idIncidenteSci}/mensajes/{ownLicKey}` y solicitud activa
    const primaryReq = sortedRequests[0] || null;
    const activeIncId =
      primaryReq?.idIncidenteSci ||
      neighborCorps[0]?.idIncidenteSci ||
      '';
    const incMsgMap = activeIncId ? (latestSciIncidentMensajesByInc[activeIncId] || {}) : {};
    const directChannelDoc = incMsgMap[ownLicKey] || incMsgMap[`SCI_CHAT_${ownLicKey}`] || null;
    const generalChannelDoc = incMsgMap.GENERAL || incMsgMap.SCI_CHAT_INTERCUERPOS || null;

    const candidatesChat = activeIncId
      ? [
          String(directChannelDoc?.mensajesChat || directChannelDoc?.mensajeAlerta || '').trim(),
          String(primaryReq?.mensajesChat || '').trim(),
          String(generalChannelDoc?.mensajesChat || generalChannelDoc?.mensajeAlerta || '').trim()
        ].filter(Boolean)
      : [];

    let chatRaw = '';
    let maxCount = -1;
    candidatesChat.forEach((cStr) => {
      const cLen = parseCanonicalChatMessages(cStr).length;
      if (cLen > maxCount) {
        maxCount = cLen;
        chatRaw = cStr;
      }
    });

    const parsedChatMsgs = parseCanonicalChatMessages(chatRaw);
    if (lastSciChatMsgCount < 0) {
      lastSciChatMsgCount = parsedChatMsgs.length;
    }
    const newChatMsgsCount = Math.max(0, parsedChatMsgs.length - lastSciChatMsgCount);

    const pendingRequests = sortedRequests.filter((r) =>
      ['PENDIENTE', 'ALERTA'].includes(String(r.estado || '').trim().toUpperCase())
    );
    const sciUnreadCount = pendingRequests.length + newChatMsgsCount;
    const isSciTabActive = sortedRequests.length > 0;

    // Si el modal de chat está abierto sobre el canal SCI, mantenerlo sincronizado o cerrarlo si ya no hay SCI activo
    const curActiveChat = get().activeChatAlert;
    if (curActiveChat && (curActiveChat.isSciCentralChat || isResidualSciAlertDoc(curActiveChat))) {
      if (!isSciTabActive) {
        set({ activeChatAlert: null, chatAlert: null });
      } else {
        const updatedModalChat = {
          ...curActiveChat,
          idIncidenteSci: activeIncId || curActiveChat.idIncidenteSci || '',
          mensajeAlerta: chatRaw,
          _metaTimestamp: Date.now()
        };
        set({ activeChatAlert: updatedModalChat, chatAlert: updatedModalChat });
      }
    }

    // Si ya no hay incidentes SCI activos, limpiar cualquier banner urgente SCI residual
    if (!isSciTabActive) {
      const curBanner = String(get().urgentBannerMessage || '');
      if (curBanner.includes('SCI')) {
        set({ urgentBannerMessage: null });
      }
    }

    // 4. Disparar tono de alerta `alertas.mp3`, notificación Push/Local y banner urgente SOLO al Comandante cuando entra nueva solicitud PENDIENTE/ALERTA o nuevo mensaje SCI
    const latestChatMsg = parsedChatMsgs.length > 0 ? parsedChatMsgs[parsedChatMsgs.length - 1] : null;
    const myReg = String(currentUser?.idRegistro || '').trim();
    const myNameUp = String(currentUser?.nombreBombero || currentUser?.nombre || '').trim().toUpperCase();
    const isLastMsgFromMe = latestChatMsg
      ? (myReg && String(latestChatMsg.senderId).includes(myReg)) ||
        (myNameUp && String(latestChatMsg.senderId).toUpperCase().includes(myNameUp))
      : true;

    const unnotifiedPending = pendingRequests.filter((r) => {
      const rKey = `${r.idSolicitud || r.id}_${r.tipoSolicitud || 'ALERTA'}_${r.idCarroSolicitado || ''}`;
      const playedKey = `sci_req_${r.idSolicitud || r.id}`;
      return !notifiedSciRequestIds.has(rKey) && !hasPlayedSoundKey(playedKey);
    });

    if (isFirstSciSnapshot) {
      // Marcar snapshot inicial solo cuando ya se cargaron las solicitudes de los incidentes activos
      const activeIncsInCentral = Object.entries(latestCentralSciIncidentsMap || {}).filter(
        ([kId, docObj]) => isSciIncidentDocActive(docObj, kId)
      );
      const allActiveSubcolsLoaded =
        hasLoadedCentralSciIncidents &&
        activeIncsInCentral.every(([kId]) => latestSciIncidentSolicitudesByInc[kId] !== undefined);
      if (allActiveSubcolsLoaded) {
        isFirstSciSnapshot = false;
        pendingRequests.forEach((r) => {
          const rKey = `${r.idSolicitud || r.id}_${r.tipoSolicitud || 'ALERTA'}_${r.idCarroSolicitado || ''}`;
          notifiedSciRequestIds.add(rKey);
          markPlayedSoundKey(`sci_req_${r.idSolicitud || r.id}`);
        });
      }
    } else if (unnotifiedPending.length > 0) {
      unnotifiedPending.forEach((r) => {
        const rKey = `${r.idSolicitud || r.id}_${r.tipoSolicitud || 'ALERTA'}_${r.idCarroSolicitado || ''}`;
        notifiedSciRequestIds.add(rKey);
        markPlayedSoundKey(`sci_req_${r.idSolicitud || r.id}`);
      });
      const topReq = unnotifiedPending[0];
      const isUnitTop = isSpecificUnitReq(topReq);
      const sciNotifTitle = isUnitTop
        ? `🚨 [${topReq.idSolicitud}] SCI SOLICITA UNIDAD ${topReq.idCarroSolicitado}`
        : `🚨 [${topReq.idSolicitud}] ALERTA DE APOYO SCI`;
      const sciNotifBody = isUnitTop
        ? `${topReq.cuerpoSolicitante} solicita la unidad ${topReq.idCarroSolicitado} para emergencia Clave ${topReq.claveEmergencia || '10-12'}${topReq.direccionEmergencia ? ` en ${topReq.direccionEmergencia}` : ''}.`
        : `${topReq.cuerpoSolicitante} solicita apoyo SCI por emergencia Clave ${topReq.claveEmergencia || '10-12'}${topReq.direccionEmergencia ? ` en ${topReq.direccionEmergencia}` : ''}.`;

      get().playDispatchSound('alerta', {
        forceMaxVolume: true,
        vibratePatternCsv: '0,1000,300,1000,300,1000'
      });
      try {
        if (typeof window !== 'undefined') {
          if (window.AndroidApp?.showLocalNotification) {
            window.AndroidApp.showLocalNotification(sciNotifTitle, sciNotifBody, 'alertas');
          } else if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
            window.webkit.messageHandlers.iOSApp.postMessage({
              action: 'showLocalNotification',
              id: `sci_req_${topReq.idSolicitud || Date.now()}`,
              title: sciNotifTitle,
              body: sciNotifBody,
              soundName: 'alertas.mp3',
              forceMaxVolume: true
            });
          }
        }
      } catch (_) {}
      set({
        urgentBannerMessage: isUnitTop
          ? `🚨 [${topReq.idSolicitud}] SCI SOLICITA UNIDAD ${topReq.idCarroSolicitado} (${topReq.cuerpoSolicitante})`
          : `🚨 [${topReq.idSolicitud}] SOLICITUD DE APOYO SCI — ${topReq.cuerpoSolicitante} (${topReq.claveEmergencia})`
      });
    } else if (newChatMsgsCount > 0 && !isLastMsgFromMe) {
      lastSciChatMsgCount = parsedChatMsgs.length;
      const chatTitle = `💬 MENSAJE SCI — ${primaryReq?.cuerpoSolicitante || 'RED INTER-CUERPOS'}`;
      const chatBody = String(latestChatMsg?.texto || 'Nuevo mensaje en Canal Directo SCI');
      get().playDispatchSound('alerta', {
        forceMaxVolume: true,
        vibratePatternCsv: '0,800,300,800'
      });
      try {
        if (typeof window !== 'undefined') {
          if (window.AndroidApp?.showLocalNotification) {
            window.AndroidApp.showLocalNotification(chatTitle, chatBody, 'alertas');
          } else if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
            window.webkit.messageHandlers.iOSApp.postMessage({
              action: 'showLocalNotification',
              id: `sci_chat_${Date.now()}`,
              title: chatTitle,
              body: chatBody,
              soundName: 'alertas.mp3',
              forceMaxVolume: true
            });
          }
        }
      } catch (_) {}
      set({
        urgentBannerMessage: `💬 NUEVO MENSAJE SCI (${primaryReq?.cuerpoSolicitante || 'RED INTER-CUERPOS'}): ${latestChatMsg?.texto || ''}`
      });
    }

    const prevSciState = get();
    const prevReqSig = (prevSciState.sciRequestsList || [])
      .map((r) => `${r.idSolicitud}:${r.estado}:${r._metaTimestamp || 0}`)
      .join('|');
    const nextReqSig = sortedRequests
      .map((r) => `${r.idSolicitud}:${r.estado}:${r._metaTimestamp || 0}`)
      .join('|');
    const prevCorpsSig = (prevSciState.activeSciNeighborCorps || [])
      .map((c) => `${c.licencia}:${c.idServicio}`)
      .join('|');
    const nextCorpsSig = neighborCorps
      .map((c) => `${c.licencia}:${c.idServicio}`)
      .join('|');

    if (
      prevReqSig !== nextReqSig ||
      prevCorpsSig !== nextCorpsSig ||
      prevSciState.sciChatMessagesRaw !== chatRaw ||
      prevSciState.sciUnreadCount !== sciUnreadCount ||
      prevSciState.isSciTabActive !== isSciTabActive ||
      (prevSciState.interCorpsAlert?.idSolicitud || '') !== (primaryReq?.idSolicitud || '')
    ) {
      set({
        interCorpsAlert: primaryReq || null,
        sciRequestsList: sortedRequests,
        activeSciNeighborCorps: neighborCorps,
        sciChatMessagesRaw: chatRaw,
        sciUnreadCount,
        isSciTabActive
      });
    }
  },

  // Carga instantánea desde caché local al abrir la app + Sincronización incremental ("Delta Sync") al presionar "Sincronizar"
  subscribeAttendanceRealtime: async (forceRefresh = false) => {
    const currentUser = useAuthStore.getState().user;
    const userId = String(currentUser?.idRegistro || currentUser?.idRadial || '').trim();
    if (!userId) return;
    lastSubscribedAttendanceUserId = userId;

    if (unsubAsistencia) {
      try { unsubAsistencia(); } catch (e) {}
      unsubAsistencia = null;
    }

    const cacheKey = `@sentinel_one_v3_attendance_v4_canon_${userId}`;
    const statusMapKey = `@sentinel_one_v3_att_status_map_v4_${userId}`;
    const initDoneKey = `@sentinel_one_v3_att_init_done_v4_${userId}`;
    let cachedList = [];
    let statusCacheObj = {};
    let isInitDone = false;

    try {
      // 1. Lectura rápida síncrona desde window.localStorage si está disponible
      if (typeof window !== 'undefined' && window.localStorage) {
        const syncCache = window.localStorage.getItem(cacheKey);
        const syncMap = window.localStorage.getItem(statusMapKey);
        const syncInit = window.localStorage.getItem(initDoneKey);
        if (syncCache) cachedList = JSON.parse(syncCache) || [];
        if (syncMap) statusCacheObj = JSON.parse(syncMap) || {};
        if (syncInit === '1') isInitDone = true;
      }

      // 2. Respaldo desde AsyncStorage si aún no estaba en memoria
      if (cachedList.length === 0) {
        const [rawCache, rawMap, rawInit] = await Promise.all([
          AsyncStorage.getItem(cacheKey),
          AsyncStorage.getItem(statusMapKey),
          AsyncStorage.getItem(initDoneKey)
        ]);
        if (rawCache) cachedList = JSON.parse(rawCache) || [];
        if (rawMap) statusCacheObj = JSON.parse(rawMap) || {};
        if (rawInit === '1') isInitDone = true;
      }
    } catch (e) {}

    // Hidratar inmediatamente las listas guardadas en pantalla
    if (cachedList.length > 0) {
      set({
        attendanceList: cachedList,
        isLoadingAttendance: false,
        isAttendanceLoading: false
      });
    }

    // REGLA CLAVE: Al abrir la app (forceRefresh === false), si ya existen listas guardadas en caché
    // (o ya se hizo la carga inicial), NO volver a descargar todas las listas desde Firebase.
    if (!forceRefresh && (cachedList.length > 0 || isInitDone)) {
      set({ isLoadingAttendance: false, isAttendanceLoading: false });
      return;
    }

    const db = getDb();
    if (!db) {
      set({ isLoadingAttendance: false, isAttendanceLoading: false });
      return;
    }

    set({ isLoadingAttendance: true, isAttendanceLoading: true });

    try {
      await ensureFirebaseAuth();
      const cachedById = new Map();
      cachedList.forEach((item) => {
        if (item?.idLista) cachedById.set(String(item.idLista).trim(), item);
      });

      let rawSheetDocs = [];
      try {
        const snap = await getDocs(collection(db, 'asistencia'));
        snap.forEach((d) => {
          rawSheetDocs.push({ id: d.id, data: d.data() || {} });
        });
      } catch (_) {}

      // Respaldo por REST si getDocs falla en iOS WKWebView
      if (rawSheetDocs.length === 0) {
        try {
          const fbConfig = useLicenseStore.getState().firebaseConfig;
          if (fbConfig && fbConfig.projectId && fbConfig.apiKey) {
            const baseUrl = `https://firestore.googleapis.com/v1/projects/${fbConfig.projectId}/databases/(default)/documents`;
            const res = await fetchFirestoreWithAuth(`${baseUrl}/asistencia?pageSize=200`, fbConfig.apiKey);
            if (res && res.ok) {
              const rJson = await res.json();
              rawSheetDocs = (rJson?.documents || []).map((docItem) => {
                const fields = docItem?.fields || {};
                const parsed = {};
                for (const k of Object.keys(fields)) parsed[k] = parseFirestoreValue(fields[k]);
                return { id: docItem.name.split('/').pop(), data: parsed };
              });
            }
          }
        } catch (_) {}
      }

      const sheets = rawSheetDocs.map(({ id, data }) => {
        const normalized = normalizeAttendanceSheetDoc(id, data);
        const existingCached = cachedById.get(String(normalized.idLista).trim());
        // Ley #4: Mayor Timestamp Gana en caché de listas de asistencia
        if (existingCached && getEntityMaxTimestamp(existingCached) > getEntityMaxTimestamp(normalized)) {
          return {
            ...normalized,
            ...existingCached,
            _metaTimestamp: Math.max(getEntityMaxTimestamp(existingCached), getEntityMaxTimestamp(normalized))
          };
        }
        return normalized;
      });

      sheets.sort((a, b) => {
        const numA = parseInt(String(a.idLista).replace(/\D/g, ''), 10) || 0;
        const numB = parseInt(String(b.idLista).replace(/\D/g, ''), 10) || 0;
        return numB - numA;
      });

      // Asegurar que las listas ya aprobadas en cachedList estén registradas en statusCacheObj
      cachedList.forEach((item) => {
        const wasApproved = String(item?.aprobadoPor || '').trim().length > 0;
        if (item?.idLista && item.userEstado && !statusCacheObj[item.idLista]) {
          statusCacheObj[item.idLista] = {
            u: item.userEstado,
            a: item.userAbono,
            approved: wasApproved
          };
        }
      });

      const sheetsToFetch = [];
      sheets.forEach((sheet, idx) => {
        const isApprovedSheet = String(sheet.aprobadoPor || '').trim().length > 0;
        const cachedEntry = statusCacheObj[sheet.idLista];
        const isRecentTop3 = forceRefresh && idx < 3;

        // 1. Si viene embebido en el documento raíz
        if (sheet.inlineBomberos && sheet.inlineBomberos[userId]) {
          const bData = sheet.inlineBomberos[userId];
          sheet.userEstado = String(bData.estado || 'FALTA');
          const inlineAbono = bData.esAbono ?? bData.abono;
          if (inlineAbono !== undefined && inlineAbono !== null && inlineAbono !== '') {
            sheet.userAbono = inlineAbono;
          }
          statusCacheObj[sheet.idLista] = {
            u: sheet.userEstado,
            a: sheet.userAbono,
            approved: isApprovedSheet
          };
        } else if (
          isApprovedSheet &&
          cachedEntry?.u &&
          cachedEntry.approved !== false &&
          !isRecentTop3
        ) {
          // 2. Lista histórica ya aprobada y ya conocida en caché local: reutilizar sin descargar subcolección
          sheet.userEstado = cachedEntry.u;
          if (cachedEntry.a !== undefined && cachedEntry.a !== null) {
            sheet.userAbono = cachedEntry.a;
          }
        } else {
          // 3. Lista nueva, pendiente por aprobar, recién aprobada o dentro de las 3 más recientes al sincronizar
          sheetsToFetch.push(sheet);
        }

        // Liberar mapa pesado inlineBomberos para no saturar RAM ni localStorage
        sheet.inlineBomberos = null;
      });

      // Consultar subcolección `asistencia/{idLista}/bomberos/{userId}` SOLO para listas nuevas o actualizadas
      if (sheetsToFetch.length > 0) {
        const BATCH_SIZE = 15;
        for (let i = 0; i < sheetsToFetch.length; i += BATCH_SIZE) {
          const batch = sheetsToFetch.slice(i, i + BATCH_SIZE);
          await Promise.all(
            batch.map(async (sheet) => {
              try {
                const subSnap = await getDoc(doc(db, 'asistencia', sheet.idLista, 'bomberos', userId));
                if (subSnap.exists()) {
                  const subData = subSnap.data() || {};
                  sheet.userEstado = String(subData.estado || 'FALTA');
                  const subAbono = subData.esAbono ?? subData.abono;
                  if (subAbono !== undefined && subAbono !== null && subAbono !== '') {
                    sheet.userAbono = subAbono;
                  }
                } else {
                  sheet.userEstado = 'NO_REGISTRA';
                }
              } catch {
                sheet.userEstado = 'NO_REGISTRA';
              }
              const isApprovedSheet = String(sheet.aprobadoPor || '').trim().length > 0;
              if (sheet.userEstado) {
                statusCacheObj[sheet.idLista] = {
                  u: sheet.userEstado,
                  a: sheet.userAbono,
                  approved: isApprovedSheet
                };
              }
            })
          );
        }
      }

      const validSheets = sheets
        .filter((s) => s.userEstado && s.userEstado !== 'NO_REGISTRA')
        .sort((a, b) => {
          const numA = parseInt(String(a.idLista).replace(/\D/g, ''), 10) || 0;
          const numB = parseInt(String(b.idLista).replace(/\D/g, ''), 10) || 0;
          return numB - numA;
        });

      set({
        attendanceList: validSheets,
        isLoadingAttendance: false,
        isAttendanceLoading: false
      });

      const serializedValid = JSON.stringify(validSheets);
      const serializedMap = JSON.stringify(statusCacheObj);

      try {
        if (typeof window !== 'undefined' && window.localStorage) {
          window.localStorage.setItem(cacheKey, serializedValid);
          window.localStorage.setItem(statusMapKey, serializedMap);
          window.localStorage.setItem(initDoneKey, '1');
        }
      } catch (_) {}

      try {
        await Promise.all([
          AsyncStorage.setItem(cacheKey, serializedValid),
          AsyncStorage.setItem(statusMapKey, serializedMap),
          AsyncStorage.setItem(initDoneKey, '1')
        ]);
      } catch (_) {}
    } catch (err) {
      console.warn('Error sincronizando asistencias:', err);
      set({ isLoadingAttendance: false, isAttendanceLoading: false });
    }
  },

  refreshAttendance: async () => {
    await get().subscribeAttendanceRealtime(true);
  },

  // =========================================================================
  // ACCIONES OPERATIVAS (TIMBRE, PUERTA IOT, CENTRAL, DESPACHO, ALERTAS, CHAT)
  // =========================================================================

  // 1. Botón TIMBRE: Solicitar apertura de puerta al Operador CAD (Idéntico a ESQUEMA_CANONICO_V3.md)
  solicitarAperturaPuerta: async () => {
    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return { success: true };

    const user = useAuthStore.getState().user;
    if (!user) return { success: false };

    const nowTs = Date.now();
    const solId = String(nowTs);

    const words = String(user.nombreBombero || 'BOMBERO').trim().split(/\s+/).filter(Boolean);
    const shortName = words.length >= 3
      ? `${words[0]} ${words[words.length - 2]}`
      : words.join(' ');

    const myReg = String(user.idRegistro || user.idRadial || '').trim();
    const compNum = Number(user.compania || 1) || 1;
    knownDoorRequestKeys.add(`door_${solId}`);

    // Ejecutar también por puente nativo iOS para evitar latencia/suspensión de WKWebView
    try {
      if (typeof window !== 'undefined' && window.webkit?.messageHandlers?.iOSApp?.postMessage) {
        window.webkit.messageHandlers.iOSApp.postMessage({
          action: 'requestDoorBell',
          solId,
          idRegistro: myReg,
          nombreBombero: shortName,
          compania: compNum
        });
      }
    } catch (_) {}

    const docData = {
      idSolicitud: solId,
      idRegistro: myReg,
      nombreBombero: shortName,
      compania: compNum,
      estado: 'pendiente',
      timestamp: nowTs,
      _metaTimestamp: nowTs
    };

    const withLimit = (promise, ms = 3000) =>
      Promise.race([promise, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), ms))]);

    try {
      const db = getDb();
      await withLimit(ensureFirebaseAuth(), 2500).catch(() => {});
      markLocalWrite('solicitudes_puerta', solId, nowTs);
      await withLimit(setDoc(doc(db, 'solicitudes_puerta', solId), docData), 3500);
      return { success: true };
    } catch (e) {
      // Respaldo REST autenticado
      try {
        const fbConfig = useLicenseStore.getState().firebaseConfig;
        if (fbConfig?.projectId && fbConfig?.apiKey) {
          const restUrl = `https://firestore.googleapis.com/v1/projects/${fbConfig.projectId}/databases/(default)/documents/solicitudes_puerta/${encodeURIComponent(solId)}`;
          const res = await fetchFirestoreWithAuth(restUrl, fbConfig.apiKey, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              fields: {
                idSolicitud: { stringValue: solId },
                idRegistro: { stringValue: myReg },
                nombreBombero: { stringValue: shortName },
                compania: { integerValue: String(compNum) },
                estado: { stringValue: 'pendiente' },
                timestamp: { integerValue: String(nowTs) },
                _metaTimestamp: { integerValue: String(nowTs) }
              }
            })
          });
          if (res.ok) return { success: true };
        }
      } catch (_) {}
      console.warn('Error en solicitarAperturaPuerta:', e);
      return { success: false, error: e.message };
    }
  },

  // 2. Apertura directa de Puerta IoT (Comandante / Operador / Autorizados)
  openDoorIoT: async () => {
    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return { success: true };

    const user = useAuthStore.getState().user;
    const nowTs = Date.now();
    const callerName = String(user?.nombreBombero || user?.nombre || 'SENTINEL ONE').trim();

    // Disparar puente nativo iOS inmediatamente (escribe accesos/central, configuracion/porton y RTDB con resets automáticos)
    let triggeredNativeIos = false;
    try {
      if (typeof window !== 'undefined' && window.webkit?.messageHandlers?.iOSApp?.postMessage) {
        window.webkit.messageHandlers.iOSApp.postMessage({
          action: 'openDoor',
          userName: callerName
        });
        triggeredNativeIos = true;
      }
    } catch (_) {}

    const withLimit = (promise, ms = 3000) =>
      Promise.race([promise, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), ms))]);

    let wroteSomething = triggeredNativeIos;

    try {
      await withLimit(ensureFirebaseAuth(), 2200).catch(() => {});

      const rtdb = getRtdb();
      if (rtdb) {
        withLimit(
          rtdbSet(ref(rtdb, 'hardware/puerta'), {
            trigger: true,
            comando: 'ABRIR',
            timestamp: nowTs,
            solicitante: callerName
          }),
          2500
        )
          .then(() => {
            wroteSomething = true;
          })
          .catch(() => {});
      }

      const db = getDb();
      let centralWritten = false;
      if (db) {
        try {
          markLocalWrite('accesos', 'central', nowTs);
          await withLimit(
            setDoc(
              doc(db, 'accesos', 'central'),
              {
                puerta: true,
                timestampPuerta: nowTs,
                solicitantePuerta: callerName,
                _metaTimestamp: nowTs
              },
              { merge: true }
            ),
            3000
          );
          centralWritten = true;
          wroteSomething = true;

          withLimit(
            setDoc(
              doc(db, 'configuracion', 'porton'),
              {
                comando: 'ABRIR',
                timestamp: nowTs,
                autorizadoPor: callerName,
                _metaTimestamp: nowTs
              },
              { merge: true }
            ),
            2500
          ).catch(() => {});
        } catch (_) {}
      }

      if (!centralWritten) {
        const fbConfig = useLicenseStore.getState().firebaseConfig;
        if (fbConfig?.projectId && fbConfig?.apiKey) {
          const patchCentralUrl = `https://firestore.googleapis.com/v1/projects/${fbConfig.projectId}/databases/(default)/documents/accesos/central?updateMask.fieldPaths=puerta&updateMask.fieldPaths=timestampPuerta&updateMask.fieldPaths=solicitantePuerta&updateMask.fieldPaths=_metaTimestamp`;
          const restRes = await withLimit(
            fetchFirestoreWithAuth(patchCentralUrl, fbConfig.apiKey, {
              method: 'PATCH',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({
                fields: {
                  puerta: { booleanValue: true },
                  timestampPuerta: { integerValue: String(nowTs) },
                  solicitantePuerta: { stringValue: callerName },
                  _metaTimestamp: { integerValue: String(nowTs) }
                }
              })
            }),
            3500
          ).catch(() => null);
          if (restRes?.ok) {
            centralWritten = true;
            wroteSomething = true;
          }

          const patchPortonUrl = `https://firestore.googleapis.com/v1/projects/${fbConfig.projectId}/databases/(default)/documents/configuracion/porton?updateMask.fieldPaths=comando&updateMask.fieldPaths=timestamp&updateMask.fieldPaths=autorizadoPor&updateMask.fieldPaths=_metaTimestamp`;
          fetchFirestoreWithAuth(patchPortonUrl, fbConfig.apiKey, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              fields: {
                comando: { stringValue: 'ABRIR' },
                timestamp: { integerValue: String(nowTs) },
                autorizadoPor: { stringValue: callerName },
                _metaTimestamp: { integerValue: String(nowTs) }
              }
            })
          }).catch(() => {});
        }
      }

      // Reset automático de puerta a false tras 3.2s para futuras aperturas limpias (igual a SENTINEL LINK)
      setTimeout(() => {
        const resetTs = Date.now();
        try {
          const activeDb = getDb();
          if (activeDb) {
            markLocalWrite('accesos', 'central', resetTs);
            setDoc(
              doc(activeDb, 'accesos', 'central'),
              { puerta: false, _metaTimestamp: resetTs },
              { merge: true }
            ).catch(() => {});
          }
        } catch (_) {}
      }, 3200);

      return { success: wroteSomething };
    } catch (e) {
      return { success: wroteSomething, error: e.message };
    }
  },

  // 3. Cerrar turno de Operador de Central de Alarmas
  closeCentralOperatorSession: async () => {
    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return { success: true };

    const nowTs = Date.now();

    try {
      const db = getDb();
      await ensureFirebaseAuth();
      const sessionId = get().centralSessionId;

      if (sessionId) {
        markLocalWrite('accesos', sessionId, nowTs);
        await setDoc(doc(db, 'accesos', 'central', 'registros', sessionId), {
          estado: 'cerrado',
          timestampCierre: nowTs,
          _metaTimestamp: nowTs
        }, { merge: true }).catch(() => {});
      }

      markLocalWrite('accesos', 'central', nowTs);
      await setDoc(doc(db, 'accesos', 'central'), {
        estado: 'cerrado',
        idInicio: '',
        idRegistro: '',
        nombreBombero: '',
        timestampCierre: nowTs,
        _metaTimestamp: nowTs
      }, { merge: true });

      return { success: true };
    } catch (e) {
      return { success: false, error: e.message };
    }
  },

  // 4A. Confirmación Inicial de Clave (Idéntico a confirmInitialKey en SENTINEL CAD: crea pre-despacho en vivo)
  confirmInitialKeyFromCentral: async ({ key = '10-0', activeCompanies = ['1'], sirenEnabled = true } = {}) => {
    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return { success: true, draftId: `${new Date().getFullYear()}-00001` };

    const user = useAuthStore.getState().user;
    const nowTs = Date.now();
    const year = new Date().getFullYear();

    try {
      const db = getDb();
      await ensureFirebaseAuth();

      const draftId = await generateNextCanonicalDispatchId(db, {
        localDispatches: get().dispatches || [],
        year,
        ensureAuthFn: ensureFirebaseAuth
      });

      // Pre-marcar despacho como notificado/reproducido localmente y en nativo porque somos quien despacha
      notifiedDispatchIds.add(draftId);
      lastDispatchClaves[draftId] = String(key || '10-0').trim();
      markPlayedSoundKey(draftId);
      const isActuallyCentralOp = get().recomputeCentralState();
      set({ fullscreenDispatchId: null });
      get().stopDispatchSound();
      try {
        if (typeof window !== 'undefined') {
          window.AndroidApp?.setCentralOperatorMode?.(Boolean(isActuallyCentralOp));
          window.AndroidApp?.acknowledgeDispatch?.(draftId, false);
          window.webkit?.messageHandlers?.iOSApp?.postMessage?.({
            action: 'setCentralOperatorMode',
            active: Boolean(isActuallyCentralOp)
          });
          window.webkit?.messageHandlers?.iOSApp?.postMessage?.({
            action: 'acknowledgeDispatch',
            dispatchId: draftId,
            declined: false
          });
        }
      } catch (_) {}

      const cuartelLat = useLicenseStore.getState().cuartelLat || -34.637373;
      const cuartelLng = useLicenseStore.getState().cuartelLng || -71.125741;
      const comuna = String(useLicenseStore.getState().comuna || '').toUpperCase();

      const companiasArr = (activeCompanies || [])
        .map((c) => parseInt(String(c).replace(/\D/g, ''), 10))
        .filter((n) => !isNaN(n) && n > 0);

      const preDispatchData = {
        idServicio: draftId,
        clave: String(key || '10-0').trim(),
        subclave: '',
        claveApoyo: '',
        direccion: '',
        referencia: '',
        comuna,
        geo: {
          lat: Number(cuartelLat),
          lng: Number(cuartelLng),
          accuracy: 10,
          origen: 'CENTRAL_DEFECTO'
        },
        alertante: {
          nombre: '',
          telefono: ''
        },
        companias: companiasArr.length > 0 ? companiasArr : [1],
        unidades: {},
        estado: 'pre-despacho',
        createdAt: nowTs,
        h67: null,
        finalizadoAt: null,
        operadorInicial: String(user?.nombreBombero || get().centralOperatorName || 'OPERADOR CENTRAL'),
        operadorFinal: '',
        preinforme: '',
        informeObac: '',
        sirena: Boolean(sirenEnabled),
        visibleMovil: true,
        _metaTimestamp: nowTs
      };

      markLocalWrite('despachos', draftId, nowTs);
      await setDoc(doc(db, 'despachos', draftId), preDispatchData);

      // Si se despacha CON SIRENA, accionar también sirena/control y apertura de portón igual que SENTINEL CAD
      if (sirenEnabled) {
        try {
          markLocalWrite('sirena', 'control', nowTs);
          await setDoc(doc(db, 'sirena', 'control'), {
            estado: 'encendida',
            updatedAt: nowTs,
            ejecutadoPor: String(user?.nombreBombero || get().centralOperatorName || 'OPERADOR CENTRAL'),
            motivo: `DESPACHO ${key}`,
            _metaTimestamp: nowTs
          }, { merge: true });
          markLocalWrite('accesos', 'central', nowTs);
          await setDoc(doc(db, 'accesos', 'central'), {
            puerta: true,
            timestampPuerta: nowTs,
            solicitantePuerta: String(user?.nombreBombero || get().centralOperatorName || 'OPERADOR CENTRAL'),
            _metaTimestamp: nowTs
          }, { merge: true });
        } catch (_) {}
      }

      return { success: true, draftId };
    } catch (e) {
      console.warn('Error en confirmInitialKeyFromCentral:', e);
      return { success: false, error: e.message };
    }
  },

  // 4B. Cancelar Pre-Despacho en configuración (Idéntico a resetDispatch en SENTINEL CAD)
  cancelDraftDispatchFromCentral: async (draftId) => {
    if (!draftId) return;
    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return;

    const user = useAuthStore.getState().user;
    const nowTs = Date.now();
    try {
      const db = getDb();
      await ensureFirebaseAuth();
      markLocalWrite('despachos', String(draftId), nowTs);
      await updateDoc(doc(db, 'despachos', String(draftId)), {
        estado: 'cancelado',
        finalizadoAt: nowTs,
        operadorFinal: String(user?.nombreBombero || get().centralOperatorName || 'OPERADOR CENTRAL'),
        visibleMovil: false,
        _metaTimestamp: nowTs
      });
    } catch (_) {}
  },

  // 4B.2 Actualizar Ubicación y Coordenadas del Pre-Despacho en vivo (Idéntico a setLocation en SENTINEL CAD)
  updateDraftLocationFromCentral: async (draftId, locationText, coords = null, isAlertanteGeo = false) => {
    if (!draftId) return;
    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return;

    const nowTs = Date.now();
    try {
      const db = getDb();
      await ensureFirebaseAuth();
      const cleanLoc = String(locationText || '').trim().toUpperCase();
      const updateObj = {
        direccion: cleanLoc,
        _metaTimestamp: nowTs
      };
      if (coords && typeof coords.lat === 'number' && typeof coords.lng === 'number' && !isNaN(coords.lat) && !isNaN(coords.lng)) {
        updateObj.geo = {
          lat: Number(coords.lat),
          lng: Number(coords.lng),
          accuracy: 10,
          origen: isAlertanteGeo ? 'GPS_ALERTANTE' : 'MANUAL_CAD'
        };
      }
      markLocalWrite('despachos', String(draftId), nowTs);
      await updateDoc(doc(db, 'despachos', String(draftId)), updateObj);
    } catch (_) {}
  },

  // 4C. Despachar Alarma General desde Consola Móvil (Idéntico a confirmDispatch en SENTINEL CAD)
  dispatchFromCentral: async (params = {}) => {
    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return { success: true };

    const clave = params.clave || '10-0';
    const lugar = params.lugar || params.direccion || '';
    const referencia = params.referencia || '';
    const comunaParam = params.comuna || '';
    const claveApoyo = params.claveApoyo || '';
    const callerName = params.callerName || '';
    const callerPhone = params.callerPhone || '';
    const preinforme = params.preinforme || params.detalle || '';
    const sirenEnabled = params.sirenEnabled !== undefined ? Boolean(params.sirenEnabled) : true;
    const coords = params.coords || null;
    const rawAlertanteGeo = params.alertanteGeo || null;
    const activeCompaniesParam = params.activeCompanies || [];
    const selectedVehicles = params.selectedVehicles || params.vehiculosSeleccionados || [];

    const user = useAuthStore.getState().user;
    const nowTs = Date.now();
    const year = new Date().getFullYear();

    try {
      const db = getDb();
      await ensureFirebaseAuth();

      let idServicio = String(params.draftId || params.idServicio || '').trim();
      if (!idServicio) {
        // Generar ID correlativo canónico AAAA-00000
        idServicio = await generateNextCanonicalDispatchId(db, {
          localDispatches: get().dispatches || [],
          year,
          ensureAuthFn: ensureFirebaseAuth
        });
      }

      const unidadesMap = {};
      const companiasSet = new Set(
        (activeCompaniesParam || [])
          .map((c) => parseInt(String(c).replace(/\D/g, ''), 10))
          .filter((n) => !isNaN(n) && n > 0)
      );
      selectedVehicles.forEach((carroId) => {
        unidadesMap[carroId] = {
          idSalida: '',
          solicitudConductorTimestamp: null,
          solicitudPersonalTimestamp: null
        };
        companiasSet.add(resolveCompanyFromVehicleId(carroId));
      });
      if (companiasSet.size === 0) companiasSet.add(1);

      const cuartelLat = useLicenseStore.getState().cuartelLat || -34.637373;
      const cuartelLng = useLicenseStore.getState().cuartelLng || -71.125741;
      const hasExplicitCoords = Boolean(coords?.lat && coords?.lng);
      const finalLat = hasExplicitCoords ? Number(coords.lat) : cuartelLat;
      const finalLng = hasExplicitCoords ? Number(coords.lng) : cuartelLng;

      const cleanAlertanteGeo =
        rawAlertanteGeo &&
        Number.isFinite(Number(rawAlertanteGeo.lat)) &&
        Number.isFinite(Number(rawAlertanteGeo.lng)) &&
        Number(rawAlertanteGeo.lat) !== 0 &&
        Number(rawAlertanteGeo.lng) !== 0
          ? {
              lat: Number(rawAlertanteGeo.lat),
              lng: Number(rawAlertanteGeo.lng),
              accuracy: Number(rawAlertanteGeo.accuracy || 10),
              address: String(rawAlertanteGeo.address || rawAlertanteGeo.direccion || '').trim().toUpperCase(),
              telefono: String(rawAlertanteGeo.telefono || callerPhone || '').trim(),
              timestamp: Number(rawAlertanteGeo.timestamp || nowTs)
            }
          : null;

      const dispatchPayload = {
        idServicio,
        clave: String(clave || '10-0').trim(),
        subclave: '',
        claveApoyo: String(claveApoyo || '').trim().toUpperCase(),
        direccion: String(lugar || 'DIRECCIÓN PENDIENTE').trim().toUpperCase(),
        referencia: String(referencia || '').trim().toUpperCase(),
        comuna: String(comunaParam || useLicenseStore.getState().comuna || '').trim().toUpperCase(),
        geo: {
          lat: finalLat,
          lng: finalLng,
          accuracy: 10,
          origen: hasExplicitCoords ? 'MANUAL_CAD' : 'CENTRAL_DEFECTO'
        },
        alertante: {
          nombre: String(callerName || '').trim().toUpperCase(),
          telefono: String(callerPhone || '').trim(),
          ...(cleanAlertanteGeo ? { geo: cleanAlertanteGeo } : {})
        },
        ...(cleanAlertanteGeo ? { alertanteGeo: cleanAlertanteGeo } : {}),
        companias: Array.from(companiasSet),
        unidades: unidadesMap,
        estado: 'activa',
        createdAt: nowTs,
        h67: null,
        finalizadoAt: null,
        operadorInicial: String(user?.nombreBombero || get().centralOperatorName || 'OPERADOR CENTRAL'),
        operadorFinal: '',
        preinforme: String(preinforme || '').trim(),
        informeObac: '',
        sirena: sirenEnabled,
        visibleMovil: true,
        _metaTimestamp: nowTs
      };

      markLocalWrite('despachos', idServicio, nowTs);

      // Pre-marcar despacho como notificado/reproducido localmente y en nativo porque somos quien despacha
      notifiedDispatchIds.add(idServicio);
      lastDispatchClaves[idServicio] = String(clave || '10-0').trim();
      markPlayedSoundKey(idServicio);
      markPlayedSoundKey(`${idServicio}_${String(clave || '10-0').trim()}`);
      const isActuallyCentralOp = get().recomputeCentralState();
      set({ fullscreenDispatchId: null });
      get().stopDispatchSound();
      try {
        if (typeof window !== 'undefined') {
          window.AndroidApp?.setCentralOperatorMode?.(Boolean(isActuallyCentralOp));
          window.AndroidApp?.acknowledgeDispatch?.(idServicio, false);
          window.webkit?.messageHandlers?.iOSApp?.postMessage?.({
            action: 'setCentralOperatorMode',
            active: Boolean(isActuallyCentralOp)
          });
          window.webkit?.messageHandlers?.iOSApp?.postMessage?.({
            action: 'acknowledgeDispatch',
            dispatchId: idServicio,
            declined: false
          });
        }
      } catch (_) {}

      await setDoc(doc(db, 'despachos', idServicio), dispatchPayload, { merge: true });

      // Marcar carros en servicio
      await Promise.all(
        selectedVehicles.map((carroId) => {
          markLocalWrite('vehiculos', carroId, nowTs);
          return setDoc(doc(db, 'vehiculos', carroId), {
            enServicio: idServicio,
            _metaTimestamp: nowTs
          }, { merge: true }).catch(() => {});
        })
      );

      return { success: true, idServicio };
    } catch (e) {
      console.warn('Error en dispatchFromCentral:', e);
      return { success: false, error: e.message };
    }
  },

  // 5. Marcar Orden o Alerta como VISTO (`conforme`)
  registerConforme: async (alertOrId, optionalRadial) => {
    const user = useAuthStore.getState().user;
    const myRadial = String(optionalRadial || user?.idRadial || '').trim().toUpperCase();
    if (!myRadial) return;

    const targetId = typeof alertOrId === 'object' ? (alertOrId?.idAlerta || alertOrId?.id) : String(alertOrId || '');
    if (!targetId) return;

    const existingObj =
      (typeof alertOrId === 'object' ? alertOrId : null) ||
      get().allAlertsList.find(a => a.idAlerta === targetId || a.id === targetId);

    const currentList = String(existingObj?.conforme || '')
      .split(',')
      .map(s => s.trim().toUpperCase())
      .filter(Boolean);

    if (currentList.includes(myRadial)) return;
    currentList.push(myRadial);
    const newConforme = currentList.join(',');
    const nowTs = Date.now();
    markLocalWrite('alertas', targetId, nowTs);

    // Optimistic update
    const nextAll = get().allAlertsList.map(a =>
      (a.idAlerta === targetId || a.id === targetId) ? { ...a, conforme: newConforme, _metaTimestamp: nowTs } : a
    );
    set(splitAlertsAndOrders(nextAll, get().activeChatAlert));

    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return;

    try {
      const db = getDb();
      await ensureFirebaseAuth();
      await updateDoc(doc(db, 'alertas', targetId), {
        conforme: newConforme,
        _metaTimestamp: nowTs
      });
    } catch (e) {}
  },

  // 6. Fijar / Desfijar Alerta (`fijar`)
  toggleAlertPin: async (alertOrId) => {
    const user = useAuthStore.getState().user;
    const myRadial = String(user?.idRadial || '1').trim().toUpperCase();

    const targetId = typeof alertOrId === 'object' ? (alertOrId?.idAlerta || alertOrId?.id) : String(alertOrId || '');
    if (!targetId) return;

    const existingObj =
      (typeof alertOrId === 'object' ? alertOrId : null) ||
      get().allAlertsList.find(a => a.idAlerta === targetId || a.id === targetId);

    const currentPins = String(existingObj?.fijar || '')
      .split(',')
      .map(s => s.trim().toUpperCase())
      .filter(Boolean);

    let updatedPins;
    if (currentPins.includes(myRadial)) {
      updatedPins = currentPins.filter(r => r !== myRadial);
    } else {
      updatedPins = [...currentPins, myRadial];
    }
    const newFijar = updatedPins.join(',');
    const newFijada = updatedPins.length > 0;
    const nowTs = Date.now();
    markLocalWrite('alertas', targetId, nowTs);

    const nextAll = get().allAlertsList.map(a =>
      (a.idAlerta === targetId || a.id === targetId) ? { ...a, fijar: newFijar, fijada: newFijada, _metaTimestamp: nowTs } : a
    );
    set(splitAlertsAndOrders(nextAll, get().activeChatAlert));

    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return;

    try {
      const db = getDb();
      await ensureFirebaseAuth();
      await updateDoc(doc(db, 'alertas', targetId), {
        fijar: newFijar,
        _metaTimestamp: nowTs
      });
    } catch (e) {}
  },

  // 7. Enviar mensaje en Canal de Chat (`duracion == 'C'`, idéntico a alertas.html línea 1916 y TabScreens.kt)
  sendChatMessage: async (alertOrMsg, optionalMsg) => {
    const isFirstArgString = typeof alertOrMsg === 'string';
    const messageText = isFirstArgString ? alertOrMsg : optionalMsg;
    const cleanMsg = String(messageText || '').trim().replace(/\|/g, ' ');
    if (!cleanMsg) return;

    const targetAlert = (!isFirstArgString && alertOrMsg)
      ? alertOrMsg
      : (get().chatAlert || get().activeChatAlert);

    // Si es un canal de chat SCI (`SCI-AAAA-000X` o `SCI_CHAT_*`), enviarlo exclusivamente a `sisbom-central` sin tocar `alertas`
    if (targetAlert && (targetAlert.isSciCentralChat || isResidualSciAlertDoc(targetAlert))) {
      await get().sendSciChatMessage(cleanMsg, targetAlert);
      return;
    }

    const user = useAuthStore.getState().user;
    // Se guarda siempre el idRegistro personal permanente del bombero
    const senderId = String(user?.idRegistro || user?.idRadial || '1').trim();
    const senderRadial = String(user?.idRadial || '1').trim();
    const dateStr = getLocalDateString();
    const timeStr = getLocalTimeString();
    // Formato canónico de alertas.html y TabScreens.kt: DD-MM-YYYY/HH:MM/idRegistro: mensaje |
    const formattedEntry = `${dateStr}/${timeStr}/${senderId}: ${cleanMsg} |`;

    // Si aún no existe un canal de chat en Firestore, crearlo automáticamente
    if (!targetAlert?.idAlerta) {
      await get().publishAlert({
        razon: 'CANAL OPERACIONAL',
        mensaje: cleanMsg,
        duracion: 'C',
        aQuien: 'TC'
      });
      return;
    }

    const existingMsg = String(targetAlert.mensajeAlerta || '').trim();
    let finalChatString;
    if (!existingMsg) {
      finalChatString = formattedEntry;
    } else if (existingMsg.endsWith('|')) {
      finalChatString = `${existingMsg} ${formattedEntry}`;
    } else {
      finalChatString = `${existingMsg} | ${formattedEntry}`;
    }

    const nowTs = Date.now();
    markLocalWrite('alertas', targetAlert.idAlerta, nowTs);

    const nextAll = get().allAlertsList.map(a =>
      a.idAlerta === targetAlert.idAlerta
        ? { ...a, mensajeAlerta: finalChatString, conforme: senderRadial, _metaTimestamp: nowTs }
        : a
    );
    set(splitAlertsAndOrders(nextAll, {
      ...targetAlert,
      mensajeAlerta: finalChatString,
      conforme: senderRadial,
      _metaTimestamp: nowTs
    }));

    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return;

    try {
      const db = getDb();
      await ensureFirebaseAuth();
      await updateDoc(doc(db, 'alertas', targetAlert.idAlerta), {
        mensajeAlerta: finalChatString,
        conforme: senderRadial,
        _metaTimestamp: nowTs
      });
    } catch (e) {
      console.warn('Error enviando mensaje de chat:', e);
    }
  },

  // 8. Publicar Orden del Día (Comandancia)
  publishOrder: async (params = {}) => {
    const user = useAuthStore.getState().user;
    const nowTs = Date.now();
    const idAlerta = String(nowTs);
    const myRadial = String(user?.idRadial || '1').trim();
    const numero = String(params.numeroOrden || params.numero || '').trim();
    const fecha = String(params.fechaOrden || params.fecha || getLocalDateString()).trim();
    const cuerpo = String(params.cuerpo || params.mensaje || '').trim();
    const firmaNombre = String(params.firmaNombre || user?.nombreBombero || 'COMANDANTE').toUpperCase();
    const firmaCargo = String(params.firmaCargo || user?.cargo || 'COMANDANTE').toUpperCase();

    const newOrder = normalizeAlertDoc(idAlerta, {
      idAlerta,
      tipo: 'orden',
      gradoAlerta: '1',
      aQuienAlerta: 'TC',
      quienAlerta: firmaNombre,
      razonAlerta: `ORDEN DEL DÍA N° ${numero}`,
      mensajeAlerta: cuerpo,
      fechaAlerta: fecha,
      horaAlerta: getLocalTimeString(),
      duracion: 'i',
      conforme: myRadial,
      fijar: '',
      numeroOrden: numero,
      fechaOrden: fecha,
      firmaNombre,
      firmaCargo,
      timestamp: nowTs,
      _metaTimestamp: nowTs
    });

    markLocalWrite('alertas', idAlerta, nowTs);

    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) {
      const nextAll = [newOrder, ...get().allAlertsList];
      set(splitAlertsAndOrders(nextAll, get().activeChatAlert));
      return { success: true };
    }

    try {
      const db = getDb();
      await ensureFirebaseAuth();
      await setDoc(doc(db, 'alertas', idAlerta), newOrder);
      return { success: true };
    } catch (e) {
      return { success: false, error: e.message };
    }
  },

  // 9. Publicar Alerta / Canal de Chat (100% compatible con alertas.html y TabScreens.kt)
  publishAlert: async (params = {}) => {
    const user = useAuthStore.getState().user;
    const nowTs = Date.now();

    // Calcular siguiente ID correlativo numérico igual que alertas.html (líneas 1226-1231)
    let maxId = 0;
    (get().allAlertsList || []).forEach((a) => {
      const num = parseInt(String(a.idAlerta || ''), 10);
      if (!isNaN(num) && num < 1000000000 && num > maxId) {
        maxId = num;
      }
    });
    const idAlerta = String(maxId > 0 ? maxId + 1 : 1);

    const myRadial = String(user?.idRadial || '1').trim();
    const myReg = String(user?.idRegistro || user?.idRadial || '1').trim();
    const dateStr = getLocalDateString();
    const timeStr = getLocalTimeString();

    const duracion = String(params.duracion || 'i').trim();
    const isChat = duracion.toUpperCase() === 'C';
    const rawMsg = String(params.mensaje || params.cuerpo || '').trim().replace(/\|/g, ' ');
    const initialMsg = isChat
      ? `${dateStr}/${timeStr}/${myReg}: ${rawMsg} |`
      : rawMsg;

    // Resolver destinatarios: aunque se busque por idRadial, en aQuienAlerta se guarda siempre idRegistro
    const rawAQuien = String(params.aQuien || params.aQuienAlerta || 'TC').trim();
    let finalAQuien = 'TC';
    if (params.exactIdRegistro && rawAQuien && rawAQuien.toUpperCase() !== 'TC') {
      finalAQuien = rawAQuien;
    } else if (rawAQuien.toUpperCase() !== 'TC' && rawAQuien !== '') {
      const personnel = get().firefightersList || [];
      const tokens = rawAQuien
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean);
      const resolvedRegs = [];
      tokens.forEach((tok) => {
        // Primero verificar si ya es un idRegistro válido
        const byReg = personnel.find((p) => String(p.idRegistro).trim() === tok);
        if (byReg && byReg.idRegistro) {
          const r = String(byReg.idRegistro).trim();
          if (!resolvedRegs.includes(r)) resolvedRegs.push(r);
          return;
        }
        // Si fue ingresado como idRadial, resolver al idRegistro permanente del bombero
        const byRad = personnel.find(
          (p) =>
            String(p.idRadial).trim().toUpperCase() === tok.toUpperCase() ||
            parseInt(String(p.idRadial).trim(), 10) === parseInt(tok, 10)
        );
        if (byRad && byRad.idRegistro) {
          const r = String(byRad.idRegistro).trim();
          if (!resolvedRegs.includes(r)) resolvedRegs.push(r);
        } else if (!resolvedRegs.includes(tok)) {
          resolvedRegs.push(tok);
        }
      });
      finalAQuien = resolvedRegs.length > 0 ? resolvedRegs.join(',') : 'TC';
    }

    const defaultQuien = `${user?.cargo || 'Voluntario'} - ${user?.nombreBombero || 'Comandancia'}`;
    const quienAlerta = String(params.creadoPor || params.quienAlerta || defaultQuien).trim();
    const razonAlerta = String(params.razon || params.razonAlerta || 'COMUNICADO OFICIAL')
      .toUpperCase()
      .trim();
    const gradoAlerta = String(params.grado || params.gradoAlerta || '1').trim();

    // Payload exacto en colección `alertas` de Firestore (alertas.html líneas 1242-1253)
    const firestorePayload = {
      idAlerta,
      fechaAlerta: `'${dateStr}`,
      horaAlerta: `'${timeStr}`,
      razonAlerta,
      mensajeAlerta: initialMsg,
      quienAlerta,
      gradoAlerta,
      duracion: isChat || duracion === 'i' ? duracion : `'${duracion}`,
      aQuienAlerta: finalAQuien,
      conforme: isChat ? myRadial : '',
      fijar: '',
      _metaTimestamp: nowTs
    };

    const newAlert = normalizeAlertDoc(idAlerta, {
      ...firestorePayload,
      timestamp: nowTs,
      _metaTimestamp: nowTs
    });

    markLocalWrite('alertas', idAlerta, nowTs);

    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) {
      const nextAll = [newAlert, ...get().allAlertsList];
      set(splitAlertsAndOrders(nextAll, isChat ? newAlert : get().activeChatAlert));
      return { success: true, alert: newAlert };
    }

    try {
      const db = getDb();
      await ensureFirebaseAuth();
      await setDoc(doc(db, 'alertas', idAlerta), firestorePayload);
      return { success: true, alert: newAlert };
    } catch (e) {
      return { success: false, error: e.message };
    }
  },

  // 10. Eliminar Orden o Alerta (Comandancia / Autor: duracion = "" igual que alertas.html línea 2011)
  deleteAlert: async (alertId) => {
    if (!alertId) return;
    const targetId = String(alertId);
    const nowTs = Date.now();
    markLocalWrite('alertas', targetId, nowTs);

    const nextAll = get().allAlertsList.filter(a => a.idAlerta !== targetId && a.id !== targetId);
    set(splitAlertsAndOrders(nextAll, get().activeChatAlert));

    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return { success: true };

    try {
      const db = getDb();
      await ensureFirebaseAuth();
      await updateDoc(doc(db, 'alertas', targetId), {
        duracion: '',
        _metaTimestamp: nowTs
      }).catch(async () => {
        await deleteDoc(doc(db, 'alertas', targetId));
      });
      return { success: true };
    } catch (e) {
      return { success: false, error: e.message };
    }
  },

  // 11. Responder a Solicitud de Apoyo Inter-Cuerpos (`SCI-AAAA-000X`) exclusivamente en `sisbom-central`
  respondToInterCorpsAlert: async (decision = 'CONFIRMADA', targetAlert = null) => {
    const cleanDecision = String(decision || 'CONFIRMADA').trim().toUpperCase();
    const user = useAuthStore.getState().user;
    if (!hasCommanderPrivileges(user)) {
      return { success: false, error: 'Solo el Comandante o Modo Admin puede responder solicitudes SCI.' };
    }
    const licState = useLicenseStore.getState();
    const ownLicKey = String(licState.licenseKey || '').trim().toUpperCase();
    const shortCmdName = formatShortPersonName(user?.nombreBombero || user?.nombre || 'COMANDANTE');
    const ownCorpShort = String(licState.nombreMostrar || licState.clientName || ownLicKey)
      .replace(/^CUERPO DE BOMBEROS DE\s+/i, '')
      .trim()
      .toUpperCase();
    const radialTag = String(user?.idRadial || '1').trim() || '1';
    const commanderLabel = `[${radialTag}] ${shortCmdName}${ownCorpShort ? ` - ${ownCorpShort}` : ''}`;
    const nowTs = Date.now();

    set({ isRespondingInterCorps: true });
    get().stopDispatchSound();
    get().clearUrgentBanner();

    try {
      await ensureFirebaseAuth();
      const authInst = getAuthInstance();
      let commanderToken = '';
      try {
        if (authInst?.currentUser) {
          commanderToken = await authInst.currentUser.getIdToken();
        }
      } catch (_) {}

      const cachedCreds = resolveCachedUserAuthCredentials();
      const isGrantingAccess =
        cleanDecision === 'CONFIRMADA' ||
        cleanDecision === 'APOYO_OFRECIDO' ||
        cleanDecision === 'DESPACHADA';

      const commanderAuth = isGrantingAccess && cachedCreds?.email && cachedCreds?.password
        ? {
            email: cachedCreds.email,
            password: cachedCreds.password,
            idRegistro: String(user?.idRegistro || '1'),
            idRadial: '1'
          }
        : null;

      // Filtrar unidades disponibles (0-9) de nuestra flota para compartir con SENTINEL SCI
      const allVehicles = get().vehiculosList || get().vehiclesList || [];
      const activeDisps = get().dispatchesList || [];
      const availableUnits = isGrantingAccess
        ? allVehicles
            .filter((v) => {
              const uName = String(v.idCarro || v.id || '').trim().toUpperCase();
              if (!uName || uName === 'INIT') return false;
              const info = resolveCoreUnitStatusInfo(v, activeDisps, allVehicles);
              return info.isAvailable;
            })
            .map((v) => ({
              id: String(v.idCarro || v.id).trim().toUpperCase(),
              idCarro: String(v.idCarro || v.id).trim().toUpperCase(),
              unidad: String(v.idCarro || v.id).trim().toUpperCase(),
              tipo: String(v.tipo || 'BOMBA').toUpperCase(),
              compania: Number(v.compania || resolveCompanyFromVehicleId(v.idCarro || v.id)) || 1,
              estado: '0-9',
              conductor: String(v.conductor || ''),
              capacidadAgua: String(v.capacidadAgua || '')
            }))
        : [];

      const currentReq = targetAlert || get().sciRequestsList?.[0] || get().interCorpsAlert || {};
      const ownCorpFullTitle = String(
        licState.clientName || licState.nombreMostrar || ownLicKey
      ).trim().toUpperCase();
      const requestingLicKey = String(currentReq.licenciaSolicitante || '').trim().toUpperCase();
      const requestingCorpTitle = String(
        currentReq.cuerpoSolicitante ||
          currentReq.nombreCuerpoSolicitante ||
          ownCorpFullTitle
      ).trim().toUpperCase();

      const rawCar = String(currentReq.idCarroSolicitado || '').trim().toUpperCase();
      const isUnitRequest =
        String(currentReq.tipoSolicitud || '').trim().toUpperCase() === 'SOLICITUD_UNIDAD' &&
        Boolean(rawCar) &&
        !['ALERTA GENERAL', 'CUALQUIERA', 'TODOS', 'NINGUNO'].includes(rawCar);
      const unitIdRequested = isUnitRequest ? rawCar : (rawCar || 'ALERTA GENERAL');

      const svcIdForInc = String(
        currentReq.idServicioOrigen || currentReq.idServicio || 'SCI-ACTIVO'
      ).trim();
      const matchedActiveInc = Object.values(latestCentralSciIncidentsMap || {}).find(
        (inc) =>
          inc &&
          inc.activo !== false &&
          String(inc.licenciaSolicitante || '').trim().toUpperCase() ===
            (requestingLicKey || ownLicKey)
      );
      const idIncidenteSci =
        String(currentReq.idIncidenteSci || matchedActiveInc?.idIncidenteSci || '').trim() ||
        buildCanonicalCentralSciIncidentId(requestingLicKey || ownLicKey, svcIdForInc);

      // Resolver SIEMPRE el mismo idSolicitud existente en `sci_incidentes/{idIncidenteSci}/solicitudes` (PROHIBIDO crear uno nuevo al confirmar apoyo)
      let idSolicitud = String(
        currentReq.idSolicitud || currentReq.id || currentReq.idAlerta || ''
      )
        .trim()
        .toUpperCase();

      if (idIncidenteSci && latestSciIncidentSolicitudesByInc[idIncidenteSci]) {
        const incMap = latestSciIncidentSolicitudesByInc[idIncidenteSci];
        if (!idSolicitud || !incMap[idSolicitud]) {
          const matchingExisting = Object.values(incMap).find((s) => {
            if (!s || typeof s !== 'object') return false;
            const dest = String(s.licenciaDestino || s.licenciaApoyo || '').trim().toUpperCase();
            if (dest !== ownLicKey) return false;
            if (s.sciActivo === false || String(s.estado || '').toUpperCase() === 'CANCELADA') return false;
            if (isUnitRequest) {
              return String(s.idCarroSolicitado || '').trim().toUpperCase() === unitIdRequested;
            }
            return true;
          });
          if (matchingExisting) {
            idSolicitud = String(matchingExisting.idSolicitud || matchingExisting.id || '').trim().toUpperCase();
          }
        }
      }

      const centralDb = getCentralDb();
      if (!idSolicitud && centralDb && idIncidenteSci) {
        try {
          const sSnap = await getDocs(collection(centralDb, 'sci_incidentes', idIncidenteSci, 'solicitudes'));
          sSnap.forEach((d) => {
            if (idSolicitud || d.id === 'init') return;
            const sData = d.data() || {};
            const dest = String(sData.licenciaDestino || sData.licenciaApoyo || '').trim().toUpperCase();
            if (dest === ownLicKey && sData.sciActivo !== false && String(sData.estado || '').toUpperCase() !== 'CANCELADA') {
              idSolicitud = String(sData.idSolicitud || d.id).trim().toUpperCase();
            }
          });
        } catch (_) {}
      }

      if (!idSolicitud) {
        return { success: false, error: 'No se encontró la solicitud SCI activa para confirmar' };
      }

      const effectiveDecision = cleanDecision === 'APOYO_OFRECIDO' ? 'CONFIRMADA' : cleanDecision;

      const existingChatMsg = String(
        currentReq.mensajesChat ||
          get().sciChatMessagesRaw ||
          currentReq.chatDirecto?.mensajeAlerta ||
          ''
      ).trim();

      let autoReplyText = '';
      if (isUnitRequest) {
        autoReplyText = isGrantingAccess
          ? `Unidad ${unitIdRequested} AUTORIZADA Y DESPACHADA en apoyo por ${commanderLabel} (${idSolicitud}).`
          : `Solicitud de unidad ${unitIdRequested} (${idSolicitud}) marcada como ${effectiveDecision} por ${commanderLabel}.`;
      } else if (effectiveDecision === 'CONFIRMADA') {
        autoReplyText = `Apoyo confirmado por ${ownCorpFullTitle} (${availableUnits.length} unidades 0-9 a disposición). Folio ${idSolicitud}.`;
      } else if (effectiveDecision === 'EN_ESPERA') {
        autoReplyText = `Solicitud ${idSolicitud} en espera de evaluación por Comandancia de ${ownCorpFullTitle}.`;
      } else if (effectiveDecision === 'RECHAZADA') {
        autoReplyText = `Solicitud ${idSolicitud} sin disponibilidad actual en ${ownCorpFullTitle}.`;
      }

      const updatedChatStr = autoReplyText
        ? appendCanonicalChatMessage(
            existingChatMsg,
            formatCanonicalChatEntry({
              senderId: commanderLabel,
              texto: autoReplyText
            })
          )
        : existingChatMsg;

      const directChatId = `SCI_CHAT_${ownLicKey}`;

      const canonicalReqDoc = {
        ...buildCanonicalCentralSciRequestDoc({
          ...currentReq,
          idSolicitud,
          sciActivo: true,
          soloComandante: true,
          idRadialDestinatario: '1',
          tipoSolicitud: isUnitRequest ? 'SOLICITUD_UNIDAD' : 'ALERTA_PREVENTIVA',
          estado: effectiveDecision,
          licenciaSolicitante: requestingLicKey || ownLicKey,
          cuerpoSolicitante: requestingCorpTitle,
          comandanteSolicitante: currentReq.comandanteSolicitante || currentReq.quienAlerta || commanderLabel,
          licenciaApoyo: ownLicKey,
          cuerpoApoyo: ownCorpFullTitle,
          idServicioOrigen: svcIdForInc,
          claveEmergencia: currentReq.claveEmergencia || '10-12',
          direccionEmergencia: currentReq.direccionEmergencia || '',
          idCarroSolicitado: unitIdRequested,
          sectorDestino: currentReq.sectorDestino || '',
          instruccionTactica: currentReq.instruccionTactica || '',
          dirigirse: currentReq.dirigirse || null,
          confirmadoPor: commanderLabel,
          confirmadoAt: nowTs,
          unidadesDisponibles: availableUnits,
          commanderToken: isGrantingAccess ? commanderToken : '',
          commanderAuth: isGrantingAccess ? commanderAuth : null,
          mensajesChat: updatedChatStr,
          createdAt: currentReq.createdAt || currentReq.timestamp || nowTs,
          updatedAt: nowTs
        }),
        idIncidenteSci,
        licenciaDestino: ownLicKey,
        cuerpoDestino: ownCorpFullTitle
      };

      // Actualizar optimistamente en memoria SOBRE EL MISMO `idSolicitud` en `sci_incidentes/{idIncidenteSci}/solicitudes`
      if (idIncidenteSci) {
        latestSciIncidentSolicitudesByInc = {
          ...latestSciIncidentSolicitudesByInc,
          [idIncidenteSci]: {
            ...(latestSciIncidentSolicitudesByInc[idIncidenteSci] || {}),
            [idSolicitud]: canonicalReqDoc
          }
        };
      }
      get().recomputeSciState();

      if (centralDb && ownLicKey && idIncidenteSci) {
        // Actualizar el MISMO documento `sisbom-central/sci_incidentes/{idIncidenteSci}/solicitudes/{idSolicitud}` y `mensajes/{ownLicKey}`
        await setDoc(
          doc(centralDb, 'sci_incidentes', idIncidenteSci, 'solicitudes', idSolicitud),
          canonicalReqDoc,
          { merge: true }
        ).catch(() => {});

        await setDoc(
          doc(centralDb, 'sci_incidentes', idIncidenteSci, 'mensajes', ownLicKey),
          {
            idCanal: ownLicKey,
            idAlerta: directChatId,
            idIncidenteSci,
            licenciaSolicitante: requestingLicKey || ownLicKey,
            licenciaDestino: ownLicKey,
            cuerpoDestino: ownCorpFullTitle,
            razonAlerta: requestingCorpTitle,
            mensajeAlerta: updatedChatStr,
            mensajesChat: updatedChatStr,
            quienAlerta: commanderLabel,
            soloComandante: true,
            idRadialDestinatario: '1',
            updatedAt: nowTs,
            _metaTimestamp: nowTs
          },
          { merge: true }
        ).catch(() => {});
      }

      // =======================================================================
      // FLUJO 1: ALERTA DE ACTIVACIÓN SCI (ALERTA_PREVENTIVA / APOYO_OFRECIDO)
      // Cuando el Comandante acepta poner a su Cuerpo en alerta para apoyar al
      // SCI solicitante, publicar automáticamente ALERTA GRADO 3 a Todas las Compañías (TC)
      // =======================================================================
      if (
        !isUnitRequest &&
        (cleanDecision === 'CONFIRMADA' || cleanDecision === 'APOYO_OFRECIDO')
      ) {
        try {
          await get().publishAlert({
            gradoAlerta: '3',
            aQuienAlerta: 'TC',
            duracion: 'i',
            quienAlerta: `COMANDANTE - ${commanderLabel}`,
            razonAlerta: `ALERTA SCI ACTIVADA — APOYO A ${requestingCorpTitle}`,
            mensaje: `🚨 ALERTA PREVENTIVA SCI GRADO 3: El Comandante ha activado el estado de alerta para todas las compañías por emergencia Clave ${currentReq.claveEmergencia || '10-12'} en jurisdicción de ${requestingCorpTitle} (${currentReq.direccionEmergencia || 'Sin dirección'}). Todo el personal atento a posible despacho de unidades en apoyo.`
          });
        } catch (errAlertTc) {
          console.warn('Error publicando Alerta Grado 3 a Todas las Compañías (TC):', errAlertTc);
        }
      }

      // =======================================================================
      // FLUJO 2: SOLICITUD DE UNIDAD (SOLICITUD_UNIDAD) + ACEPTAR APOYO
      // - 1ª Unidad aceptada: Crea despacho 10-12 activo con sirena: true y GPS SCI
      // - 2ª Unidad en adelante: Agrega unidad al despacho 10-12 activo y activa 6-6
      // - Silencia al Comandante (idRadial == "1") y al Operador en accesos/central
      // - Envía Alerta Grado 3 al bombero en turno en accesos/central usando su idRegistro
      // =======================================================================
      if (
        isUnitRequest &&
        unitIdRequested &&
        (cleanDecision === 'CONFIRMADA' || cleanDecision === 'DESPACHADA')
      ) {
        try {
          const db = getDb();
          if (db) {
            const activeDispsNow = get().dispatchesList || [];
            const cleanReqCorpShort = String(currentReq.comunaSolicitante || requestingCorpTitle)
              .replace(/^CUERPO DE BOMBEROS DE\s+/i, '')
              .trim()
              .toUpperCase();

            const existingDispatch = activeDispsNow.find((d) => {
              if (!d || String(d.clave || '').trim() !== '10-12') return false;
              if (idIncidenteSci && String(d.idIncidenteSci || '').trim() === idIncidenteSci) {
                return true;
              }
              const dComuna = String(d.comuna || '').trim().toUpperCase();
              const dRef = String(d.referencia || '').trim().toUpperCase();
              const dDir = String(d.direccion || '').trim().toUpperCase();
              return (
                (cleanReqCorpShort &&
                  (dComuna.includes(cleanReqCorpShort) ||
                    dRef.includes(cleanReqCorpShort) ||
                    dDir.includes(cleanReqCorpShort))) ||
                (requestingCorpTitle &&
                  (dRef.includes(requestingCorpTitle) || dDir.includes(requestingCorpTitle)))
              );
            });

            let targetServiceId = existingDispatch
              ? String(existingDispatch.idServicio || existingDispatch.id).trim()
              : '';

            if (!targetServiceId) {
              const year = new Date().getFullYear();
              targetServiceId = await generateNextCanonicalDispatchId(db, {
                localDispatches: activeDispsNow,
                year,
                ensureAuthFn: ensureFirebaseAuth
              });
            }

            // 1. Silenciar al propio Comandante (idRadial == "1") y al Bombero en turno en accesos/central
            notifiedDispatchIds.add(targetServiceId);
            lastDispatchClaves[targetServiceId] = '10-12';
            lastUnitPersonalReq[`${targetServiceId}_${unitIdRequested}`] = nowTs;
            markPlayedSoundKey(targetServiceId);
            markPlayedSoundKey(`${targetServiceId}_10-12`);
            set({ fullscreenDispatchId: null });
            get().stopDispatchSound();
            try {
              if (typeof window !== 'undefined') {
                window.AndroidApp?.acknowledgeDispatch?.(targetServiceId, false);
                window.webkit?.messageHandlers?.iOSApp?.postMessage?.({
                  action: 'acknowledgeDispatch',
                  dispatchId: targetServiceId,
                  declined: false
                });
              }
            } catch (_) {}

            if (!existingDispatch) {
              // CASO A — 1ª Unidad aceptada para este SCI (No existe aún despacho 10-12 activo)
              const rawGeo =
                currentReq.dirigirse?.lat && (currentReq.dirigirse?.lng ?? currentReq.dirigirse?.long)
                  ? currentReq.dirigirse
                  : currentReq.geoEmergencia ||
                    currentReq.geo ||
                    matchedActiveInc?.geoEmergencia ||
                    matchedActiveInc?.geo ||
                    null;
              const geoPayload =
                rawGeo &&
                !isNaN(Number(rawGeo.lat)) &&
                !isNaN(Number(rawGeo.lng ?? rawGeo.long))
                  ? {
                      lat: Number(rawGeo.lat),
                      lng: Number(rawGeo.lng ?? rawGeo.long),
                      accuracy: 10,
                      origen: 'CENTRAL'
                    }
                  : null;
              const compNum = Number(resolveCompanyFromVehicleId(unitIdRequested)) || 1;
              const canonicalDispatch = {
                idServicio: targetServiceId,
                idIncidenteSci,
                clave: '10-12',
                subclave: '',
                claveApoyo: String(currentReq.claveEmergencia || '10-0').trim().toUpperCase(),
                direccion: String(
                  currentReq.direccionEmergencia || `APOYO A ${requestingCorpTitle}`
                )
                  .trim()
                  .toUpperCase(),
                referencia: currentReq.sectorDestino
                  ? `SECTOR: ${currentReq.sectorDestino}`
                  : `APOYO SCI A ${requestingCorpTitle}`,
                comuna: String(currentReq.comunaSolicitante || requestingCorpTitle)
                  .replace(/^CUERPO DE BOMBEROS DE\s+/i, '')
                  .trim()
                  .toUpperCase(),
                geo: geoPayload,
                alertante: {
                  nombre: `SCI ${requestingCorpTitle} (${currentReq.comandanteSolicitante || 'CI'})`,
                  telefono: ''
                },
                companias: [compNum],
                unidades: {
                  [unitIdRequested]: {
                    idSalida: '',
                    ...(currentReq.dirigirse ? { dirigirse: currentReq.dirigirse } : {})
                  }
                },
                estado: 'activa',
                createdAt: nowTs,
                h67: null,
                finalizadoAt: null,
                operadorInicial: commanderLabel,
                operadorFinal: '',
                preinforme: [
                  `APOYO SOLICITADO POR: ${requestingCorpTitle}`,
                  `CLAVE ORIGEN: ${currentReq.claveEmergencia || '10-0'}`,
                  `UNIDAD DESPACHADA POR COMANDANTE: ${unitIdRequested}`,
                  currentReq.sectorDestino ? `SECTOR ASIGNADO: ${currentReq.sectorDestino}` : '',
                  currentReq.instruccionTactica
                    ? `INSTRUCCIÓN TÁCTICA: ${currentReq.instruccionTactica}`
                    : ''
                ]
                  .filter(Boolean)
                  .join('\n'),
                informeObac: '',
                sirena: true,
                visibleMovil: true,
                pushSent: false,
                _metaTimestamp: nowTs
              };
              markLocalWrite('despachos', targetServiceId, nowTs);
              await setDoc(doc(db, 'despachos', targetServiceId), canonicalDispatch, {
                merge: true
              });
              markLocalWrite('vehiculos', unitIdRequested, nowTs);
              await setDoc(
                doc(db, 'vehiculos', unitIdRequested),
                { enServicio: targetServiceId, _metaTimestamp: nowTs },
                { merge: true }
              );
            } else {
              // CASO B — 2ª Unidad (o siguiente) aceptada para el mismo incidente SCI (Ya existe despacho 10-12 activo)
              const compNum = Number(resolveCompanyFromVehicleId(unitIdRequested)) || 1;
              const mergedCompanias = Array.from(
                new Set([...(existingDispatch.companias || [1]).map(Number), compNum])
              );
              const updatedUnidades = {
                ...(existingDispatch.unidades || {}),
                [unitIdRequested]: {
                  ...(existingDispatch.unidades?.[unitIdRequested] || {}),
                  idSalida: existingDispatch.unidades?.[unitIdRequested]?.idSalida || '',
                  solicitudPersonalTimestamp: nowTs, // <-- Activa automáticamente el 6-6 para esta unidad
                  ...(currentReq.dirigirse ? { dirigirse: currentReq.dirigirse } : {})
                }
              };
              markLocalWrite('despachos', existingDispatch.idServicio, nowTs);
              await updateDoc(doc(db, 'despachos', existingDispatch.idServicio), {
                companias: mergedCompanias,
                unidades: updatedUnidades,
                _metaTimestamp: nowTs
              });
              markLocalWrite('vehiculos', unitIdRequested, nowTs);
              await setDoc(
                doc(db, 'vehiculos', unitIdRequested),
                { enServicio: existingDispatch.idServicio, _metaTimestamp: nowTs },
                { merge: true }
              );
            }

            // 2. Alertar al Bombero en turno en accesos/central usando exclusivamente su idRegistro
            let operadorIdRegistro = '';
            try {
              const centralSnap = await getDoc(doc(db, 'accesos', 'central'));
              if (centralSnap.exists()) {
                operadorIdRegistro = String(centralSnap.data()?.idRegistro || '').trim();
              }
            } catch (_) {}
            if (!operadorIdRegistro) {
              operadorIdRegistro = String(
                lastCentralRawData?.idRegistro || get().centralOperatorId || ''
              ).trim();
            }

            if (operadorIdRegistro) {
              await get().publishAlert({
                gradoAlerta: '3',
                aQuienAlerta: operadorIdRegistro,
                exactIdRegistro: true,
                duracion: 'i',
                quienAlerta: `ORDEN DE COMANDANCIA SCI - ${commanderLabel}`,
                razonAlerta: `UNIDAD ${unitIdRequested} DESPACHADA EN APOYO A ${requestingCorpTitle}`,
                mensaje: `🚒 DESPACHO SCI EJECUTADO: El Comandante ha autorizado y despachado la unidad ${unitIdRequested} al 10-12 x ${currentReq.claveEmergencia || '10-0'} en apoyo a ${requestingCorpTitle} (${currentReq.direccionEmergencia || 'Ubicación GPS SCI'})${currentReq.sectorDestino ? ` — Sector: ${currentReq.sectorDestino}` : ''}${currentReq.instruccionTactica ? ` — Instrucción: ${currentReq.instruccionTactica}` : ''}. Continúe el control radial (6-0, 6-3, 6-8) en SENTINEL CAD.`
              });
            }
          }
        } catch (errSciDisp) {
          console.warn('Error ejecutando despacho automático SCI 10-12:', errSciDisp);
        }
      }

      return {
        success: true,
        idSolicitud,
        idIncidenteSci,
        estado: cleanDecision,
        availableUnitsCount: availableUnits.length
      };
    } catch (e) {
      console.warn('Error en respondToInterCorpsAlert:', e);
      return { success: false, error: e.message };
    } finally {
      set({ isRespondingInterCorps: false });
    }
  },

  // 12. Confirmar Apoyo sobre la solicitud existente del Cuerpo Vecino con SCI Activo (sin crear jamás un nuevo idSolicitud)
  offerVoluntarySciSupport: async (neighborCorp = null) => {
    const existingReq =
      (neighborCorp?.idIncidenteSci &&
        (get().sciRequestsList || []).find((r) => r.idIncidenteSci === neighborCorp.idIncidenteSci)) ||
      get().sciRequestsList?.[0] ||
      get().interCorpsAlert ||
      null;
    if (!existingReq) {
      return { success: false, error: 'No existe solicitud SCI activa para confirmar' };
    }
    return await get().respondToInterCorpsAlert('CONFIRMADA', existingReq);
  },

  // 13. Enviar Mensaje en el Chat Directo SCI (`sci_incidentes/{idIncidenteSci}/mensajes/{ownLicKey}`, SIN mostrar `idRegistro` y SIN crear solicitudes nuevas)
  sendSciChatMessage: async (messageText = '', targetRequest = null) => {
    const cleanMsg = String(messageText || '').trim().replace(/\|/g, ' ');
    if (!cleanMsg) return { success: false };

    const user = useAuthStore.getState().user;
    if (!hasCommanderPrivileges(user)) return { success: false };

    const licState = useLicenseStore.getState();
    const ownLicKey = String(licState.licenseKey || '').trim().toUpperCase();
    const shortCmdName = formatShortPersonName(user?.nombreBombero || user?.nombre || 'COMANDANTE');
    const ownCorpShort = String(licState.nombreMostrar || licState.clientName || ownLicKey)
      .replace(/^CUERPO DE BOMBEROS DE\s+/i, '')
      .trim()
      .toUpperCase();
    // REGLA ESTRICTA: Nunca guardar ni mostrar `idRegistro` en el nombre del remitente del chat SCI
    const radialTag = String(user?.idRadial || '1').trim() || '1';
    const senderLabel = `[${radialTag}] ${shortCmdName}${ownCorpShort ? ` - ${ownCorpShort}` : ''}`;
    const nowTs = Date.now();

    const primaryReq = targetRequest || get().sciRequestsList?.[0] || get().interCorpsAlert || {};
    const idSolicitud = String(
      primaryReq.idSolicitud || primaryReq.id || primaryReq.idAlerta || ''
    )
      .trim()
      .toUpperCase();
    const requestingLicKey = String(
      primaryReq.licenciaSolicitante || get().activeSciNeighborCorps?.[0]?.licenciaSolicitante || ''
    )
      .trim()
      .toUpperCase();
    const svcIdForInc = String(
      primaryReq.idServicioOrigen ||
        primaryReq.idServicio ||
        get().activeSciNeighborCorps?.[0]?.idServicio ||
        'SCI-ACTIVO'
    ).trim();
    const matchedActiveInc = Object.values(latestCentralSciIncidentsMap || {}).find(
      (inc) =>
        inc &&
        inc.activo !== false &&
        String(inc.licenciaSolicitante || '').trim().toUpperCase() ===
          (requestingLicKey || ownLicKey)
    );
    const idIncidenteSci =
      String(
        primaryReq.idIncidenteSci ||
          matchedActiveInc?.idIncidenteSci ||
          get().activeSciNeighborCorps?.[0]?.idIncidenteSci ||
          ''
      ).trim() ||
      buildCanonicalCentralSciIncidentId(requestingLicKey || ownLicKey, svcIdForInc);

    const currentChatStr = String(
      get().sciChatMessagesRaw ||
        primaryReq.mensajesChat ||
        primaryReq.mensajeAlerta ||
        ''
    ).trim();

    const updatedChatStr = appendCanonicalChatMessage(
      currentChatStr,
      formatCanonicalChatEntry({
        senderId: senderLabel,
        texto: cleanMsg
      })
    );

    const parsedAfter = parseCanonicalChatMessages(updatedChatStr);
    lastSciChatMsgCount = parsedAfter.length;

    set({ sciChatMessagesRaw: updatedChatStr });
    if (idIncidenteSci) {
      latestSciIncidentMensajesByInc = {
        ...latestSciIncidentMensajesByInc,
        [idIncidenteSci]: {
          ...(latestSciIncidentMensajesByInc[idIncidenteSci] || {}),
          [ownLicKey]: {
            ...(latestSciIncidentMensajesByInc[idIncidenteSci]?.[ownLicKey] || {}),
            idCanal: ownLicKey,
            mensajesChat: updatedChatStr,
            mensajeAlerta: updatedChatStr,
            updatedAt: nowTs,
            _metaTimestamp: nowTs
          }
        }
      };
    }
    get().recomputeSciState();

    const centralDb = getCentralDb();
    if (centralDb && ownLicKey && idIncidenteSci) {
      const directChatId = `SCI_CHAT_${ownLicKey}`;
      const ownCorpFullTitle = String(
        licState.clientName || licState.nombreMostrar || ownLicKey
      ).trim().toUpperCase();
      const requestingCorpTitle = String(
        primaryReq.cuerpoSolicitante ||
          primaryReq.nombreCuerpoSolicitante ||
          ownCorpFullTitle
      ).trim().toUpperCase();

      const writes = [
        setDoc(
          doc(centralDb, 'sci_incidentes', idIncidenteSci, 'mensajes', ownLicKey),
          {
            idCanal: ownLicKey,
            idAlerta: directChatId,
            idIncidenteSci,
            licenciaSolicitante: requestingLicKey || ownLicKey,
            licenciaDestino: ownLicKey,
            cuerpoDestino: ownCorpFullTitle,
            razonAlerta: requestingCorpTitle,
            mensajeAlerta: updatedChatStr,
            mensajesChat: updatedChatStr,
            quienAlerta: senderLabel,
            soloComandante: true,
            idRadialDestinatario: '1',
            updatedAt: nowTs,
            _metaTimestamp: nowTs
          },
          { merge: true }
        ).catch(() => {})
      ];

      if (idSolicitud && latestSciIncidentSolicitudesByInc[idIncidenteSci]?.[idSolicitud]) {
        writes.push(
          setDoc(
            doc(centralDb, 'sci_incidentes', idIncidenteSci, 'solicitudes', idSolicitud),
            {
              idSolicitud,
              idIncidenteSci,
              mensajesChat: updatedChatStr,
              updatedAt: nowTs,
              _metaTimestamp: nowTs
            },
            { merge: true }
          ).catch(() => {})
        );
      }

      await Promise.all(writes);
    }

    return { success: true, mensajesChat: updatedChatStr };
  },

  // Desconectar listeners al salir o cambiar de vista
  unsubscribeRealtime: () => {
    [
      unsubDespachos,
      unsubPersonal,
      unsubVehiculos,
      unsubProtocols,
      unsubAlertas,
      unsubCentral,
      unsubAsistencia,
      unsubCentralInterCorps,
      unsubCentralSciRequests,
      unsubCentralSciIncidents
    ].forEach(unsub => {
      if (unsub) {
        try { unsub(); } catch (e) {}
      }
    });
    unsubSciIncidentSubcols.forEach((arr) => {
      (arr || []).forEach((u) => {
        try { u(); } catch (_) {}
      });
    });
    unsubSciIncidentSubcols.clear();
    unsubDespachos = null;
    unsubPersonal = null;
    unsubVehiculos = null;
    unsubProtocols = null;
    unsubAlertas = null;
    unsubCentral = null;
    unsubAsistencia = null;
    unsubCentralInterCorps = null;
    unsubCentralSciRequests = null;
    unsubCentralSciIncidents = null;
    activeSubscribedProjectId = '';
    lastRestHydrationTs = 0;
    set({ isLoading: false });
  },

  triggerNativeVibration: (patternCsv = '0,800,300,800') => {
    try {
      if (typeof window !== 'undefined') {
        if (window.AndroidApp?.vibratePattern) {
          window.AndroidApp.vibratePattern(patternCsv);
          return;
        }
        if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
          window.webkit.messageHandlers.iOSApp.postMessage({
            action: 'vibrate',
            pattern: patternCsv
          });
          return;
        }
        if (navigator?.vibrate) {
          const arr = patternCsv.split(',').map((n) => parseInt(n.trim(), 10)).filter((n) => !isNaN(n));
          navigator.vibrate(arr);
        }
      }
    } catch (_) {}
  },

  // Reproducción de Sirena de Clave (c10_X.mp3) o Tonos de Alerta (Nativo Android + iOS + Web)
  playDispatchSound: async (clave = '10-0', options = {}) => {
    if (useAuthStore.getState().isAirplaneMode || get().isCentralActive) return;
    const resolvedKey = resolveSoundKeyForClave(clave);
    const rawName = ANDROID_RAW_SOUNDS[resolvedKey] || ANDROID_RAW_SOUNDS[clave] || 'despacho';
    const userEstado = String(useAuthStore.getState().user?.estado || '0-9').trim();
    const forceMax = options.forceMaxVolume !== undefined && options.forceMaxVolume !== null
      ? Boolean(options.forceMaxVolume)
      : (userEstado === '0-9');

    if (options.vibratePatternCsv) {
      get().triggerNativeVibration(options.vibratePatternCsv);
    }

    // Filtro de sonido en primer plano: si la app está abierta en primer plano no debe sonar, solo vibrar
    if (typeof document !== 'undefined' && document.visibilityState === 'visible') {
      if (!options.vibratePatternCsv) {
        get().triggerNativeVibration('0,800,300,800');
      }
      return;
    }

    try {
      if (typeof window !== 'undefined' && window.AndroidApp) {
        if (typeof window.AndroidApp.playToneWithVolume === 'function') {
          window.AndroidApp.playToneWithVolume(rawName, forceMax);
          set({ isAudioPlaying: true });
          return;
        }
        if (typeof window.AndroidApp.playTone === 'function') {
          window.AndroidApp.playTone(rawName);
          set({ isAudioPlaying: true });
          return;
        }
        if (typeof window.AndroidApp.playSound === 'function') {
          window.AndroidApp.playSound(rawName);
          set({ isAudioPlaying: true });
          return;
        }
      }

      if (typeof window !== 'undefined' && window.webkit?.messageHandlers?.iOSApp?.postMessage) {
        window.webkit.messageHandlers.iOSApp.postMessage({
          action: 'playSound',
          soundName: rawName,
          forceMaxVolume: forceMax
        });
        set({ isAudioPlaying: true });
        return;
      }

      if (Platform.OS === 'android' && NativeModules.SentinelSound) {
        NativeModules.SentinelSound.playSound(rawName);
        set({ isAudioPlaying: true });
        return;
      }

      if (Platform.OS === 'web' && typeof window !== 'undefined' && typeof window.Audio !== 'undefined') {
        const prev = get().soundObject;
        if (prev && typeof prev.pause === 'function') {
          try { prev.pause(); } catch (e) {}
        }
        const src = SOUND_FILES[resolvedKey] || SOUND_FILES[clave] || SOUND_FILES['despacho'] || SOUND_FILES['alerta'];
        if (typeof src === 'string') {
          const audio = new window.Audio(src);
          audio.volume = 1.0;
          audio.onended = () => set({ isAudioPlaying: false });
          await audio.play();
          set({ soundObject: audio, isAudioPlaying: true });
        }
      }
    } catch (e) {
      console.warn('Audio playback error:', e);
      set({ isAudioPlaying: false });
    }
  },

  stopDispatchSound: async () => {
    try {
      if (typeof window !== 'undefined' && window.AndroidApp) {
        if (typeof window.AndroidApp.stopTone === 'function') {
          window.AndroidApp.stopTone();
        } else if (typeof window.AndroidApp.stopSound === 'function') {
          window.AndroidApp.stopSound();
        }
      }
      if (typeof window !== 'undefined' && window.webkit?.messageHandlers?.iOSApp?.postMessage) {
        window.webkit.messageHandlers.iOSApp.postMessage({
          action: 'stopSound'
        });
      }
      if (Platform.OS === 'android' && NativeModules.SentinelSound) {
        NativeModules.SentinelSound.stopSound();
      }
      const currentSound = get().soundObject;
      if (currentSound && typeof currentSound.pause === 'function') {
        currentSound.pause();
      }
    } catch (e) {}
    set({ soundObject: null, isAudioPlaying: false });
  }
}));

// Registrar puente síncrono directo para que `useAuthStore.updateStatus` y `setEmergencyResponse`
// actualicen instantáneamente `firefightersList` en `0ms` sin `require()` circular
registerPersonalBridge({
  onOptimisticStatus: (docId, newEstado, extraFields, explicitTs) => {
    return useDispatchStore.getState().optimisticUpdateFirefighterStatus(docId, newEstado, extraFields, explicitTs);
  },
  onOptimisticService: (docId, newEnServicio, explicitTs) => {
    const cleanId = String(docId || '').trim();
    if (!cleanId) return 0;
    const nowTs = markLocalWrite('personal', cleanId, explicitTs || Date.now());
    const dispState = useDispatchStore.getState();
    if (dispState && Array.isArray(dispState.firefightersList)) {
      useDispatchStore.setState({
        firefightersList: dispState.firefightersList.map((f) =>
          String(f.idRegistro).trim() === cleanId
            ? {
                ...f,
                enServicio: String(newEnServicio ?? '0'),
                _metaTimestamp: nowTs,
                _fieldTimestamps: {
                  ...(f._fieldTimestamps || {}),
                  enServicio: nowTs
                }
              }
            : f
        )
      });
    }
    return nowTs;
  }
});
