import React, { useState, useRef, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TextInput,
  TouchableOpacity,
  Image,
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  ScrollView
} from 'react-native';
import { KeyRound, ShieldCheck, AlertCircle } from 'lucide-react-native';
import { useLicenseStore } from '../store/useLicenseStore';
import { useAuthStore } from '../store/useAuthStore';

export const LicenseActivationView = ({
  onActivationSuccess,
  introMode = false,
  introCardReveal = 1,
  onTopLogoRect
}) => {
  const { validateLicense, isChecking, errorReason } = useLicenseStore();
  const [inputKey, setInputKey] = useState('');
  const [localError, setLocalError] = useState('');
  const [isValidatedSuccess, setIsValidatedSuccess] = useState(false);
  const [isFocused, setIsFocused] = useState(false);
  const licenseInputRef = useRef(null);
  const topLogoRef = useRef(null);
  const isIosRuntime =
    typeof window !== 'undefined' &&
    Boolean(window.webkit?.messageHandlers?.iOSApp || window.__SENTINEL_PLATFORM__ === 'ios');

  const measureTopLogo = useCallback(() => {
    if (!onTopLogoRect || !topLogoRef.current) return;
    try {
      const node = topLogoRef.current;
      if (typeof node.getBoundingClientRect === 'function') {
        const r = node.getBoundingClientRect();
        if (r && r.width > 10 && r.height > 10) {
          onTopLogoRect({ x: r.left, y: r.top, width: r.width, height: r.height });
          return;
        }
      }
      if (typeof node.measureInWindow === 'function') {
        node.measureInWindow((x, y, width, height) => {
          if (width > 10 && height > 10) {
            onTopLogoRect({ x, y, width, height });
          }
        });
      }
    } catch (_) {}
  }, [onTopLogoRect]);

  useEffect(() => {
    if (!introMode) return;
    const t1 = setTimeout(measureTopLogo, 30);
    const t2 = setTimeout(measureTopLogo, 180);
    if (typeof window !== 'undefined') {
      window.addEventListener('resize', measureTopLogo);
    }
    return () => {
      clearTimeout(t1);
      clearTimeout(t2);
      if (typeof window !== 'undefined') {
        window.removeEventListener('resize', measureTopLogo);
      }
    };
  }, [introMode, measureTopLogo]);

  const clampedReveal = introMode ? Math.max(0, Math.min(1, introCardReveal)) : 1;

  const isFloated = isFocused || Boolean(inputKey && inputKey.length > 0);

  const handleActivate = async (keyToUse) => {
    const key = (keyToUse || inputKey).trim().toUpperCase();
    if (!key) {
      setLocalError(isIosRuntime ? 'Ingrese el Código de Institución.' : 'Ingrese una Clave de Licencia.');
      return;
    }
    setLocalError('');

    // Códigos Maestros de Transformación de Sistema (EXCLUSIVO EN ANDROID — deshabilitado en iOS)
    const isAndroidNativeRuntime =
      typeof window !== 'undefined' &&
      Boolean(window.AndroidApp && typeof window.AndroidApp.switchEcosystemAppMode === 'function') &&
      !window.webkit?.messageHandlers?.iOSApp &&
      window.__SENTINEL_PLATFORM__ !== 'ios';

    if (isAndroidNativeRuntime) {
      const modeMap = {
        SENTINELNAV: 'NAV',
        SENTINELMED: 'MED',
        SENTINELCREW: 'CREW'
      };
      const targetEcosystemMode = modeMap[key];
      if (targetEcosystemMode) {
        setIsValidatedSuccess(true);
        try {
          window.AndroidApp.switchEcosystemAppMode(targetEcosystemMode);
        } catch (err) {
          setIsValidatedSuccess(false);
          setLocalError('No se pudo activar el modo solicitado.');
        }
        return;
      }
    }

    const success = await validateLicense(key);
    if (success) {
      setIsValidatedSuccess(true);
      if (onActivationSuccess) {
        onActivationSuccess();
      }
      await useAuthStore.getState().logout();
    }
  };

  const displayError = localError || errorReason;

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      style={styles.container}
    >
      <ScrollView contentContainerStyle={styles.scrollContent} keyboardShouldPersistTaps="handled">
        {/* Tarjeta Unificada #login-box (Sin título ni subtítulo en Paso 1) */}
        <View
          style={[
            styles.loginBoxCard,
            introMode && {
              backgroundColor: `rgba(15, 23, 42, ${(0.85 * clampedReveal).toFixed(3)})`,
              borderColor: `rgba(30, 41, 59, ${(0.8 * clampedReveal).toFixed(3)})`,
              shadowOpacity: 0.65 * clampedReveal
            }
          ]}
        >
          {/* Logo superior transparente LOGO.png (Sin círculo) */}
          <View
            ref={topLogoRef}
            collapsable={false}
            onLayout={measureTopLogo}
            style={[styles.topLogo, introMode && { opacity: 0 }]}
          >
            <Image
              source={require('../../assets/LOGO_SENTINEL.png')}
              style={styles.topLogoImg}
              resizeMode="contain"
            />
          </View>

          <View
            style={{
              width: '100%',
              alignItems: 'center',
              opacity: clampedReveal,
              transform: [{ translateY: (1 - clampedReveal) * 14 }]
            }}
            pointerEvents={introMode && clampedReveal < 0.95 ? 'none' : 'auto'}
          >
          {/* Campo Clave de Licencia: Todo el rectángulo es táctil */}
          <TouchableOpacity
            activeOpacity={1}
            onPress={() => licenseInputRef.current?.focus()}
            style={[styles.floatingInputWrapper, isFocused && styles.floatingInputWrapperFocused]}
          >
            <TextInput
              ref={licenseInputRef}
              style={styles.floatingTextInput}
              value={inputKey}
              onChangeText={(t) => {
                setInputKey(t.toUpperCase());
                setLocalError('');
              }}
              onFocus={() => setIsFocused(true)}
              onBlur={() => setIsFocused(false)}
              onSubmitEditing={() => handleActivate()}
              returnKeyType="done"
              autoCapitalize="characters"
              autoCorrect={false}
              editable={!isChecking && !isValidatedSuccess}
            />

            <View pointerEvents="none" style={styles.floatingInputIconBox}>
              {isIosRuntime ? (
                <ShieldCheck size={18} color={isFocused ? '#ef4444' : '#64748b'} />
              ) : (
                <KeyRound size={18} color={isFocused ? '#ef4444' : '#64748b'} />
              )}
            </View>

            <Text
              pointerEvents="none"
              style={[
                styles.floatingLabelText,
                isFloated ? styles.floatingLabelFloated : styles.floatingLabelResting
              ]}
            >
              {isIosRuntime ? 'Código de Institución' : 'Clave de Licencia'}
            </Text>
          </TouchableOpacity>

          {/* Mensaje de Error */}
          <View style={styles.errorContainer}>
            {displayError ? (
              <>
                <AlertCircle size={15} color="#ef4444" style={{ marginRight: 6 }} />
                <Text style={styles.errorText}>{displayError}</Text>
              </>
            ) : null}
          </View>

          {/* Botón Conectar */}
          <TouchableOpacity
            style={styles.connectButton}
            activeOpacity={0.85}
            disabled={isChecking || isValidatedSuccess}
            onPress={() => handleActivate()}
          >
            {isChecking ? (
              <ActivityIndicator color="#ffffff" size="small" />
            ) : (
              <Text style={styles.buttonText}>
                {isValidatedSuccess ? 'CONECTANDO...' : 'CONECTAR'}
              </Text>
            )}
          </TouchableOpacity>

          {/* Pie de Versión con Logo Oficial SENTINEL ONE + V.3.2.0 */}
          <View style={styles.footerVersionRow}>
            <Image
              source={require('../../assets/SENTINEL_ONE.png')}
              style={styles.moduleFooterLogo}
              resizeMode="contain"
            />
            <Text style={styles.versionTagText}>V.3.2.0</Text>
          </View>
          </View>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1
  },
  scrollContent: {
    flexGrow: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20
  },
  loginBoxCard: {
    width: '100%',
    maxWidth: 364,
    backgroundColor: 'rgba(15, 23, 42, 0.85)',
    borderRadius: 20,
    borderWidth: 1,
    borderColor: 'rgba(30, 41, 59, 0.8)',
    padding: 26,
    alignItems: 'center',
    shadowColor: '#000000',
    shadowOpacity: 0.65,
    shadowRadius: 28,
    elevation: 12
  },
  topLogo: {
    width: 156,
    height: 117,
    marginBottom: 19.5
  },
  topLogoImg: {
    width: '100%',
    height: '100%'
  },
  floatingInputWrapper: {
    width: '100%',
    position: 'relative',
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: 'rgba(2, 6, 23, 0.6)',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#1e293b',
    height: 48,
    overflow: 'hidden'
  },
  floatingInputWrapperFocused: {
    borderColor: '#B91C1C',
    backgroundColor: 'rgba(15, 23, 42, 0.95)'
  },
  floatingInputIconBox: {
    position: 'absolute',
    left: 14,
    top: 0,
    bottom: 0,
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 2
  },
  floatingLabelText: {
    position: 'absolute',
    left: 0,
    right: 0,
    textAlign: 'center',
    fontWeight: '700',
    letterSpacing: 0.5,
    textTransform: 'uppercase',
    zIndex: 2
  },
  floatingLabelResting: {
    top: 16,
    fontSize: 10.5,
    color: '#64748b'
  },
  floatingLabelFloated: {
    top: 5,
    fontSize: 8.5,
    color: '#ef4444'
  },
  floatingTextInput: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    width: '100%',
    height: '100%',
    color: '#ffffff',
    fontSize: 13.5,
    fontWeight: '700',
    textAlign: 'center',
    paddingTop: 15,
    paddingBottom: 2,
    paddingHorizontal: 38,
    outlineStyle: 'none',
    borderWidth: 0,
    zIndex: 1
  },
  errorContainer: {
    minHeight: 18,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 13,
    marginBottom: 6.5
  },
  errorText: {
    color: '#ef4444',
    fontSize: 10.5,
    fontWeight: '700',
    textAlign: 'center'
  },
  connectButton: {
    width: '100%',
    backgroundColor: '#b91c1c',
    borderRadius: 10,
    paddingVertical: 13,
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#dc2626',
    shadowOpacity: 0.4,
    shadowRadius: 16,
    elevation: 6
  },
  buttonText: {
    color: '#ffffff',
    fontSize: 11.5,
    fontWeight: '800',
    letterSpacing: 1.3
  },
  footerVersionRow: {
    marginTop: 26,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center'
  },
  moduleFooterLogo: {
    width: 128,
    height: 28,
    marginRight: 10
  },
  versionTagText: {
    color: 'rgba(255, 255, 255, 0.92)',
    fontSize: 11.5,
    fontWeight: '900',
    letterSpacing: 1.8
  }
});
