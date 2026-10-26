import React, { useState, useRef, useMemo, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  Image,
  TextInput,
  Modal,
  Platform,
  Animated,
  Easing,
  Dimensions
} from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import * as Haptics from 'expo-haptics';
import {
  Flame,
  Users,
  Calendar,
  FileText,
  Bell,
  RefreshCw,
  MessageSquare,
  Plus,
  Bookmark,
  Info,
  Trash2,
  X
} from 'lucide-react-native';
import { useAuthStore } from '../store/useAuthStore';
import { useLicenseStore } from '../store/useLicenseStore';
import {
  useDispatchStore,
  CHILEAN_DISPATCH_KEYS,
  SPECIAL_DISPATCH_KEYS,
  DEFAULT_PROTOCOLS
} from '../store/useDispatchStore';
import { TacticalDispatchMap } from '../components/dispatch/TacticalDispatchMap';
import { ProfileDrawer } from '../components/modals/ProfileDrawer';
import { FirefighterModal } from '../components/modals/FirefighterModal';
import { AttendanceDetailModal } from '../components/modals/AttendanceDetailModal';
import { OrdenDetailModal } from '../components/modals/OrdenDetailModal';
import { ChatModal, parseChatMessages } from '../components/modals/ChatModal';
import { CreateAlertOrderModal } from '../components/modals/CreateAlertOrderModal';

function cleanSheetPrefix(s = '') {
  return String(s).replace(/^['"´`‘’]+|['"´`‘’]+$/g, '').trim();
}

// Parseo exacto de fechas de asistencia idéntico a TabScreens.kt (parseDateToDate líneas 3280-3292)
function parseAttendanceDate(dateStr = '') {
  const clean = cleanSheetPrefix(dateStr);
  if (!clean) return null;

  // dd-MM-yyyy o dd/MM/yyyy
  const dmyMatch = clean.match(/^(\d{1,2})[-/](\d{1,2})[-/](\d{4})$/);
  if (dmyMatch) {
    const day = parseInt(dmyMatch[1], 10);
    const month = parseInt(dmyMatch[2], 10) - 1;
    const year = parseInt(dmyMatch[3], 10);
    return new Date(year, month, day);
  }

  // yyyy-MM-dd o yyyy/MM/dd
  const ymdMatch = clean.match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})$/);
  if (ymdMatch) {
    const year = parseInt(ymdMatch[1], 10);
    const month = parseInt(ymdMatch[2], 10) - 1;
    const day = parseInt(ymdMatch[3], 10);
    return new Date(year, month, day);
  }

  return null;
}

// Ciclo anual bomberil idéntico a TabScreens.kt (getCycleYear líneas 3294-3302: Diciembre pertenece a year + 1)
function getCycleYear(dateObj) {
  if (!dateObj || isNaN(dateObj.getTime())) return 0;
  const year = dateObj.getFullYear();
  const month = dateObj.getMonth(); // 0 = Enero, 11 = Diciembre
  return month === 11 ? year + 1 : year;
}

// Verificación de Abono idéntica a TabScreens.kt (isAbonoValue líneas 3256-3271)
function isAbonoValue(abono, clave = '', tipo = '') {
  if (typeof abono === 'number' && Math.round(abono) === 1) return true;
  if (typeof abono === 'boolean' && abono === true) return true;
  if (typeof abono === 'string') {
    const s = abono.trim().toUpperCase();
    if (
      s === '1' ||
      s === 'SI' ||
      s === 'SÍ' ||
      s === 'TRUE' ||
      s === 'ABONO' ||
      s.includes('ABONO') ||
      s.includes('EXTRA')
    ) {
      return true;
    }
  }
  const c = String(clave || '').trim().toUpperCase();
  const t = String(tipo || '').trim().toUpperCase();
  return c.includes('ABONO') || c.includes('EXTRA') || t.includes('ABONO') || t.includes('EXTRA');
}

// Cálculo estadístico del ciclo anual idéntico a TabScreens.kt (calculateCycleStats líneas 3314-3351)
function calculateCycleStats(list = []) {
  const validRecords = list.filter((h) => {
    const st = String(h.userEstado || h.estado || '').trim().toUpperCase();
    if (!st || st === 'NO_REGISTRA' || st === 'NO REGISTRA') return false;
    const isPresent = st === 'A' || st === 'ASISTE' || st === 'C' || st === 'CDS';
    const isAbono = isAbonoValue(h.userAbono, h.clave, h.tipo);
    return !(isAbono && !isPresent);
  });

  let obligatorias = 0;
  let obligatoriasAsistidas = 0;
  let abonos = 0;

  validRecords.forEach((h) => {
    const st = String(h.userEstado || h.estado || '').trim().toUpperCase();
    const isPresent = st === 'A' || st === 'ASISTE' || st === 'C' || st === 'CDS';
    const isAbono = isAbonoValue(h.userAbono, h.clave, h.tipo);
    if (isAbono) {
      if (isPresent) abonos += 1;
    } else {
      obligatorias += 1;
      if (isPresent) obligatoriasAsistidas += 1;
    }
  });

  const factor = obligatorias / 100;
  const suma = obligatoriasAsistidas + abonos;
  const rawPercentage = factor > 0 ? suma / factor : (abonos > 0 ? 100 : 0);
  const percentage = Math.min(100, Math.max(0, rawPercentage));

  return {
    obligatorias,
    totalObligatorias: obligatorias,
    obligatoriasAsistidas,
    abonos,
    totalAbonosAsiste: abonos,
    totalEfectivas: suma,
    percentage,
    porcentaje: percentage,
    pct: percentage
  };
}

// Círculo de Progreso de Asistencia idéntico a AttendanceCircle en Components.kt (líneas 481-528)
// Con tamaño ligeramente ampliado (66px) para lectura óptima de "XX.XX%" y línea de progreso circular
function AttendanceCircle({ percentage = 0, size = 66, strokeWidth = 6.5, isDarkMode = true }) {
  const safePct = Number.isFinite(Number(percentage))
    ? Math.max(0, Math.min(100, Number(percentage)))
    : 0;
  const formattedPct = `${safePct.toFixed(2)}%`;
  const progressColor = safePct >= 50 ? '#10B981' : '#EF4444';
  const trackColor = isDarkMode ? 'rgba(51, 65, 85, 0.38)' : '#E2E8F0';
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const strokeDashoffset = circumference - (safePct / 100) * circumference;

  return (
    <View
      style={{
        width: size,
        height: size,
        alignItems: 'center',
        justifyContent: 'center',
        position: 'relative'
      }}
    >
      {Platform.OS === 'web' ? (
        <svg
          width={size}
          height={size}
          viewBox={`0 0 ${size} ${size}`}
          style={{ position: 'absolute', top: 0, left: 0, transform: 'rotate(-90deg)' }}
        >
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="none"
            stroke={trackColor}
            strokeWidth={strokeWidth}
          />
          {safePct > 0 ? (
            <circle
              cx={size / 2}
              cy={size / 2}
              r={radius}
              fill="none"
              stroke={progressColor}
              strokeWidth={strokeWidth}
              strokeDasharray={`${circumference} ${circumference}`}
              strokeDashoffset={strokeDashoffset}
              strokeLinecap="round"
            />
          ) : null}
        </svg>
      ) : (
        <View
          style={{
            position: 'absolute',
            width: size,
            height: size,
            borderRadius: size / 2,
            borderWidth: strokeWidth,
            borderColor: safePct > 0 ? progressColor : trackColor
          }}
        />
      )}

      <Text
        style={{
          color: isDarkMode ? '#FFFFFF' : '#1E293B',
          fontSize: 11.5,
          fontWeight: '900',
          letterSpacing: -0.3,
          textAlign: 'center'
        }}
        numberOfLines={1}
      >
        {formattedPct}
      </Text>
    </View>
  );
}

// En la tarjeta principal: primera palabra y penúltima palabra (ej: "DANIEL OLATE")
function formatFirefighterName(fullName = '') {
  const parts = String(fullName).trim().split(/\s+/).filter(Boolean);
  if (parts.length >= 3) {
    return `${parts[0]} ${parts[parts.length - 2]}`.toUpperCase();
  }
  return parts.join(' ').toUpperCase();
}

// En el Operador CAD: primera palabra y penúltima palabra (ej: "NICOLAS PEREZ")
function formatOperatorCadName(rawName = '') {
  const clean = String(rawName).trim();
  if (!clean || clean.toUpperCase() === 'SIN OPERADOR' || clean === '--') return 'SIN OPERADOR';
  const withoutTitle = clean
    .replace(/^(OP\.|OPERADOR|VOL\.|VOLUNTARIO|BOMBERO)\s+/i, '')
    .trim();
  const parts = withoutTitle.split(/\s+/).filter(Boolean);
  if (parts.length >= 3) {
    return `${parts[0]} ${parts[parts.length - 2]}`.toUpperCase();
  }
  return parts.join(' ').toUpperCase();
}

