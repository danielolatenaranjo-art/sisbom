// ============================================================================
// ESCRITURAS FIRESTORE CON METADATO OBLIGATORIO (LEY #4 — "MAYOR TIMESTAMP GANA")
// + CORTACIRCUITOS ANTI-BUCLES EN MEMORIA (LEY #14)
// Envoltorios de setDoc / updateDoc / addDoc / writeBatch que adjuntan `_metaTimestamp`
// (Unix ms) y bloquean automáticamente ráfagas repetitivas sobre un mismo documento.
// ============================================================================
import {
  setDoc as fsSetDoc,
  updateDoc as fsUpdateDoc,
  addDoc as fsAddDoc,
  writeBatch as fsWriteBatch
} from 'firebase/firestore';

const CIRCUIT_WINDOW_MS = 30_000;
const CIRCUIT_IDENTICAL_WINDOW_MS = 5_000;
const CIRCUIT_IDENTICAL_THRESHOLD = 3;
const CIRCUIT_MAX_SAME_DOC_WRITES = 15;
const CIRCUIT_MAX_SAME_COL_ADDS = 40;
const CIRCUIT_BLOCK_DURATION_MS = 60_000;
const CIRCUIT_LOG_COOLDOWN_MS = 10_000;
const MAX_TRACKED_PATHS = 500;

const writeHistoryByPath = new Map();
const circuitBreakerTelemetry = {
  blockedWrites: 0,
  trippedPaths: new Set(),
  lastBlockedPath: '',
  lastBlockedReason: '',
  lastBlockedAt: 0
};

const stripUndefinedDeep = (val) => {
  if (val === undefined) return undefined;
  if (val === null || typeof val !== 'object') return val;
  if (Array.isArray(val)) {
    return val.map((item) => {
      const cleaned = stripUndefinedDeep(item);
      return cleaned === undefined ? null : cleaned;
    });
  }
  const proto = Object.getPrototypeOf(val);
  if (proto !== null && proto !== Object.prototype) {
    return val;
  }
  const out = {};
  for (const [k, v] of Object.entries(val)) {
    if (v !== undefined) {
      out[k] = stripUndefinedDeep(v);
    }
  }
  return out;
};

const computeStablePayloadSignature = (data) => {
  try {
    if (data === null || data === undefined) return String(data);
    if (typeof data !== 'object') return String(data);
    const seen = new WeakSet();
    const normalizeForHash = (value, depth = 0) => {
      if (value === null || value === undefined || typeof value !== 'object') {
        return value;
      }
      if (seen.has(value) || depth > 4) return '[Circular]';
      seen.add(value);
      if (Array.isArray(value)) {
        return value.map((item) => normalizeForHash(item, depth + 1));
      }
      const proto = Object.getPrototypeOf(value);
      if (proto !== null && proto !== Object.prototype) {
        return String(value?.type || value?.constructor?.name || value);
      }
      const out = {};
      const keys = Object.keys(value).sort();
      for (const k of keys) {
        if (k === '_metaTimestamp' || k === '_allowFinalizedEdit' || k === 'updatedAt') {
          continue;
        }
        out[k] = normalizeForHash(value[k], depth + 1);
      }
      return out;
    };
    return JSON.stringify(normalizeForHash(data));
  } catch {
    return '';
  }
};

const pruneWriteHistoryIfNeeded = (now) => {
  if (writeHistoryByPath.size <= MAX_TRACKED_PATHS) return;
  for (const [key, entry] of writeHistoryByPath.entries()) {
    const expiredBlock = !entry.blockedUntil || now > entry.blockedUntil;
    const staleTimestamps =
      !entry.timestamps.length || now - entry.timestamps[entry.timestamps.length - 1] > CIRCUIT_WINDOW_MS;
    if (expiredBlock && staleTimestamps) {
      writeHistoryByPath.delete(key);
    }
    if (writeHistoryByPath.size <= Math.floor(MAX_TRACKED_PATHS * 0.7)) break;
  }
};

