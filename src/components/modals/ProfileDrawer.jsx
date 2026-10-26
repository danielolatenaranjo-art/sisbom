import React, { useState, useEffect, useRef, useMemo } from 'react';
import { 
  View, 
  Text, 
  StyleSheet, 
  Modal, 
  TouchableOpacity, 
  TouchableWithoutFeedback, 
  Image, 
  ScrollView, 
  Switch, 
  TextInput, 
  Animated, 
  Easing,
  Dimensions, 
  Platform 
} from 'react-native';
import { 
  X, 
  Lock, 
  LockOpen, 
  Moon, 
  BellOff, 
  CheckCircle, 
  AlertCircle,
  Power
} from 'lucide-react-native';
import * as Haptics from 'expo-haptics';
import { useAuthStore, formatProfilePhotoUrl } from '../../store/useAuthStore';
import { useLicenseStore } from '../../store/useLicenseStore';
import { useDispatchStore } from '../../store/useDispatchStore';

const { width: SCREEN_WIDTH } = Dimensions.get('window');
const DRAWER_WIDTH = Math.min(320, SCREEN_WIDTH * 0.82);

export const ProfileDrawer = ({ visible, onClose }) => {
  const { 
    user, 
    isDarkMode, 
    setDarkMode, 
    isAirplaneMode, 
    setAirplaneMode, 
    changePassword,
    refreshUserProfile
  } = useAuthStore();
  const { logoUrl } = useLicenseStore();
  const { 
    openDoorIoT, 
    closeCentralOperatorSession, 
    centralOperatorName, 
    centralOperatorId, 
    isCentralActive,
    firefightersList
  } = useDispatchStore();

  const slideAnim = useRef(new Animated.Value(-DRAWER_WIDTH)).current;
  const fadeAnim = useRef(new Animated.Value(0)).current;
  const [isRendered, setIsRendered] = useState(visible);
  const isClosingRef = useRef(false);

  const [showPasswordChange, setShowPasswordChange] = useState(false);
  const [currentPass, setCurrentPass] = useState('');
  const [newPass, setNewPass] = useState('');
  const [confirmPass, setConfirmPass] = useState('');
  const [passError, setPassError] = useState('');
  const [passSuccess, setPassSuccess] = useState('');
  const [isDoorOpening, setIsDoorOpening] = useState(false);

  useEffect(() => {
    if (visible) {
      isClosingRef.current = false;
      setIsRendered(true);
      if (refreshUserProfile) {
        refreshUserProfile();
      }
      slideAnim.stopAnimation();
      fadeAnim.stopAnimation();
      slideAnim.setValue(-DRAWER_WIDTH);
      fadeAnim.setValue(0);
      Animated.parallel([
        Animated.timing(slideAnim, {
          toValue: 0,
          duration: 280,
          easing: Easing.out(Easing.cubic),
          useNativeDriver: true
        }),
        Animated.timing(fadeAnim, {
          toValue: 1,
          duration: 280,
          easing: Easing.out(Easing.cubic),
          useNativeDriver: true
        })
      ]).start();
    } else if (isRendered && !isClosingRef.current) {
      isClosingRef.current = true;
      slideAnim.stopAnimation();
      fadeAnim.stopAnimation();
      Animated.parallel([
        Animated.timing(slideAnim, {
          toValue: -DRAWER_WIDTH,
          duration: 260,
          easing: Easing.inOut(Easing.cubic),
          useNativeDriver: true
        }),
        Animated.timing(fadeAnim, {
          toValue: 0,
          duration: 260,
          easing: Easing.inOut(Easing.cubic),
          useNativeDriver: true
        })
      ]).start(() => {
        isClosingRef.current = false;
        setIsRendered(false);
      });
    }
  }, [visible]);

  const handleAnimatedClose = () => {
    if (isClosingRef.current) return;
    isClosingRef.current = true;
    slideAnim.stopAnimation();
    fadeAnim.stopAnimation();
    Animated.parallel([
      Animated.timing(slideAnim, {
        toValue: -DRAWER_WIDTH,
        duration: 260,
        easing: Easing.inOut(Easing.cubic),
        useNativeDriver: true
      }),
      Animated.timing(fadeAnim, {
        toValue: 0,
        duration: 260,
        easing: Easing.inOut(Easing.cubic),
        useNativeDriver: true
      })
    ]).start(() => {
      isClosingRef.current = false;
      setIsRendered(false);
      if (onClose) onClose();
    });
  };

  const liveUser = useMemo(() => {
    if (!user || !Array.isArray(firefightersList) || firefightersList.length === 0) return null;
    const myIdReg = String(user.idRegistro || '').trim();
    const uName = String(user.nombreBombero || user.nombre || '').trim().toUpperCase();
    return (
      (myIdReg && firefightersList.find(p => String(p.idRegistro).trim() === myIdReg)) ||
      (!myIdReg && uName && firefightersList.find(p => String(p.nombreBombero || p.nombre || '').trim().toUpperCase() === uName)) ||
      null
    );
  }, [user, firefightersList]);

  const triggerHaptic = (style = 'medium') => {
    try {
      if (Platform.OS !== 'web') {
        if (style === 'heavy') Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Heavy);
        else if (style === 'success') Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        else Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      }
    } catch (e) {}
  };

  const handleOpenDoor = async () => {
    triggerHaptic('success');
    setIsDoorOpening(true);
    await openDoorIoT();
    setTimeout(() => {
      setIsDoorOpening(false);
    }, 3500);
  };

  const handleCloseCentralSession = async () => {
    triggerHaptic('heavy');
    await closeCentralOperatorSession();
    handleAnimatedClose();
  };

  const handleChangePassword = async () => {
    triggerHaptic('medium');
    setPassError('');
    setPassSuccess('');

    const res = await changePassword(currentPass, newPass, confirmPass);
    if (!res.success) {
      setPassError(res.error || 'Error al actualizar contraseña');
      return;
    }

    setPassSuccess(res.message || '¡Clave actualizada correctamente!');
    setTimeout(() => {
      setShowPasswordChange(false);
      setCurrentPass('');
      setNewPass('');
      setConfirmPass('');
      setPassSuccess('');
    }, 1500);
  };

  if (!isRendered) return null;

  const displayName = liveUser?.nombreBombero || user?.nombreBombero || user?.nombre || 'BOMBERO';
  const displayCargo = liveUser?.cargo || user?.cargo || 'Voluntario';
  const idReg = liveUser?.idRegistro || user?.idRegistro || user?.idRadial || '1';
  const idRad = liveUser?.idRadial || user?.idRadial || '1';

  const isComandante = String(idRad).trim() === '1' || String(idRad).trim() === '01' || displayCargo.toUpperCase().includes('COMANDANTE');
  const normStr = (s) =>
    String(s || '')
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .trim()
      .toUpperCase();
  const isMyCentralTurn =
    Boolean(isCentralActive) ||
    Boolean(
      centralOperatorId &&
        (String(centralOperatorId).trim() === String(idReg).trim() ||
          String(centralOperatorId).trim() === String(idRad).trim())
    ) ||
    Boolean(
      centralOperatorName && normStr(centralOperatorName) === normStr(displayName)
    );

  const hasPuertaPermission =
    user?.puerta === true ||
    user?.Puerta === true ||
    liveUser?.puerta === true ||
    liveUser?.Puerta === true ||
    user?.puerta === 1 ||
    liveUser?.puerta === 1 ||
    String(user?.puerta ?? '').trim().toLowerCase() === 'true' ||
    String(liveUser?.puerta ?? '').trim().toLowerCase() === 'true' ||
    String(user?.puerta ?? '').trim().toUpperCase() === 'SI' ||
    String(liveUser?.puerta ?? '').trim().toUpperCase() === 'SI';

  const canOpenDoor = isComandante || isMyCentralTurn || isCentralActive || hasPuertaPermission;
  const canCloseOp = Boolean(centralOperatorName) && (isComandante || isMyCentralTurn || displayCargo.toUpperCase().includes('OPERADOR'));

  const photoUri = formatProfilePhotoUrl(liveUser?.foto || user?.foto);

  return (
    <Modal visible={isRendered} transparent animationType="none" onRequestClose={handleAnimatedClose}>
      <View style={styles.overlayContainer}>
        {/* Backdrop oscuro con fade */}
        <TouchableWithoutFeedback onPress={handleAnimatedClose}>
          <Animated.View style={[styles.backdrop, { opacity: fadeAnim }]} />
        </TouchableWithoutFeedback>

        {/* Contenedor del Drawer deslizante desde la izquierda */}
        <Animated.View
          style={[
            styles.drawerPanel,
            {
              backgroundColor: isDarkMode ? '#0A0505' : '#FFFFFF',
              borderRightColor: isDarkMode ? '#1e293b' : '#E2E8F0',
              transform: [{ translateX: slideAnim }]
            }
          ]}
        >
          <ScrollView 
            style={styles.scrollBody} 
            contentContainerStyle={styles.scrollContent} 
            showsVerticalScrollIndicator={false}
          >
            {/* Cabecera del Perfil con Foto, Nombre y Botón Cerrar (X) */}
            <View style={styles.headerRow}>
              <View style={styles.headerInfoLeft}>
                <View style={styles.avatarBorder}>
                  {photoUri ? (
                    <Image source={{ uri: photoUri }} style={styles.avatarImage} />
                  ) : logoUrl ? (
                    <Image source={{ uri: logoUrl }} style={styles.avatarImage} />
                  ) : (
                    <Image source={require('../../../assets/LOGO_SENTINEL.png')} style={styles.avatarImage} />
                  )}
                </View>

                <View style={styles.nameContainer}>
                  <Text
                    style={[
                      styles.userNameText,
                      { color: isDarkMode ? '#ffffff' : '#0F172A' }
                    ]}
                    numberOfLines={2}
                  >
                    {displayName.toUpperCase()}
                  </Text>
                  <Text style={styles.userCargoText} numberOfLines={1}>
                    {displayCargo.toUpperCase()}
                  </Text>
                </View>
              </View>

              <TouchableOpacity 
                style={[
                  styles.closeBtn,
                  {
                    backgroundColor: isDarkMode ? '#1e293b' : '#F1F5F9',
                    borderColor: isDarkMode ? '#334155' : '#CBD5E1'
                  }
                ]}
                onPress={() => {
                  triggerHaptic('light');
                  handleAnimatedClose();
                }}
                activeOpacity={0.7}
              >
                <X size={16} color={isDarkMode ? '#ffffff' : '#0F172A'} />
              </TouchableOpacity>
            </View>

            <View
              style={[
                styles.divider,
                { backgroundColor: isDarkMode ? '#1e293b' : '#E2E8F0' }
              ]}
            />

            {/* SECCIÓN 1: IDENTIFICACIÓN OFICIAL */}
            <Text style={styles.sectionTitle}>IDENTIFICACIÓN OFICIAL</Text>
            <View style={styles.idCardsRow}>
              {/* N° Registro */}
              <View
                style={[
                  styles.idBoxGrey,
                  {
                    backgroundColor: isDarkMode ? 'rgba(30, 41, 59, 0.4)' : '#F8FAFC',
                    borderColor: isDarkMode ? '#334155' : '#CBD5E1'
                  }
                ]}
              >
                <Text style={styles.idBoxLabel}>N° REGISTRO</Text>
                <Text
                  style={[
                    styles.idBoxValueGrey,
                    { color: isDarkMode ? '#ffffff' : '#0F172A' }
                  ]}
                >
                  {idReg}
                </Text>
              </View>

              {/* ID Radial */}
              <View
                style={[
                  styles.idBoxRed,
                  {
                    backgroundColor: isDarkMode ? 'rgba(69, 10, 10, 0.35)' : '#FEF2F2',
                    borderColor: 'rgba(239, 68, 68, 0.4)'
                  }
                ]}
              >
                <Text style={styles.idBoxLabelRed}>ID RADIAL</Text>
                <Text style={styles.idBoxValueRed}>{idRad}</Text>
              </View>
            </View>

            {/* SECCIÓN 2: APARIENCIA DE LA APP */}
            <Text style={styles.sectionTitle}>APARIENCIA DE LA APP</Text>
            <View
              style={[
                styles.optionCard,
                {
                  backgroundColor: isDarkMode ? 'rgba(30, 41, 59, 0.35)' : '#F8FAFC',
                  borderColor: isDarkMode ? '#334155' : '#CBD5E1'
                }
              ]}
            >
              <View style={styles.optionRow}>
                <View style={styles.optionLeft}>
                  <Moon size={18} color={isDarkMode ? '#94a3b8' : '#475569'} />
                  <Text
                    style={[
                      styles.optionLabel,
                      { color: isDarkMode ? '#ffffff' : '#0F172A' }
                    ]}
                  >
                    Modo Visual Oscuro
                  </Text>
                </View>
                <Switch
                  value={isDarkMode}
                  onValueChange={(val) => {
                    triggerHaptic('medium');
                    setDarkMode(val);
                  }}
                  trackColor={{ false: '#CBD5E1', true: '#3b82f6' }}
                  thumbColor="#ffffff"
                />
              </View>
            </View>

            {/* SECCIÓN 3: MODO DE NOTIFICACIONES */}
            <Text style={styles.sectionTitle}>MODO DE NOTIFICACIONES</Text>
            <View
              style={[
                styles.optionCard,
                {
                  backgroundColor: isDarkMode ? 'rgba(30, 41, 59, 0.35)' : '#F8FAFC',
                  borderColor: isDarkMode ? '#334155' : '#CBD5E1'
                }
              ]}
            >
              <View style={styles.optionRow}>
                <View style={styles.optionColumn}>
                  <View style={styles.optionLeft}>
                    <BellOff size={18} color={isAirplaneMode ? '#ef4444' : (isDarkMode ? '#94a3b8' : '#475569')} />
                    <Text
                      style={[
                        styles.optionLabel,
                        { color: isDarkMode ? '#ffffff' : '#0F172A' }
                      ]}
                    >
                      Modo Avión (0-8 Absoluto)
                    </Text>
                  </View>
                  <Text style={styles.optionSubtitle}>Silencia y bloquea toda alerta</Text>
                </View>
                <Switch
                  value={isAirplaneMode}
                  onValueChange={(val) => {
                    triggerHaptic('heavy');
                    setAirplaneMode(val);
                  }}
                  trackColor={{ false: '#CBD5E1', true: '#ef4444' }}
                  thumbColor="#ffffff"
                />
              </View>
            </View>

            {/* SECCIÓN 4: SEGURIDAD (CAMBIAR CONTRASEÑA) */}
            <Text style={styles.sectionTitle}>SEGURIDAD</Text>
            {!showPasswordChange ? (
              <TouchableOpacity 
                style={[
                  styles.changePassBtn,
                  {
                    backgroundColor: isDarkMode ? 'rgba(30, 41, 59, 0.35)' : '#F8FAFC',
                    borderColor: isDarkMode ? '#334155' : '#CBD5E1'
                  }
                ]}
                onPress={() => {
                  triggerHaptic('medium');
                  setShowPasswordChange(true);
                }}
                activeOpacity={0.8}
              >
                <Lock size={15} color={isDarkMode ? '#cbd5e1' : '#334155'} style={{ marginRight: 8 }} />
                <Text
                  style={[
                    styles.changePassBtnText,
                    { color: isDarkMode ? '#cbd5e1' : '#334155' }
                  ]}
                >
                  CAMBIAR CONTRASEÑA
                </Text>
              </TouchableOpacity>
            ) : (
              <View
                style={[
                  styles.passChangeCard,
                  {
                    backgroundColor: isDarkMode ? 'rgba(15, 23, 42, 0.7)' : '#F8FAFC',
                    borderColor: isDarkMode ? '#334155' : '#CBD5E1'
                  }
                ]}
              >
                <Text style={styles.passInputLabel}>Contraseña Actual</Text>
                <TextInput
                  style={[
                    styles.passInput,
                    {
                      backgroundColor: isDarkMode ? '#0b0f17' : '#FFFFFF',
                      borderColor: isDarkMode ? '#334155' : '#CBD5E1',
                      color: isDarkMode ? '#ffffff' : '#0F172A'
                    }
                  ]}
                  secureTextEntry
                  value={currentPass}
                  onChangeText={setCurrentPass}
                  placeholder="••••"
                  placeholderTextColor="#64748b"
                />

                <Text style={styles.passInputLabel}>Contraseña Nueva</Text>
                <TextInput
                  style={[
                    styles.passInput,
                    {
                      backgroundColor: isDarkMode ? '#0b0f17' : '#FFFFFF',
                      borderColor: isDarkMode ? '#334155' : '#CBD5E1',
                      color: isDarkMode ? '#ffffff' : '#0F172A'
                    }
                  ]}
                  secureTextEntry
                  value={newPass}
                  onChangeText={setNewPass}
                  placeholder="••••"
                  placeholderTextColor="#64748b"
                />

                <Text style={styles.passInputLabel}>Repetir Contraseña</Text>
                <TextInput
                  style={[
                    styles.passInput,
                    {
                      backgroundColor: isDarkMode ? '#0b0f17' : '#FFFFFF',
                      borderColor: isDarkMode ? '#334155' : '#CBD5E1',
                      color: isDarkMode ? '#ffffff' : '#0F172A'
                    }
                  ]}
                  secureTextEntry
                  value={confirmPass}
                  onChangeText={setConfirmPass}
                  placeholder="••••"
                  placeholderTextColor="#64748b"
                />

                {passError ? (
                  <View style={styles.passFeedbackRow}>
                    <AlertCircle size={14} color="#ef4444" />
                    <Text style={styles.passErrorText}>{passError}</Text>
                  </View>
                ) : null}

                {passSuccess ? (
                  <View style={styles.passFeedbackRow}>
                    <CheckCircle size={14} color="#10b981" />
                    <Text style={styles.passSuccessText}>{passSuccess}</Text>
                  </View>
                ) : null}

                <TouchableOpacity 
                  style={styles.btnSavePass} 
                  onPress={handleChangePassword}
                  activeOpacity={0.8}
                >
                  <Text style={styles.btnSavePassText}>ACTUALIZAR CLAVE</Text>
                </TouchableOpacity>

                <TouchableOpacity 
                  style={styles.btnCancelPass} 
                  onPress={() => setShowPasswordChange(false)}
                  activeOpacity={0.7}
                >
                  <Text style={styles.btnCancelPassText}>OCULTAR</Text>
                </TouchableOpacity>
              </View>
            )}

            {/* SECCIÓN 5: ACCESOS (APERTURA DE PUERTA IOT) */}
            {canOpenDoor ? (
              <>
                <Text style={styles.sectionTitle}>ACCESOS</Text>
                <TouchableOpacity 
                  style={[styles.btnDoor, isDoorOpening && styles.btnDoorActive]} 
                  onPress={handleOpenDoor}
                  disabled={isDoorOpening}
                  activeOpacity={0.8}
                >
                  <LockOpen size={16} color="#ffffff" style={{ marginRight: 8 }} />
                  <Text style={styles.btnDoorText}>
                    {isDoorOpening ? 'ABRIENDO PUERTA...' : 'APERTURA DE PUERTA'}
                  </Text>
                </TouchableOpacity>
              </>
            ) : null}

            {/* FOOTER DE VERSIÓN CON LOGO SENTINEL ONE OFICIAL + V.3.0.0 */}
            <View style={styles.versionFooter}>
              <Image
                source={
                  isDarkMode
                    ? require('../../../assets/SENTINEL_ONE.png')
                    : require('../../../assets/SENTINEL_ONE_LIGHT.png')
                }
                style={styles.footerLogoImage}
                resizeMode="contain"
              />
              <Text style={styles.versionTitle}>V.3.0.0</Text>
            </View>
          </ScrollView>
        </Animated.View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlayContainer: {
    flex: 1,
    flexDirection: 'row'
  },
  backdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0, 0, 0, 0.72)'
  },
  drawerPanel: {
    width: DRAWER_WIDTH,
    height: '100%',
    backgroundColor: '#07090e',
    borderRightWidth: 1,
    borderRightColor: '#1e293b',
    shadowColor: '#000',
    shadowOffset: { width: 4, height: 0 },
    shadowOpacity: 0.5,
    shadowRadius: 16,
    elevation: 20
  },
  scrollBody: {
    flex: 1
  },
  scrollContent: {
    paddingTop: Platform.OS === 'ios' ? 50 : 40,
    paddingBottom: 36,
    paddingHorizontal: 16
  },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 16
  },
  headerInfoLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    flex: 1,
    paddingRight: 8
  },
  avatarBorder: {
    width: 54,
    height: 54,
    borderRadius: 27,
    borderWidth: 2,
    borderColor: '#ef4444',
    overflow: 'hidden',
    backgroundColor: '#0f172a',
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 12
  },
  avatarImage: {
    width: '100%',
    height: '100%',
    resizeMode: 'cover'
  },
  nameContainer: {
    flex: 1
  },
  userNameText: {
    fontSize: 13,
    fontWeight: '900',
    color: '#ffffff',
    letterSpacing: 0.3,
    lineHeight: 17
  },
  userCargoText: {
    fontSize: 11,
    fontWeight: '800',
    color: '#ef4444',
    marginTop: 2,
    letterSpacing: 0.2
  },
  closeBtn: {
    width: 32,
    height: 32,
    borderRadius: 16,
    backgroundColor: '#1e293b',
    borderWidth: 1,
    borderColor: '#334155',
    alignItems: 'center',
    justifyContent: 'center'
  },
  divider: {
    height: 1,
    backgroundColor: '#1e293b',
    marginVertical: 14
  },
  sectionTitle: {
    fontSize: 10,
    fontWeight: '900',
    color: '#94a3b8',
    letterSpacing: 0.8,
    marginTop: 14,
    marginBottom: 8
  },
  idCardsRow: {
    flexDirection: 'row',
    gap: 10
  },
  idBoxGrey: {
    flex: 1,
    backgroundColor: 'rgba(30, 41, 59, 0.4)',
    borderWidth: 1,
    borderColor: '#334155',
    borderRadius: 14,
    paddingVertical: 10,
    alignItems: 'center',
    justifyContent: 'center'
  },
  idBoxLabel: {
    fontSize: 9,
    fontWeight: '800',
    color: '#94a3b8',
    letterSpacing: 0.5
  },
  idBoxValueGrey: {
    fontSize: 19,
    fontWeight: '900',
    color: '#ffffff',
    marginTop: 2
  },
  idBoxRed: {
    flex: 1,
    backgroundColor: 'rgba(69, 10, 10, 0.35)',
    borderWidth: 1,
    borderColor: 'rgba(239, 68, 68, 0.4)',
    borderRadius: 14,
    paddingVertical: 10,
    alignItems: 'center',
    justifyContent: 'center'
  },
  idBoxLabelRed: {
    fontSize: 9,
    fontWeight: '800',
    color: '#f87171',
    letterSpacing: 0.5
  },
  idBoxValueRed: {
    fontSize: 19,
    fontWeight: '900',
    color: '#ef4444',
    marginTop: 2
  },
  optionCard: {
    backgroundColor: 'rgba(30, 41, 59, 0.35)',
    borderWidth: 1,
    borderColor: '#334155',
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 12
  },
  optionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between'
  },
  optionLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10
  },
  optionColumn: {
    flex: 1,
    paddingRight: 8
  },
  optionLabel: {
    fontSize: 12,
    fontWeight: '700',
    color: '#ffffff'
  },
  optionSubtitle: {
    fontSize: 10,
    fontWeight: '600',
    color: '#64748b',
    marginTop: 2,
    marginLeft: 28
  },
  changePassBtn: {
    backgroundColor: 'rgba(30, 41, 59, 0.35)',
    borderWidth: 1,
    borderColor: '#334155',
    borderRadius: 14,
    paddingVertical: 12,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center'
  },
  changePassBtnText: {
    fontSize: 11,
    fontWeight: '900',
    color: '#cbd5e1',
    letterSpacing: 0.5
  },
  passChangeCard: {
    backgroundColor: 'rgba(15, 23, 42, 0.7)',
    borderWidth: 1,
    borderColor: '#334155',
    borderRadius: 14,
    padding: 14
  },
  passInputLabel: {
    fontSize: 10,
    fontWeight: '700',
    color: '#94a3b8',
    marginBottom: 4,
    marginTop: 6
  },
  passInput: {
    backgroundColor: '#0b0f17',
    borderWidth: 1,
    borderColor: '#334155',
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 8,
    color: '#ffffff',
    fontSize: 13,
    outlineStyle: 'none'
  },
  passFeedbackRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginTop: 8
  },
  passErrorText: {
    fontSize: 11,
    fontWeight: '700',
    color: '#ef4444'
  },
  passSuccessText: {
    fontSize: 11,
    fontWeight: '700',
    color: '#10b981'
  },
  btnSavePass: {
    backgroundColor: '#dc2626',
    borderRadius: 10,
    paddingVertical: 10,
    alignItems: 'center',
    marginTop: 12
  },
  btnSavePassText: {
    fontSize: 11,
    fontWeight: '900',
    color: '#ffffff',
    letterSpacing: 0.5
  },
  btnCancelPass: {
    backgroundColor: '#1e293b',
    borderRadius: 10,
    paddingVertical: 8,
    alignItems: 'center',
    marginTop: 6
  },
  btnCancelPassText: {
    fontSize: 11,
    fontWeight: '800',
    color: '#94a3b8'
  },
  btnDoor: {
    backgroundColor: '#10B981',
    borderRadius: 16,
    paddingVertical: 13,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center'
  },
  btnDoorActive: {
    backgroundColor: '#059669'
  },
  btnDoorText: {
    fontSize: 11,
    fontWeight: '900',
    color: '#ffffff',
    letterSpacing: 0.5
  },
  btnCloseCentral: {
    backgroundColor: '#b91c1c',
    borderRadius: 14,
    paddingVertical: 12,
    paddingHorizontal: 10,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 10
  },
  btnCloseCentralText: {
    fontSize: 10.5,
    fontWeight: '900',
    color: '#ffffff',
    letterSpacing: 0.4,
    textAlign: 'center'
  },
  versionFooter: {
    marginTop: 24,
    alignItems: 'center',
    justifyContent: 'center'
  },
  footerLogoImage: {
    width: 170,
    height: 34,
    marginBottom: 4
  },
  versionTitle: {
    fontSize: 11,
    fontWeight: '900',
    color: '#D97706',
    letterSpacing: 0.6
  }
});
