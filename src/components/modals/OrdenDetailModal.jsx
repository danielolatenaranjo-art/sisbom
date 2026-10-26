import React from 'react';
import { View, Text, StyleSheet, Modal, TouchableOpacity, ScrollView, Image } from 'react-native';
import { FileText, X } from 'lucide-react-native';
import { useLicenseStore } from '../../store/useLicenseStore';

export const OrdenDetailModal = ({ visible, orden, onClose }) => {
  const { clientName, nombreMostrar, comuna, logoUrl } = useLicenseStore();

  if (!orden) return null;

  const isAlerta = Boolean(orden.isAlertaComunicado);
  const ordenNum = orden.numeroOrden || orden.numero || '13/2026';
  const fechaDoc = String(orden.fechaOrden || orden.fechaAlerta || orden.fechaDocumento || orden.fecha || '').replace(/^'+/, '');
  const horaDoc = String(orden.horaAlerta || orden.hora || '').replace(/^'+/, '');
  const alertTitle = String(orden.razonAlerta || 'COMUNICADO OFICIAL').replace(/^'+/, '').trim().toUpperCase();
  const rawBody = orden.mensajeAlerta || orden.cuerpo || orden.mensaje || '';
  const bodyLines = rawBody
    ? String(rawBody).replace(/\|/g, '\n')
    : 'I.- CITASE AL PERSONAL DEL CUERPO DE BOMBEROS A CITACIÓN INSTITUCIONAL SEGÚN REGLAMENTO VIGENTE.';

  const instComuna = (comuna || nombreMostrar || clientName || 'INSTITUCIONAL')
    .replace(/^cuerpo\s+de\s+bomberos\s+de\s+/i, '')
    .trim();
  const instUpper = instComuna.toUpperCase();

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <View style={styles.overlay}>
        {/* Barra Superior con Icono, Título y Botón Cerrar Redondo */}
        <View style={styles.topBar}>
          <View style={styles.topBarLeft}>
            <View style={styles.docIconWrapper}>
              <FileText size={18} color="#ffffff" />
            </View>
            <View>
              <Text style={styles.topBarTitle}>
                {isAlerta ? 'COMUNICADO OFICIAL' : `ORDEN DEL DÍA N° ${ordenNum}`}
              </Text>
              <Text style={styles.topBarSub}>CUERPO DE BOMBEROS DE {instUpper}, {fechaDoc}</Text>
            </View>
          </View>

          <TouchableOpacity onPress={onClose} style={styles.roundCloseBtn} activeOpacity={0.75}>
            <X size={18} color="#ffffff" />
          </TouchableOpacity>
        </View>

        {/* Hoja de Documento Oficial Blanco (Fidelidad 100% con V2 Kotlin TabScreens.kt) */}
        <ScrollView style={styles.scrollArea} contentContainerStyle={styles.scrollContent}>
          <View style={styles.whiteSheet}>
            {isAlerta ? (
              <>
                {/* Comunicado Oficial limpio igual a TabScreens.kt líneas 2816-2865 */}
                <Text style={styles.dateRight}>
                  {instComuna}, {fechaDoc}{horaDoc ? ` • ${horaDoc}` : ''}
                </Text>

                <Text style={[styles.orderCenterTitle, { fontSize: 15, marginVertical: 14 }]}>
                  {alertTitle}
                </Text>

                <View style={styles.orderBody}>
                  <Text
                    style={[
                      styles.bodyParagraph,
                      {
                        fontWeight: '500',
                        fontSize: 13.5,
                        lineHeight: 21,
                        color: '#374151',
                        textAlign: 'justify'
                      }
                    ]}
                  >
                    {bodyLines}
                  </Text>
                </View>

                {orden.quienAlerta ? (
                  <>
                    <View style={[styles.blackDivider, { height: 1, backgroundColor: '#e2e8f0', marginVertical: 12 }]} />
                    <Text style={[styles.sheetFooter, { fontSize: 10, color: '#475569' }]}>
                      EMITIDO POR: {String(orden.quienAlerta).toUpperCase()}
                    </Text>
                  </>
                ) : null}
              </>
            ) : (
              <>
                {/* Membrete Oficial Orden del Día */}
                <View style={styles.headerRow}>
                  <View style={styles.headerLeft}>
                    <Text style={styles.headerTitleDark}>CUERPO DE BOMBEROS</Text>
                    <Text style={styles.headerMotto}>“Abnegación y Sacrificio”</Text>
                    <Text style={styles.headerComandancia}>COMANDANCIA</Text>
                    <Text style={styles.headerCorps}>CUERPO DE BOMBEROS DE {instUpper}</Text>
                  </View>

                  {/* Escudo Oficial */}
                  <Image
                    source={logoUrl ? { uri: logoUrl } : require('../../../assets/LOGO_SENTINEL.png')}
                    style={styles.corpsCrest}
                    resizeMode="contain"
                  />
                </View>

                {/* Línea divisoria negra superior */}
                <View style={styles.blackDivider} />

                {/* Fecha a la derecha */}
                <Text style={styles.dateRight}>{instComuna}, {fechaDoc}</Text>

                {/* Título Central */}
                <Text style={styles.orderCenterTitle}>
                  {`ORDEN DEL DIA N° ${ordenNum}`}
                </Text>

                {/* Cuerpo de la Orden (Texto Institucional en Mayúsculas) */}
                <View style={styles.orderBody}>
                  <Text style={styles.bodyParagraph}>
                    Y SEGÚN LAS FACULTADES QUE ME CONFIERE EL REGLAMENTO GENERAL, DICTO LA SIGUIENTE ORDEN DEL DÍA:
                  </Text>

                  <Text style={styles.bodyParagraph}>
                    {bodyLines.toUpperCase()}
                  </Text>

                  <Text style={styles.bodyParagraphFoot}>
                    TODA MODIFICACION A LA PRESENTE SE HARA DE FORMA ESCRITA O VERBAL{'\n'}
                    COMUNIQUESE Y CUMPLASE.
                  </Text>
                </View>

                {/* Imagen Oficial de Firma y Timbre del Comandante */}
                <View style={styles.officialFirmaContainer}>
                  <Image
                    source={require('../../../assets/firma_comandante.jpg')}
                    style={styles.officialFirmaImage}
                    resizeMode="contain"
                  />
                  {orden.firmaNombre ? (
                    <View style={{ alignItems: 'center', marginTop: 4 }}>
                      <Text style={styles.firmaNombreText}>{orden.firmaNombre.toUpperCase()}</Text>
                      <Text style={styles.firmaCargoText}>{(orden.firmaCargo || 'COMANDANTE').toUpperCase()}</Text>
                    </View>
                  ) : null}
                </View>

                {/* Línea divisoria negra inferior */}
                <View style={styles.blackDivider} />

                {/* Pie de Página Institucional */}
                <Text style={styles.sheetFooter}>
                  DOCUMENTO OFICIAL — COMANDANCIA CUERPO DE BOMBEROS DE {instUpper}
                </Text>
              </>
            )}
          </View>
        </ScrollView>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: '#070a14',
    paddingTop: 40
  },
  topBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255, 255, 255, 0.08)'
  },
  topBarLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12
  },
  docIconWrapper: {
    width: 32,
    height: 32,
    borderRadius: 8,
    backgroundColor: '#1e293b',
    alignItems: 'center',
    justifyContent: 'center'
  },
  topBarTitle: {
    fontSize: 13,
    fontWeight: '900',
    color: '#ffffff',
    letterSpacing: 0.5
  },
  topBarSub: {
    fontSize: 10,
    fontWeight: '700',
    color: '#94a3b8',
    marginTop: 2
  },
  roundCloseBtn: {
    width: 38,
    height: 38,
    borderRadius: 19,
    backgroundColor: '#242b3d',
    alignItems: 'center',
    justifyContent: 'center'
  },
  scrollArea: {
    flex: 1
  },
  scrollContent: {
    padding: 14,
    paddingBottom: 40
  },
  whiteSheet: {
    backgroundColor: '#ffffff',
    borderRadius: 16,
    padding: 20,
    shadowColor: '#000000',
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.35,
    shadowRadius: 14,
    elevation: 8
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start'
  },
  headerLeft: {
    flex: 1,
    paddingRight: 10
  },
  headerTitleDark: {
    fontSize: 13,
    fontWeight: '900',
    color: '#000000',
    letterSpacing: 0.5
  },
  headerMotto: {
    fontSize: 11,
    fontStyle: 'italic',
    fontWeight: '800',
    color: '#b91c1c',
    marginTop: 1
  },
  headerFundado: {
    fontSize: 9.5,
    color: '#555555',
    marginTop: 1
  },
  headerComandancia: {
    fontSize: 12,
    fontWeight: '900',
    color: '#000000',
    marginTop: 3,
    letterSpacing: 0.4
  },
  headerCorps: {
    fontSize: 11.5,
    fontWeight: '800',
    color: '#000000',
    letterSpacing: 0.3
  },
  corpsCrest: {
    width: 62,
    height: 62
  },
  blackDivider: {
    height: 2,
    backgroundColor: '#000000',
    marginVertical: 14
  },
  dateRight: {
    fontSize: 12,
    fontWeight: '700',
    color: '#000000',
    textAlign: 'right',
    marginBottom: 8
  },
  orderCenterTitle: {
    fontSize: 14,
    fontWeight: '900',
    color: '#000000',
    textAlign: 'center',
    letterSpacing: 0.8,
    marginVertical: 12
  },
  orderBody: {
    marginVertical: 6
  },
  bodyParagraph: {
    fontSize: 12,
    fontWeight: '700',
    color: '#000000',
    lineHeight: 18,
    letterSpacing: 0.3,
    marginBottom: 14
  },
  bodyParagraphFoot: {
    fontSize: 10.5,
    fontWeight: '700',
    color: '#111827',
    lineHeight: 16,
    letterSpacing: 0.2,
    marginTop: 8,
    marginBottom: 16
  },
  officialFirmaContainer: {
    alignItems: 'center',
    justifyContent: 'center',
    marginVertical: 12
  },
  officialFirmaImage: {
    width: 240,
    height: 120
  },
  firmaNombreText: {
    fontSize: 11,
    fontWeight: '900',
    color: '#000000'
  },
  firmaCargoText: {
    fontSize: 10,
    fontWeight: '800',
    color: '#b91c1c'
  },
  sheetFooter: {
    fontSize: 8,
    fontWeight: '700',
    color: '#4b5563',
    textAlign: 'center',
    letterSpacing: 0.3
  }
});
