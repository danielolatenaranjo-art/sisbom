/**
 * ============================================================================
 * SENTINEL V3 - NÚCLEO COMPARTIDO DEL ECOSISTEMA (SHARED CORE)
 * ============================================================================
 * Reutilizable en los 8 módulos oficiales (Web React/Vite y Móvil React Native):
 * 1. SENTINEL CAD  (Central de Alarmas y Despacho)
 * 2. SENTINEL ONE  (App Móvil Operativa para Bomberos - iOS / Android)
 * 3. SENTINEL CORE (Control Operativo de Registro y Estadísticas - Capitanías / Comandancia)
 * 4. SENTINEL LINK (Enlace Telecomunicaciones Alertante ↔ Central: App Android SMS/CallerID + Web GPS)
 * 5. SENTINEL SCI  (Sistema de Comando de Incidentes - Tablet / PC: Mapa Táctico, Fuego, Viento/Clima)
 * 6. SENTINEL NAV  (Navegador Táctico en Carros Bomba - Tablet Android: Ruta Óptima y Grifos)
 * 7. SENTINEL CREW (Control y Lista de Asistencia de Bomberos)
 * 8. SENTINEL MED  (Departamento Médico: Fichas Médicas de Bomberos y Atención de Pacientes)
 *
 * LEYES FUNDAMENTALES EN ESTE NÚCLEO:
 * 1. NOMENCLATURA DE COMPAÑÍAS POR UNIDAD:
 *    - Todas las unidades de la 1ª Compañía terminan en 1 (B-1, BX-1, R-1, Z-1, BF-1...).
 *    - Todas las unidades de la 2ª Compañía terminan en 2 (B-2, BX-2, R-2, Z-2...).
 *    - Todas las unidades de la 3ª Compañía terminan en 3 (B-3, BX-3, R-3...), y así sucesivamente.
 *    - El último número del `idCarro` indica siempre la compañía a la que pertenece, además de su
 *      campo canónico `compania` administrado desde SENTINEL CORE.
 * 2. CONTROL MONOTÓNICO POR TIMESTAMP ("MAYOR TIMESTAMP GANA"):
 *    - Todo cambio de estado o de datos genera metadatos con timestamp numérico en milisegundos.
 *    - Ante cualquier cruce entre estado local y snapshot de red (internet lento), siempre prevalece
 *      el dato con el timestamp MAYOR, impidiendo retrocesos de estado o pérdida de datos.
 * 3. DUAL-READ / CANONICAL-WRITE:
 *    - Lectura compatible con datos históricos V2 y canónicos V3.
 *    - Escritura 100% ceñida a `SHARED/ESQUEMA_CANONICO_V3.md`.
 */

// ============================================================================
// 1. UTILIDADES DE TIEMPO Y FORMATO (EPOCH <-> HH:MM / DD-MM-YYYY)
// ============================================================================

