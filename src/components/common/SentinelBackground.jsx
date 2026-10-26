import React, { useEffect, useRef } from 'react';
import { View, StyleSheet, Platform } from 'react-native';

export const SentinelBackground = ({
  children,
  stage = 'login',
  theme = 'dark',
  windSpeed = 0.85,
  particleDensity = 1.9,
  lightIntensity = 1.4,
  emergencyPulseMode = 'atmospheric'
}) => {
  const canvasRef = useRef(null);
  const propsRef = useRef({ stage, theme, windSpeed, particleDensity, lightIntensity, emergencyPulseMode });
  const burstTriggeredRef = useRef(false);

  useEffect(() => {
    propsRef.current = { stage, theme, windSpeed, particleDensity, lightIntensity, emergencyPulseMode };
  }, [stage, theme, windSpeed, particleDensity, lightIntensity, emergencyPulseMode]);

  useEffect(() => {
    if (Platform.OS !== 'web') return;
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    let animationFrameId;

    let width = (canvas.width = window.innerWidth);
    let height = (canvas.height = window.innerHeight);

    const handleResize = () => {
      width = canvas.width = window.innerWidth;
      height = canvas.height = window.innerHeight;
    };
    window.addEventListener('resize', handleResize);

    const baseCount = Math.min(34, Math.floor((width * height) / 24000));
    const totalParticles = Math.min(54, Math.max(28, Math.floor(baseCount * particleDensity)));

    const sparkColors = [
      {
        core: '#fffbeb',
        mid: '#f59e0b',
        glow: 'rgba(245, 158, 11, 0.85)',
        lightCore: '#fef08a',
        lightRing: '#ea580c',
        lightGlow: 'rgba(234, 88, 12, 0.75)'
      },
      {
        core: '#fef08a',
        mid: '#ea580c',
        glow: 'rgba(234, 88, 12, 0.9)',
        lightCore: '#fde047',
        lightRing: '#dc2626',
        lightGlow: 'rgba(220, 38, 38, 0.78)'
      },
      {
        core: '#fed7aa',
        mid: '#dc2626',
        glow: 'rgba(220, 38, 38, 0.85)',
        lightCore: '#ffedd5',
        lightRing: '#b91c1c',
        lightGlow: 'rgba(185, 28, 28, 0.72)'
      }
    ];

    const lightAshColors = ['#1e293b', '#334155', '#475569', '#0f172a', '#292524'];

    const particles = Array.from({ length: totalParticles }, () => {
      const isSpark = Math.random() > 0.35;
      // En modo claro, una parte de las partículas son pavesas/cenizas de carbón y otra parte chispas incandescentes
      const isLightAsh = !isSpark || Math.random() < 0.38;
      const depth = Math.random() * 0.85 + 0.15;
      return {
        x: Math.random() * width,
        y: Math.random() * height,
        z: depth,
        isSpark,
        isLightAsh,
        size: isSpark ? (Math.random() * 2.2 + 0.8) * depth : (Math.random() * 3.6 + 1.6) * depth,
        vx: (Math.random() * 0.22 + 0.06) * depth,
        vy: -(Math.random() * 0.45 + 0.15) * depth,
        turbulenceOffset: Math.random() * 100,
        turbulenceFreq: Math.random() * 0.008 + 0.003,
        flickerPhase: Math.random() * Math.PI * 2,
        flickerSpeed: Math.random() * 0.05 + 0.02,
        rotation: Math.random() * Math.PI * 2,
        rotationSpeed: (Math.random() - 0.5) * 0.015,
        aspectRatio: Math.random() * 0.5 + 0.4,
        colorScheme: sparkColors[Math.floor(Math.random() * sparkColors.length)],
        lightAshColor: lightAshColors[Math.floor(Math.random() * lightAshColors.length)],
        hasHotRim: Math.random() > 0.45,
        baseAlpha: isSpark ? Math.random() * 0.55 + 0.45 : Math.random() * 0.35 + 0.15,
        isBurst: false,
        dead: false
      };
    });

    const windStreaks = [];
    const spawnWindStreaks = () => {
      for (let i = 0; i < 24; i++) {
        windStreaks.push({
          x: Math.random() * width * 0.7 - 200,
          y: Math.random() * height,
          len: Math.random() * 240 + 120,
          speed: Math.random() * 32 + 22,
          alpha: Math.random() * 0.4 + 0.2,
          thickness: Math.random() * 2.0 + 0.7
        });
      }
    };

    const spawnLoginCardBurst = () => {
      const cardCenterX = width / 2;
      const cardCenterY = height / 2;
      const burstCount = 92;

      for (let i = 0; i < burstCount; i++) {
        const isSpark = Math.random() > 0.25;
        const isLightAsh = !isSpark || Math.random() < 0.35;
        const depth = Math.random() * 0.75 + 0.35;
        const offsetX = (Math.random() - 0.5) * Math.min(360, width * 0.7);
        const offsetY = (Math.random() - 0.5) * Math.min(420, height * 0.7);
        const vx = (Math.random() * 16 + 12) * depth;
        const vy = -(Math.random() * 5 + 1.2) * depth;

        particles.push({
          x: cardCenterX + offsetX,
          y: cardCenterY + offsetY,
          z: depth,
          isSpark,
          isLightAsh,
          size: isSpark ? (Math.random() * 2.8 + 1.2) * depth : (Math.random() * 4.2 + 2.0) * depth,
          vx,
          vy,
          turbulenceOffset: Math.random() * 100,
          turbulenceFreq: Math.random() * 0.03 + 0.015,
          flickerPhase: Math.random() * Math.PI * 2,
          flickerSpeed: Math.random() * 0.22 + 0.08,
          rotation: Math.random() * Math.PI * 2,
          rotationSpeed: (Math.random() - 0.5) * 0.08,
          aspectRatio: Math.random() * 0.5 + 0.4,
          colorScheme: sparkColors[Math.floor(Math.random() * sparkColors.length)],
          lightAshColor: lightAshColors[Math.floor(Math.random() * lightAshColors.length)],
          hasHotRim: Math.random() > 0.4,
          baseAlpha: isSpark ? Math.random() * 0.45 + 0.55 : Math.random() * 0.35 + 0.2,
          isBurst: true,
          dead: false
        });
      }

      spawnWindStreaks();
    };

    let time = 0;
    let fadeStartTime = null;
    let lastHandledStage = 'login';

    const render = (now) => {
      if (typeof document !== 'undefined' && document.hidden) {
        animationFrameId = requestAnimationFrame(render);
        return;
      }
      const currentStage = propsRef.current.stage;
      const isLightTheme = propsRef.current.theme === 'light';
      const currentEmergencyMode = propsRef.current.emergencyPulseMode;
      const currentLightIntensity = propsRef.current.lightIntensity;
      const baseWindSpeed = propsRef.current.windSpeed;

      if (currentStage !== lastHandledStage) {
        if (currentStage === 'wind-gust' && !burstTriggeredRef.current) {
          burstTriggeredRef.current = true;
          spawnLoginCardBurst();
        }
        if (currentStage === 'particles-fade') {
          fadeStartTime = now;
        }
        if (currentStage === 'logout-emerge-login') {
          fadeStartTime = now;
        }
        if (currentStage === 'login') {
          burstTriggeredRef.current = false;
          fadeStartTime = null;
        }
        lastHandledStage = currentStage;
      }

      if (currentStage === 'cad' || currentStage === 'logout-fade-cad') {
        ctx.fillStyle = isLightTheme ? '#F8FAFC' : '#060911';
        ctx.fillRect(0, 0, width, height);
        animationFrameId = requestAnimationFrame(render);
        return;
      }

      time += 0.016;

      let globalFadeAlpha = 1.0;
      if (currentStage === 'particles-fade' && fadeStartTime) {
        const elapsed = now - fadeStartTime;
        globalFadeAlpha = Math.max(0, 1 - elapsed / 580);
      } else if (currentStage === 'logout-emerge-login' && fadeStartTime) {
        const elapsed = now - fadeStartTime;
        globalFadeAlpha = Math.min(1, elapsed / 650);
      } else if (currentStage === 'cad-emerge' || currentStage === 'cad' || currentStage === 'logout-fade-cad') {
        globalFadeAlpha = 0;
      }

      // Base del cielo / atmósfera (Modo Oscuro #060911 vs Modo Claro Táctico #F8FAFC -> #E2E8F0)
      if (isLightTheme) {
        const skyGrad = ctx.createLinearGradient(0, 0, 0, height);
        skyGrad.addColorStop(0, '#F8FAFC');
        skyGrad.addColorStop(0.28, '#F8FAFC');
        skyGrad.addColorStop(0.68, '#F1F5F9');
        skyGrad.addColorStop(1, '#E2E8F0');
        ctx.fillStyle = skyGrad;
        ctx.fillRect(0, 0, width, height);
      } else {
        ctx.fillStyle = '#060911';
        ctx.fillRect(0, 0, width, height);
      }

      if (globalFadeAlpha <= 0.001) {
        animationFrameId = requestAnimationFrame(render);
        return;
      }

      const speedMultiplier = currentEmergencyMode === 'siren' ? 3.4 : 0.85;
      const t = time * speedMultiplier;
      const effectiveLightIntensity = currentLightIntensity * globalFadeAlpha;

      // 1. Luz Ambiental Roja Volumétrica + Resplandor Térmico Inferior en Modo Claro
      if (effectiveLightIntensity > 0.01) {
        const redPulse =
          currentEmergencyMode === 'siren'
            ? (Math.sin(t * 2.2) + 1) * 0.5
            : Math.sin(t) * 0.35 + 0.65;
        const redX = width * (0.28 + Math.sin(time * 0.35) * 0.12);
        const redY = height * (0.46 + Math.cos(time * 0.28) * 0.14);
        const redGrad = ctx.createRadialGradient(
          redX,
          redY,
          10,
          redX,
          redY,
          Math.max(width, height) * 0.58
        );
        const redAlpha = (isLightTheme ? 0.14 : 0.19) * effectiveLightIntensity * redPulse;
        redGrad.addColorStop(0, `rgba(220, 38, 38, ${redAlpha})`);
        redGrad.addColorStop(1, isLightTheme ? 'rgba(248, 250, 252, 0)' : 'rgba(6, 9, 17, 0)');
        ctx.fillStyle = redGrad;
        ctx.fillRect(0, 0, width, height);

        // 2. Luz Ambiental Azul Volumétrica
        const bluePulse =
          currentEmergencyMode === 'siren'
            ? (Math.sin(t * 2.2 + Math.PI) + 1) * 0.5
            : Math.cos(t * 0.9) * 0.35 + 0.65;
        const blueX = width * (0.72 + Math.cos(time * 0.32) * 0.12);
        const blueY = height * (0.58 + Math.sin(time * 0.25) * 0.14);
        const blueGrad = ctx.createRadialGradient(
          blueX,
          blueY,
          10,
          blueX,
          blueY,
          Math.max(width, height) * 0.58
        );
        const blueAlpha = (isLightTheme ? 0.12 : 0.18) * effectiveLightIntensity * bluePulse;
        blueGrad.addColorStop(0, `rgba(37, 99, 235, ${blueAlpha})`);
        blueGrad.addColorStop(1, isLightTheme ? 'rgba(248, 250, 252, 0)' : 'rgba(6, 9, 17, 0)');
        ctx.fillStyle = blueGrad;
        ctx.fillRect(0, 0, width, height);

        // 3. En Modo Claro: Resplandor cálido de brasas en el horizonte inferior
        if (isLightTheme) {
          const horizonPulse = Math.sin(t * 0.7) * 0.25 + 0.75;
          const horizonGrad = ctx.createRadialGradient(
            width * 0.5,
            height * 1.02,
            10,
            width * 0.5,
            height * 1.02,
            Math.max(width, height) * 0.52
          );
          horizonGrad.addColorStop(0, `rgba(234, 88, 12, ${0.13 * horizonPulse * globalFadeAlpha})`);
          horizonGrad.addColorStop(0.55, `rgba(245, 158, 11, ${0.06 * horizonPulse * globalFadeAlpha})`);
          horizonGrad.addColorStop(1, 'rgba(248, 250, 252, 0)');
          ctx.fillStyle = horizonGrad;
          ctx.fillRect(0, 0, width, height);
        }
      }

      const isGusting = currentStage === 'wind-gust' || currentStage === 'particles-fade';
      const windMultiplier = isGusting ? 8.5 : 1.0;
      const globalWind = (Math.sin(time * 0.45) * 0.5 + 0.85) * baseWindSpeed * windMultiplier;

      if (isGusting && windStreaks.length > 0) {
        windStreaks.forEach((st) => {
          st.x += st.speed;
          st.y -= st.speed * 0.14;

          if (st.x < width + 300) {
            ctx.save();
            const grad = ctx.createLinearGradient(st.x, st.y, st.x + st.len, st.y - st.len * 0.14);
            if (isLightTheme) {
              grad.addColorStop(0, 'rgba(234, 88, 12, 0)');
              grad.addColorStop(0.5, `rgba(220, 38, 38, ${st.alpha * globalFadeAlpha})`);
              grad.addColorStop(1, 'rgba(71, 85, 105, 0)');
            } else {
              grad.addColorStop(0, 'rgba(255, 180, 100, 0)');
              grad.addColorStop(0.5, `rgba(254, 215, 170, ${st.alpha * globalFadeAlpha})`);
              grad.addColorStop(1, 'rgba(255, 240, 200, 0)');
            }
            ctx.strokeStyle = grad;
            ctx.lineWidth = st.thickness;
            ctx.beginPath();
            ctx.moveTo(st.x, st.y);
            ctx.lineTo(st.x + st.len, st.y - st.len * 0.14);
            ctx.stroke();
            ctx.restore();
          }
        });
      }

      const allowEdgeRespawn = currentStage === 'login';

      particles.forEach((p) => {
        if (p.dead) return;

        const currentVx =
          (p.vx + (isGusting ? globalWind * 0.85 : globalWind * 0.15)) * p.z +
          Math.sin(p.flickerPhase * 0.25 + p.turbulenceOffset) * (isGusting ? 0.65 : 0.14);
        const currentVy = p.vy - (isGusting ? Math.abs(globalWind) * 0.22 : 0.04);

        p.x += currentVx;
        p.y += currentVy;
        p.flickerPhase += p.flickerSpeed;
        p.rotation += p.rotationSpeed;

        if (p.y < -40 || p.x > width + 40 || p.x < -40 || p.y > height + 40) {
          if (allowEdgeRespawn) {
            if (p.y < -30) {
              p.y = height + 20;
              p.x = Math.random() * width;
            }
            if (p.x > width + 30) p.x = -25;
            if (p.x < -30) p.x = width + 25;
          } else {
            p.dead = true;
            return;
          }
        }

        ctx.save();
        const flicker = 0.65 + 0.35 * Math.sin(p.flickerPhase);

        if (isLightTheme) {
          // En la franja superior del logo (y < 95px), suavizar partículas para máxima nitidez de SENTINEL | ONE
          const headerFade = p.y < 95 ? Math.max(0.25, p.y / 95) : 1.0;

          if (!p.isLightAsh) {
            // Chispa / Pavesa Incandescente Diurna sin crear CanvasGradient por frame
            const baseA = Math.max(0, Math.min(1, (p.baseAlpha * 1.15) * flicker * globalFadeAlpha * headerFade));

            if (isGusting && (p.isBurst || windMultiplier > 2)) {
              ctx.globalAlpha = baseA;
              ctx.strokeStyle = p.colorScheme.lightRing;
              ctx.lineWidth = Math.max(1, p.size * 0.9);
              ctx.beginPath();
              ctx.moveTo(p.x, p.y);
              ctx.lineTo(p.x - currentVx * 2.2, p.y - currentVy * 2.2);
              ctx.stroke();
            }

            ctx.globalAlpha = baseA * 0.25;
            ctx.fillStyle = p.colorScheme.lightRing;
            ctx.beginPath();
            ctx.arc(p.x, p.y, p.size * 2.6, 0, Math.PI * 2);
            ctx.fill();

            ctx.globalAlpha = baseA;
            ctx.fillStyle = p.colorScheme.lightRing;
            ctx.beginPath();
            ctx.arc(p.x, p.y, p.size * 1.05, 0, Math.PI * 2);
            ctx.fill();

            ctx.fillStyle = p.colorScheme.lightCore;
            ctx.beginPath();
            ctx.arc(p.x, p.y, p.size * 0.52, 0, Math.PI * 2);
            ctx.fill();
          } else {
            // Ceniza / Pavesa de Carbón Volando en Modo Claro
            const ashAlpha = Math.min(0.82, (p.baseAlpha * 1.75 + 0.18) * flicker * globalFadeAlpha * headerFade);
            ctx.globalAlpha = Math.max(0, ashAlpha);
            ctx.translate(p.x, p.y);
            ctx.rotate(p.rotation);

            const ashRx = Math.max(1.4, p.size * 1.18);
            const ashRy = Math.max(0.9, p.size * p.aspectRatio * 1.18);

            ctx.fillStyle = p.lightAshColor;
            ctx.beginPath();
            ctx.ellipse(0, 0, ashRx, ashRy, 0, 0, Math.PI * 2);
            ctx.fill();

            if (p.hasHotRim) {
              ctx.strokeStyle = 'rgba(234, 88, 12, 0.75)';
              ctx.lineWidth = 0.7;
              ctx.stroke();
            }
          }
        } else {
          // Modo Oscuro Original optimizado (cero asignaciones de gradiente por partícula)
          const baseA = Math.max(0, Math.min(1, p.baseAlpha * flicker * globalFadeAlpha));

          if (p.isSpark) {
            if (isGusting && (p.isBurst || windMultiplier > 2)) {
              ctx.globalAlpha = baseA;
              ctx.strokeStyle = p.colorScheme.glow;
              ctx.lineWidth = Math.max(1, p.size * 0.85);
              ctx.beginPath();
              ctx.moveTo(p.x, p.y);
              ctx.lineTo(p.x - currentVx * 2.2, p.y - currentVy * 2.2);
              ctx.stroke();
            }

            ctx.globalAlpha = baseA * 0.24;
            ctx.fillStyle = p.colorScheme.mid;
            ctx.beginPath();
            ctx.arc(p.x, p.y, p.size * 2.6, 0, Math.PI * 2);
            ctx.fill();

            ctx.globalAlpha = baseA;
            ctx.fillStyle = p.colorScheme.core;
            ctx.beginPath();
            ctx.arc(p.x, p.y, p.size * 0.75, 0, Math.PI * 2);
            ctx.fill();
          } else {
            ctx.globalAlpha = baseA;
            ctx.translate(p.x, p.y);
            ctx.rotate(p.rotation);
            ctx.fillStyle = '#475569';
            ctx.beginPath();
            ctx.ellipse(0, 0, p.size, p.size * p.aspectRatio, 0, 0, Math.PI * 2);
            ctx.fill();
          }
        }
        ctx.restore();
      });

      animationFrameId = requestAnimationFrame(render);
    };

    animationFrameId = requestAnimationFrame(render);

    return () => {
      window.removeEventListener('resize', handleResize);
      cancelAnimationFrame(animationFrameId);
    };
  }, [particleDensity]);

  return (
    <View
      style={[
        styles.container,
        { backgroundColor: theme === 'light' ? '#F8FAFC' : '#060911' }
      ]}
    >
      {Platform.OS === 'web' ? (
        <canvas
          ref={canvasRef}
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            width: '100%',
            height: '100%',
            pointerEvents: 'none',
            zIndex: 0
          }}
        />
      ) : null}
      <View style={styles.content}>{children}</View>
    </View>
  );
};

export default SentinelBackground;

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#060911',
    width: '100%',
    height: '100%',
    position: 'relative',
    overflow: 'hidden'
  },
  content: {
    flex: 1,
    zIndex: 10
  }
});
