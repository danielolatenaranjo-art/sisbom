import React, { useEffect, useState, useRef, useCallback, useMemo } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Animated,
  Easing,
  Dimensions,
  Platform
} from 'react-native';
import * as Haptics from 'expo-haptics';
import {
  Sparkles,
  UserCheck,
  Radio,
  Flame,
  LayoutGrid,
  ShieldCheck,
  ChevronRight,
  ChevronLeft,
  CheckCircle2,
  Bell,
  MapPin,
  Zap
} from 'lucide-react-native';
import {
  GUIDED_TOUR_SEEN_KEY,
  persistOnboardingFlag,
  readSafeAreaInsets
} from './FirstLaunchIntroVideo';
import { readNativePermissionsStatus } from '../modals/LocationDisclosureModal';

function measureTargetNode(refObj) {
  return new Promise((resolve) => {
    const node = refObj?.current;
    if (!node) {
      resolve(null);
      return;
    }
    try {
      if (typeof node.getBoundingClientRect === 'function') {
        const r = node.getBoundingClientRect();
        if (r && r.width > 4 && r.height > 4) {
          resolve({
            x: r.left,
            y: r.top,
            width: r.width,
            height: r.height
          });
          return;
        }
      }
      if (typeof node.measureInWindow === 'function') {
        node.measureInWindow((x, y, width, height) => {
          if (width > 4 && height > 4) {
            resolve({ x, y, width, height });
          } else {
            resolve(null);
          }
        });
        return;
      }
    } catch (_) {}
    resolve(null);
  });
}

