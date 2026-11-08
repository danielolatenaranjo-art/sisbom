import React, { useState, useRef, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  Modal,
  TouchableOpacity,
  ScrollView,
  TextInput,
  KeyboardAvoidingView,
  Platform,
  Image
} from 'react-native';
import { MessageSquare, Send, X } from 'lucide-react-native';
import { useAuthStore } from '../../store/useAuthStore';
import { useLicenseStore } from '../../store/useLicenseStore';
import { useDispatchStore } from '../../store/useDispatchStore';
import { SentinelBackground } from '../common/SentinelBackground';
import {
  parseCanonicalChatMessages,
  formatShortPersonName
} from '../../shared/sentinelCore';

// Parser Dual-Read de mensajes de chat sincronizado con SHARED/sentinelCore.js
export function parseChatMessages(rawMensaje = '', fallbackSender = 'COMANDANCIA') {
  return parseCanonicalChatMessages(rawMensaje, fallbackSender);
}

/**
 * Resuelve la identidad visual de un remitente de mensaje de chat ocultando SIEMPRE el idRegistro.
 * Soporta tanto llamada por objeto `{ rawSenderId, firefightersList, user, ownCorpShort, fallbackSenderLabel }`
 * como llamada posicional `(rawSenderId, firefightersList, user, ownCorpShort, fallbackSenderLabel)`,
 * y reconoce el formato SCI (`[1] NOMBRE APELLIDO - COMUNA` y `224 ([23] NOMBRE APELLIDO - COMUNA)`).
 */
