import React, { useState, useRef } from 'react';
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
import { User, Lock, Eye, EyeOff, LogIn, AlertCircle } from 'lucide-react-native';
import * as Haptics from 'expo-haptics';
import { useAuthStore } from '../store/useAuthStore';
import { useLicenseStore } from '../store/useLicenseStore';

const FloatingLabelInput = ({
  label,
  value,
  onChangeText,
  secureTextEntry,
  keyboardType = 'default',
  isPassword = false,
  showPassword,
  onToggleShowPassword,
  editable = true,
  onSubmitEditing,
  returnKeyType = 'default',
  icon: IconComponent
}) => {
  const [isFocused, setIsFocused] = useState(false);
  const inputRef = useRef(null);
  const isFloated = isFocused || Boolean(value && String(value).length > 0);

  return (
    <TouchableOpacity
      activeOpacity={1}
      onPress={() => inputRef.current?.focus()}
      style={[styles.floatingInputWrapper, isFocused && styles.floatingInputWrapperFocused]}
    >
      <TextInput
        ref={inputRef}
        style={styles.floatingTextInput}
        value={value}
        onChangeText={onChangeText}
        onFocus={() => setIsFocused(true)}
        onBlur={() => setIsFocused(false)}
        onSubmitEditing={onSubmitEditing}
        returnKeyType={returnKeyType}
        secureTextEntry={isPassword ? !showPassword : secureTextEntry}
        keyboardType={keyboardType}
        autoCapitalize="none"
        autoCorrect={false}
        editable={editable}
      />

      {IconComponent ? (
        <View pointerEvents="none" style={styles.floatingInputIconBox}>
          <IconComponent size={18} color={isFocused ? '#ef4444' : '#64748b'} />
        </View>
      ) : null}

      <Text
        pointerEvents="none"
        style={[
          styles.floatingLabelText,
          isFloated ? styles.floatingLabelFloated : styles.floatingLabelResting
        ]}
      >
        {label}
      </Text>

      {isPassword ? (
        <TouchableOpacity
          style={styles.eyeToggleBtn}
          onPress={onToggleShowPassword}
          activeOpacity={0.7}
        >
          {showPassword ? (
            <EyeOff size={18} color="#94a3b8" />
          ) : (
            <Eye size={18} color="#94a3b8" />
          )}
        </TouchableOpacity>
      ) : null}
    </TouchableOpacity>
  );
};

export const LoginView = ({ onLoginSuccess }) => {
  const { login, isLoading, error } = useAuthStore();
  const { nombreMostrar, clientName, comuna, logoUrl } = useLicenseStore();

  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [localError, setLocalError] = useState('');

  const triggerHaptic = (style = 'medium') => {
    try {
      if (Platform.OS !== 'web') {
        if (style === 'heavy') Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Heavy);
        else if (style === 'success') Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        else Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      }
    } catch (e) {}
  };

  const handleLogin = async () => {
    triggerHaptic('medium');
    if (!identifier.trim()) {
      setLocalError('Ingrese su usuario');
      return;
    }
    if (!password.trim()) {
      setLocalError('Ingrese su clave de acceso');
      return;
    }
    setLocalError('');

    const res = await login(identifier, password, false);
    if (res.success) {
      triggerHaptic('success');
      if (onLoginSuccess) {
        onLoginSuccess();
      }
    } else {
      triggerHaptic('heavy');
      setLocalError(res.error || 'Credenciales incorrectas');
    }
  };

  const rawInstitution = (comuna || nombreMostrar || clientName || '')
    .replace(/^cuerpo\s+de\s+bomberos\s+de\s+/i, '')
    .trim()
    .toUpperCase();
  const institutionTitle = rawInstitution
    ? `CUERPO DE BOMBEROS DE ${rawInstitution}`
    : 'CUERPO DE BOMBEROS';

  const displayError = localError || error;

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      style={styles.container}
    >
      <ScrollView contentContainerStyle={styles.scrollContent} keyboardShouldPersistTaps="handled">
        <View style={styles.loginBoxCard}>
          {/* Escudo Institucional (sin círculo) */}
          <Image
            source={logoUrl ? { uri: logoUrl } : require('../../assets/LOGO_SENTINEL.png')}
            style={styles.logo}
            resizeMode="contain"
          />

          <Text style={styles.institutionTitle}>{institutionTitle}</Text>
          <Text style={styles.institutionSubtitle}>APLICACIÓN OPERATIVA DEL BOMBERO</Text>

          {/* Input Floating Label: Usuario (Todo el rectángulo es táctil) */}
          <FloatingLabelInput
            label="Usuario"
            value={identifier}
            onChangeText={(t) => {
              setIdentifier(t);
              setLocalError('');
            }}
            icon={User}
            keyboardType="default"
            returnKeyType="next"
            editable={!isLoading}
          />

          {/* Input Floating Label: Clave de Acceso (Todo el rectángulo es táctil) */}
          <FloatingLabelInput
            label="Clave de Acceso"
            value={password}
            onChangeText={(t) => {
              setPassword(t);
              setLocalError('');
            }}
            icon={Lock}
            isPassword
            showPassword={showPassword}
            onToggleShowPassword={() => setShowPassword(!showPassword)}
            onSubmitEditing={handleLogin}
            returnKeyType="done"
            editable={!isLoading}
          />

          {/* Mensaje de Error */}
          <View style={styles.errorContainer}>
            {displayError ? (
              <>
                <AlertCircle size={15} color="#ef4444" style={{ marginRight: 6 }} />
                <Text style={styles.errorText}>{displayError}</Text>
              </>
            ) : null}
          </View>

          {/* Botón Ingresar al Sistema */}
          <TouchableOpacity
            style={styles.loginButton}
            activeOpacity={0.85}
            disabled={isLoading}
            onPress={handleLogin}
          >
            {isLoading ? (
              <ActivityIndicator color="#ffffff" size="small" />
            ) : (
              <>
                <LogIn size={18} color="#ffffff" style={{ marginRight: 8 }} />
                <Text style={styles.buttonText}>INGRESAR AL SISTEMA</Text>
              </>
            )}
          </TouchableOpacity>

          {/* Pie de Versión con Logo Oficial SENTINEL ONE + V.3.0.0 */}
          <View style={styles.footerVersionRow}>
            <Image
              source={require('../../assets/SENTINEL_ONE.png')}
              style={styles.moduleFooterLogo}
              resizeMode="contain"
            />
            <Text style={styles.versionTagText}>V.3.0.0</Text>
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
  logo: {
    width: 78,
    height: 78,
    marginBottom: 13
  },
  institutionTitle: {
    fontSize: 19.5,
    fontWeight: '900',
    color: '#ffffff',
    textAlign: 'center',
    letterSpacing: 0.3,
    textTransform: 'uppercase'
  },
  institutionSubtitle: {
    fontSize: 9.75,
    fontWeight: '700',
    color: '#94a3b8',
    textAlign: 'center',
    letterSpacing: 1.2,
    marginTop: 3.5,
    marginBottom: 19.5,
    textTransform: 'uppercase'
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
    marginBottom: 13,
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
  eyeToggleBtn: {
    position: 'absolute',
    right: 8,
    top: 0,
    bottom: 0,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 8,
    zIndex: 5
  },
  errorContainer: {
    minHeight: 18,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 6.5
  },
  errorText: {
    color: '#ef4444',
    fontSize: 10.5,
    fontWeight: '700',
    textAlign: 'center'
  },
  loginButton: {
    width: '100%',
    backgroundColor: '#b91c1c',
    borderRadius: 10,
    paddingVertical: 13,
    flexDirection: 'row',
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