export const SentinelSpotlightTour = ({
  visible,
  targetRefs,
  userEstado = '0-8',
  canOpenMed = false,
  isCommanderOrAdmin = false,
  onEnsureHomeTop,
  onFinish
}) => {
  const [stepIndex, setStepIndex] = useState(0);
  const [rect, setRect] = useState(null);
  const [winSize, setWinSize] = useState(() => {
    const d = Dimensions.get('window');
    return { width: d.width || 390, height: d.height || 844 };
  });

  const overlayFade = useRef(new Animated.Value(0)).current;
  const cardScale = useRef(new Animated.Value(0.96)).current;
  const pulseAnim = useRef(new Animated.Value(0)).current;

  const isIos =
    typeof window !== 'undefined' &&
    Boolean(window.webkit?.messageHandlers?.iOSApp || window.__SENTINEL_PLATFORM__ === 'ios');

  const perms = useMemo(() => (visible ? readNativePermissionsStatus() : {}), [visible, stepIndex]);
  const allPermsReady = isIos
    ? Boolean(perms.notifications && (perms.backgroundLocation || perms.fineLocation))
    : Boolean(
        perms.notifications &&
          perms.backgroundLocation &&
          perms.overlay &&
          perms.batteryUnrestricted
      );

  const steps = useMemo(() => {
    const extraDrawerFeatures = canOpenMed
      ? ', abrir SENTINEL MED'
      : '';
    const extraTabs = isCommanderOrAdmin
      ? ' Además, con tu perfil tienes habilitada la vista de Disponibles y GPS de Unidades.'
      : '';

    return [
      {
        key: 'profile',
        targetKey: 'profile',
        pad: 7,
        radius: 16,
        allowPassThrough: false,
        icon: UserCheck,
        badge: 'TU PERFIL Y MENÚ LATERAL',
        title: 'Configuración y Accesos Rápidos',
        description: `Toca tu foto o nombre para abrir la barra lateral. Desde ahí puedes alternar entre Modo Oscuro y Claro, activar Silencio Total, cambiar tu clave${extraDrawerFeatures} o repetir esta guía cuando quieras.`
      },
      {
        key: 'status',
        targetKey: 'status',
        pad: 7,
        radius: 12,
        allowPassThrough: true,
        icon: Radio,
        badge: 'INTERACTIVO • PRUÉBALO AHORA',
        title: 'Tu Estado Operativo 0-9 / 0-8',
        description: isIos
          ? 'Marca 09 cuando estés disponible para concurrir y 08 cuando estés fuera de servicio. En 09 recibirás las alertas y tonos de despacho de tu institución. ¡Puedes tocar los botones iluminados para probarlo!'
          : 'Marca 09 cuando estés disponible para concurrir y 08 cuando estés fuera de servicio. En 09 las alarmas sonarán al máximo volumen. ¡Puedes tocar los botones iluminados para probarlo!'
      },
      {
        key: 'activity',
        targetKey: 'activity',
        pad: 8,
        radius: 18,
        allowPassThrough: false,
        icon: Flame,
        badge: 'CENTRAL Y DESPACHOS EN VIVO',
        title: 'Actividad y Respuesta a Emergencias',
        description:
          'Aquí verás en tiempo real quién opera la Central y el estado de las unidades. Al ingresar una emergencia aparecerá su ubicación en mapa y los botones rápidos para confirmar tu asistencia:',
        showDispatchPreview: true
      },
      {
        key: 'bottomBar',
        targetKey: 'bottomBar',
        pad: 4,
        radius: 22,
        allowPassThrough: false,
        icon: LayoutGrid,
        badge: 'NAVEGACIÓN INFERIOR',
        title: 'Órdenes, Alertas y Asistencia',
        description: `Toca cada pestaña o desliza la pantalla hacia los lados para revisar Órdenes del Día, Alertas institucionales y tu historial de Asistencia.${extraTabs}`
      },
      {
        key: 'permissions',
        targetKey: null,
        pad: 0,
        radius: 20,
        allowPassThrough: false,
        icon: ShieldCheck,
        badge: 'PASO FINAL • RECEPCIÓN 24/7',
        title: 'Recepción Confiable de Alarmas',
        description: isIos
          ? 'Para recibir el tono de despacho al instante y reportar tu posición en camino a una emergencia, mantén activas las Notificaciones y la Ubicación.'
          : 'Para que el tono de emergencia suene siempre con la pantalla bloqueada, mantén activas las Notificaciones, Ubicación en servicio, Mostrar sobre otras apps y Batería sin restricciones.',
        showPermissionsSummary: true
      }
    ];
  }, [canOpenMed, isCommanderOrAdmin, isIos]);

  const currentStep = steps[stepIndex] || steps[0];

  const updateMeasurement = useCallback(async () => {
    if (!visible) return;
    const step = steps[stepIndex];
    if (!step || !step.targetKey) {
      setRect(null);
      return;
    }
    const targetRef = targetRefs?.[step.targetKey];
    const measured = await measureTargetNode(targetRef);
    if (measured) {
      const pad = step.pad || 6;
      setRect({
        x: Math.max(4, measured.x - pad),
        y: Math.max(4, measured.y - pad),
        width: measured.width + pad * 2,
        height: measured.height + pad * 2,
        radius: step.radius || 14
      });
    } else {
      setRect(null);
    }
  }, [visible, stepIndex, steps, targetRefs]);

  useEffect(() => {
    if (!visible) {
      setStepIndex(0);
      setRect(null);
      overlayFade.setValue(0);
      return;
    }

    if (onEnsureHomeTop) {
      onEnsureHomeTop();
    }

    Animated.timing(overlayFade, {
      toValue: 1,
      duration: 260,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true
    }).start();

    const pulseLoop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulseAnim, {
          toValue: 1,
          duration: 1100,
          easing: Easing.inOut(Easing.quad),
          useNativeDriver: true
        }),
        Animated.timing(pulseAnim, {
          toValue: 0,
          duration: 1100,
          easing: Easing.inOut(Easing.quad),
          useNativeDriver: true
        })
      ])
    );
    pulseLoop.start();

    return () => {
      pulseLoop.stop();
    };
  }, [visible]);

  useEffect(() => {
    if (!visible) return;

    cardScale.setValue(0.96);
    Animated.spring(cardScale, {
      toValue: 1,
      friction: 8,
      tension: 95,
      useNativeDriver: true
    }).start();

    if (onEnsureHomeTop && stepIndex <= 2) {
      onEnsureHomeTop();
    }

    let cancelled = false;
    const t1 = setTimeout(() => {
      if (!cancelled) updateMeasurement();
    }, 45);
    const t2 = setTimeout(() => {
      if (!cancelled) updateMeasurement();
    }, 220);

    const onResize = () => {
      const d = Dimensions.get('window');
      setWinSize({ width: d.width || 390, height: d.height || 844 });
      updateMeasurement();
    };

    const dimSub = Dimensions.addEventListener?.('change', onResize);
    if (typeof window !== 'undefined') {
      window.addEventListener('resize', onResize);
    }

    return () => {
      cancelled = true;
      clearTimeout(t1);
      clearTimeout(t2);
      dimSub?.remove?.();
      if (typeof window !== 'undefined') {
        window.removeEventListener('resize', onResize);
      }
    };
  }, [visible, stepIndex, updateMeasurement, cardScale, onEnsureHomeTop]);

  const closeTour = useCallback(
    (completedAll = false) => {
      try {
        Haptics.impactAsync(
          completedAll ? Haptics.ImpactFeedbackStyle.Medium : Haptics.ImpactFeedbackStyle.Light
        ).catch(() => {});
      } catch (_) {}

      persistOnboardingFlag(GUIDED_TOUR_SEEN_KEY);
      Animated.timing(overlayFade, {
        toValue: 0,
        duration: 220,
        easing: Easing.inOut(Easing.cubic),
        useNativeDriver: true
      }).start(() => {
        if (onFinish) onFinish({ completedAll, needsPermissionsPrompt: !allPermsReady });
      });
    },
    [overlayFade, onFinish, allPermsReady]
  );

  const handleNext = () => {
    try {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light).catch(() => {});
    } catch (_) {}
    if (stepIndex < steps.length - 1) {
      setStepIndex((prev) => prev + 1);
    } else {
      closeTour(true);
    }
  };

  const handlePrev = () => {
    if (stepIndex <= 0) return;
    try {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light).catch(() => {});
    } catch (_) {}
    setStepIndex((prev) => Math.max(0, prev - 1));
  };

  if (!visible) return null;

  const safeInsets = readSafeAreaInsets();
  const safeTop = Math.max(safeInsets.top, Platform.OS === 'ios' ? 46 : 32);
  const safeBottom = Math.max(safeInsets.bottom, 18);

  const W = winSize.width;
  const H = winSize.height;

  // Cálculo de posición vertical de la tarjeta de explicación para nunca tapar el elemento iluminado ni salirse de pantalla
  let cardPlacementStyle = {
    top: Math.max(safeTop + 56, H * 0.24)
  };
  let pointerSide = null; // 'top' (flecha arriba de la tarjeta apuntando al foco superior) | 'bottom' (flecha abajo apuntando al foco inferior)
  let pointerLeft = W * 0.5 - 16;

  if (rect) {
    const targetCenterX = rect.x + rect.width * 0.5;
    pointerLeft = Math.max(30, Math.min(W - 46, targetCenterX - 8));

    const spaceBelow = H - safeBottom - (rect.y + rect.height);
    const spaceAbove = rect.y - safeTop;

    if (spaceBelow >= 235 || spaceBelow >= spaceAbove) {
      const desiredTop = rect.y + rect.height + 16;
      cardPlacementStyle = {
        top: Math.min(desiredTop, Math.max(safeTop + 52, H - safeBottom - 285))
      };
      pointerSide = 'top';
    } else {
      const desiredBottom = H - rect.y + 16;
      cardPlacementStyle = {
        bottom: Math.max(safeBottom + 14, Math.min(desiredBottom, H - safeTop - 250))
      };
      pointerSide = 'bottom';
    }
  } else {
    cardPlacementStyle = {
      top: Math.max(safeTop + 60, Math.round(H * 0.2))
    };
  }

  const StepIcon = currentStep.icon || Sparkles;
  const normalizedEstado = String(userEstado || '0-8').trim().toUpperCase();
  const isUser09 = normalizedEstado === '0-9' || normalizedEstado === '09';

  const haloScale = pulseAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [1, 1.035]
  });
  const haloOpacity = pulseAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [0.55, 1]
  });

  return (
    <Animated.View
      style={[styles.overlayRoot, { opacity: overlayFade }]}
      pointerEvents="box-none"
    >
      {/* Máscara de 4 paneles alrededor del foco (deja el componente real 100% nítido en el hueco) */}
      {rect ? (
        <>
          {/* Panel Superior */}
          <View
            style={[
              styles.maskBlock,
              { top: 0, left: 0, right: 0, height: Math.max(0, rect.y) }
            ]}
            pointerEvents="auto"
          />
          {/* Panel Inferior */}
          <View
            style={[
              styles.maskBlock,
              {
                top: Math.max(0, rect.y + rect.height),
                left: 0,
                right: 0,
                bottom: 0
              }
            ]}
            pointerEvents="auto"
          />
          {/* Panel Izquierdo */}
          <View
            style={[
              styles.maskBlock,
              {
                top: Math.max(0, rect.y),
                left: 0,
                width: Math.max(0, rect.x),
                height: Math.max(0, rect.height)
              }
            ]}
            pointerEvents="auto"
          />
          {/* Panel Derecho */}
          <View
            style={[
              styles.maskBlock,
              {
                top: Math.max(0, rect.y),
                left: Math.max(0, rect.x + rect.width),
                right: 0,
                height: Math.max(0, rect.height)
              }
            ]}
            pointerEvents="auto"
          />

          {/* Si el paso NO es interactivo, bloqueamos toques accidentales dentro del hueco; en Paso 2 (09/08) queda libre para tocar */}
          {!currentStep.allowPassThrough ? (
            <View
              style={{
                position: 'absolute',
                left: rect.x,
                top: rect.y,
                width: rect.width,
                height: rect.height,
                zIndex: 11
              }}
              pointerEvents="auto"
            />
          ) : null}

          {/* Marco luminoso pulsante alrededor del elemento real */}
          <Animated.View
            pointerEvents="none"
            style={[
              styles.spotlightRing,
              {
                left: rect.x,
                top: rect.y,
                width: rect.width,
                height: rect.height,
                borderRadius: rect.radius,
                opacity: haloOpacity,
                transform: [{ scale: haloScale }]
              }
            ]}
          />
        </>
      ) : (
        <View style={styles.fullDarkBackdrop} pointerEvents="auto" />
      )}

      {/* Botón superior derecho "Saltar guía" siempre accesible */}
      <View
        style={[styles.topBarWrap, { top: safeTop + 6 }]}
        pointerEvents="box-none"
      >
        <TouchableOpacity
          style={styles.skipTopBtn}
          onPress={() => closeTour(false)}
          activeOpacity={0.8}
        >
          <Text style={styles.skipTopBtnText}>SALTAR GUÍA</Text>
        </TouchableOpacity>
      </View>

      {/* Tarjeta flotante de explicación */}
      <Animated.View
        style={[
          styles.calloutWrap,
          cardPlacementStyle,
          { transform: [{ scale: cardScale }] }
        ]}
        pointerEvents="box-none"
      >
        {pointerSide === 'top' ? (
          <View style={[styles.pointerArrowTop, { left: pointerLeft - 16 }]} />
        ) : null}

        <View style={styles.calloutCard} pointerEvents="auto">
          {/* Encabezado de paso */}
          <View style={styles.cardHeaderRow}>
            <View style={styles.stepBadgePill}>
              <StepIcon size={13} color="#F87171" style={{ marginRight: 6 }} />
              <Text style={styles.stepBadgeText}>{currentStep.badge}</Text>
            </View>
            <Text style={styles.stepCounterText}>
              {stepIndex + 1} / {steps.length}
            </Text>
          </View>

          <Text style={styles.cardTitle}>{currentStep.title}</Text>
          <Text style={styles.cardDescription}>{currentStep.description}</Text>

          {/* Retroalimentación en vivo cuando el bombero prueba 09/08 en el Paso 2 */}
          {currentStep.key === 'status' ? (
            <View
              style={[
                styles.liveStatusFeedback,
                {
                  borderColor: isUser09
                    ? 'rgba(16, 185, 129, 0.45)'
                    : 'rgba(239, 68, 68, 0.45)',
                  backgroundColor: isUser09
                    ? 'rgba(6, 78, 59, 0.32)'
                    : 'rgba(69, 10, 10, 0.34)'
                }
              ]}
            >
              <View
                style={[
                  styles.liveDot,
                  { backgroundColor: isUser09 ? '#10B981' : '#EF4444' }
                ]}
              />
              <Text style={styles.liveStatusText}>
                Estado actual:{' '}
                <Text
                  style={{
                    fontWeight: '900',
                    color: isUser09 ? '#34D399' : '#F87171'
                  }}
                >
                  {isUser09 ? '0-9 DISPONIBLE' : `${normalizedEstado} NO DISPONIBLE`}
                </Text>
              </Text>
            </View>
          ) : null}

          {/* Mini vista previa visual de confirmación de emergencia en Paso 3 */}
          {currentStep.showDispatchPreview ? (
            <View style={styles.dispatchPreviewBox}>
              <View style={styles.previewBtnYes}>
                <Text style={styles.previewBtnYesText}>ASISTIR</Text>
              </View>
              <View style={styles.previewBtnNo}>
                <Text style={styles.previewBtnNoText}>NO ASISTIR</Text>
              </View>
            </View>
          ) : null}

          {/* Resumen rápido de permisos en Paso 5 */}
          {currentStep.showPermissionsSummary ? (
            <View style={styles.permsMiniList}>
              <View style={styles.permMiniRow}>
                <Bell
                  size={14}
                  color={perms.notifications ? '#10B981' : '#F87171'}
                />
                <Text style={styles.permMiniLabel}>Alertas y Sonido de Despacho</Text>
                {perms.notifications ? (
                  <CheckCircle2 size={14} color="#10B981" />
                ) : (
                  <Text style={styles.permPendingTag}>PENDIENTE</Text>
                )}
              </View>

              <View style={styles.permMiniRow}>
                <MapPin
                  size={14}
                  color={
                    perms.backgroundLocation || perms.fineLocation
                      ? '#10B981'
                      : '#F87171'
                  }
                />
                <Text style={styles.permMiniLabel}>Ubicación GPS en Emergencias</Text>
                {perms.backgroundLocation || perms.fineLocation ? (
                  <CheckCircle2 size={14} color="#10B981" />
                ) : (
                  <Text style={styles.permPendingTag}>PENDIENTE</Text>
                )}
              </View>

              {!isIos ? (
                <View style={styles.permMiniRow}>
                  <Zap
                    size={14}
                    color={
                      perms.overlay && perms.batteryUnrestricted
                        ? '#10B981'
                        : '#F87171'
                    }
                  />
                  <Text style={styles.permMiniLabel}>
                    Pantalla de Bloqueo y Batería 24/7
                  </Text>
                  {perms.overlay && perms.batteryUnrestricted ? (
                    <CheckCircle2 size={14} color="#10B981" />
                  ) : (
                    <Text style={styles.permPendingTag}>PENDIENTE</Text>
                  )}
                </View>
              ) : null}
            </View>
          ) : null}

          {/* Barra inferior de la tarjeta: puntos de progreso + Anterior / Siguiente */}
          <View style={styles.cardFooterRow}>
            <View style={styles.dotsRow}>
              {steps.map((s, idx) => (
                <View
                  key={s.key}
                  style={[
                    styles.dotItem,
                    idx === stepIndex && styles.dotItemActive
                  ]}
                />
              ))}
            </View>

            <View style={styles.navButtonsRow}>
              {stepIndex > 0 ? (
                <TouchableOpacity
                  style={styles.prevBtn}
                  onPress={handlePrev}
                  activeOpacity={0.78}
                >
                  <ChevronLeft size={16} color="#CBD5E1" />
                </TouchableOpacity>
              ) : null}

              <TouchableOpacity
                style={styles.nextBtn}
                onPress={handleNext}
                activeOpacity={0.85}
              >
                <Text style={styles.nextBtnText}>
                  {stepIndex === steps.length - 1
                    ? allPermsReady
                      ? '¡COMENZAR!'
                      : 'CONTINUAR'
                    : 'SIGUIENTE'}
                </Text>
                <ChevronRight size={15} color="#FFFFFF" style={{ marginLeft: 3 }} />
              </TouchableOpacity>
            </View>
          </View>
        </View>

        {pointerSide === 'bottom' ? (
          <View style={[styles.pointerArrowBottom, { left: pointerLeft - 16 }]} />
        ) : null}
      </Animated.View>
    </Animated.View>
  );
};

