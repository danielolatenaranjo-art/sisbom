import React, { useEffect, useState, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Platform,
  Linking,
  Image,
  Animated,
  Easing
} from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { Check, X } from 'lucide-react-native';
import { useLicenseStore } from '../../store/useLicenseStore';
import { useAuthStore } from '../../store/useAuthStore';

function latLngToTile(lat, lng, zoom = 15) {
  const n = Math.pow(2, zoom);
  const xFloat = ((lng + 180) / 360) * n;
  const latRad = (lat * Math.PI) / 180;
  const yFloat = ((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2) * n;
  const xTile = Math.floor(xFloat);
  const yTile = Math.floor(yFloat);
  return { xTile, yTile };
}

// Emoji táctico por tipo de unidad bomberil (Idéntico a TabScreens.kt getVehicleEmoji)
export function getVehicleEmoji(carro = '') {
  const upper = String(carro).trim().toUpperCase();
  if (upper.startsWith('B') || upper.startsWith('BX') || upper.startsWith('BF')) return '🚒';
  if (upper.startsWith('R') || upper.startsWith('RX') || upper.startsWith('RH') || upper.startsWith('RS')) return '🚑';
  if (upper.startsWith('Q') || upper.startsWith('M') || upper.startsWith('MX')) return '🪜';
  if (upper.startsWith('Z') || upper.startsWith('BT')) return '🚚';
  if (upper.startsWith('K') || upper.startsWith('J') || upper.startsWith('UT')) return '🚙';
  if (upper.startsWith('H') || upper.startsWith('HZ')) return '☣️';
  return '🚒';
}

// Color táctico según clave radial (Idéntico a Components.kt getClavePinHex)
export function getClavePinHex(clave = '10-0') {
  const cleanKey = String(clave).trim().toUpperCase();
  if (cleanKey === '10-0' || cleanKey.startsWith('10-0') || cleanKey.includes('10-30') || cleanKey.includes('10_30')) return '#DC2626';
  if (cleanKey === '10-1') return '#EA580C';
  if (cleanKey === '10-2' || cleanKey.includes('FORESTAL') || cleanKey.includes('PASTIZAL')) return '#D97706';
  if (cleanKey === '10-3' || cleanKey === '10-8') return '#0284C7';
  if (cleanKey === '10-4') return '#E11D48';
  if (cleanKey === '10-5' || cleanKey.includes('HAZMAT')) return '#7C3AED';
  if (cleanKey === '10-6' || cleanKey.includes('GAS')) return '#0D9488';
  if (cleanKey === '10-7' || cleanKey.includes('ELECTR')) return '#EAB308';
  if (cleanKey === '10-9') return '#C2410C';
  if (cleanKey === '10-10') return '#0369A1';
  if (cleanKey === '10-11') return '#2563EB';
  if (cleanKey === '10-12') return '#4F46E5';
  if (cleanKey === '10-14') return '#059669';
  return '#DC2626';
}

export function isSolicitudActiva(timestamp) {
  if (!timestamp) return false;
  const elapsed = Date.now() - Number(timestamp);
  return elapsed >= 0 && elapsed < 60000;
}

function formatOperatorCadName(fullName = '') {
  const words = String(fullName).trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '';
  if (words.length === 1) return words[0];
  if (words.length === 2) return `${words[0]} ${words[1]}`;
  return `${words[0]} ${words[words.length - 2]}`;
}

function cleanLugarDisplay(lugar = '') {
  const t = String(lugar).trim();
  if (!t) return '';
  const up = t.toUpperCase();
  if (
    up === '---' ||
    up === '-' ||
    up === 'SIN DIRECCIÓN' ||
    up === 'SIN DIRECCION' ||
    up === 'DIRECCIÓN PENDIENTE' ||
    up === 'DIRECCION PENDIENTE' ||
    up.includes('OBTENIENDO') ||
    up.includes('EN PROCESO DE GEORREFERENCI')
  ) {
    return '';
  }
  return t;
}

function cleanSheetPrefix(s = '') {
  return String(s).replace(/^['"´`‘’]+|['"´`‘’]+$/g, '').trim();
}

export const TacticalDispatchMap = ({
  dispatch,
  hasAssisted = false,
  hasDeclined = false,
  is08 = false,
  isFullScreen = false,
  onAsistir = () => {},
  onNoAsistir = () => {},
  onCancelarAsistencia = null
}) => {
  const { cuartelLat = -34.637373, cuartelLng = -71.125741, comuna = '', region = '' } = useLicenseStore();
  const { isDarkMode: isDark = true } = useAuthStore();
  const [, setNowTick] = useState(Date.now());
  const [geocodedCoords, setGeocodedCoords] = useState(null);
  const pulseScaleAnim = useRef(new Animated.Value(0.85)).current;
  const pulseAlphaAnim = useRef(new Animated.Value(0.3)).current;

  // Animación idéntica a AnimatedVisibility en TabScreens.kt (líneas 664-678):
  // enter = fadeIn(300ms) + slideInVertically(300ms) + expandVertically(300ms)
  // exit  = fadeOut(200ms) + slideOutVertically(200ms) + shrinkVertically(200ms)
  const isButtonsVisible = !is08 || hasAssisted;
  const [shouldRenderButtons, setShouldRenderButtons] = useState(isButtonsVisible);
  const visibilityAnim = useRef(new Animated.Value(isButtonsVisible ? 1 : 0)).current;
  const actionSwitchAnim = useRef(new Animated.Value(1)).current;

  useEffect(() => {
    const timer = setInterval(() => setNowTick(Date.now()), 2000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    const loop = Animated.loop(
      Animated.parallel([
        Animated.sequence([
          Animated.timing(pulseScaleAnim, {
            toValue: 1.25,
            duration: 1800,
            easing: Easing.linear,
            useNativeDriver: false
          }),
          Animated.timing(pulseScaleAnim, {
            toValue: 0.85,
            duration: 1800,
            easing: Easing.linear,
            useNativeDriver: false
          })
        ]),
        Animated.sequence([
          Animated.timing(pulseAlphaAnim, {
            toValue: 0.8,
            duration: 1800,
            easing: Easing.linear,
            useNativeDriver: false
          }),
          Animated.timing(pulseAlphaAnim, {
            toValue: 0.3,
            duration: 1800,
            easing: Easing.linear,
            useNativeDriver: false
          })
        ])
      ])
    );
    loop.start();
    return () => loop.stop();
  }, []);

  useEffect(() => {
    if (isButtonsVisible) {
      setShouldRenderButtons(true);
      Animated.timing(visibilityAnim, {
        toValue: 1,
        duration: 300,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: false
      }).start();
    } else {
      Animated.timing(visibilityAnim, {
        toValue: 0,
        duration: 200,
        easing: Easing.in(Easing.cubic),
        useNativeDriver: false
      }).start(({ finished }) => {
        if (finished) {
          setShouldRenderButtons(false);
        }
      });
    }
  }, [isButtonsVisible]);

  useEffect(() => {
    actionSwitchAnim.setValue(0.92);
    Animated.spring(actionSwitchAnim, {
      toValue: 1,
      friction: 6,
      tension: 120,
      useNativeDriver: false
    }).start();
  }, [hasAssisted, hasDeclined]);

  const rawLat = Number(
    dispatch?.geo?.lat ??
      dispatch?.coords?.lat ??
      dispatch?.geolocalizacion?.lat ??
      dispatch?.ubicacion?.lat ??
      dispatch?.lat ??
      dispatch?.latitud ??
      0
  );
  const rawLng = Number(
    dispatch?.geo?.lng ??
      dispatch?.coords?.lng ??
      dispatch?.geolocalizacion?.lng ??
      dispatch?.ubicacion?.lng ??
      dispatch?.lng ??
      dispatch?.longitud ??
      0
  );
  const cleanLugar = cleanLugarDisplay(
    dispatch?.direccion || dispatch?.lugar || dispatch?.location || dispatch?.ubicacionTexto || ''
  );
  const dispatchComuna = String(dispatch?.comuna || comuna || '').trim();

  const geoOrigen = String(dispatch?.geo?.origen || '').trim().toUpperCase();
  const isDefaultCentralPlaceholder = geoOrigen === 'CENTRAL_DEFECTO';
  const hasDirectCoords = Boolean(
    rawLat !== 0 &&
      rawLng !== 0 &&
      !isNaN(rawLat) &&
      !isNaN(rawLng) &&
      Math.abs(rawLat) > 0.0001 &&
      Math.abs(rawLng) > 0.0001 &&
      (!isDefaultCentralPlaceholder ||
        Math.abs(rawLat - cuartelLat) > 0.00005 ||
        Math.abs(rawLng - cuartelLng) > 0.00005)
  );

  // Si el despacho tiene dirección escrita en CAD pero aún no tiene coordenadas exactas, geocodificar automáticamente
  useEffect(() => {
    if (hasDirectCoords || !cleanLugar || cleanLugar.length < 3) {
      setGeocodedCoords(null);
      return;
    }
    let cancelled = false;
    const coordMatch = cleanLugar.match(/(-?\d{1,2}\.\d{3,})\s*[,;/ ]\s*(-?\d{1,3}\.\d{3,})/);
    if (coordMatch) {
      const pLat = parseFloat(coordMatch[1]);
      const pLng = parseFloat(coordMatch[2]);
      if (!isNaN(pLat) && !isNaN(pLng)) {
        setGeocodedCoords({ lat: pLat, lng: pLng });
        return;
      }
    }

    const qParts = [cleanLugar];
    if (dispatchComuna) qParts.push(dispatchComuna);
    if (region) qParts.push(region);
    qParts.push('Chile');
    const url = `https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=cl&q=${encodeURIComponent(
      qParts.join(', ')
    )}`;
    fetch(url, { headers: { Accept: 'application/json' } })
      .then((r) => r.json())
      .then((data) => {
        if (cancelled) return;
        if (Array.isArray(data) && data[0]?.lat && data[0]?.lon) {
          setGeocodedCoords({
            lat: parseFloat(data[0].lat),
            lng: parseFloat(data[0].lon)
          });
        }
      })
      .catch(() => {});

    return () => {
      cancelled = true;
    };
  }, [hasDirectCoords, cleanLugar, dispatchComuna, region]);

  const hasValidGps = Boolean(
    hasDirectCoords ||
      (geocodedCoords &&
        Math.abs(geocodedCoords.lat) > 0.0001 &&
        Math.abs(geocodedCoords.lng) > 0.0001)
  );

  const lat = hasDirectCoords
    ? rawLat
    : geocodedCoords?.lat
      ? geocodedCoords.lat
      : cuartelLat;
  const lng = hasDirectCoords
    ? rawLng
    : geocodedCoords?.lng
      ? geocodedCoords.lng
      : cuartelLng;

  const clave = dispatch?.clave || '10-0';
  const claveText =
    clave === '10-12' && dispatch?.claveApoyo
      ? `${clave} (${dispatch.claveApoyo})`
      : clave;
  const pinHex = getClavePinHex(clave);

  const horaStr = cleanSheetPrefix(dispatch?.horaStr || dispatch?.horaDespacho || '') || '--:--';
  const unidadesMap = dispatch?.unidades || {};

  const carrosList = (() => {
    if (Array.isArray(dispatch?.carros) && dispatch.carros.length > 0) {
      return dispatch.carros.map((c) => String(c).trim()).filter(Boolean);
    }
    if (typeof dispatch?.carros === 'string' && dispatch.carros.trim()) {
      return dispatch.carros.split(/[,/ ]+/).map((c) => c.trim()).filter(Boolean);
    }
    return Object.keys(unidadesMap);
  })();

  const hora67 = cleanSheetPrefix(dispatch?.hora67 || '');
  const operadorName = (dispatch?.quienDespacha || dispatch?.operadorInicial || dispatch?.operadorFinal || '').trim();

  // Pre-informe
  const rawPreinforme = String(dispatch?.preinforme || '').trim().replace(/---/g, '').trim();
  const hasPreinforme = rawPreinforme.length > 0 && !rawPreinforme.toLowerCase().includes('a la espera');

  const handleOpenExternalMaps = () => {
    if (!hasValidGps) return;
    const label = cleanLugar ? `${clave} - ${cleanLugar}` : `Emergencia ${clave}`;
    const geoUrl = `geo:${lat},${lng}?q=${lat},${lng}(${encodeURIComponent(label)})`;
    const iosMapsUrl = `http://maps.apple.com/?ll=${lat},${lng}&q=${encodeURIComponent(label)}`;
    const webUrl = `https://www.google.com/maps/search/?api=1&query=${lat},${lng}`;

    try {
      if (typeof window !== 'undefined' && window.AndroidApp?.openExternalUrl) {
        window.AndroidApp.openExternalUrl(geoUrl);
        return;
      }
      if (typeof window !== 'undefined' && window.webkit?.messageHandlers?.iOSApp) {
        window.webkit.messageHandlers.iOSApp.postMessage({
          action: 'openExternalUrl',
          url: iosMapsUrl
        });
        return;
      }
    } catch (_) {}

    Linking.openURL(webUrl).catch(() => {});
  };

  // Exact OSMDroid / GoogleMapsRoadmap color filter from IncidentMapPreview in TabScreens.kt
  const mapHtml = `
    <!DOCTYPE html>
    <html lang="es" class="notranslate" translate="no">
    <head>
      <meta charset="utf-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1.0" />
      <meta name="google" content="notranslate" />
      <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
      <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
      <style>
        body, html, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: ${isDark ? '#140303' : '#F1F5F9'}; }
        ${
          isDark
            ? `.leaflet-tile-pane { filter: invert(85%) hue-rotate(180deg) saturate(25%) brightness(88%) contrast(115%); }`
            : ''
        }
        ${!hasValidGps ? `#map { filter: blur(4px); }` : ''}
        .kt-marker-wrap {
          position: relative;
          width: 68px;
          height: 68px;
          display: flex;
          align-items: center;
          justify-content: center;
        }
        .kt-marker-halo {
          position: absolute;
          width: 64px;
          height: 64px;
          border-radius: 50%;
          background: ${pinHex}33;
          border: 1.8px solid ${pinHex}B3;
        }
        .kt-marker-core {
          position: relative;
          width: 30px;
          height: 30px;
          border-radius: 50%;
          background: ${pinHex};
          border: 2.5px solid #ffffff;
          display: flex;
          align-items: center;
          justify-content: center;
          color: #ffffff;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          font-weight: 900;
          font-size: 15px;
          line-height: 1;
        }
        .leaflet-control-attribution { display: none !important; }
      </style>
    </head>
    <body class="notranslate" translate="no">
      <div id="map"></div>
      <script>
        const map = L.map('map', {
          center: [${lat}, ${lng}],
          zoom: 14.5,
          zoomControl: false,
          dragging: false,
          touchZoom: false,
          scrollWheelZoom: false,
          doubleClickZoom: false
        });
        L.tileLayer('https://mt{s}.google.com/vt/lyrs=m&hl=es&x={x}&y={y}&z={z}', {
          maxZoom: 20,
          subdomains: ['0', '1', '2', '3']
        }).addTo(map);
        ${
          hasValidGps
            ? `
        const customIcon = L.divIcon({
          className: 'custom-kt-pin',
          html: '<div class="kt-marker-wrap"><div class="kt-marker-halo"></div><div class="kt-marker-core">!</div></div>',
          iconSize: [68, 68],
          iconAnchor: [34, 34]
        });
        L.marker([${lat}, ${lng}], { icon: customIcon }).addTo(map);
        `
            : ''
        }
        setTimeout(() => map.invalidateSize(), 100);
        setTimeout(() => map.invalidateSize(), 350);
        setTimeout(() => map.invalidateSize(), 800);
        window.addEventListener('resize', () => map.invalidateSize());
      </script>
    </body>
    </html>
  `;

  const renderMapWithPendingGlassOverlay = () => (
    <View style={styles.mapLayer} pointerEvents="none">
      {Platform.OS === 'web' ? (
        <iframe
          key={`map_${lat}_${lng}_${hasValidGps ? '1' : '0'}_${isDark ? 'd' : 'l'}_${pinHex}`}
          srcDoc={mapHtml}
          style={styles.iframe}
          title="IncidentMapPreview"
          frameBorder="0"
          scrolling="no"
        />
      ) : (
        <View style={styles.nativeMapPlaceholder}>
          {(() => {
            const { xTile, yTile } = latLngToTile(lat, lng, 15);
            return (
              <View style={{ width: 768, height: 768, flexDirection: 'column', opacity: hasValidGps ? 0.72 : 0.45 }}>
                {[-1, 0, 1].map((dy) => (
                  <View key={dy} style={{ flexDirection: 'row', width: 768, height: 256 }}>
                    {[-1, 0, 1].map((dx) => (
                      <Image
                        key={dx}
                        source={{ uri: `https://mt1.google.com/vt/lyrs=m&hl=es&x=${xTile + dx}&y=${yTile + dy}&z=15` }}
                        style={{ width: 256, height: 256 }}
                        resizeMode="cover"
                        blurRadius={hasValidGps ? 0 : 8}
                      />
                    ))}
                  </View>
                ))}
              </View>
            );
          })()}
        </View>
      )}
    </View>
  );

  // Si es pantalla completa (EmergencyFullscreenOverlay de V2), devuelve solo el lienzo inmersivo 100% edge-to-edge sin bordes ni tarjeta
  if (isFullScreen) {
    return (
      <View style={[StyleSheet.absoluteFillObject, { backgroundColor: isDark ? '#140202' : '#F8FAFC' }]}>
        {renderMapWithPendingGlassOverlay()}
      </View>
    );
  }

  const isPreDespacho = String(dispatch?.estado || '').toLowerCase().trim() === 'pre-despacho';

  const cardBorderColor = hasAssisted
    ? '#10B981'
    : isPreDespacho
      ? '#F59E0B'
      : isDark
        ? '#7F1D1D'
        : 'rgba(239, 68, 68, 0.5)';

  return (
    <View
      style={[
        styles.cardContainer,
        {
          backgroundColor: isDark ? '#140303' : '#FFFFFF',
          borderColor: cardBorderColor
        }
      ]}
    >
      {/* LAYER 0: MAP AS THE IMMERSIVE BACKGROUND CANVAS (Idéntico a DispatchItemCard en TabScreens.kt) */}
      {renderMapWithPendingGlassOverlay()}

      {/* LAYER 1: CONTENT WITH TOP & BOTTOM GRADIENT OVERLAYS (Idéntico a TabScreens.kt líneas 386-821) */}
      <View style={styles.contentColumn}>
        {/* TOP HEADER: Gradient fading downwards into transparency over the map */}
        <LinearGradient
          colors={
            isDark
              ? ['rgba(45, 6, 6, 0.98)', 'rgba(37, 5, 5, 0.94)', 'rgba(30, 4, 4, 0.85)', 'rgba(22, 3, 3, 0.50)', 'transparent']
              : ['rgba(255, 255, 255, 1)', 'rgba(255, 255, 255, 0.96)', 'rgba(255, 255, 255, 0.86)', 'rgba(255, 255, 255, 0.50)', 'transparent']
          }
          style={styles.topGradientBlock}
        >
          <View style={styles.topStatusRow}>
            <Text
              style={[
                styles.statusAlertTitle,
                {
                  color: hasAssisted
                    ? '#10B981'
                    : isPreDespacho
                      ? '#F59E0B'
                      : isDark
                        ? '#EF4444'
                        : '#DC2626'
                }
              ]}
            >
              {hasAssisted
                ? 'SALIENDO A SERVICIO'
                : isPreDespacho
                  ? '🚨 PRE-DESPACHO EN PREPARACIÓN'
                  : '¡DESPACHO ACTIVO!'}
            </Text>
            <Text style={[styles.horaText, { color: isDark ? '#CBD5E1' : '#64748B' }]}>
              {horaStr}
            </Text>
          </View>

          <Text style={[styles.claveText, { color: isDark ? '#FFFFFF' : '#0F172A' }]}>
            {claveText}
          </Text>

          <Text
            style={[styles.direccionText, { color: isDark ? '#E2E8F0' : '#334155' }]}
            numberOfLines={2}
          >
            {cleanLugar
              ? cleanLugar.toUpperCase()
              : isPreDespacho
                ? 'CONFIGURANDO UBICACIÓN Y UNIDADES EN CENTRAL...'
                : 'UBICACIÓN EN PROCESO DE GEORREFERENCIACIÓN'}
          </Text>
        </LinearGradient>

        {/* CENTER VIEWPORT: 180dp transparent clickable area to view and tap the map (Sin etiqueta flotante extra) */}
        <TouchableOpacity
          style={styles.mapCenterViewport}
          activeOpacity={0.9}
          onPress={handleOpenExternalMaps}
        />

        {/* BOTTOM CONTROLS: Gradient fading upwards into transparency over the map */}
        <LinearGradient
          colors={
            isDark
              ? ['transparent', 'rgba(22, 3, 3, 0.50)', 'rgba(26, 4, 4, 0.85)', 'rgba(21, 3, 3, 0.94)', 'rgba(16, 2, 2, 0.98)']
              : ['transparent', 'rgba(255, 255, 255, 0.50)', 'rgba(255, 255, 255, 0.86)', 'rgba(255, 255, 255, 0.96)', 'rgba(255, 255, 255, 1)']
          }
          style={styles.bottomGradientBlock}
        >
          {/* 1. UNIDADES DESPACHADAS (FlowRow de mini-cards igual a TabScreens.kt líneas 513-605) */}
          <View style={styles.unitsFlowRow}>
            {carrosList.length === 0 ? (
              <Text style={[styles.emptyCarrosText, { color: isDark ? '#F87171' : '#DC2626' }]}>
                ---
              </Text>
            ) : carrosList.length === 1 && carrosList[0].toUpperCase() === 'PERSONAL' ? (
              <Text style={[styles.emptyCarrosText, { color: isDark ? '#F87171' : '#DC2626' }]}>
                🧑‍🚒 PERSONAL
              </Text>
            ) : (
              carrosList.map((carroName) => {
                const uData = unidadesMap[carroName] || {};
                const solConductor = String(uData.solicitudConductorAt || '').trim() || isSolicitudActiva(uData.solicitudConductorTimestamp);
                const solPersonal = String(uData.solicitudPersonalAt || '').trim() || isSolicitudActiva(uData.solicitudPersonalTimestamp);
                const emoji = getVehicleEmoji(carroName);

                return (
                  <View
                    key={carroName}
                    style={[
                      styles.unitChipCard,
                      {
                        backgroundColor: isDark ? 'rgba(0, 0, 0, 0.40)' : '#F8FAFC',
                        borderColor: isDark ? 'rgba(255, 255, 255, 0.20)' : '#CBD5E1'
                      }
                    ]}
                  >
                    <Text style={styles.unitEmoji}>{emoji}</Text>
                    <Text style={[styles.unitNameText, { color: isDark ? '#FFFFFF' : '#0F172A' }]}>
                      {carroName}
                    </Text>

                    {solConductor ? (
                      <View style={styles.badge1210}>
                        <Text style={styles.badge1210Text}>12-10</Text>
                      </View>
                    ) : null}

                    {solPersonal ? (
                      <View style={styles.badge66}>
                        <Text style={styles.badge66Text}>6-6</Text>
                      </View>
                    ) : null}
                  </View>
                );
              })
            )}
          </View>

          {/* 2. PRE-INFORME (Debajo de unidades despachadas, igual a TabScreens.kt líneas 608-641) */}
          {hasPreinforme ? (
            <View
              style={[
                styles.preinformeCard,
                {
                  backgroundColor: isDark ? 'rgba(0, 0, 0, 0.30)' : '#F8FAFC',
                  borderColor: isDark ? 'rgba(255, 255, 255, 0.15)' : '#E2E8F0'
                }
              ]}
            >
              <Text style={[styles.preinformeHeader, { color: isDark ? '#94A3B8' : '#64748B' }]}>
                📋 PRE-INFORME:
              </Text>
              <Text style={[styles.preinformeBody, { color: isDark ? '#FFFFFF' : '#0F172A' }]}>
                {rawPreinforme}
              </Text>
            </View>
          ) : null}

          {/* 3. CONTROL DE EMERGENCIA (6-7) */}
          {hora67 ? (
            <View style={styles.control67Banner}>
              <Text style={[styles.control67Text, { color: isDark ? '#34D399' : '#047857' }]}>
                ✓ CONTROL DE EMERGENCIA (6-7): {hora67}
              </Text>
            </View>
          ) : null}

          {/* 4. BOTONES DE ACCIÓN CON ANIMACIÓN EXACTA KOTLIN (AnimatedVisibility 300ms/200ms + Spring) */}
          {shouldRenderButtons ? (
            <Animated.View
              style={[
                styles.actionButtonsWrapper,
                {
                  opacity: visibilityAnim,
                  maxHeight: visibilityAnim.interpolate({
                    inputRange: [0, 1],
                    outputRange: [0, 68]
                  }),
                  marginTop: visibilityAnim.interpolate({
                    inputRange: [0, 1],
                    outputRange: [0, 12]
                  }),
                  transform: [
                    {
                      translateY: visibilityAnim.interpolate({
                        inputRange: [0, 1],
                        outputRange: [22, 0]
                      })
                    },
                    {
                      scale: actionSwitchAnim
                    }
                  ]
                }
              ]}
            >
              {hasAssisted ? (
                <TouchableOpacity
                  style={styles.btnCancelarAsistencia}
                  onPress={typeof onCancelarAsistencia === 'function' ? onCancelarAsistencia : onAsistir}
                  activeOpacity={0.85}
                >
                  <Text style={styles.btnCancelarAsistenciaText}>CANCELAR ASISTENCIA</Text>
                </TouchableOpacity>
              ) : (
                <View style={styles.buttonsRow}>
                  {/* ASISTIR (Crimson / Ruby Red #881A1A Filled Pill Button — TabScreens.kt línea 720) */}
                  <TouchableOpacity
                    style={styles.btnAsistirKotlin}
                    onPress={onAsistir}
                    activeOpacity={0.85}
                  >
                    <Check size={16} color="#FFFFFF" strokeWidth={3} style={{ marginRight: 6 }} />
                    <Text style={styles.btnAsistirText}>ASISTIR</Text>
                  </TouchableOpacity>

                  {/* NO ASISTIR: Se oculta al presionarlo para feedback visual inmediato */}
                  {!hasDeclined ? (
                    <TouchableOpacity
                      style={styles.btnNoAsistirKotlin}
                      onPress={onNoAsistir}
                      activeOpacity={0.85}
                    >
                      <X
                        size={16}
                        color="#DC2626"
                        strokeWidth={3}
                        style={{ marginRight: 6 }}
                      />
                      <Text
                        style={[
                          styles.btnNoAsistirText,
                          { color: '#DC2626' }
                        ]}
                      >
                        NO ASISTIR
                      </Text>
                    </TouchableOpacity>
                  ) : null}
                </View>
              )}
            </Animated.View>
          ) : null}
        </LinearGradient>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  cardContainer: {
    width: '100%',
    borderRadius: 24,
    overflow: 'hidden',
    position: 'relative',
    borderWidth: 1.2,
    marginBottom: 10
  },
  mapLayer: {
    ...StyleSheet.absoluteFillObject,
    width: '100%',
    height: '100%'
  },
  iframe: {
    width: '100%',
    height: '100%',
    border: 'none',
    pointerEvents: 'none'
  },
  nativeMapPlaceholder: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: '#140303',
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden'
  },
  contentColumn: {
    width: '100%',
    justifyContent: 'space-between',
    zIndex: 10
  },
  topGradientBlock: {
    paddingHorizontal: 16,
    paddingTop: 14,
    paddingBottom: 14
  },
  topStatusRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 4
  },
  statusAlertTitle: {
    fontSize: 12,
    fontWeight: '900',
    letterSpacing: 0.5
  },
  horaText: {
    fontSize: 12,
    fontWeight: '700'
  },
  claveText: {
    fontSize: 34,
    fontWeight: '900',
    lineHeight: 38
  },
  direccionText: {
    fontSize: 13,
    fontWeight: '600',
    lineHeight: 17,
    marginTop: 2
  },
  mapCenterViewport: {
    height: 180,
    width: '100%'
  },
  bottomGradientBlock: {
    paddingHorizontal: 16,
    paddingTop: 14,
    paddingBottom: 14
  },
  unitsFlowRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    alignItems: 'center'
  },
  unitChipCard: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderRadius: 10,
    paddingHorizontal: 10,
    paddingVertical: 6,
    gap: 6
  },
  unitEmoji: {
    fontSize: 15
  },
  unitNameText: {
    fontSize: 13,
    fontWeight: '900'
  },
  badge1210: {
    backgroundColor: 'rgba(245, 158, 11, 0.25)',
    borderWidth: 1,
    borderColor: 'rgba(245, 158, 11, 0.70)',
    borderRadius: 6,
    paddingHorizontal: 5,
    paddingVertical: 2
  },
  badge1210Text: {
    fontSize: 9,
    fontWeight: '900',
    color: '#FBBF24'
  },
  badge66: {
    backgroundColor: 'rgba(59, 130, 246, 0.25)',
    borderWidth: 1,
    borderColor: 'rgba(59, 130, 246, 0.70)',
    borderRadius: 6,
    paddingHorizontal: 5,
    paddingVertical: 2
  },
  badge66Text: {
    fontSize: 9,
    fontWeight: '900',
    color: '#60A5FA'
  },
  emptyCarrosText: {
    fontSize: 13,
    fontWeight: '900'
  },
  preinformeCard: {
    marginTop: 10,
    borderWidth: 1,
    borderRadius: 14,
    padding: 10
  },
  preinformeHeader: {
    fontSize: 10,
    fontWeight: '700'
  },
  preinformeBody: {
    fontSize: 12,
    fontWeight: '700',
    marginTop: 2
  },
  control67Banner: {
    marginTop: 10,
    backgroundColor: 'rgba(16, 185, 129, 0.10)',
    borderWidth: 1,
    borderColor: 'rgba(16, 185, 129, 0.30)',
    borderRadius: 8,
    padding: 8,
    alignItems: 'center'
  },
  control67Text: {
    fontSize: 11,
    fontWeight: '900'
  },
  actionButtonsWrapper: {
    width: '100%',
    overflow: 'hidden'
  },
  buttonsRow: {
    flexDirection: 'row',
    gap: 10,
    width: '100%'
  },
  btnAsistirKotlin: {
    flex: 1,
    height: 48,
    backgroundColor: '#881A1A',
    borderRadius: 20,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center'
  },
  btnAsistirText: {
    fontSize: 13,
    fontWeight: '900',
    color: '#FFFFFF'
  },
  btnNoAsistirKotlin: {
    flex: 1,
    height: 48,
    backgroundColor: 'rgba(0, 0, 0, 0.10)',
    borderRadius: 20,
    borderWidth: 1.2,
    borderColor: 'rgba(239, 68, 68, 0.50)',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center'
  },
  btnNoAsistirDeclinedKotlin: {
    backgroundColor: '#EF4444',
    borderColor: '#EF4444'
  },
  btnNoAsistirText: {
    fontSize: 13,
    fontWeight: '900'
  },
  btnCancelarAsistencia: {
    width: '100%',
    height: 48,
    backgroundColor: 'rgba(239, 68, 68, 0.15)',
    borderRadius: 20,
    borderWidth: 1,
    borderColor: 'rgba(239, 68, 68, 0.50)',
    alignItems: 'center',
    justifyContent: 'center'
  },
  btnCancelarAsistenciaText: {
    fontSize: 13,
    fontWeight: '900',
    color: '#EF4444'
  },
  consoleOperatorBadge: {
    marginTop: 10,
    borderWidth: 1,
    borderRadius: 14,
    paddingHorizontal: 12,
    paddingVertical: 9,
    alignItems: 'center',
    justifyContent: 'center'
  },
  consoleOperatorText: {
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.5
  }
});
