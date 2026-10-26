import React, { useEffect, useState, useCallback } from 'react';
import { View, Text, StyleSheet, Modal, TouchableOpacity, ScrollView } from 'react-native';
import {
  MapPin,
  CheckCircle2,
  Layers,
  Zap,
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
          notifications: Boolean(p.notifications ?? true)
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

  const refreshPerms = useCallback(() => {
    const next = readNativePermissionsStatus();
    setPerms(next);
    return next;
  }, []);

  useEffect(() => {
    if (!visible) return;
    refreshPerms();

    const handler = () => {
      const updated = refreshPerms();
      if (updated.backgroundLocation && updated.overlay && updated.batteryUnrestricted) {
        setTimeout(() => {
          if (onAccept) onAccept(updated);
        }, 450);
      }
    };

    if (typeof window !== 'undefined') {
      window.addEventListener('sentinelPermissionsChanged', handler);
    }
    const interval = setInterval(refreshPerms, 1200);

    return () => {
      if (typeof window !== 'undefined') {
        window.removeEventListener('sentinelPermissionsChanged', handler);
      }
      clearInterval(interval);
    };
  }, [visible, refreshPerms, onAccept]);

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

  const handlePrimaryAction = () => {
    const current = refreshPerms();

    if (typeof window !== 'undefined' && window.AndroidApp) {
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
      if (!current.backgroundLocation) {
        handleRequestGpsAlways();
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

          {/* Contenido con texto justificado de lado a lado y sin tarjeta roja */}
          <ScrollView
            style={styles.scrollArea}
            contentContainerStyle={styles.scrollContent}
            showsVerticalScrollIndicator={false}
          >
            <Text style={styles.sectionHeading}>
              ¿Por qué Sentinel One utiliza su GPS?
            </Text>

            <Text style={styles.justifiedParagraph}>
              Sentinel One recopila datos de ubicación en tiempo real para permitir el despacho de emergencias, el cálculo de rutas al cuartel o incidente y la confirmación de llegada y seguridad del bombero, incluso cuando la aplicación está cerrada o no está en uso.
            </Text>

            <Text style={styles.justifiedParagraph}>
              <Text style={styles.boldLabel}>Despacho y seguridad: </Text>
              Permite a la central y al OBAC conocer el arribo de bomberos y su ubicación en el perímetro de la emergencia.
            </Text>

            <Text style={styles.justifiedParagraph}>
              <Text style={styles.boldLabel}>Privacidad protegida: </Text>
              Sus datos de ubicación son de uso exclusivo institucional para emergencias y jamás se comparten con terceros ni fines publicitarios.
            </Text>

            <View style={styles.sectionDivider} />

            {/* Lista de los 3 Permisos Requeridos */}
            <Text style={styles.sectionTitle}>Permisos requeridos para operar</Text>

            {/* 1. Ubicación GPS: Permitir siempre */}
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
                <Text style={styles.permTitle}>Ubicación GPS: Permitir siempre</Text>
                <Text style={styles.permDesc}>
                  Requerido para despacho y ruta en emergencia.
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

            {/* 2. Mostrar sobre otras aplicaciones */}
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
                <Text style={styles.permTitle}>Mostrar sobre otras aplicaciones</Text>
                <Text style={styles.permDesc}>
                  Muestra la alarma de despacho sobre otras pantallas.
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

            {/* 3. Encendido y segundo plano 24/7 */}
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
                <Text style={styles.permTitle}>Encendido y segundo plano 24/7</Text>
                <Text style={styles.permDesc}>
                  Activo sin restricciones en segundo plano.
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
          </ScrollView>

          {/* Botones de Acción: Aceptar y No acepto */}
          <View style={styles.footer}>
            <TouchableOpacity
              style={styles.acceptButton}
              activeOpacity={0.85}
              onPress={handlePrimaryAction}
            >
              <Text style={styles.acceptButtonText}>ACEPTAR</Text>
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
    fontSize: 13,
    lineHeight: 20,
    color: '#CBD5E1',
    textAlign: 'justify',
    marginBottom: 12
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
    paddingVertical: 12,
    paddingHorizontal: 12,
    borderRadius: 14,
    borderWidth: 1,
    marginBottom: 10
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
    fontSize: 11.5,
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
