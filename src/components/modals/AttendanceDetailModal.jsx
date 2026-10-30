import React from 'react';
import { View, Text, StyleSheet, Modal, TouchableOpacity, ScrollView } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { CheckCircle, XCircle, X } from 'lucide-react-native';

export const AttendanceDetailModal = ({ visible, item, onClose }) => {
  if (!item) return null;

  const rawSt = String(item.userEstado || item.estado || 'ASISTE').toUpperCase();
  const isPresent = ['A', 'ASISTE', 'CDS'].includes(rawSt);
  const checkIsAbono = (abonoVal, clave = '', tipo = '') => {
    if (typeof abonoVal === 'number') return Math.round(abonoVal) === 1;
    if (typeof abonoVal === 'boolean') return abonoVal === true;
    if (typeof abonoVal === 'string') {
      const s = abonoVal.trim().toUpperCase();
      if (s === '1' || s === 'SI' || s === 'SÍ' || s === 'TRUE' || s === 'ABONO' || s.includes('ABONO') || s.includes('EXTRA')) {
        return true;
      }
      if (s === '0' || s === 'NO' || s === 'FALSE' || s === 'OBLIGATORIA') {
        return false;
      }
    }
    const c = String(clave || '').trim().toUpperCase();
    const t = String(tipo || '').trim().toUpperCase();
    return c.includes('ABONO') || c.includes('EXTRA') || t.includes('ABONO') || t.includes('EXTRA');
  };
  const isAbono = checkIsAbono(item.userAbono ?? item.abono, item.clave, item.tipo);
  const formatPersonDisplay = (val, fallback = '') => {
    if (!val) return fallback;
    if (typeof val === 'object') {
      const rad = String(val.idRadial || val.codigoRadial || val.id || '').trim();
      const nom = String(val.nombre || val.nombreBombero || '').trim();
      if (rad && nom) return `${rad} - ${nom}`;
      return nom || rad || fallback;
    }
    const str = String(val).trim();
    if (!str || str === '[object Object]') return fallback;
    const bracketMatch = str.match(/^\[([^\]]+)\]\s*(.+)$/);
    if (bracketMatch) {
      return `${bracketMatch[1].trim()} - ${bracketMatch[2].trim()}`;
    }
    return str;
  };

  const obacDisplay = formatPersonDisplay(item.obac, 'NO ESPECIFICADO');
  const listaPorDisplay = formatPersonDisplay(item.listaPasadaPor || item.listaPor, 'AYUDANTE');
  const aprobadoPorClean = formatPersonDisplay(item.aprobadoPor, '');
  const isApproved = aprobadoPorClean.length > 0;

  const statusText = (() => {
    switch (rawSt) {
      case 'A':
      case 'ASISTE':
        return '✓ ASISTE';
      case 'CDS':
        return '★ COMISIÓN DE SERVICIO';
      case 'P':
      case 'PERMISO':
        return 'PERMISO AUTORIZADO';
      case 'L':
      case 'LICENCIA':
        return 'LICENCIA MÉDICA';
      case 'S':
      case 'SUSPENDIDO':
        return 'SUSPENDIDO';
      default:
        return '✕ FALTA';
    }
  })();

  const formatShortListId = (rawVal) => {
    const clean = String(rawVal || '').replace(/^['"#\s]+|['"\s]+$/g, '').trim();
    if (!clean) return '1';
    // Si viene en formato anual de V2/V3 como "20260001" o "2026-0001", extraer solo el número correlativo (ej: "1")
    const yearMatch = clean.match(/^(?:19|20)\d{2}[-_]?(\d+)$/);
    if (yearMatch && yearMatch[1]) {
      const seq = parseInt(yearMatch[1], 10);
      return Number.isFinite(seq) && seq > 0 ? String(seq) : yearMatch[1];
    }
    const numOnly = parseInt(clean.replace(/\D/g, ''), 10);
    return Number.isFinite(numOnly) && numOnly > 0 ? String(numOnly) : clean;
  };

  const formattedIdLista = `N° ${formatShortListId(
    item.nroLista || item.numeroLista || item.idLista || item.id
  )}`;

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <View style={styles.overlay}>
        <View style={styles.card}>
          <LinearGradient
            colors={['#1c1822', '#11131a']}
            style={styles.gradient}
          >
            {/* Header del Modal */}
            <View style={styles.header}>
              <View style={styles.headerLeft}>
                <View style={[styles.claveBadge, isPresent ? styles.claveAsiste : styles.claveFalta]}>
                  <Text style={styles.claveBadgeText}>{item.clave || 'CITACIÓN'}</Text>
                </View>
                <View>
                  <Text style={styles.headerTitle}>DETALLE DE ASISTENCIA</Text>
                  <Text style={styles.headerSub}>LISTA {formattedIdLista}</Text>
                </View>
              </View>

              <TouchableOpacity onPress={onClose} style={styles.closeBtn}>
                <X size={20} color="#94a3b8" />
              </TouchableOpacity>
            </View>

            <ScrollView showsVerticalScrollIndicator={false} style={styles.scrollContent}>
              {/* Estado de Asistencia */}
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>ESTADO:</Text>
                <View style={[styles.statusPill, isPresent ? styles.pillGreen : styles.pillRed]}>
                  {isPresent ? (
                    <CheckCircle size={14} color="#34d399" style={{ marginRight: 6 }} />
                  ) : (
                    <XCircle size={14} color="#f87171" style={{ marginRight: 6 }} />
                  )}
                  <Text style={[styles.statusPillText, isPresent ? styles.textGreen : styles.textRed]}>
                    {statusText}
                  </Text>
                </View>
              </View>

              {/* Fecha y Hora */}
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>FECHA Y HORA:</Text>
                <Text style={styles.infoValue}>
                  {item.fecha || '---'} • {item.hora || '--:--'}
                </Text>
              </View>

              {/* Lugar / Dirección */}
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>LUGAR:</Text>
                <Text style={styles.infoValue} numberOfLines={2}>
                  {(item.lugar || 'CUARTEL GENERAL').toUpperCase()}
                </Text>
              </View>

              {/* Abono */}
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>MODALIDAD:</Text>
                <View style={[styles.abonoBadge, isAbono ? styles.abonoYes : styles.abonoNo]}>
                  <Text style={[styles.abonoText, isAbono ? styles.abonoTextYes : styles.abonoTextNo]}>
                    {isAbono ? 'ABONO' : 'OBLIGATORIA'}
                  </Text>
                </View>
              </View>

              {/* OBAC (Oficial / Bombero a Cargo) */}
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>OBAC:</Text>
                <Text style={styles.infoValue}>
                  {obacDisplay}
                </Text>
              </View>

              {/* Lista pasada por */}
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>LISTA POR:</Text>
                <Text style={styles.infoValue}>
                  {listaPorDisplay}
                </Text>
              </View>

              {/* Aprobado por (Solo se muestra si la lista ya fue aprobada) */}
              {isApproved ? (
                <View style={styles.infoRow}>
                  <Text style={styles.infoLabel}>APROBADO POR:</Text>
                  <Text style={[styles.infoValue, { color: '#34d399' }]}>
                    {aprobadoPorClean}
                  </Text>
                </View>
              ) : null}

              {/* Detalle / Observación */}
              <View style={styles.detailSection}>
                <Text style={styles.detailSectionLabel}>DETALLE DE LA ACTUACIÓN:</Text>
                <View style={styles.detailBox}>
                  <Text style={styles.detailText}>
                    {item.detalle ||
                      (isApproved
                        ? 'Acto de servicio o citación institucional registrada y aprobada en el Libro de Guardia Digital.'
                        : 'Acto de servicio o citación institucional pendiente de revisión y aprobación.')}
                  </Text>
                </View>
              </View>
            </ScrollView>

            {/* Botón Cerrar */}
            <TouchableOpacity style={styles.closeButton} onPress={onClose} activeOpacity={0.8}>
              <Text style={styles.closeButtonText}>CERRAR DETALLE</Text>
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
    maxWidth: 390,
    borderRadius: 22,
    overflow: 'hidden',
    borderWidth: 1.5,
    borderColor: 'rgba(255,255,255,0.12)'
  },
  gradient: {
    padding: 20,
    maxHeight: 560
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 16,
    paddingBottom: 14,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255,255,255,0.08)'
  },
  headerLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    flex: 1,
    minWidth: 0,
    marginRight: 10
  },
  claveBadge: {
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 10,
    borderWidth: 1.5
  },
  claveAsiste: {
    backgroundColor: '#064e3b',
    borderColor: '#10b981'
  },
  claveFalta: {
    backgroundColor: '#450a0a',
    borderColor: '#ef4444'
  },
  claveBadgeText: {
    fontSize: 16,
    fontWeight: '900',
    color: '#ffffff'
  },
  headerTitle: {
    fontSize: 14,
    fontWeight: '900',
    color: '#ffffff',
    letterSpacing: 0.5
  },
  headerSub: {
    fontSize: 11,
    color: '#94a3b8',
    fontWeight: '700',
    marginTop: 1
  },
  closeBtn: {
    padding: 6,
    borderRadius: 8,
    backgroundColor: 'rgba(255,255,255,0.06)'
  },
  scrollContent: {
    maxHeight: 380
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
    fontSize: 12,
    fontWeight: '700',
    color: '#ffffff',
    textAlign: 'right',
    maxWidth: '60%'
  },
  statusPill: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 10,
    borderWidth: 1
  },
  pillGreen: {
    backgroundColor: 'rgba(16, 185, 129, 0.15)',
    borderColor: '#10b981'
  },
  pillRed: {
    backgroundColor: 'rgba(239, 68, 68, 0.15)',
    borderColor: '#ef4444'
  },
  statusPillText: {
    fontSize: 11,
    fontWeight: '900'
  },
  textGreen: {
    color: '#34d399'
  },
  textRed: {
    color: '#f87171'
  },
  abonoBadge: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 6
  },
  abonoYes: {
    backgroundColor: '#6366f1'
  },
  abonoNo: {
    backgroundColor: '#334155'
  },
  abonoText: {
    fontSize: 10,
    fontWeight: '900'
  },
  abonoTextYes: {
    color: '#ffffff'
  },
  abonoTextNo: {
    color: '#94a3b8'
  },
  detailSection: {
    marginTop: 14
  },
  detailSectionLabel: {
    fontSize: 11,
    fontWeight: '800',
    color: '#94a3b8',
    marginBottom: 6,
    letterSpacing: 0.5
  },
  detailBox: {
    backgroundColor: 'rgba(0,0,0,0.3)',
    borderRadius: 12,
    padding: 12,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.07)'
  },
  detailText: {
    fontSize: 12,
    color: '#cbd5e1',
    lineHeight: 18
  },
  closeButton: {
    marginTop: 16,
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
