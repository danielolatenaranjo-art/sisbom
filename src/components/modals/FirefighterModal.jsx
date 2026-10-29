import React, { useState } from 'react';
import { View, Text, StyleSheet, Modal, TouchableOpacity, Image, Linking } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { X, MessageCircle, PhoneCall } from 'lucide-react-native';
import { doc } from 'firebase/firestore';
import { updateDoc } from '../../config/firestoreMeta';
import { getDb } from '../../config/firebase';
import { useAuthStore } from '../../store/useAuthStore';
import { useLicenseStore } from '../../store/useLicenseStore';
import { useDispatchStore } from '../../store/useDispatchStore';

// Formato de Teléfono Chileno: +56 9 XXXX XXXX
function formatChileanPhone(rawPhone) {
  if (!rawPhone) return 'NO REGISTRADO';
  const digits = String(rawPhone).replace(/\D/g, '');
  if (!digits) return 'NO REGISTRADO';
  let nineDigits = '';
  if (digits.startsWith('569')) {
    nineDigits = digits.slice(2);
  } else if (digits.startsWith('9')) {
    nineDigits = digits;
  } else if (digits.length === 8) {
    nineDigits = '9' + digits;
  } else {
    nineDigits = digits.slice(-9);
  }
  if (nineDigits.length >= 9) {
    const part1 = nineDigits.slice(1, 5);
    const part2 = nineDigits.slice(5, 9);
    return `+56 9 ${part1} ${part2}`;
  }
  return rawPhone;
}

