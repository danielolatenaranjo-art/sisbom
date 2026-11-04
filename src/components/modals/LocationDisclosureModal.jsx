import React, { useEffect, useState, useCallback } from 'react';
import { View, Text, StyleSheet, Modal, TouchableOpacity, ScrollView } from 'react-native';
import {
  MapPin,
  CheckCircle2,
  Layers,
  Zap,
  Bell,
  ChevronRight
} from 'lucide-react-native';

export function readNativePermissionsStatus() {
  try {
    if (typeof window !== 'undefined') {
      if (window.AndroidApp?.getPermissionsStatusJson) {
        const raw = window.AndroidApp.getPermissionsStatusJson();
        if (raw) {
          const parsed = JSON.parse(raw);
          return {
            fineLocation: Boolean(parsed.fineLocation),
            backgroundLocation: Boolean(parsed.backgroundLocation),
            overlay: Boolean(parsed.overlay),
            batteryUnrestricted: Boolean(parsed.batteryUnrestricted),
            notifications: Boolean(parsed.notifications)
          };
        }
      }
      if (window.__SENTINEL_IOS_PERMS__) {
        const p = window.__SENTINEL_IOS_PERMS__;
        return {
          fineLocation: Boolean(p.fineLocation),
          backgroundLocation: Boolean(p.backgroundLocation),
          overlay: true,
          batteryUnrestricted: true,
          notifications: Boolean(p.notifications)
        };
      }
    }
  } catch (e) {}
  return {
    fineLocation: false,
    backgroundLocation: false,
    overlay: false,
    batteryUnrestricted: false,
    notifications: false
  };
}

