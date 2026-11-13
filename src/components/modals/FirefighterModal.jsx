import React from "react";
import {
  View,
  Text,
  StyleSheet,
  Modal,
  TouchableOpacity,
  Image,
  Linking,
} from "react-native";
import { LinearGradient } from "expo-linear-gradient";
import { X, MessageCircle, PhoneCall } from "lucide-react-native";
import { useAuthStore } from "../../store/useAuthStore";
import { useLicenseStore } from "../../store/useLicenseStore";
import { useDispatchStore } from "../../store/useDispatchStore";
import { hasCommanderPrivileges } from "../../shared/sentinelCore";

// Formato de Teléfono Chileno: +56 9 XXXX XXXX
function formatChileanPhone(rawPhone) {
  if (!rawPhone) return "NO REGISTRADO";
  const digits = String(rawPhone).replace(/\D/g, "");
  if (!digits) return "NO REGISTRADO";
  let nineDigits = "";
  if (digits.startsWith("569")) {
    nineDigits = digits.slice(2);
  } else if (digits.startsWith("9")) {
    nineDigits = digits;
  } else if (digits.length === 8) {
    nineDigits = "9" + digits;
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

const ADMIN_STATUS_OPTIONS = [
  { key: "0-9", label: "0-9", bg: "#059669", border: "#34D399" },
  { key: "0-8", label: "0-8", bg: "#DC2626", border: "#F87171" },
  { key: "PERMISO", label: "PERMISO", bg: "#EA580C", border: "#FB923C" },
  { key: "LICENCIA", label: "LICENCIA", bg: "#0284C7", border: "#38BDF8" },
  { key: "CDS", label: "CDS", bg: "#CA8A04", border: "#FACC15" },
  { key: "SUSPENDIDO", label: "SUSPENDIDO", bg: "#7C3AED", border: "#C084FC" },
];

function getStatusPillColors(estUpper) {
  switch (estUpper) {
    case "0-9":
      return {
        bg: "rgba(52, 199, 89, 0.15)",
        border: "#34c759",
        dot: "#34c759",
        text: "#34c759",
        label: "0-9 EN SERVICIO",
      };
    case "PERMISO":
      return {
        bg: "rgba(249, 115, 22, 0.18)",
        border: "#fb923c",
        dot: "#fb923c",
        text: "#fb923c",
        label: "PERMISO",
      };
    case "LICENCIA":
      return {
        bg: "rgba(14, 165, 233, 0.18)",
        border: "#38bdf8",
        dot: "#38bdf8",
        text: "#38bdf8",
        label: "LICENCIA MÉDICA",
      };
    case "CDS":
      return {
        bg: "rgba(234, 179, 8, 0.18)",
        border: "#facc15",
        dot: "#facc15",
        text: "#facc15",
        label: "CDS",
      };
    case "SUSPENDIDO":
      return {
        bg: "rgba(139, 92, 246, 0.22)",
        border: "#c084fc",
        dot: "#c084fc",
        text: "#c084fc",
        label: "SUSPENDIDO",
      };
    default:
      return {
        bg: "rgba(255, 59, 48, 0.15)",
        border: "#ff3b30",
        dot: "#ff3b30",
        text: "#ff6b6b",
        label: estUpper === "0-8" ? "0-8 FUERA DE SERVICIO" : estUpper,
      };
  }
}

export const FirefighterModal = ({ visible, bombero, onClose }) => {
  const { user, updateStatus } = useAuthStore();
  const { logoUrl } = useLicenseStore();
  const { isCentralActive } = useDispatchStore();

  if (!bombero) return null;

  const est = String(bombero.estado || "0-8").trim().toUpperCase();
  const is09 = est === "0-9";
  const pillTheme = getStatusPillColors(est);
  const phoneFormatted = formatChileanPhone(bombero.telefono);

  const myRadial = String(user?.idRadial || "").trim();
  const radialNum = parseInt(myRadial.replace(/\D/g, ""), 10) || 0;
  const isComandante =
    hasCommanderPrivileges(user) ||
    myRadial === "1" ||
    user?.cargo?.toUpperCase().includes("COMANDANTE");
  const isCapitan = radialNum >= 40 && radialNum <= 49;
  const canManageStatus = isComandante || isCapitan || isCentralActive;

  const getPhotoUri = (foto) => {
    if (!foto) return null;
    const str = String(foto).trim();
    if (str.startsWith("http") || str.startsWith("data:image")) return str;
    return `data:image/jpeg;base64,${str}`;
  };
  const photoUri = getPhotoUri(bombero.foto);

  const handleOpenWhatsApp = () => {
    const digits = String(bombero.telefono || "").replace(/\D/g, "");
    if (!digits) return;
    let cleanNumber = digits;
    if (digits.startsWith("569")) {
      cleanNumber = digits;
    } else if (digits.startsWith("9")) {
      cleanNumber = "56" + digits;
    } else {
      cleanNumber = "569" + digits.slice(-8);
    }

    const appUrl = `whatsapp://send?phone=${cleanNumber}`;
    const webUrl = `https://wa.me/${cleanNumber}`;
    try {
      if (typeof window !== "undefined" && window.AndroidApp?.openExternalUrl) {
        window.AndroidApp.openExternalUrl(appUrl);
        return;
      }
      if (
        typeof window !== "undefined" &&
        window.webkit?.messageHandlers?.iOSApp
      ) {
        window.webkit.messageHandlers.iOSApp.postMessage({
          action: "openExternalUrl",
          url: appUrl,
        });
        return;
      }
    } catch (_) {}

    Linking.openURL(appUrl).catch(() => {
      Linking.openURL(webUrl).catch((err) => {
        console.warn("Error abriendo WhatsApp:", err);
      });
    });
  };

  const handleCallPhone = () => {
    const digits = String(bombero.telefono || "").replace(/\D/g, "");
    if (!digits) return;
    const telUrl = `tel:+56${digits.startsWith("56") ? digits.slice(2) : digits}`;
    try {
      if (typeof window !== "undefined" && window.AndroidApp?.openExternalUrl) {
        window.AndroidApp.openExternalUrl(telUrl);
        return;
      }
      if (
        typeof window !== "undefined" &&
        window.webkit?.messageHandlers?.iOSApp
      ) {
        window.webkit.messageHandlers.iOSApp.postMessage({
          action: "openExternalUrl",
          url: telUrl,
        });
        return;
      }
    } catch (_) {}

    Linking.openURL(telUrl).catch(() => {});
  };

  // Cambio de estado 100% instantáneo (0ms en pantalla, sincronización REST + SDK en segundo plano sin bloquear la UI)
  const handleChangeBomberoStatus = (newEstado) => {
    const docId = String(bombero.idRegistro || "").trim();
    if (!docId) return;
    const prevEstado = String(bombero.estado || "0-8").trim().toUpperCase();
    const cleanNewEstado = String(newEstado || "0-8").trim().toUpperCase();
    const isSelf = docId === String(user?.idRegistro || "").trim();

    updateStatus(cleanNewEstado, isSelf ? null : docId, {
      previousStatus: prevEstado,
      origen: isCentralActive ? "CAD" : "ONE",
      modificadoPor: String(
        user?.nombreBombero || user?.nombre || user?.idRegistro || "OPERADOR",
      ).trim(),
      enServicio: String(bombero.enServicio || "0"),
    }).catch((err) => {
      console.warn("Error actualizando estado de bombero:", err);
    });

    onClose();
  };

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      onRequestClose={onClose}
    >
      <View style={styles.overlay}>
        <View style={styles.card}>
          <LinearGradient
            colors={["#1c1924", "#121118"]}
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
                        : require("../../../assets/LOGO_SENTINEL.png")
                  }
                  style={styles.photoImage}
                  resizeMode="cover"
                />
              </View>

              <View style={{ flex: 1, marginHorizontal: 12 }}>
                <View style={styles.nameRow}>
                  <View
                    style={[
                      styles.miniRadialBadge,
                      is09 ? styles.badge09 : styles.badge08,
                    ]}
                  >
                    <Text style={styles.miniRadialText}>
                      {bombero.idRadial}
                    </Text>
                  </View>
                  <Text style={styles.nameText} numberOfLines={2}>
                    {bombero.nombre || bombero.nombreBombero}
                  </Text>
                </View>
                <Text style={styles.cargoText}>
                  {bombero.cargo || "VOLUNTARIO"}
                </Text>
              </View>

              <TouchableOpacity onPress={onClose} style={styles.closeBtn}>
                <X size={20} color="#94a3b8" />
              </TouchableOpacity>
            </View>

            {/* Estado Operativo */}
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>ESTADO OPERACIONAL:</Text>
              <View
                style={[
                  styles.statusPill,
                  {
                    backgroundColor: pillTheme.bg,
                    borderColor: pillTheme.border,
                  },
                ]}
              >
                <View
                  style={[
                    styles.dot,
                    { backgroundColor: pillTheme.dot },
                  ]}
                />
                <Text
                  style={[
                    styles.statusPillText,
                    { color: pillTheme.text },
                  ]}
                >
                  {pillTheme.label}
                </Text>
              </View>
            </View>

            {/* N° Registro */}
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>N° REGISTRO:</Text>
              <Text style={styles.infoValue}>
                #{bombero.idRegistro || bombero.idRadial}
              </Text>
            </View>

            {/* Compañía */}
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>COMPAÑÍA:</Text>
              <Text style={styles.infoValue}>
                {bombero.compania || 1}° COMPAÑÍA
              </Text>
            </View>

            {/* Calidad de Conductor */}
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>HABILITADO CONDUCTOR:</Text>
              <Text
                style={[
                  styles.infoValue,
                  { color: bombero.conductor ? "#38bdf8" : "#94a3b8" },
                ]}
              >
                {bombero.conductor ? "SÍ (CONDUCTOR)" : "NO"}
              </Text>
            </View>

            {/* Teléfono de Contacto */}
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>CONTACTO:</Text>
              <TouchableOpacity
                onPress={handleCallPhone}
                disabled={!bombero.telefono}
              >
                <Text
                  style={[
                    styles.infoValue,
                    bombero.telefono ? { color: "#60a5fa" } : null,
                  ]}
                >
                  {phoneFormatted}
                </Text>
              </TouchableOpacity>
            </View>

            {/* Panel de Gestión de Estado para Comandancia / Capitán / Operador (Incluye SUSPENDIDO, 6 botones de igual tamaño) */}
            {canManageStatus ? (
              <View style={styles.adminStatusSection}>
                <Text style={styles.adminStatusTitle}>
                  CAMBIAR ESTADO OPERACIONAL:
                </Text>
                <View style={styles.adminBtnsRow}>
                  {ADMIN_STATUS_OPTIONS.map((opt) => {
                    const active = est === opt.key;
                    return (
                      <TouchableOpacity
                        key={opt.key}
                        style={[
                          styles.adminStatusChip,
                          active && {
                            backgroundColor: opt.bg,
                            borderColor: opt.border,
                          },
                        ]}
                        onPress={() => handleChangeBomberoStatus(opt.key)}
                        activeOpacity={0.8}
                      >
                        <Text
                          style={[
                            styles.adminStatusChipText,
                            active && { color: "#ffffff" },
                          ]}
                          numberOfLines={1}
                        >
                          {opt.label}
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
                  style={styles.whatsappButton}
                  onPress={handleOpenWhatsApp}
                  activeOpacity={0.85}
                >
                  <MessageCircle
                    size={17}
                    color="#ffffff"
                    style={{ marginRight: 6 }}
                  />
                  <Text style={styles.whatsappButtonText}>WHATSAPP</Text>
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.callButton}
                  onPress={handleCallPhone}
                  activeOpacity={0.85}
                >
                  <PhoneCall
                    size={17}
                    color="#ffffff"
                    style={{ marginRight: 6 }}
                  />
                  <Text style={styles.whatsappButtonText}>LLAMAR</Text>
                </TouchableOpacity>
              </View>
            ) : null}

            {/* Botón Cerrar */}
            <TouchableOpacity
              style={styles.closeButton}
              onPress={onClose}
              activeOpacity={0.8}
            >
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
    backgroundColor: "rgba(0,0,0,0.85)",
    justifyContent: "center",
    alignItems: "center",
    padding: 20,
  },
  card: {
    width: "100%",
    maxWidth: 380,
    borderRadius: 22,
    overflow: "hidden",
    borderWidth: 1.5,
    borderColor: "rgba(255,255,255,0.12)",
  },
  gradient: {
    padding: 20,
  },
  header: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 16,
    paddingBottom: 14,
    borderBottomWidth: 1,
    borderBottomColor: "rgba(255,255,255,0.08)",
  },
  photoWrapper: {
    width: 52,
    height: 52,
    borderRadius: 14,
    backgroundColor: "#0a0d14",
    borderWidth: 1.5,
    borderColor: "#7f1d1d",
    alignItems: "center",
    justifyContent: "center",
    overflow: "hidden",
  },
  photoImage: {
    width: 52,
    height: 52,
  },
  nameRow: {
    flexDirection: "row",
    alignItems: "center",
    flexWrap: "wrap",
    gap: 6,
  },
  miniRadialBadge: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 6,
    alignItems: "center",
    justifyContent: "center",
  },
  badge09: {
    backgroundColor: "#064e3b",
    borderWidth: 1,
    borderColor: "#10b981",
  },
  badge08: {
    backgroundColor: "#450a0a",
    borderWidth: 1,
    borderColor: "#ef4444",
  },
  miniRadialText: {
    fontSize: 12,
    fontWeight: "900",
    color: "#ffffff",
  },
  nameText: {
    fontSize: 15,
    fontWeight: "900",
    color: "#ffffff",
    flex: 1,
  },
  cargoText: {
    fontSize: 12,
    color: "#94a3b8",
    fontWeight: "600",
    marginTop: 2,
  },
  closeBtn: {
    width: 36,
    height: 36,
    borderRadius: 10,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: "rgba(255,255,255,0.06)",
  },
  infoRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingVertical: 9,
    borderBottomWidth: 1,
    borderBottomColor: "rgba(255,255,255,0.05)",
  },
  infoLabel: {
    fontSize: 11,
    fontWeight: "800",
    color: "#94a3b8",
    letterSpacing: 0.5,
  },
  infoValue: {
    fontSize: 13,
    fontWeight: "700",
    color: "#ffffff",
  },
  statusPill: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
    borderWidth: 1,
  },
  dot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    marginRight: 6,
  },
  statusPillText: {
    fontSize: 11,
    fontWeight: "800",
  },
  adminStatusSection: {
    marginTop: 12,
    paddingTop: 10,
    borderTopWidth: 1,
    borderTopColor: "rgba(255,255,255,0.08)",
  },
  adminStatusTitle: {
    fontSize: 10,
    fontWeight: "800",
    color: "#94a3b8",
    marginBottom: 8,
    letterSpacing: 0.5,
  },
  adminBtnsRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    justifyContent: "space-between",
    rowGap: 8,
  },
  adminStatusChip: {
    width: "31.5%",
    height: 38,
    borderRadius: 10,
    backgroundColor: "#1e293b",
    borderWidth: 1,
    borderColor: "#334155",
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 4,
  },
  adminStatusChipText: {
    fontSize: 10,
    fontWeight: "900",
    color: "#cbd5e1",
    letterSpacing: 0.3,
  },
  contactButtonsRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 10,
    marginTop: 16,
  },
  whatsappButton: {
    flex: 1,
    height: 44,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: "#25d366",
    borderRadius: 12,
  },
  whatsappButtonText: {
    fontSize: 12,
    fontWeight: "900",
    color: "#ffffff",
    letterSpacing: 0.6,
  },
  callButton: {
    flex: 1,
    height: 44,
    flexDirection: "row",
    borderRadius: 12,
    backgroundColor: "#2563eb",
    alignItems: "center",
    justifyContent: "center",
  },
  closeButton: {
    marginTop: 10,
    height: 44,
    backgroundColor: "rgba(255,255,255,0.08)",
    borderRadius: 12,
    alignItems: "center",
    justifyContent: "center",
    borderWidth: 1,
    borderColor: "rgba(255,255,255,0.1)",
  },
  closeButtonText: {
    fontSize: 13,
    fontWeight: "800",
    color: "#ffffff",
    letterSpacing: 1,
  },
});