export const FirefighterModal = ({ visible, bombero, onClose }) => {
  const { user } = useAuthStore();
  const { logoUrl } = useLicenseStore();
  const { isCentralActive } = useDispatchStore();
  const [updatingStatus, setUpdatingStatus] = useState(false);

  if (!bombero) return null;

  const est = String(bombero.estado || '0-8').toUpperCase();
  const is09 = est === '0-9';
  const phoneFormatted = formatChileanPhone(bombero.telefono);

  const myRadial = String(user?.idRadial || '').trim();
  const radialNum = parseInt(myRadial.replace(/\D/g, ''), 10) || 0;
  const isComandante = myRadial === '1' || user?.cargo?.toUpperCase().includes('COMANDANTE');
  const isCapitan = radialNum >= 40 && radialNum <= 49;
  const canManageStatus = isComandante || isCapitan || isCentralActive;

  const getPhotoUri = (foto) => {
    if (!foto) return null;
    const str = String(foto).trim();
    if (str.startsWith('http') || str.startsWith('data:image')) return str;
    return `data:image/jpeg;base64,${str}`;
  };
  const photoUri = getPhotoUri(bombero.foto);

  const handleOpenWhatsApp = () => {
    const digits = String(bombero.telefono || '').replace(/\D/g, '');
    if (!digits) return;
    let cleanNumber = digits;
    if (digits.startsWith('569')) {
      cleanNumber = digits;
    } else if (digits.startsWith('9')) {
      cleanNumber = '56' + digits;
    } else {
      cleanNumber = '569' + digits.slice(-8);
    }

    const url = `https://wa.me/${cleanNumber}`;
    Linking.openURL(url).catch((err) => {
      console.warn('Error abriendo WhatsApp:', err);
    });
  };

  const handleCallPhone = () => {
    const digits = String(bombero.telefono || '').replace(/\D/g, '');
    if (!digits) return;
    Linking.openURL(`tel:+56${digits.startsWith('56') ? digits.slice(2) : digits}`).catch(() => {});
  };

  const handleChangeBomberoStatus = async (newEstado) => {
    if (updatingStatus) return;
    setUpdatingStatus(true);
    try {
      const db = getDb();
      if (db && bombero.idRegistro) {
        const nowTs = Date.now();
        const payload = {
          estado: newEstado,
          permiso: newEstado === 'PERMISO',
          licenciaMedica: newEstado === 'LICENCIA',
          cds: newEstado === 'CDS',
          _metaTimestamp: nowTs,
          _fieldTimestamps: {
            estado: nowTs,
            permiso: nowTs,
            licenciaMedica: nowTs,
            cds: nowTs
          }
        };
        await updateDoc(doc(db, 'personal', String(bombero.idRegistro)), payload);
      }
      onClose();
    } catch (e) {
      console.warn('Error actualizando estado de bombero:', e);
    } finally {
      setUpdatingStatus(false);
    }
  };

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <View style={styles.overlay}>
        <View style={styles.card}>
          <LinearGradient
            colors={['#1c1924', '#121118']}
            style={styles.gradient}
          >
            {/* Header del Modal con Foto / Fallback Logo Sentinel */}
            <View style={styles.header}>
              <View style={styles.photoWrapper}>
                <Image
                  source={
                    photoUri
                      ? { uri: photoUri }
                      : logoUrl
                        ? { uri: logoUrl }
                        : require('../../../assets/LOGO_SENTINEL.png')
                  }
                  style={styles.photoImage}
                  resizeMode="cover"
                />
              </View>

              <View style={{ flex: 1, marginHorizontal: 12 }}>
                <View style={styles.nameRow}>
                  <View style={[styles.miniRadialBadge, is09 ? styles.badge09 : styles.badge08]}>
                    <Text style={styles.miniRadialText}>{bombero.idRadial}</Text>
                  </View>
                  <Text style={styles.nameText} numberOfLines={2}>
                    {bombero.nombre || bombero.nombreBombero}
                  </Text>
                </View>
                <Text style={styles.cargoText}>{bombero.cargo || 'VOLUNTARIO'}</Text>
              </View>

              <TouchableOpacity onPress={onClose} style={styles.closeBtn}>
                <X size={20} color="#94a3b8" />
              </TouchableOpacity>
            </View>

            {/* Estado Operativo */}
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>ESTADO OPERACIONAL:</Text>
              <View style={[styles.statusPill, is09 ? styles.pill09 : styles.pill08]}>
                <View style={[styles.dot, { backgroundColor: is09 ? '#34c759' : '#ff3b30' }]} />
                <Text style={[styles.statusPillText, { color: is09 ? '#34c759' : '#ff6b6b' }]}>
                  {is09 ? '0-9 EN SERVICIO' : est === '0-8' ? '0-8 FUERA DE SERVICIO' : est}
                </Text>
              </View>
            </View>

            {/* N° Registro */}
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>N° REGISTRO:</Text>
              <Text style={styles.infoValue}>#{bombero.idRegistro || bombero.idRadial}</Text>
            </View>

            {/* Compañía */}
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>COMPAÑÍA:</Text>
              <Text style={styles.infoValue}>{bombero.compania || 1}° COMPAÑÍA</Text>
            </View>

            {/* Calidad de Conductor */}
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>HABILITADO CONDUCTOR:</Text>
              <Text style={[styles.infoValue, { color: bombero.conductor ? '#38bdf8' : '#94a3b8' }]}>
                {bombero.conductor ? 'SÍ (CONDUCTOR)' : 'NO'}
              </Text>
            </View>

            {/* Teléfono de Contacto */}
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>CONTACTO:</Text>
              <TouchableOpacity onPress={handleCallPhone} disabled={!bombero.telefono}>
                <Text style={[styles.infoValue, bombero.telefono ? { color: '#60a5fa' } : null]}>
                  {phoneFormatted}
                </Text>
              </TouchableOpacity>
            </View>

            {/* Panel de Gestión de Estado para Comandancia / Capitán / Operador */}
            {canManageStatus ? (
              <View style={styles.adminStatusSection}>
                <Text style={styles.adminStatusTitle}>CAMBIAR ESTADO OPERACIONAL:</Text>
                <View style={styles.adminBtnsRow}>
                  {['0-9', '0-8', 'PERMISO', 'LICENCIA', 'CDS'].map((stOption) => {
                    const active = est === stOption;
                    return (
                      <TouchableOpacity
                        key={stOption}
                        style={[styles.adminStatusChip, active && styles.adminStatusChipActive]}
                        onPress={() => handleChangeBomberoStatus(stOption)}
                        disabled={updatingStatus}
                      >
                        <Text style={[styles.adminStatusChipText, active && { color: '#ffffff' }]}>
                          {stOption}
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                </View>
              </View>
            ) : null}

            {/* Botones de Contacto */}
            {bombero.telefono ? (
              <View style={styles.contactButtonsRow}>
                <TouchableOpacity 
                  style={[styles.whatsappButton, { flex: 1 }]} 
                  onPress={handleOpenWhatsApp} 
                  activeOpacity={0.85}
                >
                  <MessageCircle size={17} color="#ffffff" style={{ marginRight: 6 }} />
                  <Text style={styles.whatsappButtonText}>WHATSAPP</Text>
                </TouchableOpacity>

                <TouchableOpacity 
                  style={styles.callButton} 
                  onPress={handleCallPhone} 
                  activeOpacity={0.85}
                >
                  <PhoneCall size={17} color="#ffffff" />
                </TouchableOpacity>
              </View>
            ) : null}

            {/* Botón Cerrar */}
            <TouchableOpacity style={styles.closeButton} onPress={onClose} activeOpacity={0.8}>
              <Text style={styles.closeButtonText}>CERRAR FICHA</Text>
            </TouchableOpacity>
          </LinearGradient>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.85)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20
  },
  card: {
    width: '100%',
    maxWidth: 380,
    borderRadius: 22,
    overflow: 'hidden',
    borderWidth: 1.5,
    borderColor: 'rgba(255,255,255,0.12)'
  },
  gradient: {
    padding: 20
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 16,
    paddingBottom: 14,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255,255,255,0.08)'
  },
  photoWrapper: {
    width: 52,
    height: 52,
    borderRadius: 14,
    backgroundColor: '#0a0d14',
    borderWidth: 1.5,
    borderColor: '#7f1d1d',
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden'
  },
  photoImage: {
    width: 52,
    height: 52
  },
  nameRow: {
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: 6
  },
  miniRadialBadge: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 6,
    alignItems: 'center',
    justifyContent: 'center'
  },
  badge09: {
    backgroundColor: '#064e3b',
    borderWidth: 1,
    borderColor: '#10b981'
  },
  badge08: {
    backgroundColor: '#450a0a',
    borderWidth: 1,
    borderColor: '#ef4444'
  },
  miniRadialText: {
    fontSize: 12,
    fontWeight: '900',
    color: '#ffffff'
  },
  nameText: {
    fontSize: 15,
    fontWeight: '900',
    color: '#ffffff',
    flex: 1
  },
  cargoText: {
    fontSize: 12,
    color: '#94a3b8',
    fontWeight: '600',
    marginTop: 2
  },
  closeBtn: {
    padding: 6,
    borderRadius: 8,
    backgroundColor: 'rgba(255,255,255,0.06)'
  },
  infoRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 9,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255,255,255,0.05)'
  },
  infoLabel: {
    fontSize: 11,
    fontWeight: '800',
    color: '#94a3b8',
    letterSpacing: 0.5
  },
  infoValue: {
    fontSize: 13,
    fontWeight: '700',
    color: '#ffffff'
  },
  statusPill: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
    borderWidth: 1
  },
  pill09: {
    backgroundColor: 'rgba(52, 199, 89, 0.15)',
    borderColor: '#34c759'
  },
  pill08: {
    backgroundColor: 'rgba(255, 59, 48, 0.15)',
    borderColor: '#ff3b30'
  },
  dot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    marginRight: 6
  },
  statusPillText: {
    fontSize: 11,
    fontWeight: '800'
  },
  adminStatusSection: {
    marginTop: 12,
    paddingTop: 10,
    borderTopWidth: 1,
    borderTopColor: 'rgba(255,255,255,0.08)'
  },
  adminStatusTitle: {
    fontSize: 10,
    fontWeight: '800',
    color: '#94a3b8',
    marginBottom: 8,
    letterSpacing: 0.5
  },
  adminBtnsRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6
  },
  adminStatusChip: {
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 8,
    backgroundColor: '#1e293b',
    borderWidth: 1,
    borderColor: '#334155'
  },
  adminStatusChipActive: {
    backgroundColor: '#dc2626',
    borderColor: '#ef4444'
  },
  adminStatusChipText: {
    fontSize: 10,
    fontWeight: '900',
    color: '#cbd5e1'
  },
  contactButtonsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginTop: 16
  },
  whatsappButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#25d366',
    borderRadius: 14,
    paddingVertical: 11
  },
  whatsappButtonText: {
    fontSize: 12,
    fontWeight: '900',
    color: '#ffffff',
    letterSpacing: 0.6
  },
  callButton: {
    width: 44,
    height: 42,
    borderRadius: 14,
    backgroundColor: '#2563eb',
    alignItems: 'center',
    justifyContent: 'center'
  },
  closeButton: {
    marginTop: 10,
    backgroundColor: 'rgba(255,255,255,0.08)',
    borderRadius: 12,
    paddingVertical: 12,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.1)'
  },
  closeButtonText: {
    fontSize: 13,
    fontWeight: '800',
    color: '#ffffff',
    letterSpacing: 1
  }
});