export function getLocalTimeString(dateObj = new Date()) {
  const d = dateObj instanceof Date ? dateObj : new Date(dateObj || Date.now());
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

export function getLocalDateString(dateObj = new Date()) {
  const d = dateObj instanceof Date ? dateObj : new Date(dateObj || Date.now());
  return `${String(d.getDate()).padStart(2, '0')}-${String(d.getMonth() + 1).padStart(2, '0')}-${d.getFullYear()}`;
}

export function getLocalISODateString(dateObj = new Date()) {
  const d = dateObj instanceof Date ? dateObj : new Date(dateObj || Date.now());
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function formatEpochToHHMM(val) {
  if (!val) return '';
  if (typeof val === 'string') {
    const clean = val.replace(/['"]/g, '').trim();
    if (clean.includes(':')) return clean.substring(0, 5);
  }
  const num = Number(val);
  if (!isNaN(num) && num > 1000000000) {
    return getLocalTimeString(new Date(num));
  }
  if (typeof val === 'object' && typeof val.seconds === 'number') {
    return getLocalTimeString(new Date(val.seconds * 1000));
  }
  return '';
}

export function formatEpochToDDMMYYYY(val) {
  if (!val) return '';
  if (typeof val === 'string') {
    const clean = val.replace(/['"]/g, '').trim();
    if (/^\d{2}-\d{2}-\d{4}$/.test(clean)) return clean;
    if (/^\d{4}-\d{2}-\d{2}$/.test(clean)) {
      const [y, m, d] = clean.split('-');
      return `${d}-${m}-${y}`;
    }
  }
  const num = Number(val);
  if (!isNaN(num) && num > 1000000000) {
    return getLocalDateString(new Date(num));
  }
  if (typeof val === 'object' && typeof val.seconds === 'number') {
    return getLocalDateString(new Date(val.seconds * 1000));
  }
  return '';
}

export function parseToEpochMs(val, fallbackHoraStr = '', fallbackFechaStr = '') {
  if (typeof val === 'number' && !isNaN(val) && val > 1000000000) {
    return val;
  }
  if (typeof val === 'object' && val !== null && typeof val.seconds === 'number') {
    return val.seconds * 1000;
  }
  if (typeof val === 'string' && /^\d{10,15}$/.test(val.trim())) {
    return Number(val.trim());
  }
  const parseDateStr = (dStr) => {
    const clean = String(dStr || '').replace(/['"]/g, '').trim();
    if (!clean) return null;
    const m1 = clean.match(/^(\d{2})[-/](\d{2})[-/](\d{4})$/);
    if (m1) return { y: parseInt(m1[3], 10), m: parseInt(m1[2], 10), d: parseInt(m1[1], 10) };
    const m2 = clean.match(/^(\d{4})[-/](\d{2})[-/](\d{2})/);
    if (m2) return { y: parseInt(m2[1], 10), m: parseInt(m2[2], 10), d: parseInt(m2[3], 10) };
    return null;
  };
  const timeStr = (typeof val === 'string' && val.includes(':')) ? val.trim() : String(fallbackHoraStr || '').trim();
  if (timeStr && timeStr.includes(':')) {
    const [hh, mm] = timeStr.replace(/['"]/g, '').split(':').map(n => parseInt(n, 10));
    if (!isNaN(hh) && !isNaN(mm)) {
      const parsedD = parseDateStr(fallbackFechaStr);
      if (parsedD && !isNaN(parsedD.y) && !isNaN(parsedD.m) && !isNaN(parsedD.d)) {
        return new Date(parsedD.y, parsedD.m - 1, parsedD.d, hh, mm, 0, 0).getTime();
      }
      // Si no hay fecha de respaldo explícita y val era nulo/vacío, no inventar fecha "hoy" para registros vacíos
      if (!val && !fallbackFechaStr) return null;
      const base = new Date();
      base.setHours(hh, mm, 0, 0);
      return base.getTime();
    }
  }
  return null;
}

// ============================================================================
// 2. RESOLUCIÓN CANÓNICA DE COMPAÑÍAS Y UNIDADES (SENTINEL CORE / CAD / ONE)
// Regla Bomberil Chilena:
// - Todas las unidades de la 1ª Compañía terminan en 1 (B-1, BX-1, R-1, Z-1...)
// - Todas las unidades de la 2ª Compañía terminan en 2 (B-2, BX-2, R-2, Z-2...)
// - Todas las unidades de la 3ª Compañía terminan en 3 (B-3, BX-3, R-3...), etc.
// - El último número del `idCarro` es al que le corresponde de la compañía.
// ============================================================================

export function getCompanyOrdinalWord(compId) {
  const num = parseInt(String(compId).replace(/\D/g, ''), 10);
  const ordinals = {
    1: 'PRIMERA',
    2: 'SEGUNDA',
    3: 'TERCERA',
    4: 'CUARTA',
    5: 'QUINTA',
    6: 'SEXTA',
    7: 'SÉPTIMA',
    8: 'OCTAVA',
    9: 'NOVENA',
    10: 'DÉCIMA',
    11: 'DÉCIMO PRIMERA',
    12: 'DÉCIMO SEGUNDA'
  };
  return ordinals[num] || `${compId}ª`;
}

/**
 * Extrae el número de compañía a partir del último número del `idCarro` (ej. "B-1" -> 1, "BX-2" -> 2, "BT-3" -> 3)
 * o desde el campo canónico `compania` si fue provisto explícitamente.
 */
export function resolveCompanyFromVehicleId(idCarro, explicitCompania = null) {
  const cleanId = String(idCarro || '').trim().toUpperCase();
  const matches = cleanId.match(/\d+/g);
  if (matches && matches.length > 0) {
    const lastNum = parseInt(matches[matches.length - 1], 10);
    if (!isNaN(lastNum) && lastNum > 0) {
      return lastNum;
    }
  }
  if (explicitCompania !== undefined && explicitCompania !== null && String(explicitCompania).trim() !== '') {
    const parsed = parseInt(String(explicitCompania).replace(/\D/g, ''), 10);
    if (!isNaN(parsed) && parsed > 0) return parsed;
  }
  return 1;
}

function resolveCompanyIdFromKeyOrData(key, data) {
  const rawAlias = String(data?.alias ?? data?.numero ?? data?.id ?? '').replace(/\D/g, '').trim();
  if (rawAlias) return rawAlias;
  const keyDigits = String(key || '').replace(/\D/g, '').trim();
  if (keyDigits) return keyDigits;
  const combined = `${String(key || '')} ${String(data?.nombre || '')}`
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
  const wordMap = [
    ['decimo segunda', '12'], ['decimosegunda', '12'],
    ['decimo primera', '11'], ['decimoprimera', '11'],
    ['decima', '10'],
    ['novena', '9'],
    ['octava', '8'],
    ['septima', '7'],
    ['sexta', '6'],
    ['quinta', '5'],
    ['cuarta', '4'],
    ['tercera', '3'],
    ['segunda', '2'],
    ['primera', '1']
  ];
  for (const [word, num] of wordMap) {
    if (combined.includes(word)) return num;
  }
  return '1';
}

/**
 * Resuelve las compañías activas de la institución a partir de `cuarteles` (licencia)
 * y de las unidades reales registradas (`vehicles`), donde el último número de `idCarro`
 * y el campo canónico `compania` determinan la compañía de cada carro.
 */
export function getInstitutionCompanies(cuarteles, vehicles) {
  const compMap = new Map();

  // 1. Compañías declaradas en la licencia (`saas_clientes.companias` / `cuarteles`)
  if (cuarteles && typeof cuarteles === 'object' && Object.keys(cuarteles).length > 0) {
    Object.entries(cuarteles).forEach(([key, data]) => {
      if (!data || typeof data !== 'object') return;
      const safeId = resolveCompanyIdFromKeyOrData(key, data);
      const prev = compMap.get(safeId);
      const defaultCuartel = safeId === '1' ? 'CUARTEL CENTRAL' : `CUARTEL ${safeId}ª CÍA`;
      const incomingName = String(data?.nombre || '').trim();
      const incomingCuartel = String(data?.cuartel || '').trim();
      const incomingLat = data?.lat ?? data?.latitud ?? data?.latCuartel ?? data?.cuartel_lat ?? data?.cuartelLat ?? null;
      const incomingLng = data?.lng ?? data?.longitud ?? data?.lngCuartel ?? data?.cuartel_long ?? data?.cuartel_lng ?? data?.cuartelLng ?? null;

      compMap.set(safeId, {
        id: safeId,
        ordinal: getCompanyOrdinalWord(safeId),
        name: incomingName || prev?.name || `${safeId}ª COMPAÑÍA`,
        alias: `${safeId}ª CÍA`,
        cuartel: incomingCuartel || prev?.cuartel || defaultCuartel,
        lat: incomingLat ?? prev?.lat ?? null,
        lng: incomingLng ?? prev?.lng ?? null
      });
    });
  }

  // 2. Compañías determinadas por la flota real de carros (último número de idCarro / campo compania)
  if (Array.isArray(vehicles) && vehicles.length > 0) {
    vehicles.forEach(v => {
      const unitId = v?.idCarro || v?.id || '';
      const compNum = resolveCompanyFromVehicleId(unitId, v?.compania);
      const safeId = String(compNum || 1);
      if (!compMap.has(safeId)) {
        compMap.set(safeId, {
          id: safeId,
          ordinal: getCompanyOrdinalWord(safeId),
          name: `${safeId}ª COMPAÑÍA`,
          alias: `${safeId}ª CÍA`,
          cuartel: safeId === '1' ? 'CUARTEL CENTRAL' : `CUARTEL ${safeId}ª CÍA`,
          lat: null,
          lng: null
        });
      }
    });
  }

  if (compMap.size > 0) {
    return Array.from(compMap.values()).sort((a, b) => parseInt(a.id, 10) - parseInt(b.id, 10));
  }

  return [
    {
      id: '1',
      ordinal: 'PRIMERA',
      name: '1ª COMPAÑÍA',
      alias: '1ª CÍA',
      cuartel: 'CUARTEL CENTRAL',
      lat: null,
      lng: null
    }
  ];
}

export function isSingleCompanyInstitution(cuarteles, vehicles) {
  return getInstitutionCompanies(cuarteles, vehicles).length <= 1;
}

/**
 * Función transversal del ecosistema SENTINEL (`sentinelCore`) para resolver el estado operativo
 * y disponibilidad real de un carro (`0-9 DISP`, `0-8 F/S` o `EN {CLAVE}`).
 * Reutilizable en SENTINEL CAD (Despacho, Agregar Unidad Refuerzo, Mapa Táctico, Material Mayor),
 * SENTINEL CORE, SENTINEL ONE y SENTINEL SCI.
 */
export function resolveCoreUnitStatusInfo(
  unitOrId,
  activeServicesList = [],
  allVehiclesList = [],
  bitacoraMapOrList = null,
  hasLoadedServices = true
) {
  const vehiclesList = Array.isArray(allVehiclesList) ? allVehiclesList : [];
  const servicesList = Array.isArray(activeServicesList) ? activeServicesList : [];

  const unitObj = (typeof unitOrId === 'object' && unitOrId !== null)
    ? unitOrId
    : vehiclesList.find(
        v => String(v.idCarro || v.id || '').replace(/-/g, '').trim().toUpperCase() === String(unitOrId || '').replace(/-/g, '').trim().toUpperCase()
      ) || { idCarro: String(unitOrId || '') };

  const unitId = String(unitObj.idCarro || unitObj.id || '').trim().toUpperCase();
  const normUnitId = unitId.replace(/-/g, '');
  const rawServ = String(unitObj.enServicio ?? '0').trim();
  const rawEst = String(unitObj.estado ?? '0-9').trim().toLowerCase();

  // 1. Verificar si está 0-8 Fuera de Servicio
  const is08 = rawEst === '0' || rawEst === '0-8' || rawEst === 'false' || rawServ === '0-8';
  if (is08) {
    return {
      isAvailable: false,
      is08: true,
      isInService: false,
      activeClave: '0-8',
      activeServiceId: null,
      badgeLabel: '0-8 F/S'
    };
  }

  // 2. Buscar si la unidad está asignada activamente en algún despacho de `activeServicesList`
  const activeSvcByUnit = servicesList.find(s =>
    (s.units || []).some(u => String(u?.name || '').replace(/-/g, '').trim().toUpperCase() === normUnitId)
  );
  if (activeSvcByUnit) {
    const clave = String(activeSvcByUnit.clave || activeSvcByUnit.key || 'ACTO').trim().toUpperCase();
    return {
      isAvailable: false,
      is08: false,
      isInService: true,
      activeClave: clave,
      activeServiceId: String(activeSvcByUnit.id || activeSvcByUnit.idServicio || ''),
      badgeLabel: `EN ${clave}`
    };
  }

  // 3. Si `enServicio` tiene un valor distinto de disponible ("0" / "0-9" / "")
  const isFreeRaw = rawServ === '0' || rawServ === '0-9' || rawServ === '';
  if (!isFreeRaw) {
    // ¿Es un ID de servicio tipo "2026-0008"?
    if (/^\d{4}-\d+$/.test(rawServ)) {
      const matchingSvc = servicesList.find(s => String(s.id || s.idServicio).trim() === rawServ);
      if (matchingSvc) {
        const inActiveUnits = (matchingSvc.units || []).some(
          u => String(u?.name || '').replace(/-/g, '').trim().toUpperCase() === normUnitId
        );
        if (inActiveUnits) {
          const clave = String(matchingSvc.clave || matchingSvc.key || 'ACTO').trim().toUpperCase();
          return {
            isAvailable: false,
            is08: false,
            isInService: true,
            activeClave: clave,
            activeServiceId: rawServ,
            badgeLabel: `EN ${clave}`
          };
        }
      }

      if (!hasLoadedServices) {
        let bitClave = '';
        if (bitacoraMapOrList && typeof bitacoraMapOrList.forEach === 'function') {
          bitacoraMapOrList.forEach(b => {
            if (b?.idServicio === rawServ && b?.clave) bitClave = b.clave;
          });
        }
        const labelClave = bitClave ? String(bitClave).toUpperCase() : 'SERVICIO';
        return {
          isAvailable: false,
          is08: false,
          isInService: true,
          activeClave: labelClave,
          activeServiceId: rawServ,
          badgeLabel: `EN ${labelClave}`
        };
      }

      return {
        isAvailable: true,
        is08: false,
        isInService: false,
        activeClave: null,
        activeServiceId: null,
        badgeLabel: '0-9 DISP'
      };
    }

    const cleanClave = rawServ === '1' ? 'ACTO' : rawServ.toUpperCase();
    return {
      isAvailable: false,
      is08: false,
      isInService: true,
      activeClave: cleanClave,
      activeServiceId: rawServ,
      badgeLabel: `EN ${cleanClave}`
    };
  }

  return {
    isAvailable: true,
    is08: false,
    isInService: false,
    activeClave: null,
    activeServiceId: null,
    badgeLabel: '0-9 DISP'
  };
}

// ============================================================================
// 3. MOTOR DE METADATOS CON TIMESTAMP ANTI-RETROCESOS ("EL MAYOR TIMESTAMP GANA")
// ============================================================================

const STATUS_PROGRESSION_ORDER = {
  pending_departure: 0,
  despachado: 0,
  en_trayecto: 1,
  'en trayecto': 1,
  en_lugar: 2,
  'en lugar': 2,
  retorno: 3,
  finalizado: 4,
  'en cuartel': 4,
  disponible: 5,
  '6-8': 5
};

/**
 * Calcula el timestamp máximo (más reciente) de cualquier documento o entidad
 * revisando todos sus campos temporales y metadatos.
 */
export function getEntityMaxTimestamp(entity = {}) {
  if (!entity || typeof entity !== 'object') return 0;
  const candidates = [
    entity._metaTimestamp,
    entity.lastLocalUpdate,
    entity.timestamp,
    entity.finalizadoAt,
    entity.h67,
    entity.createdAt,
    entity.epoch68,
    entity.epoch610,
    entity.epoch69,
    entity.epoch63,
    entity.epoch60,
    entity.tiempos?.h68,
    entity.tiempos?.h610,
    entity.tiempos?.h69,
    entity.tiempos?.h63,
    entity.tiempos?.h60,
    entity.tiempos?.h615,
    entity.solicitudConductorTimestamp,
    entity.solicitudPersonalTimestamp,
    entity.aprobadoAt,
    entity.queuedAt
  ];

  if (entity._fieldTimestamps && typeof entity._fieldTimestamps === 'object') {
    Object.values(entity._fieldTimestamps).forEach(ts => candidates.push(ts));
  }

  if (entity.unidades && typeof entity.unidades === 'object') {
    Object.values(entity.unidades).forEach(u => {
      if (u && typeof u === 'object') {
        candidates.push(u.solicitudConductorTimestamp, u.solicitudPersonalTimestamp, u._metaTimestamp);
      }
    });
  }

  let maxTs = 0;
  for (const c of candidates) {
    const num = Number(c);
    if (!isNaN(num) && num > maxTs) {
      maxTs = num;
    }
  }
  return maxTs;
}

/**
 * Fusiona dos estados de una unidad (local vs remoto de Firestore) garantizando
 * que SIEMPRE gane el proceso con el MAYOR timestamp y que los estados de salida
 * (6-0 -> 6-3 -> 6-9 -> 6-10 -> 6-8) jamás retrocedan por un snapshot atrasado.
 */
export function mergeServiceUnitByHigherTimestamp(localUnit = {}, remoteUnit = {}) {
  if (!localUnit || !localUnit.name) return remoteUnit;
  if (!remoteUnit || !remoteUnit.name) return localUnit;

  const localTs = getEntityMaxTimestamp(localUnit);
  const remoteTs = getEntityMaxTimestamp(remoteUnit);
  const localFieldTs = localUnit._fieldTimestamps || {};
  const remoteFieldTs = remoteUnit._fieldTimestamps || {};

  // Los timestamps progresivos de salida (6-0, 6-3, 6-9, 6-10, 6-8) son monotónicos:
  // si ya existe un timestamp en cualquiera de los dos, se conserva el mayor (nunca vuelve a null).
  const epoch60 = Math.max(Number(localUnit.epoch60 || 0), Number(remoteUnit.epoch60 || 0)) || null;
  const epoch63 = Math.max(Number(localUnit.epoch63 || 0), Number(remoteUnit.epoch63 || 0)) || null;
  const epoch69 = Math.max(Number(localUnit.epoch69 || 0), Number(remoteUnit.epoch69 || 0)) || null;
  const epoch610 = Math.max(Number(localUnit.epoch610 || 0), Number(remoteUnit.epoch610 || 0)) || null;
  const epoch68 = Math.max(Number(localUnit.epoch68 || 0), Number(remoteUnit.epoch68 || 0)) || null;

  const departureTime = formatEpochToHHMM(epoch60) || localUnit.departureTime || remoteUnit.departureTime || '';
  const arrivalTime = formatEpochToHHMM(epoch63) || localUnit.arrivalTime || remoteUnit.arrivalTime || '';
  const time69 = formatEpochToHHMM(epoch69) || localUnit.time69 || remoteUnit.time69 || '';
  const time610 = formatEpochToHHMM(epoch610) || localUnit.time610 || remoteUnit.time610 || '';
  const time68 = formatEpochToHHMM(epoch68) || localUnit.time68 || remoteUnit.time68 || '';

  // Resolver estado sin retroceso
  const localOrder = STATUS_PROGRESSION_ORDER[localUnit.status] ?? 0;
  const remoteOrder = STATUS_PROGRESSION_ORDER[remoteUnit.status] ?? 0;
  let status = localOrder >= remoteOrder ? localUnit.status : remoteUnit.status;
  if (epoch68 || time68) status = '6-8';
  else if (epoch610 || time610) status = 'finalizado';
  else if (epoch69 || time69) status = 'retorno';
  else if (epoch63 || arrivalTime) status = 'en_lugar';
  else if (epoch60 || departureTime) status = 'en_trayecto';

  // Función auxiliar por campo: gana el campo con mayor timestamp de modificación;
  // si un lado tiene dato y el otro está vacío, se preserva el dato no vacío a menos que el vacío tenga un timestamp explícito mayor.
  const pickField = (fieldName) => {
    const lVal = localUnit[fieldName];
    const rVal = remoteUnit[fieldName];
    const lFts = Number(localFieldTs[fieldName] || localTs || 0);
    const rFts = Number(remoteFieldTs[fieldName] || remoteTs || 0);

    const lHas = lVal !== undefined && lVal !== null && String(lVal).trim() !== '';
    const rHas = rVal !== undefined && rVal !== null && String(rVal).trim() !== '';

    if (lHas && !rHas) {
      return (Number(remoteFieldTs[fieldName] || 0) > lFts) ? rVal : lVal;
    }
    if (!lHas && rHas) {
      return (Number(localFieldTs[fieldName] || 0) > rFts) ? lVal : rVal;
    }
    return lFts >= rFts ? lVal : rVal;
  };

  const mergedFieldTimestamps = {
    ...remoteFieldTs,
    ...localFieldTs
  };
  Object.keys(mergedFieldTimestamps).forEach(k => {
    mergedFieldTimestamps[k] = Math.max(Number(localFieldTs[k] || 0), Number(remoteFieldTs[k] || 0));
  });

  return {
    ...remoteUnit,
    ...localUnit,
    name: localUnit.name || remoteUnit.name,
    idSalida: localUnit.idSalida || remoteUnit.idSalida || '',
    status,
    driverRad: pickField('driverRad') || '',
    obacRad: pickField('obacRad') || '',
    count: pickField('count') || '',
    tripInfo: pickField('tripInfo') || '',
    report: pickField('report') || '',
    observation: pickField('observation') || '',
    kmVal: pickField('kmVal') || '',
    departureTime,
    arrivalTime,
    time69,
    time610,
    time68,
    epoch60,
    epoch63,
    epoch69,
    epoch610,
    epoch68,
    solicitudConductorTimestamp: Math.max(
      Number(localUnit.solicitudConductorTimestamp || 0),
      Number(remoteUnit.solicitudConductorTimestamp || 0)
    ) || null,
    solicitudPersonalTimestamp: Math.max(
      Number(localUnit.solicitudPersonalTimestamp || 0),
      Number(remoteUnit.solicitudPersonalTimestamp || 0)
    ) || null,
    _metaTimestamp: Math.max(localTs, remoteTs, Date.now()),
    _fieldTimestamps: mergedFieldTimestamps
  };
}

// ============================================================================
// 4. LECTORES DUALES (DUAL-READ: COMPATIBLE CON HISTÓRICO V2 Y CANÓNICO V3)
// ============================================================================

/**
 * Extrae el código radial desde un campo que puede ser objeto canónico V3 `{ idRadial, nombre }`
 * o string histórico V2 `"112"` o `"1 - CRISTIAN LOPEZ MELLA"`.
 */
export function extractPersonRadial(val) {
  if (!val) return '';
  if (typeof val === 'object') {
    const rawRad = String(val.idRadial || val.codigoRadial || val.id || '').trim();
    const sepMatch = rawRad.match(/^([A-Z0-9-]+)\s*(?:•|-)\s*(.+)$/);
    return sepMatch ? sepMatch[1].trim() : rawRad;
  }
  const str = String(val).trim();
  const sepMatch = str.match(/^([A-Z0-9-]+)\s*(?:•|-)\s*(.+)$/);
  return sepMatch ? sepMatch[1].trim() : str;
}

export function extractPersonName(val) {
  if (!val) return '';
  if (typeof val === 'object') {
    const explicitName = String(val.nombre || val.nombreBombero || '').trim();
    if (explicitName) return explicitName;
    const rawRad = String(val.idRadial || val.codigoRadial || '').trim();
    const sepMatch = rawRad.match(/^([A-Z0-9-]+)\s*(?:•|-)\s*(.+)$/);
    return sepMatch ? sepMatch[2].trim() : '';
  }
  const str = String(val).trim();
  const sepMatch = str.match(/^([A-Z0-9-]+)\s*(?:•|-)\s*(.+)$/);
  return sepMatch ? sepMatch[2].trim() : '';
}

/**
 * Formatea el nombre de una persona mostrando el primer nombre y el penúltimo nombre registrado
 * (ej. "CRISTIAN ANDRES LOPEZ MELLA" -> "CRISTIAN LOPEZ").
 * Si la cadena incluye un prefijo separado por " - " (ej. "1 - CRISTIAN ANDRES LOPEZ MELLA" o
 * "COMANDANTE - CRISTIAN ANDRES LOPEZ MELLA"), preserva el prefijo y formatea la parte del nombre.
 */
export function formatShortPersonName(rawName) {
  if (!rawName) return '';
  let str = '';
  if (typeof rawName === 'object') {
    str = String(rawName.nombre || rawName.nombreBombero || '').trim();
  } else {
    str = String(rawName).trim();
  }
  if (!str) return '';

  const formatWords = (part) => {
    const clean = String(part || '').trim();
    if (!clean) return '';
    const parenMatch = clean.match(/^(.*?)\s*(\([^)]*\))$/);
    if (parenMatch) {
      const base = parenMatch[1].trim();
      const suffix = parenMatch[2].trim();
      const words = base.split(/\s+/).filter(Boolean);
      const shortBase = words.length >= 3 ? `${words[0]} ${words[words.length - 2]}` : words.join(' ');
      return `${shortBase} ${suffix}`.trim();
    }
    const words = clean.split(/\s+/).filter(Boolean);
    if (words.length >= 3) {
      return `${words[0]} ${words[words.length - 2]}`;
    }
    return words.join(' ');
  };

  const dashIdx = str.indexOf(' - ');
  if (dashIdx !== -1) {
    const prefix = str.slice(0, dashIdx).trim();
    const rest = str.slice(dashIdx + 3).trim();
    if (!rest) return prefix;
    return `${prefix} - ${formatWords(rest)}`;
  }

  return formatWords(str);
}

/**
 * Normaliza un documento de `bitacora/{idSalida}` leyendo tanto el esquema canónico V3
 * (`SHARED/ESQUEMA_CANONICO_V3.md`: `conductor: { idRadial, nombre }`, `obac: { idRadial, nombre }`,
 * `cuantosBomberos`, `tripulacion: []`, `tiempos: { h60, h63, h68, h69, h610, h615 }`, `estadoMovil`)
 * como el esquema histórico V2 (`hora60`, `fecha60`, `idCarro`, `lugar`, `cuantosBomberos`, `km`).
 */
export function normalizeBitacoraDoc(docId, raw = {}) {
  if (!raw || typeof raw !== 'object') return null;
  const idSalida = String(raw.idSalida || raw.ID || docId || '').trim();
  const idServicio = String(raw.idServicio || raw.idDespacho || '').trim();
  const carro = String(raw.carro || raw.idCarro || '').trim().toUpperCase();
  const compania = resolveCompanyFromVehicleId(carro, raw.compania);
  const clave = String(raw.clave || raw.key || '').trim();
  const subclave = String(raw.subclave || '').trim();
  const claveApoyo = String(raw.claveApoyo || '').trim();
  const direccion = String(raw.direccion || raw.lugar || '').trim();

  const conductorRadial = extractPersonRadial(raw.conductor || raw.driverRad);
  const conductorNombre = extractPersonName(raw.conductor);
  const obacRadial = extractPersonRadial(raw.obac || raw.obacRad);
  const obacNombre = extractPersonName(raw.obac);

  const rawTripulacion = raw.tripulacion;
  const tripulacionArray = Array.isArray(rawTripulacion) ? rawTripulacion : [];
  const cuantosBomberosVal = raw.cuantosBomberos !== undefined && raw.cuantosBomberos !== null
    ? raw.cuantosBomberos
    : (typeof rawTripulacion === 'string' || typeof rawTripulacion === 'number'
      ? rawTripulacion
      : (raw.cantidadTripulantes ?? raw.tripulantes ?? (tripulacionArray.length > 0 ? tripulacionArray.length : '')));

  const kmLlegada = parseInt(String(raw.kmLlegada ?? raw.km ?? '0').replace(/\D/g, ''), 10) || 0;

  const baseRefDate = String(
    raw.fecha60 || raw.fechaDespacho || raw.fecha || raw.fecha63 || raw.fecha69 || raw.fecha610 || raw.fecha68 || ''
  ).trim();

  const h60 = parseToEpochMs(raw.tiempos?.h60 ?? raw.h60, raw.hora60, raw.fecha60 || baseRefDate);
  const h63 = parseToEpochMs(raw.tiempos?.h63 ?? raw.h63, raw.hora63, raw.fecha63 || baseRefDate);
  const h69 = parseToEpochMs(raw.tiempos?.h69 ?? raw.h69, raw.hora69, raw.fecha69 || baseRefDate);
  const h610 = parseToEpochMs(raw.tiempos?.h610 ?? raw.h610, raw.hora610, raw.fecha610 || baseRefDate);
  const h68 = parseToEpochMs(raw.tiempos?.h68 ?? raw.h68, raw.hora68, raw.fecha68 || baseRefDate);
  const h615 = parseToEpochMs(raw.tiempos?.h615 ?? raw.h615, raw.hora615, raw.fecha615 || baseRefDate);

  const hora60 = formatEpochToHHMM(h60) || String(raw.hora60 || '').replace(/['"]/g, '').trim();
  const hora63 = formatEpochToHHMM(h63) || String(raw.hora63 || '').replace(/['"]/g, '').trim();
  const hora69 = formatEpochToHHMM(h69) || String(raw.hora69 || '').replace(/['"]/g, '').trim();
  const hora610 = formatEpochToHHMM(h610) || String(raw.hora610 || '').replace(/['"]/g, '').trim();
  const hora68 = formatEpochToHHMM(h68) || String(raw.hora68 || '').replace(/['"]/g, '').trim();

  const fecha60 = formatEpochToDDMMYYYY(h60) || String(raw.fecha60 || baseRefDate).trim();
  const fecha63 = formatEpochToDDMMYYYY(h63) || String(raw.fecha63 || fecha60).trim();
  const fecha69 = formatEpochToDDMMYYYY(h69) || String(raw.fecha69 || fecha60).trim();
  const fecha610 = formatEpochToDDMMYYYY(h610) || String(raw.fecha610 || fecha60).trim();
  const fecha68 = formatEpochToDDMMYYYY(h68) || String(raw.fecha68 || fecha60).trim();

  const preinforme = String(raw.preinforme ?? raw.preInforme ?? raw.preinformeUnidad ?? '').trim();
  const informe63 = String(raw.informe63 ?? raw.report ?? '').trim();
  const observacion = String(raw.observacion ?? raw.observaciones ?? raw.motivo ?? '').trim();

  let unitStatus = 'pending_departure';
  let estadoMovil = String(raw.estadoMovil || '').trim();
  if (h68 || hora68) {
    unitStatus = '6-8';
    if (!estadoMovil) estadoMovil = 'en cuartel';
  } else if (h610 || hora610) {
    unitStatus = 'finalizado';
    if (!estadoMovil) estadoMovil = 'en cuartel';
  } else if (h69 || hora69) {
    unitStatus = 'retorno';
    if (!estadoMovil) estadoMovil = 'retorno';
  } else if (h63 || hora63) {
    unitStatus = 'en_lugar';
    if (!estadoMovil) estadoMovil = 'en lugar';
  } else if (h60 || hora60) {
    unitStatus = 'en_trayecto';
    if (!estadoMovil) estadoMovil = 'en trayecto';
  }

  const maxTs = Math.max(h68 || 0, h610 || 0, h69 || 0, h63 || 0, h60 || 0, h615 || 0, Number(raw._metaTimestamp || 0));

  return {
    idSalida,
    idServicio,
    carro,
    compania,
    clave,
    subclave,
    claveApoyo,
    direccion,
    conductorObj: { idRadial: conductorRadial, nombre: conductorNombre },
    obacObj: { idRadial: obacRadial, nombre: obacNombre },
    conductor: conductorRadial,
    obac: obacRadial,
    cuantosBomberos: cuantosBomberosVal,
    tripulacion: tripulacionArray,
    tripulantes: String(cuantosBomberosVal ?? ''),
    kmLlegada,
    estadoMovil,
    tiempos: { h60, h63, h68, h69, h610, h615 },
    h60,
    h63,
    h69,
    h610,
    h68,
    h615,
    preinforme,
    informe63,
    observacion,
    hora60,
    hora63,
    hora69,
    hora610,
    hora68,
    fecha60,
    fecha63,
    fecha69,
    fecha610,
    fecha68,
    unitStatus,
    _metaTimestamp: maxTs
  };
}

/**
 * Convierte un registro normalizado de `bitacora` en el objeto `unit` en memoria que usa EmergencyControlView,
 * aplicando fusión por Mayor Timestamp (`mergeServiceUnitByHigherTimestamp`) para impedir retrocesos.
 */
export function bitacoraToServiceUnit(bitacoraNorm, existingUnit = {}, dispatchUnitMeta = {}) {
  const b = bitacoraNorm || {};
  const ex = existingUnit || {};
  const meta = dispatchUnitMeta || {};

  const departureTime = b.hora60 || ex.departureTime || '';
  const arrivalTime = b.hora63 || ex.arrivalTime || '';
  const time69 = b.hora69 || ex.time69 || '';
  const time610 = b.hora610 || ex.time610 || '';
  const time68 = b.hora68 || ex.time68 || '';

  let status = ex.status || 'pending_departure';
  if (time68 || b.tiempos?.h68) {
    status = '6-8';
  } else if (time610 || b.tiempos?.h610) {
    status = 'finalizado';
  } else if (time69 || b.tiempos?.h69) {
    status = 'retorno';
  } else if (arrivalTime || b.tiempos?.h63) {
    status = 'en_lugar';
  } else if (departureTime || b.tiempos?.h60) {
    status = 'en_trayecto';
  }

  const remoteCandidate = {
    name: b.carro || ex.name || '',
    idSalida: b.idSalida || meta.idSalida || ex.idSalida || '',
    status,
    driverRad: b.conductor || '',
    obacRad: b.obac || '',
    count: b.cuantosBomberos !== undefined && b.cuantosBomberos !== '' ? String(b.cuantosBomberos) : '',
    tripInfo: b.preinforme || '',
    report: b.informe63 || '',
    observation: b.observacion || '',
    kmVal: b.kmLlegada ? String(b.kmLlegada) : '',
    departureTime,
    arrivalTime,
    time69,
    time610,
    time68,
    epoch60: b.tiempos?.h60 || null,
    epoch63: b.tiempos?.h63 || null,
    epoch69: b.tiempos?.h69 || null,
    epoch610: b.tiempos?.h610 || null,
    epoch68: b.tiempos?.h68 || null,
    is615: Boolean(b.tiempos?.h615 || ex.is615),
    departureTime615: formatEpochToHHMM(b.tiempos?.h615) || ex.departureTime615 || '',
    solicitudConductorTimestamp: meta.solicitudConductorTimestamp || ex.solicitudConductorTimestamp || null,
    solicitudPersonalTimestamp: meta.solicitudPersonalTimestamp || ex.solicitudPersonalTimestamp || null,
    _metaTimestamp: b._metaTimestamp || 0
  };

  return mergeServiceUnitByHigherTimestamp(ex, remoteCandidate);
}

// ============================================================================
// 5. CONSTRUCTORES CANÓNICOS ESTRICTOS (CANONICAL-WRITE: 100% ESQUEMA_CANONICO_V3.md)
// ============================================================================

/**
 * 1. Colección `despachos/{idServicio}` — Campos canónicos de ESQUEMA_CANONICO_V3.md
 */
export const CANONICAL_DISPATCH_KEYS = new Set([
  'idServicio',
  'clave',
  'subclave',
  'claveApoyo',
  'direccion',
  'referencia',
  'comuna',
  'geo',
  'alertante',
  'companias',
  'unidades',
  'estado',
  'createdAt',
  'h67',
  'finalizadoAt',
  'operadorInicial',
  'operadorFinal',
  'preinforme',
  'informeObac',
  'observacion',
  'idLista',
  'idListaPrincipal',
  'obacRad',
  'obacServicio',
  'sirena',
  'visibleMovil',
  'pushSent'
]);

export function sanitizeCanonicalUnidadesMap(unidadesInput = {}, unitsArray = []) {
  const cleanMap = {};

  if (unidadesInput && typeof unidadesInput === 'object' && !Array.isArray(unidadesInput)) {
    Object.entries(unidadesInput).forEach(([carro, val]) => {
      if (!carro) return;
      const uObj = (typeof val === 'object' && val !== null) ? val : {};
      const entry = {
        idSalida: uObj.idSalida ? String(uObj.idSalida).trim() : (typeof val === 'string' ? val.trim() : '')
      };
      if (uObj.solicitudConductorTimestamp !== undefined) {
        entry.solicitudConductorTimestamp = uObj.solicitudConductorTimestamp ? Number(uObj.solicitudConductorTimestamp) : null;
      }
      if (uObj.solicitudPersonalTimestamp !== undefined) {
        entry.solicitudPersonalTimestamp = uObj.solicitudPersonalTimestamp ? Number(uObj.solicitudPersonalTimestamp) : null;
      }
      cleanMap[carro] = entry;
    });
  }

  if (Array.isArray(unitsArray)) {
    unitsArray.forEach(u => {
      const name = typeof u === 'string' ? u : (u?.name || u?.carro || '');
      if (!name) return;
      const existing = cleanMap[name] || {};
      const entry = {
        idSalida: String((typeof u === 'object' ? u.idSalida : '') || existing.idSalida || '').trim()
      };
      const condTs = (typeof u === 'object' ? u.solicitudConductorTimestamp : null) || existing.solicitudConductorTimestamp;
      const persTs = (typeof u === 'object' ? u.solicitudPersonalTimestamp : null) || existing.solicitudPersonalTimestamp;
      if (condTs) entry.solicitudConductorTimestamp = Number(condTs);
      if (persTs) entry.solicitudPersonalTimestamp = Number(persTs);
      cleanMap[name] = entry;
    });
  }

  return cleanMap;
}

/**
 * 2. Colección `bitacora/{idSalida}` — Estructura exacta de `SHARED/ESQUEMA_CANONICO_V3.md` (líneas 79-128):
 * - `idSalida`, `idServicio`, `carro`, `compania`, `clave`, `subclave`, `claveApoyo`,
 * - `conductor: { idRadial, nombre }`, `obac: { idRadial, nombre }`,
 * - `cuantosBomberos`, `tripulacion: []`, `kmLlegada`, `estadoMovil`,
 * - `tiempos: { h60, h63, h68, h69, h610, h615 }`,
 * - `observacion`, `preinforme`, `informe63`
 */
export function buildCanonicalBitacoraPayload(svc = {}, unit = {}, vehicles = [], personnelList = []) {
  // Soporte para invocación directa `buildCanonicalBitacoraPayload(singleObj)` desde MaterialMayorView
  const isSingleArg = (!unit || Object.keys(unit).length === 0) && (svc.carro || svc.idCarro || svc.idSalida);
  const sourceSvc = isSingleArg ? svc : svc;
  const sourceUnit = isSingleArg ? svc : unit;

  const idSalida = String(sourceUnit.idSalida || sourceUnit.bitacoraId || '').trim();
  const idServicio = String(sourceSvc.idServicio || sourceSvc.id || sourceUnit.idServicio || '').trim();
  const carro = String(sourceUnit.name || sourceUnit.carro || sourceUnit.idCarro || '').trim().toUpperCase();

  const vMatch = (Array.isArray(vehicles) ? vehicles : []).find(
    v => String(v.idCarro || v.id || '').replace(/-/g, '').toUpperCase() === carro.replace(/-/g, '').toUpperCase()
  );
  // El último número del ID de carro determina la compañía (o el campo canónico `compania`)
  const companiaNum = resolveCompanyFromVehicleId(carro, sourceUnit.compania ?? vMatch?.compania);

  const resolvePersonObj = (rawVal) => {
    if (!rawVal) return { idRadial: '', nombre: '' };
    if (typeof rawVal === 'object' && rawVal.idRadial !== undefined) {
      const rawIdStr = String(rawVal.idRadial || '').trim();
      const rawNomStr = String(rawVal.nombre || rawVal.nombreBombero || '').trim();
      if (rawNomStr) {
        return {
          idRadial: extractPersonRadial(rawIdStr),
          nombre: rawNomStr.toUpperCase()
        };
      }
      rawVal = rawIdStr;
    }
    const rawStr = String(rawVal).trim().toUpperCase();
    // Si el usuario ingresó formato "124 - BRIGIDA ADASME" o "124 • BRIGIDA ADASME", separar radial de nombre
    const sepMatch = rawStr.match(/^([A-Z0-9-]+)\s*(?:•|-)\s*(.+)$/);
    const radCandidate = sepMatch ? sepMatch[1].trim() : rawStr;
    const pList = Array.isArray(personnelList) ? personnelList : [];
    let pMatch = pList.find(
      p => String(p.idRadial || p.codigoRadial || '').trim().toUpperCase() === radCandidate
    );
    if (!pMatch) {
      pMatch = pList.find(
        p => String(p.idRegistro || p.id || '').trim().toUpperCase() === radCandidate
      );
    }
    if (!pMatch && rawStr.length > 2) {
      pMatch = pList.find(
        p => String(p.nombreBombero || p.nombre || `${p.nombres || ''} ${p.apellidos || ''}`).trim().toUpperCase().includes(rawStr)
      );
    }
    const finalRad = pMatch
      ? String(pMatch.idRadial || pMatch.codigoRadial || radCandidate).trim().toUpperCase()
      : radCandidate;
    const fullName = pMatch
      ? (pMatch.nombreBombero || `${pMatch.nombres || ''} ${pMatch.apellidos || ''}`.trim())
      : (sepMatch ? sepMatch[2].trim() : '');
    return {
      idRadial: finalRad,
      nombre: String(fullName || '').trim().toUpperCase()
    };
  };

  const conductorObj = resolvePersonObj(sourceUnit.driverRad ?? sourceUnit.conductor ?? sourceUnit.conductorObj);
  const obacObj = resolvePersonObj(sourceUnit.obacRad ?? sourceUnit.obac ?? sourceUnit.obacObj);

  const rawCount = sourceUnit.count ?? sourceUnit.cuantosBomberos ?? sourceUnit.cantidadTripulantes ?? sourceUnit.tripulantes ?? '';
  const rawCountStr = String(rawCount ?? '').trim().toUpperCase();
  const isPureNumericCount = /^\d+$/.test(rawCountStr);
  const cuantosBomberos = rawCountStr === '' ? 0 : (isPureNumericCount ? parseInt(rawCountStr, 10) : rawCountStr);

  const kmLlegadaNum = parseInt(
    String(sourceUnit.kmVal ?? sourceUnit.kmLlegada ?? vMatch?.kmActual ?? '0').replace(/\D/g, ''),
    10
  ) || 0;

  const baseRefDate = String(
    sourceUnit.fecha60 ||
    sourceUnit.fechaDespacho ||
    sourceUnit.fecha ||
    sourceSvc.fechaDespacho ||
    sourceSvc.fecha ||
    sourceUnit.fecha63 ||
    sourceUnit.fecha69 ||
    sourceUnit.fecha610 ||
    sourceUnit.fecha68 ||
    ''
  ).trim();

  const h60 = parseToEpochMs(
    sourceUnit.epoch60 ?? sourceUnit.h60 ?? sourceUnit.tiempos?.h60,
    sourceUnit.departureTime ?? sourceUnit.hora60,
    sourceUnit.fecha60 || baseRefDate
  );
  const h63 = parseToEpochMs(
    sourceUnit.epoch63 ?? sourceUnit.h63 ?? sourceUnit.tiempos?.h63,
    sourceUnit.arrivalTime ?? sourceUnit.hora63,
    sourceUnit.fecha63 || baseRefDate
  );
  const h69 = parseToEpochMs(
    sourceUnit.epoch69 ?? sourceUnit.h69 ?? sourceUnit.tiempos?.h69,
    sourceUnit.time69 ?? sourceUnit.hora69,
    sourceUnit.fecha69 || baseRefDate
  );
  const h610 = parseToEpochMs(
    sourceUnit.epoch610 ?? sourceUnit.h610 ?? sourceUnit.tiempos?.h610,
    sourceUnit.time610 ?? sourceUnit.hora610,
    sourceUnit.fecha610 || baseRefDate
  );
  const h68 = parseToEpochMs(
    sourceUnit.epoch68 ?? sourceUnit.h68 ?? sourceUnit.tiempos?.h68,
    sourceUnit.time68 ?? sourceUnit.hora68,
    sourceUnit.fecha68 || baseRefDate
  );
  const h615 = parseToEpochMs(
    sourceUnit.epoch615 ?? sourceUnit.h615 ?? sourceUnit.tiempos?.h615,
    sourceUnit.departureTime615,
    sourceUnit.fecha615 || baseRefDate
  );

  let estadoMovil = 'en cuartel';
  if (h68) estadoMovil = 'en cuartel';
  else if (h610) estadoMovil = 'en cuartel';
  else if (h69) estadoMovil = 'retorno';
  else if (h63) estadoMovil = 'en lugar';
  else if (h60) estadoMovil = 'en trayecto';

  return {
    idSalida,
    idServicio,
    carro,
    compania: companiaNum,
    clave: String(sourceSvc.clave || sourceSvc.key || sourceUnit.clave || '10-0').trim(),
    subclave: String(sourceSvc.subclave || sourceUnit.subclave || '').trim(),
    claveApoyo: String(sourceSvc.claveApoyo || sourceUnit.claveApoyo || '').trim(),
    direccion: String(sourceSvc.direccion || sourceSvc.location || sourceUnit.direccion || '').trim(),
    conductor: conductorObj,
    obac: obacObj,
    cuantosBomberos: typeof rawCount === 'string' && isNaN(Number(rawCount.trim())) && rawCount.trim() !== ''
      ? rawCount.trim()
      : cuantosBomberos,
    tripulacion: Array.isArray(sourceUnit.tripulacion) ? sourceUnit.tripulacion : [],
    kmLlegada: kmLlegadaNum,
    estadoMovil,
    tiempos: {
      h60: h60 || null,
      h63: h63 || null,
      h68: h68 || null,
      h69: h69 || null,
      h610: h610 || null,
      h615: h615 || null
    },
    observacion: String(sourceUnit.observation ?? sourceUnit.observacion ?? sourceUnit.observaciones ?? '').trim(),
    preinforme: String(sourceUnit.tripInfo ?? sourceUnit.preinforme ?? sourceUnit.preinformeUnidad ?? '').trim(),
    informe63: String(sourceUnit.report ?? sourceUnit.informe63 ?? '').trim()
  };
}

/**
 * 3. Colección `vehiculos/{idCarro}` — Estructura canónica de `SHARED/ESQUEMA_CANONICO_V3.md` (líneas 134-151):
 * `idCarro`, `compania`, `patente`, `estado`, `enServicio`, `kmActual`, `observacion`, `notas`
 * Lista para crear/administrar unidades desde SENTINEL CORE y actualizar su estado en SENTINEL CAD.
 */
export const CANONICAL_VEHICLE_KEYS = new Set([
  'idCarro',
  'compania',
  'patente',
  'tipo',
  'estado',
  'enServicio',
  'kmActual',
  'observacion',
  'notas'
]);

/**
 * Construye un documento completo de unidad (`vehiculos/{idCarro}`) para SENTINEL CORE / SENTINEL CAD.
 * Garantiza que `compania` corresponda al último número de `idCarro` (ej. B-1 -> 1, B-2 -> 2, B-3 -> 3).
 */
export function buildCanonicalVehicleDoc(raw = {}) {
  const idCarro = String(raw.idCarro || raw.id || '').trim().toUpperCase();
  const compania = resolveCompanyFromVehicleId(idCarro, raw.compania);
  const rawEstado = String(raw.estado ?? '0-9').trim();
  const estado = (rawEstado === '1' || rawEstado === '0-9') ? '0-9' : ((rawEstado === '0' || rawEstado === '0-8') ? '0-8' : rawEstado);

  return {
    idCarro,
    compania,
    patente: String(raw.patente || '').trim().toUpperCase(),
    estado,
    enServicio: String(raw.enServicio ?? '0').trim(),
    kmActual: parseInt(String(raw.kmActual ?? raw.km ?? '0').replace(/\D/g, ''), 10) || 0,
    observacion: String(raw.observacion ?? raw.motivo08 ?? '').trim(),
    notas: String(raw.notas || '').trim()
  };
}

export function sanitizeCanonicalVehicleUpdate(partial = {}, fallbackIdCarro = '') {
  const clean = {};
  const targetId = partial.idCarro || fallbackIdCarro;
  if (partial.idCarro !== undefined) {
    clean.idCarro = String(partial.idCarro).trim().toUpperCase();
  }
  if (partial.compania !== undefined || targetId) {
    clean.compania = resolveCompanyFromVehicleId(targetId, partial.compania);
  }
  if (partial.patente !== undefined) clean.patente = String(partial.patente).trim().toUpperCase();
  if (partial.tipo !== undefined) clean.tipo = String(partial.tipo).trim().toUpperCase();
  if (partial.estado !== undefined) {
    const rawEst = String(partial.estado).trim();
    clean.estado = rawEst === '1' ? '0-9' : (rawEst === '0' ? '0-8' : rawEst);
  }
  if (partial.enServicio !== undefined) clean.enServicio = String(partial.enServicio).trim();
  if (partial.kmActual !== undefined) clean.kmActual = parseInt(String(partial.kmActual).replace(/\D/g, ''), 10) || 0;
  if (partial.observacion !== undefined || partial.motivo08 !== undefined) {
    clean.observacion = String(partial.observacion ?? partial.motivo08 ?? '').trim();
  }
  if (partial.notas !== undefined) clean.notas = String(partial.notas).trim();
  return clean;
}

/**
 * 4. Colección `personal/{idRegistro}` — Campos canónicos de `SHARED/ESQUEMA_CANONICO_V3.md` (líneas 155-195)
 */
export function sanitizeCanonicalPersonnelUpdate(partial = {}) {
  const clean = {};
  if (partial.idRegistro !== undefined) clean.idRegistro = String(partial.idRegistro).trim();
  if (partial.rut !== undefined) clean.rut = String(partial.rut).trim();
  if (partial.idRadial !== undefined || partial.codigoRadial !== undefined) {
    clean.idRadial = String(partial.idRadial ?? partial.codigoRadial).trim();
  }
  if (partial.nombreBombero !== undefined) clean.nombreBombero = String(partial.nombreBombero).trim();
  if (partial.foto !== undefined) clean.foto = String(partial.foto).trim();
  if (partial.numeroRadio !== undefined) clean.numeroRadio = String(partial.numeroRadio).trim();
  if (partial.compania !== undefined) clean.compania = parseInt(String(partial.compania).replace(/\D/g, ''), 10) || 1;
  if (partial.cargo !== undefined) clean.cargo = String(partial.cargo).trim();
  if (partial.ordenEnLista !== undefined) clean.ordenEnLista = Number(partial.ordenEnLista) || 0;
  if (partial.activo !== undefined) clean.activo = Boolean(partial.activo);
  if (partial.conductor !== undefined || partial.esConductor !== undefined) {
    clean.conductor = Boolean(partial.conductor ?? partial.esConductor);
  }
  if (partial.estado !== undefined) clean.estado = String(partial.estado).trim();
  if (partial.enServicio !== undefined) clean.enServicio = String(partial.enServicio).trim();
  if (partial.deviceId !== undefined || partial.fcmToken !== undefined) {
    clean.deviceId = String(partial.deviceId ?? partial.fcmToken ?? '').trim();
  }
  return clean;
}

/**
 * 5. Colección `asistencia/{idLista}` — Esquema Canónico de `SHARED/ESQUEMA_CANONICO_V3.md` (líneas 209-253)
 */
export function buildCanonicalAttendanceDoc(raw = {}) {
  const idLista = String(raw.idLista || raw.idAsistencia || '').trim();
  return {
    idLista,
    idServicio: String(raw.idServicio || '').trim(),
    clave: String(raw.clave || '10-0').trim(),
    subclave: String(raw.subclave || '').trim(),
    evento: String(raw.evento || raw.clave || '').trim(),
    direccion: String(raw.direccion || '').trim(),
    timestamp: Number(raw.timestamp || raw.createdAt || Date.now()),
    abono: String(raw.abono || 'NO').trim(),
    obac: (raw.obac && typeof raw.obac === 'object')
      ? { idRadial: String(raw.obac.idRadial || ''), nombre: String(raw.obac.nombre || '') }
      : { idRadial: '', nombre: '' },
    listaPor: (raw.listaPor && typeof raw.listaPor === 'object')
      ? { idRadial: String(raw.listaPor.idRadial || ''), nombre: String(raw.listaPor.nombre || '') }
      : { idRadial: '', nombre: String(raw.registradoPor || '') },
    aprobadoPor: String(raw.aprobadoPor || '').trim(),
    aprobadoAt: raw.aprobadoAt ? Number(raw.aprobadoAt) : null,
    anulada: Boolean(raw.anulada),
    anuladaPor: String(raw.anuladaPor || '').trim(),
    observacion: String(raw.observacion || '').trim()
  };
}

/**
 * 6. Colección `prueba_radial/{id115}` — Esquema Canónico de `SHARED/ESQUEMA_CANONICO_V3.md` (líneas 257-283)
 */
export function buildCanonicalRadialTestDoc(raw = {}) {
  const ts = Number(raw.timestamp || Date.now());
  const d = new Date(ts);
  const defaultTurno = d.getHours() < 14 ? 'MANANA' : 'TARDE';
  return {
    id115: String(raw.id115 || raw.idPrueba || '').trim(),
    turno: String(raw.turno || defaultTurno).trim().toUpperCase(),
    timestamp: ts,
    totalPersonal: Number(raw.totalPersonal ?? raw.totalEvaluados ?? 0),
    operador: String(raw.operador || 'OPERADOR CENTRAL').trim()
  };
}

/**
 * 7. Colección `sirena/{idToqueSirena}` — Esquema Canónico de `SHARED/ESQUEMA_CANONICO_V3.md` (líneas 287-298)
 */
export function buildCanonicalSirenControlDoc(raw = {}) {
  return {
    idToqueSirena: String(raw.idToqueSirena || raw.idToque || '').trim(),
    timestamp: Number(raw.timestamp || Date.now()),
    razonDeToque: String(raw.razonDeToque || raw.clave || '12-4 PRUEBA DE SIRENA').trim(),
    sono: raw.sono !== undefined ? Boolean(raw.sono) : true,
    operador: String(raw.operador || 'OPERADOR CENTRAL').trim()
  };
}

// ============================================================================
// 6. TELECOMUNICACIONES E IoT (SENTINEL LINK — BLOQUES 6 Y 7 DE ESQUEMA_CANONICO_V3.md)
// ============================================================================

/**
 * Normaliza un número telefónico chileno o de red fija/móvil para Caller ID y cola SMS.
 */
export function sanitizeChileanPhone(rawPhone = '') {
  const raw = String(rawPhone || '').trim();
  if (!raw) return '';
  if (raw.toUpperCase() === 'LLAMADA ENTRANTE' || raw.toUpperCase() === 'DESCONOCIDO' || raw.toUpperCase() === 'EN LLAMADA') {
    return raw.toUpperCase();
  }
  const digits = raw.replace(/\D/g, '');
  if (!digits) return raw;
  if (digits.length === 8) return `+569${digits}`;
  if (digits.length === 9 && digits.startsWith('9')) return `+56${digits}`;
  if (digits.length === 11 && digits.startsWith('56')) return `+${digits}`;
  return raw.startsWith('+') ? `+${digits}` : digits;
}

/**
 * 8. Colección `solicitudes_puerta/{idSolicitud}` — Esquema Canónico (`SHARED/ESQUEMA_CANONICO_V3.md` líneas 406-418)
 */
export function buildCanonicalDoorRequestDoc(raw = {}) {
  const ts = Number(raw.timestamp || Date.now());
  const idSolicitud = String(raw.idSolicitud || raw.id || `SOL-${Math.floor(ts / 1000)}`).trim();
  const compNum = parseInt(String(raw.compania ?? 1).replace(/\D/g, ''), 10) || 1;
  return {
    idSolicitud,
    idRegistro: String(raw.idRegistro || '').trim(),
    nombreBombero: String(raw.nombreBombero || raw.solicitante || '').trim().toUpperCase(),
    compania: compNum,
    estado: String(raw.estado || 'pendiente').trim().toLowerCase(),
    timestamp: ts
  };
}

/**
 * 9. Colección `llamadas/central` (Caller ID Telefónico en Vivo — `SHARED/ESQUEMA_CANONICO_V3.md` líneas 422-432)
 */
export function buildCanonicalLiveCallDoc(raw = {}) {
  const estado = String(raw.estado || 'TIMBRANDO').trim().toUpperCase();
  const activo = raw.activo !== undefined
    ? Boolean(raw.activo)
    : (estado === 'TIMBRANDO' || estado === 'ATENDIDA' || estado === 'EN_LLAMADA');
  return {
    activo,
    telefono: sanitizeChileanPhone(raw.telefono || raw.rawTelefono || ''),
    estado,
    timestamp: Number(raw.timestamp || Date.now())
  };
}

/**
 * 10. Colección `historial_llamadas/{idLlamada}` (Auditoría Telefónica 132 — `SHARED/ESQUEMA_CANONICO_V3.md` líneas 436-448)
 */
export function buildCanonicalCallHistoryDoc(raw = {}) {
  const ts = Number(raw.timestamp || Date.now());
  const idLlamada = String(raw.idLlamada || raw.id || `LLAM-${Math.floor(ts / 1000)}`).trim();
  const tsTermino = raw.timestampTermino ? Number(raw.timestampTermino) : null;
  return {
    idLlamada,
    telefono: sanitizeChileanPhone(raw.telefono || ''),
    estado: String(raw.estado || 'ATENDIDA').trim().toUpperCase(),
    timestamp: ts,
    timestampTermino: tsTermino,
    operador: String(raw.operador || '').trim().toUpperCase()
  };
}

export function normalizeCallHistoryDoc(docId, raw = {}) {
  if (!raw || typeof raw !== 'object') return null;
  const ts = parseToEpochMs(raw.timestamp, raw.hora, raw.fecha) || Date.now();
  const tsTermino = parseToEpochMs(raw.timestampTermino, raw.horaTermino, raw.fechaTermino) || null;
  const idLlamada = String(raw.idLlamada || raw.id || docId || `LLAM-${Math.floor(ts / 1000)}`).trim();
  return {
    idLlamada,
    telefono: sanitizeChileanPhone(raw.telefono || raw.rawTelefono || ''),
    estado: String(raw.estado || 'FINALIZADA').trim().toUpperCase(),
    timestamp: ts,
    timestampTermino: tsTermino,
    operador: String(raw.operador || raw.nombre || '').trim().toUpperCase(),
    horaStr: formatEpochToHHMM(ts) || String(raw.hora || '').substring(0, 5),
    fechaStr: formatEpochToDDMMYYYY(ts) || String(raw.fecha || '')
  };
}

/**
 * 11. Colección `sms_queue/{idSms}` (Cola de Despacho de Enlaces GPS — `SHARED/ESQUEMA_CANONICO_V3.md` líneas 452-465)
 */
export function buildCanonicalSmsQueueDoc(raw = {}) {
  const ts = Number(raw.timestamp || Date.now());
  const idSms = String(raw.idSms || raw.id || `SMS-${Math.floor(ts / 1000)}`).trim();
  return {
    idSms,
    idDespacho: String(raw.idDespacho || raw.idServicio || '').trim(),
    telefono: sanitizeChileanPhone(raw.telefono || ''),
    mensaje: String(raw.mensaje || 'BOMBEROS: Presione el enlace para compartir su ubicacion con la Central').trim(),
    enlace: String(raw.enlace || '').trim(),
    estado: String(raw.estado || 'PENDIENTE').trim().toUpperCase(),
    timestamp: ts
  };
}

export function normalizeSmsQueueDoc(docId, raw = {}) {
  if (!raw || typeof raw !== 'object') return null;
  const ts = parseToEpochMs(raw.timestamp, raw.hora, raw.fecha) || Date.now();
  const idSms = String(raw.idSms || raw.id || docId || `SMS-${Math.floor(ts / 1000)}`).trim();
  return {
    idSms,
    idDespacho: String(raw.idDespacho || raw.idServicio || '').trim(),
    telefono: sanitizeChileanPhone(raw.telefono || ''),
    mensaje: String(raw.mensaje || '').trim(),
    enlace: String(raw.enlace || '').trim(),
    estado: String(raw.estado || 'PENDIENTE').trim().toUpperCase(),
    timestamp: ts,
    horaStr: formatEpochToHHMM(ts) || String(raw.hora || '').substring(0, 5),
    fechaStr: formatEpochToDDMMYYYY(ts) || String(raw.fecha || '')
  };
}

/**
 * 12. Normalizador Dual-Read para `accesos/central` (Sesión Activa de Operador Central CAD ↔ SENTINEL LINK)
 */
export function normalizeCentralSessionDoc(raw = {}) {
  if (!raw || typeof raw !== 'object') return null;
  const estado = String(raw.estado || 'cerrado').trim().toLowerCase();
  const idInicioRaw = raw.idInicio ?? raw.idAcceso ?? raw.idIngreso ?? '';
  const idInicioNum = parseInt(String(idInicioRaw).replace(/\D/g, ''), 10);
  const tsIngreso = parseToEpochMs(
    raw.timestampIngreso ?? raw.timestampInicio ?? raw.timestamp,
    raw.horaIngreso,
    raw.fechaIngreso
  );
  const tsCierre = parseToEpochMs(
    raw.timestampCierre,
    raw.horaCierre,
    raw.fechaCierre
  );

  return {
    idInicio: !isNaN(idInicioNum) && idInicioNum > 0 ? idInicioNum : String(idInicioRaw || ''),
    idAcceso: String(idInicioRaw || ''),
    idRegistro: String(raw.idRegistro || '').trim(),
    nombreBombero: String(raw.nombreBombero || raw.operador || '').trim().toUpperCase(),
    cargo: String(raw.cargo || 'OPERADOR CENTRAL').trim().toUpperCase(),
    estado,
    timestampIngreso: tsIngreso,
    timestampCierre: tsCierre,
    puerta: Boolean(raw.puerta),
    horaIngresoStr: formatEpochToHHMM(tsIngreso) || String(raw.horaIngreso || '').substring(0, 5),
    fechaIngresoStr: formatEpochToDDMMYYYY(tsIngreso) || String(raw.fechaIngreso || '')
  };
}

// ============================================================================
// 7. CONFIGURACIÓN Y LICENCIA MULTI-CUERPO (SENTINEL SAAS — BLOQUE 4 DE ESQUEMA_CANONICO_V3.md)
// ============================================================================

/**
 * Normaliza un documento `saas_clientes/{licencia}` en modo Dual-Read (compatible con V2 y V3).
 */
export function normalizeSaasClientDoc(docId, raw = {}) {
  if (!raw || typeof raw !== 'object') return null;
  const licencia = String(raw.licencia || raw.key || docId || '').trim().toUpperCase();
  const nombreCliente = String(raw.nombreCliente || raw.nombreMostrar || licencia).trim();
  const nombreMostrar = String(raw.nombreMostrar || raw.nombreCliente || licencia).trim().toUpperCase();
  const comuna = String(raw.comuna || '').trim().toUpperCase();
  const region = String(raw.region || '').trim().toUpperCase();
  const logoUrl = String(raw.logoUrl || '').trim();
  const estadoSuscripcion = String(raw.estadoSuscripcion || raw.estado || 'activo').trim().toLowerCase();
  const avisoPagoHasta = String(raw.avisoPagoHasta || '').trim();

  // Cuartel General (Canónico V3 + fallback raíz V2)
  const cgRaw = (raw.cuartelGeneral && typeof raw.cuartelGeneral === 'object') ? raw.cuartelGeneral : {};
  const rawLat = cgRaw.lat ?? raw.lat ?? raw.latitud ?? raw.latCuartel ?? raw.cuartelLat ?? null;
  const rawLng = cgRaw.lng ?? raw.lng ?? raw.longitud ?? raw.lngCuartel ?? raw.cuartelLng ?? null;
  const lat = rawLat !== null && rawLat !== '' && !isNaN(Number(rawLat)) ? Number(rawLat) : null;
  const lng = rawLng !== null && rawLng !== '' && !isNaN(Number(rawLng)) ? Number(rawLng) : null;

  const cuartelGeneral = {
    nombre: String(cgRaw.nombre || 'Cuartel General').trim(),
    lat,
    lng,
    direccion: String(cgRaw.direccion || '').trim()
  };

  // Compañías / Cuarteles (Canónico V3 `companias` + espejo `cuarteles`)
  const rawCompanias = (raw.companias && typeof raw.companias === 'object')
    ? raw.companias
    : ((raw.cuarteles && typeof raw.cuarteles === 'object') ? raw.cuarteles : {});
  const companias = {};
  Object.entries(rawCompanias).forEach(([k, v]) => {
    if (!v || typeof v !== 'object') return;
    const alias = resolveCompanyIdFromKeyOrData(k, v);
    const prev = companias[alias];
    const cLat = v.lat ?? v.latitud ?? v.latCuartel ?? null;
    const cLng = v.lng ?? v.longitud ?? v.lngCuartel ?? null;
    const incomingNombre = String(v.nombre || '').trim();
    const incomingCuartel = String(v.cuartel || '').trim();
    const incomingDir = String(v.direccion || '').trim();
    companias[alias] = {
      alias,
      nombre: incomingNombre || prev?.nombre || `${getCompanyOrdinalWord(alias)} Compañía`,
      cuartel: incomingCuartel || prev?.cuartel || (alias === '1' ? 'Cuartel Primera Compañía' : `Cuartel ${alias}ª Compañía`),
      lat: cLat !== null && cLat !== '' && !isNaN(Number(cLat)) ? Number(cLat) : (prev?.lat ?? lat),
      lng: cLng !== null && cLng !== '' && !isNaN(Number(cLng)) ? Number(cLng) : (prev?.lng ?? lng),
      direccion: incomingDir || prev?.direccion || ''
    };
  });

  if (Object.keys(companias).length === 0) {
    companias['1'] = {
      alias: '1',
      nombre: 'Primera Compañía',
      cuartel: 'Cuartel General',
      lat,
      lng,
      direccion: cuartelGeneral.direccion
    };
  }

  const totalCompanias = Math.max(
    parseInt(String(raw.totalCompanias ?? raw.limiteCompanias ?? Object.keys(companias).length), 10) || 1,
    Object.keys(companias).length
  );

  // Dispositivos (Canónico V3 `dispositivos: { [hwid]: { alias, autorizado } }` — Autorización global por equipo para todo el ecosistema)
  const dispositivos = {};
  const rawDevices = (raw.dispositivos && typeof raw.dispositivos === 'object') ? raw.dispositivos : {};
  Object.entries(rawDevices).forEach(([hwidKey, devVal]) => {
    const cleanHwid = String(hwidKey || '').trim().toUpperCase();
    if (!cleanHwid) return;
    if (typeof devVal === 'string') {
      dispositivos[cleanHwid] = { alias: '', autorizado: true };
    } else if (devVal && typeof devVal === 'object') {
      dispositivos[cleanHwid] = {
        alias: String(devVal.alias || '').trim(),
        autorizado: devVal.autorizado !== false
      };
    }
  });

  if (Array.isArray(raw.hardwareUUIDs)) {
    raw.hardwareUUIDs.forEach((h) => {
      const cleanHwid = String(h || '').trim().toUpperCase();
      if (cleanHwid && !dispositivos[cleanHwid]) {
        dispositivos[cleanHwid] = { alias: '', autorizado: true };
      }
    });
  }

  const hardwareUUIDs = Object.keys(dispositivos).filter((h) => dispositivos[h]?.autorizado !== false);

  // El ecosistema SENTINEL V3 se comercializa y habilita como paquete integral unificado
  const modulos = {
    central: true,
    comandancia: true,
    apk: true,
    material: true,
    lista: true,
    cad: true,
    core: true,
    one: true,
    link: true,
    sci: true,
    nav: true,
    crew: true,
    med: true
  };

  // Firebase Config
  const fc = (raw.firebaseConfig && typeof raw.firebaseConfig === 'object') ? raw.firebaseConfig : {};
  const firebaseConfig = {
    apiKey: String(fc.apiKey || '').trim(),
    authDomain: String(fc.authDomain || '').trim(),
    projectId: String(fc.projectId || '').trim(),
    storageBucket: String(fc.storageBucket || '').trim(),
    messagingSenderId: String(fc.messagingSenderId || '').trim(),
    appId: String(fc.appId || '').trim()
  };

  // Google Sheets Bitácora (Canónico V3 `googleSheetsBitacora` + fallback V2)
  const gsObj = (raw.googleSheetsBitacora && typeof raw.googleSheetsBitacora === 'object') ? raw.googleSheetsBitacora : {};
  const googleSheetsBitacora = {
    enabled: gsObj.enabled !== undefined
      ? Boolean(gsObj.enabled)
      : (raw.googleSheetsBitacoraEnabled !== undefined ? Boolean(raw.googleSheetsBitacoraEnabled) : true),
    url: String(gsObj.url ?? raw.googleSheetsBitacoraUrl ?? '').trim(),
    token: String(gsObj.token ?? raw.googleSheetsBitacoraToken ?? '').trim()
  };

  return {
    key: licencia,
    licencia,
    nombreCliente,
    nombreMostrar,
    comuna,
    region,
    logoUrl,
    estadoSuscripcion,
    avisoPagoHasta,
    lat,
    lng,
    cuartelGeneral,
    totalCompanias,
    companias,
    cuarteles: companias,
    dispositivos,
    hardwareUUIDs,
    modulos,
    firebaseConfig,
    googleSheetsBitacora,
    googleSheetsBitacoraEnabled: googleSheetsBitacora.enabled,
    googleSheetsBitacoraUrl: googleSheetsBitacora.url,
    googleSheetsBitacoraToken: googleSheetsBitacora.token,
    updatedAt: Number(raw.updatedAt || Date.now())
  };
}

/**
 * Construye el payload canónico de `saas_clientes/{licencia}` cumpliendo `SHARED/ESQUEMA_CANONICO_V3.md` (Bloque 4)
 * y preservando espejos de lectura rápida para todos los módulos del ecosistema.
 */
export function buildCanonicalSaasClientDoc(raw = {}) {
  const norm = normalizeSaasClientDoc(raw.licencia || raw.key || '', raw);
  if (!norm) return {};
  return {
    licencia: norm.licencia,
    nombreCliente: norm.nombreCliente,
    nombreMostrar: norm.nombreMostrar,
    comuna: norm.comuna,
    region: norm.region,
    logoUrl: norm.logoUrl,
    estadoSuscripcion: norm.estadoSuscripcion,
    avisoPagoHasta: norm.avisoPagoHasta,
    lat: norm.cuartelGeneral.lat,
    lng: norm.cuartelGeneral.lng,
    cuartelGeneral: norm.cuartelGeneral,
    totalCompanias: norm.totalCompanias,
    companias: norm.companias,
    cuarteles: norm.companias,
    dispositivos: norm.dispositivos,
    hardwareUUIDs: norm.hardwareUUIDs,
    modulos: norm.modulos,
    firebaseConfig: norm.firebaseConfig,
    googleSheetsBitacora: norm.googleSheetsBitacora,
    googleSheetsBitacoraEnabled: norm.googleSheetsBitacora.enabled,
    googleSheetsBitacoraUrl: norm.googleSheetsBitacora.url,
    googleSheetsBitacoraToken: norm.googleSheetsBitacora.token,
    updatedAt: Date.now()
  };
}

// ============================================================================
// 8. CONTROL DE LISTAS DE ASISTENCIA (SENTINEL CREW — BLOQUE 3 DE ESQUEMA_CANONICO_V3.md)
// ============================================================================

/**
 * Formatea un identificador de lista (`"2026-00453"` o `"2026453"`) a su número correlativo corto (`"453"`).
 */
export function formatAttendanceIdShort(idLista = '') {
  const s = String(idLista || '').trim();
  if (!s) return '';
  if (s.includes(',')) {
    return s
      .split(',')
      .map((part) => formatAttendanceIdShort(part.trim()))
      .filter(Boolean)
      .join(', ');
  }
  if (/^\d{4}-\d+$/.test(s)) {
    const seq = parseInt(s.split('-')[1], 10);
    return !isNaN(seq) ? String(seq) : s;
  }
  if (/^\d{7,9}$/.test(s)) {
    const seq = parseInt(s.substring(4), 10);
    return !isNaN(seq) ? String(seq) : s;
  }
  return s;
}

/**
 * Calcula el siguiente correlativo canónico `AAAA-00000` (ej: `"2026-00453"`) compatible
 * con historiales mixtos V2 (`"2026001"`) y V3 (`"2026-00001"`).
 */
export function calculateNextCanonicalAttendanceId(historyArray = [], dateObj = new Date()) {
  const d = dateObj instanceof Date ? dateObj : new Date(dateObj || Date.now());
  const y = d.getFullYear();
  const m = d.getMonth() + 1;
  const cycleYear = m === 12 ? y + 1 : y;
  const yearPrefix = String(cycleYear);

  let maxSeq = 0;
  if (Array.isArray(historyArray)) {
    historyArray.forEach((h) => {
      const idStr = String(h?.idLista || h?.serviceId || h?.id || '').trim();
      if (!idStr) return;
      if (idStr.startsWith(`${yearPrefix}-`)) {
        const seq = parseInt(idStr.split('-')[1], 10);
        if (!isNaN(seq) && seq > maxSeq) maxSeq = seq;
      } else if (idStr.startsWith(yearPrefix) && idStr.length >= 7) {
        const seq = parseInt(idStr.substring(4), 10);
        if (!isNaN(seq) && seq > maxSeq) maxSeq = seq;
      }
    });
  }

  const nextSeq = maxSeq + 1;
  return `${yearPrefix}-${String(nextSeq).padStart(5, '0')}`;
}

/**
 * Normaliza en modo Dual-Read un documento de `asistencia/{idLista}` (compatible con V2 y V3).
 */
export function normalizeAttendanceDoc(docId, raw = {}) {
  if (!raw || typeof raw !== 'object') return null;

  const idLista = String(raw.idLista || raw.serviceId || docId || '').trim();
  const idServicio = String(raw.idServicio || raw.idDespacho || '').trim();
  const clave = String(raw.clave || raw.serviceKey || '').trim().toUpperCase();
  const subclave = String(raw.subclave || '').trim().toUpperCase();
  const evento = String(raw.evento || raw.Evento || subclave || clave || '').trim();
  const direccion = String(raw.direccion || raw.lugar || raw.Lugar || '').trim();

  const ts = parseToEpochMs(raw.timestamp ?? raw.createdAt, raw.hora, raw.fecha) || Date.now();
  const fechaStr = formatEpochToDDMMYYYY(ts) || String(raw.fecha || '').trim();
  const horaStr = formatEpochToHHMM(ts) || String(raw.hora || '').trim();

  const rawAbono = raw.abono ?? (raw.esAbono === 1 || raw.esAbono === true ? 'SÍ' : 'NO');
  const isAbonoBool = String(rawAbono).toUpperCase() === 'SÍ' || String(rawAbono).toUpperCase() === 'SI' || rawAbono === 1 || rawAbono === true;
  const abono = isAbonoBool ? 'SÍ' : 'NO';

  const extractPersonRef = (field) => {
    if (!field) return { idRadial: '', nombre: '', display: '' };
    if (typeof field === 'object') {
      const idRadial = String(field.idRadial || field.codigoRadial || '').trim();
      const nombre = String(field.nombre || field.nombreBombero || '').trim().toUpperCase();
      const display = idRadial && nombre ? `${idRadial} - ${nombre}` : (nombre || idRadial);
      return { idRadial, nombre, display };
    }
    const str = String(field).trim().toUpperCase();
    if (str.includes(' - ')) {
      const [rad, ...rest] = str.split(' - ');
      const nombre = rest.join(' - ').trim();
      return { idRadial: rad.trim(), nombre, display: str };
    }
    return { idRadial: '', nombre: str, display: str };
  };

  const obacObj = extractPersonRef(raw.obac);
  const listaPorObj = extractPersonRef(raw.listaPor || raw.registradoPor);

  const aprobadoPor = String(raw.aprobadoPor || '').trim().toUpperCase();
  const aprobadoAt = parseToEpochMs(raw.aprobadoAt, '', raw.fechaAprobacion);

  const rawAnulada = raw.anulada;
  const anulada =
    rawAnulada === true ||
    rawAnulada === 1 ||
    String(rawAnulada || '').trim().toUpperCase() === 'SI' ||
    String(rawAnulada || '').trim().toUpperCase() === 'SÍ' ||
    String(rawAnulada || '').trim().toLowerCase() === 'true';

  const anuladaPor = String(raw.anuladaPor || '').trim();
  const observacion = String(raw.observacion || raw.obs || '').trim();

  return {
    idLista,
    serviceId: idLista,
    shortId: formatAttendanceIdShort(idLista),
    idServicio,
    clave,
    serviceKey: clave,
    subclave,
    evento,
    Evento: evento,
    direccion,
    Lugar: direccion,
    timestamp: ts,
    fecha: fechaStr,
    hora: horaStr,
    abono,
    esAbono: isAbonoBool ? 1 : 0,
    obac: { idRadial: obacObj.idRadial, nombre: obacObj.nombre },
    obacDisplay: obacObj.display,
    listaPor: { idRadial: listaPorObj.idRadial, nombre: listaPorObj.nombre },
    listaPorDisplay: listaPorObj.display,
    aprobadoPor,
    aprobadoAt,
    anulada,
    anuladaPor,
    observacion,
    obs: observacion,
    isLocalDraft: Boolean(raw.isLocalDraft),
    firefighters: Array.isArray(raw.firefighters) ? raw.firefighters : null,
    _metaTimestamp: Number(raw._metaTimestamp || 0)
  };
}

/**
 * Construye un documento canónico para la subcolección `asistencia/{idLista}/bomberos/{idRegistro}`
 * según `SHARED/ESQUEMA_CANONICO_V3.md` (líneas 244-253).
 */
export function buildCanonicalAttendanceFirefighterDoc(raw = {}) {
  const rawEstado = String(raw.estado || raw.status || 'FALTA').trim().toUpperCase();
  const estado = rawEstado === 'LICENCIA MÉDICA' ? 'LICENCIA MEDICA' : rawEstado;
  return {
    idRegistro: String(raw.idRegistro || raw.id || '').trim(),
    nombreBombero: String(raw.nombreBombero || raw.nombreCompleto || raw.nombre || '').trim().toUpperCase(),
    ordenEnLista: Number(raw.ordenEnLista ?? raw.orden ?? 999) || 999,
    estado
  };
}

/**
 * Normaliza en modo Dual-Read un registro de `asistencia/{idLista}/bomberos/{idRegistro}`.
 */
export function normalizeAttendanceFirefighterDoc(docId, raw = {}) {
  if (!raw || typeof raw !== 'object') return null;
  const rawEstado = String(raw.estado || raw.status || 'FALTA').trim().toUpperCase();
  const estado = rawEstado === 'LICENCIA MÉDICA' ? 'LICENCIA MEDICA' : rawEstado;
  return {
    idRegistro: String(raw.idRegistro || docId || '').trim(),
    nombreBombero: String(raw.nombreBombero || raw.nombre || '').trim().toUpperCase(),
    ordenEnLista: Number(raw.ordenEnLista ?? raw.orden ?? 999) || 999,
    estado,
    _metaTimestamp: Number(raw._metaTimestamp || 0)
  };
}

// ============================================================================
// 9. DEPARTAMENTO MÉDICO E HISTORIAL CLÍNICO (SENTINEL MED & SENTINEL CORE)
// ============================================================================

/**
 * Normaliza en modo Dual-Read la Ficha Médica de un Bombero combinando la subcolección
 * `personal/{idRegistro}/salud/actual` (V2/V3) y los campos médicos de `personal/{idRegistro}`.
 */
export function normalizeFirefighterHealthDoc(idRegistro, rawSalud = {}, rawPersonal = {}) {
  const s = (rawSalud && typeof rawSalud === 'object') ? rawSalud : {};
  const p = (rawPersonal && typeof rawPersonal === 'object') ? rawPersonal : {};

  const cleanId = String(idRegistro || s.idRegistro || p.idRegistro || p.id || '').trim();
  const sangre = String(s.sangre || s.grupoSanguineo || p.grupoSanguineo || p.sangre || '').trim().toUpperCase();
  const alergias = String(s.alergias || p.alergias || '').trim().toUpperCase();
  const cronicas = String(s.cronicas || s.enfermedadesCronicas || p.enfermedadesCronicas || p.cronicas || '').trim().toUpperCase();
  const medicamentos = String(s.medicamentos || p.medicamentos || '').trim().toUpperCase();
  const previsionSalud = String(s.previsionSalud || s.prevision || p.previsionSalud || p.prevision || '').trim().toUpperCase();
  const seguroAccidentes = String(s.seguroAccidentes || 'SI').trim().toUpperCase();
  const detalleSeguro = String(s.detalleSeguro || '').trim().toUpperCase();
  const contactoNombre = String(s.contactoNombre || s.contactoEmergenciaNombre || p.contactoEmergencia || s.contactoEmergencia || '').trim().toUpperCase();
  const contactoFono = sanitizeChileanPhone(s.contactoFono || s.contactoEmergenciaTelefono || p.telefonoEmergencia || s.telefonoEmergencia || '');
  const contactoParentesco = String(s.contactoEmergenciaParentesco || s.contactoParentesco || '').trim().toUpperCase();
  const observaciones = String(s.observaciones || s.observacionesMedicas || p.observacionesMedicas || '').trim().toUpperCase();

  // Licencia médica (soporta tanto objeto V2/MED { activa, inicio, fin, tipoReposo, observaciones } como timestamp/string CORE)
  const rawLicencia = s.licenciaMedica ?? p.licenciaMedica ?? p.licenciaHasta ?? null;
  const isObjLic = rawLicencia && typeof rawLicencia === 'object';
  const licFinStr = isObjLic ? String(rawLicencia.fin || '') : (typeof rawLicencia === 'string' ? rawLicencia : String(p.licenciaHasta || ''));
  const licInicioStr = isObjLic ? String(rawLicencia.inicio || '') : String(p.licenciaDesde || '');
  const licenciaMedicaTs = isObjLic
    ? parseToEpochMs(rawLicencia.finTimestamp || rawLicencia.fin, '', licFinStr)
    : parseToEpochMs(rawLicencia, '', licFinStr);

  const isLicenciaActiva = Boolean(
    (isObjLic && String(rawLicencia.activa || '').toUpperCase() === 'SI') ||
    p.enLicenciaMedica === true ||
    (licenciaMedicaTs && licenciaMedicaTs >= Date.now() - 86400000) ||
    String(p.estado || '').toUpperCase().includes('LICENCIA')
  );

  const motivoLicencia = String(
    (isObjLic ? (rawLicencia.tipoReposo || rawLicencia.observaciones) : '') ||
    s.motivoLicencia ||
    p.motivoLicencia ||
    ''
  ).trim().toUpperCase();

  const licenciaMedicaObj = {
    activa: isLicenciaActiva ? 'SI' : 'NO',
    inicio: licInicioStr,
    fin: licFinStr || formatEpochToDDMMYYYY(licenciaMedicaTs) || '',
    tipoReposo: motivoLicencia,
    observaciones: isObjLic ? String(rawLicencia.observaciones || motivoLicencia).toUpperCase() : motivoLicencia,
    finTimestamp: licenciaMedicaTs || null
  };

  let aptitudOperativa = String(s.aptitudOperativa || '').trim().toUpperCase();
  if (!aptitudOperativa) {
    aptitudOperativa = isLicenciaActiva ? 'BAJA_TEMPORAL' : 'APTO';
  }

  const svRaw = (s.signosVitalesBase && typeof s.signosVitalesBase === 'object') ? s.signosVitalesBase : {};
  const pesoKg = Number(svRaw.pesoKg ?? s.pesoKg ?? 0) || null;
  const tallaCm = Number(svRaw.tallaCm ?? s.tallaCm ?? s.alturaCm ?? 0) || null;
  let imc = Number(svRaw.imc ?? s.imc ?? 0) || null;
  if (!imc && pesoKg && tallaCm && tallaCm > 50) {
    const m = tallaCm / 100;
    imc = Number((pesoKg / (m * m)).toFixed(1));
  }

  const signosVitalesBase = {
    paSistolica: Number(svRaw.paSistolica ?? 0) || null,
    paDiastolica: Number(svRaw.paDiastolica ?? 0) || null,
    fc: Number(svRaw.fc ?? 0) || null,
    spo2: Number(svRaw.spo2 ?? 0) || null,
    temperatura: Number(svRaw.temperatura ?? 0) || null,
    pesoKg,
    tallaCm,
    imc,
    timestamp: parseToEpochMs(svRaw.timestamp) || null
  };

  const ts = parseToEpochMs(s.timestamp ?? s._metaTimestamp, '', s.fechaActualizacion) || Date.now();
  const proximoControlTimestamp = parseToEpochMs(s.proximoControlTimestamp, '', s.proximoControlFecha) || null;

  return {
    id: cleanId,
    idRegistro: cleanId,
    sangre,
    grupoSanguineo: sangre,
    alergias,
    cronicas,
    enfermedadesCronicas: cronicas,
    medicamentos,
    previsionSalud,
    prevision: previsionSalud,
    seguroAccidentes,
    detalleSeguro,
    contactoNombre,
    contactoEmergencia: contactoNombre,
    contactoEmergenciaNombre: contactoNombre,
    contactoFono,
    telefonoEmergencia: contactoFono,
    contactoEmergenciaTelefono: contactoFono,
    contactoEmergenciaParentesco: contactoParentesco,
    observaciones,
    observacionesMedicas: observaciones,
    aptitudOperativa,
    pesoKg,
    alturaCm: tallaCm,
    tallaCm,
    imc,
    ultimaEvaluacionMedica: String(s.ultimaEvaluacionMedica || '').trim(),
    licenciaMedica: licenciaMedicaObj,
    licenciaMedicaTs,
    licenciaHastaStr: formatEpochToDDMMYYYY(licenciaMedicaTs) || licFinStr,
    motivoLicencia,
    isLicenciaActiva,
    signosVitalesBase,
    proximoControlTimestamp,
    proximoControlStr: formatEpochToDDMMYYYY(proximoControlTimestamp),
    actualizadoPor: String(s.actualizadoPor || 'DEPARTAMENTO MEDICO').trim().toUpperCase(),
    timestamp: ts,
    fechaActualizacionStr: formatEpochToDDMMYYYY(ts),
    _metaTimestamp: Math.max(ts, Number(s._metaTimestamp || 0))
  };
}

/**
 * Construye el payload canónico para `personal/{idRegistro}/salud/actual`
 * manteniendo compatibilidad 100% con V2, SENTINEL CORE y SENTINEL MED.
 */
export function buildCanonicalFirefighterHealthDoc(raw = {}) {
  const nowMs = Number(raw.timestamp || Date.now());
  const norm = normalizeFirefighterHealthDoc(raw.idRegistro || raw.id, raw, raw);
  return {
    idRegistro: norm.idRegistro,
    sangre: norm.sangre,
    grupoSanguineo: norm.sangre,
    alergias: norm.alergias,
    cronicas: norm.cronicas,
    enfermedadesCronicas: norm.cronicas,
    medicamentos: norm.medicamentos,
    previsionSalud: norm.previsionSalud,
    prevision: norm.previsionSalud,
    seguroAccidentes: norm.seguroAccidentes,
    detalleSeguro: norm.detalleSeguro,
    contactoNombre: norm.contactoNombre,
    contactoEmergenciaNombre: norm.contactoNombre,
    contactoFono: norm.contactoFono,
    contactoEmergenciaTelefono: norm.contactoFono,
    contactoEmergenciaParentesco: norm.contactoEmergenciaParentesco,
    observaciones: norm.observaciones,
    observacionesMedicas: norm.observaciones,
    aptitudOperativa: norm.aptitudOperativa,
    pesoKg: norm.pesoKg,
    alturaCm: norm.alturaCm,
    tallaCm: norm.tallaCm,
    imc: norm.imc,
    ultimaEvaluacionMedica: norm.ultimaEvaluacionMedica,
    licenciaMedica: norm.licenciaMedica,
    licenciaMedicaTs: norm.licenciaMedicaTs || null,
    motivoLicencia: norm.motivoLicencia || '',
    isLicenciaActiva: norm.isLicenciaActiva,
    signosVitalesBase: norm.signosVitalesBase,
    proximoControlTimestamp: norm.proximoControlTimestamp || null,
    actualizadoPor: norm.actualizadoPor,
    timestamp: nowMs,
    _metaTimestamp: nowMs
  };
}

/**
 * Calcula el siguiente correlativo canónico `AAAA-00000` (ej: `"2026-00001"`)
 * para registros clínicos de pacientes en `atenciones_medicas`.
 */
export function calculateNextCanonicalMedicalRecordId(recordsArray = [], dateObj = new Date()) {
  const d = dateObj instanceof Date ? dateObj : new Date(dateObj || Date.now());
  const yearPrefix = String(d.getFullYear());
  let maxSeq = 0;

  if (Array.isArray(recordsArray)) {
    recordsArray.forEach((r) => {
      const idStr = String(r?.idAtencion || r?.idFicha || r?.id || '').trim();
      if (idStr.startsWith(`${yearPrefix}-`)) {
        const seq = parseInt(idStr.split('-')[1], 10);
        if (!isNaN(seq) && seq > maxSeq) maxSeq = seq;
      }
    });
  }

  return `${yearPrefix}-${String(maxSeq + 1).padStart(5, '0')}`;
}

/**
 * Construye un documento canónico de Atención Clínica de Paciente en Emergencia
 * (`atenciones_medicas/{idAtencion}`) compatible con SENTINEL MED y SENTINEL CORE.
 */
export function buildCanonicalPatientCareDoc(raw = {}) {
  const nowMs = Number(raw.timestamp || Date.now());
  const idAtencion = String(raw.idAtencion || raw.idFicha || raw.id || `${new Date(nowMs).getFullYear()}-00001`).trim();
  const carro = String(raw.carro || raw.unidadMovil || raw.unidad || '').trim().toUpperCase();
  const compania = resolveCompanyFromVehicleId(carro, raw.compania || raw.companiaPaciente);

  const pacRaw = (raw.paciente && typeof raw.paciente === 'object') ? raw.paciente : raw;
  const abcRaw = (raw.evaluacionABCDE && typeof raw.evaluacionABCDE === 'object') ? raw.evaluacionABCDE : raw;
  const svRaw = (raw.signosVitales && typeof raw.signosVitales === 'object') ? raw.signosVitales : raw;
  const trRaw = (raw.traslado615 && typeof raw.traslado615 === 'object') ? raw.traslado615 : raw;
  const atPorRaw = (raw.atendidoPor && typeof raw.atendidoPor === 'object') ? raw.atendidoPor : {};

  const glasgowOcular = Math.min(4, Math.max(1, parseInt(String(raw.glasgowOcular ?? abcRaw.glasgowOcular ?? 4), 10) || 4));
  const glasgowVerbal = Math.min(5, Math.max(1, parseInt(String(raw.glasgowVerbal ?? abcRaw.glasgowVerbal ?? 5), 10) || 5));
  const glasgowMotor = Math.min(6, Math.max(1, parseInt(String(raw.glasgowMotor ?? abcRaw.glasgowMotor ?? 6), 10) || 6));
  const glasgowNum = Math.min(
    15,
    Math.max(3, parseInt(String(raw.glasgowTotal ?? abcRaw.glasgow ?? (glasgowOcular + glasgowVerbal + glasgowMotor)), 10) || 15)
  );
  const evaNum = Math.min(10, Math.max(0, parseInt(String(svRaw.evaDolor ?? raw.evaDolor ?? 0), 10) || 0));

  const triageCanon = String(raw.triageStart || raw.triage || 'VERDE').trim().toUpperCase();
  const nombrePac = String(pacRaw.nombre || raw.nombrePaciente || pacRaw.nombrePaciente || 'PACIENTE N/N').trim().toUpperCase();
  const rutPac = String(pacRaw.rut || raw.documentoPaciente || pacRaw.rutPaciente || '').trim().toUpperCase();
  const edadPac = (pacRaw.edad ?? raw.edadPaciente) !== undefined && (pacRaw.edad ?? raw.edadPaciente) !== ''
    ? Number(pacRaw.edad ?? raw.edadPaciente) || 0
    : null;
  const sexoPac = String(pacRaw.sexo || raw.sexoPaciente || 'M').trim().toUpperCase();
  const alergiasPac = String(pacRaw.alergias || raw.alergiasConocidas || '').trim().toUpperCase();

  const viaAerea = String(abcRaw.viaAerea || raw.viaAerea || 'PERMEABLE').trim().toUpperCase();
  const ventilacion = String(abcRaw.ventilacion || raw.ventilacion || 'ESPONTANEA NORMAL').trim().toUpperCase();
  const circulacion = String(abcRaw.circulacion || raw.circulacion || 'PULSO PRESENTE, SIN HEMORRAGIA').trim().toUpperCase();
  const pupilas = String(abcRaw.pupilas || raw.neurologico || 'ISOCORICAS REACTIVAS').trim().toUpperCase();
  const exposicionLesiones = String(abcRaw.exposicionLesiones || raw.exposicionLesiones || raw.motivoConsulta || abcRaw.lesiones || '').trim().toUpperCase();
  const motivoConsulta = String(raw.motivoConsulta || exposicionLesiones || 'EVALUACION PREHOSPITALARIA').trim().toUpperCase();

  const fc = Number(svRaw.fc ?? raw.fc ?? 0) || null;
  const fr = Number(svRaw.fr ?? raw.fr ?? 0) || null;
  const paSistolica = Number(svRaw.paSistolica ?? raw.paSistolica ?? 0) || null;
  const paDiastolica = Number(svRaw.paDiastolica ?? raw.paDiastolica ?? 0) || null;
  const spo2 = Number(svRaw.spo2 ?? raw.spo2 ?? 0) || null;
  const temperatura = Number(svRaw.temperatura ?? raw.temperatura ?? 0) || null;
  const hgt = Number(svRaw.hgt ?? raw.glucemia ?? 0) || null;

  const estadoTraslado = String(
    raw.estadoTraslado ||
    (trRaw.requiereTraslado ? 'EN_TRASLADO_6_15' : 'EN_ESCENA')
  ).trim().toUpperCase();
  const requiereTraslado =
    Boolean(trRaw.requiereTraslado) ||
    estadoTraslado === 'EN_TRASLADO_6_15' ||
    estadoTraslado === 'ENTREGADO_HOSPITAL';
  const medioTraslado = String(trRaw.medioTraslado || raw.unidadTraslado || 'SAMU / AMBULANCIA').trim().toUpperCase();
  const centroAsistencial = String(trRaw.centroAsistencial || raw.centroAsistencialDestino || '').trim().toUpperCase();
  const responsableRecepcion = String(trRaw.responsableRecepcion || raw.medicoReceptor || '').trim().toUpperCase();
  const evaluadorNombre = String(atPorRaw.nombre || raw.evaluador || raw.atendidoPorNombre || raw.operador || 'OFICIAL SANIDAD').trim().toUpperCase();

  const procedimientos = Array.isArray(raw.procedimientos)
    ? raw.procedimientos.map((p) => String(p).trim().toUpperCase()).filter(Boolean)
    : String(raw.procedimientos || '')
        .split(',')
        .map((p) => p.trim().toUpperCase())
        .filter(Boolean);

  return {
    id: idAtencion,
    idFicha: idAtencion,
    idAtencion,
    idServicio: String(raw.idServicio || raw.idDespacho || '').trim(),
    idDespacho: String(raw.idDespacho || raw.idServicio || '').trim(),
    clave: String(raw.clave || raw.claveEmergencia || '10-4').trim().toUpperCase(),
    claveEmergencia: String(raw.claveEmergencia || raw.clave || '10-4').trim().toUpperCase(),
    direccion: String(raw.direccion || raw.direccionIncidente || raw.lugar || '').trim().toUpperCase(),
    direccionIncidente: String(raw.direccionIncidente || raw.direccion || raw.lugar || '').trim().toUpperCase(),
    carro,
    unidadMovil: carro,
    compania,
    companiaPaciente: String(raw.companiaPaciente || compania || '').trim().toUpperCase(),
    timestamp: nowMs,
    tipoPaciente: String(raw.tipoPaciente || 'CIVIL').trim().toUpperCase(),
    idRegistroBombero: String(raw.idRegistroBombero || raw.idBomberoAccidentado || '').trim(),
    idBomberoAccidentado: String(raw.idBomberoAccidentado || raw.idRegistroBombero || '').trim(),
    nombrePaciente: nombrePac,
    documentoPaciente: rutPac,
    edadPaciente: edadPac,
    sexoPaciente: sexoPac,
    alergiasConocidas: alergiasPac,
    paciente: {
      nombre: nombrePac,
      rut: rutPac,
      edad: edadPac,
      sexo: sexoPac,
      telefono: sanitizeChileanPhone(pacRaw.telefono || ''),
      grupoSanguineo: String(pacRaw.grupoSanguineo || pacRaw.sangre || '').trim().toUpperCase(),
      alergias: alergiasPac,
      antecedentes: String(pacRaw.antecedentes || '').trim().toUpperCase()
    },
    triage: triageCanon,
    triageStart: triageCanon,
    motivoConsulta,
    mecanismoLesion: String(raw.mecanismoLesion || '').trim().toUpperCase(),
    viaAerea,
    ventilacion,
    circulacion,
    neurologico: pupilas,
    exposicionLesiones,
    glasgowOcular,
    glasgowVerbal,
    glasgowMotor,
    glasgowTotal: glasgowNum,
    evaluacionABCDE: {
      viaAerea,
      ventilacion,
      circulacion,
      glasgow: glasgowNum,
      pupilas,
      exposicionLesiones
    },
    fc,
    fr,
    paSistolica,
    paDiastolica,
    spo2,
    temperatura,
    glucemia: hgt,
    evaDolor: evaNum,
    signosVitales: {
      fc,
      fr,
      paSistolica,
      paDiastolica,
      spo2,
      temperatura,
      hgt,
      evaDolor: evaNum,
      timestamp: Number(svRaw.timestamp || nowMs)
    },
    procedimientos,
    medicamentosAdministrados: String(raw.medicamentosAdministrados || '').trim().toUpperCase(),
    estadoTraslado,
    centroAsistencialDestino: centroAsistencial,
    unidadTraslado: medioTraslado,
    medicoReceptor: responsableRecepcion,
    traslado615: {
      requiereTraslado,
      medioTraslado,
      centroAsistencial,
      responsableRecepcion,
      timestampEntrega: trRaw.timestampEntrega ? Number(trRaw.timestampEntrega) : null
    },
    evaluador: evaluadorNombre,
    atendidoPor: {
      idRadial: String(atPorRaw.idRadial || raw.operadorRadial || '').trim().toUpperCase(),
      nombre: evaluadorNombre
    },
    observaciones: String(raw.observaciones || raw.observacionesClinicas || '').trim().toUpperCase(),
    observacionesClinicas: String(raw.observacionesClinicas || raw.observaciones || '').trim().toUpperCase(),
    _metaTimestamp: Math.max(nowMs, Number(raw._metaTimestamp || 0))
  };
}

/**
 * Normaliza en modo Dual-Read un documento de `atenciones_medicas/{idAtencion}`.
 */
export function normalizePatientCareDoc(docId, raw = {}) {
  if (!raw || typeof raw !== 'object') return null;
  const canonical = buildCanonicalPatientCareDoc({
    ...raw,
    idAtencion: raw.idAtencion || raw.idFicha || raw.id || docId
  });
  return {
    ...canonical,
    fechaStr: formatEpochToDDMMYYYY(canonical.timestamp),
    horaStr: formatEpochToHHMM(canonical.timestamp)
  };
}

/**
 * Construye un registro canónico de Monitoreo / Rehabilitación Táctica de Bombero (NFPA 1584)
 * en `personal/{idRegistro}/controles_salud/{idControl}`.
 */
export function buildCanonicalRehabCheckDoc(raw = {}) {
  const nowMs = Number(raw.timestamp || Date.now());
  const idControl = String(raw.idControl || raw.id || `CTRL-${nowMs}`).trim();
  const idBombero = String(raw.idRegistro || raw.idBombero || '').trim();
  const faseControl = String(raw.faseControl || raw.contexto || 'ENTRADA_REHAB').trim().toUpperCase();
  const condicionSalida = String(raw.condicionSalida || raw.estadoRehab || 'APTO_RETORNO').trim().toUpperCase();
  const hidratacion = String(raw.hidratacionAdministrada || raw.hidratacion || 'ADECUADA').trim().toUpperCase();
  const cohb = Number(raw.cohb ?? raw.carboxihemoglobina ?? 0) || null;
  const glucemia = Number(raw.glucemia ?? raw.hgt ?? 0) || null;

  return {
    id: idControl,
    idControl,
    idRegistro: idBombero,
    idBombero,
    nombreBombero: String(raw.nombreBombero || '').trim().toUpperCase(),
    idRadial: String(raw.idRadial || '').trim().toUpperCase(),
    compania: String(raw.compania || '1').trim().toUpperCase(),
    idServicio: String(raw.idServicio || raw.idDespacho || '').trim(),
    idDespacho: String(raw.idDespacho || raw.idServicio || '').trim(),
    contexto: faseControl,
    faseControl,
    paSistolica: Number(raw.paSistolica ?? 0) || null,
    paDiastolica: Number(raw.paDiastolica ?? 0) || null,
    fc: Number(raw.fc ?? 0) || null,
    fr: Number(raw.fr ?? 0) || null,
    spo2: Number(raw.spo2 ?? 0) || null,
    temperatura: Number(raw.temperatura ?? 0) || null,
    hgt: glucemia,
    glucemia,
    carboxihemoglobina: cohb,
    cohb,
    nivelFatiga: Number(raw.nivelFatiga ?? 1) || 1,
    minutosExposicionScba: Number(raw.minutosExposicionScba ?? 0) || 0,
    hidratacion,
    hidratacionAdministrada: hidratacion,
    estadoRehab: condicionSalida,
    condicionSalida,
    observaciones: String(raw.observaciones || '').trim().toUpperCase(),
    evaluador: String(raw.evaluador || 'OFICIAL SANIDAD').trim().toUpperCase(),
    timestamp: nowMs,
    _metaTimestamp: nowMs
  };
}

export function normalizeRehabCheckDoc(docId, raw = {}) {
  if (!raw || typeof raw !== 'object') return null;
  const canonical = buildCanonicalRehabCheckDoc({
    ...raw,
    idControl: raw.idControl || raw.id || docId
  });
  return {
    ...canonical,
    fechaStr: formatEpochToDDMMYYYY(canonical.timestamp),
    horaStr: formatEpochToHHMM(canonical.timestamp)
  };
}

// ============================================================================
// 10. REGLAS CANÓNICAS DE LOGIN UNIFICADO Y AUTORIDAD DE COMANDANTE (ID RADIAL 1)
// ============================================================================

/**
 * Regla Canónica del Ecosistema SENTINEL V3:
 * Si el usuario al que se anexa tiene `idRadial === "1"`, significa canónicamente
 * que es el Comandante del Cuerpo de Bomberos.
 */
export function isCanonicalCommander(personDoc = {}) {
  if (!personDoc || typeof personDoc !== 'object') return false;
  const rad = String(personDoc.idRadial ?? '').trim().toUpperCase();
  return rad === '1';
}

/**
 * Formatea el título institucional canónico para la tarjeta de Login unificada
 * (ej: "Chépica" -> "CUERPO DE BOMBEROS DE CHÉPICA").
 */
export function formatCanonicalInstitutionTitle(rawName = '') {
  if (!rawName) return 'CUERPO DE BOMBEROS';
  const name = String(rawName).trim();
  const clean = name
    .replace(/^cuerpo\s+de\s+bomberos\s+de\s+/i, '')
    .replace(/^cuerpo\s+de\s+bomberos\s+/i, '')
    .replace(/^cb\s+de\s+/i, '')
    .replace(/^cb\s+/i, '')
    .trim();
  if (!clean) return 'CUERPO DE BOMBEROS';
  return `CUERPO DE BOMBEROS DE ${clean.toUpperCase()}`;
}

/**
 * Formatea la etiqueta de compañía para selectores y badges (ej: 1 -> "1ª Compañía").
 */
export function formatCompanyLabel(compId, customName = '') {
  if (customName && String(customName).trim()) {
    return String(customName).trim();
  }
  const num = parseInt(String(compId || '1').replace(/\D/g, ''), 10) || 1;
  return `${num}ª Compañía`;
}

/**
 * Normaliza en modo Dual-Read un documento de `personal/{idRegistro}` (compatible con V2 y V3).
 */
export function normalizeFirefighterDoc(docId, raw = {}) {
  if (!raw || typeof raw !== 'object') return {};
  const idRegistro = String(raw.idRegistro || raw.id || docId || '').trim();
  const idRadial = String(raw.idRadial ?? raw.codigoRadial ?? idRegistro).trim().toUpperCase();
  const nombreBombero = String(raw.nombreBombero || raw.nombreCompleto || raw.nombre || '').trim().toUpperCase();
  const cargo = String(raw.cargo || 'VOLUNTARIO').trim().toUpperCase();

  let compania = parseInt(String(raw.compania ?? '').replace(/\D/g, ''), 10);
  if (isNaN(compania) || compania <= 0) {
    const cargoMatch = cargo.match(/(\d+)\s*[°ªº]?\s*(?:C[IÍ]A|COMPA[ÑN][IÍ]A)/i);
    if (cargoMatch) {
      compania = parseInt(cargoMatch[1], 10) || 1;
    } else {
      compania = 1;
    }
  }

  const rawActivoStr = String(raw.activo ?? 'SI').trim().toUpperCase();
  const isActivo =
    raw.activo === undefined ||
    raw.activo === null ||
    raw.activo === '' ||
    raw.activo === true ||
    raw.activo === 1 ||
    rawActivoStr === '1' ||
    rawActivoStr === 'SI' ||
    rawActivoStr === 'SÍ' ||
    rawActivoStr === 'S' ||
    rawActivoStr === 'TRUE' ||
    rawActivoStr === 'ACTIVO';

  return {
    id: idRegistro,
    idRegistro,
    rut: String(raw.rut || '').trim(),
    idRadial,
    nombreBombero,
    foto: String(raw.foto || '').trim(),
    numeroRadio: String(raw.numeroRadio || '').trim(),
    compania,
    cargo,
    ordenEnLista: Number(raw.ordenEnLista ?? raw.orden ?? 999) || 999,
    activo: isActivo,
    conductor: Boolean(raw.conductor ?? raw.esConductor),
    estado: String(raw.estado || '0-9').trim(),
    enServicio: String(raw.enServicio ?? '0').trim(),
    contrasena: String(raw.contrasena ?? raw.password ?? '').trim(),
    _metaTimestamp: Number(raw._metaTimestamp || 0)
  };
}

/**
 * Normaliza en modo Dual-Read un documento de `despachos/{idServicio}` (compatible con V2 y V3).
 */
export function normalizeDispatchDoc(docId, raw = {}) {
  if (!raw || typeof raw !== 'object') return {};
  const idServicio = String(raw.idServicio || raw.id || docId || '').trim();
  const clave = String(raw.clave || raw.key || '').trim().toUpperCase();
  const subclave = String(raw.subclave || raw.evento || '').trim();
  const direccion = String(raw.direccion || raw.lugar || raw.location || '').trim().toUpperCase();
  const ts = parseToEpochMs(
    raw.createdAt ?? raw.timestamp ?? raw.h67,
    raw.horaDespacho ?? raw.hora,
    raw.fechaDespacho ?? raw.fecha
  ) || Date.now();

  let vehiculo = String(raw.vehiculo || raw.idCarro || '').trim().toUpperCase();
  if (!vehiculo && raw.unidades && typeof raw.unidades === 'object' && !Array.isArray(raw.unidades)) {
    vehiculo = Object.keys(raw.unidades).join(', ');
  } else if (!vehiculo && Array.isArray(raw.unidades)) {
    vehiculo = raw.unidades.map((u) => (typeof u === 'string' ? u : u?.name || u?.carro || '')).filter(Boolean).join(', ');
  }

  return {
    ...raw,
    id: idServicio,
    idServicio,
    clave,
    subclave,
    evento: String(raw.evento || subclave || clave).trim(),
    direccion,
    lugar: direccion,
    vehiculo,
    timestamp: ts,
    fechaDespacho: formatEpochToDDMMYYYY(ts) || String(raw.fechaDespacho || raw.fecha || '').trim(),
    horaDespacho: formatEpochToHHMM(ts) || String(raw.horaDespacho || raw.hora || '').trim(),
    obac: raw.obac || '',
    idLista: String(raw.idLista || '').trim(),
    idListaPrincipal: String(raw.idListaPrincipal || '').trim(),
    estado: String(raw.estado || 'activa').trim(),
    unidades: (raw.unidades && typeof raw.unidades === 'object') ? raw.unidades : {},
    geo: raw.geo || (raw.coords ? { lat: Number(raw.coords.lat), lng: Number(raw.coords.lng) } : null),
    visibleMovil: raw.visibleMovil !== false,
    _metaTimestamp: Number(raw._metaTimestamp || 0),
    _fieldTimestamps: (raw._fieldTimestamps && typeof raw._fieldTimestamps === 'object') ? raw._fieldTimestamps : {}
  };
}

/**
 * Configuración canónica del flujo de Anexado de Licencia, Autorización de Comandante (idRadial == "1")
 * y comportamiento posterior por cada uno de los 8 módulos del ecosistema SENTINEL V3 (sin HWID).
 */
export const SENTINEL_UNIFIED_AUTH_MODES = {
  CAD: {
    moduleId: 'CAD',
    requiresCommanderAuth: true,
    postAuthMode: 'OPERATOR_LOGIN',
    subtitle: 'CENTRAL DE ALARMAS Y DESPACHOS',
    logoFile: '/sentinel_cad_logo.png'
  },
  CORE: {
    moduleId: 'CORE',
    requiresCommanderAuth: true,
    postAuthMode: 'OPERATOR_LOGIN',
    subtitle: 'CONTROL OPERATIVO Y COMANDANCIA',
    logoFile: '/sentinel_core_logo.png'
  },
  MED: {
    moduleId: 'MED',
    requiresCommanderAuth: true,
    postAuthMode: 'OPERATOR_LOGIN',
    subtitle: 'DEPARTAMENTO MÉDICO INSTITUCIONAL',
    logoFile: '/SENTINEL MED.png'
  },
  SCI: {
    moduleId: 'SCI',
    requiresCommanderAuth: true,
    postAuthMode: 'OPERATOR_LOGIN',
    subtitle: 'SISTEMA DE COMANDO DE INCIDENTES',
    logoFile: '/sentinel_sci_logo.png'
  },
  LINK: {
    moduleId: 'LINK',
    requiresCommanderAuth: true,
    postAuthMode: 'OPERATOR_LOGIN',
    subtitle: 'ENLACE TELEFÓNICO Y ACCESO CENTRAL',
    logoFile: '/sentinel_link_logo.png'
  },
  NAV: {
    moduleId: 'NAV',
    requiresCommanderAuth: true,
    postAuthMode: 'LOCK_SINGLE_VEHICLE',
    subtitle: 'NAVEGADOR TÁCTICO VEHICULAR',
    logoFile: '/sentinel_nav_logo.png'
  },
  ONE: {
    moduleId: 'ONE',
    requiresCommanderAuth: false,
    postAuthMode: 'FIREFIGHTER_LOGIN',
    subtitle: 'APLICACIÓN OPERATIVA PERSONAL',
    logoFile: '/sentinel_one_logo.png'
  },
  CREW: {
    moduleId: 'CREW',
    requiresCommanderAuth: true,
    postAuthMode: 'DIRECT_ATTENDANCE_OBAC_SIGN',
    subtitle: 'CONTROL Y REGISTRO DE ASISTENCIA',
    logoFile: '/sentinel_crew_logo.png'
  }
};

/**
 * Busca y valida un bombero/usuario en la colección `personal` por `idRegistro`, `idRadial` o `rut`,
 * comprobando su `contrasena` (o validación positiva previa en Firebase Auth) y estado `activo`.
 */
export function matchPersonnelCredentials(
  personnelList = [],
  username = '',
  password = '',
  firebaseAuthConfirmed = false
) {
  const u = String(username || '').trim();
  const p = String(password || '').trim();
  if (!u || !p) {
    return { matched: false, user: null, error: 'Complete todos los campos' };
  }

  const list = Array.isArray(personnelList) ? personnelList : [];
  const uLower = u.toLowerCase();
  const uCleanRut = u.replace(/[^0-9kK]/g, '').toLowerCase();

  const matchesIdentity = (x) => {
    if (!x || typeof x !== 'object') return false;
    const idReg = String(x.idRegistro ?? x.id ?? '').trim().toLowerCase();
    const idRad = String(x.idRadial ?? '').trim().toLowerCase();
    const rutClean = String(x.rut ?? '').replace(/[^0-9kK]/g, '').toLowerCase();
    return (
      (idReg && idReg === uLower) ||
      (idRad && idRad === uLower) ||
      (uCleanRut && rutClean && rutClean === uCleanRut)
    );
  };

  let found = list.find(
    (x) => matchesIdentity(x) && String(x.contrasena ?? x.password ?? '').trim() === p
  );

  if (!found && firebaseAuthConfirmed) {
    found = list.find((x) => matchesIdentity(x));
  }

  if (!found) {
    return { matched: false, user: null, error: 'Credenciales incorrectas' };
  }

  const isActivoRaw = String(found.activo ?? 'SI').trim().toUpperCase();
  const isActivo =
    found.activo === true ||
    isActivoRaw === 'SI' ||
    isActivoRaw === 'SÍ' ||
    isActivoRaw === '1' ||
    isActivoRaw === 'S' ||
    isActivoRaw === 'TRUE';

  if (!isActivo) {
    return { matched: false, user: null, error: 'Usuario Inactivo' };
  }

  return { matched: true, user: found, error: '' };
}

/**
 * Valida la autorización de un módulo por parte del Comandante:
 * El "Usuario" ingresado es estrictamente su `idRegistro` (junto con su "Clave de Acceso").
 * Si el usuario (`idRegistro`) encontrado en `personal` posee `idRadial === "1"`, significa que es el Comandante y se autoriza.
 */
export function verifyCommanderAuthorization(
  personnelList = [],
  username = '',
  password = '',
  firebaseAuthConfirmed = false
) {
  const u = String(username || '').trim().toLowerCase();
  const p = String(password || '').trim();
  if (!u || !p) {
    const msg = 'Complete Usuario y Clave de Acceso';
    return {
      ok: false,
      authorized: false,
      person: null,
      commander: null,
      reason: msg,
      error: msg
    };
  }

  const list = Array.isArray(personnelList) ? personnelList : [];
  const matchesIdRegistro = (x) => {
    if (!x || typeof x !== 'object') return false;
    const idReg = String(x.idRegistro ?? x.id ?? '').trim().toLowerCase();
    return Boolean(idReg && idReg === u);
  };

  let found = list.find(
    (x) => matchesIdRegistro(x) && String(x.contrasena ?? x.password ?? '').trim() === p
  );

  if (!found && firebaseAuthConfirmed) {
    found = list.find((x) => matchesIdRegistro(x));
  }

  if (!found) {
    const msg = 'Usuario (idRegistro) o Clave de Acceso incorrectos';
    return {
      ok: false,
      authorized: false,
      person: null,
      commander: null,
      reason: msg,
      error: msg
    };
  }

  if (!isCanonicalCommander(found)) {
    const msg = 'Acceso denegado: El usuario ingresado no posee ID Radial = 1 (Comandante)';
    return {
      ok: false,
      authorized: false,
      person: null,
      commander: null,
      reason: msg,
      error: msg
    };
  }

  return {
    ok: true,
    authorized: true,
    person: found,
    commander: found,
    reason: '',
    error: ''
  };
}




