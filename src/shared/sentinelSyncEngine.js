/**
 * ============================================================================
 * SENTINEL V3 — MOTOR SINCRÓNICO TRANSVERSAL DEL ECOSISTEMA (SHARED SYNC ENGINE)
 * ============================================================================
 * Fuente única de verdad para operaciones Firebase Firestore + RTDB que cruzan
 * 2 o más sistemas del ecosistema (CAD, ONE, SCI, NAV, LINK, CORE, CREW, MED, SAAS):
 *   1. Telemetría GPS en vivo 100% en Realtime Database (RTDB), jamás en Firestore.
 *   2. Ubicación fija puntual del alertante 132 y emergencia en Firestore + tracking RTDB.
 *   3. Reubicación transversal del incidente entre NAV, SCI, CAD y ONE.
 *   4. Alertas canónicas CAD/SCI y compuertas anti-polling / backoff exponencial (>=15s).
 *   5. Fusión monotónica de snapshots ("Mayor Timestamp Gana" — Ley #4).
 */
import {
  doc,
  collection,
  query,
  where,
  getDocs,
  documentId
} from 'firebase/firestore';
import {
  ref as rtdbRef,
  set as rtdbSet,
  remove as rtdbRemove,
  onValue as rtdbOnValue
} from 'firebase/database';
import { setDoc, updateDoc } from '../config/firestoreMeta';
import {
  getEntityMaxTimestamp,
  calculateHaversineDistanceMeters,
  buildCanonicalCadAlertDoc,
  buildCanonicalCentralSciIncidentId,
  formatCanonicalDispatchId,
  parseDispatchIdParts
} from './sentinelCore';

// ============================================================================
// 1. RUTAS CANÓNICAS Y CONFIGURACIÓN RTDB
// ============================================================================

