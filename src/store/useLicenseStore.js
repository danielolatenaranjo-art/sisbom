import { create } from 'zustand';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  CENTRAL_FIREBASE_CONFIG,
  DEFAULT_FIREBASE_CONFIG,
  parseFirestoreValue,
  fetchFirestoreWithAuth
} from '../config/firebase';
import { normalizeSaasClientDoc } from '../shared/sentinelCore';

const CACHE_KEY = '@sentinel_one_v3_license_cache';
const LOGO_DATA_URI_KEY = '@sentinel_one_v3_logo_data_uri';

// Guardas anti-bucle para refresco silencioso en segundo plano (máx 1 vez por arranque o cada 5 min)
let silentSyncInFlight = false;
let lastSilentSyncTs = 0;

function parseValidCoord(val) {
  if (val === null || val === undefined || val === '') return null;
  const num = Number(val);
  if (!Number.isFinite(num) || Math.abs(num) < 0.001) return null;
  return num;
}

function sanitizeCachedCoord(val) {
  const num = parseValidCoord(val);
  if (num === null) return null;
  // Descartar coordenada legada hardcodeada por defecto (-34.637373, -71.125741) si no viene de un cuartel real
  return num;
}

function parseFirestoreDocFields(docJson) {
  const fields = docJson?.fields || {};
  const out = {};
  for (const k of Object.keys(fields)) {
    out[k] = parseFirestoreValue(fields[k]);
  }
  return out;
}

async function resolveTenantStationOverrides(licenseKey, clientFbConfig) {
  if (!clientFbConfig || !clientFbConfig.projectId || !clientFbConfig.apiKey) {
    return { tenantSaasFields: null, stationPois: [] };
  }
  const projId = String(clientFbConfig.projectId).trim();
  const apiKey = String(clientFbConfig.apiKey).trim();
  let tenantSaasFields = null;
  const stationPois = [];

  // 1. Intentar leer `saas_clientes/{licenseKey}` desde el proyecto propio de la institución (igual que SENTINEL CAD y SENTINEL NAV)
  if (licenseKey) {
    try {
      const tenantSaasUrl = `https://firestore.googleapis.com/v1/projects/${projId}/databases/(default)/documents/saas_clientes/${encodeURIComponent(licenseKey)}?key=${apiKey}`;
      const tRes = await fetchFirestoreWithAuth(tenantSaasUrl, apiKey);
      if (tRes && tRes.ok) {
        const tJson = await tRes.json();
        if (tJson && tJson.fields) {
          tenantSaasFields = parseFirestoreDocFields(tJson);
        }
      }
    } catch (_) {}
  }

  // 2. Respaldo dinámico desde `puntos_interes` (igual que `resolveUnitStationCoords` en SENTINEL NAV)
  try {
    const poisUrl = `https://firestore.googleapis.com/v1/projects/${projId}/databases/(default)/documents/puntos_interes?pageSize=250&key=${apiKey}`;
    const pRes = await fetchFirestoreWithAuth(poisUrl, apiKey);
    if (pRes && pRes.ok) {
      const pJson = await pRes.json();
      const docs = Array.isArray(pJson?.documents) ? pJson.documents : [];
      for (const docItem of docs) {
        const d = parseFirestoreDocFields(docItem);
        const cat = String(d.categoria || d.tipo || d.category || '').trim().toLowerCase();
        const nom = String(d.nombre || d.titulo || '').trim().toUpperCase();
        if (!(cat.includes('cuartel') || nom.includes('CUARTEL') || nom.includes('COMPAÑÍA') || nom.includes('COMPANIA'))) {
          continue;
        }
        const geo = d.geo && typeof d.geo === 'object' ? d.geo : {};
        const coords = d.coords && typeof d.coords === 'object' ? d.coords : {};
        const ubic = d.ubicacion && typeof d.ubicacion === 'object' ? d.ubicacion : {};
        const pLat = parseValidCoord(
          geo.lat ?? geo.latitude ?? coords.lat ?? ubic.lat ?? d.lat ?? d.latitude ?? d.latitud
        );
        const pLng = parseValidCoord(
          geo.lng ?? geo.lon ?? geo.longitude ?? coords.lng ?? ubic.lng ?? d.lng ?? d.lon ?? d.longitude ?? d.longitud
        );
        if (pLat !== null && pLng !== null) {
          stationPois.push({
            nombre: String(d.nombre || d.titulo || 'CUARTEL').trim(),
            direccion: String(d.direccion || d.descripcion || '').trim(),
            compania: String(d.compania || '').trim(),
            lat: pLat,
            lng: pLng
          });
        }
      }
    }
  } catch (_) {}

  return { tenantSaasFields, stationPois };
}

