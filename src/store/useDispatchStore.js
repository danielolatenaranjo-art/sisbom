import { create } from 'zustand';
import { Platform, NativeModules } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  collection,
  doc,
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
import { ref, set as rtdbSet } from 'firebase/database';
import { useLicenseStore } from './useLicenseStore';
import { useAuthStore } from './useAuthStore';
import {
  getDb,
  getRtdb,
  ensureFirebaseAuth,
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
  getEntityMaxTimestamp,
  mergeServiceUnitByHigherTimestamp
} from '../shared/sentinelCore';

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
let restFallbackIntervalId = null;
let lastSnapshotReceivedTs = 0;

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
    timestamp,
    _metaTimestamp: metaTs,
    _fieldTimestamps: raw._fieldTimestamps && typeof raw._fieldTimestamps === 'object' ? raw._fieldTimestamps : {}
  };
}

// Normalizador de Lista de Asistencia (Dual-Read V2 + V3 idéntico a mapToAttendanceSheet en FirebaseRepository.kt)
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

  return {
    id: idLista,
    idLista,
    nroLista: raw.nroLista ?? raw.numeroLista ?? '',
    clave: String(raw.clave || raw.acto || '').trim(),
    tipo: String(raw.tipo || '').trim(),
    fecha,
    hora,
    lugar: String(raw.lugar || raw.direccion || '').trim(),
    aprobadoPor: String(raw.aprobadoPor || '').trim(),
    listaPor: String(raw.listaPor || raw.creadoPor || '').trim(),
    obac:
      typeof raw.obac === 'object'
        ? String(raw.obac?.nombre || raw.obac?.idRadial || '')
        : String(raw.obac || raw.obacServicio || '').trim(),
    detalle: String(raw.detalle || raw.detalles || raw.observacion || '').trim(),
    anulada: anuladaVal,
    userEstado: String(raw.userEstado || ''),
    userAbono: rawAbono,
    inlineBomberos: raw.bomberos && typeof raw.bomberos === 'object' ? raw.bomberos : null,
    timestamp: ts,
    _metaTimestamp: metaTs,
    _fieldTimestamps: raw._fieldTimestamps && typeof raw._fieldTimestamps === 'object' ? raw._fieldTimestamps : {}
  };
}