function getPhotoUri(foto) {
  if (!foto) return null;
  const clean = String(foto).trim().replace(/^['"]|['"]$/g, '');
  if (!clean || clean.toLowerCase().includes('logo.png')) return null;
  if (clean.startsWith('http://') || clean.startsWith('https://') || clean.startsWith('data:')) {
    return clean;
  }
  if (clean.length > 200 && !clean.includes(' ')) {
    return `data:image/jpeg;base64,${clean}`;
  }
  const base = 'https://sisbom-central.web.app/';
  return clean.startsWith('/') ? `${base}${clean.substring(1)}` : `${base}${clean}`;
}

const INTERROGATION_GUIDES = {
  '10-0': [
    { q: '¿Qué es lo que se está quemando?', k: 'SE QUEMA' },
    { q: '¿Sabe si hay personas atrapadas al interior?', k: 'PERSONAS AL INTERIOR' },
    { q: '¿De qué material es la estructura?', k: 'MATERIAL ESTRUCTURA' },
    { q: '¿Hay peligro de propagación a otras casas?', k: 'PELIGRO PROPAGACIÓN' },
    { q: '¿De cuántos pisos es la estructura?', k: 'CANTIDAD PISOS' }
  ],
  '10-1': [
    { q: '¿Qué tipo de vehículo es?', k: 'TIPO VEHÍCULO' },
    { q: '¿Hay peligro para casas o bosques cercanos?', k: 'PELIGRO PROPAGACIÓN' },
    { q: '¿Hay personas dentro del vehículo?', k: 'OCUPANTES AL INTERIOR' },
    { q: '¿Sabe qué tipo de combustible usa?', k: 'TIPO COMBUSTIBLE' }
  ],
  '10-2': [
    { q: '¿Qué se quema (pasto, basura, árboles)?', k: 'QUE SE QUEMA' },
    { q: '¿El fuego está cerca de viviendas?', k: 'PELIGRO VIVIENDAS' },
    { q: '¿Es de fácil acceso?', k: 'ACCESO FÁCIL' },
    { q: '¿Existe peligro de incendio forestal?', k: 'PELIGRO FORESTAL' }
  ],
  '10-3': [
    { q: '¿Qué le ocurrió a la persona?', k: 'SITUACIÓN ACTUAL' },
    { q: '¿Cuántas personas están lesionadas?', k: 'PERSONAS LESIONADAS' },
    { q: '¿La persona responde y respira?', k: 'RESPONDE Y RESPIRA' },
    { q: '¿La persona se encuentra atrapada?', k: 'PERSONA ATRAPADA' }
  ],
  '10-4': [
    { q: '¿Cuántos vehículos están involucrados?', k: 'CANTIDAD VEHÍCULOS' },
    { q: '¿Hay personas atrapadas?', k: 'PERSONAS ATRAPADAS' },
    { q: '¿Ve alguna fuga de combustible o humo?', k: 'FUGA O DERRAME' },
    { q: '¿Qué tipo de vehículos chocaron?', k: 'TIPOS DE VEHÍCULO' }
  ],
  '10-5': [
    { q: '¿Conoce el nombre o número del producto?', k: 'NOMBRE PRODUCTO' },
    { q: '¿Hay fuga o derrame activo en este momento?', k: 'HAY FUGA ACTIVA' },
    { q: '¿Qué cantidad aproximada se derramó?', k: 'CANTIDAD DERRAMADA' },
    { q: '¿Hay personas con síntomas o malestar?', k: 'PERSONAS AFECTADAS' }
  ],
  '10-6': [
    { q: '¿Siente el olor en la calle o adentro?', k: 'LUGAR DEL OLOR' },
    { q: '¿Viene de un cilindro o de la cañería?', k: 'PROCEDENCIA GAS' },
    { q: '¿Es gas licuado o gas natural?', k: 'TIPO DE GAS' },
    { q: '¿Hay personas con algún síntoma?', k: 'PERSONAS CON SÍNTOMAS' }
  ],
  '10-7': [
    { q: '¿Hay fuego en el lugar y dónde?', k: 'LUGAR DEL FUEGO' },
    { q: '¿Hay cables cortados en el suelo?', k: 'CABLES EN EL SUELO' },
    { q: '¿Existe riesgo para las casas cercanas?', k: 'PELIGRO VIVIENDAS' }
  ],
  '10-8': [
    { q: '¿Podría detallar qué está ocurriendo?', k: 'SITUACIÓN ACTUAL' },
    { q: '¿Hay riesgo vital inmediato?', k: 'HAY RIESGO VITAL' },
    { q: '¿Necesita a Carabineros o SAMU?', k: 'REQUIERE APOYO' }
  ],
  '10-9': [
    { q: '¿Qué servicio específico necesita?', k: 'SERVICIO SOLICITADO' },
    { q: '¿Es una situación de urgencia?', k: 'SITUACIÓN URGENCIA' }
  ],
  '10-10': [
    { q: '¿Qué es lo que se está quemando nuevamente?', k: 'QUE SE QUEMA' },
    { q: '¿Hay peligro de propagación?', k: 'PELIGRO PROPAGACIÓN' }
  ],
  '10-12': [
    { q: '¿Con qué oficial deben reportarse?', k: 'OFICIAL A CARGO' },
    { q: '¿Qué material o carro necesitan?', k: 'MATERIAL SOLICITADO' },
    { q: '¿Cuál es el punto de encuentro?', k: 'PUNTO DE ENCUENTRO' },
    { q: '¿En qué frecuencia radial trabajarán?', k: 'FRECUENCIA RADIAL' }
  ],
  '10-15': [
    { q: '¿Qué institución realiza el simulacro?', k: 'INSTITUCIÓN' },
    { q: '¿Qué escenario se está simulando?', k: 'ESCENARIO SIMULADO' },
    { q: '¿Para qué fecha está programado?', k: 'FECHA' },
    { q: '¿A qué hora comenzará?', k: 'HORA' }
  ],
  '9-0': [
    { q: '¿Quién ordena el acuartelamiento?', k: 'ORDENA' },
    { q: '¿Cuál es el grado (1, 2 o 3)?', k: 'GRADO' },
    { q: '¿Instrucciones para el personal?', k: 'INSTRUCCIÓN' }
  ],
  LLAMADO: [
    { q: '¿Quién ordena el llamado?', k: 'ORDENA' },
    { q: '¿Instrucciones para el personal?', k: 'INSTRUCCIÓN' }
  ],
  '10-30': [
    { q: '¿Qué estructura se quema y fase del fuego?', k: 'FASE FUEGO' },
    { q: '¿Hay riesgo inminente de propagación?', k: 'PROPAGACIÓN' },
    { q: '¿Se requiere apoyo de abastecimiento (Cisternas)?', k: 'ABASTECIMIENTO' }
  ],
  'ALARMA FORESTAL': [
    { q: '¿Qué tipo de vegetación se quema y avance?', k: 'VEGETACIÓN' },
    { q: '¿Hay amenaza a viviendas o infraestructura crítica?', k: 'AMENAZA VIVIENDAS' },
    { q: '¿Se solicita apoyo aéreo CONAF o brigadas?', k: 'APOYO CONAF' }
  ]
};

export const MainHomeView = ({ onLogout }) => {
  const { user, isDarkMode = true, isAirplaneMode = false, updateStatus, setEmergencyResponse } = useAuthStore();
  const {
    nombreMostrar,
    clientName,
    logoUrl,
    comuna,
    region,
    cuartelLat,
    cuartelLng,
    cuarteles,
    totalCompanias
  } = useLicenseStore();

  const {
    activeDispatch,
    dispatchesList,
    fullscreenDispatchId,
    urgentBannerMessage,
    isAudioPlaying,
    firefightersList,
    vehiculosList,
    ordersList,
    alertsList,
    attendanceList,
    isAttendanceLoading,
    centralOperatorName,
    centralOperatorId,
    isCentralActive,
    protocols,
    solicitarAperturaPuerta,
    openDoorIoT,
    confirmInitialKeyFromCentral,
    cancelDraftDispatchFromCentral,
    dispatchFromCentral,
    registerConforme,
    toggleAlertPin,
    deleteAlert,
    refreshAttendance,
    setActiveChatAlert,
    dismissFullscreenDispatch,
    clearUrgentBanner,
    stopDispatchSound
  } = useDispatchStore();

  const [currentTab, setCurrentTab] = useState('actividad'); // 'actividad' | 'ordenes' | 'alertas' | 'asistencia' | 'disponibles'
  const [attendanceHistoryFilter, setAttendanceHistoryFilter] = useState('aprobados'); // 'aprobados' | 'por_aprobar'
  const [selectedCompanyFilter, setSelectedCompanyFilter] = useState('TODAS');
  const [selectedBomberoModal, setSelectedBomberoModal] = useState(null);
  const [selectedAttendanceModal, setSelectedAttendanceModal] = useState(null);
  const [selectedOrdenModal, setSelectedOrdenModal] = useState(null);
  const [chatModalVisible, setChatModalVisible] = useState(false);
  const [createModalMode, setCreateModalMode] = useState(null); // null | 'orden' | 'alerta'
  const [gateState, setGateState] = useState('IDLE'); // 'IDLE' | 'TOCADO'
  const [profileDrawerVisible, setProfileDrawerVisible] = useState(false);

  // Flujo Oficial de Despacho Táctico idéntico a SENTINEL CAD (KeySelector -> PreDispatchConfirmModal -> DispatchDetails + CompanyUnitsSelector)
  const [showCentralConsole, setShowCentralConsole] = useState(false);
  const [pendingCadKey, setPendingCadKey] = useState(null); // { key, desc } en modal ¿DESPACHAR CLAVE?
  const [cadSirenEnabled, setCadSirenEnabled] = useState(true);
  const [cadActiveCompanies, setCadActiveCompanies] = useState(['1']);
  const [cadSelectedKey, setCadSelectedKey] = useState(null);
  const [cadSelectedKeyDesc, setCadSelectedKeyDesc] = useState('');
  const [cadDraftId, setCadDraftId] = useState(null);
  const [confirmingInitialKey, setConfirmingInitialKey] = useState(false);
  const [formClave, setFormClave] = useState('10-0');
  const [formLugar, setFormLugar] = useState('');
  const [formReferencia, setFormReferencia] = useState('');
  const [formCallerName, setFormCallerName] = useState('');
  const [formCallerPhone, setFormCallerPhone] = useState('');
  const [formCallerLoc, setFormCallerLoc] = useState('');
  const [formSupportKey, setFormSupportKey] = useState('');
  const [formSupportCommune, setFormSupportCommune] = useState('');
  const [formAnswers, setFormAnswers] = useState({});
  const [formDetalle, setFormDetalle] = useState('');
  const [cadCoords, setCadCoords] = useState(null);
  const [isSearchingAddress, setIsSearchingAddress] = useState(false);
  const [showInterrogationGuide, setShowInterrogationGuide] = useState(false);
  const [selectedUnits, setSelectedUnits] = useState([]);
  const [dispatchingNow, setDispatchingNow] = useState(false);

  // Animación limpia 100% horizontal (translateX + opacity sin scale ni colisión de pestañas)
  const tabFadeAnim = useRef(new Animated.Value(1)).current;
  const tabSlideAnim = useRef(new Animated.Value(0)).current;
  const scrollBodyRef = useRef(null);
  const [attendanceRenderLimit, setAttendanceRenderLimit] = useState(24);
  const renderLimitTimerRef = useRef(null);

  const myRadial = String(user?.idRadial || '23').trim();
  const radialNum = parseInt(myRadial.replace(/\D/g, ''), 10) || 23;
  const userEstado = String(user?.estado || '0-9').trim().toUpperCase();
  const is09 = userEstado === '0-9';
  const is08 = userEstado === '0-8';
  const isRestrictedStatus = ['PERMISO', 'LICENCIA', 'CDS', 'SUSPENDIDO'].includes(userEstado);

  // Reconocimiento reactivo instantáneo del Operador Central Activo (por idRegistro, idRadial o Nombre normalizado)
  const isMeCentralOperator = useMemo(() => {
    if (isCentralActive) return true;
    const myReg = String(user?.idRegistro || '').trim();
    const myRad = String(user?.idRadial || '').trim();
    const opId = String(centralOperatorId || '').trim();
    if (opId && ((myReg && opId === myReg) || (myRad && opId === myRad))) return true;
    const norm = (s) =>
      String(s || '')
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .trim()
        .toUpperCase();
    const myNameNorm = norm(user?.nombreBombero || user?.nombre);
    const opNameNorm = norm(centralOperatorName);
    if (
      myNameNorm &&
      opNameNorm &&
      (myNameNorm === opNameNorm ||
        formatOperatorCadName(myNameNorm) === formatOperatorCadName(opNameNorm))
    ) {
      return true;
    }
    return false;
  }, [isCentralActive, user?.idRegistro, user?.idRadial, user?.nombreBombero, user?.nombre, centralOperatorId, centralOperatorName]);

  // Visibilidad de pestañas idéntica a getVisibleTabs en MainScreen.kt
  const showDisponiblesTab = Boolean(isMeCentralOperator || myRadial === '1');
  const visibleTabs = showDisponiblesTab
    ? ['actividad', 'ordenes', 'alertas', 'asistencia', 'disponibles']
    : ['actividad', 'ordenes', 'alertas', 'asistencia'];

  const triggerHaptic = (style = 'medium') => {
    try {
      if (Platform.OS !== 'web') {
        if (style === 'heavy') Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Heavy);
        else if (style === 'success') Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
        else Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
      }
    } catch (e) {}
  };

  const switchTabAnimated = (targetTab) => {
    if (targetTab === currentTab) return;
    const prevIdx = visibleTabs.indexOf(currentTab);
    const nextIdx = visibleTabs.indexOf(targetTab);
    const dir = nextIdx >= prevIdx ? 1 : -1;

    triggerHaptic('medium');

    // Al entrar a Asistencia, montar primero las primeras 24 tarjetas visibles durante los 230ms
    // de la animación para que la transición sea igual de rápida y fluida que las demás pestañas
    if (renderLimitTimerRef.current) {
      clearTimeout(renderLimitTimerRef.current);
      renderLimitTimerRef.current = null;
    }
    if (targetTab === 'asistencia') {
      setAttendanceRenderLimit(24);
      renderLimitTimerRef.current = setTimeout(() => {
        setAttendanceRenderLimit(9999);
      }, 240);
    }

    setCurrentTab(targetTab);

    if (scrollBodyRef.current && typeof scrollBodyRef.current.scrollTo === 'function') {
      scrollBodyRef.current.scrollTo({ y: 0, animated: false });
    }

    tabFadeAnim.stopAnimation();
    tabSlideAnim.stopAnimation();
    tabFadeAnim.setValue(0);
    tabSlideAnim.setValue(dir * 32);

    Animated.parallel([
      Animated.timing(tabFadeAnim, {
        toValue: 1,
        duration: 230,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true
      }),
      Animated.timing(tabSlideAnim, {
        toValue: 0,
        duration: 230,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true
      })
    ]).start();
  };

  // Manejo inteligente del botón/gesto Atrás de Android para no cerrar la aplicación por accidente
  useEffect(() => {
    if (typeof window === 'undefined') return undefined;
    const onNativeBack = () => {
      if (pendingCadKey) {
        setPendingCadKey(null);
        return;
      }
      if (selectedAttendanceModal) {
        setSelectedAttendanceModal(null);
        return;
      }
      if (selectedOrdenModal) {
        setSelectedOrdenModal(null);
        return;
      }
      if (selectedBomberoModal) {
        setSelectedBomberoModal(null);
        return;
      }
      if (chatModalVisible) {
        setChatModalVisible(false);
        return;
      }
      if (createModalMode) {
        setCreateModalMode(null);
        return;
      }
      if (profileDrawerVisible) {
        setProfileDrawerVisible(false);
        return;
      }
      if (showCentralConsole) {
        setShowCentralConsole(false);
        return;
      }
      if (currentTab !== 'actividad') {
        switchTabAnimated('actividad');
        return;
      }
      if (window.AndroidApp?.minimizeApp) {
        window.AndroidApp.minimizeApp();
      }
    };
    window.addEventListener('sentinelBackButton', onNativeBack);
    return () => {
      window.removeEventListener('sentinelBackButton', onNativeBack);
    };
  }, [
    pendingCadKey,
    selectedAttendanceModal,
    selectedOrdenModal,
    selectedBomberoModal,
    chatModalVisible,
    createModalMode,
    profileDrawerVisible,
    showCentralConsole,
    currentTab
  ]);

  // Detección de swipe horizontal confiable en toda la pantalla (incluyendo cuando el dedo inicia sobre tarjetas o dentro del ScrollView)
  const touchStartX = useRef(null);
  const touchStartY = useRef(null);
  const touchLastX = useRef(null);
  const touchLastY = useRef(null);
  const touchStartTime = useRef(0);
  const touchMaxAbsDy = useRef(0);
  const touchSwipeTriggered = useRef(false);
  const lastSwipeTs = useRef(0);
  const swipeStateRef = useRef({});
  swipeStateRef.current = {
    currentTab,
    visibleTabs,
    switchTabAnimated,
    anyModalOpen: Boolean(
      fullscreenDispatchId ||
        pendingCadKey ||
        selectedAttendanceModal ||
        selectedOrdenModal ||
        selectedBomberoModal ||
        chatModalVisible ||
        createModalMode ||
        profileDrawerVisible
    )
  };

  const extractTouchPoint = (e) => {
    const ne = e?.nativeEvent || e;
    const t =
      ne?.touches?.[0] ||
      ne?.changedTouches?.[0] ||
      e?.touches?.[0] ||
      e?.changedTouches?.[0] ||
      ne;
    const x = t?.pageX ?? t?.clientX ?? t?.screenX ?? null;
    const y = t?.pageY ?? t?.clientY ?? t?.screenY ?? null;
    return { x, y };
  };

  const isIgnoredSwipeTarget = (e) => {
    try {
      const target = e?.target || e?.nativeEvent?.target;
      if (!target) return false;
      const tag = String(target.tagName || '').toUpperCase();
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
      if (typeof target.closest === 'function') {
        if (target.closest('.leaflet-container')) return true;
        if (target.closest('input, textarea')) return true;
      }
    } catch (_) {}
    return false;
  };

  const tryExecuteHorizontalSwipe = (dx, dy, maxDy, elapsed, minDx = 46) => {
    const now = Date.now();
    if (touchSwipeTriggered.current) return false;
    if (now - lastSwipeTs.current < 240) return false;
    const {
      currentTab: activeTab,
      visibleTabs: tabs,
      switchTabAnimated: doSwitch,
      anyModalOpen
    } = swipeStateRef.current;
    if (anyModalOpen) return false;

    const isHorizontalSwipe =
      Math.abs(dx) >= minDx &&
      Math.abs(dx) > Math.abs(dy) * 1.38 &&
      maxDy < 90 &&
      elapsed <= 900;

    if (!isHorizontalSwipe) return false;

    const currentIndex = tabs.indexOf(activeTab);
    if (currentIndex === -1) return false;

    if (dx < 0 && currentIndex < tabs.length - 1) {
      touchSwipeTriggered.current = true;
      lastSwipeTs.current = now;
      doSwitch(tabs[currentIndex + 1]);
      return true;
    }
    if (dx > 0 && currentIndex > 0) {
      touchSwipeTriggered.current = true;
      lastSwipeTs.current = now;
      doSwitch(tabs[currentIndex - 1]);
      return true;
    }
    return false;
  };

  const handleTouchStart = (e) => {
    if (swipeStateRef.current.anyModalOpen || isIgnoredSwipeTarget(e)) {
      touchStartX.current = null;
      touchStartY.current = null;
      return;
    }
    const { x, y } = extractTouchPoint(e);
    const screenW = Dimensions.get('window').width || 390;
    // Ignorar únicamente los 14px extremos del borde de gestos del sistema Android
    if (typeof x === 'number' && typeof y === 'number' && x > 14 && x < screenW - 14) {
      touchStartX.current = x;
      touchStartY.current = y;
      touchLastX.current = x;
      touchLastY.current = y;
      touchStartTime.current = Date.now();
      touchMaxAbsDy.current = 0;
      touchSwipeTriggered.current = false;
    } else {
      touchStartX.current = null;
      touchStartY.current = null;
    }
  };

  const handleTouchMove = (e) => {
    if (typeof touchStartX.current !== 'number' || typeof touchStartY.current !== 'number') {
      return;
    }
    const { x, y } = extractTouchPoint(e);
    if (typeof x === 'number' && typeof y === 'number') {
      touchLastX.current = x;
      touchLastY.current = y;
      const absDy = Math.abs(y - touchStartY.current);
      if (absDy > touchMaxAbsDy.current) {
        touchMaxAbsDy.current = absDy;
      }
      const dx = x - touchStartX.current;
      const dy = y - touchStartY.current;
      const elapsed = Date.now() - touchStartTime.current;
      // Si el usuario ya deslizó claramente en horizontal (>= 64px), cambiar de pestaña al instante sin esperar touchend (evita pérdida por touchcancel del ScrollView)
      if (Math.abs(dx) >= 64 && Math.abs(dx) > Math.abs(dy) * 1.55 && touchMaxAbsDy.current < 75) {
        tryExecuteHorizontalSwipe(dx, dy, touchMaxAbsDy.current, elapsed, 64);
      }
    }
  };

  const handleTouchEnd = (e) => {
    if (typeof touchStartX.current !== 'number' || typeof touchStartY.current !== 'number') {
      return;
    }
    const { x, y } = extractTouchPoint(e);
    const startX = touchStartX.current;
    const startY = touchStartY.current;
    const endX = typeof x === 'number' ? x : touchLastX.current;
    const endY = typeof y === 'number' ? y : touchLastY.current;
    const elapsed = Date.now() - touchStartTime.current;
    const maxDy = touchMaxAbsDy.current;

    touchStartX.current = null;
    touchStartY.current = null;
    touchLastX.current = null;
    touchLastY.current = null;
    touchMaxAbsDy.current = 0;

    if (typeof endX !== 'number' || typeof endY !== 'number') return;

    const dx = endX - startX;
    const dy = endY - startY;
    tryExecuteHorizontalSwipe(dx, dy, maxDy, elapsed, 44);
  };

  // Listeners nativos en fase de captura (window) para que los TouchableOpacity / tarjetas hijas o touchcancel de Android WebView nunca bloqueen el deslizamiento entre pestañas
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.addEventListener !== 'function') {
      return undefined;
    }
    const onWinTouchStart = (e) => handleTouchStart(e);
    const onWinTouchMove = (e) => handleTouchMove(e);
    const onWinTouchEnd = (e) => handleTouchEnd(e);
    const onWinTouchCancel = (e) => handleTouchEnd(e);
    const onWinClickCapture = (e) => {
      if (Date.now() - lastSwipeTs.current < 340) {
        try {
          e.stopPropagation();
          e.preventDefault();
        } catch (_) {}
      }
    };

    window.addEventListener('touchstart', onWinTouchStart, { capture: true, passive: true });
    window.addEventListener('touchmove', onWinTouchMove, { capture: true, passive: true });
    window.addEventListener('touchend', onWinTouchEnd, { capture: true, passive: true });
    window.addEventListener('touchcancel', onWinTouchCancel, { capture: true, passive: true });
    window.addEventListener('click', onWinClickCapture, true);

    return () => {
      window.removeEventListener('touchstart', onWinTouchStart, { capture: true });
      window.removeEventListener('touchmove', onWinTouchMove, { capture: true });
      window.removeEventListener('touchend', onWinTouchEnd, { capture: true });
      window.removeEventListener('touchcancel', onWinTouchCancel, { capture: true });
      window.removeEventListener('click', onWinClickCapture, true);
    };
  }, []);

  const handleResponseForDispatch = async (serviceId, type) => {
    triggerHaptic('heavy');
    const targetId = String(serviceId || activeDispatch?.idServicio || '').trim();
    if (targetId) {
      await setEmergencyResponse(targetId, type);
    }
  };

  const handleResponse = async (type) => {
    await handleResponseForDispatch(activeDispatch?.idServicio, type);
  };

  const handleTimbrePress = async () => {
    if (gateState !== 'IDLE') return;
    triggerHaptic('success');
    setGateState('TOCADO');
    try {
      window.AndroidApp?.showToast?.('Timbre tocado: Notificación enviada al Operador');
    } catch (_) {}
    await solicitarAperturaPuerta();
    setTimeout(() => {
      setGateState('IDLE');
    }, 30000);
  };

  const allActiveDispatches = useMemo(() => {
    if (Array.isArray(dispatchesList) && dispatchesList.length > 0) {
      return dispatchesList;
    }
    return activeDispatch ? [activeDispatch] : [];
  }, [dispatchesList, activeDispatch]);

  const myResponse = user?.enServicio;
  const hasAssisted = Boolean(
    myResponse &&
      allActiveDispatches.some((d) => String(myResponse) === String(d.idServicio || d.id))
  );
  const hasDeclined = Boolean(
    myResponse &&
      activeDispatch?.idServicio &&
      String(myResponse) === `-${activeDispatch.idServicio}`
  );
  const inService = Boolean(
    myResponse &&
      String(myResponse).trim() !== '0' &&
      String(myResponse).trim() !== '' &&
      !String(myResponse).trim().startsWith('-') &&
      hasAssisted
  );

  // Jerarquía de visualización según ID Radial y Rol (MainScreen.kt)
  const isComandante = myRadial === '1' || user?.cargo?.toUpperCase().includes('COMANDANTE');
  const isAyudante = myRadial === '25' || user?.cargo?.toUpperCase().includes('AYUDANTE');
  const isSecretario = myRadial === '32' || user?.cargo?.toUpperCase().includes('SECRETARIO');
  const isOperadorCad = user?.cargo?.toUpperCase().includes('OPERADOR') || isMeCentralOperator;
  const canViewAllCompanies = isComandante || isOperadorCad || isAyudante;

  const isCapitan = radialNum >= 40 && radialNum <= 49;
  const capitanCompany = isCapitan ? (radialNum % 10 || 1) : null;

  const isDirector = radialNum >= 70 && radialNum <= 79;
  const directorCompany = isDirector ? (radialNum % 10 || 1) : null;

  const canPublishOrders = isComandante || isAyudante || isOperadorCad;
  const canPublishAlerts = isComandante || isAyudante || isSecretario || isCapitan || isDirector || isOperadorCad;

  // Colores exactos de tarjeta superior según estado en MainScreen.kt (líneas 481-527)
  const getTopStatusCardColors = () => {
    if (isDarkMode) {
      switch (userEstado) {
        case '0-9':
          return { bg: '#0F3D1C', border: 'rgba(0, 176, 80, 0.50)' };
        case '0-8':
          return { bg: '#5C1414', border: 'rgba(255, 59, 48, 0.50)' };
        case 'PERMISO':
          return { bg: '#5C3308', border: 'rgba(255, 149, 0, 0.50)' };
        case 'LICENCIA':
          return { bg: '#093E5C', border: 'rgba(52, 168, 223, 0.50)' };
        case 'CDS':
          return { bg: '#5C4F08', border: 'rgba(209, 161, 0, 0.50)' };
        case 'SUSPENDIDO':
          return { bg: '#3B0C54', border: 'rgba(142, 36, 170, 0.50)' };
        default:
          return { bg: '#5C1414', border: 'rgba(255, 59, 48, 0.50)' };
      }
    } else {
      switch (userEstado) {
        case '0-9':
          return { bg: '#C8E6C9', border: '#81C784' };
        case '0-8':
          return { bg: '#FFCDD2', border: '#E57373' };
        case 'PERMISO':
          return { bg: '#FFE0B2', border: '#FFB74D' };
        case 'LICENCIA':
          return { bg: '#B3E5FC', border: '#4FC3F7' };
        case 'CDS':
          return { bg: '#FFF9C4', border: '#FFF176' };
        case 'SUSPENDIDO':
          return { bg: '#E1BEE7', border: '#BA68C8' };
        default:
          return { bg: '#FFCDD2', border: '#E57373' };
      }
    }
  };

  // Órdenes y Alertas (reales de Firestore)
  const effectiveOrders = Array.isArray(ordersList) ? ordersList : [];

  const isConformeByMe = (conformeStr = '') => {
    const list = String(conformeStr)
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    return list.includes(myRadial) || list.includes(String(user?.idRegistro || ''));
  };

  const unreadOrdersCount = effectiveOrders.filter((o) => !isConformeByMe(o.conforme)).length;
  const unreadAlertsCount = (alertsList || []).filter((a) => !isConformeByMe(a.conforme)).length;

  const handleOpenOrden = async (orden) => {
    triggerHaptic('medium');
    setSelectedOrdenModal(orden);
    if (orden.id && !isConformeByMe(orden.conforme)) {
      await registerConforme(orden.id, myRadial);
    }
  };

  const handleOpenAlerta = async (alerta) => {
    triggerHaptic('medium');
    setSelectedOrdenModal({
      ...alerta,
      isAlertaComunicado: true
    });
    if (alerta?.id && !isConformeByMe(alerta.conforme)) {
      await registerConforme(alerta.id, myRadial);
    }
  };

  // Resolver lista real de compañías de la institución (ej. 1ª y 3ª en pruebas-sisbom, o 1ª, 2ª, 3ª según dotación/carros/licencia)
  const institutionCompaniesList = useMemo(() => {
    const compSet = new Set();
    // 1. Compañías reales presentes en la dotación de bomberos (personal)
    (firefightersList || []).forEach((f) => {
      const cNum = parseInt(String(f.compania || '').replace(/\D/g, ''), 10);
      if (cNum > 0) compSet.add(String(cNum));
    });
    // 2. Compañías reales presentes en el material mayor (último dígito de idCarro o campo compania)
    (vehiculosList || []).forEach((v) => {
      const m = String(v.idCarro || v.id || '').match(/(\d+)$/);
      const cNum = m
        ? parseInt(m[1], 10)
        : parseInt(String(v.compania || '').replace(/\D/g, ''), 10);
      if (cNum > 0) compSet.add(String(cNum));
    });
    // 3. Compañías definidas explícitamente en cuarteles/companias de la licencia
    if (cuarteles && typeof cuarteles === 'object') {
      Object.keys(cuarteles).forEach((k) => {
        const cNum = parseInt(String(k).replace(/\D/g, ''), 10);
        if (cNum > 0) compSet.add(String(cNum));
      });
    }
    // 4. Respaldo inicial solo si aún no cargan datos
    if (compSet.size === 0) {
      const total = Math.max(1, Number(totalCompanias || 1));
      for (let i = 1; i <= total; i += 1) compSet.add(String(i));
    }
    return Array.from(compSet)
      .sort((a, b) => parseInt(a, 10) - parseInt(b, 10))
      .map((id) => ({
        id,
        filterLabel: `${id}° CIA`,
        alias: `${id}ª CÍA`,
        name: `${id}ª COMPAÑÍA`
      }));
  }, [firefightersList, vehiculosList, cuarteles, totalCompanias]);

  const hasMultipleCadCompanies = institutionCompaniesList.length > 1;
  const isSingleCompanyInstitution = !hasMultipleCadCompanies && (totalCompanias || 1) <= 1;

  // Evaluar si un vehículo está disponible (0-9 y sin servicio activo) igual que SENTINEL CAD
  const getCadVehicleAvailability = (v) => {
    const unitId = String(v?.idCarro || v?.id || v?.nombre || '').trim().toUpperCase();
    const est = String(v?.estado ?? '0-9').trim().toUpperCase();
    const serv = String(v?.enServicio ?? '0').trim();
    const is08Unit =
      est === '0-8' ||
      est === '0' ||
      est.includes('6-14') ||
      est.includes('FUERA');
    const inActiveDispatch = allActiveDispatches.find((d) => {
      if (cadDraftId && String(d.idServicio) === String(cadDraftId)) return false;
      const unitsObj = d.unidades || {};
      const carrosArr = Array.isArray(d.carros) ? d.carros : Object.keys(unitsObj);
      return carrosArr.some((c) => String(c).trim().toUpperCase() === unitId);
    });
    const isBusyByField = serv !== '0' && serv !== '' && serv !== '0-9' && (!cadDraftId || serv !== String(cadDraftId));
    const isBusy = Boolean(inActiveDispatch || isBusyByField);
    const isAvailable = !is08Unit && !isBusy;
    const badgeLabel = is08Unit
      ? '0-8 F/S'
      : inActiveDispatch
        ? `EN ${inActiveDispatch.clave || 'SERV'}`
        : isBusy
          ? 'EN SERVICIO'
          : '0-9 DISP';
    return { isAvailable, is08Unit, isBusy, badgeLabel };
  };

  // Calcular protocolo sugerido adaptado a las compañías activas y unidades disponibles (igual que CompanyUnitsSelector.jsx en CAD)
  const getRecommendedUnitsForKey = (key, activeComps = cadActiveCompanies) => {
    const effComps = !hasMultipleCadCompanies ? ['1'] : (activeComps || ['1']);
    const defaultProto = DEFAULT_PROTOCOLS[key] || { units: [], label: 'Estándar' };
    const rawProto = (protocols && protocols[key]) || defaultProto;
    const baseUnits =
      Array.isArray(rawProto.units) && rawProto.units.length > 0
        ? rawProto.units
        : Array.isArray(defaultProto.units)
          ? defaultProto.units
          : [];
    const basePrefixes = Array.from(
      new Set(
        baseUnits
          .map((u) => String(u || '').replace(/[-\d\s]+/g, '').trim().toUpperCase())
          .filter(Boolean)
      )
    );
    const fleet = Array.isArray(vehiculosList) ? vehiculosList : [];
    const recommended = [];

    effComps.forEach((compId) => {
      let addedForComp = false;
      baseUnits.forEach((uId) => {
        const normU = String(uId).replace(/-/g, '').trim().toUpperCase();
        const matched = fleet.find(
          (v) => String(v.idCarro || v.id).replace(/-/g, '').trim().toUpperCase() === normU
        );
        if (matched && String(matched.compania || '1') === String(compId)) {
          addedForComp = true;
          if (getCadVehicleAvailability(matched).isAvailable) {
            recommended.push(matched.idCarro || matched.id);
          }
        }
      });
      if (!addedForComp && basePrefixes.length > 0) {
        basePrefixes.forEach((prefix) => {
          const candidate = fleet.find((v) => {
            const normV = String(v.idCarro || v.id).replace(/-/g, '').trim().toUpperCase();
            return normV === `${prefix}${compId}`;
          });
          if (candidate && getCadVehicleAvailability(candidate).isAvailable) {
            recommended.push(candidate.idCarro || candidate.id);
          }
        });
      }
    });

    return {
      label: rawProto.label || defaultProto.label || 'Estándar',
      units: Array.from(new Set(recommended))
    };
  };

  // Paso 1 CAD: Seleccionar Clave -> Abre Modal ¿DESPACHAR CLAVE? (PreDispatchConfirmModal.jsx)
  const handleSelectCadKey = (key, desc) => {
    triggerHaptic('medium');
    const initialComps = !hasMultipleCadCompanies
      ? [institutionCompaniesList[0]?.id || '1']
      : cadActiveCompanies.length > 0
        ? cadActiveCompanies
        : [institutionCompaniesList[0]?.id || '1'];
    setCadActiveCompanies(initialComps);
    setCadSirenEnabled(true);
    setPendingCadKey({ key, desc });
  };

  // Paso 2 CAD: Confirmar Clave Inicial (Con o Sin Sirena) -> Crea Pre-Despacho y abre Configuración
  const handleConfirmInitialCadKey = async () => {
    if (!pendingCadKey || confirmingInitialKey) return;
    triggerHaptic('heavy');
    setConfirmingInitialKey(true);
    try {
      const { key, desc } = pendingCadKey;
      const effComps = !hasMultipleCadCompanies
        ? [institutionCompaniesList[0]?.id || '1']
        : cadActiveCompanies.length > 0
          ? cadActiveCompanies
          : ['1'];
      const rec = getRecommendedUnitsForKey(key, effComps);
      const res = await confirmInitialKeyFromCentral({
        key,
        activeCompanies: effComps,
        sirenEnabled: cadSirenEnabled
      });
      setCadSelectedKey(key);
      setCadSelectedKeyDesc(desc);
      setFormClave(key);
      setCadDraftId(res?.draftId || null);
      setSelectedUnits(rec.units);
      setFormLugar('');
      setFormReferencia('');
      setFormCallerName('');
      setFormCallerPhone('');
      setFormCallerLoc('');
      setFormSupportKey('');
      setFormSupportCommune('');
      setFormAnswers({});
      setFormDetalle('');
      setCadCoords(null);
      setPendingCadKey(null);
    } finally {
      setConfirmingInitialKey(false);
    }
  };

  // Cancelar configuración de despacho y anular Pre-Despacho en Firestore
  const handleCancelCadDraft = async () => {
    triggerHaptic('medium');
    const draftToCancel = cadDraftId;
    setCadSelectedKey(null);
    setCadSelectedKeyDesc('');
    setCadDraftId(null);
    setFormLugar('');
    setFormReferencia('');
    setFormCallerName('');
    setFormCallerPhone('');
    setFormCallerLoc('');
    setFormSupportKey('');
    setFormSupportCommune('');
    setFormAnswers({});
    setFormDetalle('');
    setCadCoords(null);
    setSelectedUnits([]);
    if (draftToCancel) {
      await cancelDraftDispatchFromCentral(draftToCancel);
    }
  };

  // Reconstruir Pre-Informe Inicial igual que DispatchDetails.jsx en SENTINEL CAD
  const rebuildCadPreReport = (rRef, rName, rLoc, rAnswers) => {
    const lines = [];
    if (String(rRef || '').trim()) lines.push(`REF: ${String(rRef).trim().toUpperCase()}`);
    if (String(rName || '').trim()) lines.push(`ALERTANTE: ${String(rName).trim().toUpperCase()}`);
    if (String(rLoc || '').trim()) lines.push(`EN EL LUGAR: ${String(rLoc).trim().toUpperCase()}`);
    const qEntries = Object.entries(rAnswers || {}).filter(([_, v]) => String(v || '').trim());
    if (qEntries.length > 0) {
      lines.push('--- INFORME INICIAL ---');
      qEntries.forEach(([k, val]) => {
        lines.push(`${k}: ${String(val).trim().toUpperCase()}`);
      });
    }
    setFormDetalle(lines.join('\n'));
  };

  // Georreferenciación de dirección en el mapa igual que SENTINEL CAD
  const handleSearchCadAddress = async () => {
    const clean = String(formLugar || '').trim();
    if (!clean || clean.length < 3 || isSearchingAddress) return;
    triggerHaptic('medium');
    setIsSearchingAddress(true);
    try {
      const qParts = [clean];
      if (comuna) qParts.push(comuna);
      if (region) qParts.push(region);
      qParts.push('Chile');
      const url = `https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=cl&q=${encodeURIComponent(
        qParts.join(', ')
      )}`;
      const res = await fetch(url, {
        headers: { Accept: 'application/json' }
      });
      const data = await res.json();
      if (Array.isArray(data) && data[0]?.lat && data[0]?.lon) {
        setCadCoords({
          lat: parseFloat(data[0].lat),
          lng: parseFloat(data[0].lon)
        });
        try {
          window.AndroidApp?.showToast?.('📍 Punto localizado en el mapa táctico');
        } catch (_) {}
      } else {
        try {
          window.AndroidApp?.showToast?.('Dirección registrada (coordenadas de cuartel por defecto)');
        } catch (_) {}
      }
    } catch (_) {
    } finally {
      setIsSearchingAddress(false);
    }
  };

  // Paso 3 CAD: Confirmar y Emitir Despacho Táctico Definitivo
  const handleDispatchFromCentral = async () => {
    const activeKey = cadSelectedKey || formClave || '10-0';
    const requiresUnits = activeKey !== '9-0' && activeKey !== 'LLAMADO';
    if ((requiresUnits && selectedUnits.length === 0) || dispatchingNow) return;
    triggerHaptic('heavy');
    setDispatchingNow(true);
    try {
      const effComps = !hasMultipleCadCompanies
        ? [institutionCompaniesList[0]?.id || '1']
        : cadActiveCompanies.length > 0
          ? cadActiveCompanies
          : ['1'];
      const res = await dispatchFromCentral({
        draftId: cadDraftId,
        clave: activeKey,
        lugar: formLugar.trim() || 'DIRECCIÓN PENDIENTE',
        referencia: formReferencia.trim(),
        comuna: formSupportCommune.trim() || comuna || '',
        claveApoyo: formSupportKey.trim(),
        callerName: formCallerName.trim(),
        callerPhone: formCallerPhone.trim(),
        preinforme: formDetalle.trim(),
        sirenEnabled: cadSirenEnabled,
        coords: cadCoords,
        activeCompanies: effComps,
        vehiculosSeleccionados: selectedUnits
      });
      if (res?.success) {
        try {
          window.AndroidApp?.showToast?.(`🚨 Despacho ${activeKey} emitido a ${selectedUnits.join(', ')}`);
        } catch (_) {}
        setCadSelectedKey(null);
        setCadSelectedKeyDesc('');
        setCadDraftId(null);
        setFormLugar('');
        setFormReferencia('');
        setFormCallerName('');
        setFormCallerPhone('');
        setFormCallerLoc('');
        setFormSupportKey('');
        setFormSupportCommune('');
        setFormAnswers({});
        setFormDetalle('');
        setCadCoords(null);
        setSelectedUnits([]);
        setShowCentralConsole(false);
      }
    } finally {
      setDispatchingNow(false);
    }
  };

  const toggleVehicleUnit = (unitName) => {
    triggerHaptic('medium');
    setSelectedUnits((prev) =>
      prev.includes(unitName) ? prev.filter((u) => u !== unitName) : [...prev, unitName]
    );
  };

  // Filtrado de Bomberos en Disponibles
  const allFirefighters = Array.isArray(firefightersList) ? firefightersList : [];

  const displayedFirefighters = allFirefighters
    .filter((f) => {
      if (isSingleCompanyInstitution) return true;
      if (isCapitan) return Number(f.compania) === capitanCompany;
      if (isDirector) return Number(f.compania) === directorCompany;

      if (selectedCompanyFilter && selectedCompanyFilter !== 'TODAS') {
        const selectedCompNum = parseInt(String(selectedCompanyFilter).replace(/\D/g, ''), 10);
        if (selectedCompNum > 0) {
          return Number(f.compania) === selectedCompNum;
        }
      }
      return true;
    })
    .sort((a, b) => {
      const numA = parseInt(String(a.idRadial).replace(/\D/g, ''), 10) || 999;
      const numB = parseInt(String(b.idRadial).replace(/\D/g, ''), 10) || 999;
      return numA - numB;
    });

  // Título de sección inferior dentro de TopAppBarView (MainScreen.kt líneas 732-759)
  const renderTabHeaderTitle = (tab) => {
    let prefix = 'DESPACHOS ';
    let highlight = 'ACTIVOS';

    switch (tab) {
      case 'actividad':
        if (isMeCentralOperator || showCentralConsole) {
          prefix = 'CENTRAL DE ';
          highlight = 'DESPACHO CAD';
        } else {
          prefix = 'DESPACHOS ';
          highlight = 'ACTIVOS';
        }
        break;
      case 'ordenes':
        prefix = 'ORDENES ';
        highlight = 'DEL DIA';
        break;
      case 'alertas':
        prefix = 'MURO ';
        highlight = 'DE COMUNICACIONES';
        break;
      case 'asistencia':
        prefix = 'HISTORIAL ';
        highlight = 'DE ASISTENCIAS';
        break;
      case 'disponibles':
        prefix = 'PERSONAL ';
        highlight = 'DISPONIBLE';
        break;
      default:
        prefix = 'DESPACHOS ';
        highlight = 'ACTIVOS';
    }

    return (
      <Text style={styles.sectionFooterTitle}>
        <Text
          style={[
            styles.titlePrefixGray,
            { color: isDarkMode ? 'rgba(255, 255, 255, 0.50)' : 'rgba(15, 23, 42, 0.50)' }
          ]}
        >
          {prefix}
        </Text>
        <Text
          style={[
            styles.titleHighlightWhite,
            { color: isDarkMode ? '#FFFFFF' : '#0F172A' }
          ]}
        >
          {highlight}
        </Text>
      </Text>
    );
  };

  // =========================================================================
  // 1. PESTAÑA ACTIVIDAD / DESPACHO (Con Flujo Oficial SENTINEL CAD + Múltiples Despachos)
  // =========================================================================
  const renderActividadTab = () => {
    const formattedOpName = formatOperatorCadName(
      centralOperatorName || activeDispatch?.operadorInicial || 'SIN OPERADOR'
    );
    const hasOperator = formattedOpName !== 'SIN OPERADOR';
    const showCadDispatchPanel = Boolean(isMeCentralOperator || showCentralConsole);
    const activeProtocolRec = cadSelectedKey
      ? getRecommendedUnitsForKey(cadSelectedKey, cadActiveCompanies)
      : { label: 'Estándar', units: [] };
    const activeGuideQuestions = cadSelectedKey ? INTERROGATION_GUIDES[cadSelectedKey] || [] : [];
    const effectiveActiveCompanies = !hasMultipleCadCompanies
      ? [institutionCompaniesList[0]?.id || '1']
      : cadActiveCompanies.length > 0
        ? cadActiveCompanies
        : ['1'];
    const filteredFleetForCad = (
      Array.isArray(vehiculosList) && vehiculosList.length > 0
        ? vehiculosList
        : [
            { id: 'B-1', idCarro: 'B-1', nombre: 'B-1', tipo: 'BOMBA', compania: 1, estado: '0-9' },
            { id: 'BX-1', idCarro: 'BX-1', nombre: 'BX-1', tipo: 'BOMBA', compania: 1, estado: '0-9' },
            { id: 'R-1', idCarro: 'R-1', nombre: 'R-1', tipo: 'RESCATE', compania: 1, estado: '0-9' },
            { id: 'Z-1', idCarro: 'Z-1', nombre: 'Z-1', tipo: 'CISTERNA', compania: 1, estado: '0-9' }
          ]
    ).filter((v) => {
      if (!hasMultipleCadCompanies) return true;
      return effectiveActiveCompanies.includes(String(v.compania || '1'));
    });

    return (
      <View style={styles.tabScrollContainer}>
        {/* Operador CAD Info Card (Solo visible si el usuario NO es el propio Operador CAD) */}
        {!isMeCentralOperator ? (
          <View
            style={[
              styles.glassCard,
              {
                backgroundColor: isDarkMode ? '#120A0A' : '#FFFFFF',
                borderColor: isDarkMode
                  ? 'rgba(239, 68, 68, 0.10)'
                  : 'rgba(15, 23, 42, 0.12)',
                paddingHorizontal: 18,
                paddingVertical: 16,
                marginBottom: 14
              }
            ]}
          >
            <View style={styles.cadOperatorRow}>
              <View style={styles.cadOperatorLeft}>
                <View
                  style={[
                    styles.liveStatusDot,
                    { backgroundColor: hasOperator ? '#4ADE80' : '#94A3B8' }
                  ]}
                />
                <View style={{ flex: 1 }}>
                  <Text
                    style={[
                      styles.cadOperatorText,
                      { color: isDarkMode ? '#FFFFFF' : '#0F172A' }
                    ]}
                    numberOfLines={1}
                  >
                    OPERADOR CAD: {formattedOpName}
                  </Text>
                </View>
              </View>

              <TouchableOpacity
                style={[
                  styles.timbreButton,
                  {
                    backgroundColor:
                      gateState === 'TOCADO'
                        ? 'rgba(16, 185, 129, 0.20)'
                        : isDarkMode
                          ? 'rgba(127, 29, 29, 0.60)'
                          : '#FEE2E2'
                  }
                ]}
                disabled={gateState === 'TOCADO'}
                onPress={handleTimbrePress}
                activeOpacity={0.8}
              >
                <Text
                  style={[
                    styles.timbreButtonText,
                    {
                      color:
                        gateState === 'TOCADO'
                          ? '#10B981'
                          : isDarkMode
                            ? '#FCA5A5'
                            : '#DC2626'
                    }
                  ]}
                >
                  {gateState === 'TOCADO' ? 'TOCADO' : 'TIMBRE'}
                </Text>
              </TouchableOpacity>
            </View>
          </View>
        ) : null}

        {/* =================================================================== */}
        {/* FLUJO OFICIAL DE DESPACHO SENTINEL CAD (CUANDO ES OPERADOR ACTIVO)  */}
        {/* =================================================================== */}
        {showCadDispatchPanel ? (
          <View
            style={[
              styles.glassCard,
              {
                backgroundColor: isDarkMode ? '#0B1120' : '#FFFFFF',
                borderColor: isDarkMode ? 'rgba(239, 68, 68, 0.28)' : 'rgba(15, 23, 42, 0.15)',
                padding: 14,
                marginBottom: 16
              }
            ]}
          >
            {/* Cabecera del Módulo CAD */}
            <View
              style={{
                flexDirection: 'row',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginBottom: 12,
                paddingBottom: 10,
                borderBottomWidth: 1,
                borderBottomColor: isDarkMode ? 'rgba(255,255,255,0.08)' : '#E2E8F0'
              }}
            >
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <View
                  style={{
                    width: 28,
                    height: 28,
                    borderRadius: 8,
                    backgroundColor: 'rgba(239, 68, 68, 0.16)',
                    borderWidth: 1,
                    borderColor: 'rgba(239, 68, 68, 0.4)',
                    alignItems: 'center',
                    justifyContent: 'center'
                  }}
                >
                  <Flame size={16} color="#EF4444" />
                </View>
                <View>
                  <Text
                    style={{
                      color: isDarkMode ? '#FFFFFF' : '#0F172A',
                      fontSize: 13,
                      fontWeight: '900',
                      letterSpacing: 0.5
                    }}
                  >
                    {!cadSelectedKey ? '1. SELECCIONAR CLAVE' : '2. CONFIGURACIÓN DE DESPACHO'}
                  </Text>
                  <Text style={{ color: '#94A3B8', fontSize: 10, fontWeight: '700' }}>
                    CENTRAL DE ALARMAS Y DESPACHO SENTINEL CAD
                  </Text>
                </View>
              </View>

              {showCentralConsole && !isMeCentralOperator ? (
                <TouchableOpacity
                  onPress={() => setShowCentralConsole(false)}
                  style={{
                    paddingHorizontal: 10,
                    paddingVertical: 5,
                    borderRadius: 8,
                    backgroundColor: 'rgba(239, 68, 68, 0.15)'
                  }}
                >
                  <Text style={{ color: '#F87171', fontSize: 10, fontWeight: '900' }}>CERRAR</Text>
                </TouchableOpacity>
              ) : null}
            </View>

            {!cadSelectedKey ? (
              /* =============================================================== */
              /* PASO 1 CAD: GRILLA 3x5 DE CLAVES + 2 BOTONES ESPECIALES         */
              /* =============================================================== */
              <View>
                <View
                  style={{
                    flexDirection: 'row',
                    flexWrap: 'wrap',
                    justifyContent: 'space-between',
                    gap: 8
                  }}
                >
                  {CHILEAN_DISPATCH_KEYS.map((item) => (
                    <TouchableOpacity
                      key={item.key}
                      style={{
                        width: '31.3%',
                        backgroundColor: isDarkMode ? 'rgba(30, 41, 59, 0.65)' : '#F8FAFC',
                        borderWidth: 1,
                        borderColor: isDarkMode ? 'rgba(148, 163, 184, 0.20)' : '#CBD5E1',
                        borderRadius: 12,
                        paddingVertical: 11,
                        paddingHorizontal: 6,
                        alignItems: 'center',
                        justifyContent: 'center'
                      }}
                      onPress={() => handleSelectCadKey(item.key, item.desc)}
                      activeOpacity={0.78}
                    >
                      <Text
                        style={{
                          color: isDarkMode ? '#FFFFFF' : '#0F172A',
                          fontSize: item.key === 'LLAMADO' ? 11.5 : 15,
                          fontWeight: '900',
                          letterSpacing: 0.3
                        }}
                        numberOfLines={1}
                      >
                        {item.key}
                      </Text>
                      <Text
                        style={{
                          color: '#94A3B8',
                          fontSize: 9.5,
                          fontWeight: '700',
                          marginTop: 2,
                          textAlign: 'center'
                        }}
                        numberOfLines={1}
                      >
                        {item.desc}
                      </Text>
                    </TouchableOpacity>
                  ))}
                </View>

                {/* Botones Especiales CAD: 10-30 Incendio Declarado y Alarma Forestal */}
                <View
                  style={{
                    flexDirection: 'row',
                    gap: 8,
                    marginTop: 10,
                    paddingTop: 10,
                    borderTopWidth: 1,
                    borderTopColor: isDarkMode ? 'rgba(255,255,255,0.08)' : '#E2E8F0'
                  }}
                >
                  {SPECIAL_DISPATCH_KEYS.map((sp) => {
                    const isForestal = sp.key === 'ALARMA FORESTAL';
                    return (
                      <TouchableOpacity
                        key={sp.key}
                        style={{
                          flex: 1,
                          backgroundColor: isForestal
                            ? 'rgba(217, 119, 6, 0.22)'
                            : 'rgba(220, 38, 38, 0.24)',
                          borderWidth: 1.5,
                          borderColor: isForestal ? '#F59E0B' : '#EF4444',
                          borderRadius: 12,
                          paddingVertical: 11,
                          paddingHorizontal: 8,
                          alignItems: 'center',
                          justifyContent: 'center'
                        }}
                        onPress={() => handleSelectCadKey(sp.key, sp.desc)}
                        activeOpacity={0.8}
                      >
                        <Text
                          style={{
                            color: isForestal ? '#FDE68A' : '#FECACA',
                            fontSize: 11,
                            fontWeight: '900',
                            textAlign: 'center',
                            letterSpacing: 0.3
                          }}
                          numberOfLines={1}
                        >
                          {isForestal ? '🌲 10-2 ALARMA FORESTAL' : '🔥 10-30 INCENDIO DECLARADO'}
                        </Text>
                        <Text
                          style={{
                            color: isForestal ? '#FBBF24' : '#F87171',
                            fontSize: 8.5,
                            fontWeight: '800',
                            marginTop: 2
                          }}
                          numberOfLines={1}
                        >
                          {isForestal ? 'EMERGENCIA FORESTAL MAYOR' : 'EMERGENCIA ESTRUCTURAL MAYOR'}
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                </View>
              </View>
            ) : (
              /* =============================================================== */
              /* PASO 3 CAD: FORMULARIO TÁCTICO COMPLETO (DispatchDetails.jsx)   */
              /* =============================================================== */
              <View>
                {/* Banner de Clave Seleccionada + Botón Cancelar Pre-Despacho */}
                <View
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    backgroundColor: 'rgba(220, 38, 38, 0.14)',
                    borderWidth: 1,
                    borderColor: 'rgba(239, 68, 68, 0.35)',
                    borderRadius: 12,
                    paddingHorizontal: 12,
                    paddingVertical: 10,
                    marginBottom: 12
                  }}
                >
                  <View style={{ flex: 1, paddingRight: 8 }}>
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                      <Text
                        style={{
                          color: '#F87171',
                          fontSize: 9,
                          fontWeight: '900',
                          letterSpacing: 0.8
                        }}
                      >
                        CLAVE EN PRE-DESPACHO
                      </Text>
                      <View
                        style={{
                          paddingHorizontal: 6,
                          paddingVertical: 2,
                          borderRadius: 6,
                          backgroundColor: cadSirenEnabled
                            ? 'rgba(239, 68, 68, 0.25)'
                            : 'rgba(100, 116, 139, 0.25)'
                        }}
                      >
                        <Text
                          style={{
                            color: cadSirenEnabled ? '#FCA5A5' : '#CBD5E1',
                            fontSize: 8.5,
                            fontWeight: '900'
                          }}
                        >
                          {cadSirenEnabled ? '🚨 CON SIRENA' : '🔕 SIN SIRENA'}
                        </Text>
                      </View>
                    </View>
                    <Text
                      style={{
                        color: '#FFFFFF',
                        fontSize: 18,
                        fontWeight: '900',
                        marginTop: 2
                      }}
                    >
                      {cadSelectedKey}{' '}
                      <Text style={{ color: '#CBD5E1', fontSize: 12, fontWeight: '700' }}>
                        — {cadSelectedKeyDesc}
                      </Text>
                    </Text>
                  </View>

                  <TouchableOpacity
                    onPress={handleCancelCadDraft}
                    style={{
                      backgroundColor: 'rgba(239, 68, 68, 0.22)',
                      borderWidth: 1,
                      borderColor: 'rgba(239, 68, 68, 0.5)',
                      borderRadius: 10,
                      paddingHorizontal: 10,
                      paddingVertical: 7
                    }}
                    activeOpacity={0.8}
                  >
                    <Text style={{ color: '#FCA5A5', fontSize: 10.5, fontWeight: '900' }}>
                      ✕ CANCELAR
                    </Text>
                  </TouchableOpacity>
                </View>

                {/* Campos especiales para 10-12 (Apoyo a otros Cuerpos) */}
                {cadSelectedKey === '10-12' ? (
                  <View style={{ flexDirection: 'row', gap: 8, marginBottom: 10 }}>
                    <View style={{ flex: 1 }}>
                      <Text style={styles.consoleLabel}>CLAVE DEL APOYO</Text>
                      <TextInput
                        style={[
                          styles.consoleInput,
                          {
                            color: isDarkMode ? '#FFFFFF' : '#0F172A',
                            backgroundColor: isDarkMode ? 'rgba(0,0,0,0.35)' : '#F8FAFC',
                            marginBottom: 0
                          }
                        ]}
                        value={formSupportKey}
                        onChangeText={(t) => setFormSupportKey(t.toUpperCase())}
                        placeholder="Ej: 10-0-1"
                        placeholderTextColor="#64748B"
                      />
                    </View>
                    <View style={{ flex: 1 }}>
                      <Text style={styles.consoleLabel}>COMUNA DE DESTINO</Text>
                      <TextInput
                        style={[
                          styles.consoleInput,
                          {
                            color: isDarkMode ? '#FFFFFF' : '#0F172A',
                            backgroundColor: isDarkMode ? 'rgba(0,0,0,0.35)' : '#F8FAFC',
                            marginBottom: 0
                          }
                        ]}
                        value={formSupportCommune}
                        onChangeText={(t) => setFormSupportCommune(t.toUpperCase())}
                        placeholder="Ej: NANCAGUA"
                        placeholderTextColor="#64748B"
                      />
                    </View>
                  </View>
                ) : null}

                {/* Dirección Exacta + Botón Georreferenciar */}
                <Text style={styles.consoleLabel}>DIRECCIÓN EXACTA DE LA EMERGENCIA</Text>
                <View style={{ flexDirection: 'row', gap: 8, marginBottom: 10 }}>
                  <TextInput
                    style={[
                      styles.consoleInput,
                      {
                        flex: 1,
                        marginBottom: 0,
                        color: isDarkMode ? '#FFFFFF' : '#0F172A',
                        backgroundColor: isDarkMode ? 'rgba(0,0,0,0.35)' : '#F8FAFC'
                      }
                    ]}
                    value={formLugar}
                    onChangeText={(t) => setFormLugar(t.toUpperCase())}
                    onBlur={handleSearchCadAddress}
                    placeholder="CALLE Y NÚMERO O INTERSECCIÓN..."
                    placeholderTextColor="#64748B"
                  />
                  <TouchableOpacity
                    onPress={handleSearchCadAddress}
                    disabled={isSearchingAddress}
                    style={{
                      backgroundColor: cadCoords
                        ? 'rgba(16, 185, 129, 0.22)'
                        : 'rgba(59, 130, 246, 0.22)',
                      borderWidth: 1,
                      borderColor: cadCoords ? '#10B981' : '#3B82F6',
                      borderRadius: 10,
                      paddingHorizontal: 12,
                      alignItems: 'center',
                      justifyContent: 'center'
                    }}
                    activeOpacity={0.8}
                  >
                    <Text
                      style={{
                        color: cadCoords ? '#34D399' : '#93C5FD',
                        fontSize: 10.5,
                        fontWeight: '900'
                      }}
                    >
                      {isSearchingAddress ? '...' : cadCoords ? '✓ GEO' : '📍 MAPA'}
                    </Text>
                  </TouchableOpacity>
                </View>

                {/* Referencia y Datos del Alertante */}
                <Text style={styles.consoleLabel}>REFERENCIA / ESQUINA / SECTOR</Text>
                <TextInput
                  style={[
                    styles.consoleInput,
                    {
                      color: isDarkMode ? '#FFFFFF' : '#0F172A',
                      backgroundColor: isDarkMode ? 'rgba(0,0,0,0.35)' : '#F8FAFC',
                      marginBottom: 10
                    }
                  ]}
                  value={formReferencia}
                  onChangeText={(t) => {
                    const up = t.toUpperCase();
                    setFormReferencia(up);
                    rebuildCadPreReport(up, formCallerName, formCallerLoc, formAnswers);
                  }}
                  placeholder="FRENTE A PLAZA / VILLA / KM..."
                  placeholderTextColor="#64748B"
                />

                <View style={{ flexDirection: 'row', gap: 8, marginBottom: 10 }}>
                  <View style={{ flex: 1 }}>
                    <Text style={styles.consoleLabel}>QUIÉN LLAMA</Text>
                    <TextInput
                      style={[
                        styles.consoleInput,
                        {
                          marginBottom: 0,
                          color: isDarkMode ? '#FFFFFF' : '#0F172A',
                          backgroundColor: isDarkMode ? 'rgba(0,0,0,0.35)' : '#F8FAFC'
                        }
                      ]}
                      value={formCallerName}
                      onChangeText={(t) => {
                        const up = t.toUpperCase();
                        setFormCallerName(up);
                        rebuildCadPreReport(formReferencia, up, formCallerLoc, formAnswers);
                      }}
                      placeholder="NOMBRE ALERTANTE"
                      placeholderTextColor="#64748B"
                    />
                  </View>
                  <View style={{ flex: 1 }}>
                    <Text style={styles.consoleLabel}>TELÉFONO</Text>
                    <TextInput
                      style={[
                        styles.consoleInput,
                        {
                          marginBottom: 0,
                          color: isDarkMode ? '#FFFFFF' : '#0F172A',
                          backgroundColor: isDarkMode ? 'rgba(0,0,0,0.35)' : '#F8FAFC'
                        }
                      ]}
                      value={formCallerPhone}
                      onChangeText={setFormCallerPhone}
                      keyboardType="phone-pad"
                      placeholder="+56 9..."
                      placeholderTextColor="#64748B"
                    />
                  </View>
                </View>

                {/* Guía de Interrogación Rápida por Clave */}
                {activeGuideQuestions.length > 0 ? (
                  <View style={{ marginBottom: 12 }}>
                    <TouchableOpacity
                      onPress={() => setShowInterrogationGuide((v) => !v)}
                      style={{
                        flexDirection: 'row',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        backgroundColor: 'rgba(59, 130, 246, 0.12)',
                        borderWidth: 1,
                        borderColor: 'rgba(59, 130, 246, 0.30)',
                        borderRadius: 10,
                        paddingHorizontal: 12,
                        paddingVertical: 8
                      }}
                      activeOpacity={0.8}
                    >
                      <Text style={{ color: '#93C5FD', fontSize: 10.5, fontWeight: '900' }}>
                        💬 GUÍA DE INTERROGACIÓN TÁCTICA ({cadSelectedKey})
                      </Text>
                      <Text style={{ color: '#60A5FA', fontSize: 10.5, fontWeight: '900' }}>
                        {showInterrogationGuide ? 'OCULTAR ▲' : 'ABRIR ▼'}
                      </Text>
                    </TouchableOpacity>

                    {showInterrogationGuide ? (
                      <View
                        style={{
                          marginTop: 8,
                          padding: 10,
                          borderRadius: 10,
                          backgroundColor: isDarkMode ? 'rgba(15, 23, 42, 0.7)' : '#F1F5F9',
                          borderWidth: 1,
                          borderColor: isDarkMode ? 'rgba(148, 163, 184, 0.15)' : '#CBD5E1',
                          gap: 8
                        }}
                      >
                        {activeGuideQuestions.map((item) => (
                          <View key={item.k}>
                            <Text
                              style={{
                                color: '#93C5FD',
                                fontSize: 10,
                                fontWeight: '800',
                                marginBottom: 4
                              }}
                            >
                              {item.q}
                            </Text>
                            <TextInput
                              style={[
                                styles.consoleInput,
                                {
                                  marginBottom: 0,
                                  paddingVertical: 7,
                                  color: isDarkMode ? '#FFFFFF' : '#0F172A',
                                  backgroundColor: isDarkMode ? 'rgba(0,0,0,0.4)' : '#FFFFFF'
                                }
                              ]}
                              value={formAnswers[item.k] || ''}
                              onChangeText={(t) => {
                                const nextAns = { ...formAnswers, [item.k]: t.toUpperCase() };
                                setFormAnswers(nextAns);
                                rebuildCadPreReport(
                                  formReferencia,
                                  formCallerName,
                                  formCallerLoc,
                                  nextAns
                                );
                              }}
                              placeholder={item.k}
                              placeholderTextColor="#64748B"
                            />
                          </View>
                        ))}
                      </View>
                    ) : null}
                  </View>
                ) : null}

                {/* Pre-Informe Inicial */}
                <Text style={styles.consoleLabel}>PRE-INFORME INICIAL / OBSERVACIONES</Text>
                <TextInput
                  style={[
                    styles.consoleInput,
                    {
                      minHeight: 54,
                      textAlignVertical: 'top',
                      color: isDarkMode ? '#FFFFFF' : '#0F172A',
                      backgroundColor: isDarkMode ? 'rgba(0,0,0,0.35)' : '#F8FAFC',
                      marginBottom: 12
                    }
                  ]}
                  multiline
                  value={formDetalle}
                  onChangeText={setFormDetalle}
                  placeholder="PRE-INFORME PARA LAS UNIDADES..."
                  placeholderTextColor="#64748B"
                />

                {/* Selector de Compañías Activas (SOLO si la institución tiene > 1 compañía, Ley #3) */}
                {hasMultipleCadCompanies ? (
                  <View style={{ marginBottom: 12 }}>
                    <Text style={styles.consoleLabel}>COMPAÑÍAS A DESPACHAR</Text>
                    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
                      {institutionCompaniesList.map((comp) => {
                        const isCompActive = effectiveActiveCompanies.includes(comp.id);
                        return (
                          <TouchableOpacity
                            key={comp.id}
                            onPress={() => {
                              triggerHaptic('medium');
                              const nextComps = isCompActive
                                ? effectiveActiveCompanies.length > 1
                                  ? effectiveActiveCompanies.filter((c) => c !== comp.id)
                                  : effectiveActiveCompanies
                                : [...effectiveActiveCompanies, comp.id];
                              setCadActiveCompanies(nextComps);
                              const nextRec = getRecommendedUnitsForKey(cadSelectedKey, nextComps);
                              setSelectedUnits(nextRec.units);
                            }}
                            style={{
                              paddingHorizontal: 12,
                              paddingVertical: 8,
                              borderRadius: 10,
                              borderWidth: 1,
                              backgroundColor: isCompActive
                                ? 'rgba(239, 68, 68, 0.22)'
                                : isDarkMode
                                  ? 'rgba(30, 41, 59, 0.6)'
                                  : '#F1F5F9',
                              borderColor: isCompActive ? '#EF4444' : '#475569'
                            }}
                          >
                            <Text
                              style={{
                                color: isCompActive ? '#FFFFFF' : '#94A3B8',
                                fontSize: 11,
                                fontWeight: '900'
                              }}
                            >
                              {comp.alias}
                            </Text>
                          </TouchableOpacity>
                        );
                      })}
                    </View>
                  </View>
                ) : null}

                {/* Protocolo Sugerido + Grilla de Material Mayor (CompanyUnitsSelector.jsx) */}
                {cadSelectedKey !== '9-0' && cadSelectedKey !== 'LLAMADO' ? (
                  <View style={{ marginBottom: 12 }}>
                    <View
                      style={{
                        flexDirection: 'row',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        marginBottom: 8
                      }}
                    >
                      <View style={{ flex: 1, paddingRight: 8 }}>
                        <Text style={[styles.consoleLabel, { marginBottom: 2 }]}>
                          MATERIAL MAYOR A DESPACHAR
                        </Text>
                        <Text style={{ color: '#60A5FA', fontSize: 10, fontWeight: '800' }}>
                          Sugerido ({activeProtocolRec.label}):{' '}
                          {activeProtocolRec.units.length > 0
                            ? activeProtocolRec.units.join(', ')
                            : 'Selección manual'}
                        </Text>
                      </View>
                      {activeProtocolRec.units.length > 0 ? (
                        <TouchableOpacity
                          onPress={() => {
                            triggerHaptic('medium');
                            setSelectedUnits(activeProtocolRec.units);
                          }}
                          style={{
                            backgroundColor: 'rgba(59, 130, 246, 0.2)',
                            borderWidth: 1,
                            borderColor: '#3B82F6',
                            borderRadius: 8,
                            paddingHorizontal: 10,
                            paddingVertical: 6
                          }}
                        >
                          <Text style={{ color: '#93C5FD', fontSize: 10, fontWeight: '900' }}>
                            ⚡ CARGAR TREN
                          </Text>
                        </TouchableOpacity>
                      ) : null}
                    </View>

                    <View style={styles.unitsGrid}>
                      {filteredFleetForCad.map((v) => {
                        const unitName = String(v.idCarro || v.nombre || v.id).trim().toUpperCase();
                        const avail = getCadVehicleAvailability(v);
                        const isSelected = selectedUnits.includes(unitName);
                        return (
                          <TouchableOpacity
                            key={unitName}
                            style={[
                              styles.unitSelectChip,
                              {
                                minWidth: 92,
                                paddingVertical: 10,
                                paddingHorizontal: 10,
                                alignItems: 'center'
                              },
                              isSelected && styles.unitSelectChipActive,
                              !avail.isAvailable && styles.unitSelectChipDisabled
                            ]}
                            disabled={!avail.isAvailable}
                            onPress={() => toggleVehicleUnit(unitName)}
                            activeOpacity={0.8}
                          >
                            <Text
                              style={[
                                styles.unitSelectText,
                                { fontSize: 14, fontWeight: '900' },
                                isSelected && { color: '#FFFFFF' },
                                !avail.isAvailable && { color: '#64748B' }
                              ]}
                            >
                              {unitName}
                            </Text>
                            <Text
                              style={{
                                fontSize: 8.5,
                                fontWeight: '900',
                                marginTop: 2,
                                color: isSelected
                                  ? '#FECACA'
                                  : avail.is08Unit
                                    ? '#F87171'
                                    : avail.isBusy
                                      ? '#FBBF24'
                                      : '#4ADE80'
                              }}
                            >
                              {avail.badgeLabel}
                            </Text>
                          </TouchableOpacity>
                        );
                      })}
                    </View>
                  </View>
                ) : null}

                {/* Botón Final de Despacho Táctico */}
                <TouchableOpacity
                  style={[
                    styles.btnDespacharCentral,
                    ((cadSelectedKey !== '9-0' &&
                      cadSelectedKey !== 'LLAMADO' &&
                      selectedUnits.length === 0) ||
                      dispatchingNow) && {
                      backgroundColor: '#334155'
                    }
                  ]}
                  disabled={
                    (cadSelectedKey !== '9-0' &&
                      cadSelectedKey !== 'LLAMADO' &&
                      selectedUnits.length === 0) ||
                    dispatchingNow
                  }
                  onPress={handleDispatchFromCentral}
                  activeOpacity={0.85}
                >
                  <Text style={styles.btnDespacharCentralText}>
                    {dispatchingNow
                      ? 'EMITIENDO DESPACHO...'
                      : cadSelectedKey === '9-0' || cadSelectedKey === 'LLAMADO'
                        ? `🚨 CONFIRMAR Y EMITIR ${cadSelectedKey}`
                        : `🚨 CONFIRMAR Y DESPACHAR (${selectedUnits.length})`}
                  </Text>
                </TouchableOpacity>
              </View>
            )}
          </View>
        ) : null}

        {/* =================================================================== */}
        {/* MODAL PASO 2 CAD: ¿DESPACHAR CLAVE? (PreDispatchConfirmModal.jsx)   */}
        {/* =================================================================== */}
        <Modal
          visible={Boolean(pendingCadKey)}
          transparent
          animationType="fade"
          onRequestClose={() => setPendingCadKey(null)}
        >
          <View
            style={{
              flex: 1,
              backgroundColor: 'rgba(2, 6, 23, 0.84)',
              justifyContent: 'center',
              alignItems: 'center',
              padding: 18
            }}
          >
            <View
              style={{
                width: '100%',
                maxWidth: 400,
                backgroundColor: '#0F172A',
                borderRadius: 22,
                borderWidth: 1,
                borderColor: 'rgba(239, 68, 68, 0.35)',
                padding: 20
              }}
            >
              <View style={{ alignItems: 'center', marginBottom: 14 }}>
                <View
                  style={{
                    width: 54,
                    height: 54,
                    borderRadius: 27,
                    backgroundColor: 'rgba(239, 68, 68, 0.16)',
                    borderWidth: 1,
                    borderColor: 'rgba(239, 68, 68, 0.45)',
                    alignItems: 'center',
                    justifyContent: 'center',
                    marginBottom: 10
                  }}
                >
                  <Flame size={28} color="#EF4444" />
                </View>
                <Text
                  style={{
                    color: '#FFFFFF',
                    fontSize: 18,
                    fontWeight: '900',
                    letterSpacing: 0.6
                  }}
                >
                  ¿DESPACHAR CLAVE?
                </Text>
                <Text
                  style={{
                    color: '#94A3B8',
                    fontSize: 11,
                    marginTop: 4,
                    textAlign: 'center'
                  }}
                >
                  Se activará el pre-despacho en las pantallas y teléfonos institucionales.
                </Text>

                <View
                  style={{
                    marginTop: 12,
                    paddingVertical: 10,
                    paddingHorizontal: 18,
                    borderRadius: 14,
                    backgroundColor: 'rgba(239, 68, 68, 0.12)',
                    borderWidth: 1,
                    borderColor: 'rgba(239, 68, 68, 0.30)',
                    alignItems: 'center',
                    width: '100%'
                  }}
                >
                  <Text style={{ color: '#F87171', fontSize: 22, fontWeight: '900' }}>
                    {pendingCadKey?.key}
                  </Text>
                  <Text
                    style={{
                      color: '#E2E8F0',
                      fontSize: 11,
                      fontWeight: '700',
                      marginTop: 2,
                      textAlign: 'center'
                    }}
                  >
                    {pendingCadKey?.desc}
                  </Text>
                </View>
              </View>

              {/* Selector de Compañía Inicial: Oculto si es Cuerpo de 1 sola Compañía (Ley #3) */}
              {hasMultipleCadCompanies ? (
                <View style={{ marginBottom: 14 }}>
                  <Text
                    style={{
                      color: '#94A3B8',
                      fontSize: 10,
                      fontWeight: '900',
                      letterSpacing: 0.8,
                      textAlign: 'center',
                      marginBottom: 8
                    }}
                  >
                    SELECCIONAR COMPAÑÍA INICIAL
                  </Text>
                  <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
                    {institutionCompaniesList.map((comp) => {
                      const isSel = cadActiveCompanies.includes(comp.id);
                      return (
                        <TouchableOpacity
                          key={comp.id}
                          onPress={() => {
                            triggerHaptic('medium');
                            setCadActiveCompanies((prev) =>
                              prev.includes(comp.id)
                                ? prev.length > 1
                                  ? prev.filter((id) => id !== comp.id)
                                  : prev
                                : [...prev, comp.id]
                            );
                          }}
                          style={{
                            flex: 1,
                            minWidth: '45%',
                            paddingVertical: 10,
                            borderRadius: 10,
                            borderWidth: 1,
                            alignItems: 'center',
                            backgroundColor: isSel
                              ? 'rgba(239, 68, 68, 0.25)'
                              : 'rgba(30, 41, 59, 0.7)',
                            borderColor: isSel ? '#EF4444' : '#334155'
                          }}
                        >
                          <Text
                            style={{
                              color: isSel ? '#FFFFFF' : '#94A3B8',
                              fontSize: 12,
                              fontWeight: '900'
                            }}
                          >
                            {comp.alias}
                          </Text>
                        </TouchableOpacity>
                      );
                    })}
                  </View>
                </View>
              ) : null}

              {/* Accionamiento de Sirena (CON SIRENA vs SIN SIRENA) */}
              <View style={{ marginBottom: 16 }}>
                <Text
                  style={{
                    color: '#94A3B8',
                    fontSize: 10,
                    fontWeight: '900',
                    letterSpacing: 0.8,
                    textAlign: 'center',
                    marginBottom: 8
                  }}
                >
                  ACCIONAMIENTO DE SIRENA DE CUARTEL
                </Text>
                <View style={{ flexDirection: 'row', gap: 10 }}>
                  <TouchableOpacity
                    onPress={() => {
                      triggerHaptic('medium');
                      setCadSirenEnabled(true);
                    }}
                    style={{
                      flex: 1,
                      paddingVertical: 12,
                      borderRadius: 12,
                      borderWidth: 1.5,
                      alignItems: 'center',
                      backgroundColor: cadSirenEnabled
                        ? 'rgba(220, 38, 38, 0.25)'
                        : 'rgba(30, 41, 59, 0.6)',
                      borderColor: cadSirenEnabled ? '#EF4444' : '#334155'
                    }}
                  >
                    <Text style={{ fontSize: 18, marginBottom: 4 }}>🚨</Text>
                    <Text
                      style={{
                        color: cadSirenEnabled ? '#FFFFFF' : '#94A3B8',
                        fontSize: 11,
                        fontWeight: '900'
                      }}
                    >
                      CON SIRENA
                    </Text>
                    <Text style={{ color: '#64748B', fontSize: 9, fontWeight: '700' }}>
                      Toque de Cuartel
                    </Text>
                  </TouchableOpacity>

                  <TouchableOpacity
                    onPress={() => {
                      triggerHaptic('medium');
                      setCadSirenEnabled(false);
                    }}
                    style={{
                      flex: 1,
                      paddingVertical: 12,
                      borderRadius: 12,
                      borderWidth: 1.5,
                      alignItems: 'center',
                      backgroundColor: !cadSirenEnabled
                        ? 'rgba(59, 130, 246, 0.22)'
                        : 'rgba(30, 41, 59, 0.6)',
                      borderColor: !cadSirenEnabled ? '#3B82F6' : '#334155'
                    }}
                  >
                    <Text style={{ fontSize: 18, marginBottom: 4 }}>🔕</Text>
                    <Text
                      style={{
                        color: !cadSirenEnabled ? '#FFFFFF' : '#94A3B8',
                        fontSize: 11,
                        fontWeight: '900'
                      }}
                    >
                      SIN SIRENA
                    </Text>
                    <Text style={{ color: '#64748B', fontSize: 9, fontWeight: '700' }}>
                      Silencioso / Radial
                    </Text>
                  </TouchableOpacity>
                </View>
              </View>

              {/* Botones Cancelar y Confirmar Pre-Despacho */}
              <View style={{ flexDirection: 'row', gap: 10 }}>
                <TouchableOpacity
                  onPress={() => setPendingCadKey(null)}
                  style={{
                    flex: 1,
                    paddingVertical: 13,
                    borderRadius: 12,
                    backgroundColor: '#1E293B',
                    alignItems: 'center'
                  }}
                >
                  <Text style={{ color: '#CBD5E1', fontSize: 11.5, fontWeight: '800' }}>
                    CANCELAR
                  </Text>
                </TouchableOpacity>

                <TouchableOpacity
                  disabled={confirmingInitialKey}
                  onPress={handleConfirmInitialCadKey}
                  style={{
                    flex: 1.5,
                    paddingVertical: 13,
                    borderRadius: 12,
                    backgroundColor: '#DC2626',
                    alignItems: 'center'
                  }}
                >
                  <Text style={{ color: '#FFFFFF', fontSize: 11.5, fontWeight: '900' }}>
                    {confirmingInitialKey ? 'ACTIVANDO...' : 'CONFIRMAR Y CONFIGURAR'}
                  </Text>
                </TouchableOpacity>
              </View>
            </View>
          </View>
        </Modal>

        {/* =================================================================== */}
        {/* LISTA COMPLETA DE TODOS LOS DESPACHOS ACTIVOS (allActiveDispatches) */}
        {/* =================================================================== */}
        {allActiveDispatches.length > 0 ? (
          <View style={{ gap: 14 }}>
            {allActiveDispatches.length > 1 ? (
              <View
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  paddingHorizontal: 4
                }}
              >
                <Text
                  style={{
                    color: '#F87171',
                    fontSize: 11,
                    fontWeight: '900',
                    letterSpacing: 0.8
                  }}
                >
                  🚨 EMERGENCIAS EN CURSO ({allActiveDispatches.length})
                </Text>
              </View>
            ) : null}

            {allActiveDispatches.map((disp, idx) => {
              const dispId = String(disp.idServicio || disp.id || idx);
              const myAssistedThis = Boolean(
                myResponse && String(myResponse).trim() === dispId
              );
              const myDeclinedThis = Boolean(
                myResponse && String(myResponse).trim() === `-${dispId}`
              );

              return (
                <TacticalDispatchMap
                  key={dispId}
                  dispatch={disp}
                  hasAssisted={myAssistedThis}
                  hasDeclined={myDeclinedThis}
                  is08={is08 || isRestrictedStatus}
                  onAsistir={() =>
                    handleResponseForDispatch(dispId, myAssistedThis ? 'LIBRE' : 'ASISTO')
                  }
                  onNoAsistir={() => handleResponseForDispatch(dispId, 'NO_ASISTO')}
                  onCancelarAsistencia={() => handleResponseForDispatch(dispId, 'LIBRE')}
                />
              );
            })}
          </View>
        ) : (
          <View
            style={[
              styles.glassCard,
              {
                backgroundColor: isDarkMode ? '#120A0A' : '#FFFFFF',
                borderColor: isDarkMode ? 'rgba(239, 68, 68, 0.10)' : 'rgba(15, 23, 42, 0.12)',
                paddingVertical: 32,
                paddingHorizontal: 20,
                alignItems: 'center'
              }
            ]}
          >
            <Text style={{ fontSize: 32, marginBottom: 8 }}>🚒</Text>
            <Text
              style={{
                color: '#94A3B8',
                fontSize: 13,
                fontWeight: '500',
                textAlign: 'center'
              }}
            >
              Sin Despachos Activos
            </Text>
          </View>
        )}
      </View>
    );
  };

  // =========================================================================
  // 2. PESTAÑA ÓRDENES DEL DÍA (TabScreens.kt líneas 489-555: Grilla 2 Columnas)
  // =========================================================================
  const renderOrdenesTab = () => (
    <View style={styles.tabScrollContainer}>
      {effectiveOrders.length === 0 ? (
        <View
          style={[
            styles.glassCard,
            {
              backgroundColor: isDarkMode ? '#120A0A' : '#FFFFFF',
              borderColor: isDarkMode ? 'rgba(239, 68, 68, 0.10)' : 'rgba(15, 23, 42, 0.12)',
              paddingVertical: 32,
              paddingHorizontal: 20,
              alignItems: 'center'
            }
          ]}
        >
          <Text style={{ fontSize: 32, marginBottom: 8 }}>📄</Text>
          <Text style={{ color: '#94A3B8', fontSize: 13, fontWeight: '500' }}>
            Sin Órdenes del Día
          </Text>
        </View>
      ) : (
        <View style={styles.twoColGrid}>
          {effectiveOrders.map((c) => {
            const isSeen = isConformeByMe(c.conforme);
            const numOrden = c.numeroOrden || c.numero || 'S/N';
            const fechaOrd = c.fechaOrden || c.fechaDocumento || c.fecha || '';

            return (
              <TouchableOpacity
                key={c.id}
                style={[
                  styles.ordenGridCard,
                  {
                    backgroundColor: isDarkMode ? '#120A0A' : '#FFFFFF',
                    borderColor: isSeen
                      ? 'rgba(74, 222, 128, 0.35)'
                      : isDarkMode
                        ? 'rgba(239, 68, 68, 0.10)'
                        : 'rgba(15, 23, 42, 0.12)'
                  }
                ]}
                onPress={() => handleOpenOrden(c)}
                activeOpacity={0.8}
              >
                <View style={styles.ordenIconCircle}>
                  <FileText size={24} color="#EF4444" />
                </View>

                <Text
                  style={[
                    styles.ordenGridTitle,
                    { color: isDarkMode ? '#FFFFFF' : '#0F172A' }
                  ]}
                  numberOfLines={1}
                >
                  N°  {numOrden}
                </Text>

                <Text style={styles.ordenGridDate} numberOfLines={1}>
                  {fechaOrd}
                </Text>

                <View
                  style={[
                    styles.cardThinDivider,
                    {
                      backgroundColor: isDarkMode
                        ? 'rgba(255,255,255,0.08)'
                        : 'rgba(15,23,42,0.08)'
                    }
                  ]}
                />

                <Text
                  style={[
                    styles.vistoStatusText,
                    { color: isSeen ? (isDarkMode ? '#4ADE80' : '#16A34A') : '#F87171' }
                  ]}
                >
                  {isSeen ? '✓ VISTO' : '✕ PENDIENTE'}
                </Text>
              </TouchableOpacity>
            );
          })}
        </View>
      )}
    </View>
  );

  // =========================================================================
  // 3. PESTAÑA ALERTAS Y MURO DE COMUNICACIONES (alertas.html + TabScreens.kt)
  // =========================================================================
  const renderAlertasTab = () => {
    const rawAlerts = Array.isArray(alertsList) ? alertsList : [];
    const myIdReg = String(user?.idRegistro || '').trim();
    const myRadialUpper = String(myRadial || '').trim().toUpperCase();
    const myFullNameUpper = String(user?.nombreBombero || '').trim().toUpperCase();

    // Resolver nombres de destinatarios desde idRegistro (con fallback a idRadial)
    const resolveTargetLabel = (aQuienRaw = 'TC') => {
      const clean = String(aQuienRaw || 'TC').trim();
      if (!clean || clean.toUpperCase() === 'TC' || clean.toUpperCase() === 'SC' || clean === '1') {
        return 'Toda la Compañía';
      }
      const ids = clean.split(',').map((s) => s.trim()).filter(Boolean);
      const labels = ids.map((id) => {
        const byReg = (firefightersList || []).find((p) => String(p.idRegistro).trim() === id);
        if (byReg) {
          return `[${byReg.idRadial}] ${formatFirefighterName(byReg.nombre || byReg.nombreBombero)}`;
        }
        const byRad = (firefightersList || []).find((p) => String(p.idRadial).trim() === id);
        if (byRad) {
          return `[${byRad.idRadial}] ${formatFirefighterName(byRad.nombre || byRad.nombreBombero)}`;
        }
        return `Reg #${id}`;
      });
      return labels.join(', ');
    };

    // Filtrar alertas dirigidas a Toda la Compañía o al idRegistro del bombero actual (o creadas por él / Comandante)
    const visibleAlerts = rawAlerts
      .filter((alerta) => {
        const aQuien = String(alerta.aQuienAlerta || 'TC').trim();
        const aQuienUp = aQuien.toUpperCase();
        if (!aQuien || aQuienUp === 'TC' || aQuienUp === 'SC' || aQuien === '1' || isComandante) {
          return true;
        }
        const ids = aQuien.split(',').map((s) => s.trim()).filter(Boolean);
        const isRecipientByReg = Boolean(myIdReg && ids.includes(myIdReg));
        const isRecipientByRad = Boolean(myRadialUpper && ids.map((i) => i.toUpperCase()).includes(myRadialUpper));
        const isAuthor = Boolean(
          myFullNameUpper &&
            String(alerta.quienAlerta || '').toUpperCase().includes(myFullNameUpper)
        );
        return isRecipientByReg || isRecipientByRad || isAuthor;
      })
      .sort((a, b) => {
        const pinsA = String(a.fijar || '')
          .split(',')
          .map((s) => s.trim().toUpperCase())
          .filter(Boolean);
        const pinsB = String(b.fijar || '')
          .split(',')
          .map((s) => s.trim().toUpperCase())
          .filter(Boolean);
        const aPinned = pinsA.includes(myRadialUpper) || (myIdReg && pinsA.includes(myIdReg));
        const bPinned = pinsB.includes(myRadialUpper) || (myIdReg && pinsB.includes(myIdReg));
        if (aPinned !== bPinned) return aPinned ? -1 : 1;
        const numA = parseInt(String(a.idAlerta || '').replace(/\D/g, ''), 10) || 0;
        const numB = parseInt(String(b.idAlerta || '').replace(/\D/g, ''), 10) || 0;
        return numB - numA;
      });

    return (
      <View style={styles.tabScrollContainer}>
        {visibleAlerts.length === 0 ? (
          <View
            style={[
              styles.glassCard,
              {
                backgroundColor: isDarkMode ? '#120A0A' : '#FFFFFF',
                borderColor: isDarkMode ? 'rgba(239, 68, 68, 0.10)' : 'rgba(15, 23, 42, 0.12)',
                paddingVertical: 32,
                paddingHorizontal: 20,
                alignItems: 'center'
              }
            ]}
          >
            <Text style={{ fontSize: 32, marginBottom: 8 }}>🔔</Text>
            <Text style={{ color: '#94A3B8', fontSize: 13, fontWeight: '600' }}>
              No se registran alertas en el muro de comunicaciones.
            </Text>
          </View>
        ) : (
          visibleAlerts.map((alerta) => {
            const isSeen = isConformeByMe(alerta.conforme);
            const pinsList = String(alerta.fijar || '')
              .split(',')
              .map((s) => s.trim().toUpperCase())
              .filter(Boolean);
            const isPinned =
              pinsList.includes(myRadialUpper) || Boolean(myIdReg && pinsList.includes(myIdReg));
            const isChat = String(alerta.duracion || '').trim().toUpperCase() === 'C';
            const isAuthor = Boolean(
              myFullNameUpper &&
                String(alerta.quienAlerta || '').toUpperCase().includes(myFullNameUpper)
            );
            const canDelete = isComandante || isCentralActive || isAuthor;
            const targetLabel = resolveTargetLabel(alerta.aQuienAlerta);
            const isWholeCompany =
              !alerta.aQuienAlerta ||
              ['TC', 'SC', '1'].includes(String(alerta.aQuienAlerta).trim().toUpperCase());
            const conformesCount = String(alerta.conforme || '')
              .split(',')
              .map((s) => s.trim())
              .filter(Boolean).length;

            // ===============================================================
            // TARJETA DE CONVERSACIÓN / CHAT GRUPAL (duracion == 'C')
            // ===============================================================
            if (isChat) {
              const parsedMsgs = parseChatMessages(alerta.mensajeAlerta || '');
              const lastMsg = parsedMsgs.length > 0 ? parsedMsgs[parsedMsgs.length - 1] : null;
              let lastSenderName = '';
              if (lastMsg) {
                const senderObj =
                  (firefightersList || []).find(
                    (f) => String(f.idRegistro).trim() === String(lastMsg.senderId).trim()
                  ) ||
                  (firefightersList || []).find(
                    (f) => String(f.idRadial).trim() === String(lastMsg.senderId).trim()
                  );
                const isLastMe =
                  (myIdReg && String(lastMsg.senderId).trim() === myIdReg) ||
                  (senderObj && myIdReg && String(senderObj.idRegistro).trim() === myIdReg) ||
                  (!senderObj && String(lastMsg.senderId).trim().toUpperCase() === myRadialUpper);
                lastSenderName = isLastMe
                  ? 'Tú'
                  : senderObj
                    ? `[${senderObj.idRadial}] ${formatFirefighterName(senderObj.nombre || senderObj.nombreBombero)}`
                    : `Vol. #${lastMsg.senderId}`;
              }

              const openThisChat = () => {
                triggerHaptic('medium');
                setActiveChatAlert(alerta);
                setChatModalVisible(true);
              };

              return (
                <TouchableOpacity
                  key={alerta.id}
                  style={[
                    styles.glassCard,
                    {
                      backgroundColor: isDarkMode ? 'rgba(20, 14, 18, 0.94)' : '#FFFFFF',
                      borderColor: isPinned
                        ? '#EF4444'
                        : !isSeen
                          ? '#38BDF8'
                          : isDarkMode
                            ? 'rgba(239, 68, 68, 0.28)'
                            : 'rgba(15, 23, 42, 0.14)',
                      borderLeftWidth: 4,
                      borderLeftColor: '#EF4444',
                      padding: 14,
                      marginBottom: 12
                    }
                  ]}
                  onPress={openThisChat}
                  activeOpacity={0.88}
                >
                  {/* Fila superior: Icono + Título + Acciones Fijar/Eliminar */}
                  <View
                    style={{
                      flexDirection: 'row',
                      justifyContent: 'space-between',
                      alignItems: 'flex-start',
                      marginBottom: 8
                    }}
                  >
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, flex: 1, marginRight: 8 }}>
                      <View
                        style={{
                          width: 36,
                          height: 36,
                          borderRadius: 18,
                          backgroundColor: 'rgba(239, 68, 68, 0.15)',
                          borderWidth: 1,
                          borderColor: 'rgba(239, 68, 68, 0.35)',
                          alignItems: 'center',
                          justifyContent: 'center'
                        }}
                      >
                        <MessageSquare size={17} color="#EF4444" />
                      </View>
                      <View style={{ flex: 1 }}>
                        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                          <View
                            style={{
                              backgroundColor: 'rgba(239, 68, 68, 0.16)',
                              paddingHorizontal: 6,
                              paddingVertical: 1.5,
                              borderRadius: 5
                            }}
                          >
                            <Text style={{ color: '#F87171', fontSize: 9, fontWeight: '900' }}>
                              CONVERSACIÓN
                            </Text>
                          </View>
                          {!isSeen ? (
                            <View
                              style={{
                                backgroundColor: '#EF4444',
                                paddingHorizontal: 6,
                                paddingVertical: 1.5,
                                borderRadius: 5
                              }}
                            >
                              <Text style={{ color: '#FFFFFF', fontSize: 8.5, fontWeight: '900' }}>
                                NUEVO MENSAJE
                              </Text>
                            </View>
                          ) : null}
                        </View>
                        <Text
                          style={{
                            color: isDarkMode ? '#FFFFFF' : '#0F172A',
                            fontSize: 13.5,
                            fontWeight: '900',
                            marginTop: 3
                          }}
                          numberOfLines={1}
                        >
                          {alerta.razonAlerta || 'CANAL DE COMUNICACIÓN'}
                        </Text>
                      </View>
                    </View>

                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                      <TouchableOpacity
                        onPress={() => toggleAlertPin(alerta)}
                        style={{ padding: 4 }}
                      >
                        <Bookmark
                          size={16}
                          color={isPinned ? '#EF4444' : '#64748B'}
                          fill={isPinned ? '#EF4444' : 'transparent'}
                        />
                      </TouchableOpacity>
                      {canDelete ? (
                        <TouchableOpacity
                          onPress={() => deleteAlert(alerta.idAlerta || alerta.id)}
                          style={{ padding: 4 }}
                        >
                          <Trash2 size={15} color="#EF4444" />
                        </TouchableOpacity>
                      ) : null}
                    </View>
                  </View>

                  {/* Destinatarios */}
                  <View style={{ flexDirection: 'row', alignItems: 'center', marginBottom: 8 }}>
                    <View
                      style={{
                        backgroundColor: isWholeCompany
                          ? 'rgba(59, 130, 246, 0.14)'
                          : 'rgba(245, 158, 11, 0.15)',
                        borderColor: isWholeCompany
                          ? 'rgba(59, 130, 246, 0.35)'
                          : 'rgba(245, 158, 11, 0.4)',
                        borderWidth: 1,
                        borderRadius: 6,
                        paddingHorizontal: 7,
                        paddingVertical: 2
                      }}
                    >
                      <Text
                        style={{
                          color: isWholeCompany ? '#60A5FA' : '#FBBF24',
                          fontSize: 9.5,
                          fontWeight: '800'
                        }}
                        numberOfLines={1}
                      >
                        Para: {targetLabel}
                      </Text>
                    </View>
                  </View>

                  {/* Último mensaje formateado igual a alertas.html / TabScreens.kt */}
                  <View
                    style={{
                      backgroundColor: isDarkMode ? 'rgba(15, 23, 42, 0.65)' : '#F8FAFC',
                      borderRadius: 10,
                      paddingHorizontal: 10,
                      paddingVertical: 8,
                      borderWidth: 1,
                      borderColor: isDarkMode ? 'rgba(255,255,255,0.06)' : '#E2E8F0'
                    }}
                  >
                    {lastMsg ? (
                      <Text
                        style={{
                          color: isDarkMode ? '#E2E8F0' : '#1E293B',
                          fontSize: 12,
                          lineHeight: 17
                        }}
                        numberOfLines={2}
                      >
                        <Text style={{ fontWeight: '900', color: '#F87171' }}>
                          {lastSenderName}:{' '}
                        </Text>
                        {lastMsg.texto}
                      </Text>
                    ) : (
                      <Text style={{ color: '#64748B', fontSize: 11.5, fontStyle: 'italic' }}>
                        Sin mensajes aún. Toque para participar.
                      </Text>
                    )}
                  </View>

                  {/* Footer del Chat */}
                  <View
                    style={{
                      flexDirection: 'row',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      marginTop: 10
                    }}
                  >
                    <Text style={{ color: '#94A3B8', fontSize: 10, fontWeight: '600' }}>
                      {alerta.fechaAlerta || alerta.fecha || ''}{' '}
                      {alerta.horaAlerta || alerta.hora ? `• ${alerta.horaAlerta || alerta.hora}` : ''} •{' '}
                      {parsedMsgs.length} msj
                    </Text>

                    <TouchableOpacity
                      style={styles.chatResponderBtn}
                      onPress={openThisChat}
                      activeOpacity={0.85}
                    >
                      <Text style={styles.chatResponderBtnText}>ABRIR CHAT</Text>
                    </TouchableOpacity>
                  </View>
                </TouchableOpacity>
              );
            }

            // ===============================================================
            // TARJETA ESTÁNDAR DE ALERTA / COMUNICADO (alertas.html + TabScreens.kt)
            // ===============================================================
            const grado = String(alerta.gradoAlerta || '1').trim();
            const gradeColor =
              grado === '3' ? '#EF4444' : grado === '2' ? '#F59E0B' : '#3B82F6';
            const gradeLabel =
              grado === '3'
                ? 'GRADO 3 • CRÍTICO'
                : grado === '2'
                  ? 'GRADO 2 • CITACIÓN'
                  : 'GRADO 1 • INFO';

            const titleText =
              String(alerta.razonAlerta || '').trim() || 'COMUNICADO OFICIAL';
            const durClean = String(alerta.duracion || 'i').trim();
            const hasExpiry = durClean && durClean.toLowerCase() !== 'i';

            return (
              <TouchableOpacity
                key={alerta.id}
                style={[
                  styles.glassCard,
                  {
                    backgroundColor: isDarkMode ? 'rgba(18, 12, 16, 0.94)' : '#FFFFFF',
                    borderColor: isPinned
                      ? '#EF4444'
                      : isSeen
                        ? 'rgba(74, 222, 128, 0.38)'
                        : isDarkMode
                          ? 'rgba(239, 68, 68, 0.18)'
                          : 'rgba(15, 23, 42, 0.14)',
                    borderLeftWidth: 4,
                    borderLeftColor: gradeColor,
                    padding: 14,
                    marginBottom: 12
                  }
                ]}
                onPress={() => handleOpenAlerta(alerta)}
                activeOpacity={0.86}
              >
                {/* Fila superior: Badges de Grado / Destinatario + Botones Fijar y Eliminar */}
                <View
                  style={{
                    flexDirection: 'row',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    marginBottom: 8
                  }}
                >
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, flexWrap: 'wrap', flex: 1, marginRight: 8 }}>
                    <View
                      style={{
                        backgroundColor: `${gradeColor}22`,
                        borderColor: `${gradeColor}66`,
                        borderWidth: 1,
                        paddingHorizontal: 7,
                        paddingVertical: 2,
                        borderRadius: 6
                      }}
                    >
                      <Text style={{ color: gradeColor, fontSize: 9, fontWeight: '900' }}>
                        {gradeLabel}
                      </Text>
                    </View>

                    <View
                      style={{
                        backgroundColor: isWholeCompany
                          ? 'rgba(59, 130, 246, 0.12)'
                          : 'rgba(245, 158, 11, 0.15)',
                        borderColor: isWholeCompany
                          ? 'rgba(59, 130, 246, 0.30)'
                          : 'rgba(245, 158, 11, 0.40)',
                        borderWidth: 1,
                        paddingHorizontal: 7,
                        paddingVertical: 2,
                        borderRadius: 6,
                        maxWidth: '62%'
                      }}
                    >
                      <Text
                        style={{
                          color: isWholeCompany ? '#60A5FA' : '#FBBF24',
                          fontSize: 9,
                          fontWeight: '800'
                        }}
                        numberOfLines={1}
                      >
                        {isWholeCompany ? 'Toda la Cía' : `Para: ${targetLabel}`}
                      </Text>
                    </View>

                    {hasExpiry ? (
                      <View
                        style={{
                          backgroundColor: 'rgba(148, 163, 184, 0.14)',
                          paddingHorizontal: 6,
                          paddingVertical: 2,
                          borderRadius: 5
                        }}
                      >
                        <Text style={{ color: '#94A3B8', fontSize: 8.5, fontWeight: '700' }}>
                          Hasta {durClean}
                        </Text>
                      </View>
                    ) : null}
                  </View>

                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                    <TouchableOpacity
                      onPress={() => toggleAlertPin(alerta)}
                      style={{ padding: 4 }}
                    >
                      <Bookmark
                        size={16}
                        color={isPinned ? '#EF4444' : '#64748B'}
                        fill={isPinned ? '#EF4444' : 'transparent'}
                      />
                    </TouchableOpacity>
                    {canDelete ? (
                      <TouchableOpacity
                        onPress={() => deleteAlert(alerta.idAlerta || alerta.id)}
                        style={{ padding: 4 }}
                      >
                        <Trash2 size={15} color="#EF4444" />
                      </TouchableOpacity>
                    ) : null}
                  </View>
                </View>

                {/* Título de la Alerta */}
                <Text
                  style={{
                    color: isDarkMode ? '#FFFFFF' : '#0F172A',
                    fontSize: 14,
                    fontWeight: '900',
                    marginBottom: 4
                  }}
                >
                  {titleText}
                </Text>

                {/* Vista previa del Mensaje */}
                {alerta.mensajeAlerta ? (
                  <Text
                    style={{
                      color: isDarkMode ? '#CBD5E1' : '#334155',
                      fontSize: 12,
                      lineHeight: 17,
                      marginBottom: 8
                    }}
                    numberOfLines={3}
                  >
                    {String(alerta.mensajeAlerta).replace(/\|/g, '\n')}
                  </Text>
                ) : null}

                <View
                  style={[
                    styles.cardThinDivider,
                    {
                      backgroundColor: isDarkMode
                        ? 'rgba(255,255,255,0.08)'
                        : 'rgba(15,23,42,0.08)',
                      marginVertical: 6
                    }
                  ]}
                />

                {/* Pie de tarjeta: Autor + Fecha/Hora + Estado Conforme */}
                <View
                  style={{
                    flexDirection: 'row',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    gap: 8
                  }}
                >
                  <View style={{ flex: 1 }}>
                    <Text
                      style={{
                        color: isDarkMode ? '#94A3B8' : '#475569',
                        fontSize: 10,
                        fontWeight: '700'
                      }}
                      numberOfLines={1}
                    >
                      Por: {alerta.quienAlerta || 'Comandancia'}
                    </Text>
                    <Text style={{ color: '#64748B', fontSize: 9.5, fontWeight: '600', marginTop: 1 }}>
                      {alerta.fechaAlerta || alerta.fecha || ''}{' '}
                      {alerta.horaAlerta || alerta.hora ? `• ${alerta.horaAlerta || alerta.hora}` : ''}
                    </Text>
                  </View>

                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                    {conformesCount > 0 ? (
                      <Text style={{ color: '#64748B', fontSize: 10, fontWeight: '800' }}>
                        ✓ {conformesCount}
                      </Text>
                    ) : null}
                    <View
                      style={{
                        backgroundColor: isSeen
                          ? 'rgba(74, 222, 128, 0.14)'
                          : 'rgba(248, 113, 113, 0.16)',
                        borderColor: isSeen
                          ? 'rgba(74, 222, 128, 0.4)'
                          : 'rgba(248, 113, 113, 0.45)',
                        borderWidth: 1,
                        borderRadius: 8,
                        paddingHorizontal: 8,
                        paddingVertical: 3.5
                      }}
                    >
                      <Text
                        style={{
                          color: isSeen ? (isDarkMode ? '#4ADE80' : '#16A34A') : '#F87171',
                          fontSize: 10,
                          fontWeight: '900'
                        }}
                      >
                        {isSeen ? '✓ CONFORME' : '✕ PENDIENTE'}
                      </Text>
                    </View>
                  </View>
                </View>
              </TouchableOpacity>
            );
          })
        )}
      </View>
    );
  };

  // =========================================================================
  // 4. PESTAÑA ASISTENCIA (TabScreens.kt líneas 3529-4020: Cálculo Anual Memoizado)
  // =========================================================================
  const attendanceComputed = useMemo(() => {
    const now = new Date();
    const currentCycleYear = now.getMonth() === 11 ? now.getFullYear() + 1 : now.getFullYear();
    const isDecember8th = now.getMonth() === 11 && now.getDate() === 8;

    const rawItems = Array.isArray(attendanceList) ? attendanceList : [];

    // 1. Solo listas aprobadas por OBAC (aprobadoPor no vacío) y no anuladas, ordenadas por idLista descendente
    const approvedHistory = rawItems
      .filter((row) => String(row.aprobadoPor || '').trim().length > 0 && !row.anulada)
      .sort((a, b) => {
        const numA = parseInt(String(a.idLista || a.id || '').replace(/\D/g, ''), 10) || 0;
        const numB = parseInt(String(b.idLista || b.id || '').replace(/\D/g, ''), 10) || 0;
        return numB - numA;
      });

    // 2. Mapear cada registro a su año de ciclo bomberil igual que TabScreens.kt líneas 3552-3569
    const historyWithCycle = [];
    approvedHistory.forEach((row) => {
      const parsedDate = parseAttendanceDate(row.fecha);
      const idStr = String(row.idLista || row.id || '').trim();
      if (parsedDate && !isNaN(parsedDate.getTime())) {
        const cYear = getCycleYear(parsedDate);
        if (idStr.startsWith(String(cYear))) {
          historyWithCycle.push({ row, cycleYear: cYear });
        }
      } else if (idStr.startsWith(String(currentCycleYear))) {
        historyWithCycle.push({ row, cycleYear: currentCycleYear });
      }
    });

    // 3. Filtrar registros del ciclo anual actual y anterior
    let currentCycleRows = historyWithCycle
      .filter((item) => item.cycleYear === currentCycleYear)
      .map((item) => item.row);

    if (currentCycleRows.length === 0 && approvedHistory.length > 0) {
      currentCycleRows = approvedHistory.filter((row) => {
        const parsedDate = parseAttendanceDate(row.fecha);
        const cYear = parsedDate && !isNaN(parsedDate.getTime()) ? getCycleYear(parsedDate) : currentCycleYear;
        return cYear === currentCycleYear;
      });
    }

    const prevCycleRows = historyWithCycle
      .filter((item) => item.cycleYear === currentCycleYear - 1)
      .map((item) => item.row);

    // 4. Estadísticas exactas del ciclo
    const currentStats = calculateCycleStats(currentCycleRows);
    const prevCycleStats = calculateCycleStats(prevCycleRows);

    // 5. Historial Aprobados
    const displayedApprovedHistory = currentCycleRows.filter((row) => {
      const st = String(row.userEstado || row.estado || '').trim().toUpperCase();
      if (!st || st === 'NO_REGISTRA' || st === 'NO REGISTRA') return false;
      const isAbono = isAbonoValue(row.userAbono, row.clave, row.tipo);
      const isPresent = st === 'A' || st === 'ASISTE' || st === 'C' || st === 'CDS';
      return !(isAbono && !isPresent);
    });

    // 6. Historial Por aprobar (aprobadoPor vacío y no anuladas)
    const pendingApprovalHistory = rawItems
      .filter((row) => String(row.aprobadoPor || '').trim().length === 0 && !row.anulada)
      .filter((row) => {
        const st = String(row.userEstado || row.estado || '').trim().toUpperCase();
        if (!st || st === 'NO_REGISTRA' || st === 'NO REGISTRA') return false;
        const parsedDate = parseAttendanceDate(row.fecha);
        const idStr = String(row.idLista || row.id || '').trim();
        if (parsedDate && !isNaN(parsedDate.getTime())) {
          return getCycleYear(parsedDate) === currentCycleYear;
        }
        return idStr.startsWith(String(currentCycleYear)) || true;
      })
      .sort((a, b) => {
        const numA = parseInt(String(a.idLista || a.id || '').replace(/\D/g, ''), 10) || 0;
        const numB = parseInt(String(b.idLista || b.id || '').replace(/\D/g, ''), 10) || 0;
        return numB - numA;
      });

    return {
      currentCycleYear,
      isDecember8th,
      ...currentStats,
      prevCycleStats,
      displayedApprovedHistory,
      pendingApprovalHistory
    };
  }, [attendanceList]);

  const renderAsistenciaTab = () => {
    const {
      currentCycleYear,
      isDecember8th,
      totalObligatorias,
      obligatoriasAsistidas,
      totalAbonosAsiste,
      porcentaje,
      prevCycleStats,
      displayedApprovedHistory,
      pendingApprovalHistory
    } = attendanceComputed;

    const fullHistory =
      attendanceHistoryFilter === 'por_aprobar' ? pendingApprovalHistory : displayedApprovedHistory;
    const displayedHistory =
      fullHistory.length > attendanceRenderLimit
        ? fullHistory.slice(0, attendanceRenderLimit)
        : fullHistory;

    // Colores y textos exactos de AttendanceItemCard en TabScreens.kt líneas 3906-3996
    const getAttendanceCardTheme = (rawSt) => {
      const raw = String(rawSt || 'FALTA').trim().toUpperCase();
      let cleanStatus = raw;
      if (raw === 'A' || raw === 'ASISTE') cleanStatus = 'ASISTE';
      else if (raw === 'F' || raw === 'FALTA' || raw === '') cleanStatus = 'FALTA';
      else if (raw === 'L' || raw === 'LICENCIA') cleanStatus = 'LICENCIA';
      else if (raw === 'P' || raw === 'PERMISO') cleanStatus = 'PERMISO';
      else if (raw === 'S' || raw === 'SUSPENDIDO') cleanStatus = 'SUSPENDIDO';
      else if (raw === 'C' || raw === 'CDS') cleanStatus = 'CDS';

      if (isDarkMode) {
        switch (cleanStatus) {
          case 'ASISTE':
            return {
              bg: 'rgba(10, 62, 46, 0.84)',
              border: 'rgba(16, 185, 129, 0.50)',
              badge: '#10B981',
              text: '✓ ASISTE'
            };
          case 'CDS':
            return {
              bg: 'rgba(10, 62, 46, 0.84)',
              border: 'rgba(16, 185, 129, 0.50)',
              badge: '#10B981',
              text: '✓ CDS'
            };
          case 'FALTA':
            return {
              bg: 'rgba(78, 18, 24, 0.84)',
              border: 'rgba(239, 68, 68, 0.50)',
              badge: '#EF4444',
              text: '✗ FALTA'
            };
          case 'PERMISO':
            return {
              bg: 'rgba(82, 44, 12, 0.84)',
              border: 'rgba(245, 158, 11, 0.50)',
              badge: '#F59E0B',
              text: '✗ PERMISO'
            };
          case 'LICENCIA':
            return {
              bg: 'rgba(14, 42, 82, 0.84)',
              border: 'rgba(59, 130, 246, 0.50)',
              badge: '#3B82F6',
              text: '✗ LICENCIA'
            };
          case 'SUSPENDIDO':
            return {
              bg: 'rgba(56, 20, 86, 0.84)',
              border: 'rgba(139, 92, 246, 0.50)',
              badge: '#8B5CF6',
              text: 'SUSPENDIDO'
            };
          default:
            return {
              bg: 'rgba(30, 41, 59, 0.86)',
              border: 'rgba(255,255,255,0.18)',
              badge: '#94A3B8',
              text: cleanStatus
            };
        }
      } else {
        switch (cleanStatus) {
          case 'ASISTE':
            return {
              bg: 'rgba(209, 250, 229, 0.88)',
              border: 'rgba(16, 185, 129, 0.48)',
              badge: '#059669',
              text: '✓ ASISTE'
            };
          case 'CDS':
            return {
              bg: 'rgba(209, 250, 229, 0.88)',
              border: 'rgba(16, 185, 129, 0.48)',
              badge: '#059669',
              text: '✓ CDS'
            };
          case 'FALTA':
            return {
              bg: 'rgba(254, 226, 226, 0.88)',
              border: 'rgba(239, 68, 68, 0.48)',
              badge: '#DC2626',
              text: '✗ FALTA'
            };
          case 'PERMISO':
            return {
              bg: 'rgba(254, 243, 199, 0.88)',
              border: 'rgba(245, 158, 11, 0.48)',
              badge: '#D97706',
              text: '✗ PERMISO'
            };
          case 'LICENCIA':
            return {
              bg: 'rgba(219, 234, 254, 0.88)',
              border: 'rgba(59, 130, 246, 0.48)',
              badge: '#2563EB',
              text: '✗ LICENCIA'
            };
          default:
            return {
              bg: 'rgba(243, 232, 255, 0.88)',
              border: 'rgba(139, 92, 246, 0.48)',
              badge: '#7C3AED',
              text: cleanStatus
            };
        }
      }
    };

    return (
      <View style={styles.tabScrollContainer}>
        {/* 1. Tarjeta Resumen Anual idéntica a AsistenciaTab en TabScreens.kt (líneas 3601-3695) */}
        <View
          style={[
            styles.glassCard,
            {
              backgroundColor: isDarkMode ? 'rgba(30, 41, 59, 0.92)' : 'rgba(255, 255, 255, 0.94)',
              borderColor: isDarkMode ? 'rgba(255, 255, 255, 0.15)' : 'rgba(15, 23, 42, 0.12)',
              borderRadius: 24,
              paddingHorizontal: 14,
              paddingVertical: 12,
              marginBottom: 14
            }
          ]}
        >
          <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', flex: 1, marginRight: 8 }}>
              <AttendanceCircle
                percentage={porcentaje}
                size={66}
                strokeWidth={6.5}
                isDarkMode={isDarkMode}
              />

              <View style={{ marginLeft: 12, flex: 1 }}>
                <Text
                  style={{
                    color: isDarkMode ? '#FFFFFF' : '#0F172A',
                    fontSize: 13.5,
                    fontWeight: '800'
                  }}
                  numberOfLines={1}
                >
                  Asistencia {currentCycleYear}
                </Text>
                <Text
                  style={{
                    color: isDarkMode ? 'rgba(255, 255, 255, 0.52)' : 'rgba(15, 23, 42, 0.52)',
                    fontSize: 8.5,
                    fontWeight: '900',
                    letterSpacing: 0.3,
                    marginTop: 2
                  }}
                  numberOfLines={1}
                >
                  RENDIMIENTO ANUAL EN CURSO
                </Text>
              </View>
            </View>

            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
              <View
                style={[
                  styles.v2MiniStatBox,
                  {
                    backgroundColor: isDarkMode
                      ? 'rgba(255, 255, 255, 0.08)'
                      : 'rgba(15, 23, 42, 0.04)',
                    borderWidth: 1,
                    borderColor: isDarkMode
                      ? 'rgba(255, 255, 255, 0.15)'
                      : 'rgba(15, 23, 42, 0.08)'
                  }
                ]}
              >
                <Text
                  style={{
                    color: isDarkMode ? '#FFFFFF' : '#0F172A',
                    fontSize: 11.5,
                    fontWeight: '900'
                  }}
                >
                  {obligatoriasAsistidas} de {totalObligatorias}
                </Text>
                <Text
                  style={{
                    color: isDarkMode ? 'rgba(255, 255, 255, 0.52)' : 'rgba(15, 23, 42, 0.52)',
                    fontSize: 8,
                    fontWeight: '900'
                  }}
                >
                  ASISTE
                </Text>
              </View>

              <View
                style={[
                  styles.v2MiniStatBox,
                  {
                    backgroundColor: isDarkMode
                      ? 'rgba(255, 255, 255, 0.08)'
                      : 'rgba(15, 23, 42, 0.04)',
                    borderWidth: 1,
                    borderColor: isDarkMode
                      ? 'rgba(255, 255, 255, 0.15)'
                      : 'rgba(15, 23, 42, 0.08)'
                  }
                ]}
              >
                <Text
                  style={{
                    color: isDarkMode ? '#FFFFFF' : '#0F172A',
                    fontSize: 11.5,
                    fontWeight: '900'
                  }}
                >
                  {totalAbonosAsiste}
                </Text>
                <Text
                  style={{
                    color: isDarkMode ? 'rgba(255, 255, 255, 0.52)' : 'rgba(15, 23, 42, 0.52)',
                    fontSize: 8,
                    fontWeight: '900'
                  }}
                >
                  ABONOS
                </Text>
              </View>
            </View>
          </View>
        </View>

        {/* Tarjeta Ciclo Anterior (8 de Diciembre, TabScreens.kt líneas 3697-3819) */}
        {isDecember8th ? (
          <View
            style={[
              styles.glassCard,
              {
                backgroundColor: isDarkMode ? 'rgba(30, 41, 59, 0.92)' : 'rgba(255, 255, 255, 0.94)',
                borderColor: isDarkMode ? 'rgba(255, 255, 255, 0.15)' : 'rgba(15, 23, 42, 0.12)',
                borderRadius: 24,
                paddingHorizontal: 14,
                paddingVertical: 12,
                marginBottom: 14
              }
            ]}
          >
            <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', flex: 1, marginRight: 8 }}>
                <AttendanceCircle
                  percentage={prevCycleStats.porcentaje}
                  size={66}
                  strokeWidth={6.5}
                  isDarkMode={isDarkMode}
                />
                <View style={{ marginLeft: 12, flex: 1 }}>
                  <Text
                    style={{
                      color: isDarkMode ? '#FFFFFF' : '#0F172A',
                      fontSize: 13,
                      fontWeight: '800'
                    }}
                  >
                    Ciclo Anterior ({currentCycleYear - 1})
                  </Text>
                  <View style={{ flexDirection: 'row', gap: 4, marginTop: 4 }}>
                    <View
                      style={{
                        backgroundColor: prevCycleStats.porcentaje >= 30 ? 'rgba(16, 185, 129, 0.15)' : 'rgba(239, 68, 68, 0.15)',
                        borderColor: prevCycleStats.porcentaje >= 30 ? '#10B981' : '#EF4444',
                        borderWidth: 1,
                        borderRadius: 4,
                        paddingHorizontal: 5,
                        paddingVertical: 2
                      }}
                    >
                      <Text
                        style={{
                          color: prevCycleStats.porcentaje >= 30 ? '#10B981' : '#EF4444',
                          fontSize: 8,
                          fontWeight: '800'
                        }}
                      >
                        {prevCycleStats.porcentaje >= 30 ? 'VOTA: SÍ' : 'VOTA: NO'}
                      </Text>
                    </View>
                    <View
                      style={{
                        backgroundColor: prevCycleStats.porcentaje >= 40 ? 'rgba(16, 185, 129, 0.15)' : 'rgba(239, 68, 68, 0.15)',
                        borderColor: prevCycleStats.porcentaje >= 40 ? '#10B981' : '#EF4444',
                        borderWidth: 1,
                        borderRadius: 4,
                        paddingHorizontal: 5,
                        paddingVertical: 2
                      }}
                    >
                      <Text
                        style={{
                          color: prevCycleStats.porcentaje >= 40 ? '#10B981' : '#EF4444',
                          fontSize: 8,
                          fontWeight: '800'
                        }}
                      >
                        {prevCycleStats.porcentaje >= 40 ? 'CARGO: SÍ' : 'CARGO: NO'}
                      </Text>
                    </View>
                  </View>
                </View>
              </View>

              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                <View
                  style={[
                    styles.v2MiniStatBox,
                    {
                      backgroundColor: isDarkMode ? 'rgba(255, 255, 255, 0.08)' : 'rgba(15, 23, 42, 0.04)',
                      borderWidth: 1,
                      borderColor: isDarkMode ? 'rgba(255, 255, 255, 0.15)' : 'rgba(15, 23, 42, 0.08)'
                    }
                  ]}
                >
                  <Text style={{ color: isDarkMode ? '#FFFFFF' : '#0F172A', fontSize: 11.5, fontWeight: '900' }}>
                    {prevCycleStats.obligatoriasAsistidas} de {prevCycleStats.totalObligatorias}
                  </Text>
                  <Text style={{ color: isDarkMode ? 'rgba(255, 255, 255, 0.52)' : 'rgba(15, 23, 42, 0.52)', fontSize: 8, fontWeight: '900' }}>
                    ASISTE
                  </Text>
                </View>
                <View
                  style={[
                    styles.v2MiniStatBox,
                    {
                      backgroundColor: isDarkMode ? 'rgba(255, 255, 255, 0.08)' : 'rgba(15, 23, 42, 0.04)',
                      borderWidth: 1,
                      borderColor: isDarkMode ? 'rgba(255, 255, 255, 0.15)' : 'rgba(15, 23, 42, 0.08)'
                    }
                  ]}
                >
                  <Text style={{ color: isDarkMode ? '#FFFFFF' : '#0F172A', fontSize: 11.5, fontWeight: '900' }}>
                    {prevCycleStats.totalAbonosAsiste}
                  </Text>
                  <Text style={{ color: isDarkMode ? 'rgba(255, 255, 255, 0.52)' : 'rgba(15, 23, 42, 0.52)', fontSize: 8, fontWeight: '900' }}>
                    ABONOS
                  </Text>
                </View>
              </View>
            </View>
          </View>
        ) : null}

        {/* Fila HISTORIAL + Botones Aprobados / Por aprobar + Sincronizar */}
        <View style={styles.historialHeaderRow}>
          <View style={styles.historialLeftGroup}>
            <Text style={[styles.historialTitle, { color: isDarkMode ? '#FFFFFF' : '#0F172A' }]}>
              HISTORIAL
            </Text>

            <View
              style={[
                styles.historyFilterPillGroup,
                {
                  backgroundColor: isDarkMode
                    ? 'rgba(15, 23, 42, 0.75)'
                    : 'rgba(226, 232, 240, 0.85)',
                  borderColor: isDarkMode
                    ? 'rgba(255, 255, 255, 0.14)'
                    : 'rgba(15, 23, 42, 0.12)'
                }
              ]}
            >
              <TouchableOpacity
                style={[
                  styles.historyFilterBtn,
                  attendanceHistoryFilter === 'aprobados' && styles.historyFilterBtnActiveApproved
                ]}
                onPress={() => {
                  triggerHaptic('medium');
                  setAttendanceHistoryFilter('aprobados');
                }}
                activeOpacity={0.8}
              >
                <Text
                  style={[
                    styles.historyFilterBtnText,
                    {
                      color:
                        attendanceHistoryFilter === 'aprobados'
                          ? '#FFFFFF'
                          : isDarkMode
                            ? '#94A3B8'
                            : '#475569'
                    }
                  ]}
                >
                  Aprobados
                </Text>
              </TouchableOpacity>

              <TouchableOpacity
                style={[
                  styles.historyFilterBtn,
                  attendanceHistoryFilter === 'por_aprobar' && styles.historyFilterBtnActivePending
                ]}
                onPress={() => {
                  triggerHaptic('medium');
                  setAttendanceHistoryFilter('por_aprobar');
                }}
                activeOpacity={0.8}
              >
                <Text
                  style={[
                    styles.historyFilterBtnText,
                    {
                      color:
                        attendanceHistoryFilter === 'por_aprobar'
                          ? '#FFFFFF'
                          : isDarkMode
                            ? '#94A3B8'
                            : '#475569'
                    }
                  ]}
                >
                  Por aprobar
                  {pendingApprovalHistory.length > 0 ? ` (${pendingApprovalHistory.length})` : ''}
                </Text>
              </TouchableOpacity>
            </View>
          </View>

          <TouchableOpacity
            style={styles.sincronizarBtn}
            onPress={() => {
              triggerHaptic('medium');
              refreshAttendance();
            }}
            disabled={isAttendanceLoading}
            activeOpacity={0.7}
          >
            <RefreshCw size={14} color="#EF4444" style={{ marginRight: 4 }} />
            <Text style={styles.sincronizarText}>
              {isAttendanceLoading ? 'Sincronizando...' : 'Sincronizar'}
            </Text>
          </TouchableOpacity>
        </View>

        {displayedHistory.length === 0 ? (
          <View
            style={[
              styles.glassCard,
              {
                backgroundColor: isDarkMode ? '#120A0A' : '#FFFFFF',
                borderColor: isDarkMode ? 'rgba(239, 68, 68, 0.10)' : 'rgba(15, 23, 42, 0.12)',
                paddingVertical: 32,
                paddingHorizontal: 20,
                alignItems: 'center'
              }
            ]}
          >
            <Text style={{ color: '#64748B', fontSize: 13, fontWeight: '700', textAlign: 'center' }}>
              {attendanceHistoryFilter === 'por_aprobar'
                ? 'No hay listas pendientes por aprobar en este momento.'
                : 'No registra historial de asistencias aprobadas este año.'}
            </Text>
          </View>
        ) : (
          <View style={styles.twoColGrid}>
            {displayedHistory.map((item, idx) => {
              const theme = getAttendanceCardTheme(item.userEstado || item.estado);
              const isAbono = isAbonoValue(item.userAbono, item.clave, item.tipo);

              return (
                <TouchableOpacity
                  key={item.id || idx}
                  style={[
                    styles.v2HistoryCard,
                    {
                      backgroundColor: theme.bg,
                      borderColor: theme.border
                    }
                  ]}
                  onPress={() => {
                    triggerHaptic('medium');
                    setSelectedAttendanceModal(item);
                  }}
                  activeOpacity={0.8}
                >
                  <View style={styles.v2HistoryTop}>
                    <Text
                      style={[
                        styles.v2CardClave,
                        { color: isDarkMode ? '#FFFFFF' : '#0F172A', flex: 1 }
                      ]}
                      numberOfLines={1}
                    >
                      {item.clave || 'CITACIÓN'}
                    </Text>
                    {isAbono ? (
                      <View style={styles.v2AbonoChip}>
                        <Text style={styles.v2AbonoText}>ABONO</Text>
                      </View>
                    ) : null}
                  </View>

                  <Text style={[styles.v2CardStatus, { color: theme.badge, marginTop: 4 }]}>
                    {theme.text}
                  </Text>

                  <Text
                    style={[
                      styles.v2CardDate,
                      {
                        color: isDarkMode ? '#94A3B8' : '#475569',
                        marginTop: 6
                      }
                    ]}
                    numberOfLines={1}
                  >
                    {cleanSheetPrefix(item.fecha)} {cleanSheetPrefix(item.hora)}
                  </Text>

                  <Text
                    style={[
                      styles.v2CardLugar,
                      { color: isDarkMode ? '#94A3B8' : '#475569', marginTop: 2 }
                    ]}
                    numberOfLines={1}
                  >
                    {item.lugar || '(Sin lugar)'}
                  </Text>
                </TouchableOpacity>
              );
            })}
          </View>
        )}
      </View>
    );
  };

  // =========================================================================
  // 5. PESTAÑA DISPONIBLES (TabScreens.kt líneas 1022-1197)
  // =========================================================================
  const renderDisponiblesTab = () => {
    const active09Count = displayedFirefighters.filter(
      (f) => String(f.estado || '').trim().toUpperCase() === '0-9'
    ).length;

    const getFirefighterStatusStyle = (est) => {
      const s = String(est || '0-8').trim().toUpperCase();
      switch (s) {
        case '0-9':
          return {
            color: '#4ADE80',
            bg: 'rgba(22, 101, 52, 0.50)',
            border: 'rgba(74, 222, 128, 0.30)'
          };
        case 'PERMISO':
          return {
            color: '#FB923C',
            bg: 'rgba(154, 52, 18, 0.50)',
            border: 'rgba(251, 146, 60, 0.30)'
          };
        case 'LICENCIA':
          return {
            color: '#38BDF8',
            bg: 'rgba(7, 89, 133, 0.50)',
            border: 'rgba(56, 189, 248, 0.30)'
          };
        case 'CDS':
          return {
            color: '#FACC15',
            bg: 'rgba(133, 77, 14, 0.50)',
            border: 'rgba(250, 204, 21, 0.30)'
          };
        case 'SUSPENDIDO':
          return {
            color: '#C084FC',
            bg: 'rgba(107, 33, 168, 0.50)',
            border: 'rgba(192, 132, 252, 0.30)'
          };
        default:
          return {
            color: '#F87171',
            bg: 'rgba(127, 29, 29, 0.50)',
            border: 'rgba(248, 113, 113, 0.30)'
          };
      }
    };

    return (
      <View style={styles.tabScrollContainer}>
        {/* Selector de Compañía Dinámico (Oculto en Cuerpos de 1 sola compañía) */}
        {!isSingleCompanyInstitution && canViewAllCompanies ? (
          <View style={styles.companyFilterBar}>
            {[...institutionCompaniesList.map((c) => c.filterLabel), 'TODAS'].map((comp) => (
              <TouchableOpacity
                key={comp}
                style={[
                  styles.compFilterBtn,
                  selectedCompanyFilter === comp && styles.compFilterBtnActive
                ]}
                onPress={() => {
                  triggerHaptic('medium');
                  setSelectedCompanyFilter(comp);
                }}
              >
                <Text
                  style={[
                    styles.compFilterText,
                    selectedCompanyFilter === comp && styles.compFilterTextActive
                  ]}
                >
                  {comp}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
        ) : null}

        {/* Tarjeta Superior En Servicio (0-9) idéntica a DisponiblesTab en TabScreens.kt */}
        <View
          style={[
            styles.glassCard,
            {
              backgroundColor: isDarkMode ? '#120A0A' : '#FFFFFF',
              borderColor: isDarkMode ? 'rgba(239, 68, 68, 0.10)' : 'rgba(15, 23, 42, 0.12)',
              padding: 16,
              marginBottom: 14,
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'space-between'
            }
          ]}
        >
          <View style={{ flexDirection: 'row', alignItems: 'center' }}>
            <Users size={20} color="#4ADE80" />
            <Text
              style={{
                color: isDarkMode ? '#FFFFFF' : '#0F172A',
                fontSize: 15,
                fontWeight: '900',
                marginLeft: 8
              }}
            >
              En Servicio (0-9)
            </Text>
          </View>

          <View
            style={{
              backgroundColor: 'rgba(22, 101, 52, 0.40)',
              borderColor: 'rgba(74, 222, 128, 0.40)',
              borderWidth: 1,
              borderRadius: 12,
              paddingHorizontal: 12,
              paddingVertical: 4
            }}
          >
            <Text style={{ color: '#4ADE80', fontSize: 14, fontWeight: '900' }}>
              {active09Count}
            </Text>
          </View>
        </View>

        {/* Grilla 3 Columnas idéntica a DisponiblesTab en TabScreens.kt */}
        <View style={styles.threeColGrid}>
          {displayedFirefighters.map((bombero) => {
            const st = getFirefighterStatusStyle(bombero.estado);
            const rawStatusText = String(bombero.estado || '0-8').trim().toUpperCase();
            const bPhoto = getPhotoUri(bombero.foto);

            return (
              <TouchableOpacity
                key={bombero.idRegistro || bombero.idRadial}
                style={[
                  styles.dispGridCard,
                  {
                    backgroundColor: isDarkMode ? '#120A0A' : '#FFFFFF',
                    borderColor: isDarkMode
                      ? 'rgba(239, 68, 68, 0.10)'
                      : 'rgba(15, 23, 42, 0.12)'
                  }
                ]}
                onPress={() => {
                  triggerHaptic('medium');
                  setSelectedBomberoModal(bombero);
                }}
                activeOpacity={0.78}
              >
                <View
                  style={[
                    styles.dispAvatarBox,
                    { borderColor: st.color }
                  ]}
                >
                  {bPhoto ? (
                    <Image source={{ uri: bPhoto }} style={styles.dispAvatarImg} resizeMode="cover" />
                  ) : logoUrl ? (
                    <Image source={{ uri: logoUrl }} style={styles.dispAvatarImg} resizeMode="cover" />
                  ) : (
                    <Image
                      source={require('../../assets/LOGO_SENTINEL.png')}
                      style={styles.dispAvatarImg}
                      resizeMode="contain"
                    />
                  )}
                </View>

                <Text
                  style={[
                    styles.dispCardName,
                    { color: isDarkMode ? '#FFFFFF' : '#0F172A' }
                  ]}
                  numberOfLines={2}
                >
                  {bombero.nombre || bombero.nombreBombero}
                </Text>

                <Text style={styles.dispCardCargo} numberOfLines={1}>
                  {bombero.idRadial} - {bombero.cargo || 'Bombero'}
                </Text>

                <View
                  style={[
                    styles.dispStatusBadge,
                    { backgroundColor: st.bg, borderColor: st.border }
                  ]}
                >
                  <Text style={[styles.dispStatusBadgeText, { color: st.color }]}>
                    {rawStatusText}
                  </Text>
                </View>
              </TouchableOpacity>
            );
          })}
        </View>
      </View>
    );
  };

  const userPhotoUri = getPhotoUri(user?.foto);
  const topCardColors = getTopStatusCardColors();
  const topHeaderPrimaryText = isDarkMode ? '#FFFFFF' : '#0F172A';
  const topHeaderSecondaryText = isDarkMode ? 'rgba(255, 255, 255, 0.70)' : 'rgba(15, 23, 42, 0.70)';
  const institutionFullName = (
    clientName ||
    nombreMostrar ||
    'CUERPO DE BOMBEROS'
  ).toUpperCase();

  return (
    <View
      style={styles.mainContainer}
      onTouchStart={handleTouchStart}
      onTouchMove={handleTouchMove}
      onTouchEnd={handleTouchEnd}
    >
      {/* ===================================================================== */}
      {/* FONDO ANIMADO CON CENIZAS VOLANDO (SentinelBackground en App.js)      */}
      {/* + MARCA DE AGUA INSTITUCIONAL EN AMBOS MODOS (OSCURO Y CLARO)         */}
      {/* ===================================================================== */}
      <View style={StyleSheet.absoluteFill} pointerEvents="none">
        {logoUrl ? (
          <Image
            source={{ uri: logoUrl }}
            style={[
              styles.institutionalWatermark,
              { opacity: isDarkMode ? 0.18 : 0.14 }
            ]}
            resizeMode="contain"
          />
        ) : null}
      </View>

      {/* ===================================================================== */}
      {/* CABECERA FIJA SUPERIOR V2 (TopAppBarView en MainScreen.kt)            */}
      {/* ===================================================================== */}
      <View style={styles.fixedHeaderSection}>
        {/* Logo SENTINEL ONE centrado sin texto adicional */}
        <View style={styles.v2BrandHeader}>
          <Image
            source={
              isDarkMode
                ? require('../../assets/SENTINEL_ONE.png')
                : require('../../assets/SENTINEL_ONE_LIGHT.png')
            }
            style={styles.v2BrandLogoImage}
            resizeMode="contain"
          />
        </View>

        {/* Tarjeta de Estado Superior V2 */}
        <View
          style={[
            styles.topProfileCard,
            {
              backgroundColor: topCardColors.bg,
              borderColor: topCardColors.border
            }
          ]}
        >
          {/* Nombre del Cuerpo de Bomberos en una sola línea (12sp, Black) */}
          <Text
            style={[
              styles.cuerpoSingleLineTitle,
              { color: isDarkMode ? 'rgba(255, 255, 255, 0.90)' : 'rgba(15, 23, 42, 0.90)' }
            ]}
            numberOfLines={1}
          >
            {institutionFullName}
          </Text>

          <View style={styles.profileRow}>
            {/* Foto + Nombre + Cargo (Al tocar abre el Drawer lateral) */}
            <TouchableOpacity
              style={styles.profileRowLeft}
              onPress={() => {
                triggerHaptic('medium');
                setProfileDrawerVisible(true);
              }}
              activeOpacity={0.75}
            >
              <View
                style={[
                  styles.shieldAvatarContainer,
                  {
                    borderColor: isDarkMode
                      ? 'rgba(255, 255, 255, 0.30)'
                      : 'rgba(15, 23, 42, 0.20)'
                  }
                ]}
              >
                <Image
                  source={
                    userPhotoUri
                      ? { uri: userPhotoUri }
                      : logoUrl
                        ? { uri: logoUrl }
                        : require('../../assets/LOGO_SENTINEL.png')
                  }
                  style={styles.shieldAvatar}
                  resizeMode="cover"
                />
              </View>

              <View style={styles.profileInfo}>
                <Text
                  style={[styles.profileName, { color: topHeaderPrimaryText }]}
                  numberOfLines={1}
                >
                  {formatFirefighterName(user?.nombreBombero || user?.nombre || 'BOMBERO')}
                </Text>
                <Text
                  style={[styles.profileRank, { color: topHeaderSecondaryText }]}
                  numberOfLines={1}
                >
                  {user?.idRadial || '1'} - {user?.cargo || 'Voluntario'}
                </Text>
              </View>
            </TouchableOpacity>

            {/* Botones 09 y 08 (54x44, borderRadius: 8) o Estado Especial */}
            {isRestrictedStatus ? (
              <View
                style={[
                  styles.restrictedStatusPill,
                  {
                    backgroundColor:
                      userEstado === 'PERMISO'
                        ? '#FF9500'
                        : userEstado === 'LICENCIA'
                          ? '#34A8DF'
                          : userEstado === 'CDS'
                            ? '#D1A100'
                            : '#8E24AA'
                  }
                ]}
              >
                <Text style={styles.restrictedStatusPillText}>{userEstado}</Text>
              </View>
            ) : (
              <View style={styles.toggle0908Container}>
                <TouchableOpacity
                  style={[
                    styles.btn0908Base,
                    is09
                      ? {
                          backgroundColor: '#00B050',
                          borderColor: '#00B050'
                        }
                      : {
                          backgroundColor: 'rgba(0, 176, 80, 0.15)',
                          borderColor: 'rgba(0, 176, 80, 0.50)'
                        }
                  ]}
                  onPress={() => {
                    if (inService) return;
                    triggerHaptic('medium');
                    updateStatus('0-9');
                  }}
                  disabled={inService}
                  activeOpacity={0.8}
                >
                  <Text
                    style={[
                      styles.btn0908Text,
                      {
                        color: is09
                          ? '#FFFFFF'
                          : isDarkMode
                            ? 'rgba(255, 255, 255, 0.60)'
                            : '#00B050'
                      }
                    ]}
                  >
                    09
                  </Text>
                </TouchableOpacity>

                <TouchableOpacity
                  style={[
                    styles.btn0908Base,
                    is08
                      ? {
                          backgroundColor: '#FF3B30',
                          borderColor: '#FF3B30'
                        }
                      : {
                          backgroundColor: 'rgba(255, 59, 48, 0.15)',
                          borderColor: 'rgba(255, 59, 48, 0.50)'
                        }
                  ]}
                  onPress={() => {
                    if (inService) return;
                    triggerHaptic('medium');
                    updateStatus('0-8');
                  }}
                  disabled={inService}
                  activeOpacity={0.8}
                >
                  <Text
                    style={[
                      styles.btn0908Text,
                      {
                        color: is08
                          ? '#FFFFFF'
                          : isDarkMode
                            ? 'rgba(255, 255, 255, 0.60)'
                            : '#FF3B30'
                      }
                    ]}
                  >
                    08
                  </Text>
                </TouchableOpacity>
              </View>
            )}
          </View>

          {/* Divisor horizontal + Título alineado a la izquierda (MainScreen.kt líneas 726-760) */}
          <View
            style={[
              styles.topCardDivider,
              {
                backgroundColor: isDarkMode
                  ? 'rgba(255, 255, 255, 0.15)'
                  : 'rgba(15, 23, 42, 0.12)'
              }
            ]}
          />
          {renderTabHeaderTitle(currentTab)}
        </View>
      </View>

      {/* Cuerpo desplazable con transición horizontal limpia (translateX + opacity sin scale) */}
      <ScrollView
        ref={scrollBodyRef}
        style={styles.scrollBody}
        contentContainerStyle={styles.scrollBodyContent}
        showsVerticalScrollIndicator={false}
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
      >
        <Animated.View
          style={{
            opacity: tabFadeAnim,
            transform: [{ translateX: tabSlideAnim }]
          }}
        >
          {currentTab === 'actividad' && renderActividadTab()}
          {currentTab === 'ordenes' && renderOrdenesTab()}
          {currentTab === 'alertas' && renderAlertasTab()}
          {currentTab === 'asistencia' && renderAsistenciaTab()}
          {currentTab === 'disponibles' && renderDisponiblesTab()}
        </Animated.View>
      </ScrollView>

      {/* Botón Flotante Circular (+) en Órdenes y Alertas (TabScreens.kt líneas 539-554 y 775-792) */}
      {currentTab === 'ordenes' && canPublishOrders ? (
        <TouchableOpacity
          style={styles.fabRedCircle}
          onPress={() => {
            triggerHaptic('medium');
            setCreateModalMode('orden');
          }}
          activeOpacity={0.85}
        >
          <Plus size={24} color="#FFFFFF" />
        </TouchableOpacity>
      ) : null}

      {currentTab === 'alertas' && canPublishAlerts ? (
        <TouchableOpacity
          style={styles.fabRedCircle}
          onPress={() => {
            triggerHaptic('medium');
            setCreateModalMode('alerta');
          }}
          activeOpacity={0.85}
        >
          <Plus size={24} color="#FFFFFF" />
        </TouchableOpacity>
      ) : null}

      {/* PANTALLA LATERAL IZQUIERDA (DRAWER OFICIAL V2) */}
      <ProfileDrawer
        visible={profileDrawerVisible}
        onClose={() => setProfileDrawerVisible(false)}
        onLogout={onLogout}
      />

      {/* MODAL DETALLE DE BOMBERO (DISPONIBLES) */}
      <FirefighterModal
        visible={Boolean(selectedBomberoModal)}
        bombero={selectedBomberoModal}
        onClose={() => setSelectedBomberoModal(null)}
      />

      {/* MODAL DETALLE DE ASISTENCIA (ASISTENCIA) */}
      <AttendanceDetailModal
        visible={Boolean(selectedAttendanceModal)}
        item={selectedAttendanceModal}
        onClose={() => setSelectedAttendanceModal(null)}
      />

      {/* MODAL DE ORDEN DEL DÍA O ALERTA FIRMADA */}
      <OrdenDetailModal
        visible={Boolean(selectedOrdenModal)}
        orden={selectedOrdenModal}
        onClose={() => setSelectedOrdenModal(null)}
      />

      {/* MODAL DE CHAT OPERACIONAL EN VIVO */}
      <ChatModal
        visible={chatModalVisible}
        onClose={() => setChatModalVisible(false)}
      />

      {/* MODAL DE CREACIÓN DE ORDEN DEL DÍA O ALERTA */}
      <CreateAlertOrderModal
        visible={Boolean(createModalMode)}
        mode={createModalMode || 'orden'}
        onClose={() => setCreateModalMode(null)}
      />

      {/* ===================================================================== */}
      {/* BARRA DE NAVEGACIÓN INFERIOR V2 (BottomNavigationBarView MainScreen.kt)*/}
      {/* ===================================================================== */}
      <View style={styles.v2BottomBarContainer}>
        <View
          style={[
            styles.v2BottomBar,
            {
              backgroundColor: isDarkMode ? '#000000' : '#FFFFFF',
              borderColor: isDarkMode ? '#450A0A' : 'rgba(15, 23, 42, 0.12)'
            }
          ]}
        >
          {/* 1. Actividad / Despacho */}
          <TouchableOpacity
            style={styles.v2TabTouchArea}
            onPress={() => {
              triggerHaptic('medium');
              switchTabAnimated('actividad');
            }}
            activeOpacity={0.8}
          >
            <View
              style={[
                styles.v2TabPill,
                currentTab === 'actividad' && styles.v2TabPillActive
              ]}
            >
              <Flame
                size={21}
                color={currentTab === 'actividad' ? '#F87171' : '#94A3B8'}
                fill={currentTab === 'actividad' ? '#F87171' : 'transparent'}
              />
              <Text
                style={[
                  styles.v2TabLabel,
                  currentTab === 'actividad' && {
                    color: '#F87171',
                    fontWeight: '700'
                  }
                ]}
              >
                {isMeCentralOperator ? 'Despacho' : 'Actividad'}
              </Text>
            </View>
          </TouchableOpacity>

          {/* 2. Órdenes */}
          <TouchableOpacity
            style={styles.v2TabTouchArea}
            onPress={() => {
              triggerHaptic('medium');
              switchTabAnimated('ordenes');
            }}
            activeOpacity={0.8}
          >
            <View
              style={[
                styles.v2TabPill,
                currentTab === 'ordenes' && styles.v2TabPillActive
              ]}
            >
              <FileText
                size={21}
                color={currentTab === 'ordenes' ? '#F87171' : '#94A3B8'}
              />
              <Text
                style={[
                  styles.v2TabLabel,
                  currentTab === 'ordenes' && {
                    color: '#F87171',
                    fontWeight: '700'
                  }
                ]}
              >
                Órdenes
              </Text>
            </View>
          </TouchableOpacity>

          {/* 3. Alertas */}
          <TouchableOpacity
            style={styles.v2TabTouchArea}
            onPress={() => {
              triggerHaptic('medium');
              switchTabAnimated('alertas');
            }}
            activeOpacity={0.8}
          >
            <View
              style={[
                styles.v2TabPill,
                currentTab === 'alertas' && styles.v2TabPillActive
              ]}
            >
              <Bell
                size={21}
                color={currentTab === 'alertas' ? '#F87171' : '#94A3B8'}
                fill={currentTab === 'alertas' ? '#F87171' : '#94A3B8'}
              />
              <Text
                style={[
                  styles.v2TabLabel,
                  currentTab === 'alertas' && {
                    color: '#F87171',
                    fontWeight: '700'
                  }
                ]}
              >
                Alertas
              </Text>
            </View>
          </TouchableOpacity>

          {/* 4. Asistencia */}
          <TouchableOpacity
            style={styles.v2TabTouchArea}
            onPress={() => {
              triggerHaptic('medium');
              switchTabAnimated('asistencia');
            }}
            activeOpacity={0.8}
          >
            <View
              style={[
                styles.v2TabPill,
                currentTab === 'asistencia' && styles.v2TabPillActive
              ]}
            >
              <Calendar
                size={21}
                color={currentTab === 'asistencia' ? '#F87171' : '#94A3B8'}
              />
              <Text
                style={[
                  styles.v2TabLabel,
                  currentTab === 'asistencia' && {
                    color: '#F87171',
                    fontWeight: '700'
                  }
                ]}
              >
                Asistencia
              </Text>
            </View>
          </TouchableOpacity>

          {/* 5. Disponibles (Visible cuando es Comandante idRadial == 1 o Central Activa) */}
          {showDisponiblesTab ? (
            <TouchableOpacity
              style={styles.v2TabTouchArea}
              onPress={() => {
                triggerHaptic('medium');
                switchTabAnimated('disponibles');
              }}
              activeOpacity={0.8}
            >
              <View
                style={[
                  styles.v2TabPill,
                  currentTab === 'disponibles' && styles.v2TabPillActive
                ]}
              >
                <Users
                  size={21}
                  color={currentTab === 'disponibles' ? '#F87171' : '#94A3B8'}
                />
                <Text
                  style={[
                    styles.v2TabLabel,
                    currentTab === 'disponibles' && {
                      color: '#F87171',
                      fontWeight: '700'
                    }
                  ]}
                >
                  Disponibles
                </Text>
              </View>
            </TouchableOpacity>
          ) : null}
        </View>
      </View>

      {/* ===================================================================== */}
      {/* BANNER URGENTE DE SOLICITUDES 12-10 / 6-6 / ESCALAMIENTO              */}
      {/* ===================================================================== */}
      {urgentBannerMessage ? (
        <View
          style={{
            position: 'absolute',
            top: Platform.OS === 'ios' ? 50 : 48,
            left: 14,
            right: 14,
            zIndex: 9500,
            backgroundColor: '#991B1B',
            borderRadius: 14,
            borderWidth: 1.5,
            borderColor: '#FCA5A5',
            paddingVertical: 12,
            paddingHorizontal: 14,
            flexDirection: 'row',
            alignItems: 'center',
            justifyContent: 'space-between',
            shadowColor: '#000',
            shadowOffset: { width: 0, height: 8 },
            shadowOpacity: 0.5,
            shadowRadius: 12,
            elevation: 14
          }}
        >
          <Text
            style={{
              flex: 1,
              color: '#FFFFFF',
              fontSize: 13,
              fontWeight: '800',
              marginRight: 10
            }}
          >
            {urgentBannerMessage}
          </Text>
          <TouchableOpacity
            onPress={() => {
              stopDispatchSound();
              clearUrgentBanner();
            }}
            style={{
              paddingHorizontal: 10,
              paddingVertical: 6,
              borderRadius: 8,
              backgroundColor: 'rgba(0,0,0,0.35)'
            }}
          >
            <Text style={{ color: '#FCA5A5', fontSize: 11, fontWeight: '800' }}>OK</Text>
          </TouchableOpacity>
        </View>
      ) : null}

      {/* ===================================================================== */}
      {/* ALERTA DE EMERGENCIA EN PANTALLA COMPLETA (PARIDAD EXACTA V2)         */}
      {/* Idéntico a EmergencyFullscreenOverlay en MainActivity.kt (1825-2097)  */}
      {/* ===================================================================== */}
      {(() => {
        if (!fullscreenDispatchId || isAirplaneMode || isMeCentralOperator) return null;
        const fsDispatch =
          (dispatchesList || []).find((d) => String(d.idServicio) === String(fullscreenDispatchId)) ||
          (activeDispatch && String(activeDispatch.idServicio) === String(fullscreenDispatchId) ? activeDispatch : null);
        if (!fsDispatch) return null;

        const claveUp = String(fsDispatch.clave || '').trim().toUpperCase();
        const isEscalationAlarm =
          claveUp.includes('10-30') ||
          claveUp.includes('10_30') ||
          claveUp.includes('FORESTAL') ||
          claveUp === '9-0' ||
          claveUp === '9.0' ||
          claveUp === '9_0' ||
          claveUp.includes('COMANDANCIA') ||
          claveUp.includes('LLAMADO');

        if ((is08 && !isEscalationAlarm) || isRestrictedStatus || hasAssisted || hasDeclined) {
          return null;
        }

        const fsClave =
          fsDispatch.clave === '10-12' && fsDispatch.claveApoyo
            ? `${fsDispatch.clave} (${fsDispatch.claveApoyo})`
            : fsDispatch.clave || '10-0';

        const horaLimpia = cleanSheetPrefix(fsDispatch.horaDespacho || fsDispatch.horaStr || '').trim();
        const rawLugar = String(fsDispatch.direccion || fsDispatch.lugar || fsDispatch.location || '').trim();
        const rawLugarUp = rawLugar.toUpperCase();
        const lugarLimpio =
          !rawLugar ||
          rawLugarUp === '---' ||
          rawLugarUp === '-' ||
          rawLugarUp === 'SIN DIRECCIÓN' ||
          rawLugarUp === 'SIN DIRECCION' ||
          rawLugarUp === 'DIRECCIÓN PENDIENTE' ||
          rawLugarUp === 'DIRECCION PENDIENTE' ||
          rawLugarUp.includes('OBTENIENDO') ||
          rawLugarUp.includes('EN PROCESO DE GEORREFERENCI')
            ? ''
            : rawLugar;

        const carrosLimpios = (() => {
          if (typeof fsDispatch.carrosStr === 'string' && fsDispatch.carrosStr.trim()) {
            return fsDispatch.carrosStr.trim();
          }
          if (Array.isArray(fsDispatch.carros) && fsDispatch.carros.length > 0) {
            return fsDispatch.carros.join(' ');
          }
          if (typeof fsDispatch.carros === 'string' && fsDispatch.carros.trim()) {
            return fsDispatch.carros.trim();
          }
          return '';
        })();

        return (
          <View
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              right: 0,
              bottom: 0,
              zIndex: 9999,
              backgroundColor: isDarkMode ? '#140202' : '#F8FAFC'
            }}
          >
            {/* LAYER 0: IMMERSIVE FULL-SCREEN MAP CANVAS (100% Edge-to-Edge) */}
            <TacticalDispatchMap
              dispatch={fsDispatch}
              isFullScreen={true}
            />

            {/* LAYER 1: FULLSCREEN TRANSLUCENT OVERLAY (Subtle vignette for readability, keeping map clearly visible) */}
            <LinearGradient
              colors={
                isDarkMode
                  ? ['rgba(0,0,0,0.40)', 'rgba(0,0,0,0.17)', 'rgba(0,0,0,0.08)', 'rgba(0,0,0,0.17)', 'rgba(0,0,0,0.45)']
                  : ['rgba(255,255,255,0.40)', 'rgba(255,255,255,0.17)', 'rgba(255,255,255,0.08)', 'rgba(255,255,255,0.17)', 'rgba(255,255,255,0.45)']
              }
              locations={[0, 0.25, 0.5, 0.75, 1]}
              style={{
                flex: 1,
                justifyContent: 'space-between',
                paddingTop: Platform.OS === 'ios' ? 54 : 46,
                paddingBottom: 72,
                paddingHorizontal: 20
              }}
            >
              {/* LAYER 2: FLOATING TOP HUD (Clean & Minimalist: Logo, Clave, Hora, Dirección si existe, Unidades en ámbar - Sin Íconos ni píldoras extra) */}
              <View style={{ alignItems: 'center', paddingHorizontal: 4 }}>
                {logoUrl ? (
                  <Image
                    source={{ uri: logoUrl }}
                    style={{ width: 84, height: 84, marginBottom: 8 }}
                    resizeMode="contain"
                  />
                ) : null}

                <Text
                  style={{
                    fontSize: fsClave.length > 5 ? 46 : 60,
                    fontWeight: '900',
                    color: isDarkMode ? '#FFFFFF' : '#0F172A',
                    textAlign: 'center',
                    letterSpacing: 1,
                    textShadowColor: isDarkMode ? '#000000' : 'transparent',
                    textShadowOffset: { width: 0, height: 4 },
                    textShadowRadius: 14
                  }}
                >
                  {fsClave}
                </Text>

                {horaLimpia ? (
                  <Text
                    style={{
                      fontSize: 17,
                      fontWeight: '700',
                      color: isDarkMode ? '#E2E8F0' : '#334155',
                      textAlign: 'center',
                      marginTop: 2,
                      textShadowColor: isDarkMode ? '#000000' : 'transparent',
                      textShadowOffset: { width: 0, height: 2 },
                      textShadowRadius: 6
                    }}
                  >
                    {horaLimpia}
                  </Text>
                ) : null}

                {lugarLimpio ? (
                  <Text
                    numberOfLines={2}
                    style={{
                      fontSize: 16,
                      fontWeight: '700',
                      color: isDarkMode ? '#F1F5F9' : '#1E293B',
                      textAlign: 'center',
                      marginTop: 8,
                      textShadowColor: isDarkMode ? '#000000' : 'transparent',
                      textShadowOffset: { width: 0, height: 2 },
                      textShadowRadius: 8
                    }}
                  >
                    {lugarLimpio.toUpperCase()}
                  </Text>
                ) : null}

                {carrosLimpios ? (
                  <Text
                    style={{
                      fontSize: 15,
                      fontWeight: '900',
                      color: '#FBBF24',
                      textAlign: 'center',
                      marginTop: 6,
                      textShadowColor: isDarkMode ? '#000000' : 'transparent',
                      textShadowOffset: { width: 0, height: 2 },
                      textShadowRadius: 6
                    }}
                  >
                    {carrosLimpios}
                  </Text>
                ) : null}
              </View>

              {/* LAYER 3: FLOATING BOTTOM ACTION BUTTONS (Clean, accessible, raised above navigation bar — ASISTIR #10B981 / NO ASISTIR #180404 con borde rojo) */}
              <View style={{ flexDirection: 'row', gap: 16, alignItems: 'center', width: '100%' }}>
                <TouchableOpacity
                  activeOpacity={0.85}
                  onPress={() => {
                    triggerHaptic('heavy');
                    stopDispatchSound();
                    const targetId = fsDispatch.idServicio;
                    dismissFullscreenDispatch(targetId);
                    setEmergencyResponse(targetId, 'ASISTO');
                    setCurrentTab('actividad');
                  }}
                  style={{
                    flex: 1,
                    height: 56,
                    borderRadius: 16,
                    backgroundColor: '#10B981',
                    alignItems: 'center',
                    justifyContent: 'center',
                    shadowColor: '#000000',
                    shadowOffset: { width: 0, height: 6 },
                    shadowOpacity: 0.45,
                    shadowRadius: 8,
                    elevation: 8
                  }}
                >
                  <Text
                    style={{
                      color: '#FFFFFF',
                      fontSize: 18,
                      fontWeight: '900',
                      letterSpacing: 0.5
                    }}
                  >
                    ASISTIR
                  </Text>
                </TouchableOpacity>

                <TouchableOpacity
                  activeOpacity={0.85}
                  onPress={() => {
                    triggerHaptic('medium');
                    stopDispatchSound();
                    const targetId = fsDispatch.idServicio;
                    dismissFullscreenDispatch(targetId);
                    setEmergencyResponse(targetId, 'NO_ASISTO');
                  }}
                  style={{
                    flex: 1,
                    height: 56,
                    borderRadius: 16,
                    backgroundColor: isDarkMode ? 'rgba(24, 4, 4, 0.85)' : '#FEF2F2',
                    borderWidth: 2,
                    borderColor: 'rgba(239, 68, 68, 0.90)',
                    alignItems: 'center',
                    justifyContent: 'center',
                    shadowColor: '#000000',
                    shadowOffset: { width: 0, height: 6 },
                    shadowOpacity: 0.35,
                    shadowRadius: 8,
                    elevation: 8
                  }}
                >
                  <Text
                    style={{
                      color: isDarkMode ? '#FFFFFF' : '#B91C1C',
                      fontSize: 17,
                      fontWeight: '700',
                      letterSpacing: 0.5
                    }}
                  >
                    NO ASISTIR
                  </Text>
                </TouchableOpacity>
              </View>
            </LinearGradient>
          </View>
        );
      })()}
    </View>
  );
};

