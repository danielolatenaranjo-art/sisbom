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
  Platform
} from 'react-native';
import { MessageSquare, Send, X } from 'lucide-react-native';
import { useAuthStore } from '../../store/useAuthStore';
import { useDispatchStore } from '../../store/useDispatchStore';

// Parser Dual-Read de mensajes de chat (soporta DD-MM-YYYY/HH:MM/idRegistro: texto y [DD-MM-YYYY/HH:MM/idRegistro]: texto)
export function parseChatMessages(rawMensaje = '') {
  if (!rawMensaje || !String(rawMensaje).trim()) return [];
  const entries = String(rawMensaje)
    .split('|')
    .map((s) => s.trim())
    .filter(Boolean);

  return entries.map((entry, idx) => {
    // 1. Formato canónico V2/V3 (alertas.html / TabScreens.kt): DD-MM-YYYY/HH:MM/idRegistro: mensaje
    const matchStandard = entry.match(/^(\d{2}-\d{2}-\d{4})\/(\d{2}:\d{2})\/([^:]+):\s*([\s\S]*)$/);
    if (matchStandard) {
      return {
        id: `${idx}-${matchStandard[1]}-${matchStandard[2]}`,
        fecha: matchStandard[1].trim(),
        hora: matchStandard[2].trim(),
        senderId: matchStandard[3].trim(),
        texto: matchStandard[4].trim()
      };
    }

    // 2. Fallback con corchetes: [DD-MM-YYYY/HH:MM/idRegistro]: mensaje
    const matchBracket = entry.match(/^\[([^/]+)\/([^/]+)\/([^\]]+)\]:\s*([\s\S]*)$/);
    if (matchBracket) {
      return {
        id: `${idx}-${matchBracket[1]}-${matchBracket[2]}`,
        fecha: matchBracket[1].trim(),
        hora: matchBracket[2].trim(),
        senderId: matchBracket[3].trim(),
        texto: matchBracket[4].trim()
      };
    }

    return {
      id: `raw-${idx}`,
      fecha: '',
      hora: '',
      senderId: '0',
      texto: entry
    };
  });
}

function resolveRecipientsLabel(aQuien = 'TC', firefightersList = []) {
  const clean = String(aQuien || 'TC').trim();
  if (!clean || clean.toUpperCase() === 'TC' || clean.toUpperCase() === 'SC' || clean === '1') {
    return 'Toda la Compañía';
  }
  const ids = clean.split(',').map((s) => s.trim()).filter(Boolean);
  const names = ids.map((id) => {
    const byReg = (firefightersList || []).find((p) => String(p.idRegistro).trim() === id);
    if (byReg) return `[${byReg.idRadial}] ${byReg.nombre || byReg.nombreBombero}`;
    const byRad = (firefightersList || []).find((p) => String(p.idRadial).trim() === id);
    if (byRad) return `[${byRad.idRadial}] ${byRad.nombre || byRad.nombreBombero}`;
    return `ID #${id}`;
  });
  return names.join(', ');
}