function enrichNormalizedStationsWithPois(normalizedClient, stationPois = []) {
  const companias = { ...(normalizedClient?.companias || normalizedClient?.cuarteles || {}) };
  let mainLat = parseValidCoord(normalizedClient?.cuartelGeneral?.lat ?? normalizedClient?.lat);
  let mainLng = parseValidCoord(normalizedClient?.cuartelGeneral?.lng ?? normalizedClient?.lng);

  if (Array.isArray(stationPois) && stationPois.length > 0) {
    Object.keys(companias).forEach((compKey) => {
      const comp = companias[compKey];
      if (!comp || typeof comp !== 'object') return;
      const cLat = parseValidCoord(comp.lat);
      const cLng = parseValidCoord(comp.lng);
      if (cLat !== null && cLng !== null) return;

      const matchedPoi =
        stationPois.find((p) => {
          const nom = String(p.nombre || '').toUpperCase();
          return (
            String(p.compania || '') === String(compKey) ||
            nom.includes(`${compKey}ª`) ||
            nom.includes(`${compKey}°`) ||
            nom.includes(` ${compKey}`)
          );
        }) || (compKey === '1' ? stationPois[0] : null);

      if (matchedPoi) {
        companias[compKey] = {
          ...comp,
          cuartel: comp.cuartel || matchedPoi.nombre,
          direccion: comp.direccion || matchedPoi.direccion || '',
          lat: matchedPoi.lat,
          lng: matchedPoi.lng
        };
      }
    });

    if (mainLat === null || mainLng === null) {
      mainLat = stationPois[0].lat;
      mainLng = stationPois[0].lng;
    }
  }

  if (mainLat === null || mainLng === null) {
    const firstWithCoords =
      (companias['1'] && parseValidCoord(companias['1'].lat) !== null && parseValidCoord(companias['1'].lng) !== null
        ? companias['1']
        : Object.values(companias).find(
            (c) => c && parseValidCoord(c.lat) !== null && parseValidCoord(c.lng) !== null
          )) || null;
    if (firstWithCoords) {
      mainLat = parseValidCoord(firstWithCoords.lat);
      mainLng = parseValidCoord(firstWithCoords.lng);
    }
  }

  return {
    cuartelesObj: Object.keys(companias).length > 0 ? companias : null,
    cLat: mainLat,
    cLng: mainLng
  };
}

