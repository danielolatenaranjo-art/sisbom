import { create } from 'zustand';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { CENTRAL_FIREBASE_CONFIG, DEFAULT_FIREBASE_CONFIG, parseFirestoreValue } from '../config/firebase';

const CACHE_KEY = '@sentinel_one_v3_license_cache';

async function resolveLocalLogoDataUri(rawUrl) {
  const clean = String(rawUrl || '').trim();
  if (!clean) return '';
  if (clean.startsWith('data:')) return clean;
  try {
    if (typeof window !== 'undefined' && window.AndroidApp?.downloadAndCacheLogoDataUri) {
      const nativeDataUri = window.AndroidApp.downloadAndCacheLogoDataUri(clean);
      if (nativeDataUri && String(nativeDataUri).startsWith('data:')) {
        return String(nativeDataUri);
      }
    }
  } catch (e) {}
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
        return dataUri;
      }
    }
  } catch (e) {}
  return clean;
}

function getInitialCachedLicense() {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const raw = window.localStorage.getItem(CACHE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && parsed.authorized && parsed.licenseKey) {
          let effectiveLogo = parsed.logoDataUri || parsed.logoUrl || '';
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
      const cached = await AsyncStorage.getItem(CACHE_KEY);
      if (cached) {
        const parsed = JSON.parse(cached);
        if (parsed.authorized && parsed.licenseKey) {
          let effectiveLogo = parsed.logoDataUri || parsed.logoUrl || '';
          const remoteLogoUrl = parsed.rawLogoUrl || parsed.logoUrl || '';

          if (effectiveLogo && !effectiveLogo.startsWith('data:')) {
            const downloaded = await resolveLocalLogoDataUri(remoteLogoUrl);
            if (downloaded && downloaded.startsWith('data:')) {
              effectiveLogo = downloaded;
              parsed.logoDataUri = downloaded;
              parsed.rawLogoUrl = remoteLogoUrl;
              try {
                await AsyncStorage.setItem(CACHE_KEY, JSON.stringify(parsed));
              } catch (_) {}
            }
          }

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

      const subStatus = String(parsedFields.estadoSuscripcion || parsedFields.estado || 'activo').toLowerCase();
      if (subStatus === 'bloqueado' || subStatus === 'suspendido') {
        set({
          authorized: false,
          status: 'suspendido',
          isChecking: false,
          errorReason: 'Esta suscripción se encuentra suspendida o bloqueada.'
        });
        return false;
      }

      const isReadOnly = subStatus === 'lectura' || subStatus === 'readonly' || parsedFields.soloLectura === true;
      const clientFbConfig = parsedFields.firebaseConfig || DEFAULT_FIREBASE_CONFIG;
      const cuartelesObj = parsedFields.companias || parsedFields.cuarteles || null;
      const cg = parsedFields.cuartelGeneral || {};
      const cLat = Number(cg.lat ?? parsedFields.latCuartel ?? -34.637373) || -34.637373;
      const cLng = Number(cg.lng ?? parsedFields.lngCuartel ?? -71.125741) || -71.125741;

      const rawRemoteLogo = parsedFields.logoUrl || '';
      const localLogoDataUri = rawRemoteLogo ? await resolveLocalLogoDataUri(rawRemoteLogo) : '';

      const licenseData = {
        licenseKey: key,
        clientName: parsedFields.nombreCliente || parsedFields.nombreMostrar || key,
        nombreMostrar: parsedFields.nombreMostrar || parsedFields.nombreCliente || key,
        logoUrl: localLogoDataUri || rawRemoteLogo,
        rawLogoUrl: rawRemoteLogo,
        logoDataUri: localLogoDataUri && localLogoDataUri.startsWith('data:') ? localLogoDataUri : '',
        comuna: parsedFields.comuna || '',
        region: parsedFields.region || '',
        cuarteles: cuartelesObj,
        totalCompanias: Number(parsedFields.totalCompanias || (cuartelesObj ? Object.keys(cuartelesObj).length : 1)) || 1,
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
        await AsyncStorage.setItem(CACHE_KEY, JSON.stringify(licenseData));
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