export const ChatModal = ({ visible, onClose }) => {
  const { user, isDarkMode = true } = useAuthStore();
  const {
    chatAlert,
    activeChatAlert,
    firefightersList,
    sendChatMessage,
    registerConforme
  } = useDispatchStore();
  const [inputText, setInputText] = useState('');
  const [sending, setSending] = useState(false);
  const scrollRef = useRef(null);

  const currentChat = activeChatAlert || chatAlert;
  const messages = parseChatMessages(currentChat?.mensajeAlerta || '');

  // Al abrir el chat, registrar conforme automáticamente igual que alertas.html línea 1815
  useEffect(() => {
    if (visible && currentChat?.idAlerta && user?.idRadial) {
      const confList = String(currentChat.conforme || '')
        .split(',')
        .map((s) => s.trim().toUpperCase())
        .filter(Boolean);
      const myRad = String(user.idRadial).trim().toUpperCase();
      if (!confList.includes(myRad)) {
        registerConforme(currentChat.idAlerta, myRad);
      }
    }
  }, [visible, currentChat?.idAlerta, currentChat?.mensajeAlerta]);

  useEffect(() => {
    if (visible && scrollRef.current) {
      setTimeout(() => {
        scrollRef.current?.scrollToEnd({ animated: true });
      }, 180);
    }
  }, [visible, messages.length]);

  const handleSend = async () => {
    if (!inputText.trim() || sending) return;
    const msg = inputText.trim();
    setInputText('');
    setSending(true);
    try {
      await sendChatMessage(currentChat, msg);
      setTimeout(() => {
        scrollRef.current?.scrollToEnd({ animated: true });
      }, 150);
    } finally {
      setSending(false);
    }
  };

  if (!visible) return null;

  const myRegId = String(user?.idRegistro || '').trim();
  const myRadialId = String(user?.idRadial || '').trim();
  const recipientsLabel = resolveRecipientsLabel(currentChat?.aQuienAlerta, firefightersList);

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <KeyboardAvoidingView
        style={styles.overlay}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <View
          style={[
            styles.container,
            {
              backgroundColor: isDarkMode ? '#090d16' : '#f8fafc',
              borderColor: isDarkMode ? '#1e293b' : '#cbd5e1'
            }
          ]}
        >
          {/* Header idéntico a alertas.html (Título de la conversación + Destinatarios) */}
          <View
            style={[
              styles.header,
              {
                backgroundColor: isDarkMode ? '#0f172a' : '#ffffff',
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
                  {currentChat?.razonAlerta || 'CONVERSACIÓN OPERACIONAL'}
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

          {/* Lista de Mensajes */}
          <ScrollView
            ref={scrollRef}
            style={styles.messagesScroll}
            contentContainerStyle={styles.messagesContent}
            showsVerticalScrollIndicator={false}
          >
            {messages.length === 0 ? (
              <View style={styles.emptyBox}>
                <Text style={styles.emptyText}>No hay mensajes en esta conversación.</Text>
              </View>
            ) : (
              messages.map((m) => {
                // Identificar por idRegistro permanente (con fallback a idRadial en mensajes antiguos)
                const senderObj = (firefightersList || []).find(
                  (f) => String(f.idRegistro).trim() === String(m.senderId).trim()
                ) || (firefightersList || []).find(
                  (f) => String(f.idRadial).trim() === String(m.senderId).trim()
                );

                const isMe =
                  (myRegId && String(m.senderId).trim() === myRegId) ||
                  (senderObj && myRegId && String(senderObj.idRegistro).trim() === myRegId) ||
                  (!senderObj && myRadialId && String(m.senderId).trim() === myRadialId);

                const senderName = isMe
                  ? 'Tú'
                  : senderObj?.nombre || senderObj?.nombreBombero || `ID #${m.senderId}`;
                const senderCargo = senderObj?.cargo || '';
                const senderRadial = isMe
                  ? myRadialId || myRegId
                  : senderObj?.idRadial || m.senderId;

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
                              backgroundColor: isDarkMode ? '#151c2c' : '#ffffff',
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
                          <Text style={styles.radialPillText}>{senderRadial}</Text>
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
                          {senderName}
                          {!isMe && senderCargo ? ` • ${senderCargo}` : ''}
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

                      <Text
                        style={[
                          styles.timeText,
                          { color: isMe ? 'rgba(255,255,255,0.7)' : '#94a3b8' }
                        ]}
                      >
                        {m.fecha ? `${m.fecha} ` : ''}{m.hora}
                      </Text>
                    </View>
                  </View>
                );
              })
            )}
          </ScrollView>

          {/* Barra de Entrada */}
          <View
            style={[
              styles.inputBar,
              {
                backgroundColor: isDarkMode ? '#0f172a' : '#ffffff',
                borderTopColor: isDarkMode ? '#1e293b' : '#e2e8f0'
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
    backgroundColor: 'rgba(4, 7, 15, 0.92)',
    paddingTop: Platform.OS === 'ios' ? 44 : 24
  },
  container: {
    flex: 1,
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    borderWidth: 1,
    overflow: 'hidden'
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 14,
    borderBottomWidth: 1
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
    color: '#64748b',
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
    flex: 1
  },
  messagesContent: {
    padding: 14,
    paddingBottom: 24,
    gap: 10
  },
  emptyBox: {
    paddingVertical: 40,
    alignItems: 'center'
  },
  emptyText: {
    color: '#64748b',
    fontSize: 12,
    fontWeight: '600'
  },
  messageRow: {
    width: '100%',
    flexDirection: 'row'
  },
  messageRowMe: {
    justifyContent: 'flex-end'
  },
  messageRowOther: {
    justifyContent: 'flex-start'
  },
  bubble: {
    maxWidth: '82%',
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
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderTopWidth: 1,
    gap: 8
  },
  textInput: {
    flex: 1,
    borderWidth: 1,
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 10,
    fontSize: 13,
    outlineStyle: 'none'
  },
  sendBtn: {
    width: 42,
    height: 42,
    borderRadius: 14,
    backgroundColor: '#dc2626',
    alignItems: 'center',
    justifyContent: 'center'
  },
  sendBtnDisabled: {
    backgroundColor: '#334155'
  }
});
