import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Modal, Platform } from 'react-native';
import { ref, onValue } from 'firebase/database';
import {
  Truck,
  Users,
  Satellite,
  Maximize2,
  Minimize2,
  LocateFixed,
  Plus,
  Minus,
  Navigation,
  X
} from 'lucide-react-native';
import { getRtdb, ensureFirebaseAuth } from '../../config/firebase';
import {
  resolveCompanyFromVehicleId,
  resolveCoreUnitStatusInfo,
  formatShortPersonName
} from '../../shared/sentinelCore';

function buildCommanderNavMapHtml() {
  return `<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
  <style>
    * {
      margin: 0;
      padding: 0;
      box-sizing: border-box;
      -webkit-user-select: none !important;
      user-select: none !important;
      -webkit-touch-callout: none !important;
      -webkit-tap-highlight-color: transparent !important;
    }
    html, body, #map {
      width: 100%;
      height: 100%;
      background: #060911;
      overflow: hidden;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter, sans-serif;
    }
    .leaflet-control-attribution, .leaflet-control-zoom {
      display: none !important;
    }
    /* Filtro modo oscuro táctico idéntico a CarPlayTacticalMap.jsx de SENTINEL NAV */
    body.nav-dark-streets .leaflet-tile-pane {
      filter: invert(100%) hue-rotate(180deg) brightness(96%) contrast(94%) saturate(88%);
    }
    @keyframes ping {
      75%, 100% {
        transform: scale(2.05);
        opacity: 0;
      }
    }
    .leaflet-popup-content-wrapper {
      background: rgba(9, 13, 22, 0.96) !important;
      color: #F8FAFC !important;
      border: 1px solid rgba(148, 163, 184, 0.30) !important;
      border-radius: 12px !important;
      box-shadow: 0 8px 22px rgba(0, 0, 0, 0.85) !important;
    }
    .leaflet-popup-tip {
      background: rgba(9, 13, 22, 0.96) !important;
    }
    .leaflet-popup-content {
      margin: 8px 11px !important;
      font-size: 11px !important;
      line-height: 1.35 !important;
    }
  </style>
</head>
<body class="nav-dark-streets">
  <div id="map"></div>
  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <script>
    (function() {
      document.addEventListener('contextmenu', function(e) { e.preventDefault(); }, { passive: false });

      var map = L.map('map', {
        zoomControl: false,
        attributionControl: false,
        preferCanvas: true,
        tap: false
      }).setView([-34.6384, -71.1274], 15);

      var tileLayer = null;
      var currentTileMode = '';

      function applyTileLayer(layerType, isDark) {
        var modeKey = layerType + '_' + (isDark ? 'dark' : 'light');
        if (layerType === 'satellite') {
          document.body.classList.remove('nav-dark-streets');
        } else if (isDark) {
          document.body.classList.add('nav-dark-streets');
        } else {
          document.body.classList.remove('nav-dark-streets');
        }
        if (currentTileMode === modeKey && tileLayer) return;
        currentTileMode = modeKey;
        if (tileLayer) {
          try { map.removeLayer(tileLayer); } catch (e) {}
        }
        var url = layerType === 'satellite'
          ? 'https://mt{s}.google.com/vt/lyrs=y&hl=es&x={x}&y={y}&z={z}'
          : 'https://mt{s}.google.com/vt/lyrs=m&hl=es&x={x}&y={y}&z={z}';
        tileLayer = L.tileLayer(url, {
          maxZoom: 21,
          subdomains: ['0', '1', '2', '3'],
          keepBuffer: 6,
          updateWhenIdle: false
        }).addTo(map);
      }

      applyTileLayer('streets', true);

      function esc(str) {
        return String(str == null ? '' : str)
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;');
      }

      // Ícono de Cuartel de Bomberos (idéntico a createFireStationFlaticonMarker en SENTINEL NAV)
      function makeStationIcon(label) {
        var safeLabel = esc(label || 'CUARTEL');
        return L.divIcon({
          className: 'sentinel-nav-station-pin',
          html:
            '<div style="position:relative;width:48px;height:54px;display:flex;flex-direction:column;align-items:center;justify-content:center;">' +
              '<div style="position:relative;z-index:2;width:36px;height:36px;border-radius:11px;background:#ffffff;border:2.2px solid #b91c1c;box-shadow:0 4px 12px rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;overflow:hidden;">' +
                '<svg width="28" height="28" viewBox="0 0 64 64" fill="none">' +
                  '<path d="M6 24L32 8L58 24H6Z" fill="#991B1B" stroke="#450A0A" stroke-width="2" stroke-linejoin="round"/>' +
                  '<rect x="10" y="24" width="44" height="34" rx="2" fill="#DC2626" stroke="#450A0A" stroke-width="2"/>' +
                  '<circle cx="32" cy="19" r="5.5" fill="#FEF08A" stroke="#78350F" stroke-width="1.5"/>' +
                  '<path d="M32 15C33.8 17 35 18.4 35 20.2C35 21.8 33.7 23 32 23C30.3 23 29 21.8 29 20.2C29 18.4 30.2 17 32 15Z" fill="#EF4444"/>' +
                  '<rect x="18" y="34" width="28" height="24" rx="2" fill="#E2E8F0" stroke="#1E293B" stroke-width="2"/>' +
                  '<line x1="18" y1="40" x2="46" y2="40" stroke="#94A3B8" stroke-width="1.8"/>' +
                  '<line x1="18" y1="46" x2="46" y2="46" stroke="#94A3B8" stroke-width="1.8"/>' +
                  '<line x1="18" y1="52" x2="46" y2="52" stroke="#94A3B8" stroke-width="1.8"/>' +
                  '<rect x="29" y="4" width="6" height="4" rx="2" fill="#38BDF8" stroke="#0369A1" stroke-width="1.2"/>' +
                '</svg>' +
              '</div>' +
              '<span style="position:relative;z-index:3;margin-top:-2px;background:#1e293b;color:#ffffff;border:1.3px solid #ef4444;font-size:8px;font-weight:900;padding:1px 5px;border-radius:4px;white-space:nowrap;box-shadow:0 2px 5px rgba(0,0,0,0.65);">' +
                safeLabel +
              '</span>' +
            '</div>',
          iconSize: [48, 54],
          iconAnchor: [24, 27]
        });
      }

      // Ícono de Emergencia por Clave Radial (idéntico a createEmergencyTargetCrossIcon de SENTINEL NAV)
      function getClaveMeta(clave) {
        var c = String(clave || '10-0').trim().toUpperCase();
        if (c.indexOf('10-0') === 0 || c.indexOf('10-10') === 0 || c === '10-30') {
          return {
            color: '#dc2626',
            svg: '<svg width="22" height="22" viewBox="0 0 64 64" fill="none"><path d="M32 6C32 6 52 22 52 39C52 50.0457 43.0457 58 32 58C20.9543 58 12 50.0457 12 39C12 29 20 18 24 14C24 20 28 22 28 22C26 14 32 6 32 6Z" fill="#EF4444" stroke="#991B1B" stroke-width="2.5"/><path d="M32 26C32 26 43 35 43 43C43 49.0751 38.0751 54 32 54C25.9249 54 21 49.0751 21 43C21 35 32 26 32 26Z" fill="#F97316"/><path d="M32 38C32 38 37 42 37 46C37 48.7614 34.7614 51 32 51C29.2386 51 27 48.7614 27 46C27 42 32 38 32 38Z" fill="#FEF08A"/></svg>'
          };
        }
        if (c.indexOf('10-1') === 0 && c.indexOf('10-10') !== 0 && c.indexOf('10-11') !== 0 && c.indexOf('10-12') !== 0 && c.indexOf('10-15') !== 0) {
          return {
            color: '#ea580c',
            svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M12 2C12 2 16 5.2 16 8.2C16 10.3 14.2 12 12 12C9.8 12 8 10.3 8 8.2C8 5.2 12 2 12 2Z" fill="#ef4444"/><path d="M5 13L6.5 9.5H17.5L19 13M4 13H20V18H4V13Z" fill="#ea580c" stroke="#9a3412" stroke-width="1.6"/><circle cx="7.5" cy="18" r="2" fill="#1e293b" stroke="#fff" stroke-width="1.2"/><circle cx="16.5" cy="18" r="2" fill="#1e293b" stroke="#fff" stroke-width="1.2"/></svg>'
          };
        }
        if (c.indexOf('10-2') === 0 || c.indexOf('FORESTAL') !== -1) {
          return {
            color: '#16a34a',
            svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M12 3L6 11H10L5 18H19L14 11H18L12 3Z" fill="#16a34a" stroke="#14532d" stroke-width="1.5"/><rect x="10.5" y="18" width="3" height="4" rx="0.8" fill="#78350f"/><path d="M17.5 5C17.5 5 20.5 7.5 20.5 9.8C20.5 11.4 19.2 12.6 17.5 12.6C15.8 12.6 14.5 11.4 14.5 9.8C14.5 7.5 17.5 5 17.5 5Z" fill="#ef4444"/></svg>'
          };
        }
        if (c.indexOf('10-3') === 0 && c !== '10-30') {
          return {
            color: '#0284c7',
            svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="9" fill="#e0f2fe" stroke="#0284c7" stroke-width="2.2"/><circle cx="12" cy="12" r="4" fill="#fff" stroke="#ef4444" stroke-width="2"/><path d="M12 3V8M12 16V21M3 12H8M16 12H21" stroke="#ef4444" stroke-width="2.4"/></svg>'
          };
        }
        if (c.indexOf('10-4') === 0) {
          return {
            color: '#db2777',
            svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M3 14L5 9.5H14L16 14H3Z" fill="#db2777" stroke="#831843" stroke-width="1.5"/><rect x="2.5" y="13.5" width="14" height="4.5" rx="1.2" fill="#ec4899" stroke="#831843" stroke-width="1.5"/><circle cx="6" cy="18.5" r="2" fill="#1e293b" stroke="#fff" stroke-width="1.2"/><circle cx="13" cy="18.5" r="2" fill="#1e293b" stroke="#fff" stroke-width="1.2"/></svg>'
          };
        }
        if (c.indexOf('10-5') === 0) {
          return {
            color: '#7c3aed',
            svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M12 2L22 12L12 22L2 12L12 2Z" fill="#fde047" stroke="#7c3aed" stroke-width="2"/><path d="M12 6L17 12H7L12 6Z" fill="#dc2626"/><circle cx="12" cy="15.5" r="2" fill="#1e293b"/></svg>'
          };
        }
        if (c.indexOf('10-6') === 0) {
          return {
            color: '#0d9488',
            svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M6.5 17H17.5C19.43 17 21 15.43 21 13.5C21 11.74 19.7 10.29 18.01 10.04C17.42 6.62 14.45 4 10.88 4C6.88 4 3.63 7.25 3.63 11.25C3.63 11.54 3.65 11.83 3.69 12.11C2.69 12.69 2 13.76 2 15C2 16.66 3.34 18 5 18" fill="#14b8a6" stroke="#115e59" stroke-width="1.6"/></svg>'
          };
        }
        if (c.indexOf('10-7') === 0) {
          return {
            color: '#ca8a04',
            svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M13 2L4 14H11L10 22L20 10H13L13 2Z" fill="#facc15" stroke="#854d0e" stroke-width="1.8"/></svg>'
          };
        }
        return {
          color: '#dc2626',
          svg: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none"><path d="M12 3L2 20H22L12 3Z" fill="#ef4444" stroke="#7f1d1d" stroke-width="1.8"/><path d="M12 9V14" stroke="#fff" stroke-width="2.4" stroke-linecap="round"/><circle cx="12" cy="17.2" r="1.3" fill="#fff"/></svg>'
        };
      }

      function makeIncidentIcon(clave) {
        var meta = getClaveMeta(clave);
        var safeBadge = esc(clave || '10-0');
        return L.divIcon({
          className: 'sentinel-nav-incident-pin',
          html:
            '<div style="position:relative;width:48px;height:60px;display:flex;flex-direction:column;align-items:center;justify-content:flex-end;">' +
              '<span style="position:relative;z-index:25;margin-bottom:-2px;background:' + meta.color + ';color:#ffffff;border:1.5px solid #ffffff;font-size:9px;font-weight:900;padding:1px 6px;border-radius:5px;white-space:nowrap;box-shadow:0 2px 6px rgba(0,0,0,0.65);">' +
                safeBadge +
              '</span>' +
              '<div style="position:relative;width:42px;height:46px;display:flex;align-items:flex-start;justify-content:center;">' +
                '<div style="position:absolute;bottom:1px;width:13px;height:4px;border-radius:9999px;background:rgba(0,0,0,0.45);"></div>' +
                '<span style="position:absolute;top:1px;width:34px;height:34px;background:' + meta.color + '55;border-radius:9999px;animation:ping 1.6s cubic-bezier(0,0,0.2,1) infinite;"></span>' +
                '<svg width="40" height="46" viewBox="0 0 44 50" fill="none" style="position:absolute;inset:0;filter:drop-shadow(0 4px 8px rgba(0,0,0,0.55));">' +
                  '<path d="M22 2C12.0589 2 4 10.0589 4 20C4 28.5 11.2 35.8 18.2 42.8L22 47.5L25.8 42.8C32.8 35.8 40 28.5 40 20C40 10.0589 31.9411 2 22 2Z" fill="' + meta.color + '" stroke="#ffffff" stroke-width="2.5" stroke-linejoin="round"/>' +
                  '<circle cx="22" cy="19.5" r="13" fill="#ffffff"/>' +
                '</svg>' +
                '<div style="position:relative;z-index:15;margin-top:6.5px;width:22px;height:22px;display:flex;align-items:center;justify-content:center;">' +
                  meta.svg +
                '</div>' +
              '</div>' +
            '</div>',
          iconSize: [48, 60],
          iconAnchor: [24, 57],
          popupAnchor: [0, -52]
        });
      }

      // Ícono de Unidad / Carro Bomba (idéntico a createOtherVehicleTacticalIcon en SENTINEL NAV)
      function makeVehicleIcon(v) {
        var stUpper = String(v.statusShort || '0-9').trim().toUpperCase();
        var isOut = stUpper.indexOf('0-8') !== -1;
        var isEnLugar = stUpper.indexOf('6-3') !== -1;
        var isRetorno = stUpper.indexOf('6-9') !== -1;
        var isInService = Boolean(v.isInService);

        var bgGradient = isOut
          ? 'linear-gradient(135deg, #475569, #334155)'
          : isEnLugar
            ? 'linear-gradient(135deg, #d97706, #b45309)'
            : isRetorno
              ? 'linear-gradient(135deg, #2563eb, #1d4ed8)'
              : isInService
                ? 'linear-gradient(135deg, #ef4444, #b91c1c)'
                : 'linear-gradient(135deg, #10b981, #047857)';

        var borderColor = isOut
          ? '#94a3b8'
          : isEnLugar
            ? '#fde047'
            : isRetorno
              ? '#93c5fd'
              : isInService
                ? '#fca5a5'
                : '#6ee7b7';

        var rot = Number(v.heading) && !isNaN(Number(v.heading)) ? Math.round(Number(v.heading)) : 0;
        var spd = Math.max(0, Math.round(Number(v.speedKmh) || 0));
        var label = esc(v.idCarro || 'B-1');
        var subText = esc(stUpper + (spd >= 2 ? ' • ' + spd + ' km/h' : ''));

        return L.divIcon({
          className: 'sentinel-nav-other-vehicle-marker',
          html:
            '<div style="position:relative;width:78px;height:52px;display:flex;flex-direction:column;align-items:center;justify-content:center;cursor:pointer;">' +
              '<div style="display:flex;align-items:center;gap:3px;padding:3px 7px;border-radius:9px;background:' + bgGradient + ';border:2px solid ' + borderColor + ';box-shadow:0 4px 14px rgba(0,0,0,0.75);color:#ffffff;">' +
                '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#ffffff" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="transform:rotate(' + rot + 'deg);flex-shrink:0;">' +
                  '<polygon points="12 2 19 21 12 17 5 21 12 2"></polygon>' +
                '</svg>' +
                '<span style="font-weight:900;font-size:11px;letter-spacing:0.03em;white-space:nowrap;">' + label + '</span>' +
              '</div>' +
              '<div style="margin-top:2px;padding:1px 6px;border-radius:4px;background:rgba(15,23,42,0.96);border:1.5px solid ' + borderColor + ';color:#f8fafc;font-weight:900;font-size:8px;letter-spacing:0.02em;white-space:nowrap;box-shadow:0 2px 6px rgba(0,0,0,0.65);">' +
                subText +
              '</div>' +
            '</div>',
          iconSize: [78, 52],
          iconAnchor: [39, 26],
          popupAnchor: [0, -22]
        });
      }

      // Ícono de Bombero en Servicio (idéntico a createFirefighterTacticalIcon en SENTINEL NAV)
      function makeFirefighterIcon(b) {
        var radial = esc(b.idRadial || '--');
        return L.divIcon({
          className: 'personnel-gps-marker',
          html:
            '<div style="background:linear-gradient(135deg,#10b981,#059669);width:30px;height:30px;border-radius:50%;border:2.5px solid #ffffff;box-shadow:0 0 14px rgba(16,185,129,0.95),0 3px 6px rgba(0,0,0,0.65);display:flex;align-items:center;justify-content:center;color:#ffffff;font-weight:900;font-size:10.5px;letter-spacing:-0.4px;cursor:pointer;">' +
              radial +
            '</div>',
          iconSize: [30, 30],
          iconAnchor: [15, 15],
          popupAnchor: [0, -16]
        });
      }

      // Ícono de Alertante GPS (SENTINEL LINK)
      function makeAlertanteIcon() {
        return L.divIcon({
          className: 'custom-alertante-blue-pin',
          html:
            '<div style="position:relative;width:32px;height:32px;display:flex;align-items:center;justify-content:center;">' +
              '<span style="position:absolute;width:32px;height:32px;background:rgba(2,132,199,0.45);border-radius:9999px;animation:ping 2s cubic-bezier(0,0,0.2,1) infinite;"></span>' +
              '<div style="width:26px;height:26px;background:#0284c7;border:2.2px solid #ffffff;border-radius:9999px;display:flex;align-items:center;justify-content:center;box-shadow:0 0 14px rgba(2,132,199,0.95);z-index:20;">' +
                '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#ffffff" stroke-width="2.5"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>' +
              '</div>' +
            '</div>',
          iconSize: [32, 32],
          iconAnchor: [16, 16],
          popupAnchor: [0, -16]
        });
      }

      var stationMarkers = {};
      var incidentMarkers = {};
      var alertanteMarkers = {};
      var vehicleMarkers = {};
      var vehicleSigMap = {};
      var bomberoMarkers = {};
      var bomberoSigMap = {};
      var hasAutoFittedOnce = false;
      var lastActiveIncidentSig = '';

      function syncMapCollection(existingMap, sigMap, items, makeIconFn, makePopupFn, zOffset) {
        var nextKeys = {};
        for (var i = 0; i < items.length; i++) {
          var item = items[i];
          if (!item || typeof item.lat !== 'number' || typeof item.lng !== 'number') continue;
          var key = String(item.key);
          nextKeys[key] = true;
          var sig = String(item.sig || key);
          var popupHtml = makePopupFn(item);

          if (existingMap[key]) {
            existingMap[key].setLatLng([item.lat, item.lng]);
            if (!sigMap || sigMap[key] !== sig) {
              existingMap[key].setIcon(makeIconFn(item));
              existingMap[key].setPopupContent(popupHtml);
              if (sigMap) sigMap[key] = sig;
            }
          } else {
            var m = L.marker([item.lat, item.lng], {
              icon: makeIconFn(item),
              zIndexOffset: zOffset || 0
            }).bindPopup(popupHtml).addTo(map);
            existingMap[key] = m;
            if (sigMap) sigMap[key] = sig;
          }
        }
        Object.keys(existingMap).forEach(function(k) {
          if (!nextKeys[k]) {
            try { map.removeLayer(existingMap[k]); } catch (e) {}
            delete existingMap[k];
            if (sigMap) delete sigMap[k];
          }
        });
      }

      function fitOperationalBounds(state, forceFit) {
        var pts = [];
        (state.incidents || []).forEach(function(inc) {
          if (typeof inc.lat === 'number' && typeof inc.lng === 'number') pts.push([inc.lat, inc.lng]);
        });
        (state.vehicles || []).forEach(function(v) {
          if (v.isInService && typeof v.lat === 'number' && typeof v.lng === 'number') pts.push([v.lat, v.lng]);
        });
        (state.bomberos || []).forEach(function(b) {
          if (typeof b.lat === 'number' && typeof b.lng === 'number') pts.push([b.lat, b.lng]);
        });
        if (pts.length === 0) {
          (state.vehicles || []).forEach(function(v) {
            if (typeof v.lat === 'number' && typeof v.lng === 'number') pts.push([v.lat, v.lng]);
          });
          (state.stations || []).forEach(function(st) {
            if (typeof st.lat === 'number' && typeof st.lng === 'number') pts.push([st.lat, st.lng]);
          });
        }
        if (pts.length === 1) {
          map.setView(pts[0], forceFit ? 16 : 15, { animate: Boolean(forceFit) });
        } else if (pts.length > 1) {
          map.fitBounds(L.latLngBounds(pts), { padding: [36, 36], maxZoom: 16, animate: Boolean(forceFit) });
        }
      }

      var lastState = null;

      function handleSync(payload) {
        if (!payload) return;
        lastState = payload;
        applyTileLayer(payload.mapLayer || 'streets', payload.isDark !== false);

        syncMapCollection(
          stationMarkers,
          null,
          payload.stations || [],
          function(s) { return makeStationIcon(s.label); },
          function(s) {
            return '<div style="font-weight:900;color:#FCA5A5;">🏠 ' + esc(s.label) + '</div>' +
              (s.sub ? '<div style="color:#CBD5E1;font-size:10px;margin-top:2px;">' + esc(s.sub) + '</div>' : '');
          },
          200
        );

        syncMapCollection(
          incidentMarkers,
          null,
          payload.incidents || [],
          function(inc) { return makeIncidentIcon(inc.clave); },
          function(inc) {
            return '<div style="font-weight:900;color:#F87171;">🚨 ' + esc(inc.clave) + '</div>' +
              '<div style="color:#F8FAFC;font-weight:700;margin-top:2px;">' + esc(inc.direccion) + '</div>' +
              (inc.unidades ? '<div style="color:#93C5FD;font-size:10px;margin-top:2px;">🚒 ' + esc(inc.unidades) + '</div>' : '');
          },
          900
        );

        syncMapCollection(
          alertanteMarkers,
          null,
          payload.alertantes || [],
          function() { return makeAlertanteIcon(); },
          function(al) {
            return '<div style="font-weight:900;color:#38BDF8;">📍 GPS ALERTANTE</div>' +
              (al.accuracy ? '<div style="color:#CBD5E1;font-size:10px;margin-top:2px;">Precisión ±' + Math.round(al.accuracy) + 'm</div>' : '');
          },
          850
        );

        syncMapCollection(
          vehicleMarkers,
          vehicleSigMap,
          payload.showUnits !== false ? (payload.vehicles || []) : [],
          function(v) { return makeVehicleIcon(v); },
          function(v) {
            var gpsTxt = v.hasLiveGps
              ? ('GPS EN VIVO' + (v.speedKmh >= 2 ? ' • ' + Math.round(v.speedKmh) + ' km/h' : ''))
              : 'Posición en Cuartel';
            return '<div style="font-weight:900;color:#93C5FD;font-size:12px;">🚒 UNIDAD ' + esc(v.idCarro) + '</div>' +
              '<div style="color:#34D399;font-weight:800;margin-top:2px;">Estado: ' + esc(v.statusShort) + '</div>' +
              '<div style="color:#94A3B8;font-size:10px;margin-top:2px;">' + esc(gpsTxt) + '</div>';
          },
          700
        );

        syncMapCollection(
          bomberoMarkers,
          bomberoSigMap,
          payload.showBomberos !== false ? (payload.bomberos || []) : [],
          function(b) { return makeFirefighterIcon(b); },
          function(b) {
            var spdTxt = b.speedKmh >= 2 ? (' • ' + Math.round(b.speedKmh) + ' km/h') : '';
            return '<div style="font-weight:900;color:#34D399;font-size:12px;">👨‍🚒 #' + esc(b.idRadial) + ' — ' + esc(b.nombre) + '</div>' +
              '<div style="color:#CBD5E1;font-weight:700;font-size:10px;margin-top:2px;">' + esc(b.companiaLabel) + ' • En servicio' + esc(spdTxt) + '</div>';
          },
          800
        );

        var incSig = (payload.incidents || []).map(function(i) { return i.key + ':' + i.lat.toFixed(4) + ',' + i.lng.toFixed(4); }).join('|');
        if (!hasAutoFittedOnce || (incSig && incSig !== lastActiveIncidentSig)) {
          hasAutoFittedOnce = true;
          lastActiveIncidentSig = incSig;
          setTimeout(function() {
            map.invalidateSize();
            fitOperationalBounds(payload, false);
          }, 80);
        }
      }

      window.addEventListener('message', function(ev) {
        try {
          var data = typeof ev.data === 'string' ? JSON.parse(ev.data) : ev.data;
          if (!data || typeof data !== 'object') return;
          if (data.type === 'SENTINEL_CMD_MAP_SYNC') {
            handleSync(data.payload);
          } else if (data.type === 'SENTINEL_CMD_MAP_ACTION') {
            map.invalidateSize();
            if (data.action === 'ZOOM_IN') {
              map.zoomIn();
            } else if (data.action === 'ZOOM_OUT') {
              map.zoomOut();
            } else if (data.action === 'RECENTER' && lastState) {
              fitOperationalBounds(lastState, true);
            } else if (data.action === 'FOCUS_UNIT' && data.unitId) {
              var m = vehicleMarkers[String(data.unitId)];
              if (m) {
                map.setView(m.getLatLng(), Math.max(map.getZoom(), 16), { animate: true });
                m.openPopup();
              }
            } else if (data.action === 'INVALIDATE') {
              setTimeout(function() { map.invalidateSize(); }, 90);
            }
          }
        } catch (e) {}
      });

      try {
        window.parent.postMessage(JSON.stringify({ type: 'SENTINEL_CMD_MAP_READY' }), '*');
      } catch (e) {}
    })();
  </script>
</body>
</html>`;
}

