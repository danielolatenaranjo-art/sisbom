import React, { useEffect, useRef, useCallback } from 'react';
import { View, StyleSheet, Image, Platform } from 'react-native';
import * as Haptics from 'expo-haptics';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { SENTINEL_LOGO_CONTOURS } from './sentinelLogoContours';

export const INTRO_VIDEO_SEEN_KEY = '@sentinel_one_intro_video_seen_v3';
export const GUIDED_TOUR_SEEN_KEY = '@sentinel_one_guided_tour_seen_v3';

export function isSentinelOneEcosystemMode() {
  try {
    if (typeof window !== 'undefined' && window.SentinelAndroidBridge?.getAppMode) {
      const mode = String(window.SentinelAndroidBridge.getAppMode() || 'ONE')
        .trim()
        .toUpperCase();
      return mode === 'ONE' || mode === '';
    }
  } catch (_) {}
  return true;
}

export function readOnboardingFlagSync(key) {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      return window.localStorage.getItem(key) === 'true';
    }
  } catch (_) {}
  return false;
}

export async function readOnboardingFlagAsync(key) {
  if (readOnboardingFlagSync(key)) return true;
  try {
    const val = await AsyncStorage.getItem(key);
    if (val === 'true') {
      try {
        if (typeof window !== 'undefined' && window.localStorage) {
          window.localStorage.setItem(key, 'true');
        }
      } catch (_) {}
      return true;
    }
    return false;
  } catch (_) {
    return false;
  }
}

export function persistOnboardingFlag(key) {
  try {
    if (typeof window !== 'undefined') {
      if (window.localStorage) {
        window.localStorage.setItem(key, 'true');
      }
      if (window.webkit?.messageHandlers?.iOSApp?.postMessage) {
        window.webkit.messageHandlers.iOSApp.postMessage({
          action: 'syncStorageCache',
          key,
          value: 'true'
        });
      }
    }
  } catch (_) {}
  AsyncStorage.setItem(key, 'true').catch(() => {});
}

export function readSafeAreaInsets() {
  try {
    if (typeof window !== 'undefined' && window.__SENTINEL_SAFE_INSETS__) {
      const top = Number(window.__SENTINEL_SAFE_INSETS__.top) || 0;
      const bottom = Number(window.__SENTINEL_SAFE_INSETS__.bottom) || 0;
      return { top, bottom };
    }
  } catch (_) {}
  return {
    top: Platform.OS === 'ios' ? 48 : 34,
    bottom: Platform.OS === 'ios' ? 28 : 20
  };
}