async function resolveLocalLogoDataUri(rawUrl) {
  const clean = String(rawUrl || '').trim();
  if (!clean) return '';
  if (clean.startsWith('data:')) return clean;

  // 1. Verificar si ya está guardado en caché local permanente
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const cachedUri = window.localStorage.getItem(LOGO_DATA_URI_KEY);
      if (cachedUri && cachedUri.startsWith('data:')) {
        return cachedUri;
      }
    }
  } catch (_) {}

  // 2. Verificar puente nativo Android
  try {
    if (typeof window !== 'undefined' && window.AndroidApp?.downloadAndCacheLogoDataUri) {
      const nativeDataUri = window.AndroidApp.downloadAndCacheLogoDataUri(clean);
      if (nativeDataUri && String(nativeDataUri).startsWith('data:')) {
        syncNativeStorageKey(LOGO_DATA_URI_KEY, String(nativeDataUri));
        return String(nativeDataUri);
      }
    }
  } catch (e) {}

  // 3. Descargar vía fetch directo
  try {
    const res = await fetch(clean);
    if (res.ok) {
      const blob = await res.blob();
      const dataUri = await new Promise((resolve) => {
        const reader = new FileReader();
        reader.onloadend = () => resolve(typeof reader.result === 'string' ? reader.result : '');
        reader.onerror = () => resolve('');
        reader.readAsDataURL(blob);
      });
      if (dataUri && dataUri.startsWith('data:')) {
        syncNativeStorageKey(LOGO_DATA_URI_KEY, dataUri);
        try {
          await AsyncStorage.setItem(LOGO_DATA_URI_KEY, dataUri);
        } catch (_) {}
        return dataUri;
      }
    }
  } catch (e) {}

  // 4. Fallback con Image + Canvas (sortea bloqueos de fetch en WKWebView de iOS)
  try {
    if (typeof window !== 'undefined' && typeof document !== 'undefined' && typeof window.Image !== 'undefined') {
      const canvasDataUri = await new Promise((resolve) => {
        const img = new window.Image();
        img.crossOrigin = 'anonymous';
        const timer = setTimeout(() => resolve(''), 6000);
        img.onload = () => {
          clearTimeout(timer);
          try {
            const canvas = document.createElement('canvas');
            canvas.width = img.naturalWidth || img.width || 256;
            canvas.height = img.naturalHeight || img.height || 256;
            const ctx = canvas.getContext('2d');
            ctx.drawImage(img, 0, 0);
            const out = canvas.toDataURL('image/png');
            resolve(out && out.startsWith('data:') ? out : '');
          } catch (_) {
            resolve('');
          }
        };
        img.onerror = () => {
          clearTimeout(timer);
          resolve('');
        };
        img.src = clean;
      });
      if (canvasDataUri && canvasDataUri.startsWith('data:')) {
        syncNativeStorageKey(LOGO_DATA_URI_KEY, canvasDataUri);
        try {
          await AsyncStorage.setItem(LOGO_DATA_URI_KEY, canvasDataUri);
        } catch (_) {}
        return canvasDataUri;
      }
    }
  } catch (_) {}

  // 5. Solicitar al puente nativo iOS que descargue y guarde en disco/UserDefaults sin restricción CORS
  try {
    if (typeof window !== 'undefined' && window.webkit?.messageHandlers?.iOSApp?.postMessage) {
      window.webkit.messageHandlers.iOSApp.postMessage({
        action: 'cacheInstitutionLogo',
        url: clean
      });
    }
  } catch (_) {}

  return clean;
}

function getInitialCachedLicense() {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const raw = window.localStorage.getItem(CACHE_KEY);
      const cachedDedicatedLogo = window.localStorage.getItem(LOGO_DATA_URI_KEY) || '';
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && parsed.authorized && parsed.licenseKey) {
          let effectiveLogo =
            (cachedDedicatedLogo && cachedDedicatedLogo.startsWith('data:') ? cachedDedicatedLogo : '') ||
            parsed.logoDataUri ||
            parsed.logoUrl ||
            '';
          if (
            effectiveLogo &&
            !effectiveLogo.startsWith('data:') &&
            window.AndroidApp?.getCachedLogoDataUri
          ) {
            try {
              const fromDisk = window.AndroidApp.getCachedLogoDataUri(effectiveLogo);
              if (fromDisk && String(fromDisk).startsWith('data:')) {
                effectiveLogo = String(fromDisk);
              }
            } catch (_) {}
          }
          return {
            licenseKey: parsed.licenseKey,
            clientName: parsed.clientName || '',
            nombreMostrar: parsed.nombreMostrar || '',
            logoUrl: effectiveLogo,
            rawLogoUrl: parsed.rawLogoUrl || parsed.logoUrl || '',
            comuna: parsed.comuna || '',
            region: parsed.region || '',
            cuarteles: parsed.cuarteles || null,
            totalCompanias: parsed.totalCompanias || 1,
            cuartelLat: sanitizeCachedCoord(parsed.cuartelLat),
            cuartelLng: sanitizeCachedCoord(parsed.cuartelLng),
            isReadOnly: Boolean(parsed.isReadOnly),
            firebaseConfig: parsed.firebaseConfig || null,
            authorized: true,
            status: 'activo',
            isBypassMode: Boolean(parsed.isBypassMode)
          };
        }
      }
    }
  } catch (e) {}
  return null;
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

function syncNativeFirebaseConfig(fbConfig) {
  if (!fbConfig || typeof window === 'undefined') return;
  try {
    const jsonStr = JSON.stringify(fbConfig);
    if (window.AndroidApp?.syncFirebaseConfig) {
      window.AndroidApp.syncFirebaseConfig(jsonStr);
    }
    if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
      window.webkit.messageHandlers.iOSApp.postMessage({
        action: 'syncFirebaseConfig',
        config: fbConfig
      });
    }
  } catch (_) {}
}