export function resolveChatSenderIdentity(
  arg1 = {},
  arg2 = [],
  arg3 = null,
  arg4 = '',
  arg5 = 'COMANDANCIA'
) {
  const isObjCall = arg1 && typeof arg1 === 'object' && !Array.isArray(arg1);
  const rawSenderId = isObjCall ? (arg1.rawSenderId ?? '') : arg1;
  const firefightersList = isObjCall ? (arg1.firefightersList ?? []) : arg2;
  const user = isObjCall ? (arg1.user ?? null) : arg3;
  const ownCorpShort = isObjCall ? (arg1.ownCorpShort ?? '') : arg4;
  const fallbackSenderLabel = isObjCall ? (arg1.fallbackSenderLabel ?? 'COMANDANCIA') : arg5;

  const normStr = (s) =>
    String(s || '')
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .toUpperCase()
      .trim();

  const raw = String(rawSenderId || '').trim();
  const myRegId = String(user?.idRegistro || '').trim();
  const myRadialId = String(user?.idRadial || '1').trim();
  const myShortNameNorm = normStr(formatShortPersonName(user?.nombreBombero || user?.nombre || ''));
  const ownUpper = normStr(ownCorpShort)
    .replace(/^CUERPO\s+DE\s+BOMBEROS\s+DE\s+/i, '')
    .trim();

  let cleanRegOrToken = raw;
  let insideParen = '';

  // Detectar formato SENTINEL SCI: "224 ([23] DANIEL OLATE - NANCAGUA)" o "([23] DANIEL OLATE - NANCAGUA)"
  const parenMatch = raw.match(/^(\S*?)\s*\((.+)\)$/);
  if (parenMatch) {
    cleanRegOrToken = String(parenMatch[1] || '').trim();
    insideParen = String(parenMatch[2] || '').trim();
  }

  let extractedRadial = '';
  let extractedName = '';
  if (insideParen) {
    const bracketMatch = insideParen.match(/^\[([^\]]+)\]\s*(.+)$/);
    if (bracketMatch) {
      extractedRadial = String(bracketMatch[1] || '').trim();
      extractedName = String(bracketMatch[2] || '').trim();
    } else {
      extractedName = insideParen;
    }
  } else {
    const directBracket = raw.match(/^\[([^\]]+)\]\s*(.+)$/);
    if (directBracket) {
      extractedRadial = String(directBracket[1] || '').trim();
      extractedName = String(directBracket[2] || '').trim();
    }
  }

  const tagNorm = normStr(insideParen || extractedName || raw);

  // Si el mensaje trae etiqueta de otro Cuerpo de Bomberos (ej. "- NANCAGUA") distinto al nuestro, no es "isMe"
  const hasOtherCorpTag =
    Boolean(tagNorm.includes(' - ') && ownUpper) &&
    !tagNorm.includes(` - ${ownUpper}`);

  const hasOwnCorpTag = Boolean(ownUpper && tagNorm.includes(` - ${ownUpper}`));
  const matchesMyName = Boolean(myShortNameNorm && tagNorm.includes(myShortNameNorm));

  const senderObj = !hasOtherCorpTag
    ? (firefightersList || []).find(
        (f) => String(f.idRegistro || '').trim() === cleanRegOrToken
      ) ||
      (firefightersList || []).find(
        (f) => String(f.idRadial || '').trim() === cleanRegOrToken
      )
    : null;

  const isMe =
    !hasOtherCorpTag &&
    ((myRegId && cleanRegOrToken === myRegId) ||
      (senderObj && myRegId && String(senderObj.idRegistro || '').trim() === myRegId) ||
      (!senderObj && !insideParen && !extractedName && myRadialId && cleanRegOrToken === myRadialId) ||
      (hasOwnCorpTag && (!extractedRadial || extractedRadial === myRadialId)) ||
      (matchesMyName && (!extractedRadial || extractedRadial === myRadialId)));

  if (isMe) {
    const cleanOwnName = extractedName ? extractedName.replace(/^\d+\s+/, '').trim() : 'Tú';
    return {
      isMe: true,
      radial: extractedRadial || myRadialId || '1',
      name: cleanOwnName,
      cargo: '',
      previewLabel: cleanOwnName
    };
  }

  if (extractedName) {
    const cleanNameNoId = extractedName.replace(/^\d+\s+/, '').trim();
    const rad = extractedRadial || 'SCI';
    return {
      isMe: false,
      radial: rad,
      name: cleanNameNoId,
      cargo: '',
      previewLabel: `[${rad}] ${cleanNameNoId}`
    };
  }

  if (senderObj) {
    const rad = String(senderObj.idRadial || '1').trim();
    const shortName = formatShortPersonName(senderObj.nombre || senderObj.nombreBombero || 'BOMBERO');
    return {
      isMe: false,
      radial: rad,
      name: shortName,
      cargo: String(senderObj.cargo || '').trim(),
      previewLabel: `[${rad}] ${shortName}`
    };
  }

  // Nunca mostrar "ID #224" ni el idRegistro numérico crudo
  const safeFallback = String(fallbackSenderLabel || 'COMANDANCIA')
    .replace(/^COMANDANCIA\s+SCI\s*-\s*/i, '')
    .trim();
  const nonNumericLabel = /^\d+$/.test(cleanRegOrToken)
    ? safeFallback || 'COMANDANCIA'
    : cleanRegOrToken.replace(/^\d+\s*/, '').trim() || safeFallback || 'COMANDANCIA';

  return {
    isMe: false,
    radial: extractedRadial || '1',
    name: nonNumericLabel,
    cargo: '',
    previewLabel: nonNumericLabel
  };
}

