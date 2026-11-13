import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Modal, Platform } from 'react-native';
import { ref, onValue } from 'firebase/database';
import {
  Truck,
  Users,
  Satellite,
  LocateFixed,
  Plus,
  Minus,
  Navigation,
  X
} from 'lucide-react-native';
import { getRtdb, ensureFirebaseAuth } from '../../config/firebase';
import { useLicenseStore } from '../../store/useLicenseStore';
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
      var stationSigMap = {};
      var incidentMarkers = {};
      var alertanteMarkers = {};
      var vehicleMarkers = {};
      var vehicleSigMap = {};
      var bomberoMarkers = {};
      var bomberoSigMap = {};
      var hasAutoFittedOnce = false;
      var lastActiveIncidentSig = '';
      var lastStationSig = '';
      var hadAnyVehicleOnce = false;

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
          map.fitBounds(L.latLngBounds(pts), {
            paddingTopLeft: [28, 108],
            paddingBottomRight: [28, 178],
            maxZoom: 16,
            animate: Boolean(forceFit)
          });
        }
      }

      var lastState = null;

      function handleSync(payload) {
        if (!payload) return;
        lastState = payload;
        applyTileLayer(payload.mapLayer || 'streets', payload.isDark !== false);

        syncMapCollection(
          stationMarkers,
          stationSigMap,
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
            var gpsTxt = 'GPS EN VIVO' + (v.speedKmh >= 2 ? ' • ' + Math.round(v.speedKmh) + ' km/h' : '');
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
        var stSig = (payload.stations || []).map(function(s) { return s.key + ':' + s.lat.toFixed(5) + ',' + s.lng.toFixed(5); }).join('|');
        var hasVehiclesNow = (payload.vehicles || []).length > 0;
        var shouldFitFirstVehicles = !hadAnyVehicleOnce && hasVehiclesNow && !incSig;
        if (hasVehiclesNow) hadAnyVehicleOnce = true;

        if (
          !hasAutoFittedOnce ||
          (incSig && incSig !== lastActiveIncidentSig) ||
          (stSig && stSig !== lastStationSig && !incSig) ||
          shouldFitFirstVehicles
        ) {
          hasAutoFittedOnce = true;
          lastActiveIncidentSig = incSig;
          if (stSig) lastStationSig = stSig;
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
  visible = false,
  onClose = () => {},
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

  const modalIframeRef = useRef(null);
  const syncPayloadRef = useRef(null);
  const focusedUnitReqRef = useRef(null);
  const mapHtml = useMemo(() => buildCommanderNavMapHtml(), []);

  // Suscripción en tiempo real a RTDB únicamente cuando el GPS a pantalla completa está abierto (prevención total de bucles y consumo en segundo plano)
  useEffect(() => {
    if (!visible) return undefined;

    let cancelled = false;
    let unsubVeh = null;
    let unsubBom = null;
    let unsubAle = null;
    let vehTimer = null;
    let bomTimer = null;
    let pendingVehVal = null;
    let pendingBomVal = null;
    let lastVehTs = 0;
    let lastBomTs = 0;

    const connectRtdb = async () => {
      try {
        await ensureFirebaseAuth().catch(() => {});
        if (cancelled) return;
        const rtdb = getRtdb();
        if (!rtdb) return;

        unsubVeh = onValue(
          ref(rtdb, 'telemetria/vehiculos'),
          (snap) => {
            if (cancelled) return;
            const nextVal = snap.val() || {};
            const now = Date.now();
            if (now - lastVehTs >= 450) {
              lastVehTs = now;
              setRtdbVehicles(nextVal);
            } else {
              pendingVehVal = nextVal;
              if (!vehTimer) {
                vehTimer = setTimeout(() => {
                  vehTimer = null;
                  if (!cancelled && pendingVehVal) {
                    lastVehTs = Date.now();
                    setRtdbVehicles(pendingVehVal);
                    pendingVehVal = null;
                  }
                }, 450);
              }
            }
          },
          () => {}
        );

        unsubBom = onValue(
          ref(rtdb, 'telemetria/bomberos'),
          (snap) => {
            if (cancelled) return;
            const nextVal = snap.val() || {};
            const now = Date.now();
            if (now - lastBomTs >= 450) {
              lastBomTs = now;
              setRtdbBomberos(nextVal);
            } else {
              pendingBomVal = nextVal;
              if (!bomTimer) {
                bomTimer = setTimeout(() => {
                  bomTimer = null;
                  if (!cancelled && pendingBomVal) {
                    lastBomTs = Date.now();
                    setRtdbBomberos(pendingBomVal);
                    pendingBomVal = null;
                  }
                }, 450);
              }
            }
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

    // Refrescar silenciosamente metadatos del cuartel/compañías si corresponde (con guarda anti-bucle de 30s)
    useLicenseStore.getState().refreshStationMetadataSilently?.().catch(() => {});

    return () => {
      cancelled = true;
      if (vehTimer) clearTimeout(vehTimer);
      if (bomTimer) clearTimeout(bomTimer);
      try { if (typeof unsubVeh === 'function') unsubVeh(); } catch (_) {}
      try { if (typeof unsubBom === 'function') unsubBom(); } catch (_) {}
      try { if (typeof unsubAle === 'function') unsubAle(); } catch (_) {}
    };
  }, [visible]);

  const stationsList = useMemo(() => {
    const out = [];
    const baseLat = Number(cuartelLat);
    const baseLng = Number(cuartelLng);
    const hasBase =
      Number.isFinite(baseLat) &&
      Number.isFinite(baseLng) &&
      Math.abs(baseLat) > 0.1 &&
      Math.abs(baseLng) > 0.1;

    if (cuarteles && typeof cuarteles === 'object') {
      const entries = Array.isArray(cuarteles)
        ? cuarteles.map((c, idx) => [String(c?.compania || c?.alias || c?.id || idx + 1), c])
        : Object.entries(cuarteles);

      for (const [k, c] of entries) {
        if (!c || typeof c !== 'object') continue;
        const cCoords = c.coordenadas || c.geo || c.ubicacion || {};
        const lat = Number(
          c.lat ?? c.latitud ?? c.latCuartel ?? c.cuartelLat ?? c.cuartel_lat ?? cCoords.lat ?? cCoords.latitude
        );
        const lng = Number(
          c.lng ?? c.lon ?? c.longitud ?? c.lngCuartel ?? c.cuartelLng ?? c.cuartel_long ?? c.cuartel_lng ?? cCoords.lng ?? cCoords.lon ?? cCoords.longitude
        );
        if (Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) > 0.1 && Math.abs(lng) > 0.1) {
          const compNum = String(c.alias || k).replace(/\D/g, '') || '1';
          const rawLabel = String(
            c.cuartel ||
              c.nombre ||
              (institutionCompaniesList.length <= 1 ? 'CUARTEL' : `CUARTEL ${compNum}ª CÍA`)
          )
            .trim()
            .toUpperCase();
          out.push({
            key: `st_${compNum}`,
            compId: compNum,
            lat,
            lng,
            label: rawLabel,
            sub: String(c.direccion || ''),
            sig: `${compNum}|${rawLabel}|${lat.toFixed(5)},${lng.toFixed(5)}`
          });
        }
      }
    }

    if (out.length === 0 && hasBase) {
      const mainLabel = institutionCompaniesList.length > 1 ? 'CUARTEL GENERAL' : 'CUARTEL';
      out.push({
        key: 'st_main',
        compId: '1',
        lat: baseLat,
        lng: baseLng,
        label: mainLabel,
        sub: '',
        sig: `main|${mainLabel}|${baseLat.toFixed(5)},${baseLng.toFixed(5)}`
      });
    } else if (hasBase && out.length > 1) {
      // En multi-compañía, si el Cuartel General tiene coordenadas propias distintas a los cuarteles de compañía, incluirlo también
      const matchesExisting = out.some(
        (s) => Math.abs(s.lat - baseLat) < 0.00025 && Math.abs(s.lng - baseLng) < 0.00025
      );
      if (!matchesExisting) {
        out.unshift({
          key: 'st_general',
          compId: '0',
          lat: baseLat,
          lng: baseLng,
          label: 'CUARTEL GENERAL',
          sub: '',
          sig: `general|CUARTEL GENERAL|${baseLat.toFixed(5)},${baseLng.toFixed(5)}`
        });
      }
    }

    return out;
  }, [cuartelLat, cuartelLng, cuarteles, institutionCompaniesList.length]);

  const incidentsPayload = useMemo(() => {
    const list = [];
    for (const d of activeDispatches) {
      if (!d) continue;
      const lat = Number(
        d.geo?.lat ?? d.coordenadas?.lat ?? d.lat ?? d.latitud ?? d.ubicacion?.lat ?? NaN
      );
      const lng = Number(
        d.geo?.lng ?? d.coordenadas?.lng ?? d.lng ?? d.longitud ?? d.ubicacion?.lng ?? NaN
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

  // Mostrar ESTRICTAMENTE SOLO las unidades que poseen datos GPS reales en `telemetria/vehiculos` (igual que SENTINEL CAD `MaterialMayorView.jsx`)
  const vehiclesPayload = useMemo(() => {
    const baseVehicles = Array.isArray(fleetList) ? [...fleetList] : [];
    const existingNormIds = new Set(
      baseVehicles.map((v) =>
        String(v?.idCarro || v?.nombre || v?.id || '')
          .trim()
          .toUpperCase()
          .replace(/[-\s]/g, '')
      )
    );

    // Incluir cualquier unidad que esté reportando activamente en RTDB (`telemetria/vehiculos`) aunque no figure aún en `fleetList`
    for (const [tKey, tv] of Object.entries(rtdbVehicles || {})) {
      if (!tv || typeof tv !== 'object') continue;
      const rawId = String(tv.idCarro || tv.id || tv.unidad || tKey || '').trim().toUpperCase();
      const normId = rawId.replace(/[-\s]/g, '');
      if (!normId || existingNormIds.has(normId)) continue;
      existingNormIds.add(normId);
      baseVehicles.push({
        idCarro: rawId,
        id: rawId,
        estado: '0-9',
        enServicio: String(tv.enServicio || '0'),
        compania: resolveCompanyFromVehicleId(rawId)
      });
    }

    const out = [];
    const coordBuckets = {};

    for (let idx = 0; idx < baseVehicles.length; idx++) {
      const v = baseVehicles[idx];
      const uKey = String(v?.idCarro || v?.nombre || v?.id || '')
        .trim()
        .toUpperCase();
      if (!uKey) continue;

      const noDash = uKey.replace(/[-\s]/g, '');
      const withDash = uKey.replace(/^([A-Z]+)(\d+)$/, '$1-$2');

      const telem =
        rtdbVehicles?.[uKey] ||
        rtdbVehicles?.[noDash] ||
        rtdbVehicles?.[withDash] ||
        Object.values(rtdbVehicles || {}).find((tv) => {
          if (!tv || typeof tv !== 'object') return false;
          const candidate = String(tv.idCarro || tv.id || tv.unidad || '')
            .trim()
            .toUpperCase();
          return candidate === uKey || candidate.replace(/[-\s]/g, '') === noDash;
        }) ||
        null;

      // Si la unidad aún NO tiene datos GPS reales en `telemetria/vehiculos`, NO mostrarla en el mapa
      if (!telem || typeof telem !== 'object') continue;

      const tLat = Number(telem.lat ?? telem.latitude ?? telem.latitud);
      const tLng = Number(telem.lng ?? telem.lon ?? telem.longitude ?? telem.longitud);
      if (!Number.isFinite(tLat) || !Number.isFinite(tLng) || Math.abs(tLat) < 0.1 || Math.abs(tLng) < 0.1) {
        continue;
      }

      const statusInfo = resolveCoreUnitStatusInfo(v, activeDispatches, baseVehicles);
      const isOut08 = Boolean(statusInfo.is08 || statusInfo.isOut08 || statusInfo.is08Unit);
      const isInService = Boolean(statusInfo.isInService || statusInfo.isBusy);

      if (onlyServiceUnits && !isInService) continue;

      const activeClaveClean = String(statusInfo.activeClave || '').trim().toUpperCase();
      const statusShort = isOut08
        ? '0-8'
        : isInService
          ? activeClaveClean && activeClaveClean !== '0-8'
            ? activeClaveClean
            : String(statusInfo.badgeLabel || 'EN SERVICIO').replace(/^EN\s+/i, '').trim()
          : '0-9';

      // Si dos unidades con GPS real reportan exactamente en el mismo punto (ej. estacionadas en el cuartel), separarlas levemente (~14m) para que no se encimen
      const bucketKey = `${tLat.toFixed(4)}_${tLng.toFixed(4)}`;
      const slotIdx = coordBuckets[bucketKey] || 0;
      coordBuckets[bucketKey] = slotIdx + 1;
      let finalLat = tLat;
      let finalLng = tLng;
      if (slotIdx > 0) {
        const angleRad = ((slotIdx * 65) % 360) * (Math.PI / 180);
        const offset = 0.00013 * (1 + Math.floor(slotIdx / 5) * 0.6);
        finalLat += Math.sin(angleRad) * offset;
        finalLng += Math.cos(angleRad) * offset;
      }

      const rawSpd = Number(telem.speed ?? telem.velocidad ?? 0);
      const speedKmh = Number.isFinite(rawSpd) && rawSpd > 0 ? Math.round(rawSpd) : 0;
      const heading = Number(telem.heading ?? telem.rumbo ?? 0) || 0;

      out.push({
        key: uKey,
        idCarro: uKey,
        statusShort,
        isInService,
        hasLiveGps: true,
        lat: finalLat,
        lng: finalLng,
        heading,
        speedKmh,
        sig: `${uKey}|${statusShort}|${isInService ? 1 : 0}|${Math.round(heading / 5) * 5}|${speedKmh}`
      });
    }

    return out;
  }, [fleetList, activeDispatches, rtdbVehicles, onlyServiceUnits]);

  // Bomberos en Servicio con GPS en vivo (`telemetria/bomberos`)
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
      if (ts > 0 && nowMs - ts > 60 * 60 * 1000) continue;

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

  // Total exacto de Personal en Servicio (unión de bomberos con enServicio activo en Firestore + asistentes confirmados + GPS en vivo)
  const inServicePersonnelCount = useMemo(() => {
    const activeServiceIds = new Set(
      activeDispatches.map((d) => String(d?.idServicio || d?.id || '').trim()).filter(Boolean)
    );
    if (activeServiceIds.size === 0) return 0;

    const personnelIds = new Set();

    for (const f of firefightersList) {
      if (!f) continue;
      const idReg = String(f.idRegistro || f.id || '').trim();
      const est = String(f.estado || '0-9').trim().toUpperCase();
      const enSvc = String(f.enServicio || '').trim();
      if (idReg && est === '0-9' && enSvc && !enSvc.startsWith('-') && enSvc !== '0') {
        if (activeServiceIds.has(enSvc)) {
          personnelIds.add(idReg);
        }
      }
    }

    for (const d of activeDispatches) {
      if (Array.isArray(d?.asistentes)) {
        for (const a of d.asistentes) {
          const aid =
            typeof a === 'string'
              ? a.trim()
              : String(a?.idRegistro || a?.id || a?.bomberoId || '').trim();
          if (aid) personnelIds.add(aid);
        }
      }
    }

    for (const b of bomberosPayload) {
      if (b?.docId) personnelIds.add(String(b.docId));
    }

    return personnelIds.size;
  }, [activeDispatches, firefightersList, bomberosPayload]);

  const inServiceUnitsCount = useMemo(() => {
    let count = 0;
    for (const v of fleetList) {
      const info = resolveCoreUnitStatusInfo(v, activeDispatches, fleetList);
      if (info.isInService || info.isBusy) count += 1;
    }
    return count;
  }, [fleetList, activeDispatches]);

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

  syncPayloadRef.current = syncPayload;
  focusedUnitReqRef.current = focusedUnitRequest;

  const postToIframe = useCallback((messageObj) => {
    try {
      const ifr = modalIframeRef.current;
      if (ifr && ifr.contentWindow) {
        ifr.contentWindow.postMessage(JSON.stringify(messageObj), '*');
      }
    } catch (_) {}
  }, []);

  useEffect(() => {
    if (!visible) return;
    postToIframe({ type: 'SENTINEL_CMD_MAP_SYNC', payload: syncPayload });
  }, [visible, syncPayload, postToIframe]);

  useEffect(() => {
    if (!visible || typeof window === 'undefined' || typeof window.addEventListener !== 'function') {
      return undefined;
    }
    const onMessage = (ev) => {
      try {
        const data = typeof ev.data === 'string' ? JSON.parse(ev.data) : ev.data;
        if (data?.type === 'SENTINEL_CMD_MAP_READY') {
          if (syncPayloadRef.current) {
            postToIframe({ type: 'SENTINEL_CMD_MAP_SYNC', payload: syncPayloadRef.current });
          }
          if (focusedUnitReqRef.current?.unitId) {
            setTimeout(() => {
              postToIframe({
                type: 'SENTINEL_CMD_MAP_ACTION',
                action: 'FOCUS_UNIT',
                unitId: focusedUnitReqRef.current.unitId
              });
            }, 150);
          }
        }
      } catch (_) {}
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [visible, postToIframe]);

  useEffect(() => {
    if (!visible || !focusedUnitRequest?.unitId) return;
    postToIframe({
      type: 'SENTINEL_CMD_MAP_ACTION',
      action: 'FOCUS_UNIT',
      unitId: focusedUnitRequest.unitId
    });
  }, [visible, focusedUnitRequest, postToIframe]);

  const sendMapAction = (action, extra = {}) => {
    postToIframe({ type: 'SENTINEL_CMD_MAP_ACTION', action, ...extra });
  };

  if (!visible) return null;

  return (
    <Modal
      visible={visible}
      animationType="fade"
      transparent={false}
      statusBarTranslucent={true}
      onRequestClose={onClose}
    >
      <View style={styles.fullscreenModalRoot}>
        {/* Lienzo GPS 100% Pantalla Completa Inmersivo (borde a borde tipo iOS) */}
        <View style={styles.mapWrapperFullscreen}>
          {Platform.OS === 'web' ? (
            <iframe
              ref={modalIframeRef}
              title="SentinelCommanderGpsFullscreen"
              srcDoc={mapHtml}
              onLoad={() => {
                if (syncPayloadRef.current) {
                  postToIframe({ type: 'SENTINEL_CMD_MAP_SYNC', payload: syncPayloadRef.current });
                }
                setTimeout(() => {
                  sendMapAction('INVALIDATE');
                  if (focusedUnitReqRef.current?.unitId) {
                    sendMapAction('FOCUS_UNIT', { unitId: focusedUnitReqRef.current.unitId });
                  }
                }, 140);
              }}
              style={{
                position: 'absolute',
                top: 0,
                left: 0,
                right: 0,
                bottom: 0,
                width: '100%',
                height: '100%',
                border: 'none',
                backgroundColor: '#060911',
                display: 'block'
              }}
            />
          ) : null}

          {/* Encabezado Flotante Translúcido Tipo iOS (el mapa continúa detrás de extremo a extremo) */}
          <View style={styles.immersiveTopOverlay} pointerEvents="box-none">
            <View style={styles.immersiveTitlePill} pointerEvents="none">
              <Navigation size={15} color="#38BDF8" />
              <Text style={styles.fullscreenTitle} numberOfLines={2}>
                GPS DE UNIDADES Y PERSONAL EN SERVICIO
              </Text>
            </View>

            <TouchableOpacity
              activeOpacity={0.85}
              onPress={onClose}
              style={styles.closeSquareBtn}
            >
              <X size={20} color="#FFFFFF" />
            </TouchableOpacity>
          </View>

          {/* Filtro opcional si hay despacho activo */}
          {activeDispatches.length > 0 ? (
            <View style={styles.topLeftHud} pointerEvents="box-none">
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
            </View>
          ) : null}

          {/* Dock Inferior Inmersivo Tipo iOS: Botonera Horizontal Uniforme (46x46) Arriba + Tarjeta de Totales Ocupando Todo el Borde Inferior */}
          <View style={styles.bottomImmersiveCluster} pointerEvents="box-none">
            <View style={styles.bottomControlsDockRow} pointerEvents="box-none">
              <TouchableOpacity
                activeOpacity={0.85}
                onPress={() => setShowUnits((p) => !p)}
                style={[
                  styles.uniformMapBtn,
                  showUnits && styles.uniformMapBtnUnitsActive
                ]}
              >
                <Truck size={18} color={showUnits ? '#FFFFFF' : '#CBD5E1'} />
                <Text style={[styles.uniformBtnBadgeText, showUnits && { color: '#FFFFFF' }]}>
                  {vehiclesPayload.length}
                </Text>
              </TouchableOpacity>

              <TouchableOpacity
                activeOpacity={0.85}
                onPress={() => setShowBomberos((p) => !p)}
                style={[
                  styles.uniformMapBtn,
                  showBomberos && styles.uniformMapBtnBomberosActive
                ]}
              >
                <Users size={18} color={showBomberos ? '#FFFFFF' : '#CBD5E1'} />
                <Text style={[styles.uniformBtnBadgeText, showBomberos && { color: '#FFFFFF' }]}>
                  {bomberosPayload.length}
                </Text>
              </TouchableOpacity>

              <TouchableOpacity
                activeOpacity={0.85}
                onPress={() =>
                  setMapLayer((prev) => (prev === 'streets' ? 'satellite' : 'streets'))
                }
                style={[
                  styles.uniformMapBtn,
                  mapLayer === 'satellite' && styles.uniformMapBtnSatActive
                ]}
              >
                <Satellite size={19} color="#FFFFFF" />
              </TouchableOpacity>

              <TouchableOpacity
                activeOpacity={0.85}
                onPress={() => sendMapAction('RECENTER')}
                style={styles.uniformMapBtn}
              >
                <LocateFixed size={19} color="#38BDF8" />
              </TouchableOpacity>

              <TouchableOpacity
                activeOpacity={0.85}
                onPress={() => sendMapAction('ZOOM_IN')}
                style={styles.uniformMapBtn}
              >
                <Plus size={20} color="#F8FAFC" />
              </TouchableOpacity>

              <TouchableOpacity
                activeOpacity={0.85}
                onPress={() => sendMapAction('ZOOM_OUT')}
                style={styles.uniformMapBtn}
              >
                <Minus size={20} color="#F8FAFC" />
              </TouchableOpacity>
            </View>

            {/* Tarjeta de Totales en Servicio Ocupando Todo el Borde Inferior (Sin subtítulo, solo totales) */}
            <View style={styles.bottomFullTotalsBar} pointerEvents="none">
              <View style={styles.totalBlock}>
                <Text style={styles.totalNumberUnits}>
                  {inServiceUnitsCount} / {fleetList.length}
                </Text>
                <Text style={styles.totalLabel}>UNIDADES EN SERVICIO</Text>
              </View>

              <View style={styles.totalDivider} />

              <View style={styles.totalBlock}>
                <Text style={styles.totalNumberPersonnel}>
                  {inServicePersonnelCount}
                </Text>
                <Text style={styles.totalLabel}>PERSONAL EN SERVICIO</Text>
              </View>
            </View>
          </View>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  fullscreenModalRoot: {
    flex: 1,
    width: '100%',
    height: '100%',
    backgroundColor: '#060911',
    ...(Platform.OS === 'web'
      ? {
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0
        }
      : {})
  },
  mapWrapperFullscreen: {
    flex: 1,
    width: '100%',
    height: '100%',
    backgroundColor: '#060911',
    position: 'relative',
    overflow: 'hidden'
  },
  immersiveTopOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    zIndex: 30,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingTop: Platform.OS === 'web' ? 'max(38px, env(safe-area-inset-top, 38px))' : Platform.OS === 'ios' ? 48 : 38,
    paddingHorizontal: 14,
    paddingBottom: 8,
    gap: 10
  },
  immersiveTitlePill: {
    flex: 1,
    minHeight: 46,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 14,
    paddingVertical: 6,
    borderRadius: 14,
    backgroundColor: 'rgba(9, 13, 22, 0.80)',
    borderWidth: 1,
    borderColor: 'rgba(56, 189, 248, 0.34)',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.45,
    shadowRadius: 8,
    elevation: 6,
    ...(Platform.OS === 'web'
      ? {
          backdropFilter: 'blur(18px) saturate(160%)',
          WebkitBackdropFilter: 'blur(18px) saturate(160%)'
        }
      : {})
  },
  fullscreenTitle: {
    color: '#F8FAFC',
    fontSize: 12,
    fontWeight: '900',
    letterSpacing: 0.55,
    flex: 1
  },
  closeSquareBtn: {
    width: 46,
    height: 46,
    borderRadius: 14,
    backgroundColor: 'rgba(220, 38, 38, 0.88)',
    borderWidth: 1,
    borderColor: 'rgba(252, 165, 165, 0.85)',
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.45,
    shadowRadius: 8,
    elevation: 6,
    ...(Platform.OS === 'web'
      ? {
          backdropFilter: 'blur(16px)',
          WebkitBackdropFilter: 'blur(16px)'
        }
      : {})
  },
  topLeftHud: {
    position: 'absolute',
    top: Platform.OS === 'web' ? 'calc(max(38px, env(safe-area-inset-top, 38px)) + 56px)' : Platform.OS === 'ios' ? 104 : 94,
    left: 14,
    zIndex: 25,
    flexDirection: 'row',
    alignItems: 'center'
  },
  filterChip: {
    height: 46,
    paddingHorizontal: 14,
    borderRadius: 14,
    backgroundColor: 'rgba(9, 13, 22, 0.80)',
    borderWidth: 1,
    borderColor: 'rgba(148, 163, 184, 0.36)',
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 3 },
    shadowOpacity: 0.4,
    shadowRadius: 6,
    elevation: 5,
    ...(Platform.OS === 'web'
      ? {
          backdropFilter: 'blur(16px)',
          WebkitBackdropFilter: 'blur(16px)'
        }
      : {})
  },
  filterChipActive: {
    backgroundColor: 'rgba(220, 38, 38, 0.88)',
    borderColor: '#FCA5A5'
  },
  filterChipText: {
    color: '#F8FAFC',
    fontSize: 10.5,
    fontWeight: '900',
    letterSpacing: 0.5
  },
  bottomImmersiveCluster: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    zIndex: 28,
    alignItems: 'stretch'
  },
  bottomControlsDockRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    alignSelf: 'center',
    gap: 8,
    marginBottom: 10,
    paddingHorizontal: 10,
    paddingVertical: 7,
    borderRadius: 18,
    backgroundColor: 'rgba(9, 13, 22, 0.76)',
    borderWidth: 1,
    borderColor: 'rgba(148, 163, 184, 0.28)',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 6 },
    shadowOpacity: 0.5,
    shadowRadius: 12,
    elevation: 8,
    ...(Platform.OS === 'web'
      ? {
          backdropFilter: 'blur(20px) saturate(165%)',
          WebkitBackdropFilter: 'blur(20px) saturate(165%)'
        }
      : {})
  },
  uniformMapBtn: {
    width: 46,
    height: 46,
    borderRadius: 13,
    backgroundColor: 'rgba(15, 23, 42, 0.88)',
    borderWidth: 1.2,
    borderColor: 'rgba(148, 163, 184, 0.36)',
    alignItems: 'center',
    justifyContent: 'center'
  },
  uniformMapBtnUnitsActive: {
    backgroundColor: 'rgba(2, 132, 199, 0.92)',
    borderColor: '#7DD3FC'
  },
  uniformMapBtnBomberosActive: {
    backgroundColor: 'rgba(5, 150, 105, 0.92)',
    borderColor: '#6EE7B7'
  },
  uniformMapBtnSatActive: {
    backgroundColor: 'rgba(37, 99, 235, 0.92)',
    borderColor: '#93C5FD'
  },
  uniformBtnBadgeText: {
    color: '#CBD5E1',
    fontSize: 9.5,
    fontWeight: '900',
    marginTop: 1
  },
  bottomFullTotalsBar: {
    width: '100%',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: 'rgba(9, 13, 22, 0.84)',
    borderTopWidth: 1.2,
    borderTopColor: 'rgba(56, 189, 248, 0.34)',
    paddingTop: 12,
    paddingBottom: Platform.OS === 'web' ? 'max(16px, env(safe-area-inset-bottom, 16px))' : Platform.OS === 'ios' ? 26 : 14,
    paddingHorizontal: 16,
    ...(Platform.OS === 'web'
      ? {
          backdropFilter: 'blur(22px) saturate(170%)',
          WebkitBackdropFilter: 'blur(22px) saturate(170%)'
        }
      : {})
  },
  totalBlock: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center'
  },
  totalDivider: {
    width: 1,
    height: 38,
    backgroundColor: 'rgba(148, 163, 184, 0.28)',
    marginHorizontal: 10
  },
  totalNumberUnits: {
    color: '#38BDF8',
    fontSize: 22,
    fontWeight: '900',
    letterSpacing: 0.5
  },
  totalNumberPersonnel: {
    color: '#34D399',
    fontSize: 22,
    fontWeight: '900',
    letterSpacing: 0.5
  },
  totalLabel: {
    color: '#F8FAFC',
    fontSize: 11,
    fontWeight: '900',
    letterSpacing: 0.7,
    marginTop: 3
  }
});
