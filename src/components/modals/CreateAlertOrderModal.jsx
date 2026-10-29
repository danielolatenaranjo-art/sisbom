import React, { useState, useMemo } from 'react';
import {
  View,
  Text,
  StyleSheet,
  Modal,
  TouchableOpacity,
  TextInput,
  ScrollView
} from 'react-native';
import { X, FileText, Bell, Send, Search, Users, UserCheck, MessageSquare } from 'lucide-react-native';
import { useAuthStore } from '../../store/useAuthStore';
import { useDispatchStore } from '../../store/useDispatchStore';

function getTodayDDMMYYYY() {
  const d = new Date();
  const dd = String(d.getDate()).padStart(2, '0');
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  return `${dd}-${mm}-${d.getFullYear()}`;
}

export const CreateAlertOrderModal = ({ visible, mode = 'orden', onClose }) => {
  const { user, isDarkMode = true } = useAuthStore();
  const { publishOrder, publishAlert, firefightersList } = useDispatchStore();

  // Estado Orden del Día
  const [numeroOrden, setNumeroOrden] = useState('');
  const [fechaOrden, setFechaOrden] = useState(getTodayDDMMYYYY);
  const [cuerpo, setCuerpo] = useState('');

  // Estado Alerta / Muro de Comunicaciones (idéntico a alertas.html y TabScreens.kt)
  const [razonAlerta, setRazonAlerta] = useState('');
  const [gradoAlerta, setGradoAlerta] = useState('1'); // '1' | '2' | '3'
  const [tipoDuracion, setTipoDuracion] = useState('i'); // 'i' | 'C' | 'fecha' | 'hora'
  const [fechaLimite, setFechaLimite] = useState(getTodayDDMMYYYY);
  const [horaLimite, setHoraLimite] = useState('22:00');
  const [destinatarioTipo, setDestinatarioTipo] = useState('TC'); // 'TC' | 'especifico'
  const [radialQuery, setRadialQuery] = useState('');
  const [selectedRecipients, setSelectedRecipients] = useState([]); // Array de objetos bombero { idRegistro, idRadial, nombre, cargo }
  const [errorMsg, setErrorMsg] = useState('');
  const [publishing, setPublishing] = useState(false);

  const personnel = Array.isArray(firefightersList) ? firefightersList : [];

  // Filtrado por ID Radial (prioritario) o nombre para el buscador de destinatarios
  const filteredPersonnel = useMemo(() => {
    const q = radialQuery.trim().toUpperCase();
    if (!q) return [];
    // Si escribió varios separados por coma, tomar el último término para autocompletar
    const parts = q.split(',');
    const lastTerm = parts[parts.length - 1].trim();
    if (!lastTerm) return [];

    return personnel
      .filter((p) => {
        const alreadySelected = selectedRecipients.some(
          (sel) => String(sel.idRegistro) === String(p.idRegistro)
        );
        if (alreadySelected) return false;
        const rad = String(p.idRadial || '').trim().toUpperCase();
        const nom = String(p.nombre || p.nombreBombero || '').trim().toUpperCase();
        return rad.startsWith(lastTerm) || rad === lastTerm || nom.includes(lastTerm);
      })
      .slice(0, 6);
  }, [radialQuery, personnel, selectedRecipients]);

  // Resolver también IDs radiales escritos directamente (ej: "1, 23") que aún no estén como chip
  const resolvedDirectRadials = useMemo(() => {
    if (destinatarioTipo !== 'especifico' || !radialQuery.trim()) return [];
    const tokens = radialQuery
      .split(',')
      .map((t) => t.trim())
      .filter(Boolean);
    const matches = [];
    tokens.forEach((tok) => {
      const found = personnel.find(
        (p) =>
          String(p.idRadial || '').trim().toUpperCase() === tok.toUpperCase() ||
          (tok.match(/^\d+$/) && parseInt(String(p.idRadial || ''), 10) === parseInt(tok, 10))
      );
      if (
        found &&
        !selectedRecipients.some((s) => String(s.idRegistro) === String(found.idRegistro)) &&
        !matches.some((m) => String(m.idRegistro) === String(found.idRegistro))
      ) {
        matches.push(found);
      }
    });
    return matches;
  }, [destinatarioTipo, radialQuery, personnel, selectedRecipients]);

  if (!visible) return null;

  const isOrden = mode === 'orden';

  const handleAddRecipient = (bombero) => {
    if (!bombero?.idRegistro) return;
    if (!selectedRecipients.some((s) => String(s.idRegistro) === String(bombero.idRegistro))) {
      setSelectedRecipients((prev) => [...prev, bombero]);
    }
    // Limpiar el último término buscado
    const parts = radialQuery.split(',');
    parts.pop();
    setRadialQuery(parts.length > 0 ? `${parts.join(', ')}, ` : '');
    setErrorMsg('');
  };

  const handleRemoveRecipient = (idRegistro) => {
    setSelectedRecipients((prev) =>
      prev.filter((s) => String(s.idRegistro) !== String(idRegistro))
    );
  };

  const resetForm = () => {
    setCuerpo('');
    setNumeroOrden('');
    setRazonAlerta('');
    setGradoAlerta('1');
    setTipoDuracion('i');
    setDestinatarioTipo('TC');
    setRadialQuery('');
    setSelectedRecipients([]);
    setErrorMsg('');
  };

  const handlePublish = async () => {
    if (publishing) return;
    setErrorMsg('');

    if (isOrden) {
      if (!cuerpo.trim()) {
        setErrorMsg('Ingrese el contenido resolutivo de la Orden del Día.');
        return;
      }
      setPublishing(true);
      try {
        await publishOrder({
          numeroOrden: numeroOrden.trim() || `01/${new Date().getFullYear()}`,
          fechaOrden: fechaOrden.trim(),
          cuerpo: cuerpo.trim(),
          firmaNombre: user?.nombreBombero || 'COMANDANCIA',
          firmaCargo: user?.cargo || 'COMANDANTE'
        });
        resetForm();
        onClose();
      } finally {
        setPublishing(false);
      }
      return;
    }

    // Validación de Alerta / Conversación (idéntica a alertas.html líneas 1225-1283 y TabScreens.kt)
    if (!razonAlerta.trim() || !cuerpo.trim()) {
      setErrorMsg('Complete el Motivo/Título y el Cuerpo del Mensaje.');
      return;
    }

    // Resolver Vigencia / Duración
    let finalDuracion = 'i';
    if (tipoDuracion === 'C') {
      finalDuracion = 'C';
    } else if (tipoDuracion === 'fecha') {
      if (!/^\d{2}-\d{2}-\d{4}$/.test(fechaLimite.trim())) {
        setErrorMsg('Ingrese la fecha límite en formato DD-MM-AAAA.');
        return;
      }
      finalDuracion = fechaLimite.trim();
    } else if (tipoDuracion === 'hora') {
      if (!/^\d{2}:\d{2}$/.test(horaLimite.trim())) {
        setErrorMsg('Ingrese la hora límite en formato HH:MM.');
        return;
      }
      finalDuracion = `${getTodayDDMMYYYY()} ${horaLimite.trim()}`;
    }

    // Resolver Destinatarios: búsqueda por idRadial, pero envío obligatorio a idRegistro
    let finalAQuien = 'TC';
    if (destinatarioTipo === 'especifico') {
      const combined = [...selectedRecipients, ...resolvedDirectRadials];
      const regIds = Array.from(
        new Set(
          combined
            .map((p) => String(p.idRegistro || '').trim())
            .filter(Boolean)
        )
      );
      if (regIds.length === 0) {
        setErrorMsg('Busque por ID Radial y seleccione al menos un bombero destinatario.');
        return;
      }
      finalAQuien = regIds.join(',');
    }

    setPublishing(true);
    try {
      await publishAlert({
        razon: razonAlerta.trim().toUpperCase(),
        mensaje: cuerpo.trim(),
        grado: gradoAlerta,
        duracion: finalDuracion,
        aQuien: finalAQuien,
        creadoPor: `${user?.cargo || 'Voluntario'} - ${user?.nombreBombero || 'Comandancia'}`
      });
      resetForm();
      onClose();
    } finally {
      setPublishing(false);
    }
  };

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <View style={styles.overlay}>
        <View
          style={[
            styles.card,
            {
              backgroundColor: isDarkMode ? '#0f1420' : '#ffffff',
              borderColor: isDarkMode ? '#334155' : '#cbd5e1'
            }
          ]}
        >
          {/* Header */}
          <View
            style={[
              styles.header,
              { borderBottomColor: isDarkMode ? '#1e293b' : '#e2e8f0' }
            ]}
          >
            <View style={styles.headerLeft}>
              {isOrden ? (
                <FileText size={18} color="#ef4444" />
              ) : (
                <Bell size={18} color="#ef4444" />
              )}
              <Text
                style={[
                  styles.headerTitle,
                  { color: isDarkMode ? '#ffffff' : '#0f172a' }
                ]}
              >
                {isOrden ? 'EMITIR NUEVA ORDEN DEL DÍA' : 'PUBLICAR NUEVA ALERTA'}
              </Text>
            </View>
            <TouchableOpacity
              onPress={() => {
                resetForm();
                onClose();
              }}
              style={[
                styles.closeBtn,
                { backgroundColor: isDarkMode ? '#1e293b' : '#f1f5f9' }
              ]}
            >
              <X size={18} color={isDarkMode ? '#94a3b8' : '#475569'} />
            </TouchableOpacity>
          </View>

          <ScrollView
            showsVerticalScrollIndicator={false}
            style={{ maxHeight: 540 }}
            keyboardShouldPersistTaps="handled"
          >
            {isOrden ? (
              <>
                <View style={styles.row}>
                  <View style={{ flex: 1 }}>
                    <Text style={styles.label}>N° ORDEN (EJ: 14/2026)</Text>
                    <TextInput
                      style={[
                        styles.input,
                        {
                          backgroundColor: isDarkMode ? '#070a12' : '#f8fafc',
                          borderColor: isDarkMode ? '#334155' : '#cbd5e1',
                          color: isDarkMode ? '#ffffff' : '#0f172a'
                        }
                      ]}
                      value={numeroOrden}
                      onChangeText={setNumeroOrden}
                      placeholder="14/2026"
                      placeholderTextColor="#64748b"
                    />
                  </View>
                  <View style={{ flex: 1 }}>
                    <Text style={styles.label}>FECHA DOCUMENTO</Text>
                    <TextInput
                      style={[
                        styles.input,
                        {
                          backgroundColor: isDarkMode ? '#070a12' : '#f8fafc',
                          borderColor: isDarkMode ? '#334155' : '#cbd5e1',
                          color: isDarkMode ? '#ffffff' : '#0f172a'
                        }
                      ]}
                      value={fechaOrden}
                      onChangeText={setFechaOrden}
                      placeholder="DD-MM-YYYY"
                      placeholderTextColor="#64748b"
                    />
                  </View>
                </View>

                <Text style={styles.label}>CONTENIDO RESOLUTIVO DE LA ORDEN</Text>
                <TextInput
                  style={[
                    styles.input,
                    styles.textArea,
                    {
                      backgroundColor: isDarkMode ? '#070a12' : '#f8fafc',
                      borderColor: isDarkMode ? '#334155' : '#cbd5e1',
                      color: isDarkMode ? '#ffffff' : '#0f172a'
                    }
                  ]}
                  multiline
                  value={cuerpo}
                  onChangeText={setCuerpo}
                  placeholder="I.- CITASE AL PERSONAL ACTIVO Y HONORARIO..."
                  placeholderTextColor="#64748b"
                  textAlignVertical="top"
                />
              </>
            ) : (
              <>
                {/* 1. Motivo / Título de la Alerta */}
                <View style={{ marginBottom: 12 }}>
                  <Text style={styles.label}>MOTIVO / TÍTULO DE LA ALERTA *</Text>
                  <TextInput
                    style={[
                      styles.input,
                      {
                        backgroundColor: isDarkMode ? '#070a12' : '#f8fafc',
                        borderColor: isDarkMode ? '#334155' : '#cbd5e1',
                        color: isDarkMode ? '#ffffff' : '#0f172a',
                        fontWeight: '700'
                      }
                    ]}
                    value={razonAlerta}
                    onChangeText={setRazonAlerta}
                    placeholder="Ej: CITACIÓN A ACADEMIA, ACUARTELAMIENTO..."
                    placeholderTextColor="#64748b"
                    autoCapitalize="characters"
                  />
                </View>

                {/* 2. Cuerpo del Mensaje Inicial */}
                <View style={{ marginBottom: 12 }}>
                  <Text style={styles.label}>CUERPO DEL MENSAJE INICIAL *</Text>
                  <TextInput
                    style={[
                      styles.input,
                      styles.textArea,
                      {
                        backgroundColor: isDarkMode ? '#070a12' : '#f8fafc',
                        borderColor: isDarkMode ? '#334155' : '#cbd5e1',
                        color: isDarkMode ? '#ffffff' : '#0f172a',
                        marginBottom: 0,
                        minHeight: 88
                      }
                    ]}
                    multiline
                    value={cuerpo}
                    onChangeText={setCuerpo}
                    placeholder="Escribe el detalle completo de la alerta o el primer mensaje del chat..."
                    placeholderTextColor="#64748b"
                    textAlignVertical="top"
                  />
                </View>

                {/* 3. Grado de Importancia (1 = Azul Info, 2 = Ámbar Citación, 3 = Rojo Crítico) */}
                <View style={{ marginBottom: 12 }}>
                  <Text style={styles.label}>GRADO DE IMPORTANCIA *</Text>
                  <View style={styles.chipsRow}>
                    {[
                      { id: '1', label: 'GRADO 1 • INFO', color: '#3b82f6' },
                      { id: '2', label: 'GRADO 2 • CITACIÓN', color: '#f59e0b' },
                      { id: '3', label: 'GRADO 3 • CRÍTICO', color: '#ef4444' }
                    ].map((g) => {
                      const active = gradoAlerta === g.id;
                      return (
                        <TouchableOpacity
                          key={g.id}
                          style={[
                            styles.chip,
                            {
                              backgroundColor: active
                                ? g.color
                                : isDarkMode
                                  ? '#1e293b'
                                  : '#f1f5f9',
                              borderColor: active
                                ? g.color
                                : isDarkMode
                                  ? '#334155'
                                  : '#cbd5e1'
                            }
                          ]}
                          onPress={() => setGradoAlerta(g.id)}
                          activeOpacity={0.85}
                        >
                          <Text
                            style={[
                              styles.chipText,
                              { color: active ? '#ffffff' : isDarkMode ? '#94a3b8' : '#475569' }
                            ]}
                          >
                            {g.label}
                          </Text>
                        </TouchableOpacity>
                      );
                    })}
                  </View>
                </View>

                {/* 4. Vigencia / Modalidad (Indefinida, Conversación Chat, Fecha, Hora) */}
                <View style={{ marginBottom: 12 }}>
                  <Text style={styles.label}>VIGENCIA / MODALIDAD *</Text>
                  <View style={[styles.chipsRow, { flexWrap: 'wrap' }]}>
                    {[
                      { id: 'i', label: 'INDEFINIDA' },
                      { id: 'C', label: 'CHAT GRUPAL' },
                      { id: 'fecha', label: 'HASTA FECHA' },
                      { id: 'hora', label: 'HASTA HORA HOY' }
                    ].map((opt) => {
                      const active = tipoDuracion === opt.id;
                      return (
                        <TouchableOpacity
                          key={opt.id}
                          style={[
                            styles.chip,
                            {
                              minWidth: '47%',
                              backgroundColor: active
                                ? opt.id === 'C'
                                  ? '#0284c7'
                                  : '#dc2626'
                                : isDarkMode
                                  ? '#1e293b'
                                  : '#f1f5f9',
                              borderColor: active
                                ? opt.id === 'C'
                                  ? '#38bdf8'
                                  : '#ef4444'
                                : isDarkMode
                                  ? '#334155'
                                  : '#cbd5e1'
                            }
                          ]}
                          onPress={() => setTipoDuracion(opt.id)}
                          activeOpacity={0.85}
                        >
                          <Text
                            style={[
                              styles.chipText,
                              { color: active ? '#ffffff' : isDarkMode ? '#94a3b8' : '#475569' }
                            ]}
                          >
                            {opt.label}
                          </Text>
                        </TouchableOpacity>
                      );
                    })}
                  </View>

                  {tipoDuracion === 'fecha' ? (
                    <View style={{ marginTop: 8 }}>
                      <Text style={styles.label}>FECHA DE TÉRMINO (DD-MM-AAAA)</Text>
                      <TextInput
                        style={[
                          styles.input,
                          {
                            backgroundColor: isDarkMode ? '#070a12' : '#f8fafc',
                            borderColor: isDarkMode ? '#334155' : '#cbd5e1',
                            color: isDarkMode ? '#ffffff' : '#0f172a'
                          }
                        ]}
                        value={fechaLimite}
                        onChangeText={setFechaLimite}
                        placeholder="DD-MM-YYYY"
                        placeholderTextColor="#64748b"
                      />
                    </View>
                  ) : null}

                  {tipoDuracion === 'hora' ? (
                    <View style={{ marginTop: 8 }}>
                      <Text style={styles.label}>HORA DE TÉRMINO HOY (HH:MM)</Text>
                      <TextInput
                        style={[
                          styles.input,
                          {
                            backgroundColor: isDarkMode ? '#070a12' : '#f8fafc',
                            borderColor: isDarkMode ? '#334155' : '#cbd5e1',
                            color: isDarkMode ? '#ffffff' : '#0f172a'
                          }
                        ]}
                        value={horaLimite}
                        onChangeText={setHoraLimite}
                        placeholder="22:00"
                        placeholderTextColor="#64748b"
                      />
                    </View>
                  ) : null}
                </View>

                {/* 5. Destinatarios: Toda la Compañía (TC) vs Personal Específico (Búsqueda por idRadial -> envío a idRegistro) */}
                <View style={{ marginBottom: 14 }}>
                  <Text style={styles.label}>DESTINATARIO *</Text>
                  <View style={styles.chipsRow}>
                    <TouchableOpacity
                      style={[
                        styles.chip,
                        {
                          backgroundColor:
                            destinatarioTipo === 'TC'
                              ? '#dc2626'
                              : isDarkMode
                                ? '#1e293b'
                                : '#f1f5f9',
                          borderColor:
                            destinatarioTipo === 'TC'
                              ? '#ef4444'
                              : isDarkMode
                                ? '#334155'
                                : '#cbd5e1',
                          flexDirection: 'row',
                          justifyContent: 'center',
                          gap: 6
                        }
                      ]}
                      onPress={() => setDestinatarioTipo('TC')}
                      activeOpacity={0.85}
                    >
                      <Users
                        size={13}
                        color={
                          destinatarioTipo === 'TC'
                            ? '#ffffff'
                            : isDarkMode
                              ? '#94a3b8'
                              : '#475569'
                        }
                      />
                      <Text
                        style={[
                          styles.chipText,
                          {
                            color:
                              destinatarioTipo === 'TC'
                                ? '#ffffff'
                                : isDarkMode
                                  ? '#94a3b8'
                                  : '#475569'
                          }
                        ]}
                      >
                        TODA LA COMPAÑÍA (TC)
                      </Text>
                    </TouchableOpacity>

                    <TouchableOpacity
                      style={[
                        styles.chip,
                        {
                          backgroundColor:
                            destinatarioTipo === 'especifico'
                              ? '#dc2626'
                              : isDarkMode
                                ? '#1e293b'
                                : '#f1f5f9',
                          borderColor:
                            destinatarioTipo === 'especifico'
                              ? '#ef4444'
                              : isDarkMode
                                ? '#334155'
                                : '#cbd5e1',
                          flexDirection: 'row',
                          justifyContent: 'center',
                          gap: 6
                        }
                      ]}
                      onPress={() => setDestinatarioTipo('especifico')}
                      activeOpacity={0.85}
                    >
                      <UserCheck
                        size={13}
                        color={
                          destinatarioTipo === 'especifico'
                            ? '#ffffff'
                            : isDarkMode
                              ? '#94a3b8'
                              : '#475569'
                        }
                      />
                      <Text
                        style={[
                          styles.chipText,
                          {
                            color:
                              destinatarioTipo === 'especifico'
                                ? '#ffffff'
                                : isDarkMode
                                  ? '#94a3b8'
                                  : '#475569'
                          }
                        ]}
                      >
                        PERSONAL ESPECÍFICO
                      </Text>
                    </TouchableOpacity>
                  </View>

                  {destinatarioTipo === 'especifico' ? (
                    <View
                      style={[
                        styles.recipientBox,
                        {
                          backgroundColor: isDarkMode ? '#090d16' : '#f8fafc',
                          borderColor: isDarkMode ? '#1e293b' : '#cbd5e1'
                        }
                      ]}
                    >
                      <Text style={[styles.label, { marginBottom: 4 }]}>
                        BUSCAR POR ID RADIAL (SE ENVÍA AL ID DE REGISTRO PERSONAL)
                      </Text>

                      {/* Input de búsqueda por ID Radial */}
                      <View
                        style={[
                          styles.searchInputRow,
                          {
                            backgroundColor: isDarkMode ? '#0f172a' : '#ffffff',
                            borderColor: isDarkMode ? '#334155' : '#cbd5e1'
                          }
                        ]}
                      >
                        <Search size={15} color="#64748b" />
                        <TextInput
                          style={[
                            styles.searchInput,
                            { color: isDarkMode ? '#ffffff' : '#0f172a' }
                          ]}
                          value={radialQuery}
                          onChangeText={setRadialQuery}
                          placeholder="Ej: 1, 12, 23 o nombre del voluntario..."
                          placeholderTextColor="#64748b"
                        />
                        {radialQuery ? (
                          <TouchableOpacity onPress={() => setRadialQuery('')}>
                            <X size={15} color="#64748b" />
                          </TouchableOpacity>
                        ) : null}
                      </View>

                      {/* Lista desplegable de sugerencias por ID Radial */}
                      {filteredPersonnel.length > 0 ? (
                        <View
                          style={[
                            styles.suggestionsList,
                            {
                              backgroundColor: isDarkMode ? '#111827' : '#ffffff',
                              borderColor: isDarkMode ? '#334155' : '#cbd5e1'
                            }
                          ]}
                        >
                          {filteredPersonnel.map((p) => (
                            <TouchableOpacity
                              key={String(p.idRegistro || p.idRadial)}
                              style={[
                                styles.suggestionItem,
                                { borderBottomColor: isDarkMode ? '#1e293b' : '#f1f5f9' }
                              ]}
                              onPress={() => handleAddRecipient(p)}
                              activeOpacity={0.75}
                            >
                              <View style={styles.suggestionLeft}>
                                <View style={styles.radialBadge}>
                                  <Text style={styles.radialBadgeText}>{p.idRadial}</Text>
                                </View>
                                <View style={{ flex: 1 }}>
                                  <Text
                                    style={[
                                      styles.suggestionName,
                                      { color: isDarkMode ? '#f8fafc' : '#0f172a' }
                                    ]}
                                    numberOfLines={1}
                                  >
                                    {p.nombre || p.nombreBombero}
                                  </Text>
                                  <Text style={styles.suggestionCargo} numberOfLines={1}>
                                    {p.cargo || 'Voluntario'} • Reg #{p.idRegistro}
                                  </Text>
                                </View>
                              </View>
                              <Text style={styles.addTagText}>+ AGREGAR</Text>
                            </TouchableOpacity>
                          ))}
                        </View>
                      ) : null}

                      {/* Chips de destinatarios seleccionados (muestra ID Radial + Nombre, guarda idRegistro) */}
                      {(selectedRecipients.length > 0 || resolvedDirectRadials.length > 0) ? (
                        <View style={styles.selectedChipsWrap}>
                          {selectedRecipients.map((sel) => (
                            <View key={`sel-${sel.idRegistro}`} style={styles.recipientChip}>
                              <Text style={styles.recipientChipRadial}>[{sel.idRadial}]</Text>
                              <Text style={styles.recipientChipName} numberOfLines={1}>
                                {sel.nombre || sel.nombreBombero}
                              </Text>
                              <TouchableOpacity
                                onPress={() => handleRemoveRecipient(sel.idRegistro)}
                                style={{ paddingLeft: 4 }}
                              >
                                <X size={12} color="#fca5a5" />
                              </TouchableOpacity>
                            </View>
                          ))}
                          {resolvedDirectRadials.map((direct) => (
                            <TouchableOpacity
                              key={`dir-${direct.idRegistro}`}
                              style={[
                                styles.recipientChip,
                                { backgroundColor: 'rgba(16, 185, 129, 0.16)', borderColor: '#10b981' }
                              ]}
                              onPress={() => handleAddRecipient(direct)}
                            >
                              <Text style={[styles.recipientChipRadial, { color: '#34d399' }]}>
                                [{direct.idRadial}]
                              </Text>
                              <Text style={styles.recipientChipName} numberOfLines={1}>
                                {direct.nombre || direct.nombreBombero}
                              </Text>
                            </TouchableOpacity>
                          ))}
                        </View>
                      ) : (
                        <Text style={styles.helperHint}>
                          Escriba el ID Radial (ej: 23) y toque el voluntario para agregarlo.
                        </Text>
                      )}
                    </View>
                  ) : null}
                </View>
              </>
            )}

            {errorMsg ? (
              <View style={styles.errorBanner}>
                <Text style={styles.errorBannerText}>{errorMsg}</Text>
              </View>
            ) : null}

            <TouchableOpacity
              style={[
                styles.publishBtn,
                (!cuerpo.trim() || (!isOrden && !razonAlerta.trim())) && styles.publishBtnDisabled
              ]}
              onPress={handlePublish}
              disabled={!cuerpo.trim() || (!isOrden && !razonAlerta.trim()) || publishing}
              activeOpacity={0.85}
            >
              <Send size={15} color="#ffffff" style={{ marginRight: 8 }} />
              <Text style={styles.publishBtnText}>
                {publishing
                  ? 'PUBLICANDO...'
                  : isOrden
                    ? 'FIRMAR Y PUBLICAR ORDEN'
                    : tipoDuracion === 'C'
                      ? 'INICIAR CONVERSACIÓN / CHAT'
                      : 'PUBLICAR ALERTA EN EL MURO'}
              </Text>
            </TouchableOpacity>
          </ScrollView>
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
    padding: 16
  },
  card: {
    width: '100%',
    maxWidth: 420,
    borderRadius: 20,
    padding: 18,
    borderWidth: 1.5
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 14,
    paddingBottom: 12,
    borderBottomWidth: 1
  },
  headerLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    flex: 1,
    minWidth: 0,
    marginRight: 10
  },
  headerTitle: {
    fontSize: 13,
    fontWeight: '900',
    letterSpacing: 0.4
  },
  closeBtn: {
    padding: 6,
    borderRadius: 8
  },
  row: {
    flexDirection: 'row',
    gap: 10,
    marginBottom: 12
  },
  label: {
    fontSize: 10,
    fontWeight: '800',
    color: '#94a3b8',
    marginBottom: 6,
    letterSpacing: 0.5
  },
  input: {
    borderWidth: 1,
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 12.5,
    outlineStyle: 'none'
  },
  textArea: {
    minHeight: 105,
    marginBottom: 14
  },
  chipsRow: {
    flexDirection: 'row',
    gap: 8
  },
  chip: {
    flex: 1,
    paddingVertical: 9,
    paddingHorizontal: 6,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1
  },
  chipText: {
    fontSize: 9.5,
    fontWeight: '900',
    textAlign: 'center'
  },
  recipientBox: {
    marginTop: 10,
    padding: 10,
    borderRadius: 12,
    borderWidth: 1
  },
  searchInputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderRadius: 10,
    paddingHorizontal: 10,
    paddingVertical: 7,
    gap: 8
  },
  searchInput: {
    flex: 1,
    fontSize: 12,
    paddingVertical: 2,
    outlineStyle: 'none'
  },
  suggestionsList: {
    marginTop: 6,
    borderRadius: 10,
    borderWidth: 1,
    overflow: 'hidden'
  },
  suggestionItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderBottomWidth: 1
  },
  suggestionLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    flex: 1,
    marginRight: 8
  },
  radialBadge: {
    backgroundColor: '#dc2626',
    borderRadius: 6,
    paddingHorizontal: 7,
    paddingVertical: 2,
    minWidth: 28,
    alignItems: 'center'
  },
  radialBadgeText: {
    color: '#ffffff',
    fontSize: 10.5,
    fontWeight: '900'
  },
  suggestionName: {
    fontSize: 11.5,
    fontWeight: '800'
  },
  suggestionCargo: {
    fontSize: 9.5,
    color: '#64748b',
    fontWeight: '600'
  },
  addTagText: {
    fontSize: 9.5,
    fontWeight: '900',
    color: '#38bdf8'
  },
  selectedChipsWrap: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
    marginTop: 8
  },
  recipientChip: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(220, 38, 38, 0.18)',
    borderWidth: 1,
    borderColor: 'rgba(239, 68, 68, 0.5)',
    borderRadius: 8,
    paddingHorizontal: 8,
    paddingVertical: 4,
    gap: 4
  },
  recipientChipRadial: {
    color: '#f87171',
    fontSize: 10,
    fontWeight: '900'
  },
  recipientChipName: {
    color: '#f8fafc',
    fontSize: 10.5,
    fontWeight: '700',
    maxWidth: 150
  },
  helperHint: {
    fontSize: 10,
    color: '#64748b',
    marginTop: 6,
    fontStyle: 'italic'
  },
  errorBanner: {
    backgroundColor: 'rgba(239, 68, 68, 0.15)',
    borderWidth: 1,
    borderColor: '#ef4444',
    borderRadius: 10,
    paddingHorizontal: 10,
    paddingVertical: 8,
    marginBottom: 10
  },
  errorBannerText: {
    color: '#fca5a5',
    fontSize: 11,
    fontWeight: '700',
    textAlign: 'center'
  },
  publishBtn: {
    backgroundColor: '#dc2626',
    borderRadius: 12,
    paddingVertical: 13,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center'
  },
  publishBtnDisabled: {
    backgroundColor: '#334155'
  },
  publishBtnText: {
    fontSize: 12,
    fontWeight: '900',
    color: '#ffffff',
    letterSpacing: 0.5
  }
});