function resolveRecipientsLabel(aQuien = 'TC', firefightersList = [], user = null) {
  const clean = String(aQuien || 'TC').trim();
  if (!clean || clean.toUpperCase() === 'TC' || clean.toUpperCase() === 'SC') {
    return 'Toda la Compañía';
  }
  const ids = clean.split(',').map((s) => s.trim()).filter(Boolean);
  const names = ids.map((id) => {
    const byReg = (firefightersList || []).find((p) => String(p.idRegistro || '').trim() === id);
    if (byReg) {
      return `[${byReg.idRadial || '1'}] ${formatShortPersonName(byReg.nombre || byReg.nombreBombero)}`;
    }
    const byRad = (firefightersList || []).find((p) => String(p.idRadial || '').trim() === id);
    if (byRad) {
      return `[${byRad.idRadial || '1'}] ${formatShortPersonName(byRad.nombre || byRad.nombreBombero)}`;
    }
    if (user && (String(user.idRegistro || '').trim() === id || String(user.idRadial || '').trim() === id)) {
      return `[${user.idRadial || '1'}] ${formatShortPersonName(user.nombreBombero || user.nombre || 'COMANDANTE')}`;
    }
    if (id === '1') return 'Comandante';
    return 'Comandancia';
  });
  return names.join(', ');
}

