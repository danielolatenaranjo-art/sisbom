# SENTINEL ONE - App Móvil Bomberos y Maquinistas (React Native / Expo)

> [!IMPORTANT]
> **CONTRATO DE INTEROPERABILIDAD Y SINCRONIZACIÓN CON SENTINEL CAD**:
> Toda la lógica de datos, despachos, lectura de solicitudes 12-10 (conductor urgente) y 6-6 (personal de refuerzo), geolocalización `geo` y asistencia debe cumplir estrictamente con:
> - [`CONTRATO_INTEROPERABILIDAD_SENTINEL_ONE.md`](file:///c:/Users/danie/Desktop/SENTINEL%20V3/SHARED/CONTRATO_INTEROPERABILIDAD_SENTINEL_ONE.md)
> - [`ESQUEMA_CANONICO_V3.md`](file:///c:/Users/danie/Desktop/SENTINEL%20V3/SHARED/ESQUEMA_CANONICO_V3.md)
> 
> **Reglas Clave para SENTINEL ONE**:
> 1. **Construcción Dual Obligatoria (iOS + Android) y Compilación Bajo Demanda**:
>    - **Siempre que trabajes en `SENTINEL ONE`**, debes construir y sincronizar el bundle simultáneamente para **iOS (`ios/Resources/www` + archivos nativos `ios/`)** y **Android (`android/app/src/main/assets/www` + archivos nativos `android/`)** ejecutando `npm run prepare:platforms` (`node build-apk.mjs --prepare-only`), asegurando paridad 100% entre ambas plataformas en todo cambio.
>    - **Cuando el usuario te pida compilar**, compila **únicamente para el sistema que el usuario te pida**:
>      - Si pide **Android**: ejecuta `node build-apk.mjs` (o `npm run build:apk`) para generar `TERMINADOS/SENTINEL_ONE_V3.apk` y `TERMINADOS/SENTINEL_ONE_V3.aab`.
>      - Si pide **iOS**: sincroniza `ios/Resources/www` (`node build-apk.mjs --prepare-only`), incrementa `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` en `ios/project.yml` si corresponde, y ejecuta el workflow de compilación iOS (`.github/workflows/sentinel-one-ios.yml` / GitHub Actions TestFlight/App Store).
>      - Si pide **ambos**, compila ambos sistemas.
> 2. **12-10 y 6-6**: Se leen desde `despachos/{idServicio}.unidades[carro].solicitudConductorTimestamp` y `solicitudPersonalTimestamp`. Si `Date.now() - timestamp < 60000`, la solicitud está activa (cooldown 1 minuto).
> 3. **Geolocalización**: Leer únicamente `despachos/{idServicio}.geo: { lat, lng, accuracy, origen }`.
> 4. **Asistencias**: Al responder asistencia, escribir en `despachos/{idServicio}/asistencias/{idRegistro}` y actualizar `personal/{idRegistro}.enServicio`.

This is an Expo/React Native mobile application. Prioritize mobile-first patterns, performance, and cross-platform compatibility.

## Expo has changed — do not trust your training data

Expo ships breaking changes every SDK release. APIs you remember are likely renamed, moved, or removed. Before writing any code that touches an Expo, EAS, or React Native API:

1. Read the major version of the `expo` package in `package.json`.
2. Fetch the matching versioned docs: `https://docs.expo.dev/versions/v<major>.0.0/`
3. For anything else, fetch https://docs.expo.dev/llms.txt — an index of all Expo docs with corrections to common LLM misconceptions. Follow its links to the specific page you need; never answer from memory.

## Commands

Use `bunx` instead of `npx` if the project uses bun (`bun.lock` present).

```bash
npx expo install <package>  # ALWAYS use instead of npm/yarn/pnpm/bun add — resolves SDK-compatible versions
npx expo start              # start the dev server
npx expo lint               # lint
npx tsc --noEmit            # typecheck
npx expo-doctor             # diagnose dependency and config issues
npx expo install --fix      # fix incompatible package versions
```

Run lint and typecheck before declaring any task done.

## Navigation & Routing

- Use **Expo Router** for all navigation. Routes live in `src/app/` — every file there is a screen, `_layout.tsx` files define navigators. Keep non-route code (components, hooks, utils) outside `src/app/`.
- Import `Link`, `router`, and `useLocalSearchParams` from `expo-router`.
- Docs: https://docs.expo.dev/router/introduction.md

## Building with EAS

Use EAS to build, sign, and submit the app in the cloud (`eas build`, `eas submit`) and to ship over-the-air updates (`eas update`) — no local Xcode or Android Studio required. Run EAS CLI as `bunx eas-cli <command>` in Bun projects, or `npx eas-cli@latest <command>` otherwise; substitute that for bare `eas` in docs examples.
Docs: https://docs.expo.dev/eas/index.md

## Rules

- If `ios/` and `android/` directories do not exist, they are generated (Continuous Native Generation). Never create or edit them by hand — configure native behavior in `app.json` and config plugins.
- Expo Go only includes its bundled native modules. After adding a library with native code, the app needs a development build: `npx expo run:ios|android` locally, or `eas build --profile development`.
- Prefer recommended Expo modules over third-party libraries, and check your available skills before adding dependencies. Docs: https://docs.expo.dev/versions/latest/index.md