// Helper para separar órdenes, alertas y canal de chat activo
function splitAlertsAndOrders(allList = [], currentChat = null) {
  const ordersList = allList.filter(a => a.tipo === 'orden');
  const alertsList = allList.filter(a => a.tipo !== 'orden');
  const foundChat =
    (currentChat && allList.find(a => a.idAlerta === currentChat.idAlerta)) ||
    allList.find(a => String(a.duracion || '').toUpperCase() === 'C') ||
    alertsList[0] ||
    null;

  return {
    allAlertsList: allList,
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
            const isClosed =
              st === 'cancelada' ||
              st === 'cancelado' ||
              st === 'finalizada' ||
              st === 'finalizado' ||
              st === 'cerrada' ||
              st === 'cerrado' ||
              st === 'terminada' ||
              st === 'terminado' ||
              Boolean(d.operadorFinal) ||
              Boolean(d.finalizadoAt);
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
          const rawAtt = window.localStorage.getItem(`@sentinel_one_v3_attendance_v2_exact_${userId}`);
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
  isLoading: false,
  soundObject: null,
  isAudioPlaying: false,
  isLiveConnected: false,

  setActiveChatAlert: (alertObj) => set({ activeChatAlert: alertObj, chatAlert: alertObj }),

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
          get().playDispatchSound('importante', {
            forceMaxVolume: false,
            vibratePatternCsv: '0,500,250,500'
          });
        }
      } else {
        const numB = Number(uObj.numBomberos || 0);
        if (numB === 0) {
          get().playDispatchSound('importante', {
            forceMaxVolume: false,
            vibratePatternCsv: '0,500,250,500'
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
      ((opId && (opId === myReg || opId === myRad)) ||
       (myNameNorm && opNameNorm && (myNameNorm === opNameNorm || shortOperatorName(myNameNorm) === shortOperatorName(opNameNorm))))
    );

    set({
      centralOperatorName: opName,
      centralOperatorId: opId,
      centralSessionId: sessionId,
      isCentralActive: isMeCentral,
      ...(isMeCentral ? { fullscreenDispatchId: null } : {})
    });

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

    if (isMeCentral && currentUser && String(currentUser.estado || '').trim().toUpperCase() !== '0-9') {
      try {
        useAuthStore.getState().updateStatus('0-9');
      } catch (_) {}
    }

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
      const incomingNorm = normalizeDispatchDoc(id, rawData);
      const docId = String(incomingNorm.idServicio || id).trim();
      const existing = existingById.get(docId);

      // Ley #4: Fusionar unidades y campos garantizando que NUNCA un snapshot atrasado pise un timestamp mayor
      const mergedItem = existing
        ? (shouldKeepLocalByTimestamp('despachos', docId, existing, incomingNorm) || hasPendingWrites
            ? mergeDispatchEntitiesByHigherTimestamp(existing, incomingNorm)
            : mergeDispatchEntitiesByHigherTimestamp(existing, incomingNorm))
        : incomingNorm;

      const st = String(mergedItem.estado || '').toLowerCase().trim();
      const isClosed =
        st === 'cancelada' ||
        st === 'cancelado' ||
        st === 'finalizada' ||
        st === 'finalizado' ||
        st === 'cerrada' ||
        st === 'cerrado' ||
        st === 'terminada' ||
        st === 'terminado' ||
        Boolean(mergedItem.operadorFinal) ||
        Boolean(mergedItem.finalizadoAt);

      if (isClosed) {
        closedServerIds.add(docId);
        return;
      }

      const isValidActive =
        (st === 'activa' || st === 'activo' || st === 'pre-despacho' || st === 'en curso' || st === '') &&
        !isClosed &&
        mergedItem.visibleMovil !== false;

      if (isValidActive) {
        activeList.push(mergedItem);
      }
    });

    // Preservar despachos locales recién creados dentro de la ventana de protección si aún no están cerrados
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

        // Si al abrir la app hay un despacho activo reciente sin responder y está en 0-9, mostrar la pantalla del despacho en silencio (sin sonar)
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

          // A. Nuevo despacho en vivo: solo mostrar overlay si el usuario está en 0-9 y no en Modo Avión (0-8 Absoluto)
          if (!notifiedDispatchIds.has(id)) {
            notifiedDispatchIds.add(id);
            lastDispatchClaves[id] = clave;

            if (
              !isAirplane &&
              !isMeCentral &&
              !isHonorario &&
              !isSilentSpecial &&
              !isPartialSpecial &&
              is09 &&
              !isDispatchTooOld(disp) &&
              !hasUserRespondedToDispatch(disp, currentUser)
            ) {
              set({ fullscreenDispatchId: id });
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
          if (!isAirplane && !isMeCentral && !isHonorario && !isSilentSpecial && !isUserAttendingDispatch(disp, currentUser)) {
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
    }

    // Si el bombero tenía `enServicio` asociado a un despacho que ya se cerró, liberar a "0" (Idéntico a SisBomViewModel.kt líneas 977-989)
    const latestUser = useAuthStore.getState().user;
    const rawSvc = String(latestUser?.enServicio || '0').trim();
    if (rawSvc && rawSvc !== '0') {
      const cleanSvcId = rawSvc.replace(/^-/, '').trim();
      const stillActive = activeList.some((d) => String(d.idServicio).trim() === cleanSvcId);
      if (!stillActive && cleanSvcId) {
        useAuthStore.getState().setEmergencyResponse(cleanSvcId, 'LIBRE');
      } else if (stillActive && !rawSvc.startsWith('-')) {
        useAuthStore.getState().startLiveTelemetry(cleanSvcId);
      }
    } else {
      const attendingDisp = activeList.find((d) => isUserAttendingDispatch(d, latestUser));
      if (attendingDisp) {
        useAuthStore.getState().startLiveTelemetry(attendingDisp.idServicio);
      } else {
        useAuthStore.getState().stopLiveTelemetry();
      }
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

      const isActiveVal = rawData.activo === undefined
        ? true
        : (rawData.activo === true || rawData.activo === 1 || String(rawData.activo).toUpperCase() === 'SI' || String(rawData.activo) === '1');
      const isConductorVal = rawData.conductor === true || rawData.conductor === 1 || String(rawData.conductor) === '1' || String(rawData.conductor).toUpperCase() === 'SI';
      const hasPuertaVal =
        rawData.puerta === true ||
        rawData.Puerta === true ||
        rawData.puerta === 1 ||
        String(rawData.puerta ?? rawData.Puerta ?? '').trim().toLowerCase() === 'true' ||
        String(rawData.puerta ?? rawData.Puerta ?? '').trim().toUpperCase() === 'SI' ||
        String(rawData.puerta ?? rawData.Puerta ?? '').trim() === '1';

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

      // Ley #4: Si el registro local en memoria tiene un timestamp mayor al entrante, conservar el local
      if (existing && (shouldKeepLocalByTimestamp('personal', docId, existing, incomingItem) || hasPendingWrites)) {
        list.push({
          ...incomingItem,
          ...existing,
          foto: incomingItem.foto || existing.foto || '',
          _metaTimestamp: Math.max(getEntityMaxTimestamp(existing), getEntityMaxTimestamp(incomingItem))
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
    const syncedUser = useAuthStore.getState().user;
    const syncedId = String(syncedUser?.idRegistro || '').trim();
    if (syncedId && syncedId !== lastSubscribedAttendanceUserId) {
      get().subscribeAttendanceRealtime();
    }
    const syncedSvc = String(syncedUser?.enServicio || '0').trim();
    if (syncedSvc && syncedSvc !== '0' && !syncedSvc.startsWith('-')) {
      const activeListNow = get().dispatchesList || [];
      if (activeListNow.some((d) => String(d.idServicio).trim() === syncedSvc)) {
        useAuthStore.getState().startLiveTelemetry(syncedSvc);
      }
    }
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

  // Hidratación inmediata y respaldo en vivo por Firestore REST (garantiza 100% conexión en iOS WKWebView y redes restringidas)
  hydrateFromFirestoreRest: async () => {
    try {
      const fbConfig = useLicenseStore.getState().firebaseConfig;
      if (!fbConfig || !fbConfig.projectId || !fbConfig.apiKey) return;
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

      const fetchDespachosViaStructuredQuery = async () => {
        try {
          const queryRes = await fetchFirestoreWithAuth(`${baseUrl}:runQuery`, apiKey, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              structuredQuery: {
                from: [{ collectionId: 'despachos' }],
                orderBy: [{ field: { fieldPath: '__name__' }, direction: 'DESCENDING' }],
                limit: 150
              }
            })
          });
          if (queryRes && queryRes.ok) {
            const qJson = await queryRes.json();
            if (Array.isArray(qJson)) {
              const docs = [];
              qJson.forEach((row) => {
                if (row?.document?.name) {
                  docs.push({
                    id: row.document.name.split('/').pop(),
                    data: parseRestDocFields(row.document),
                    hasPendingWrites: false
                  });
                }
              });
              return docs;
            }
          }
        } catch (_) {}
        const fallbackRes = await fetchFirestoreWithAuth(`${baseUrl}/despachos?pageSize=300`, apiKey).catch(() => null);
        if (fallbackRes && fallbackRes.ok) {
          const dJson = await fallbackRes.json();
          return (dJson?.documents || []).map((docItem) => ({
            id: docItem.name.split('/').pop(),
            data: parseRestDocFields(docItem),
            hasPendingWrites: false
          }));
        }
        return null;
      };

      const [centralRes, despDocs, vehRes, alertRes] = await Promise.all([
        fetchFirestoreWithAuth(`${baseUrl}/accesos/central`, apiKey).catch(() => null),
        fetchDespachosViaStructuredQuery(),
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

      // Si aún no se ha cargado la lista de personal, hidratar resumen ligero por REST
      if ((get().firefightersList || []).length === 0) {
        const maskFields = [
          'idRegistro', 'idRadial', 'nombreBombero', 'nombre', 'cargo',
          'compania', 'estado', 'enServicio', 'conductor', 'activo',
          'puerta', 'Puerta', 'telefono', 'licenciaMedica',
          'fechaSuspensionFin', 'permiso', 'cds', '_metaTimestamp'
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

  // Subscripción en tiempo real con onSnapshot (WebSockets / Long-Polling + Respaldo REST inmediato)
  subscribeRealtime: async () => {
    get().unsubscribeRealtime();

    set({ isLoading: true });

    try {
      const fbConfig = useLicenseStore.getState().firebaseConfig;
      if (!fbConfig || !fbConfig.projectId || !fbConfig.apiKey) {
        set({ isLoading: false });
        return;
      }
      await reinitFirebaseWithConfig(fbConfig);

      // Disparar hidratación inmediata vía REST en paralelo (garantiza carga instantánea en iOS y Android)
      get().hydrateFromFirestoreRest();
      ensureFirebaseAuth();

      const db = getDb();
      if (!db) {
        console.warn('⚠️ [useDispatchStore] Firestore db no disponible, operando con sincronización REST.');
        set({ isLoading: false });
        return;
      }

      console.log(`📡 [SENTINEL ONE] Conectando 6 listeners onSnapshot en vivo a: ${fbConfig.projectId}`);

      // =====================================================================
      // 1. LISTENER EN TIEMPO REAL: DESPACHOS ACTIVOS (`despachos`)
      // =====================================================================
      unsubDespachos = onSnapshot(collection(db, 'despachos'), (snap) => {
        lastSnapshotReceivedTs = Date.now();
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
        set({ isLoading: false });
        get().hydrateFromFirestoreRest();
      });

      // =====================================================================
      // 2. LISTENER EN TIEMPO REAL: PERSONAL Y DOTACIÓN (`personal`)
      // =====================================================================
      useAuthStore.getState().refreshUserProfile()
        .then((u) => {
          get().recomputeCentralState();
          const newId = String(u?.idRegistro || u?.idRadial || '').trim();
          if (newId && newId !== lastSubscribedAttendanceUserId) {
            get().subscribeAttendanceRealtime();
          }
        })
        .catch(() => {});

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
      });

      // =====================================================================
      // 6. LISTENER Y CACHÉ DE ASISTENCIAS (`asistencia`)
      // =====================================================================
      get().subscribeAttendanceRealtime();

      // Respaldo periódico cada 6s por si iOS WKWebView o red móvil pausa el canal de streaming
      restFallbackIntervalId = setInterval(() => {
        const isIos = typeof window !== 'undefined' && Boolean(window.webkit?.messageHandlers?.iOSApp || window.__SENTINEL_PLATFORM__ === 'ios');
        const sinceLastSnap = Date.now() - lastSnapshotReceivedTs;
        if (isIos || sinceLastSnap > 7000) {
          get().hydrateFromFirestoreRest();
        }
      }, 6000);

    } catch (e) {
      console.error('❌ [SENTINEL ONE] Error conectando a Firestore onSnapshot:', e);
      set({ isLoading: false });
      get().hydrateFromFirestoreRest();
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

    const cacheKey = `@sentinel_one_v3_attendance_v2_exact_${userId}`;
    const statusMapKey = `@sentinel_one_v3_att_status_map_${userId}`;
    const initDoneKey = `@sentinel_one_v3_att_init_done_${userId}`;
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

  // 1. Botón TIMBRE: Solicitar apertura de puerta al Operador CAD (Idéntico a FirebaseRepository.kt líneas 627-661)
  solicitarAperturaPuerta: async () => {
    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return { success: true };

    const user = useAuthStore.getState().user;
    if (!user) return { success: false };

    const now = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    const fechaFull = `${pad(now.getDate())}-${pad(now.getMonth() + 1)}-${now.getFullYear()} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
    const nowTs = Date.now();
    const solId = String(nowTs);

    const words = String(user.nombreBombero || 'BOMBERO').trim().split(/\s+/).filter(Boolean);
    const shortName = words.length >= 3
      ? `${words[0]} ${words[words.length - 2]}`
      : words.join(' ');

    const myReg = String(user.idRegistro || user.idRadial || '').trim();
    knownDoorRequestKeys.add(`door_${myReg}_${fechaFull}`);
    knownDoorRequestKeys.add(`door_${solId}`);

    const docData = {
      id: solId,
      idRegistro: myReg,
      idRadial: String(user.idRadial || ''),
      nombreBombero: shortName,
      compania: Number(user.compania || 1),
      fecha: fechaFull,
      timestamp: nowTs,
      estado: 'pendiente',
      pushSent: false
    };

    try {
      const db = getDb();
      await ensureFirebaseAuth();
      markLocalWrite('solicitudes_puerta', solId, nowTs);
      markLocalWrite('accesos', 'central', nowTs);
      await addDoc(collection(db, 'solicitudes_puerta'), docData);
      try {
        await setDoc(doc(db, 'accesos', 'central'), {
          solicitudPuerta: docData,
          _metaTimestamp: nowTs,
          _fieldTimestamps: { solicitudPuerta: nowTs }
        }, { merge: true });
      } catch (e) {}
      return { success: true };
    } catch (e) {
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

    try {
      await ensureFirebaseAuth();
      const rtdb = getRtdb();
      if (rtdb) {
        await rtdbSet(ref(rtdb, 'hardware/puerta'), {
          trigger: true,
          timestamp: nowTs,
          solicitante: user?.nombreBombero || 'SENTINEL ONE'
        }).catch(() => {});
      }
      const db = getDb();
      if (db) {
        markLocalWrite('accesos', 'central', nowTs);
        await setDoc(doc(db, 'accesos', 'central'), {
          puerta: true,
          timestampPuerta: nowTs,
          _metaTimestamp: nowTs,
          _fieldTimestamps: { puerta: nowTs, timestampPuerta: nowTs }
        }, { merge: true });
      }
      return { success: true };
    } catch (e) {
      return { success: false, error: e.message };
    }
  },

  // 3. Cerrar turno de Operador de Central de Alarmas
  closeCentralOperatorSession: async () => {
    const isBypass = useLicenseStore.getState().isBypassMode;
    const isReadOnly = useLicenseStore.getState().isReadOnly;
    if (isBypass || isReadOnly) return { success: true };

    const nowTs = Date.now();
    const dateNow = getLocalDateString();
    const timeNow = getLocalTimeString();

    try {
      const db = getDb();
      await ensureFirebaseAuth();
      const sessionId = get().centralSessionId;

      if (sessionId) {
        markLocalWrite('accesos', sessionId, nowTs);
        await setDoc(doc(db, 'accesos', 'central', 'registros', sessionId), {
          estado: 'cerrado',
          timestampCierre: nowTs,
          fechaCierre: dateNow,
          horaCierre: timeNow,
          _metaTimestamp: nowTs
        }, { merge: true }).catch(() => {});
      }

      markLocalWrite('accesos', 'central', nowTs);
      await setDoc(doc(db, 'accesos', 'central'), {
        estado: 'cerrado',
        idInicio: '',
        idRegistro: '',
        cargo: '',
        nombreBombero: '',
        operador: '',
        timestampCierre: nowTs,
        _metaTimestamp: nowTs,
        _fieldTimestamps: { estado: nowTs, timestampCierre: nowTs }
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

      const snap = await getDocs(collection(db, 'despachos'));
      let maxCorrelativo = 0;
      snap.forEach((d) => {
        const idStr = String(d.id || '');
        if (idStr.startsWith(`${year}-`)) {
          const num = parseInt(idStr.split('-')[1], 10);
          if (!isNaN(num) && num > maxCorrelativo) maxCorrelativo = num;
        }
      });
      const draftId = `${year}-${String(maxCorrelativo + 1).padStart(5, '0')}`;

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
        pushSent: false,
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
        _metaTimestamp: nowTs,
        _fieldTimestamps: { estado: nowTs, finalizadoAt: nowTs, visibleMovil: nowTs }
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
        _metaTimestamp: nowTs,
        _fieldTimestamps: { direccion: nowTs }
      };
      if (coords && typeof coords.lat === 'number' && typeof coords.lng === 'number' && !isNaN(coords.lat) && !isNaN(coords.lng)) {
        updateObj.geo = {
          lat: Number(coords.lat),
          lng: Number(coords.lng),
          accuracy: 10,
          origen: isAlertanteGeo ? 'GPS_ALERTANTE' : 'MANUAL_CAD'
        };
        updateObj._fieldTimestamps.geo = nowTs;
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
        const snap = await getDocs(collection(db, 'despachos'));
        let maxCorrelativo = 0;
        snap.forEach((d) => {
          const idStr = String(d.id || '');
          if (idStr.startsWith(`${year}-`)) {
            const num = parseInt(idStr.split('-')[1], 10);
            if (!isNaN(num) && num > maxCorrelativo) maxCorrelativo = num;
          }
        });
        idServicio = `${year}-${String(maxCorrelativo + 1).padStart(5, '0')}`;
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
          telefono: String(callerPhone || '').trim()
        },
        companias: Array.from(companiasSet),
        unidades: unidadesMap,
        carros: selectedVehicles,
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
        pushSent: false,
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
            _metaTimestamp: nowTs,
            _fieldTimestamps: { enServicio: nowTs }
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
        _metaTimestamp: nowTs,
        _fieldTimestamps: { conforme: nowTs }
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
        fijada: newFijada,
        _metaTimestamp: nowTs,
        _fieldTimestamps: { fijar: nowTs, fijada: nowTs }
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
        _metaTimestamp: nowTs,
        _fieldTimestamps: { mensajeAlerta: nowTs, conforme: nowTs }
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
    if (rawAQuien.toUpperCase() !== 'TC' && rawAQuien !== '') {
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
        _metaTimestamp: nowTs,
        _fieldTimestamps: { duracion: nowTs }
      }).catch(async () => {
        await deleteDoc(doc(db, 'alertas', targetId));
      });
      return { success: true };
    } catch (e) {
      return { success: false, error: e.message };
    }
  },

  // Desconectar listeners al salir o cambiar de vista
  unsubscribeRealtime: () => {
    [unsubDespachos, unsubPersonal, unsubVehiculos, unsubProtocols, unsubAlertas, unsubCentral, unsubAsistencia].forEach(unsub => {
      if (unsub) {
        try { unsub(); } catch (e) {}
      }
    });
    unsubDespachos = null;
    unsubPersonal = null;
    unsubVehiculos = null;
    unsubProtocols = null;
    unsubAlertas = null;
    unsubCentral = null;
    unsubAsistencia = null;
    if (restFallbackIntervalId) {
      clearInterval(restFallbackIntervalId);
      restFallbackIntervalId = null;
    }
    set({ isLiveConnected: false });
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