export const CommanderTacticalNavMap = ({
  fleetList = [],
  activeDispatches = [],
  firefightersList = [],
  cuartelLat = null,
  cuartelLng = null,
  cuarteles = null,
  institutionCompaniesList = [],
  isDarkMode = true,
  focusedUnitRequest = null
}) => {
  const [rtdbVehicles, setRtdbVehicles] = useState({});
  const [rtdbBomberos, setRtdbBomberos] = useState({});
  const [rtdbAlertantes, setRtdbAlertantes] = useState({});
  const [mapLayer, setMapLayer] = useState('streets'); // 'streets' | 'satellite'
  const [showUnits, setShowUnits] = useState(true);
  const [showBomberos, setShowBomberos] = useState(true);
  const [onlyServiceUnits, setOnlyServiceUnits] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);

  const inlineIframeRef = useRef(null);
  const modalIframeRef = useRef(null);
  const mapHtml = useMemo(() => buildCommanderNavMapHtml(), []);

  // Suscripción en tiempo real a RTDB (`telemetria/vehiculos`, `telemetria/bomberos`, `telemetria/alertantes`)
  // idéntica a useTelemetryStore.js de SENTINEL CAD y useNavStore.js de SENTINEL NAV.
  useEffect(() => {
    let cancelled = false;
    let unsubVeh = null;
    let unsubBom = null;
    let unsubAle = null;

    const connectRtdb = async () => {
      try {
        await ensureFirebaseAuth().catch(() => {});
        if (cancelled) return;
        const rtdb = getRtdb();
        if (!rtdb) return;

        unsubVeh = onValue(
          ref(rtdb, 'telemetria/vehiculos'),
          (snap) => {
            if (!cancelled) setRtdbVehicles(snap.val() || {});
          },
          () => {}
        );

        unsubBom = onValue(
          ref(rtdb, 'telemetria/bomberos'),
          (snap) => {
            if (!cancelled) setRtdbBomberos(snap.val() || {});
          },
          () => {}
        );

        unsubAle = onValue(
          ref(rtdb, 'telemetria/alertantes'),
          (snap) => {
            if (!cancelled) setRtdbAlertantes(snap.val() || {});
          },
          () => {}
        );
      } catch (_) {}
    };

    connectRtdb();

    return () => {
      cancelled = true;
      try { if (typeof unsubVeh === 'function') unsubVeh(); } catch (_) {}
      try { if (typeof unsubBom === 'function') unsubBom(); } catch (_) {}
      try { if (typeof unsubAle === 'function') unsubAle(); } catch (_) {}
    };
  }, []);

  // Coordenadas base de cuarteles institucionales (1 compañía o multi-compañía)
  const stationsList = useMemo(() => {
    const out = [];
    const baseLat = Number(cuartelLat);
    const baseLng = Number(cuartelLng);
    const hasBase =
      !isNaN(baseLat) && !isNaN(baseLng) && Math.abs(baseLat) > 0.1 && Math.abs(baseLng) > 0.1;

    if (cuarteles && typeof cuarteles === 'object') {
      const entries = Array.isArray(cuarteles)
        ? cuarteles.map((c, idx) => [String(c?.compania || c?.id || idx + 1), c])
        : Object.entries(cuarteles);

      for (const [k, c] of entries) {
        const lat = Number(c?.lat ?? c?.latitud ?? c?.coordenadas?.lat);
        const lng = Number(c?.lng ?? c?.longitud ?? c?.coordenadas?.lng);
        if (!isNaN(lat) && !isNaN(lng) && Math.abs(lat) > 0.1 && Math.abs(lng) > 0.1) {
          const compNum = String(k).replace(/\D/g, '') || '1';
          out.push({
            key: `st_${compNum}`,
            compId: compNum,
            lat,
            lng,
            label: String(c?.nombre || c?.alias || `CUARTEL ${compNum}ª CÍA`).toUpperCase(),
            sub: String(c?.direccion || '')
          });
        }
      }
    }

    if (out.length === 0 && hasBase) {
      out.push({
        key: 'st_main',
        compId: '1',
        lat: baseLat,
        lng: baseLng,
        label:
          institutionCompaniesList.length > 1 ? 'CUARTEL GENERAL' : 'CUARTEL',
        sub: ''
      });
    }

    return out;
  }, [cuartelLat, cuartelLng, cuarteles, institutionCompaniesList.length]);

  const fallbackCenter = useMemo(() => {
    if (stationsList.length > 0) {
      return { lat: stationsList[0].lat, lng: stationsList[0].lng };
    }
    return { lat: -34.6384, lng: -71.1274 };
  }, [stationsList]);

  // Emergencias activas con coordenadas válidas
  const incidentsPayload = useMemo(() => {
    const list = [];
    for (const d of activeDispatches) {
      if (!d) continue;
      const lat = Number(
        d.coordenadas?.lat ?? d.lat ?? d.latitud ?? d.ubicacion?.lat ?? NaN
      );
      const lng = Number(
        d.coordenadas?.lng ?? d.lng ?? d.longitud ?? d.ubicacion?.lng ?? NaN
      );
      if (isNaN(lat) || isNaN(lng) || Math.abs(lat) < 0.1 || Math.abs(lng) < 0.1) continue;
      const unidadesStr = Array.isArray(d.carros)
        ? d.carros.join(' / ')
        : String(d.carrosTexto || '');
      list.push({
        key: String(d.idServicio || d.id || `${lat}_${lng}`),
        clave: String(d.clave || '10-0').trim().toUpperCase(),
        direccion: String(d.direccion || d.lugar || 'EMERGENCIA EN CURSO').trim(),
        unidades: unidadesStr,
        lat,
        lng
      });
    }
    return list;
  }, [activeDispatches]);

  // Alertantes con GPS activo (solo de servicios actualmente abiertos)
  const alertantesPayload = useMemo(() => {
    const activeIds = new Set(
      activeDispatches.map((d) => String(d?.idServicio || d?.id || '').trim()).filter(Boolean)
    );
    if (activeIds.size === 0) return [];
    const out = [];
    for (const [k, raw] of Object.entries(rtdbAlertantes || {})) {
      if (!raw || typeof raw !== 'object') continue;
      const svcId = String(raw.idServicio || k || '').trim();
      if (!activeIds.has(svcId)) continue;
      const lat = Number(raw.lat ?? raw.latitude);
      const lng = Number(raw.lng ?? raw.longitude);
      if (isNaN(lat) || isNaN(lng) || Math.abs(lat) < 0.1 || Math.abs(lng) < 0.1) continue;
      out.push({
        key: `al_${svcId}`,
        lat,
        lng,
        accuracy: Number(raw.accuracy || 0)
      });
    }
    return out;
  }, [rtdbAlertantes, activeDispatches]);

  // Unidades (Carros Bomba) combinando Firestore (`vehiculos` + `despachos`) + GPS en vivo de RTDB (`telemetria/vehiculos`)
  const vehiclesPayload = useMemo(() => {
    const nowMs = Date.now();
    const stationSlotCounters = {};
    const out = [];

    for (let idx = 0; idx < fleetList.length; idx++) {
      const v = fleetList[idx];
      const uKey = String(v?.idCarro || v?.nombre || v?.id || `U-${idx}`)
        .trim()
        .toUpperCase();
      if (!uKey) continue;

      const statusInfo = resolveCoreUnitStatusInfo(v, activeDispatches, fleetList);
      const isOut08 = Boolean(statusInfo.isOut08 || statusInfo.is08Unit);
      const isInService = Boolean(statusInfo.isInService || statusInfo.isBusy);

      if (onlyServiceUnits && !isInService) continue;

      const statusShort = isOut08
        ? '0-8'
        : isInService
          ? String(statusInfo.badgeLabel || 'EN SERVICIO').toUpperCase()
          : '0-9';

      // Buscar telemetría GPS en RTDB (por clave exacta o normalizada)
      const telem =
        rtdbVehicles?.[uKey] ||
        rtdbVehicles?.[uKey.replace(/[-\s]/g, '')] ||
        Object.values(rtdbVehicles || {}).find(
          (tv) =>
            String(tv?.id || tv?.idCarro || tv?.unidad || '')
              .trim()
              .toUpperCase() === uKey
        ) ||
        null;

      const tLat = Number(telem?.lat ?? telem?.latitude ?? v?.lat ?? v?.coordenadas?.lat);
      const tLng = Number(telem?.lng ?? telem?.longitude ?? v?.lng ?? v?.coordenadas?.lng);
      const tTs = Number(telem?.timestamp || 0);
      const isFreshTelem =
        !isNaN(tLat) &&
        !isNaN(tLng) &&
        Math.abs(tLat) > 0.1 &&
        Math.abs(tLng) > 0.1 &&
        (tTs === 0 || nowMs - tTs < 6 * 3600 * 1000);

      let finalLat = null;
      let finalLng = null;
      let hasLiveGps = false;

      if (isFreshTelem) {
        finalLat = tLat;
        finalLng = tLng;
        hasLiveGps = Boolean(telem && !isNaN(Number(telem.lat ?? telem.latitude)));
      } else {
        // Ubicar en el cuartel de su compañía con ligero desplazamiento radial (~22m) igual que CarPlayTacticalMap.jsx
        const compNum = String(resolveCompanyFromVehicleId(uKey, v?.compania) || '1');
        const st =
          stationsList.find((s) => String(s.compId) === compNum) ||
          stationsList[0] ||
          fallbackCenter;
        if (st && typeof st.lat === 'number' && typeof st.lng === 'number') {
          const slotIdx = stationSlotCounters[compNum] || 0;
          stationSlotCounters[compNum] = slotIdx + 1;
          const angleRad = ((slotIdx * 65) % 360) * (Math.PI / 180);
          const ringRadius = 0.00020 * (1 + Math.floor(slotIdx / 6) * 0.65);
          finalLat = st.lat + Math.sin(angleRad) * ringRadius;
          finalLng = st.lng + Math.cos(angleRad) * ringRadius;
        }
      }

      if (finalLat === null || finalLng === null) continue;

      const rawSpd = Number(telem?.speed ?? 0);
      // En SENTINEL NAV `telemetria/vehiculos.speed` viene en km/h (o m/s si es < 45 y proviene de geolocalización cruda)
      const speedKmh = rawSpd > 0 ? Math.round(rawSpd) : 0;
      const heading = Number(telem?.heading || 0);

      out.push({
        key: uKey,
        idCarro: uKey,
        statusShort,
        isInService,
        hasLiveGps,
        lat: finalLat,
        lng: finalLng,
        heading,
        speedKmh,
        sig: `${uKey}|${statusShort}|${isInService ? 1 : 0}|${Math.round(heading / 5) * 5}|${speedKmh}`
      });
    }

    return out;
  }, [fleetList, activeDispatches, rtdbVehicles, stationsList, fallbackCenter, onlyServiceUnits]);

  // Bomberos en Servicio con GPS en vivo (`telemetria/bomberos`) — Regla estricta:
  // Solo aparecen bomberos en 0-9 con asistencia activa a un servicio abierto (jamás 0-8, "0" ni "-X")
  const bomberosPayload = useMemo(() => {
    const activeServiceIds = new Set(
      activeDispatches.map((d) => String(d?.idServicio || d?.id || '').trim()).filter(Boolean)
    );
    if (activeServiceIds.size === 0) return [];

    const personById = new Map();
    for (const f of firefightersList) {
      if (!f) continue;
      const idReg = String(f.idRegistro || f.id || '').trim();
      if (idReg) personById.set(idReg, f);
    }

    // Recolectar asistentes confirmados en los documentos de despacho activos
    const dispatchAttendingIds = new Set();
    for (const d of activeDispatches) {
      if (Array.isArray(d?.asistentes)) {
        for (const a of d.asistentes) {
          const aid =
            typeof a === 'string'
              ? a.trim()
              : String(a?.idRegistro || a?.id || a?.bomberoId || '').trim();
          if (aid) dispatchAttendingIds.add(aid);
        }
      }
    }

    const nowMs = Date.now();
    const rawValid = [];

    for (const [key, b] of Object.entries(rtdbBomberos || {})) {
      if (!b || typeof b !== 'object') continue;
      const lat = Number(b.lat ?? b.latitude);
      const lng = Number(b.lng ?? b.longitude);
      if (isNaN(lat) || isNaN(lng) || Math.abs(lat) < 0.1 || Math.abs(lng) < 0.1) continue;

      const ts = Number(b.timestamp || 0);
      if (ts > 0 && nowMs - ts > 60 * 60 * 1000) continue; // Ignorar telemetría obsoleta > 60 min

      const docId = String(b.idRegistro || b.id || key || '').trim();
      if (!docId) continue;

      const person = personById.get(docId);
      if (person) {
        const est = String(person.estado || '0-9').trim().toUpperCase();
        const enSvc = String(person.enServicio || '').trim();
        if (est !== '0-9') continue;
        if (enSvc.startsWith('-') || enSvc === '0') continue;
        const hasValidSvc =
          (enSvc && activeServiceIds.has(enSvc)) || dispatchAttendingIds.has(docId);
        if (!hasValidSvc) continue;
      } else {
        const bSvc = String(b.idServicio || '').trim();
        if (!bSvc || !activeServiceIds.has(bSvc)) continue;
      }

      const idRadial = String(person?.idRadial || b.idRadial || docId).trim();
      const fullName = String(
        person?.nombreBombero || person?.nombre || b.nombre || 'Bombero'
      ).trim();
      const shortName = formatShortPersonName(fullName) || fullName;
      const compNum = Number(person?.compania || b.compania || 1) || 1;
      // En iOS/Android `telemetria/bomberos.speed` proviene de CLLocation/Location en m/s -> convertir a km/h
      const rawSpeedMs = Math.max(0, Number(b.speed || 0));
      const speedKmh = Math.round(rawSpeedMs * 3.6);

      rawValid.push({
        key: `bm_${docId}`,
        docId,
        idRadial,
        nombre: shortName,
        companiaLabel: `${compNum}ª Cía`,
        lat,
        lng,
        speedKmh
      });
    }

    // Evitar superposición exacta cuando 2+ bomberos van juntos en el mismo vehículo o cuartel
    const coordBuckets = {};
    return rawValid.map((item) => {
      const bucketKey = `${item.lat.toFixed(4)}_${item.lng.toFixed(4)}`;
      const idx = coordBuckets[bucketKey] || 0;
      coordBuckets[bucketKey] = idx + 1;
      let adjLat = item.lat;
      let adjLng = item.lng;
      if (idx > 0) {
        const angle = ((idx * 72) % 360) * (Math.PI / 180);
        const offset = 0.00012 * (1 + Math.floor(idx / 5) * 0.6);
        adjLat += Math.sin(angle) * offset;
        adjLng += Math.cos(angle) * offset;
      }
      return {
        ...item,
        lat: adjLat,
        lng: adjLng,
        sig: `${item.idRadial}|${item.nombre}|${item.speedKmh}`
      };
    });
  }, [rtdbBomberos, activeDispatches, firefightersList]);

  const syncPayload = useMemo(
    () => ({
      mapLayer,
      isDark: isDarkMode,
      showUnits,
      showBomberos,
      stations: stationsList,
      incidents: incidentsPayload,
      alertantes: alertantesPayload,
      vehicles: vehiclesPayload,
      bomberos: bomberosPayload
    }),
    [
      mapLayer,
      isDarkMode,
      showUnits,
      showBomberos,
      stationsList,
      incidentsPayload,
      alertantesPayload,
      vehiclesPayload,
      bomberosPayload
    ]
  );

  const postToIframes = useCallback((messageObj) => {
    const serialized = JSON.stringify(messageObj);
    [inlineIframeRef.current, modalIframeRef.current].forEach((ifr) => {
      try {
        if (ifr && ifr.contentWindow) {
          ifr.contentWindow.postMessage(serialized, '*');
        }
      } catch (_) {}
    });
  }, []);

  // Empujar actualizaciones en vivo al iframe Leaflet sin recargarlo
  useEffect(() => {
    postToIframes({ type: 'SENTINEL_CMD_MAP_SYNC', payload: syncPayload });
  }, [syncPayload, postToIframes, isFullscreen]);

  // Responder cuando el iframe termina de inicializar Leaflet
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.addEventListener !== 'function') {
      return undefined;
    }
    const onMessage = (ev) => {
      try {
        const data = typeof ev.data === 'string' ? JSON.parse(ev.data) : ev.data;
        if (data?.type === 'SENTINEL_CMD_MAP_READY') {
          postToIframes({ type: 'SENTINEL_CMD_MAP_SYNC', payload: syncPayload });
        }
      } catch (_) {}
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [syncPayload, postToIframes]);

  // Enfocar unidad específica cuando el Comandante toca su tarjeta en la grilla inferior
  useEffect(() => {
    if (!focusedUnitRequest?.unitId) return;
    postToIframes({
      type: 'SENTINEL_CMD_MAP_ACTION',
      action: 'FOCUS_UNIT',
      unitId: focusedUnitRequest.unitId
    });
  }, [focusedUnitRequest, postToIframes]);

  const sendMapAction = (action, extra = {}) => {
    postToIframes({ type: 'SENTINEL_CMD_MAP_ACTION', action, ...extra });
  };

  const inServiceUnitsCount = useMemo(
    () => vehiclesPayload.filter((v) => v.isInService).length,
    [vehiclesPayload]
  );
  const primaryIncident = incidentsPayload[0] || null;

  const renderMapCanvas = (heightValue, inModal = false) => (
    <View
      style={[
        styles.mapWrapper,
        inModal ? styles.mapWrapperFullscreen : { height: heightValue }
      ]}
    >
      {Platform.OS === 'web' ? (
        <iframe
          ref={inModal ? modalIframeRef : inlineIframeRef}
          title={inModal ? 'SentinelCommanderNavFullscreen' : 'SentinelCommanderNavInline'}
          srcDoc={mapHtml}
          onLoad={() => {
            postToIframes({ type: 'SENTINEL_CMD_MAP_SYNC', payload: syncPayload });
            setTimeout(() => sendMapAction('INVALIDATE'), 120);
          }}
          style={{
            width: '100%',
            height: '100%',
            border: 'none',
            backgroundColor: '#060911',
            display: 'block'
          }}
        />
      ) : null}

      {/* Píldora Superior Izquierda estilo SENTINEL NAV */}
      <View style={styles.topLeftHud} pointerEvents="box-none">
        <View style={styles.navBrandBadge}>
          <Navigation size={11} color="#38BDF8" />
          <Text style={styles.navBrandText}>MAPA TÁCTICO NAV</Text>
          <View style={styles.liveDot} />
        </View>

        {activeDispatches.length > 0 ? (
          <TouchableOpacity
            activeOpacity={0.85}
            onPress={() => setOnlyServiceUnits((prev) => !prev)}
            style={[
              styles.filterChip,
              onlyServiceUnits && styles.filterChipActive
            ]}
          >
            <Text style={styles.filterChipText}>
              {onlyServiceUnits ? 'SOLO EN SERVICIO' : 'TODA LA FLOTA'}
            </Text>
          </TouchableOpacity>
        ) : null}
      </View>

      {/* Botonera Flotante Derecha idéntica a CarPlayTacticalMap.jsx de SENTINEL NAV */}
      <View style={styles.rightControlsCol} pointerEvents="box-none">
        <TouchableOpacity
          activeOpacity={0.85}
          onPress={() => setShowUnits((p) => !p)}
          style={[
            styles.navFloatingBtn,
            showUnits && styles.navFloatingBtnUnitsActive
          ]}
        >
          <Truck size={13} color={showUnits ? '#FFFFFF' : '#CBD5E1'} />
          <Text style={[styles.navFloatingBtnText, showUnits && { color: '#FFFFFF' }]}>
            {vehiclesPayload.length}
          </Text>
        </TouchableOpacity>

        <TouchableOpacity
          activeOpacity={0.85}
          onPress={() => setShowBomberos((p) => !p)}
          style={[
            styles.navFloatingBtn,
            showBomberos && styles.navFloatingBtnBomberosActive
          ]}
        >
          <Users size={13} color={showBomberos ? '#FFFFFF' : '#CBD5E1'} />
          <Text style={[styles.navFloatingBtnText, showBomberos && { color: '#FFFFFF' }]}>
            {bomberosPayload.length}
          </Text>
        </TouchableOpacity>

        <TouchableOpacity
          activeOpacity={0.85}
          onPress={() =>
            setMapLayer((prev) => (prev === 'streets' ? 'satellite' : 'streets'))
          }
          style={[
            styles.navFloatingBtn,
            mapLayer === 'satellite' && styles.navFloatingBtnSatActive
          ]}
        >
          <Satellite size={13} color="#FFFFFF" />
        </TouchableOpacity>

        <TouchableOpacity
          activeOpacity={0.85}
          onPress={() => sendMapAction('RECENTER')}
          style={styles.navFloatingBtn}
        >
          <LocateFixed size={13} color="#38BDF8" />
        </TouchableOpacity>

        <TouchableOpacity
          activeOpacity={0.85}
          onPress={() => setIsFullscreen((prev) => !prev)}
          style={styles.navFloatingBtn}
        >
          {inModal ? (
            <Minimize2 size={13} color="#F8FAFC" />
          ) : (
            <Maximize2 size={13} color="#F8FAFC" />
          )}
        </TouchableOpacity>

        <View style={styles.zoomGroup}>
          <TouchableOpacity
            activeOpacity={0.8}
            onPress={() => sendMapAction('ZOOM_IN')}
            style={styles.zoomBtn}
          >
            <Plus size={13} color="#F8FAFC" />
          </TouchableOpacity>
          <View style={styles.zoomDivider} />
          <TouchableOpacity
            activeOpacity={0.8}
            onPress={() => sendMapAction('ZOOM_OUT')}
            style={styles.zoomBtn}
          >
            <Minus size={13} color="#F8FAFC" />
          </TouchableOpacity>
        </View>
      </View>

      {/* Barra Inferior Oscura estilo SENTINEL NAV (#262629) */}
      <View style={styles.bottomHudBar} pointerEvents="none">
        <View style={styles.bottomHudPill}>
          <Text style={styles.bottomHudPrimary} numberOfLines={1}>
            {primaryIncident
              ? `🚨 ${primaryIncident.clave} • ${primaryIncident.direccion}`
              : 'monitoreo gps de flota y personal'}
          </Text>
          <Text style={styles.bottomHudSecondary} numberOfLines={1}>
            {`🚒 ${inServiceUnitsCount}/${fleetList.length} en servicio  •  👨‍🚒 ${bomberosPayload.length} en ruta`}
          </Text>
        </View>
      </View>
    </View>
  );

  return (
    <View style={styles.outerContainer}>
      {renderMapCanvas(275, false)}

      {/* Modal Pantalla Completa para inspección táctica detallada del Comandante */}
      <Modal
        visible={isFullscreen}
        animationType="fade"
        transparent={false}
        onRequestClose={() => setIsFullscreen(false)}
      >
        <View style={styles.fullscreenModalRoot}>
          <View style={styles.fullscreenTopBar}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, flex: 1 }}>
              <Navigation size={15} color="#38BDF8" />
              <Text style={styles.fullscreenTitle} numberOfLines={1}>
                COMANDO TÁCTICO NAV — UNIDADES Y PERSONAL EN VIVO
              </Text>
            </View>
            <TouchableOpacity
              activeOpacity={0.85}
              onPress={() => setIsFullscreen(false)}
              style={styles.closeFullscreenBtn}
            >
              <X size={15} color="#FFFFFF" />
              <Text style={styles.closeFullscreenText}>CERRAR</Text>
            </TouchableOpacity>
          </View>

          <View style={{ flex: 1 }}>{renderMapCanvas(0, true)}</View>
        </View>
      </Modal>
    </View>
  );
};

