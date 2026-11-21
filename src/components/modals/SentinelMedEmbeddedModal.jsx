import React, { useEffect, useRef, useState } from 'react';
import { Platform } from 'react-native';
import { useAuthStore } from '../../store/useAuthStore';
import { useLicenseStore } from '../../store/useLicenseStore';
import { useDispatchStore } from '../../store/useDispatchStore';
import {
  isCanonicalCommander,
  hasMedicalStaffFlag,
  hasAdminModeFlag,
  formatShortPersonName
} from '../../shared/sentinelCore';

function syncMedSessionBridge(activeUser) {
  if (typeof window === 'undefined' || !window.localStorage) return;
  try {
    const lic = useLicenseStore.getState();
    const fbConfig = lic.firebaseConfig || null;
    const key = String(lic.licenseKey || '').trim().toUpperCase();
    if (!key || !fbConfig) return;

    const fullName = String(
      activeUser?.nombreBombero || activeUser?.nombreCompleto || activeUser?.nombre || 'OFICIAL SANIDAD'
    )
      .trim()
      .toUpperCase();
    const idReg = String(activeUser?.idRegistro || activeUser?.id || '').trim();
    const idRad = String(activeUser?.idRadial || '1').trim();

    const licensePayload = {
      authorized: true,
      commanderVerified: true,
      commanderInfo: {
        idRegistro: idReg,
        idRadial: idRad,
        nombreBombero: fullName,
        boundAt: Date.now()
      },
      licenseState: 'VALID',
      status: 'activo',
      isBypassMode: false,
      isReadOnly: Boolean(lic.isReadOnly),
      licenseKey: key,
      licenseId: key,
      clientName: lic.clientName || '',
      institutionName: lic.clientName || '',
      nombreMostrar: lic.nombreMostrar || lic.clientName || '',
      logoUrl: lic.logoUrl || '',
      comuna: lic.comuna || '',
      region: lic.region || '',
      totalCompanias: lic.totalCompanias || 1,
      companias: lic.cuarteles || {},
      cuarteles: lic.cuarteles || {},
      firebaseConfig: fbConfig,
      cachedAt: Date.now()
    };

    window.localStorage.setItem('sentinel_med_v3_license_cache', JSON.stringify(licensePayload));
    window.localStorage.setItem('saas_license_key', key);
    window.localStorage.setItem('saas_firebase_config', JSON.stringify(fbConfig));
    window.localStorage.setItem('firebaseConfig', JSON.stringify(fbConfig));

    if (lic.logoUrl && String(lic.logoUrl).startsWith('data:')) {
      window.localStorage.setItem('sentinel_med_v3_logo_data_uri', String(lic.logoUrl));
      if (lic.rawLogoUrl) {
        window.localStorage.setItem('sentinel_med_v3_logo_data_uri_src', String(lic.rawLogoUrl));
      }
    }

    const sanitizedUser = {
      ...activeUser,
      id: idReg,
      idRegistro: idReg,
      idRadial: idRad,
      nombre: fullName,
      nombreCorto: formatShortPersonName(fullName),
      nombreBombero: fullName,
      nombreCompleto: fullName,
      cargo: activeUser?.cargo || 'OFICIAL SANIDAD',
      compania: activeUser?.compania ?? 1,
      personalMedico: hasMedicalStaffFlag(activeUser),
      modoAdmin: hasAdminModeFlag(activeUser),
      esComandante: isCanonicalCommander(activeUser)
    };
    delete sanitizedUser.contrasena;
    delete sanitizedUser.password;

    window.localStorage.setItem('sentinel_med_v3_auth_user', JSON.stringify(sanitizedUser));
  } catch (_) {}
}

function resolveHostSafeInsets() {
  if (typeof window === 'undefined') return { top: 0, bottom: 0 };
  const nativeInsets = window.__SENTINEL_SAFE_INSETS__;
  if (nativeInsets && typeof nativeInsets === 'object') {
    return {
      top: Number(nativeInsets.top) || 0,
      bottom: Number(nativeInsets.bottom) || 0
    };
  }
  const isIos = Boolean(
    window.webkit?.messageHandlers?.iOSApp || window.__SENTINEL_PLATFORM__ === 'ios'
  );
  if (isIos) {
    return { top: 47, bottom: 20 };
  }
  return { top: 38, bottom: 16 };
}