export function formatChatTimestampDisplay(fecha = '', hora = '') {
  const f = String(fecha || '').replace(/^['"]+|['"]+$/g, '').trim();
  const h = String(hora || '').replace(/^['"]+|['"]+$/g, '').trim();
  if (f && h) {
    const parts = f.split('-');
    if (parts.length === 3) {
      return `${parts[0]}-${parts[1]}-${parts[2]} ${h}`;
    }
    return `${f} ${h}`;
  }
  return h || f || '';
}

export const ChatModal = ({ visible, onClose }) => {
  const { user, isDarkMode = true } = useAuthStore();
  const { comuna, nombreMostrar, clientName } = useLicenseStore();
  const {
    chatAlert,
    activeChatAlert,
    firefightersList,
    sendChatMessage,
    sendSciChatMessage,
    registerConforme
  } = useDispatchStore();
  const [inputText, setInputText] = useState('');
  const [sending, setSending] = useState(false);
  const [keyboardOffset, setKeyboardOffset] = useState(0);
  const [isInputFocused, setIsInputFocused] = useState(false);
  const scrollRef = useRef(null);

  const currentChat = activeChatAlert || chatAlert;
  const isSciChat = Boolean(
    currentChat?.isSciChat ||
      currentChat?.isSciCentralChat ||
      String(currentChat?.idAlerta || '').startsWith('SCI') ||
      String(currentChat?.idSolicitud || '').startsWith('SCI')
  );
  const rawChatText = currentChat?.mensajesChat ?? currentChat?.mensajeAlerta ?? '';
  const messages = parseChatMessages(
    rawChatText,
    currentChat?.cuerpoSolicitante || currentChat?.razonAlerta || 'COMANDANCIA'
  );

  // Detectar apertura y altura del teclado en WebView Android/iOS (visualViewport) para que siempre se vea lo que se escribe
  useEffect(() => {
    if (!visible || typeof window === 'undefined') {
      setKeyboardOffset(0);
      return;
    }
    const vv = window.visualViewport;
    if (!vv) return;

    const handleViewportChange = () => {
      const fullH = window.innerHeight || document.documentElement?.clientHeight || vv.height;
      const diff = Math.max(0, Math.round(fullH - vv.height - (vv.offsetTop || 0)));
      setKeyboardOffset(diff > 40 ? diff : 0);
      if (diff > 40) {
        setTimeout(() => {
          scrollRef.current?.scrollToEnd({ animated: true });
        }, 80);
      }
    };

    vv.addEventListener('resize', handleViewportChange);
    vv.addEventListener('scroll', handleViewportChange);
    handleViewportChange();

    return () => {
      vv.removeEventListener('resize', handleViewportChange);
      vv.removeEventListener('scroll', handleViewportChange);
    };
  }, [visible]);

  // Al abrir el chat interno, registrar conforme automáticamente
  useEffect(() => {
    if (visible && !isSciChat && currentChat?.idAlerta && user?.idRadial) {
      const confList = String(currentChat.conforme || '')
        .split(',')
        .map((s) => s.trim().toUpperCase())
        .filter(Boolean);
      const myRad = String(user.idRadial).trim().toUpperCase();
      if (!confList.includes(myRad)) {
        registerConforme(currentChat.idAlerta, myRad);
      }
    }
  }, [visible, isSciChat, currentChat?.idAlerta, currentChat?.mensajeAlerta]);

  useEffect(() => {
    if (visible && scrollRef.current) {
      setTimeout(() => {
        scrollRef.current?.scrollToEnd({ animated: true });
      }, 180);
    }
  }, [visible, messages.length, isInputFocused]);

  const handleSend = async () => {
    if (!inputText.trim() || sending) return;
    const msg = inputText.trim();
    setInputText('');
    setSending(true);
    try {
      if (isSciChat && typeof sendSciChatMessage === 'function') {
        await sendSciChatMessage(msg, currentChat);
      } else {
        await sendChatMessage(currentChat, msg);
      }
      setTimeout(() => {
        scrollRef.current?.scrollToEnd({ animated: true });
      }, 150);
    } finally {
      setSending(false);
    }
  };

  if (!visible) return null;

  const ownCorpShort = comuna || nombreMostrar || clientName || '';
  const recipientsLabel = isSciChat
    ? `Red SCI • [${user?.idRadial || '1'}] ${formatShortPersonName(user?.nombreBombero || user?.nombre || 'COMANDANTE')}`
    : resolveRecipientsLabel(currentChat?.aQuienAlerta, firefightersList, user);
  const isKeyboardOpen = keyboardOffset > 0 || isInputFocused;

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <KeyboardAvoidingView
        style={[
          styles.overlay,
          keyboardOffset > 0 ? { paddingBottom: keyboardOffset } : null
        ]}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <View
          style={[
            styles.container,
            {
              backgroundColor: isDarkMode ? '#060911' : '#f8fafc',
              borderColor: isDarkMode ? '#1e293b' : '#cbd5e1'
            }
          ]}
        >
          {/* Fondo de cenizas incandescentes de SENTINEL */}
          <View style={StyleSheet.absoluteFill} pointerEvents="none">
            <SentinelBackground stage="login" theme={isDarkMode ? 'dark' : 'light'} />
          </View>

          {/* Header del Chat */}
          <View
            style={[
              styles.header,
              {
                backgroundColor: isDarkMode ? 'rgba(15, 23, 42, 0.92)' : 'rgba(255, 255, 255, 0.94)',
                borderBottomColor: isDarkMode ? '#1e293b' : '#e2e8f0'
              }
            ]}
          >
            <View style={styles.headerLeft}>
              <View style={styles.chatIconBox}>
                <MessageSquare size={18} color="#ef4444" />
              </View>
              <View style={{ flex: 1 }}>
                <Text
                  style={[
                    styles.headerTitle,
                    { color: isDarkMode ? '#ffffff' : '#0f172a' }
                  ]}
                  numberOfLines={1}
                >
                  {currentChat?.razonAlerta || currentChat?.cuerpoSolicitante || 'CONVERSACIÓN OPERACIONAL'}
                </Text>
                <Text style={styles.headerSub} numberOfLines={1}>
                  Para: {recipientsLabel}
                </Text>
              </View>
            </View>
            <TouchableOpacity
              onPress={onClose}
              style={[
                styles.closeBtn,
                { backgroundColor: isDarkMode ? '#1e293b' : '#f1f5f9' }
              ]}
            >
              <X size={18} color={isDarkMode ? '#ffffff' : '#0f172a'} />
            </TouchableOpacity>
          </View>

          {/* Lista de Mensajes con el Logo de SENTINEL estático que se desplaza al correr la conversación */}
          <ScrollView
            ref={scrollRef}
            style={styles.messagesScroll}
            contentContainerStyle={styles.messagesContent}
            showsVerticalScrollIndicator={false}
            keyboardShouldPersistTaps="handled"
          >
            <View style={styles.scrollingWatermarkWrap} pointerEvents="none">
              <Image
                source={require('../../../assets/LOGO_SENTINEL.png')}
                style={[
                  styles.scrollingWatermarkImg,
                  { opacity: isDarkMode ? 0.13 : 0.09 }
                ]}
                resizeMode="contain"
              />
            </View>

            {messages.length === 0 ? (
              <View style={styles.emptyBox}>
                <Text style={styles.emptyText}>No hay mensajes en esta conversación.</Text>
              </View>
            ) : (
              messages.map((m) => {
                const senderInfo = resolveChatSenderIdentity({
                  rawSenderId: m.senderId,
                  firefightersList,
                  user,
                  ownCorpShort,
                  fallbackSenderLabel:
                    currentChat?.cuerpoSolicitante ||
                    currentChat?.razonAlerta ||
                    currentChat?.quienAlerta ||
                    'COMANDANCIA'
                });
                const isMe = senderInfo.isMe;
                const timeStr = formatChatTimestampDisplay(m.fecha, m.hora);

                return (
                  <View
                    key={m.id}
                    style={[
                      styles.messageRow,
                      isMe ? styles.messageRowMe : styles.messageRowOther
                    ]}
                  >
                    <View
                      style={[
                        styles.bubble,
                        isMe
                          ? styles.bubbleMe
                          : {
                              backgroundColor: isDarkMode
                                ? 'rgba(21, 28, 44, 0.92)'
                                : 'rgba(255, 255, 255, 0.95)',
                              borderColor: isDarkMode ? '#334155' : '#cbd5e1',
                              borderBottomLeftRadius: 4
                            }
                      ]}
                    >
                      <View style={styles.bubbleHeader}>
                        <View
                          style={[
                            styles.radialPill,
                            isMe ? styles.radialPillMe : styles.radialPillOther
                          ]}
                        >
                          <Text style={styles.radialPillText}>{senderInfo.radial}</Text>
                        </View>
                        <Text
                          style={[
                            styles.senderName,
                            {
                              color: isMe
                                ? '#fecaca'
                                : isDarkMode
                                  ? '#cbd5e1'
                                  : '#334155'
                            }
                          ]}
                          numberOfLines={1}
                        >
                          {senderInfo.name}
                          {!isMe && senderInfo.cargo ? ` • ${senderInfo.cargo}` : ''}
                        </Text>
                      </View>

                      <Text
                        style={[
                          styles.messageText,
                          { color: isMe ? '#ffffff' : isDarkMode ? '#ffffff' : '#0f172a' }
                        ]}
                      >
                        {m.texto}
                      </Text>

                      {timeStr ? (
                        <Text
                          style={[
                            styles.timeText,
                            { color: isMe ? 'rgba(255,255,255,0.75)' : '#94a3b8' }
                          ]}
                        >
                          {timeStr}
                        </Text>
                      ) : null}
                    </View>
                  </View>
                );
              })
            )}
          </ScrollView>

          {/* Barra de Entrada con margen inferior holgado para no pegarse al borde inferior y visible sobre el teclado */}
          <View
            style={[
              styles.inputBar,
              {
                backgroundColor: isDarkMode ? 'rgba(15, 23, 42, 0.96)' : 'rgba(255, 255, 255, 0.96)',
                borderTopColor: isDarkMode ? '#1e293b' : '#e2e8f0',
                paddingTop: 12,
                paddingBottom: isKeyboardOpen ? 12 : 30
              }
            ]}
          >
            <TextInput
              style={[
                styles.textInput,
                {
                  backgroundColor: isDarkMode ? '#070a12' : '#f8fafc',
                  borderColor: isDarkMode ? '#334155' : '#cbd5e1',
                  color: isDarkMode ? '#ffffff' : '#0f172a'
                }
              ]}
              value={inputText}
              onChangeText={setInputText}
              onFocus={() => {
                setIsInputFocused(true);
                setTimeout(() => {
                  scrollRef.current?.scrollToEnd({ animated: true });
                }, 150);
              }}
              onBlur={() => setIsInputFocused(false)}
              placeholder="Escribe un mensaje aquí..."
              placeholderTextColor="#64748b"
              onSubmitEditing={handleSend}
              returnKeyType="send"
            />
            <TouchableOpacity
              style={[styles.sendBtn, !inputText.trim() && styles.sendBtnDisabled]}
              onPress={handleSend}
              disabled={!inputText.trim() || sending}
              activeOpacity={0.8}
            >
              <Send size={17} color="#ffffff" />
            </TouchableOpacity>
          </View>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(4, 7, 15, 0.94)',
    paddingTop: 50
  },
  container: {
    flex: 1,
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    borderWidth: 1,
    overflow: 'hidden',
    position: 'relative'
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 14,
    borderBottomWidth: 1,
    zIndex: 2
  },
  headerLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    flex: 1,
    marginRight: 10
  },
  chatIconBox: {
    width: 36,
    height: 36,
    borderRadius: 10,
    backgroundColor: 'rgba(239, 68, 68, 0.15)',
    borderWidth: 1,
    borderColor: '#ef4444',
    alignItems: 'center',
    justifyContent: 'center'
  },
  headerTitle: {
    fontSize: 13,
    fontWeight: '900',
    letterSpacing: 0.4
  },
  headerSub: {
    fontSize: 10.5,
    fontWeight: '700',
    color: '#94a3b8',
    marginTop: 1
  },
  closeBtn: {
    width: 34,
    height: 34,
    borderRadius: 17,
    alignItems: 'center',
    justifyContent: 'center'
  },
  messagesScroll: {
    flex: 1,
    zIndex: 1
  },
  messagesContent: {
    padding: 14,
    paddingBottom: 28,
    minHeight: '100%',
    gap: 10,
    position: 'relative'
  },
  scrollingWatermarkWrap: {
    position: 'absolute',
    top: 40,
    left: 0,
    right: 0,
    bottom: 40,
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 0
  },
  scrollingWatermarkImg: {
    width: 240,
    height: 240
  },
  emptyBox: {
    paddingVertical: 40,
    alignItems: 'center',
    zIndex: 1
  },
  emptyText: {
    color: '#64748b',
    fontSize: 12,
    fontWeight: '600'
  },
  messageRow: {
    width: '100%',
    flexDirection: 'row',
    zIndex: 1
  },
  messageRowMe: {
    justifyContent: 'flex-end'
  },
  messageRowOther: {
    justifyContent: 'flex-start'
  },
  bubble: {
    maxWidth: '84%',
    borderRadius: 16,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderWidth: 1
  },
  bubbleMe: {
    backgroundColor: '#dc2626',
    borderColor: '#ef4444',
    borderBottomRightRadius: 4
  },
  bubbleHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginBottom: 4
  },
  radialPill: {
    paddingHorizontal: 6,
    paddingVertical: 1.5,
    borderRadius: 5
  },
  radialPillMe: {
    backgroundColor: 'rgba(0,0,0,0.28)'
  },
  radialPillOther: {
    backgroundColor: '#2563eb'
  },
  radialPillText: {
    fontSize: 10,
    fontWeight: '900',
    color: '#ffffff'
  },
  senderName: {
    fontSize: 11,
    fontWeight: '800',
    flexShrink: 1
  },
  messageText: {
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '600'
  },
  timeText: {
    fontSize: 9.5,
    textAlign: 'right',
    marginTop: 4,
    fontWeight: '600'
  },
  inputBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
    borderTopWidth: 1,
    gap: 8,
    zIndex: 2
  },
  textInput: {
    flex: 1,
    borderWidth: 1,
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 11,
    fontSize: 13.5,
    outlineStyle: 'none'
  },
  sendBtn: {
    width: 44,
    height: 44,
    borderRadius: 14,
    backgroundColor: '#dc2626',
    alignItems: 'center',
    justifyContent: 'center'
  },
  sendBtnDisabled: {
    backgroundColor: '#334155'
  }
});
