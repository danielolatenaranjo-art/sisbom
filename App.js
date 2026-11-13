import React, { useEffect, useState, useRef } from 'react';
import { StatusBar } from 'expo-status-bar';
import {
  StyleSheet,
  View,
  BackHandler,
  Image,
  ActivityIndicator,
  Animated,
  Easing
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

import { SentinelBackground } from './src/components/common/SentinelBackground';
import { LicenseActivationView } from './src/views/LicenseActivationView';
import { LoginView } from './src/views/LoginView';
import { MainHomeView } from './src/views/MainHomeView';
import {
  LocationDisclosureModal,
  readNativePermissionsStatus
} from './src/components/modals/LocationDisclosureModal';

import { useLicenseStore } from './src/store/useLicenseStore';
import { useAuthStore } from './src/store/useAuthStore';
import { useDispatchStore } from './src/store/useDispatchStore';

const DISCLOSURE_KEY = '@sentinel_one_location_disclosed_v3';

function persistDisclosureAccepted() {
  try {
    if (typeof window !== 'undefined') {
      if (window.localStorage) {
        window.localStorage.setItem(DISCLOSURE_KEY, 'true');
      }
      if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
        window.webkit.messageHandlers.iOSApp.postMessage({
          action: 'syncStorageCache',
          key: DISCLOSURE_KEY,
          value: 'true'
        });
      }
    }
  } catch (_) {}
  AsyncStorage.setItem(DISCLOSURE_KEY, 'true').catch(() => {});
}