export const SentinelMedEmbeddedModal = ({ visible, onClose }) => {
  const { user } = useAuthStore();
  const { licenseKey } = useLicenseStore();
  const { firefightersList } = useDispatchStore();

  const iframeRef = useRef(null);
  const [hasMountedOnce, setHasMountedOnce] = useState(false);

  const sessionSignature = `${String(licenseKey || '').trim().toUpperCase()}:${String(
    user?.idRegistro || user?.id || ''
  ).trim()}`;

  const pushInsetsAndStateToIframe = (activeFlag) => {
    try {
      const cw = iframeRef.current?.contentWindow;
      const cd = iframeRef.current?.contentDocument;
      const insets = resolveHostSafeInsets();
      if (cd?.documentElement) {
        cd.documentElement.style.setProperty('--sentinel-safe-top', `${insets.top}px`);
        cd.documentElement.style.setProperty('--sentinel-safe-bottom', `${insets.bottom}px`);
      }
      if (cw) {
        cw.postMessage({ type: 'SENTINEL_SAFE_INSETS', ...insets }, '*');
        cw.postMessage({ type: 'SENTINEL_EMBEDDED_ACTIVE', active: Boolean(activeFlag) }, '*');
      }
    } catch (_) {}
  };

  useEffect(() => {
    if (visible) {
      const myIdReg = String(user?.idRegistro || '').trim();
      const liveUser =
        (myIdReg &&
          Array.isArray(firefightersList) &&
          firefightersList.find((p) => String(p.idRegistro).trim() === myIdReg)) ||
        user;

      syncMedSessionBridge(liveUser || user);
      if (!hasMountedOnce) {
        setHasMountedOnce(true);
      } else {
        pushInsetsAndStateToIframe(true);
      }
    } else if (hasMountedOnce) {
      pushInsetsAndStateToIframe(false);
    }
  }, [visible, hasMountedOnce, sessionSignature]);

  useEffect(() => {
    if (!visible || typeof window === 'undefined') return undefined;

    window.__SENTINEL_CLOSE_EMBEDDED_MED__ = () => {
      if (onClose) onClose();
    };

    const onMsg = (ev) => {
      if (ev?.data?.type === 'SENTINEL_CLOSE_EMBEDDED_MED') {
        if (onClose) onClose();
      }
    };

    const onNativeBack = (ev) => {
      if (ev?.stopImmediatePropagation) ev.stopImmediatePropagation();
      if (onClose) onClose();
    };

    window.addEventListener('message', onMsg);
    window.addEventListener('sentinelBackButton', onNativeBack, true);

    return () => {
      delete window.__SENTINEL_CLOSE_EMBEDDED_MED__;
      window.removeEventListener('message', onMsg);
      window.removeEventListener('sentinelBackButton', onNativeBack, true);
    };
  }, [visible, onClose]);

  if (!hasMountedOnce || Platform.OS !== 'web') return null;

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        width: '100%',
        height: '100%',
        zIndex: 9999,
        backgroundColor: '#030712',
        opacity: visible ? 1 : 0,
        visibility: visible ? 'visible' : 'hidden',
        pointerEvents: visible ? 'auto' : 'none',
        transition: 'opacity 160ms ease-out',
        overflow: 'hidden'
      }}
    >
      <iframe
        key={sessionSignature}
        ref={iframeRef}
        src="./med/index.html?embedded=1"
        title="SENTINEL MED"
        onLoad={() => pushInsetsAndStateToIframe(visible)}
        style={{
          width: '100%',
          height: '100%',
          border: 'none',
          margin: 0,
          padding: 0,
          backgroundColor: '#030712',
          display: 'block'
        }}
        allow="geolocation; clipboard-read; clipboard-write"
      />
    </div>
  );
};