const styles = StyleSheet.create({
  outerContainer: {
    marginBottom: 10
  },
  mapWrapper: {
    width: '100%',
    borderRadius: 14,
    overflow: 'hidden',
    backgroundColor: '#060911',
    borderWidth: 1,
    borderColor: 'rgba(56, 189, 248, 0.28)',
    position: 'relative'
  },
  mapWrapperFullscreen: {
    flex: 1,
    borderRadius: 0,
    borderWidth: 0
  },
  topLeftHud: {
    position: 'absolute',
    top: 8,
    left: 8,
    zIndex: 20,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    flexWrap: 'wrap',
    maxWidth: '76%'
  },
  navBrandBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    backgroundColor: 'rgba(9, 13, 22, 0.90)',
    borderWidth: 1,
    borderColor: 'rgba(56, 189, 248, 0.45)',
    borderRadius: 999,
    paddingHorizontal: 9,
    paddingVertical: 4
  },
  navBrandText: {
    color: '#F8FAFC',
    fontSize: 9,
    fontWeight: '900',
    letterSpacing: 0.7
  },
  liveDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: '#10B981'
  },
  filterChip: {
    backgroundColor: 'rgba(15, 23, 42, 0.88)',
    borderWidth: 1,
    borderColor: 'rgba(148, 163, 184, 0.35)',
    borderRadius: 999,
    paddingHorizontal: 8,
    paddingVertical: 4
  },
  filterChipActive: {
    backgroundColor: 'rgba(220, 38, 38, 0.88)',
    borderColor: '#FCA5A5'
  },
  filterChipText: {
    color: '#F8FAFC',
    fontSize: 8.5,
    fontWeight: '900',
    letterSpacing: 0.4
  },
  rightControlsCol: {
    position: 'absolute',
    top: 8,
    right: 8,
    zIndex: 20,
    alignItems: 'flex-end',
    gap: 5
  },
  navFloatingBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 4,
    minWidth: 32,
    height: 28,
    paddingHorizontal: 7,
    borderRadius: 9,
    backgroundColor: 'rgba(15, 23, 42, 0.92)',
    borderWidth: 1,
    borderColor: 'rgba(148, 163, 184, 0.32)'
  },
  navFloatingBtnUnitsActive: {
    backgroundColor: 'rgba(2, 132, 199, 0.92)',
    borderColor: '#7DD3FC'
  },
  navFloatingBtnBomberosActive: {
    backgroundColor: 'rgba(5, 150, 105, 0.92)',
    borderColor: '#6EE7B7'
  },
  navFloatingBtnSatActive: {
    backgroundColor: 'rgba(37, 99, 235, 0.92)',
    borderColor: '#93C5FD'
  },
  navFloatingBtnText: {
    color: '#CBD5E1',
    fontSize: 9.5,
    fontWeight: '900'
  },
  zoomGroup: {
    backgroundColor: 'rgba(15, 23, 42, 0.92)',
    borderWidth: 1,
    borderColor: 'rgba(148, 163, 184, 0.32)',
    borderRadius: 9,
    overflow: 'hidden'
  },
  zoomBtn: {
    width: 32,
    height: 26,
    alignItems: 'center',
    justifyContent: 'center'
  },
  zoomDivider: {
    height: 1,
    backgroundColor: 'rgba(148, 163, 184, 0.25)'
  },
  bottomHudBar: {
    position: 'absolute',
    bottom: 7,
    left: 8,
    right: 46,
    zIndex: 20,
    alignItems: 'flex-start'
  },
  bottomHudPill: {
    backgroundColor: 'rgba(24, 24, 27, 0.93)',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.14)',
    borderRadius: 11,
    paddingHorizontal: 10,
    paddingVertical: 5,
    maxWidth: '100%'
  },
  bottomHudPrimary: {
    color: '#FFFFFF',
    fontSize: 9.5,
    fontWeight: '900',
    textTransform: 'uppercase',
    letterSpacing: 0.3
  },
  bottomHudSecondary: {
    color: '#94A3B8',
    fontSize: 8.5,
    fontWeight: '800',
    marginTop: 1
  },
  fullscreenModalRoot: {
    flex: 1,
    backgroundColor: '#060911'
  },
  fullscreenTopBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingTop: 42,
    paddingBottom: 10,
    paddingHorizontal: 14,
    backgroundColor: '#090D16',
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(56, 189, 248, 0.25)'
  },
  fullscreenTitle: {
    color: '#F8FAFC',
    fontSize: 11,
    fontWeight: '900',
    letterSpacing: 0.7,
    flex: 1
  },
  closeFullscreenBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    backgroundColor: 'rgba(220, 38, 38, 0.85)',
    borderWidth: 1,
    borderColor: '#FCA5A5',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 5
  },
  closeFullscreenText: {
    color: '#FFFFFF',
    fontSize: 9.5,
    fontWeight: '900',
    letterSpacing: 0.5
  }
});