export default function App() {
  const { authorized, logoUrl, init: initLicense } = useLicenseStore();
  const { isAuthenticated, isDarkMode, init: initAuth, logout } = useAuthStore();
  const { init: initDispatch } = useDispatchStore();

  const [locationModalVisible, setLocationModalVisible] = useState(false);
  const [bgStage, setBgStage] = useState('login');
  const [isBooting, setIsBooting] = useState(true);
  const bootFadeAnim = useRef(new Animated.Value(1)).current;

  // Inicialización de la aplicación con Loader institucional (cenizas + logo del Cuerpo de Bomberos + circulito)
  useEffect(() => {
    let isMounted = true;
    const startup = async () => {
      const startTime = Date.now();
      const licOk = await initLicense();
      const authOk = await initAuth();
      setBgStage('login');

      if (licOk && authOk) {
        initDispatch(false);
      }

      // Dar tiempo para que la sesión y los listeners de Firebase conecten en segundo plano sin mostrar la pantalla de licencia ni estados desconectados
      const elapsed = Date.now() - startTime;
      const minBootDuration = licOk && authOk ? 1150 : 650;
      const remaining = Math.max(0, minBootDuration - elapsed);

      setTimeout(() => {
        if (!isMounted) return;
        Animated.timing(bootFadeAnim, {
          toValue: 0,
          duration: 280,
          easing: Easing.out(Easing.cubic),
          useNativeDriver: true
        }).start(() => {
          if (isMounted) {
            setIsBooting(false);
          }
        });
      }, remaining);
    };
    startup();
    return () => {
      isMounted = false;
    };
  }, []);

  // ÚNICAMENTE después de iniciar sesión y finalizar el arranque: verificar permisos operativos
  useEffect(() => {
    if (!authorized || !isAuthenticated) {
      setLocationModalVisible(false);
      setBgStage((prev) => (prev === 'wind-gust' || prev === 'logout-emerge-login' ? prev : 'login'));
      return;
    }

    initDispatch(false);

    if (isBooting) return;

    let cancelled = false;
    const isIosRuntime =
      typeof window !== 'undefined' &&
      Boolean(window.webkit?.messageHandlers?.iOSApp || window.__SENTINEL_PLATFORM__ === 'ios');

    const isPermsSatisfied = (permsObj) => {
      if (!permsObj) return false;
      if (isIosRuntime) {
        return Boolean(
          permsObj.notifications && (permsObj.backgroundLocation || permsObj.fineLocation)
        );
      }
      return Boolean(
        permsObj.notifications &&
          permsObj.backgroundLocation &&
          permsObj.overlay &&
          permsObj.batteryUnrestricted
      );
    };

    const evaluatePermissions = async () => {
      const currentPerms = readNativePermissionsStatus();
      const allGranted = isPermsSatisfied(currentPerms);
      const localDisclosed =
        (typeof window !== 'undefined' && window.localStorage?.getItem(DISCLOSURE_KEY)) ||
        (await AsyncStorage.getItem(DISCLOSURE_KEY).catch(() => null));

      if (isIosRuntime) {
        const iosNeedsPrompt = Boolean(currentPerms.notifNotDetermined || currentPerms.locNotDetermined);
        if (!iosNeedsPrompt || allGranted) {
          persistDisclosureAccepted();
          if (!cancelled) setLocationModalVisible(false);
          return;
        }
        if (!localDisclosed) {
          if (!cancelled) setLocationModalVisible(true);
        }
        return;
      }

      if (allGranted) {
        persistDisclosureAccepted();
        if (!cancelled) setLocationModalVisible(false);
        return;
      }

      if (!localDisclosed) {
        if (!cancelled) setLocationModalVisible(true);
      }
    };

    const timer = setTimeout(evaluatePermissions, 350);

    const onPermsChanged = () => {
      const updated = readNativePermissionsStatus();
      const done = isIosRuntime
        ? !updated.notifNotDetermined && !updated.locNotDetermined
        : isPermsSatisfied(updated);
      if (done || isPermsSatisfied(updated)) {
        persistDisclosureAccepted();
        setLocationModalVisible(false);
      }
    };

    if (typeof window !== 'undefined') {
      window.addEventListener('sentinelPermissionsChanged', onPermsChanged);
    }

    return () => {
      cancelled = true;
      clearTimeout(timer);
      if (typeof window !== 'undefined') {
        window.removeEventListener('sentinelPermissionsChanged', onPermsChanged);
      }
    };
  }, [authorized, isAuthenticated, isBooting]);

  const triggerWindBurstToLogin = () => {
    setBgStage('wind-gust');
    setTimeout(() => {
      setBgStage('login');
    }, 650);
  };

  const triggerWindBurstToMain = () => {
    setBgStage('wind-gust');
    setTimeout(() => setBgStage('login'), 650);
  };

  const handleAcceptLocation = async () => {
    setLocationModalVisible(false);
    persistDisclosureAccepted();
  };

  const handleDeclineLocation = async () => {
    // Nunca cerrar forzosamente la app (cumplimiento política Broken Functionality Google Play / Apple):
    // registrar que el usuario vio el aviso y permitir continuar operando en la pantalla principal.
    setLocationModalVisible(false);
    persistDisclosureAccepted();
  };

  const handleFullLogout = async () => {
    useDispatchStore.getState().unsubscribeRealtime();
    setBgStage('wind-gust');
    await logout();
    setTimeout(() => setBgStage('login'), 650);
  };

  return (
    <View style={styles.container}>
      <StatusBar
        style={!isBooting && authorized && isAuthenticated && !isDarkMode ? 'dark' : 'light'}
        translucent
        backgroundColor="transparent"
      />

      <SentinelBackground
        stage={bgStage}
        theme={!isBooting && authorized && isAuthenticated && !isDarkMode ? 'light' : 'dark'}
      >
        {!authorized ? (
          isBooting ? null : (
            <LicenseActivationView
              onActivationSuccess={triggerWindBurstToLogin}
            />
          )
        ) : !isAuthenticated ? (
          isBooting ? null : (
            <LoginView
              onLoginSuccess={triggerWindBurstToMain}
            />
          )
        ) : (
          <MainHomeView
            onLogout={handleFullLogout}
          />
        )}
      </SentinelBackground>

      {/* Loader de inicio con fondo de cenizas, logo del Cuerpo de Bomberos arriba, el circulito centrado y abajo el logo de SENTINEL ONE */}
      {isBooting ? (
        <Animated.View
          style={[styles.bootLoaderOverlay, { opacity: bootFadeAnim }]}
          pointerEvents="auto"
        >
          <SentinelBackground stage="login" theme="dark">
            <View style={styles.bootLoaderContainer}>
              {/* Zona Superior: Logo del Cuerpo de Bomberos más arriba */}
              <View style={styles.bootTopZone}>
                {logoUrl ? (
                  <Image
                    source={{ uri: logoUrl }}
                    style={styles.bootInstitutionLogo}
                    resizeMode="contain"
                  />
                ) : null}
              </View>

              {/* Zona Central: Loader (circulito) exactamente al centro de la pantalla */}
              <View style={styles.bootCenterZone}>
                <ActivityIndicator
                  size="large"
                  color="#EF4444"
                  style={styles.bootSpinner}
                />
              </View>

              {/* Zona Inferior: Logo SENTINEL ONE más abajo */}
              <View style={styles.bootBottomZone}>
                <Image
                  source={require('./assets/LOGO_SENTINEL_LOADER.png')}
                  style={styles.bootSentinelOneBottomLogo}
                  resizeMode="contain"
                />
              </View>
            </View>
          </SentinelBackground>
        </Animated.View>
      ) : null}

      {/* Modal Requisito de Google Play: Aviso Destacado de Ubicación y Permisos (Solo post-login) */}
      <LocationDisclosureModal
        visible={Boolean(!isBooting && authorized && isAuthenticated && locationModalVisible)}
        onAccept={handleAcceptLocation}
        onDecline={handleDeclineLocation}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#060911'
  },
  bootLoaderOverlay: {
    ...StyleSheet.absoluteFillObject,
    zIndex: 9999,
    backgroundColor: '#060911'
  },
  bootLoaderContainer: {
    flex: 1,
    width: '100%',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 32
  },
  bootTopZone: {
    flex: 1,
    width: '100%',
    alignItems: 'center',
    justifyContent: 'flex-end',
    paddingBottom: 38
  },
  bootCenterZone: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 16
  },
  bootBottomZone: {
    flex: 1,
    width: '100%',
    alignItems: 'center',
    justifyContent: 'flex-start',
    paddingTop: 42
  },
  bootInstitutionLogo: {
    width: 148,
    height: 148
  },
  bootSpinner: {
    transform: [{ scale: 1.35 }]
  },
  bootSentinelOneBottomLogo: {
    width: 240,
    height: 54
  }
});