const sanitizeRtdbKey = (val = '') =>
  String(val || '')
    .trim()
    .replace(/[.#$/[\]]/g, '_');

export const SENTINEL_RTDB_PATHS = {
  vehiclesRoot: 'telemetria/vehiculos',
  vehicle: (idCarro) => `telemetria/vehiculos/${sanitizeRtdbKey(String(idCarro || '').toUpperCase())}`,
  bomberosRoot: 'telemetria/bomberos',
  bombero: (idRegistro) => `telemetria/bomberos/${sanitizeRtdbKey(idRegistro)}`,
  alertantesRoot: 'telemetria/alertantes',
  alertante: (idServicio) => `telemetria/alertantes/${sanitizeRtdbKey(idServicio)}`,
  sciInterCorpsUnitsRoot: (idIncidenteSci) =>
    `telemetria/sci_incidentes/${sanitizeRtdbKey(idIncidenteSci)}/unidades_apoyo`,
  sciInterCorpsUnit: (idIncidenteSci, unitKey) =>
    `telemetria/sci_incidentes/${sanitizeRtdbKey(idIncidenteSci)}/unidades_apoyo/${sanitizeRtdbKey(
      String(unitKey || '').toUpperCase()
    )}`
};

/**
 * Resuelve la URL canónica de Realtime Database de un proyecto cliente.
 */
export function resolveCanonicalRtdbUrl(firebaseConfig = {}) {
  if (!firebaseConfig || typeof firebaseConfig !== 'object') return '';
  if (firebaseConfig.databaseURL && String(firebaseConfig.databaseURL).trim()) {
    return String(firebaseConfig.databaseURL).trim();
  }
  const projectId = String(firebaseConfig.projectId || '').trim();
  return projectId ? `https://${projectId}-default-rtdb.firebaseio.com` : '';
}

// ============================================================================
// 2. TELEMETRÍA GPS EN VIVO (100% RTDB, CERO FIRESTORE, CON THROTTLING)
// ============================================================================

const lastTelemetrySentByKey = new Map();
const fixedAlertanteGeoCooldownByService = new Map();

function hasValidCoordinates(coords = {}) {
  const lat = Number(coords?.lat);
  const lng = Number(coords?.lng ?? coords?.long);
  return !isNaN(lat) && !isNaN(lng) && (lat !== 0 || lng !== 0);
}

function shouldPublishTelemetrySample(cacheKey, coords, options = {}) {
  const now = Date.now();
  const minIntervalMs = Math.max(1000, Number(options.minIntervalMs ?? 2500));
  const minDistanceMeters = Math.max(0, Number(options.minDistanceMeters ?? 4));
  const prev = lastTelemetrySentByKey.get(cacheKey);
  const force = Boolean(options.force);
  const stateSignature = String(options.stateSignature || '');

  if (force || !prev) {
    return { allow: true, now };
  }
  if (stateSignature && stateSignature !== prev.stateSignature) {
    return { allow: true, now };
  }
  if (now - prev.ts < minIntervalMs) {
    return { allow: false, now };
  }
  if (minDistanceMeters > 0) {
    const movedMeters = calculateHaversineDistanceMeters(prev.lat, prev.lng, coords.lat, coords.lng);
    if (movedMeters !== null && movedMeters < minDistanceMeters && now - prev.ts < Math.max(minIntervalMs * 4, 15_000)) {
      return { allow: false, now };
    }
  }
  return { allow: true, now };
}

function recordTelemetrySample(cacheKey, coords, now, stateSignature = '') {
  if (lastTelemetrySentByKey.size > 400) {
    const firstKey = lastTelemetrySentByKey.keys().next().value;
    if (firstKey) lastTelemetrySentByKey.delete(firstKey);
  }
  lastTelemetrySentByKey.set(cacheKey, {
    lat: Number(coords.lat),
    lng: Number(coords.lng ?? coords.long),
    ts: now,
    stateSignature: String(stateSignature || '')
  });
}

/**
 * Publica posición GPS continua de un carro en RTDB `telemetria/vehiculos/{idCarro}`.
 */
export async function publishVehicleTelemetryRtdb(rtdb, idCarro, coords, options = {}) {
  const cleanId = String(idCarro || '').trim().toUpperCase();
  if (!rtdb || !cleanId || !hasValidCoordinates(coords)) return false;

  const lat = Number(coords.lat);
  const lng = Number(coords.lng ?? coords.long);
  const stateSignature = `${options.estado || ''}|${options.enServicio || ''}`;
  const cacheKey = `veh:${cleanId}`;
  const check = shouldPublishTelemetrySample(cacheKey, { lat, lng }, {
    minIntervalMs: options.minIntervalMs ?? 2500,
    minDistanceMeters: options.minDistanceMeters ?? 4,
    force: options.force,
    stateSignature
  });
  if (!check.allow) return false;

  const payload = {
    idCarro: cleanId,
    lat,
    lng,
    speed: Number(coords.speed ?? coords.speedKmh ?? 0) || 0,
    heading: Number(coords.heading ?? 0) || 0,
    accuracy: Number(coords.accuracy ?? 12) || 12,
    estado: String(options.estado ?? coords.estado ?? ''),
    enServicio: String(options.enServicio ?? coords.enServicio ?? ''),
    updatedAt: check.now
  };
  await rtdbSet(rtdbRef(rtdb, SENTINEL_RTDB_PATHS.vehicle(cleanId)), payload);
  recordTelemetrySample(cacheKey, { lat, lng }, check.now, stateSignature);
  return true;
}

/**
 * Publica posición GPS continua de un bombero en servicio en RTDB `telemetria/bomberos/{idRegistro}`.
 */
export async function publishBomberoTelemetryRtdb(rtdb, idRegistro, coords, options = {}) {
  const cleanId = String(idRegistro || '').trim();
  if (!rtdb || !cleanId || !hasValidCoordinates(coords)) return false;

  const lat = Number(coords.lat);
  const lng = Number(coords.lng ?? coords.long);
  const stateSignature = `${options.idServicio || ''}|${options.estadoAsistencia || ''}`;
  const cacheKey = `bom:${cleanId}`;
  const check = shouldPublishTelemetrySample(cacheKey, { lat, lng }, {
    minIntervalMs: options.minIntervalMs ?? 3000,
    minDistanceMeters: options.minDistanceMeters ?? 4,
    force: options.force,
    stateSignature
  });
  if (!check.allow) return false;

  const payload = {
    idRegistro: cleanId,
    nombre: String(options.nombre || coords.nombre || '').trim(),
    idRadial: String(options.idRadial || coords.idRadial || '').trim(),
    idServicio: String(options.idServicio || coords.idServicio || '').trim(),
    lat,
    lng,
    speed: Number(coords.speed ?? 0) || 0,
    heading: Number(coords.heading ?? 0) || 0,
    accuracy: Number(coords.accuracy ?? 15) || 15,
    updatedAt: check.now
  };
  await rtdbSet(rtdbRef(rtdb, SENTINEL_RTDB_PATHS.bombero(cleanId)), payload);
  recordTelemetrySample(cacheKey, { lat, lng }, check.now, stateSignature);
  return true;
}

/**
 * Publica seguimiento GPS continuo (`watchPosition`) del alertante 132 en RTDB `telemetria/alertantes/{idServicio}`.
 */
export async function publishAlertanteLiveTelemetryRtdb(rtdb, idServicio, coords, options = {}) {
  const cleanSvc = String(idServicio || '').trim();
  if (!rtdb || !cleanSvc || !hasValidCoordinates(coords)) return false;

  const lat = Number(coords.lat);
  const lng = Number(coords.lng ?? coords.long);
  const cacheKey = `alt:${cleanSvc}`;
  const check = shouldPublishTelemetrySample(cacheKey, { lat, lng }, {
    minIntervalMs: options.minIntervalMs ?? 2500,
    minDistanceMeters: options.minDistanceMeters ?? 4,
    force: options.force,
    stateSignature: String(options.telefono || '')
  });
  if (!check.allow) return false;

  const payload = {
    idServicio: cleanSvc,
    lat,
    lng,
    accuracy: Number(coords.accuracy ?? 12) || 12,
    telefono: String(options.telefono || coords.telefono || '').trim(),
    origen: String(options.origen || 'GPS_ALERTANTE_LIVE').trim(),
    updatedAt: check.now
  };
  await rtdbSet(rtdbRef(rtdb, SENTINEL_RTDB_PATHS.alertante(cleanSvc)), payload);
  recordTelemetrySample(cacheKey, { lat, lng }, check.now, payload.telefono);
  return true;
}

/**
 * Publica posición GPS continua de unidad en apoyo inter-cuerpos hacia RTDB central de `sisbom-central`.
 */
export async function publishInterCorpsUnitTelemetryRtdb(centralRtdb, idIncidenteSci, unitKey, coords, options = {}) {
  const cleanInc = String(idIncidenteSci || '').trim();
  const cleanUnit = String(unitKey || '').trim().toUpperCase();
  if (!centralRtdb || !cleanInc || !cleanUnit || !hasValidCoordinates(coords)) return false;

  const lat = Number(coords.lat);
  const lng = Number(coords.lng ?? coords.long);
  const cacheKey = `sci:${cleanInc}:${cleanUnit}`;
  const stateSignature = `${options.estado || ''}|${options.licenciaOrigen || ''}`;
  const check = shouldPublishTelemetrySample(cacheKey, { lat, lng }, {
    minIntervalMs: options.minIntervalMs ?? 3000,
    minDistanceMeters: options.minDistanceMeters ?? 5,
    force: options.force,
    stateSignature
  });
  if (!check.allow) return false;

  const payload = {
    unidad: cleanUnit,
    licenciaOrigen: String(options.licenciaOrigen || '').trim().toUpperCase(),
    cuerpoOrigen: String(options.cuerpoOrigen || '').trim().toUpperCase(),
    estado: String(options.estado || 'EN RUTA').trim().toUpperCase(),
    lat,
    lng,
    speed: Number(coords.speed ?? 0) || 0,
    heading: Number(coords.heading ?? 0) || 0,
    updatedAt: check.now
  };
  await rtdbSet(rtdbRef(centralRtdb, SENTINEL_RTDB_PATHS.sciInterCorpsUnit(cleanInc, cleanUnit)), payload);
  recordTelemetrySample(cacheKey, { lat, lng }, check.now, stateSignature);
  return true;
}

/**
 * Suscribe con throttling a las ramas RTDB de vehículos, bomberos y/o alertantes.
 */
export function subscribeTenantTelemetryRtdb(rtdb, { onVehicles, onBomberos, onAlertantes, throttleMs = 400 } = {}) {
  if (!rtdb) return () => {};
  const unsubs = [];
  const timers = [];
  const safeThrottle = Math.max(150, Number(throttleMs) || 400);

  const attachBranch = (pathStr, callback) => {
    if (typeof callback !== 'function') return;
    let lastEmit = 0;
    let pendingData = null;
    let pendingTimer = null;

    const flush = () => {
      pendingTimer = null;
      if (pendingData !== null) {
        const dataToEmit = pendingData;
        pendingData = null;
        lastEmit = Date.now();
        callback(dataToEmit);
      }
    };

    const unsub = rtdbOnValue(
      rtdbRef(rtdb, pathStr),
      (snap) => {
        const val = snap.exists() ? snap.val() || {} : {};
        const elapsed = Date.now() - lastEmit;
        if (elapsed >= safeThrottle) {
          if (pendingTimer) {
            clearTimeout(pendingTimer);
            pendingTimer = null;
          }
          pendingData = null;
          lastEmit = Date.now();
          callback(val);
        } else {
          pendingData = val;
          if (!pendingTimer) {
            pendingTimer = setTimeout(flush, Math.max(50, safeThrottle - elapsed));
            timers.push(pendingTimer);
          }
        }
      },
      (err) => {
        console.warn(`[sentinelSyncEngine] Error listener RTDB (${pathStr}):`, err?.message || err);
      }
    );

    unsubs.push(() => {
      if (pendingTimer) clearTimeout(pendingTimer);
      try {
        unsub();
      } catch (_) {}
    });
  };

  attachBranch(SENTINEL_RTDB_PATHS.vehiclesRoot, onVehicles);
  attachBranch(SENTINEL_RTDB_PATHS.bomberosRoot, onBomberos);
  attachBranch(SENTINEL_RTDB_PATHS.alertantesRoot, onAlertantes);

  return () => {
    timers.forEach((t) => clearTimeout(t));
    unsubs.forEach((fn) => {
      try {
        fn();
      } catch (_) {}
    });
  };
}

/**
 * Limpia nodos RTDB efímeros cuando finaliza un servicio.
 */
export async function cleanupServiceTelemetryRtdb(
  rtdb,
  { serviceId = '', remainingActiveServicesCount = 0, vehiclesMap = {}, bomberosMap = {} } = {}
) {
  if (!rtdb) return false;
  const cleanSvc = String(serviceId || '').trim();
  const tasks = [];

  if (cleanSvc) {
    tasks.push(rtdbRemove(rtdbRef(rtdb, SENTINEL_RTDB_PATHS.alertante(cleanSvc))).catch(() => {}));
  }

  if (remainingActiveServicesCount <= 0) {
    if (bomberosMap && typeof bomberosMap === 'object') {
      Object.keys(bomberosMap).forEach((bomKey) => {
        tasks.push(rtdbRemove(rtdbRef(rtdb, SENTINEL_RTDB_PATHS.bombero(bomKey))).catch(() => {}));
      });
    }
    if (vehiclesMap && typeof vehiclesMap === 'object') {
      Object.entries(vehiclesMap).forEach(([vehKey, vehVal]) => {
        if (cleanSvc && String(vehVal?.enServicio || '') === cleanSvc) {
          tasks.push(rtdbRemove(rtdbRef(rtdb, SENTINEL_RTDB_PATHS.vehicle(vehKey))).catch(() => {}));
        }
      });
    }
  } else if (cleanSvc && bomberosMap && typeof bomberosMap === 'object') {
    Object.entries(bomberosMap).forEach(([bomKey, bomVal]) => {
      if (String(bomVal?.idServicio || '') === cleanSvc) {
        tasks.push(rtdbRemove(rtdbRef(rtdb, SENTINEL_RTDB_PATHS.bombero(bomKey))).catch(() => {}));
      }
    });
  }

  await Promise.all(tasks);
  return true;
}

// ============================================================================
// 3. OPERACIONES TRANSVERSALES MULTI-SISTEMA EN FIRESTORE
// ============================================================================

/**
 * Persiste UNA ubicación puntual fija del alertante en `geolocalizaciones_alertantes/{idServicio}`.
 * Incluye candado de cooldown de 10s para evitar uso accidental dentro de watchPosition sin filtro.
 */
export async function saveFixedAlertanteGeoFirestore(db, idServicio, geoPayload = {}) {
  const cleanSvc = String(idServicio || '').trim();
  if (!db || !cleanSvc || !hasValidCoordinates(geoPayload)) return false;

  const now = Date.now();
  const lastAt = fixedAlertanteGeoCooldownByService.get(cleanSvc) || 0;
  if (!geoPayload.force && now - lastAt < 10_000) {
    return false;
  }

  const lat = Number(geoPayload.lat);
  const lng = Number(geoPayload.lng ?? geoPayload.long);
  const docPayload = {
    idServicio: cleanSvc,
    lat,
    lng,
    accuracy: Number(geoPayload.accuracy ?? 10) || 10,
    telefono: String(geoPayload.telefono || '').trim(),
    nombre: String(geoPayload.nombre || '').trim().toUpperCase(),
    origen: String(geoPayload.origen || 'GPS_ALERTANTE').trim(),
    timestamp: now,
    _metaTimestamp: now
  };
  await setDoc(doc(db, 'geolocalizaciones_alertantes', cleanSvc), docPayload, { merge: true });
  fixedAlertanteGeoCooldownByService.set(cleanSvc, now);
  return true;
}

/**
 * Reubica el punto oficial de la emergencia sincronizando despachos, bitácora y SCI
 * (usado transversalmente por NAV, SCI, CAD y ONE).
 */
export async function relocateEmergencyLocationCrossSystem({
  tenantDb,
  centralDb = null,
  idServicio,
  idSalida = '',
  licenciaSolicitante = '',
  lat,
  lng,
  accuracy = 10,
  direccion = '',
  referencia = '',
  origen = 'REUBICACION_UNIDAD',
  syncLocalSci = false
} = {}) {
  const cleanSvc = String(idServicio || '').trim();
  const nLat = Number(lat);
  const nLng = Number(lng);
  if (!tenantDb || !cleanSvc || isNaN(nLat) || isNaN(nLng) || (nLat === 0 && nLng === 0)) {
    return false;
  }

  const now = Date.now();
  const canonicalGeo = {
    lat: nLat,
    lng: nLng,
    accuracy: Number(accuracy) || 10,
    origen: String(origen || 'REUBICACION_UNIDAD').trim()
  };

  const dispatchPatch = {
    geo: canonicalGeo,
    _metaTimestamp: now
  };
  if (direccion && String(direccion).trim()) {
    dispatchPatch.direccion = String(direccion).trim().toUpperCase();
  }
  if (referencia && String(referencia).trim()) {
    dispatchPatch.referencia = String(referencia).trim().toUpperCase();
  }

  const ops = [
    setDoc(doc(tenantDb, 'despachos', cleanSvc), dispatchPatch, { merge: true })
  ];

  const cleanSalida = String(idSalida || '').trim();
  if (cleanSalida) {
    const bitPatch = { geo: canonicalGeo, _metaTimestamp: now };
    if (dispatchPatch.direccion) bitPatch.direccion = dispatchPatch.direccion;
    ops.push(updateDoc(doc(tenantDb, 'bitacora', cleanSalida), bitPatch).catch(() => {}));
  }

  if (syncLocalSci) {
    const sciPatch = {
      geoEmergencia: { lat: nLat, lng: nLng },
      updatedAt: now,
      _metaTimestamp: now
    };
    if (dispatchPatch.direccion) sciPatch.direccionEmergencia = dispatchPatch.direccion;
    ops.push(setDoc(doc(tenantDb, 'sci_incidentes', cleanSvc), sciPatch, { merge: true }).catch(() => {}));
  }

  const cleanLic = String(licenciaSolicitante || '').trim().toUpperCase();
  if (centralDb && cleanLic) {
    const centralId = buildCanonicalCentralSciIncidentId(cleanLic, cleanSvc);
    const centralPatch = {
      geoEmergencia: { lat: nLat, lng: nLng },
      updatedAt: now,
      _metaTimestamp: now
    };
    if (dispatchPatch.direccion) centralPatch.direccionEmergencia = dispatchPatch.direccion;
    ops.push(setDoc(doc(centralDb, 'sci_incidentes', centralId), centralPatch, { merge: true }).catch(() => {}));
  }

  await Promise.all(ops);
  return true;
}

/**
 * Construye y publica una Alerta Canónica en `alertas/{idAlerta}` con esquema idéntico a SENTINEL CAD.
 */
export async function publishCanonicalCadAlert(db, rawAlertParams = {}) {
  if (!db) return null;
  const canonicalAlert = buildCanonicalCadAlertDoc(rawAlertParams);
  await setDoc(doc(db, 'alertas', String(canonicalAlert.idAlerta)), canonicalAlert, { merge: true });
  return canonicalAlert;
}

// ============================================================================
// 4. COMPUERTAS ANTI-POLLING, BACKOFF EXPONENCIAL Y FUSIÓN MONOTÓNICA
// ============================================================================

/**
 * Crea una compuerta con cooldown estricto (piso mínimo 15s) para hidrataciones puntuales REST.
 */
export function createCooldownGate(cooldownMs = 60_000) {
  const safeCooldownMs = Math.max(15_000, Number(cooldownMs) || 60_000);
  let lastRunAt = 0;
  let inFlightPromise = null;

  return {
    canRun() {
      return !inFlightPromise && Date.now() - lastRunAt >= safeCooldownMs;
    },
    getRemainingMs() {
      return Math.max(0, safeCooldownMs - (Date.now() - lastRunAt));
    },
    reset() {
      lastRunAt = 0;
    },
    async runIfAllowed(taskFn, force = false) {
      if (typeof taskFn !== 'function') return null;
      if (inFlightPromise) return inFlightPromise;
      const now = Date.now();
      if (!force && now - lastRunAt < safeCooldownMs) {
        return null;
      }
      inFlightPromise = Promise.resolve()
        .then(() => taskFn())
        .then((res) => {
          lastRunAt = Date.now();
          return res;
        })
        .finally(() => {
          inFlightPromise = null;
        });
      return inFlightPromise;
    }
  };
}

/**
 * Programador de reconexión con backoff exponencial obligatorio (piso mínimo 15s -> 30s -> 60s).
 */
export function createExponentialReconnectScheduler({ baseDelayMs = 15_000, maxDelayMs = 60_000 } = {}) {
  const safeBase = Math.max(15_000, Number(baseDelayMs) || 15_000);
  const safeMax = Math.max(safeBase, Number(maxDelayMs) || 60_000);
  let attempts = 0;
  let timerId = null;

  return {
    schedule(fn) {
      if (timerId) clearTimeout(timerId);
      const delay = Math.min(safeMax, safeBase * Math.pow(2, attempts));
      attempts = Math.min(attempts + 1, 6);
      timerId = setTimeout(() => {
        timerId = null;
        if (typeof fn === 'function') fn();
      }, delay);
      return delay;
    },
    reset() {
      attempts = 0;
      if (timerId) {
        clearTimeout(timerId);
        timerId = null;
      }
    },
    cancel() {
      if (timerId) {
        clearTimeout(timerId);
        timerId = null;
      }
    },
    getAttempts() {
      return attempts;
    }
  };
}

/**
 * Fusiona un mapa local con un QuerySnapshot de Firestore aplicando "Mayor Timestamp Gana" (Ley #4).
 */
export function mergeSnapshotDocsMonotonic(prevMap = {}, querySnapshot, normalizeDocFn = null) {
  const next = { ...(prevMap && typeof prevMap === 'object' ? prevMap : {}) };
  if (!querySnapshot) return next;

  const entries = [];
  if (typeof querySnapshot.forEach === 'function') {
    querySnapshot.forEach((docSnap) => {
      const id = docSnap?.id;
      const rawData = typeof docSnap?.data === 'function' ? docSnap.data() : docSnap;
      if (id && rawData) entries.push([id, rawData]);
    });
  } else if (Array.isArray(querySnapshot)) {
    querySnapshot.forEach((item) => {
      if (item && item.id) entries.push([item.id, item]);
    });
  }

  for (const [docId, rawData] of entries) {
    const incoming = typeof normalizeDocFn === 'function' ? normalizeDocFn(docId, rawData) : { id: docId, ...rawData };
    if (!incoming) continue;
    const existing = next[docId];
    if (!existing) {
      next[docId] = incoming;
      continue;
    }
    const existingTs = getEntityMaxTimestamp(existing);
    const incomingTs = getEntityMaxTimestamp(incoming);
    if (incomingTs >= existingTs) {
      next[docId] = incoming;
    }
  }

  return next;
}

// ============================================================================
// 6. CORRELATIVOS CANÓNICOS DE DESPACHOS (`AAAA-00000`) — CERO CONTAMINACIÓN
// ============================================================================

/**
 * Calcula y persiste localmente el siguiente ID canónico de despacho `AAAA-00000`
 * leyendo ÚNICAMENTE `despachos` (nunca `bitacora`). Cuando Firestore responde con éxito,
 * reconcilia cualquier caché local obsoleto hacia el máximo real existente en la nube.
 */
export async function generateNextCanonicalDispatchId(
  db,
  {
    localDispatches = [],
    year = new Date().getFullYear(),
    storageKeyPrefix = 'sentinel_last_dispatch_seq',
    ensureAuthFn = null
  } = {}
) {
  const targetYear = parseInt(year, 10) || new Date().getFullYear();
  let maxSeq = 0;
  let firestoreOk = false;

  const inspectRecord = (docId, data = {}, allowFallbackYear = true) => {
    const createdTs = Number(data?.createdAt || data?.timestamp || data?._metaTimestamp || 0);
    let inferredYear = createdTs > 1000000000 ? new Date(createdTs).getFullYear() : null;
    if (!inferredYear && data?.fechaDespacho) {
      const mY = String(data.fechaDespacho).match(/(20\d{2})/);
      if (mY) inferredYear = parseInt(mY[1], 10);
    }
    const fallbackYear = inferredYear ?? (allowFallbackYear ? targetYear : null);
    const parsedDoc = parseDispatchIdParts(docId, fallbackYear);
    if (parsedDoc && parsedDoc.year === targetYear && parsedDoc.seq > maxSeq) {
      maxSeq = parsedDoc.seq;
    }
    const fieldId = String(data?.idServicio || data?.idDespacho || '').trim();
    if (fieldId && fieldId !== docId) {
      const parsedField = parseDispatchIdParts(fieldId, fallbackYear);
      if (parsedField && parsedField.year === targetYear && parsedField.seq > maxSeq) {
        maxSeq = parsedField.seq;
      }
    }
  };

  if (db) {
    try {
      if (typeof ensureAuthFn === 'function') {
        await ensureAuthFn().catch(() => {});
      }
      const startId = `${targetYear}-0000`;
      const endId = `${targetYear}-99999`;
      const q = query(
        collection(db, 'despachos'),
        where(documentId(), '>=', startId),
        where(documentId(), '<=', endId)
      );
      const snap = await getDocs(q);
      firestoreOk = true;
      snap.forEach((docSnap) => {
        inspectRecord(docSnap.id, docSnap.data() || {}, true);
      });

      // Respaldo V2 si el cuerpo aún mantiene documentos con ID numérico puro ("1".."999")
      if (snap.empty) {
        const fullSnap = await getDocs(collection(db, 'despachos'));
        fullSnap.forEach((docSnap) => {
          inspectRecord(docSnap.id, docSnap.data() || {}, true);
        });
      }
    } catch (e) {
      firestoreOk = false;
      console.warn('[sentinelSyncEngine] Error consultando correlativo de despachos en Firestore:', e?.message || e);
    }
  }

  // Solo considerar caché/servicios locales pendientes; ignorar valores obsoletos de localStorage si Firestore respondió
  if (Array.isArray(localDispatches)) {
    localDispatches.forEach((s) => {
      if (!s || typeof s !== 'object') return;
      const st = String(s.estado || '').trim().toLowerCase();
      const isPendingLocal = !firestoreOk || !st || st === 'activa' || st === 'pre-despacho' || st === 'en curso';
      if (isPendingLocal) {
        const sId = String(s.id || s.idDespacho || s.idServicio || '').trim();
        inspectRecord(sId, s, false);
      }
    });
  }

  if (!firestoreOk && typeof localStorage !== 'undefined') {
    try {
      const localLast = parseInt(localStorage.getItem(`${storageKeyPrefix}_${targetYear}`) || '0', 10);
      if (!isNaN(localLast) && localLast > maxSeq && localLast < 100000) {
        maxSeq = localLast;
      }
      const rawServices = localStorage.getItem('activeServices');
      if (rawServices) {
        const parsedServices = JSON.parse(rawServices);
        if (Array.isArray(parsedServices)) {
          parsedServices.forEach((s) => {
            const sId = String(s?.id || s?.idDespacho || s?.idServicio || '').trim();
            inspectRecord(sId, s, false);
          });
        }
      }
    } catch (_) {}
  }

  const nextSeq = maxSeq + 1;
  const nextId = formatCanonicalDispatchId(targetYear, nextSeq);

  if (typeof localStorage !== 'undefined') {
    try {
      localStorage.setItem(`${storageKeyPrefix}_${targetYear}`, String(nextSeq));
    } catch (_) {}
  }

  return nextId;
}
