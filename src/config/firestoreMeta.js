// ============================================================================
// ESCRITURAS FIRESTORE CON METADATO OBLIGATORIO (LEY #4 — "MAYOR TIMESTAMP GANA")
// Envoltorios de setDoc / updateDoc / addDoc que adjuntan `_metaTimestamp` (Unix ms)
// a TODA escritura del ecosistema SENTINEL V3, para que cada documento de cada
// colección registre su última actualización.
// ============================================================================
import {
  setDoc as fsSetDoc,
  updateDoc as fsUpdateDoc,
  addDoc as fsAddDoc,
  writeBatch as fsWriteBatch
} from 'firebase/firestore';

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
  return options ? fsSetDoc(ref, withMeta(data), options) : fsSetDoc(ref, withMeta(data));
}

export function updateDoc(ref, data, ...rest) {
  // Soporta la firma de objeto; la firma (campo, valor, ...) se reenvía agregando el metadato al final
  if (typeof data === 'string' || (data && typeof data === 'object' && data.constructor?.name === 'FieldPath')) {
    return fsUpdateDoc(ref, data, ...rest, '_metaTimestamp', Date.now());
  }
  return fsUpdateDoc(ref, withMeta(data));
}

export function addDoc(ref, data) {
  return fsAddDoc(ref, withMeta(data));
}

export function writeBatch(db) {
  const rawBatch = fsWriteBatch(db);
  return {
    set(ref, data, options) {
      if (options !== undefined) {
        rawBatch.set(ref, withMeta(data), options);
      } else {
        rawBatch.set(ref, withMeta(data));
      }
      return this;
    },
    update(ref, data, ...rest) {
      if (typeof data === 'string' || (data && typeof data === 'object' && data.constructor?.name === 'FieldPath')) {
        rawBatch.update(ref, data, ...rest, '_metaTimestamp', Date.now());
      } else {
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