const styles = StyleSheet.create({
  overlayRoot: {
    ...StyleSheet.absoluteFillObject,
    zIndex: 9500,
    elevation: 9500
  },
  maskBlock: {
    position: 'absolute',
    backgroundColor: 'rgba(4, 7, 15, 0.82)',
    zIndex: 10
  },
  fullDarkBackdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(4, 7, 15, 0.86)',
    zIndex: 10
  },
  spotlightRing: {
    position: 'absolute',
    borderWidth: 2,
    borderColor: '#EF4444',
    backgroundColor: 'transparent',
    shadowColor: '#EF4444',
    shadowOffset: { width: 0, height: 0 },
    shadowOpacity: 0.85,
    shadowRadius: 14,
    zIndex: 12
  },
  topBarWrap: {
    position: 'absolute',
    right: 16,
    zIndex: 30
  },
  skipTopBtn: {
    paddingHorizontal: 13,
    paddingVertical: 7,
    borderRadius: 999,
    backgroundColor: 'rgba(15, 23, 42, 0.88)',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.18)'
  },
  skipTopBtnText: {
    color: '#E2E8F0',
    fontSize: 10.5,
    fontWeight: '800',
    letterSpacing: 1
  },
  calloutWrap: {
    position: 'absolute',
    left: 16,
    right: 16,
    zIndex: 25
  },
  calloutCard: {
    backgroundColor: 'rgba(13, 19, 33, 0.97)',
    borderRadius: 20,
    padding: 18,
    borderWidth: 1,
    borderColor: 'rgba(239, 68, 68, 0.38)',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 12 },
    shadowOpacity: 0.6,
    shadowRadius: 22,
    elevation: 24
  },
  pointerArrowTop: {
    width: 14,
    height: 14,
    backgroundColor: 'rgba(13, 19, 33, 0.97)',
    borderTopWidth: 1,
    borderLeftWidth: 1,
    borderColor: 'rgba(239, 68, 68, 0.45)',
    transform: [{ rotate: '45deg' }],
    marginBottom: -7,
    zIndex: 26
  },
  pointerArrowBottom: {
    width: 14,
    height: 14,
    backgroundColor: 'rgba(13, 19, 33, 0.97)',
    borderBottomWidth: 1,
    borderRightWidth: 1,
    borderColor: 'rgba(239, 68, 68, 0.45)',
    transform: [{ rotate: '45deg' }],
    marginTop: -7,
    zIndex: 26
  },
  cardHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 10
  },
  stepBadgePill: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 999,
    backgroundColor: 'rgba(185, 28, 28, 0.22)',
    borderWidth: 1,
    borderColor: 'rgba(248, 113, 113, 0.35)'
  },
  stepBadgeText: {
    color: '#FCA5A5',
    fontSize: 9.5,
    fontWeight: '800',
    letterSpacing: 0.8
  },
  stepCounterText: {
    color: '#94A3B8',
    fontSize: 11,
    fontWeight: '800'
  },
  cardTitle: {
    color: '#FFFFFF',
    fontSize: 16.5,
    fontWeight: '800',
    marginBottom: 6,
    letterSpacing: 0.2
  },
  cardDescription: {
    color: '#CBD5E1',
    fontSize: 13,
    lineHeight: 19,
    fontWeight: '500'
  },
  liveStatusFeedback: {
    marginTop: 12,
    paddingVertical: 8,
    paddingHorizontal: 12,
    borderRadius: 10,
    borderWidth: 1,
    flexDirection: 'row',
    alignItems: 'center'
  },
  liveDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 8
  },
  liveStatusText: {
    color: '#E2E8F0',
    fontSize: 12,
    fontWeight: '600'
  },
  dispatchPreviewBox: {
    marginTop: 12,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8
  },
  previewBtnYes: {
    flex: 1,
    paddingVertical: 8,
    borderRadius: 9,
    backgroundColor: 'rgba(22, 163, 74, 0.28)',
    borderWidth: 1,
    borderColor: 'rgba(34, 197, 94, 0.55)',
    alignItems: 'center'
  },
  previewBtnYesText: {
    color: '#86EFAC',
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 0.6
  },
  previewBtnNo: {
    flex: 1,
    paddingVertical: 8,
    borderRadius: 9,
    backgroundColor: 'rgba(185, 28, 28, 0.24)',
    borderWidth: 1,
    borderColor: 'rgba(239, 68, 68, 0.5)',
    alignItems: 'center'
  },
  previewBtnNoText: {
    color: '#FCA5A5',
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 0.6
  },
  permsMiniList: {
    marginTop: 12,
    gap: 7
  },
  permMiniRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(30, 41, 59, 0.5)',
    borderRadius: 10,
    paddingVertical: 8,
    paddingHorizontal: 11,
    borderWidth: 1,
    borderColor: 'rgba(148, 163, 184, 0.16)'
  },
  permMiniLabel: {
    flex: 1,
    color: '#E2E8F0',
    fontSize: 11.5,
    fontWeight: '600',
    marginLeft: 9
  },
  permPendingTag: {
    color: '#FCA5A5',
    fontSize: 9.5,
    fontWeight: '800',
    letterSpacing: 0.6
  },
  cardFooterRow: {
    marginTop: 16,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between'
  },
  dotsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6
  },
  dotItem: {
    width: 7,
    height: 7,
    borderRadius: 3.5,
    backgroundColor: 'rgba(148, 163, 184, 0.32)'
  },
  dotItemActive: {
    width: 20,
    backgroundColor: '#EF4444'
  },
  navButtonsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8
  },
  prevBtn: {
    width: 36,
    height: 36,
    borderRadius: 10,
    backgroundColor: 'rgba(30, 41, 59, 0.75)',
    borderWidth: 1,
    borderColor: 'rgba(148, 163, 184, 0.24)',
    alignItems: 'center',
    justifyContent: 'center'
  },
  nextBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    height: 36,
    paddingLeft: 15,
    paddingRight: 11,
    borderRadius: 10,
    backgroundColor: '#B91C1C',
    shadowColor: '#DC2626',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.35,
    shadowRadius: 8,
    elevation: 5
  },
  nextBtnText: {
    color: '#FFFFFF',
    fontSize: 11.5,
    fontWeight: '800',
    letterSpacing: 0.9
  }
});