const shouldAllowFirestoreWrite = (ref, rawPayload, opType = 'setDoc') => {
  const pathKey = `${opType === 'addDoc' ? 'ADD:' : 'DOC:'}${ref?.path || '__unknown_ref__'}`;
  const now = Date.now();
  pruneWriteHistoryIfNeeded(now);

  let entry = writeHistoryByPath.get(pathKey);
  if (!entry) {
    entry = {
      timestamps: [],
      lastSig: '',
      identicalStreak: 0,
      identicalWindowStart: now,
      blockedUntil: 0,
      lastLogAt: 0
    };
    writeHistoryByPath.set(pathKey, entry);
  }

  if (entry.blockedUntil > now) {
    circuitBreakerTelemetry.blockedWrites += 1;
    circuitBreakerTelemetry.lastBlockedPath = ref?.path || pathKey;
    circuitBreakerTelemetry.lastBlockedAt = now;
    if (now - entry.lastLogAt >= CIRCUIT_LOG_COOLDOWN_MS) {
      entry.lastLogAt = now;
      console.error(
        `[SENTINEL CIRCUIT BREAKER] Bucle de escritura bloqueado en Firestore (${opType}):`,
        ref?.path || pathKey,
        `Cooldown restante: ${Math.ceil((entry.blockedUntil - now) / 1000)}s`
      );
    }
    return false;
  }

  entry.timestamps = entry.timestamps.filter((ts) => now - ts <= CIRCUIT_WINDOW_MS);
  entry.timestamps.push(now);

  const sig = computeStablePayloadSignature(rawPayload);
  if (sig && sig === entry.lastSig && now - entry.identicalWindowStart <= CIRCUIT_IDENTICAL_WINDOW_MS) {
    entry.identicalStreak += 1;
  } else {
    entry.lastSig = sig;
    entry.identicalStreak = 1;
    entry.identicalWindowStart = now;
  }

  const maxAllowed = opType === 'addDoc' ? CIRCUIT_MAX_SAME_COL_ADDS : CIRCUIT_MAX_SAME_DOC_WRITES;
  const hitRateLimit = entry.timestamps.length > maxAllowed;
  const hitIdenticalLoop = Boolean(sig) && entry.identicalStreak >= CIRCUIT_IDENTICAL_THRESHOLD;

  if (hitRateLimit || hitIdenticalLoop) {
    entry.blockedUntil = now + CIRCUIT_BLOCK_DURATION_MS;
    entry.lastLogAt = now;
    const reason = hitIdenticalLoop
      ? `${entry.identicalStreak} escrituras idénticas consecutivas en <${CIRCUIT_IDENTICAL_WINDOW_MS / 1000}s`
      : `${entry.timestamps.length} escrituras al mismo documento en <${CIRCUIT_WINDOW_MS / 1000}s`;
    circuitBreakerTelemetry.blockedWrites += 1;
    circuitBreakerTelemetry.trippedPaths.add(ref?.path || pathKey);
    circuitBreakerTelemetry.lastBlockedPath = ref?.path || pathKey;
    circuitBreakerTelemetry.lastBlockedReason = reason;
    circuitBreakerTelemetry.lastBlockedAt = now;
    console.error(
      `[SENTINEL CIRCUIT BREAKER] Bucle de escritura bloqueado en Firestore (${opType}):`,
      ref?.path || pathKey,
      `Motivo: ${reason}`
    );
    return false;
  }

  return true;
};

export function getFirestoreCircuitBreakerStats() {
  return {
    blockedWrites: circuitBreakerTelemetry.blockedWrites,
    trippedPaths: Array.from(circuitBreakerTelemetry.trippedPaths),
    lastBlockedPath: circuitBreakerTelemetry.lastBlockedPath,
    lastBlockedReason: circuitBreakerTelemetry.lastBlockedReason,
    lastBlockedAt: circuitBreakerTelemetry.lastBlockedAt,
    trackedPaths: writeHistoryByPath.size
  };
}

export function resetFirestoreCircuitBreaker() {
  writeHistoryByPath.clear();
  circuitBreakerTelemetry.blockedWrites = 0;
  circuitBreakerTelemetry.trippedPaths.clear();
  circuitBreakerTelemetry.lastBlockedPath = '';
  circuitBreakerTelemetry.lastBlockedReason = '';
  circuitBreakerTelemetry.lastBlockedAt = 0;
}

export const withFirestoreMeta = (data) => {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return data;
  const cleaned = stripUndefinedDeep(data);
  const existingTs = Number(cleaned._metaTimestamp);
  const finalTs = (!isNaN(existingTs) && existingTs > 0) ? existingTs : Date.now();
  const next = {
    ...cleaned,
    _metaTimestamp: finalTs
  };
  if (cleaned._allowFinalizedEdit) {
    next._allowFinalizedEdit = finalTs;
  }
  return next;
};

const withMeta = withFirestoreMeta;

export function setDoc(ref, data, options) {
  if (!shouldAllowFirestoreWrite(ref, data, 'setDoc')) {
    return Promise.resolve(undefined);
  }
  return options ? fsSetDoc(ref, withMeta(data), options) : fsSetDoc(ref, withMeta(data));
}

export function updateDoc(ref, data, ...rest) {
  // Soporta la firma de objeto; la firma (campo, valor, ...) se reenvía agregando el metadato al final
  if (typeof data === 'string' || (data && typeof data === 'object' && data.constructor?.name === 'FieldPath')) {
    if (!shouldAllowFirestoreWrite(ref, [data, ...rest], 'updateDoc')) {
      return Promise.resolve(undefined);
    }
    return fsUpdateDoc(ref, data, ...rest, '_metaTimestamp', Date.now());
  }
  if (!shouldAllowFirestoreWrite(ref, data, 'updateDoc')) {
    return Promise.resolve(undefined);
  }
  return fsUpdateDoc(ref, withMeta(data));
}

export function addDoc(ref, data) {
  if (!shouldAllowFirestoreWrite(ref, data, 'addDoc')) {
    return Promise.resolve(undefined);
  }
  return fsAddDoc(ref, withMeta(data));
}

export function writeBatch(db) {
  const rawBatch = fsWriteBatch(db);
  return {
    set(ref, data, options) {
      if (!shouldAllowFirestoreWrite(ref, data, 'setDoc')) {
        return this;
      }
      if (options !== undefined) {
        rawBatch.set(ref, withMeta(data), options);
      } else {
        rawBatch.set(ref, withMeta(data));
      }
      return this;
    },
    update(ref, data, ...rest) {
      if (typeof data === 'string' || (data && typeof data === 'object' && data.constructor?.name === 'FieldPath')) {
        if (!shouldAllowFirestoreWrite(ref, [data, ...rest], 'updateDoc')) {
          return this;
        }
        rawBatch.update(ref, data, ...rest, '_metaTimestamp', Date.now());
      } else {
        if (!shouldAllowFirestoreWrite(ref, data, 'updateDoc')) {
          return this;
        }
        rawBatch.update(ref, withMeta(data));
      }
      return this;
    },
    delete(ref) {
      rawBatch.delete(ref);
      return this;
    },
    commit() {
      return rawBatch.commit();
    }
  };
}