export const LocationDisclosureModal = ({ visible, onAccept, onDecline }) => {
  const [perms, setPerms] = useState(() => readNativePermissionsStatus());
  const isIos =
    typeof window !== 'undefined' &&
    Boolean(window.webkit?.messageHandlers?.iOSApp || window.__SENTINEL_PLATFORM__ === 'ios');

  const refreshPerms = useCallback(() => {
    const next = readNativePermissionsStatus();
    setPerms(next);
    return next;
  }, []);

  useEffect(() => {
    if (!visible) return;

    const checkAndAutoClose = () => {
      const updated = refreshPerms();
      if (
        updated.notifications &&
        updated.backgroundLocation &&
        updated.overlay &&
        updated.batteryUnrestricted
      ) {
        if (onAccept) onAccept(updated);
        return true;
      }
      return false;
    };

    if (checkAndAutoClose()) return;

    const handler = () => {
      checkAndAutoClose();
    };

    if (typeof window !== 'undefined') {
      window.addEventListener('sentinelPermissionsChanged', handler);
    }
    const interval = setInterval(checkAndAutoClose, 1000);

    return () => {
      if (typeof window !== 'undefined') {
        window.removeEventListener('sentinelPermissionsChanged', handler);
      }
      clearInterval(interval);
    };
  }, [visible, refreshPerms, onAccept]);

  const handleRequestNotifications = () => {
    try {
      if (typeof window !== 'undefined') {
        if (window.AndroidApp?.requestNotificationsPermission) {
          window.AndroidApp.requestNotificationsPermission();
          return;
        }
        if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
          window.webkit.messageHandlers.iOSApp.postMessage({
            action: 'requestNotificationsPermission'
          });
          return;
        }
      }
    } catch (e) {}
  };

  const handleRequestGpsAlways = () => {
    try {
      if (typeof window !== 'undefined') {
        if (window.AndroidApp?.requestLocationAlways) {
          window.AndroidApp.requestLocationAlways();
          return;
        }
        if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
          window.webkit.messageHandlers.iOSApp.postMessage({ action: 'requestLocationAlways' });
          return;
        }
      }
    } catch (e) {}
  };

  const handleRequestOverlay = () => {
    try {
      if (typeof window !== 'undefined' && window.AndroidApp?.requestOverlayPermission) {
        window.AndroidApp.requestOverlayPermission();
      }
    } catch (e) {}
  };

  const handleRequestBattery = () => {
    try {
      if (typeof window !== 'undefined' && window.AndroidApp?.requestBatteryUnrestricted) {
        window.AndroidApp.requestBatteryUnrestricted();
      }
    } catch (e) {}
  };

  const allRequiredGranted = isIos
    ? Boolean(perms.notifications && perms.backgroundLocation)
    : Boolean(
        perms.notifications &&
          perms.backgroundLocation &&
          perms.overlay &&
          perms.batteryUnrestricted
      );

  const getPrimaryButtonLabel = () => {
    if (allRequiredGranted) return 'CONTINUAR A LA APP';
    if (!perms.notifications) return 'ACTIVAR NOTIFICACIONES';
    if (!perms.backgroundLocation) {
      return isIos ? 'ACTIVAR UBICACIÓN (SIEMPRE)' : 'ACTIVAR UBICACIÓN (TODO EL TIEMPO)';
    }
    if (!isIos && !perms.overlay) return 'ACTIVAR MOSTRAR SOBRE OTRAS APPS';
    if (!isIos && !perms.batteryUnrestricted) return 'ACTIVAR BATERÍA SIN RESTRICCIÓN';
    return 'ACEPTAR Y ACTIVAR PERMISOS';
  };

  const handlePrimaryAction = () => {
    const current = refreshPerms();

    if (typeof window !== 'undefined' && window.AndroidApp) {
      if (!current.notifications) {
        handleRequestNotifications();
        return;
      }
      if (!current.backgroundLocation) {
        handleRequestGpsAlways();
        return;
      }
      if (!current.overlay) {
        handleRequestOverlay();
        return;
      }
      if (!current.batteryUnrestricted) {
        handleRequestBattery();
        return;
      }
    } else if (typeof window !== 'undefined' && window.webkit?.messageHandlers?.iOSApp?.postMessage) {
      if (!current.notifications) {
        handleRequestNotifications();
        return;
      }
      if (!current.backgroundLocation) {
        handleRequestGpsAlways();
        return;
      }
    }

    if (onAccept) onAccept(current);
  };

  return (
    <Modal
      visible={visible}
      transparent={true}
      animationType="fade"
      onRequestClose={onDecline}
    >
      <View style={styles.overlay}>
        <View style={styles.card}>
          {/* Cabecera */}
          <View style={styles.header}>
            <View style={styles.iconCircle}>
              <MapPin size={22} color="#E2E8F0" />
            </View>
            <View style={styles.headerText}>
              <Text style={styles.title}>Aviso de uso de ubicación y permisos</Text>
            </View>
          </View>

          <View style={styles.headerDivider} />

          {/* Contenido con texto justificado de lado a lado */}
          <ScrollView
            style={styles.scrollArea}
            contentContainerStyle={styles.scrollContent}
            showsVerticalScrollIndicator={false}
          >
            <Text style={styles.sectionHeading}>
              ¿Por qué Sentinel One utiliza sus permisos y GPS?
            </Text>

            <Text style={styles.justifiedParagraph}>
              Sentinel One recibe alertas críticas de emergencia y recopila datos de ubicación en tiempo real para permitir el despacho, el cálculo de rutas al cuartel o incidente y la confirmación de llegada y seguridad del bombero, incluso cuando la aplicación está cerrada o en segundo plano.
            </Text>

            <Text style={styles.justifiedParagraph}>
              <Text style={styles.boldLabel}>Despacho y seguridad: </Text>
              Permite recibir el tono de alarma y conocer el arribo de bomberos y su ubicación en el perímetro de la emergencia.
            </Text>

            <Text style={styles.justifiedParagraph}>
              <Text style={styles.boldLabel}>Privacidad protegida: </Text>
              Sus datos de ubicación son de uso exclusivo institucional para emergencias y jamás se comparten con terceros ni fines publicitarios.
            </Text>

            <View style={styles.sectionDivider} />

            {/* Lista de Permisos Requeridos según Sistema Operativo */}
            <Text style={styles.sectionTitle}>
              {isIos
                ? 'Active estos 2 permisos en su iPhone para continuar:'
                : 'Active estos 4 permisos en su teléfono para continuar:'}
            </Text>

            {/* 1. Notificaciones de Emergencia (Android e iOS) */}
            <TouchableOpacity
              style={[
                styles.permCard,
                perms.notifications ? styles.permCardActive : styles.permCardPending
              ]}
              activeOpacity={0.8}
              onPress={handleRequestNotifications}
            >
              <View style={styles.permIconBox}>
                <Bell size={18} color="#CBD5E1" />
              </View>
              <View style={styles.permInfo}>
                <Text style={styles.permTitle}>1. Notificaciones de Emergencia</Text>
                <Text style={styles.permDesc}>
                  {isIos
                    ? 'Permite recibir alarmas de despacho y avisos de la Central. En iPhone marque "Permitir".'
                    : 'Permite sonar y mostrar alertas de despacho y cambios de estado.'}
                </Text>
              </View>
              {perms.notifications ? (
                <CheckCircle2 size={20} color="#10B981" />
              ) : (
                <View style={styles.activatePill}>
                  <Text style={styles.activatePillText}>Activar</Text>
                  <ChevronRight size={13} color="#FFFFFF" />
                </View>
              )}
            </TouchableOpacity>

            {/* 2. Ubicación GPS: Permitir siempre / Todo el tiempo */}
            <TouchableOpacity
              style={[
                styles.permCard,
                perms.backgroundLocation ? styles.permCardActive : styles.permCardPending
              ]}
              activeOpacity={0.8}
              onPress={handleRequestGpsAlways}
            >
              <View style={styles.permIconBox}>
                <MapPin size={18} color="#CBD5E1" />
              </View>
              <View style={styles.permInfo}>
                <Text style={styles.permTitle}>
                  {isIos
                    ? '2. Ubicación GPS: "Siempre"'
                    : '2. Ubicación GPS: "Permitir todo el tiempo"'}
                </Text>
                <Text style={styles.permDesc}>
                  {isIos
                    ? perms.fineLocation && !perms.backgroundLocation
                      ? 'Tiene "Al usar la app". Presione aquí y en Configuración -> Ubicación seleccione "Siempre".'
                      : 'Presione Activar: primero elija "Al usar la app" y luego "Cambiar a permitir siempre" (o en Configuración -> Ubicación -> Siempre).'
                    : perms.fineLocation && !perms.backgroundLocation
                      ? 'Falta el permiso en segundo plano. Presione aquí y en Ubicación elija "Permitir todo el tiempo".'
                      : 'Al abrirse los ajustes de Ubicación, debe elegir la opción "Permitir todo el tiempo".'}
                </Text>
              </View>
              {perms.backgroundLocation ? (
                <CheckCircle2 size={20} color="#10B981" />
              ) : (
                <View style={styles.activatePill}>
                  <Text style={styles.activatePillText}>Activar</Text>
                  <ChevronRight size={13} color="#FFFFFF" />
                </View>
              )}
            </TouchableOpacity>

            {/* 3. Mostrar sobre otras aplicaciones (Exclusivo Android) */}
            {!isIos && (
              <TouchableOpacity
                style={[
                  styles.permCard,
                  perms.overlay ? styles.permCardActive : styles.permCardPending
                ]}
                activeOpacity={0.8}
                onPress={handleRequestOverlay}
              >
                <View style={styles.permIconBox}>
                  <Layers size={18} color="#CBD5E1" />
                </View>
                <View style={styles.permInfo}>
                  <Text style={styles.permTitle}>3. Mostrar sobre otras aplicaciones</Text>
                  <Text style={styles.permDesc}>
                    Busque "SENTINEL ONE" en la lista y active el interruptor para ver la alarma sobre pantalla bloqueada.
                  </Text>
                </View>
                {perms.overlay ? (
                  <CheckCircle2 size={20} color="#10B981" />
                ) : (
                  <View style={styles.activatePill}>
                    <Text style={styles.activatePillText}>Activar</Text>
                    <ChevronRight size={13} color="#FFFFFF" />
                  </View>
                )}
              </TouchableOpacity>
            )}

            {/* 4. Encendido y segundo plano 24/7 (Exclusivo Android) */}
            {!isIos && (
              <TouchableOpacity
                style={[
                  styles.permCard,
                  perms.batteryUnrestricted ? styles.permCardActive : styles.permCardPending
                ]}
                activeOpacity={0.8}
                onPress={handleRequestBattery}
              >
                <View style={styles.permIconBox}>
                  <Zap size={18} color="#CBD5E1" />
                </View>
                <View style={styles.permInfo}>
                  <Text style={styles.permTitle}>4. Batería sin restricciones 24/7</Text>
                  <Text style={styles.permDesc}>
                    Presione "Permitir" para que el teléfono no apague la alarma en la noche.
                  </Text>
                </View>
                {perms.batteryUnrestricted ? (
                  <CheckCircle2 size={20} color="#10B981" />
                ) : (
                  <View style={styles.activatePill}>
                    <Text style={styles.activatePillText}>Activar</Text>
                    <ChevronRight size={13} color="#FFFFFF" />
                  </View>
                )}
              </TouchableOpacity>
            )}
          </ScrollView>

          {/* Botones de Acción: Paso a Paso / Continuar y No acepto */}
          <View style={styles.footer}>
            <TouchableOpacity
              style={[
                styles.acceptButton,
                allRequiredGranted && styles.acceptButtonReady
              ]}
              activeOpacity={0.85}
              onPress={handlePrimaryAction}
            >
              <Text style={styles.acceptButtonText}>{getPrimaryButtonLabel()}</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.declineButton}
              activeOpacity={0.75}
              onPress={onDecline}
            >
              <Text style={styles.declineButtonText}>NO ACEPTO</Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(6, 9, 17, 0.96)',
    paddingHorizontal: 10,
    paddingTop: 38,
    paddingBottom: 16
  },
  card: {
    flex: 1,
    width: '100%',
    backgroundColor: '#0B1120',
    borderRadius: 24,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.14)',
    paddingHorizontal: 20,
    paddingTop: 22,
    paddingBottom: 18,
    justifyContent: 'space-between'
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center'
  },
  iconCircle: {
    width: 42,
    height: 42,
    borderRadius: 12,
    backgroundColor: 'rgba(255, 255, 255, 0.06)',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.14)',
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12
  },
  headerText: {
    flex: 1
  },
  title: {
    fontSize: 17,
    fontWeight: '800',
    color: '#FFFFFF',
    letterSpacing: 0.2
  },
  headerDivider: {
    height: 1,
    backgroundColor: 'rgba(255, 255, 255, 0.10)',
    marginVertical: 14
  },
  scrollArea: {
    flex: 1
  },
  scrollContent: {
    paddingBottom: 8
  },
  sectionHeading: {
    fontSize: 14.5,
    fontWeight: '800',
    color: '#FFFFFF',
    marginBottom: 8
  },
  justifiedParagraph: {
    fontSize: 12.5,
    lineHeight: 19,
    color: '#CBD5E1',
    textAlign: 'justify',
    marginBottom: 10
  },
  boldLabel: {
    fontWeight: '800',
    color: '#FFFFFF'
  },
  sectionDivider: {
    height: 1,
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    marginVertical: 10
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: '800',
    color: '#FFFFFF',
    marginBottom: 10
  },
  permCard: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 11,
    paddingHorizontal: 12,
    borderRadius: 14,
    borderWidth: 1,
    marginBottom: 9
  },
  permCardPending: {
    backgroundColor: 'rgba(30, 41, 59, 0.55)',
    borderColor: 'rgba(255, 255, 255, 0.12)'
  },
  permCardActive: {
    backgroundColor: 'rgba(30, 41, 59, 0.75)',
    borderColor: 'rgba(16, 185, 129, 0.35)'
  },
  permIconBox: {
    width: 36,
    height: 36,
    borderRadius: 10,
    backgroundColor: 'rgba(255, 255, 255, 0.06)',
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12
  },
  permInfo: {
    flex: 1,
    paddingRight: 8
  },
  permTitle: {
    fontSize: 13,
    fontWeight: '800',
    color: '#F8FAFC'
  },
  permDesc: {
    fontSize: 11.2,
    lineHeight: 15.5,
    color: '#94A3B8',
    marginTop: 2
  },
  activatePill: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(255, 255, 255, 0.12)',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.20)',
    paddingVertical: 6,
    paddingHorizontal: 10,
    borderRadius: 8
  },
  activatePillText: {
    color: '#FFFFFF',
    fontSize: 11,
    fontWeight: '700',
    marginRight: 2
  },
  footer: {
    paddingTop: 12,
    gap: 10
  },
  acceptButton: {
    backgroundColor: '#1E293B',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.22)',
    borderRadius: 14,
    paddingVertical: 14,
    alignItems: 'center',
    justifyContent: 'center'
  },
  acceptButtonReady: {
    backgroundColor: '#059669',
    borderColor: 'rgba(16, 185, 129, 0.5)'
  },
  acceptButtonText: {
    color: '#FFFFFF',
    fontSize: 13.5,
    fontWeight: '800',
    letterSpacing: 0.6
  },
  declineButton: {
    backgroundColor: 'rgba(255, 255, 255, 0.04)',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.10)',
    borderRadius: 14,
    paddingVertical: 12,
    alignItems: 'center',
    justifyContent: 'center'
  },
  declineButtonText: {
    color: '#94A3B8',
    fontSize: 13,
    fontWeight: '700',
    letterSpacing: 0.5
  }
});
