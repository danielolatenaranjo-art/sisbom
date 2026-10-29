import { create } from 'zustand';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { CENTRAL_FIREBASE_CONFIG, DEFAULT_FIREBASE_CONFIG, parseFirestoreValue } from '../config/firebase';
import { normalizeSaasClientDoc } from '../shared/sentinelCore';

const CACHE_KEY = '@sentinel_one_v3_license_cache';
const LOGO_DATA_URI_KEY = '@sentinel_one_v3_logo_data_uri';

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
            cuartelLat: parsed.cuartelLat ?? -34.637373,
            cuartelLng: parsed.cuartelLng ?? -71.125741,
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
  cuartelLat: initialCached?.cuartelLat ?? -34.637373,
  cuartelLng: initialCached?.cuartelLng ?? -71.125741,
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
            cuartelLat: parsed.cuartelLat ?? -34.637373,
            cuartelLng: parsed.cuartelLng ?? -71.125741,
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

          return true;
        }
      }
    } catch (e) {
      console.warn('Error reading license cache:', e);
    }
    return false;
  },

  validateLicense: async (rawKey) => {
    let key = (rawKey || '').trim().toUpperCase();
    if (!key) {
      set({ errorReason: 'Ingrese una clave de licencia válida.' });
      return false;
    }

    set({ isChecking: true, status: 'verificando', errorReason: '' });

    // CONSULTA REAL A SISBOM CENTRAL (Firestore REST, sin modo de prueba ni bypass)
    try {
      const centralApiKey = CENTRAL_FIREBASE_CONFIG.apiKey;
      const centralProj = CENTRAL_FIREBASE_CONFIG.projectId;
      const url = `https://firestore.googleapis.com/v1/projects/${centralProj}/databases/(default)/documents/saas_clientes/${key}?key=${centralApiKey}`;

      const res = await fetch(url, { headers: { Accept: 'application/json' } });

      if (!res.ok) {
        if (res.status === 404) {
          set({
            authorized: false,
            status: 'no_autorizado',
            isChecking: false,
            errorReason: `La clave de licencia "${key}" no existe o es inválida.`
          });
          return false;
        }
        throw new Error(`HTTP Error ${res.status}`);
      }

      const data = await res.json();
      const fields = data.fields || {};
      const parsedFields = {};
      for (const k of Object.keys(fields)) {
        parsedFields[k] = parseFirestoreValue(fields[k]);
      }

      const normalizedClient = normalizeSaasClientDoc(key, parsedFields);
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
        set({
          authorized: false,
          status: 'no_autorizado',
          isChecking: false,
          errorReason: 'Esta licencia no tiene un proyecto Firebase asignado en el Servidor Central.'
        });
        return false;
      }
      const cuartelesObj = normalizedClient.companias || normalizedClient.cuarteles || null;
      const cg = normalizedClient.cuartelGeneral || parsedFields.cuartelGeneral || {};
      const cLat = Number(cg.lat ?? parsedFields.latCuartel ?? -34.637373) || -34.637373;
      const cLng = Number(cg.lng ?? parsedFields.lngCuartel ?? -71.125741) || -71.125741;

      const rawRemoteLogo = normalizedClient.logoUrl || '';
      const localLogoDataUri = rawRemoteLogo ? await resolveLocalLogoDataUri(rawRemoteLogo) : '';

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

      set({
        ...licenseData,
        isChecking: false
      });
      return true;

    } catch (err) {
      console.warn('Network error checking license:', err);
      set({
        authorized: false,
        status: 'no_autorizado',
        isChecking: false,
        errorReason: 'No fue posible validar la licencia. Revise su conexión a Internet.'
      });
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