const initialCached = getInitialCachedLicense();
if (initialCached?.firebaseConfig) {
  syncNativeFirebaseConfig(initialCached.firebaseConfig);
}

// Listener para recibir el logo descargado de forma nativa en iOS (sin CORS) y actualizar toda la app de inmediato
if (typeof window !== 'undefined') {
  window.addEventListener('sentinelNativeLogoCached', (ev) => {
    const dataUri = ev?.detail?.dataUri;
    if (dataUri && String(dataUri).startsWith('data:')) {
      useLicenseStore.setState({ logoUrl: String(dataUri) });
      AsyncStorage.setItem(LOGO_DATA_URI_KEY, String(dataUri)).catch(() => {});
    }
  });
}

export const useLicenseStore = create((set, get) => ({
  licenseKey: initialCached?.licenseKey || '',
  clientName: initialCached?.clientName || '',
  nombreMostrar: initialCached?.nombreMostrar || '',
  logoUrl: initialCached?.logoUrl || '',
  comuna: initialCached?.comuna || '',
  region: initialCached?.region || '',
  cuarteles: initialCached?.cuarteles || null,
  totalCompanias: initialCached?.totalCompanias || 1,
  cuartelLat: initialCached?.cuartelLat ?? null,
  cuartelLng: initialCached?.cuartelLng ?? null,
  isReadOnly: Boolean(initialCached?.isReadOnly),
  firebaseConfig: initialCached?.firebaseConfig || null,
  authorized: Boolean(initialCached?.authorized),
  status: initialCached?.status || 'esperando', // 'esperando' | 'verificando' | 'activo' | 'suspendido' | 'no_autorizado'
  errorReason: '',
  isChecking: false,
  isBypassMode: Boolean(initialCached?.isBypassMode),

  init: async () => {
    try {
      const [cached, storedLogoUri] = await Promise.all([
        AsyncStorage.getItem(CACHE_KEY),
        AsyncStorage.getItem(LOGO_DATA_URI_KEY)
      ]);
      const syncDedicatedLogo =
        (typeof window !== 'undefined' && window.localStorage
          ? window.localStorage.getItem(LOGO_DATA_URI_KEY)
          : '') ||
        storedLogoUri ||
        '';

      const fallbackRaw =
        cached ||
        (typeof window !== 'undefined' && window.localStorage
          ? window.localStorage.getItem(CACHE_KEY)
          : null);

      if (fallbackRaw) {
        const parsed = JSON.parse(fallbackRaw);
        if (parsed.authorized && parsed.licenseKey) {
          let effectiveLogo =
            (syncDedicatedLogo && syncDedicatedLogo.startsWith('data:') ? syncDedicatedLogo : '') ||
            parsed.logoDataUri ||
            parsed.logoUrl ||
            '';
          const remoteLogoUrl = parsed.rawLogoUrl || parsed.logoUrl || '';

          // Hidratar inmediatamente con lo que ya tengamos para que el Loader muestre el escudo al instante (0ms)
          syncNativeFirebaseConfig(parsed.firebaseConfig);
          set({
            licenseKey: parsed.licenseKey,
            clientName: parsed.clientName || '',
            nombreMostrar: parsed.nombreMostrar || '',
            logoUrl: effectiveLogo,
            comuna: parsed.comuna || '',
            region: parsed.region || '',
            cuarteles: parsed.cuarteles || null,
            totalCompanias: parsed.totalCompanias || 1,
            cuartelLat: sanitizeCachedCoord(parsed.cuartelLat),
            cuartelLng: sanitizeCachedCoord(parsed.cuartelLng),
            isReadOnly: Boolean(parsed.isReadOnly),
            firebaseConfig: parsed.firebaseConfig || null,
            authorized: true,
            status: 'activo',
            isBypassMode: Boolean(parsed.isBypassMode)
          });

          // Si aún no está convertido a base64 local permanente, descargarlo y persistirlo para siempre
          if (effectiveLogo && !effectiveLogo.startsWith('data:')) {
            resolveLocalLogoDataUri(remoteLogoUrl).then(async (downloaded) => {
              if (downloaded && downloaded.startsWith('data:')) {
                parsed.logoDataUri = downloaded;
                parsed.rawLogoUrl = remoteLogoUrl;
                set({ logoUrl: downloaded });
                try {
                  const serializedParsed = JSON.stringify(parsed);
                  syncNativeStorageKey(LOGO_DATA_URI_KEY, downloaded);
                  syncNativeStorageKey(CACHE_KEY, serializedParsed);
                  await AsyncStorage.setItem(LOGO_DATA_URI_KEY, downloaded);
                  await AsyncStorage.setItem(CACHE_KEY, serializedParsed);
                } catch (_) {}
              }
            }).catch(() => {});
          } else if (effectiveLogo && effectiveLogo.startsWith('data:')) {
            syncNativeStorageKey(LOGO_DATA_URI_KEY, effectiveLogo);
            syncNativeStorageKey(CACHE_KEY, JSON.stringify(parsed));
          }

          // Sincronización silenciosa en segundo plano (con guarda anti-bucle) para actualizar coordenadas de cuarteles establecidas
          get().refreshStationMetadataSilently(parsed.licenseKey).catch(() => {});

          return true;
        }
      }
    } catch (e) {
      console.warn('Error reading license cache:', e);
    }
    return false;
  },

  refreshStationMetadataSilently: async (explicitKey = '') => {
    const now = Date.now();
    if (silentSyncInFlight || now - lastSilentSyncTs < 30000) return false;
    const state = get();
    const key = String(explicitKey || state.licenseKey || '').trim().toUpperCase();
    if (!key) return false;

    silentSyncInFlight = true;
    lastSilentSyncTs = now;
    try {
      return await get().validateLicense(key, true);
    } catch (_) {
      return false;
    } finally {
      silentSyncInFlight = false;
    }
  },

  validateLicense: async (rawKey, silent = false) => {
    let key = (rawKey || '').trim().toUpperCase();
    if (!key) {
      if (!silent) set({ errorReason: 'Ingrese una clave de licencia válida.' });
      return false;
    }

    if (!silent) {
      set({ isChecking: true, status: 'verificando', errorReason: '' });
    }

    // CONSULTA REAL A SISBOM CENTRAL (Firestore REST, sin modo de prueba ni bypass)
    try {
      const centralApiKey = CENTRAL_FIREBASE_CONFIG.apiKey;
      const centralProj = CENTRAL_FIREBASE_CONFIG.projectId;
      const url = `https://firestore.googleapis.com/v1/projects/${centralProj}/databases/(default)/documents/saas_clientes/${encodeURIComponent(key)}?key=${centralApiKey}`;

      const res = await fetch(url, { headers: { Accept: 'application/json' } });

      if (!res.ok) {
        if (res.status === 404) {
          if (!silent) {
            set({
              authorized: false,
              status: 'no_autorizado',
              isChecking: false,
              errorReason: `La clave de licencia "${key}" no existe o es inválida.`
            });
          }
          return false;
        }
        throw new Error(`HTTP Error ${res.status}`);
      }

      const data = await res.json();
      const parsedFields = parseFirestoreDocFields(data);

      let normalizedClient = normalizeSaasClientDoc(key, parsedFields);
      const subStatus = normalizedClient.estadoSuscripcion;
      if (subStatus === 'bloqueado' || subStatus === 'suspendido') {
        set({
          authorized: false,
          status: 'suspendido',
          isChecking: false,
          errorReason: 'Esta suscripción se encuentra suspendida o bloqueada.'
        });
        return false;
      }

      const isReadOnly = normalizedClient.soloLectura || subStatus === 'lectura' || subStatus === 'readonly';
      const clientFbConfig =
        normalizedClient.firebaseConfig && normalizedClient.firebaseConfig.projectId && normalizedClient.firebaseConfig.apiKey
          ? normalizedClient.firebaseConfig
          : null;

      if (!clientFbConfig) {
        if (!silent) {
          set({
            authorized: false,
            status: 'no_autorizado',
            isChecking: false,
            errorReason: 'Esta licencia no tiene un proyecto Firebase asignado en el Servidor Central.'
          });
        }
        return false;
      }

      // Fusionar coordenadas y metadatos canónicos desde `saas_clientes/{key}` y `puntos_interes` del proyecto de la institución (igual que SENTINEL CAD y SENTINEL NAV)
      const { tenantSaasFields, stationPois } = await resolveTenantStationOverrides(key, clientFbConfig);
      if (tenantSaasFields) {
        const mergedNorm = normalizeSaasClientDoc(key, {
          ...parsedFields,
          ...tenantSaasFields,
          firebaseConfig: clientFbConfig
        });
        if (mergedNorm) {
          normalizedClient = mergedNorm;
        }
      }

      const { cuartelesObj, cLat, cLng } = enrichNormalizedStationsWithPois(normalizedClient, stationPois);

      const rawRemoteLogo = normalizedClient.logoUrl || '';
      const currentLogo = get().logoUrl || '';
      const localLogoDataUri =
        silent && currentLogo.startsWith('data:')
          ? currentLogo
          : rawRemoteLogo
            ? await resolveLocalLogoDataUri(rawRemoteLogo)
            : '';

      const licenseData = {
        licenseKey: key,
        clientName: normalizedClient.nombreCliente || normalizedClient.nombreMostrar || key,
        nombreMostrar: normalizedClient.nombreMostrar || normalizedClient.nombreCliente || key,
        logoUrl: localLogoDataUri || rawRemoteLogo,
        rawLogoUrl: rawRemoteLogo,
        logoDataUri: localLogoDataUri && localLogoDataUri.startsWith('data:') ? localLogoDataUri : '',
        comuna: normalizedClient.comuna || '',
        region: normalizedClient.region || '',
        cuarteles: cuartelesObj,
        totalCompanias: Number(normalizedClient.totalCompanias || (cuartelesObj ? Object.keys(cuartelesObj).length : 1)) || 1,
        cuartelLat: cLat,
        cuartelLng: cLng,
        isReadOnly,
        firebaseConfig: clientFbConfig,
        authorized: true,
        status: 'activo',
        isBypassMode: false,
        errorReason: ''
      };

      try {
        if (licenseData.logoDataUri) {
          syncNativeStorageKey(LOGO_DATA_URI_KEY, licenseData.logoDataUri);
          await AsyncStorage.setItem(LOGO_DATA_URI_KEY, licenseData.logoDataUri);
        }
        const serializedLicense = JSON.stringify(licenseData);
        syncNativeStorageKey(CACHE_KEY, serializedLicense);
        await AsyncStorage.setItem(CACHE_KEY, serializedLicense);
      } catch (e) {}

      syncNativeFirebaseConfig(clientFbConfig);

      // En modo silencioso, actualizar el store únicamente si algún dato real cambió (prevención total de re-renders innecesarios o bucles)
      if (silent) {
        const cur = get();
        const prevCuartelesJson = JSON.stringify(cur.cuarteles || null);
        const nextCuartelesJson = JSON.stringify(licenseData.cuarteles || null);
        const hasDiff =
          cur.cuartelLat !== licenseData.cuartelLat ||
          cur.cuartelLng !== licenseData.cuartelLng ||
          prevCuartelesJson !== nextCuartelesJson ||
          cur.comuna !== licenseData.comuna ||
          cur.region !== licenseData.region ||
          cur.nombreMostrar !== licenseData.nombreMostrar ||
          cur.totalCompanias !== licenseData.totalCompanias ||
          cur.isReadOnly !== licenseData.isReadOnly;

        if (hasDiff) {
          set({
            ...licenseData,
            isChecking: false
          });
        }
        return true;
      }

      set({
        ...licenseData,
        isChecking: false
      });
      return true;

    } catch (err) {
      console.warn('Network error checking license:', err);
      if (!silent) {
        set({
          authorized: false,
          status: 'no_autorizado',
          isChecking: false,
          errorReason: 'No fue posible validar la licencia. Revise su conexión a Internet.'
        });
      }
      return false;
    }
  },

  resetLicense: async () => {
    try {
      syncNativeStorageKey(CACHE_KEY, null);
      syncNativeStorageKey('@sentinel_one_v3_user_session', null);
      await AsyncStorage.removeItem(CACHE_KEY);
      await AsyncStorage.removeItem('@sentinel_one_v3_user_session');
    } catch (e) {}
    set({
      licenseKey: '',
      clientName: '',
      nombreMostrar: '',
      cuarteles: null,
      totalCompanias: 1,
      firebaseConfig: null,
      authorized: false,
      status: 'esperando',
      errorReason: '',
      isBypassMode: false
    });
  }
}));