function clamp01(v) {
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

function easeInOutCubic(t) {
  const x = clamp01(t);
  return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
}

function easeOutCubic(t) {
  const x = clamp01(t);
  return 1 - Math.pow(1 - x, 3);
}

// Pre-dividir el contorno exterior del escudo en brazo izquierdo y derecho desde la cúspide hasta la punta inferior
function buildSymmetricShieldArms(outerChains) {
  const ring = outerChains && outerChains[0];
  if (!ring || ring.length < 10) return [];
  let topIdx = 0;
  let botIdx = 0;
  for (let i = 1; i < ring.length; i++) {
    if (ring[i][1] < ring[topIdx][1]) topIdx = i;
    if (ring[i][1] > ring[botIdx][1]) botIdx = i;
  }
  const n = ring.length;
  const armA = [];
  for (let i = topIdx; ; i = (i + 1) % n) {
    armA.push(ring[i]);
    if (i === botIdx || armA.length > n) break;
  }
  const armB = [];
  for (let i = topIdx; ; i = (i - 1 + n) % n) {
    armB.push(ring[i]);
    if (i === botIdx || armB.length > n) break;
  }
  return [armA, armB];
}

const SHIELD_ARMS = buildSymmetricShieldArms(SENTINEL_LOGO_CONTOURS.shieldOuter);
const INNER_CHAINS = [...(SENTINEL_LOGO_CONTOURS.shieldInner || [])].sort((a, b) => {
  const ay = a[0] ? a[0][1] : 0;
  const by = b[0] ? b[0][1] : 0;
  return by - ay;
});
const WORDMARK_CHAINS = SENTINEL_LOGO_CONTOURS.wordmark || [];
const LOGO_ASPECT = SENTINEL_LOGO_CONTOURS.aspectRatio || 1.6327;

function createGlowSprite(stops) {
  if (typeof document === 'undefined') return null;
  const sz = 64;
  const c = document.createElement('canvas');
  c.width = sz;
  c.height = sz;
  const gCtx = c.getContext('2d');
  const grad = gCtx.createRadialGradient(sz / 2, sz / 2, 0, sz / 2, sz / 2, sz / 2);
  for (const [offset, color] of stops) {
    grad.addColorStop(offset, color);
  }
  gCtx.fillStyle = grad;
  gCtx.fillRect(0, 0, sz, sz);
  return c;
}

export const FirstLaunchIntroVideo = ({
  targetLogoRect,
  onCardRevealProgress,
  onComplete
}) => {
  const canvasRef = useRef(null);
  const frontCanvasRef = useRef(null);
  const logoWrapDomRef = useRef(null);
  const targetRectRef = useRef(targetLogoRect);
  const cardRevealCbRef = useRef(onCardRevealProgress);
  const completeCbRef = useRef(onComplete);
  const finishedRef = useRef(false);
  const lastReportedRevealRef = useRef(0);

  useEffect(() => {
    targetRectRef.current = targetLogoRect;
  }, [targetLogoRect]);

  useEffect(() => {
    cardRevealCbRef.current = onCardRevealProgress;
  }, [onCardRevealProgress]);

  useEffect(() => {
    completeCbRef.current = onComplete;
  }, [onComplete]);

  const finalizeOnce = useCallback(() => {
    if (finishedRef.current) return;
    finishedRef.current = true;
    persistOnboardingFlag(INTRO_VIDEO_SEEN_KEY);
    if (cardRevealCbRef.current) {
      cardRevealCbRef.current(1);
    }
    if (completeCbRef.current) {
      completeCbRef.current();
    }
  }, []);

  useEffect(() => {
    if (Platform.OS !== 'web' || typeof window === 'undefined') {
      finalizeOnce();
      return;
    }

    const crimsonSprite = createGlowSprite([
      [0, 'rgba(239, 68, 68, 0.72)'],
      [0.42, 'rgba(185, 28, 28, 0.30)'],
      [1, 'rgba(185, 28, 28, 0)']
    ]);
    const amberSprite = createGlowSprite([
      [0, 'rgba(255, 237, 213, 0.95)'],
      [0.28, 'rgba(251, 146, 60, 0.72)'],
      [0.65, 'rgba(220, 38, 38, 0.24)'],
      [1, 'rgba(220, 38, 38, 0)']
    ]);
    const sparkCoreSprite = createGlowSprite([
      [0, 'rgba(255, 255, 255, 1)'],
      [0.35, 'rgba(253, 186, 116, 0.85)'],
      [0.75, 'rgba(239, 68, 68, 0.3)'],
      [1, 'rgba(239, 68, 68, 0)']
    ]);

    const sparks = [];
    const flamePuffs = [];
    let impactTriggered = false;
    let rafId = 0;
    const startTs = performance.now();
    let prevTs = startTs;

    const spawnSpark = (x, y, vx, vy, size, maxLife) => {
      if (sparks.length > 260) return;
      sparks.push({
        x,
        y,
        vx,
        vy,
        size,
        life: 0,
        maxLife,
        seed: Math.random() * Math.PI * 2
      });
    };

    const spawnFlamePuff = (x, y, vx, vy, radius, maxLife) => {
      if (flamePuffs.length > 90) return;
      flamePuffs.push({
        x,
        y,
        vx,
        vy,
        radius,
        life: 0,
        maxLife
      });
    };

    const drawPartialChain = (ctx, chain, progress, imgX, imgY, imgW, imgH, emitTipSparks) => {
      if (!chain || chain.length < 2 || progress <= 0.002) return;
      const totalSegs = chain.length - 1;
      const exactPos = clamp01(progress) * totalSegs;
      const lastFull = Math.floor(exactPos);
      const frac = exactPos - lastFull;

      ctx.beginPath();
      ctx.moveTo(imgX + chain[0][0] * imgW, imgY + chain[0][1] * imgH);
      for (let i = 1; i <= lastFull && i < chain.length; i++) {
        ctx.lineTo(imgX + chain[i][0] * imgW, imgY + chain[i][1] * imgH);
      }
      let tipX = imgX + chain[Math.min(lastFull, chain.length - 1)][0] * imgW;
      let tipY = imgY + chain[Math.min(lastFull, chain.length - 1)][1] * imgH;
      if (lastFull < totalSegs && frac > 0) {
        const p0 = chain[lastFull];
        const p1 = chain[lastFull + 1];
        tipX = imgX + (p0[0] + (p1[0] - p0[0]) * frac) * imgW;
        tipY = imgY + (p0[1] + (p1[1] - p0[1]) * frac) * imgH;
        ctx.lineTo(tipX, tipY);
      }
      ctx.stroke();

      if (emitTipSparks && progress < 0.995) {
        if (amberSprite) {
          ctx.drawImage(amberSprite, tipX - 18, tipY - 18, 36, 36);
        }
        if (Math.random() < 0.65) {
          spawnSpark(
            tipX + (Math.random() - 0.5) * 4,
            tipY + (Math.random() - 0.5) * 4,
            (Math.random() - 0.5) * 95,
            -35 - Math.random() * 110,
            2.2 + Math.random() * 2.8,
            0.45 + Math.random() * 0.55
          );
        }
      }
    };

    const renderFrame = (now) => {
      if (finishedRef.current) return;

      const dt = Math.min(0.05, Math.max(0.001, (now - prevTs) / 1000));
      prevTs = now;
      const t = (now - startTs) / 1000;

      const canvas = canvasRef.current;
      const frontCanvas = frontCanvasRef.current;
      if (!canvas || !frontCanvas) {
        rafId = window.requestAnimationFrame(renderFrame);
        return;
      }

      const W = window.innerWidth || 390;
      const H = window.innerHeight || 844;
      const dpr = Math.min(2.25, window.devicePixelRatio || 1);
      const cw = Math.round(W * dpr);
      const ch = Math.round(H * dpr);
      if (canvas.width !== cw || canvas.height !== ch) {
        canvas.width = cw;
        canvas.height = ch;
      }
      if (frontCanvas.width !== cw || frontCanvas.height !== ch) {
        frontCanvas.width = cw;
        frontCanvas.height = ch;
      }

      const ctx = canvas.getContext('2d');
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);

      const fCtx = frontCanvas.getContext('2d');
      fCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
      fCtx.clearRect(0, 0, W, H);

      // Geometría del escudo en el escenario central (misma proporción 4:3 que styles.topLogo 156x117)
      const heroW = Math.min(272, Math.max(216, W * 0.66));
      const heroH = heroW * 0.75;
      const heroBox = {
        x: (W - heroW) * 0.5,
        y: H * 0.42 - heroH * 0.5,
        width: heroW,
        height: heroH
      };

      const targetBox = targetRectRef.current || {
        x: (W - 156) * 0.5,
        y: H * 0.5 - 148,
        width: 156,
        height: 117
      };

      const imgW = heroBox.width;
      const imgH = heroBox.width / LOGO_ASPECT;
      const imgX = heroBox.x;
      const imgY = heroBox.y + (heroBox.height - imgH) * 0.5;
      const shieldCenterX = imgX + imgW * 0.5;
      const shieldCenterY = imgY + imgH * 0.42;

      let curLogoBox = heroBox;
      let logoOpacity = 0;
      let cardReveal = 0;
      let fireMasterAlpha = 1;

      if (t >= 3.74) {
        logoOpacity = easeOutCubic((t - 3.74) / 0.58);
      }

      // Fusión física continua hacia #login-box (6.20s -> 8.15s)
      if (t >= 6.2) {
        const morphP = easeInOutCubic((t - 6.2) / 1.95);
        curLogoBox = {
          x: heroBox.x + (targetBox.x - heroBox.x) * morphP,
          y: heroBox.y + (targetBox.y - heroBox.y) * morphP,
          width: heroBox.width + (targetBox.width - heroBox.width) * morphP,
          height: heroBox.height + (targetBox.height - heroBox.height) * morphP
        };
        fireMasterAlpha = 1 - morphP;
      }

      if (t >= 6.6) {
        cardReveal = easeOutCubic((t - 6.6) / 1.55);
      }

      if (t >= 8.3) {
        finalizeOnce();
        return;
      }

      if (
        cardRevealCbRef.current &&
        (Math.abs(cardReveal - lastReportedRevealRef.current) >= 0.015 ||
          (cardReveal === 1 && lastReportedRevealRef.current !== 1))
      ) {
        lastReportedRevealRef.current = cardReveal;
        cardRevealCbRef.current(cardReveal);
      }

      if (logoWrapDomRef.current) {
        const el = logoWrapDomRef.current;
        el.style.left = `${curLogoBox.x}px`;
        el.style.top = `${curLogoBox.y}px`;
        el.style.width = `${curLogoBox.width}px`;
        el.style.height = `${curLogoBox.height}px`;
        el.style.opacity = String(logoOpacity);
      }

      ctx.save();
      ctx.globalCompositeOperation = 'lighter';

      // CAPA 1: Resplandor térmico volumétrico detrás del escudo
      const glowIntensity =
        (t < 1.0 ? t : t < 3.8 ? 1.0 : t < 4.3 ? 1.65 - (t - 3.8) : 1.0) *
        fireMasterAlpha;
      if (glowIntensity > 0.01 && crimsonSprite) {
        const haloR = Math.max(W, H) * 0.34 * (0.94 + 0.06 * Math.sin(t * 7.5));
        ctx.globalAlpha = clamp01(0.42 * glowIntensity);
        ctx.drawImage(
          crimsonSprite,
          shieldCenterX - haloR,
          shieldCenterY - haloR,
          haloR * 2,
          haloR * 2
        );
      }

      // CAPA 2 (0.0s -> 1.05s): Dos corrientes curvas de fuego convergiendo desde los bordes de la pantalla
      if (t <= 1.18) {
        const sp = easeInOutCubic(t / 0.96);
        const apexX = imgX + imgW * 0.5;
        const apexY = imgY + imgH * 0.015;

        const streamLeftX = -20 + (apexX + 20) * sp;
        const streamLeftY = H * 0.68 + (apexY - H * 0.68) * sp - Math.sin(sp * Math.PI) * 65;

        const streamRightX = W + 20 + (apexX - (W + 20)) * sp;
        const streamRightY = H * 0.26 + (apexY - H * 0.26) * sp + Math.sin(sp * Math.PI) * 65;

        const streamAlpha = t < 0.92 ? 1 : clamp01(1 - (t - 0.92) / 0.26);
        ctx.globalAlpha = streamAlpha * fireMasterAlpha;

        if (amberSprite) {
          ctx.drawImage(amberSprite, streamLeftX - 28, streamLeftY - 28, 56, 56);
          ctx.drawImage(amberSprite, streamRightX - 28, streamRightY - 28, 56, 56);
        }
        for (let k = 0; k < 3; k++) {
          spawnFlamePuff(
            streamLeftX + (Math.random() - 0.5) * 10,
            streamLeftY + (Math.random() - 0.5) * 10,
            (Math.random() - 0.5) * 35,
            -25 - Math.random() * 45,
            18 + Math.random() * 16,
            0.35 + Math.random() * 0.25
          );
          spawnFlamePuff(
            streamRightX + (Math.random() - 0.5) * 10,
            streamRightY + (Math.random() - 0.5) * 10,
            (Math.random() - 0.5) * 35,
            -25 - Math.random() * 45,
            18 + Math.random() * 16,
            0.35 + Math.random() * 0.25
          );
          spawnSpark(
            streamLeftX,
            streamLeftY,
            (Math.random() - 0.5) * 110,
            (Math.random() - 0.5) * 110 - 30,
            2 + Math.random() * 2.5,
            0.5 + Math.random() * 0.5
          );
          spawnSpark(
            streamRightX,
            streamRightY,
            (Math.random() - 0.5) * 110,
            (Math.random() - 0.5) * 110 - 30,
            2 + Math.random() * 2.5,
            0.5 + Math.random() * 0.5
          );
        }
      }

      // CAPA 3: Lenguas de fuego ascendentes en la base y llama central del halcón (1.15s -> 5.0s)
      if (t >= 1.1 && t <= 4.95) {
        const flameIntensity =
          t < 1.6 ? (t - 1.1) / 0.5 : t > 4.0 ? 1 - (t - 4.0) / 0.95 : 1;
        if (Math.random() < 0.85 * flameIntensity) {
          const baseSpread = (Math.random() - 0.5) * imgW * 0.34;
          const baseY = imgY + imgH * (0.64 - Math.abs(baseSpread / (imgW * 0.5)) * 0.22);
          spawnFlamePuff(
            shieldCenterX + baseSpread,
            baseY,
            (Math.random() - 0.5) * 28,
            -55 - Math.random() * 75,
            22 + Math.random() * 22,
            0.48 + Math.random() * 0.38
          );
        }
        if (Math.random() < 0.7 * flameIntensity) {
          spawnSpark(
            shieldCenterX + (Math.random() - 0.5) * imgW * 0.52,
            imgY + imgH * (0.35 + Math.random() * 0.35),
            (Math.random() - 0.5) * 70,
            -65 - Math.random() * 125,
            1.8 + Math.random() * 2.6,
            0.65 + Math.random() * 0.75
          );
        }
      }

      for (let i = flamePuffs.length - 1; i >= 0; i--) {
        const p = flamePuffs[i];
        p.life += dt;
        if (p.life >= p.maxLife) {
          flamePuffs.splice(i, 1);
          continue;
        }
        const lr = p.life / p.maxLife;
        p.x += (p.vx + Math.sin(t * 9 + i) * 18) * dt;
        p.y += p.vy * dt;
        const alpha = Math.sin(lr * Math.PI) * 0.36 * fireMasterAlpha;
        const rad = p.radius * (1 - lr * 0.35);
        if (alpha > 0.005 && amberSprite) {
          ctx.globalAlpha = alpha;
          ctx.drawImage(amberSprite, p.x - rad, p.y - rad, rad * 2, rad * 2);
        }
      }

      // CAPA 4 (0.90s -> 5.10s): Trazado incandescente de los contornos reales del Escudo Halcón + SENTINEL
      const contourFadeOut = t <= 3.95 ? 1 : clamp01(1 - (t - 3.95) / 1.15);
      if (t >= 0.88 && contourFadeOut > 0.01) {
        const outerProg = clamp01((t - 0.9) / 1.95);
        const innerGlobal = clamp01((t - 1.45) / 2.25);
        const wordProg = clamp01((t - 3.74) / 0.78);

        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';

        const strokePass = (lineWidth, strokeStyle, alphaMult, withSparks) => {
          ctx.lineWidth = lineWidth;
          ctx.strokeStyle = strokeStyle;
          ctx.globalAlpha = clamp01(contourFadeOut * alphaMult * fireMasterAlpha);

          for (let a = 0; a < SHIELD_ARMS.length; a++) {
            drawPartialChain(
              ctx,
              SHIELD_ARMS[a],
              easeInOutCubic(outerProg),
              imgX,
              imgY,
              imgW,
              imgH,
              withSparks
            );
          }

          if (innerGlobal > 0) {
            const count = INNER_CHAINS.length;
            for (let idx = 0; idx < count; idx++) {
              const startOffset = (idx / Math.max(1, count)) * 0.55;
              const localP = clamp01((innerGlobal - startOffset) / 0.45);
              if (localP > 0) {
                drawPartialChain(
                  ctx,
                  INNER_CHAINS[idx],
                  easeOutCubic(localP),
                  imgX,
                  imgY,
                  imgW,
                  imgH,
                  withSparks && idx % 3 === 0
                );
              }
            }
          }

          if (wordProg > 0) {
            const wCount = WORDMARK_CHAINS.length;
            for (let idx = 0; idx < wCount; idx++) {
              const startOffset = (idx / Math.max(1, wCount)) * 0.52;
              const localP = clamp01((wordProg - startOffset) / 0.48);
              if (localP > 0) {
                drawPartialChain(
                  ctx,
                  WORDMARK_CHAINS[idx],
                  easeOutCubic(localP),
                  imgX,
                  imgY,
                  imgW,
                  imgH,
                  withSparks && idx % 2 === 0
                );
              }
            }
          }
        };

        strokePass(8.5, 'rgba(220, 38, 38, 0.42)', 0.85, false);
        strokePass(3.4, 'rgba(251, 146, 60, 0.88)', 0.95, false);
        strokePass(1.35, 'rgba(255, 247, 237, 0.98)', 1.0, true);
      }

      // CAPA 5 (3.80s): Pulso háptico + explosión radial de chispas y onda expansiva a pantalla completa
      if (!impactTriggered && t >= 3.78) {
        impactTriggered = true;
        try {
          Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Heavy).catch(() => {});
        } catch (_) {}

        for (let k = 0; k < 105; k++) {
          const ang = Math.random() * Math.PI * 2;
          const spd = 90 + Math.random() * 360;
          spawnSpark(
            shieldCenterX + Math.cos(ang) * 18,
            shieldCenterY + Math.sin(ang) * 18,
            Math.cos(ang) * spd,
            Math.sin(ang) * spd - 35,
            2.0 + Math.random() * 3.4,
            0.65 + Math.random() * 1.15
          );
        }
      }

      if (t >= 3.78 && t <= 4.85) {
        const ringP = easeOutCubic((t - 3.78) / 1.05);
        const maxR = Math.hypot(W, H) * 0.68;
        const curR = 24 + maxR * ringP;
        ctx.globalAlpha = (1 - ringP) * 0.52 * fireMasterAlpha;
        ctx.lineWidth = Math.max(1.5, 14 * (1 - ringP));
        ctx.strokeStyle = 'rgba(251, 146, 60, 0.9)';
        ctx.beginPath();
        ctx.arc(shieldCenterX, shieldCenterY, curR, 0, Math.PI * 2);
        ctx.stroke();
      }

      // CAPA 6: Chispas incandescentes con estela en toda la pantalla vertical
      for (let i = sparks.length - 1; i >= 0; i--) {
        const s = sparks[i];
        s.life += dt;
        if (s.life >= s.maxLife) {
          sparks.splice(i, 1);
          continue;
        }
        const lr = s.life / s.maxLife;
        s.vx *= Math.pow(0.45, dt);
        s.vx += Math.sin(t * 6 + s.seed) * 32 * dt;
        s.vy -= 28 * dt;
        const prevX = s.x;
        const prevY = s.y;
        s.x += s.vx * dt;
        s.y += s.vy * dt;

        const alpha = (1 - lr * lr) * 0.92 * fireMasterAlpha;
        if (alpha <= 0.01) continue;

        ctx.globalAlpha = alpha;
        ctx.strokeStyle = lr < 0.35 ? '#FFEDD5' : '#FB923C';
        ctx.lineWidth = Math.max(1, s.size * (1 - lr * 0.5) * 0.65);
        ctx.beginPath();
        ctx.moveTo(prevX - s.vx * 0.022, prevY - s.vy * 0.022);
        ctx.lineTo(s.x, s.y);
        ctx.stroke();

        if (sparkCoreSprite) {
          const r = s.size * (1.8 - lr * 0.7);
          ctx.drawImage(sparkCoreSprite, s.x - r, s.y - r, r * 2, r * 2);
        }
      }

      ctx.restore();

      // CAPA FRONTAL DE PANTALLA COMPLETA (4.35s -> 5.95s):
      // Reflejo diagonal continuo de borde a borde en TODA la pantalla (sin ningún recuadro alrededor del logo)
      if (t >= 4.35 && t <= 5.95) {
        const gp = clamp01((t - 4.35) / 1.6);
        const sheenIntensity = Math.sin(gp * Math.PI) * fireMasterAlpha;
        if (sheenIntensity > 0.005) {
          // Vector normal a 24° (haz inclinado de arriba-derecha a abajo-izquierda atravesando el 100% del alto y ancho)
          const nx = 0.9135;
          const ny = 0.4067;
          const travelSpan = W * nx + H * ny + Math.max(W, H) * 0.55;
          const distAlongNormal = -Math.max(W, H) * 0.28 + travelSpan * easeInOutCubic(gp);
          const cx = nx * distAlongNormal;
          const cy = ny * distAlongNormal;
          const halfBand = Math.max(W, H) * 0.19;

          fCtx.save();
          fCtx.globalCompositeOperation = 'lighter';
          const sheenGrad = fCtx.createLinearGradient(
            cx - nx * halfBand,
            cy - ny * halfBand,
            cx + nx * halfBand,
            cy + ny * halfBand
          );
          const aMid = (0.14 * sheenIntensity).toFixed(4);
          const aSoft = (0.065 * sheenIntensity).toFixed(4);
          const aEdge = (0.018 * sheenIntensity).toFixed(4);
          sheenGrad.addColorStop(0, 'rgba(255, 244, 232, 0)');
          sheenGrad.addColorStop(0.22, `rgba(255, 240, 224, ${aEdge})`);
          sheenGrad.addColorStop(0.42, `rgba(255, 245, 235, ${aSoft})`);
          sheenGrad.addColorStop(0.5, `rgba(255, 250, 245, ${aMid})`);
          sheenGrad.addColorStop(0.58, `rgba(255, 245, 235, ${aSoft})`);
          sheenGrad.addColorStop(0.78, `rgba(255, 240, 224, ${aEdge})`);
          sheenGrad.addColorStop(1, 'rgba(255, 244, 232, 0)');

          fCtx.fillStyle = sheenGrad;
          fCtx.fillRect(0, 0, W, H);
          fCtx.restore();
        }
      }

      rafId = window.requestAnimationFrame(renderFrame);
    };

    rafId = window.requestAnimationFrame(renderFrame);

    return () => {
      if (rafId) window.cancelAnimationFrame(rafId);
    };
  }, [finalizeOnce]);

  return (
    <View style={styles.overlayRoot} pointerEvents="none">
      {/* Lienzo trasero de fuego, contornos y chispas sobre el SentinelBackground real de la app */}
      {Platform.OS === 'web' ? (
        <canvas
          ref={canvasRef}
          style={{
            position: 'fixed',
            inset: 0,
            width: '100%',
            height: '100%',
            pointerEvents: 'none',
            zIndex: 12
          }}
        />
      ) : null}

      {/* Escudo Oficial Retina limpio (sin ningún recuadro ni cristal recortado encima) */}
      {Platform.OS === 'web' ? (
        <div
          ref={logoWrapDomRef}
          style={{
            position: 'fixed',
            left: 0,
            top: 0,
            width: 156,
            height: 117,
            opacity: 0,
            pointerEvents: 'none',
            zIndex: 14,
            willChange: 'left, top, width, height, opacity'
          }}
        >
          <Image
            source={require('../../../assets/LOGO_SENTINEL.png')}
            style={{ width: '100%', height: '100%' }}
            resizeMode="contain"
          />
        </div>
      ) : null}

      {/* Lienzo frontal de pantalla completa para el reflejo diagonal de borde a borde */}
      {Platform.OS === 'web' ? (
        <canvas
          ref={frontCanvasRef}
          style={{
            position: 'fixed',
            inset: 0,
            width: '100%',
            height: '100%',
            pointerEvents: 'none',
            zIndex: 16
          }}
        />
      ) : null}
    </View>
  );
};

const styles = StyleSheet.create({
  overlayRoot: {
    ...StyleSheet.absoluteFillObject,
    zIndex: 40
  }
});