const styles = StyleSheet.create({
  mainContainer: {
    flex: 1,
    paddingTop: Platform.OS === 'ios' ? 44 : 42
  },
  institutionalWatermark: {
    position: 'absolute',
    right: -20,
    bottom: 98,
    width: 240,
    height: 240,
    opacity: 0.18
  },
  topGradientOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    height: 280
  },
  fixedHeaderSection: {
    paddingHorizontal: 14,
    paddingBottom: 0,
    zIndex: 20
  },
  scrollBody: {
    flex: 1,
    marginTop: -44,
    marginHorizontal: 14,
    overflow: 'hidden',
    WebkitMaskImage: 'linear-gradient(to bottom, transparent 0px, black 6px, black 100%)',
    maskImage: 'linear-gradient(to bottom, transparent 0px, black 6px, black 100%)'
  },
  scrollBodyContent: {
    paddingHorizontal: 0,
    paddingTop: 56,
    paddingBottom: 132
  },
  v2BrandHeader: {
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 8
  },
  v2BrandLogoImage: {
    height: 48,
    width: '72%'
  },
  tabScrollContainer: {
    width: '100%'
  },

  // Tarjeta Superior V2 (MainScreen.kt líneas 534-762)
  topProfileCard: {
    borderRadius: 20,
    padding: 14,
    marginBottom: 0,
    borderWidth: 1,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.35,
    shadowRadius: 10,
    elevation: 8
  },
  cuerpoSingleLineTitle: {
    fontSize: 12.5,
    fontWeight: '700',
    textAlign: 'center',
    letterSpacing: 0.6,
    marginBottom: 10
  },
  profileRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between'
  },
  profileRowLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    flex: 1,
    marginRight: 8
  },
  shieldAvatarContainer: {
    width: 56,
    height: 56,
    borderRadius: 10,
    borderWidth: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.25)',
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 12,
    overflow: 'hidden'
  },
  shieldAvatar: {
    width: '100%',
    height: '100%'
  },
  profileInfo: {
    flex: 1,
    marginRight: 6
  },
  profileName: {
    fontSize: 16,
    fontWeight: '700'
  },
  profileRank: {
    fontSize: 12.5,
    fontWeight: '500',
    marginTop: 2
  },
  restrictedStatusPill: {
    height: 48,
    paddingHorizontal: 14,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center'
  },
  restrictedStatusPillText: {
    fontSize: 13,
    fontWeight: '900',
    color: '#FFFFFF',
    letterSpacing: 0.8
  },
  toggle0908Container: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8
  },
  btn0908Base: {
    width: 58,
    height: 48,
    borderRadius: 10,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center'
  },
  btn0908Text: {
    fontSize: 18,
    fontWeight: '900'
  },
  topCardDivider: {
    height: 1,
    width: '100%',
    marginTop: 14
  },
  sectionFooterTitle: {
    fontSize: 14.5,
    paddingTop: 10,
    paddingBottom: 2,
    paddingLeft: 8
  },
  titlePrefixGray: {
    fontSize: 14.5,
    fontWeight: '400'
  },
  titleHighlightWhite: {
    fontSize: 14.5,
    fontWeight: '900'
  },

  // GlassCard Base (Components.kt líneas 526-548 - sin sombra lateral para evitar corte junto al header)
  glassCard: {
    borderRadius: 20,
    borderWidth: 1
  },

  // Barra Operador CAD (TabScreens.kt líneas 214-274)
  cadOperatorRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between'
  },
  cadOperatorLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    flex: 1,
    marginRight: 8
  },
  liveStatusDot: {
    width: 11,
    height: 11,
    borderRadius: 5.5,
    marginRight: 10
  },
  cadOperatorText: {
    fontSize: 12.5,
    fontWeight: '900',
    flexShrink: 1
  },
  timbreButton: {
    height: 36,
    paddingHorizontal: 16,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center'
  },
  timbreButtonText: {
    fontSize: 11,
    fontWeight: '900'
  },

  // Consola de Despacho Central (TabScreens.kt líneas 53-204)
  consoleLabel: {
    fontSize: 10,
    fontWeight: '900',
    color: '#94A3B8',
    letterSpacing: 0.5,
    marginBottom: 4
  },
  claveChip: {
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 10,
    backgroundColor: 'rgba(0,0,0,0.35)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)'
  },
  claveChipActive: {
    backgroundColor: '#DC2626',
    borderColor: '#EF4444'
  },
  claveChipText: {
    fontSize: 12,
    fontWeight: '900',
    color: '#94A3B8'
  },
  consoleInput: {
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 11,
    fontSize: 14,
    marginBottom: 12,
    outlineStyle: 'none'
  },
  unitsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginTop: 4,
    marginBottom: 16
  },
  unitSelectChip: {
    width: '31%',
    height: 46,
    borderRadius: 12,
    backgroundColor: 'rgba(0,0,0,0.40)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.10)',
    alignItems: 'center',
    justifyContent: 'center'
  },
  unitSelectChipActive: {
    backgroundColor: '#16A34A',
    borderColor: '#4ADE80'
  },
  unitSelectChipDisabled: {
    backgroundColor: 'rgba(0,0,0,0.20)',
    borderColor: 'rgba(255,255,255,0.04)'
  },
  unitSelectText: {
    fontSize: 13,
    fontWeight: '900',
    color: '#94A3B8'
  },
  btnDespacharCentral: {
    backgroundColor: '#DC2626',
    height: 52,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center'
  },
  btnDespacharCentralText: {
    fontSize: 14,
    fontWeight: '900',
    color: '#FFFFFF',
    letterSpacing: 1
  },

  // Grilla 2 Columnas (Órdenes y Asistencia)
  twoColGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
    rowGap: 10
  },

  // Tarjeta de Orden del Día en Grilla 2 Columnas (TabScreens.kt líneas 503-535)
  ordenGridCard: {
    width: '48.5%',
    borderRadius: 20,
    borderWidth: 1,
    padding: 14,
    alignItems: 'center',
    justifyContent: 'center'
  },
  ordenIconCircle: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: 'rgba(239, 68, 68, 0.15)',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 8
  },
  ordenGridTitle: {
    fontSize: 14,
    fontWeight: '900',
    textAlign: 'center'
  },
  ordenGridDate: {
    fontSize: 11,
    color: '#94A3B8',
    marginTop: 2,
    textAlign: 'center'
  },
  cardThinDivider: {
    height: 1,
    width: '100%',
    marginVertical: 8
  },
  vistoStatusText: {
    fontSize: 11,
    fontWeight: '700',
    textAlign: 'center'
  },

  // Pestaña Alertas (TabScreens.kt líneas 592-768)
  chatResponderBtn: {
    height: 32,
    paddingHorizontal: 14,
    borderRadius: 8,
    backgroundColor: '#0284C7',
    alignItems: 'center',
    justifyContent: 'center'
  },
  chatResponderBtnText: {
    fontSize: 11,
    fontWeight: '900',
    color: '#FFFFFF'
  },
  alertaIconCircle: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: 'rgba(245, 158, 11, 0.15)',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 8
  },
  alertaCardTitle: {
    fontSize: 15,
    fontWeight: '900',
    textAlign: 'center',
    paddingHorizontal: 8
  },
  alertaCardDate: {
    fontSize: 11,
    color: '#94A3B8',
    marginTop: 4,
    textAlign: 'center'
  },

  // Pestaña Asistencia (TabScreens.kt líneas 841-1020)
  v2ProgressCircle: {
    width: 56,
    height: 56,
    borderRadius: 28,
    borderWidth: 5,
    borderColor: '#EF4444',
    alignItems: 'center',
    justifyContent: 'center'
  },
  v2MiniStatBox: {
    borderRadius: 10,
    paddingHorizontal: 10,
    paddingVertical: 6,
    alignItems: 'center'
  },
  historialHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 10,
    paddingHorizontal: 2
  },
  historialLeftGroup: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    flexShrink: 1
  },
  historialTitle: {
    fontSize: 11.5,
    fontWeight: '900',
    color: '#94A3B8',
    letterSpacing: 0.8
  },
  historyFilterPillGroup: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: 10,
    borderWidth: 1,
    padding: 2,
    gap: 2
  },
  historyFilterBtn: {
    paddingHorizontal: 9,
    paddingVertical: 4,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center'
  },
  historyFilterBtnActiveApproved: {
    backgroundColor: '#10B981'
  },
  historyFilterBtnActivePending: {
    backgroundColor: '#F59E0B'
  },
  historyFilterBtnText: {
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 0.2
  },
  sincronizarBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 4,
    paddingHorizontal: 4
  },
  sincronizarText: {
    fontSize: 11,
    fontWeight: '700',
    color: '#EF4444'
  },
  v2HistoryCard: {
    width: '48.5%',
    borderRadius: 20,
    padding: 12,
    borderWidth: 1,
    backdropFilter: 'blur(8px)',
    WebkitBackdropFilter: 'blur(8px)'
  },
  v2HistoryTop: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center'
  },
  v2CardClave: {
    fontSize: 16,
    fontWeight: '900',
    flexShrink: 1,
    marginRight: 4
  },
  v2CardStatus: {
    fontSize: 9,
    fontWeight: '900'
  },
  v2CardDate: {
    fontSize: 11,
    color: '#94A3B8'
  },
  v2AbonoChip: {
    backgroundColor: 'rgba(56, 189, 248, 0.20)',
    borderColor: 'rgba(56, 189, 248, 0.50)',
    borderWidth: 1,
    paddingHorizontal: 5,
    paddingVertical: 1,
    borderRadius: 4
  },
  v2AbonoText: {
    fontSize: 8,
    fontWeight: '900',
    color: '#38BDF8'
  },
  v2CardLugar: {
    fontSize: 11,
    fontWeight: '500',
    marginTop: 4
  },

  // Pestaña Disponibles (TabScreens.kt líneas 1022-1197)
  companyFilterBar: {
    flexDirection: 'row',
    gap: 8,
    marginBottom: 12
  },
  compFilterBtn: {
    flex: 1,
    paddingVertical: 8,
    borderRadius: 10,
    backgroundColor: '#120A0A',
    alignItems: 'center',
    borderWidth: 1,
    borderColor: 'rgba(239, 68, 68, 0.20)'
  },
  compFilterBtnActive: {
    backgroundColor: '#DC2626',
    borderColor: '#EF4444'
  },
  compFilterText: {
    fontSize: 11,
    fontWeight: '800',
    color: '#94A3B8'
  },
  compFilterTextActive: {
    color: '#FFFFFF'
  },
  threeColGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
    rowGap: 8
  },
  dispGridCard: {
    width: '31.8%',
    borderRadius: 20,
    borderWidth: 1,
    paddingVertical: 10,
    paddingHorizontal: 4,
    alignItems: 'center',
    justifyContent: 'center'
  },
  dispAvatarBox: {
    width: 44,
    height: 44,
    borderRadius: 10,
    borderWidth: 1.5,
    backgroundColor: 'rgba(0,0,0,0.40)',
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
    marginBottom: 6
  },
  dispAvatarImg: {
    width: '100%',
    height: '100%'
  },
  dispCardName: {
    fontSize: 10,
    fontWeight: '700',
    textAlign: 'center',
    lineHeight: 12,
    minHeight: 24,
    paddingHorizontal: 2
  },
  dispCardCargo: {
    fontSize: 9,
    color: '#94A3B8',
    textAlign: 'center',
    marginTop: 2
  },
  dispStatusBadge: {
    marginTop: 6,
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 6,
    borderWidth: 1
  },
  dispStatusBadgeText: {
    fontSize: 9,
    fontWeight: '900'
  },

  // Botón Flotante Circular (+) (TabScreens.kt líneas 543-554)
  fabRedCircle: {
    position: 'absolute',
    right: 20,
    bottom: 104,
    zIndex: 45,
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: '#DC2626',
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.4,
    shadowRadius: 8,
    elevation: 14
  },

  // Barra Inferior de Navegación (BottomNavigationBarView MainScreen.kt líneas 767-948)
  v2BottomBarContainer: {
    position: 'absolute',
    bottom: 14,
    left: 0,
    right: 0,
    zIndex: 50,
    elevation: 20,
    paddingHorizontal: 14,
    paddingTop: 6,
    paddingBottom: Platform.OS === 'ios' ? 20 : 10
  },
  v2BottomBar: {
    height: 70,
    borderRadius: 28,
    borderWidth: 1.2,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-around',
    paddingHorizontal: 6,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.5,
    shadowRadius: 16,
    elevation: 16
  },
  v2TabTouchArea: {
    flex: 1,
    height: '100%',
    alignItems: 'center',
    justifyContent: 'center'
  },
  v2TabPill: {
    minWidth: 66,
    height: 54,
    paddingHorizontal: 10,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center'
  },
  v2TabPillActive: {
    backgroundColor: 'rgba(239, 68, 68, 0.16)',
    borderWidth: 1,
    borderColor: 'rgba(239, 68, 68, 0.35)'
  },
  v2TabLabel: {
    fontSize: 10.5,
    color: '#94A3B8',
    fontWeight: '600',
    marginTop: 2
  },
  tabBadgeDot: {
    position: 'absolute',
    top: -4,
    right: -8,
    backgroundColor: '#EF4444',
    borderRadius: 8,
    minWidth: 15,
    height: 15,
    paddingHorizontal: 3,
    alignItems: 'center',
    justifyContent: 'center'
  },
  tabBadgeText: {
    fontSize: 9,
    fontWeight: '900',
    color: '#FFFFFF'
  }
});
