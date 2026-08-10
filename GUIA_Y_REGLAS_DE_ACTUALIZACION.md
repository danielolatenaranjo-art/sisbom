# 📋 Guía y Reglas Estándar de Actualizaciones - Ecosistema SisBom

Este documento establece las reglas obligatorias, estándares de diseño y el protocolo de versionado para construir y desplegar actualizaciones en las distintas aplicaciones del ecosistema **SisBom**.

---

## 🎯 Filosofía General del Sistema
1. **Prioridad Absoluta a la Emergencia:** En situaciones de alarma, la velocidad, el contraste visual y la simplicidad son más importantes que la complejidad gráfica.
2. **Resiliencia de Red:** Ninguna aplicación debe bloquearse o quedar inutilizable si pierde la conexión a Internet o a Firestore.
3. **Compatibilidad Hacia Atrás:** Cambios en la estructura de base de datos (Firestore/JSON) deben mantener compatibilidad con versiones previas instaladas en terreno.
4. **Consistencia Visual:** Mantener la paleta de colores oficial SisBom, tipografías y comportamiento de la interfaz en todos los clientes.

---

## 📱 / 🖥️ Reglas Específicas por Aplicación

### 1. 🚨 `SisBom.exe` — Central de Alarmas (Desktop / Python)
* **Función Principal:** Recepción y despacho de alarmas en tiempo real, alertas sonoras, gestión de unidades y despacho multimonitor.
* **Reglas de Actualización:**
  * **Conexión Resiliente:** Forzar transporte de red estable (HTTP/2 / Long-Polling o fallback habilitado en Python/PyQt/WebEngine) para evitar fallos de paquetes UDP (`QUIC_TOO_MANY_RTOS`).
  * **Audio e Iluminación:** Prohibido modificar o alterar los archivos de audio de sirenas/tonos o los patrones de destello sin aprobación explícita de Comandancia.
  * **Thread de UI Libre:** Ninguna llamada de red o IO (Firestore, API, serial) debe ejecutarse en el hilo principal de la interfaz visual para evitar congelamientos ("No responde").
  * **Modo Multimonitor y Pantalla Completa:** Toda actualización debe probarse en configuraciones de pantalla completa y monitores secundarios de despacho.
  * **Logs Locales:** Guardar logs de errores localmente (`logs/central.log`) para diagnóstico sin interrumpir la operación del bombero/operador.

---

### 2. 🏛️ `SisBom.exe` — Comandancia (Desktop / Python)
* **Función Principal:** Gestión estratégica, bitácora de emergencias, estado global de la compañía/cuerpo, aprobación de partes y reportes.
  * **Exportación de Documentos:** Probar que la generación de reportes (PDF, Excel, partes de acto de servicio) conserve el formato institucional oficial.
  * **Regla de Cálculo de Asistencia (`estadisticas.html` y `personal.html`):**
    * Al calcular porcentajes de asistencia u desplegar historiales de un bombero en `estadisticas.html` y `personal.html`, **SOLO se deben procesar aquellas listas obligatorias en las que figure registrado activamente el `idRegistro` del bombero**.
    * Se deben omitir del total y del historial las listas creadas con anterioridad a la incorporación del bombero.

---

### 3. 📱 `MiSisBom` (`.apk` Android / `.ipa` iOS)
* **Función Principal:** App personal del bombero/voluntario para respuesta a alarmas/despachos, disponibilidad (0-8 / 0-9), consulta de órdenes y asistencia.
* **Estándar de Sonidos, Notificaciones Push y Vibración:**

  #### 🎵 Archivos de Audio Oficiales y Asignación Directa:
  * **Tonos por Clave 10-X:** `c10_0.mp3`, `c10_1.mp3`, `c10_2.mp3`, `c10_3.mp3`, `c10_4.mp3`, `c10_5.mp3`, `c10_6.mp3`, `c10_7.mp3`, `c10_8.mp3`, `c10_9.mp3`, `c10_10.mp3`, `c10_12.mp3`, `c10_15.mp3`, `c10_30.mp3`.
  * **Regla de Audios 10-X:** Cada despacho con clave `10-X` utiliza de forma **estricta y exclusiva** su archivo de audio dedicado `c10_X.mp3`. **Está prohibido usar `despacho.mp3` como fallback genérico**.
  * `alerta.mp3`: Tono dedicado para citaciones `12-10`, `6-6` y alertas Grado 3.
  * `importante.mp3`: Tono para citaciones especiales u órdenes generales.

  #### 📢 Significados y Reglas de Citaciones (12-10 y 6-6):
  * **Clave 12-10 ("Se solicita conductor para unidad X"):** 
    * **Filtros:** Solo suena si el voluntario tiene `conductor == 1` y está en estado `0-9` (Disponible).
    * **Tono:** Suena con el tono `alerta.mp3`.
  * **Clave 6-6 ("Se solicita personal para unidad X"):** 
    * **Filtros:** Solo suena si el voluntario está en estado `0-9` (Disponible).
    * **Tono:** Suena con el tono `alerta.mp3`.
  * **Comportamiento si presionó "No Asistir":** Si el bombero ha marcado "No Asistir" previamente en el despacho principal, las citaciones posteriores `12-10` o `6-6` **igual deben sonar con su tono `alerta.mp3`**, pero el tono del despacho inicial NO vuelve a sonar.

  #### ⏱️ Lógica de Temporizadores para Tonos de Despacho (1er y 2do Tono):
  * **Primer Tono (Alarma Inicial):**
    * Suena inmediatamente al recibir el despacho en tiempo real con el tono de clave `c10_X.mp3`.
    * **Comportamiento si pasaron > 2 Minutos (Caducidad):** Si han transcurrido más de **2 minutos** desde la hora del despacho (`horaDespacho` / `fechaDespacho`) por falta de señal o estar apagado el móvil, **NO debe sonar el tono fuerte de despacho inicial**. En su lugar, se emitirá una **notificación push con vibración y sonido `alerta.mp3`** utilizando el formato:
      * **Título:** `⚠️ DESPACHO DIFERIDO POR RED`
      * **Cuerpo:** `[Clave] • [Lugar]. Notificación entregada diferida por reconexión de red en el móvil.`
  * **Segundo Tono (Repetición a los 60 segundos):**
    * El segundo tono del despacho debe sonar exactamente **1 minuto (60 segundos)** después de haber sonado el primer tono.
    * **Condición de Cancelación:** El segundo tono suena **únicamente si el bombero NO ha presionado aún los botones "Asistir" ni "No Asistir"**.
    * Presionar el botón **"No Asistir"** o **"Asistir"** cancela e inhabilita inmediatamente la reproducción del segundo tono.

  #### 🚨 Grados de Alerta General:
  * **Grado de Alerta 1:** Solo genera una **notificación push normal** (sin sonido fuerte ni vibración).
  * **Grado de Alerta 2:** Genera una **notificación push + vibración**.
  * **Grado de Alerta 3:** Genera una **notificación push + vibración larga + sonido `alerta.mp3` fuerte** (forzando volumen al 100% e ignorando el silencio).

  #### 🔊 Bypass de Silencio y Volumen Máximo (Smart Alarm):
  * **Comportamiento en Alarma Crítica (Despacho / Grado 3):** La app fuerza el canal de audio al **100% (Volumen Máximo)** y cambia el modo de timbre a Normal (rompiendo el estado en silencio del sistema operativo).
  * **iOS (`.ipa`):** Utiliza `AVAudioSession` con categoría `.playback`, permitiendo sonar a volumen máximo **incluso si el iPhone tiene encendido el interruptor físico de silencio (Silent Switch)** o la pantalla está bloqueada.
  * **Restauración Automática:** Pasados 10 segundos de la alarma, la app restaura automáticamente los niveles de volumen originales del dispositivo.

  #### 🔇 Reglas Absolutas de Silencio por Estado del Voluntario:
  * **Estado `0-8` Absoluto (Fuera de Servicio / Licencia / Suspendido / Permiso):** Si el bombero está en estado `0-8` **ABSOLUTO**, **NO suena NINGUNA notificación, NO hay notificación push, ni vibración**. Bloqueo y silencio total.
  * **Estado `CDS` (Comisión de Servicio):** Silencio de audio total. No suena ningún tono de despacho ni alarma estridente, ni vibración fuerte; **únicamente recibe notificación push silenciosa sin reproducciones de sonido ni sirenas**.
  * **Silencio Instantáneo por Botones Físicos de Volumen (Android & iOS):** Cualquier tono de despacho o alarma en reproducción (`c10_X.mp3`, `despacho.mp3`, `alerta.mp3`, etc.) se detiene e insonoriza de inmediato al presionar **cualquiera de los botones físicos de volumen del teléfono** (Subir o Bajar Volumen, o Mute) y se cancela la vibración activa sin alterar el nivel de volumen global.
  * **Operador Central Activo:** Si el usuario es el operador activo en la Central (`isCentral == true`), su app móvil desactiva la alarma sonora para no interferir con la consola de despacho.
  * **Push Silencioso y Notificación Visual por Cambio de Estado (Data-Only Push):** Cuando la Central de Alarmas cambia el estado de un bombero (ej: a `CDS`, `0-8`, `0-9`), se emitirá un **Data-Only FCM Push** al tópico `usuario_[idRegistro]`. Al recibirlo, la app móvil en Android e iOS actualiza la caché e interfaz de usuario de inmediato en segundo plano, genera la tarjeta visual *"ESTADO ACTUALIZADO"*, emite vibración sin ruido estridente y aplica el **LargeIcon** correspondiente al escudo del Cuerpo/Compañía según la licencia activa.
  * **Limpieza Automática de Notificaciones al Abrir la App:** Al abrir o desbloquear la aplicación (`onResume`), se eliminan automáticamente todas las notificaciones pendientes en la barra de estado del teléfono (`NotificationManager.cancelAll()`).
  * **Mapeo de Audios de Clave 9-0:** La clave `9-0` o `9.0` (Servicios Especiales) reproduce el tono dedicado **`c10_9.mp3`** (o `despacho.mp3`), prohibiendo el uso de `alerta.mp3` como fallback para despachos.

  #### 📳 Patrón de Vibración:
  * **Android (`.apk`):** Patrón largo para Grado 3 / Despacho: `longArrayOf(0, 1000, 300, 1000, 300, 1000)`. Vibración corta para Grado 2.
  * **iOS (`.ipa`):** `UIImpactFeedbackGenerator(style: .heavy)` + `AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)`.

  #### 📊 Pestaña "Asistencia" (Cálculos y Estilos Visuales):
  * **Color Badge de Estados:**
    * `ASISTE`: Verde (`.goGreen` / `#10B981`)
    * `FALTA`: Rojo (`.bomberosRed` / `#EF4444`)
    * `PERMISO`: Amarillo (`.alertAmber` / `#F59E0B`)
    * `LICENCIA`: Azul (`.infoBlue` / `#3B82F6`)
    * **`SUSPENDIDO`:** **Morado / Purple (`#8B5CF6` / `.purple`)**. Prohibido usar gris o rojo.
  * **Ubicación del Número de Lista en la Tarjeta de Asistencia:**
    * **Tarjeta Principal:** Muestra únicamente la clave del acto (ej: `10-0`). Prohibido mostrar `#15` o el número de lista en el encabezado principal de la tarjeta.
    * **Modal de Detalles (al pulsar la tarjeta):** Al presionar cualquier elemento de la lista, el modal desplegable de detalles muestra el **número de lista limpio sin el prefijo del ciclo/año** *(ej: `Nº Lista: #15`)*.
  * **Filtrado Visual y Cálculo de Asistencia (Solo listas donde figura el `idRegistro`):**
    * **No Mostrar Listas Previas:** En la pantalla del historial de asistencias **NO se deben mostrar las listas obligatorias donde el bombero no tiene información** (listas anteriores a su ingreso en las que su `idRegistro` no está incluido).
    * **Cálculo de Porcentaje:** El porcentaje de asistencia obligatoria se calcula **únicamente sobre las listas obligatorias donde figura el `idRegistro` del bombero**, garantizando un cálculo justo y una vista limpia del historial.

---

### 4. ⏱️ `Asistencia.apk` (Tablet / Tótem de Cuartel)
* **Función Principal:** Registro de guardia y asistencia presencial en el cuartel mediante QR, RUT, PIN o Biometría.
* **Reglas de Actualización:**
  * **Modo Kiosco / Pantalla Encendida:** La app debe mantener la pantalla encendida permanentemente (*Keep Screen On*) y evitar salidas accidentales mediante gestos.
  * **Modo Offline con Sincronización:** Si el Wi-Fi del cuartel se cae, la app DEBE guardar las marcas de asistencia de forma local en la memoria de la Tablet y sincronizarlas automáticamente cuando la red se restablezca.
  * **Velocidad de Marcación:** El proceso de registro de un bombero no debe tomar más de 2 segundos.
  * **Interfaz de Alto Impacto:** Botones grandes, legibles a distancia y respuesta sonora/visual clara para marcar llegada o salida.

---

## 🔄 Guía Paso a Paso para Construir una Actualización

### Paso 1: Regla General Estricta de Versionado Semántico (`MAJOR.MINOR.PATCH`)
Cada vez que se prepare una actualización, se debe seguir la siguiente regla estricta de incremento:

* **Corrección de Errores / Hotfix (`PATCH` → `X.X.+1`):** 
  * Aplicable a solución de bugs, parches de estabilidad o correcciones menores. 
  * *Ejemplo:* Si la versión actual es `1.0.0`, se debe compilar como **`1.0.1`**.
* **Novedad / Funcionalidad Nueva (`MINOR` → `X.+1.0`):** 
  * Aplicable a nuevas características, nuevas pantallas o funciones agregadas. 
  * *Ejemplo:* De `1.0.1` se pasa a **`1.1.0`**.
* **Cambio Radical / App Renovada (`MAJOR` → `+1.0.0`):** 
  * Aplicable a rediseños completos, reconstrucción de arquitectura o cambios radicales. 
  * *Ejemplo:* De `1.2.5` se pasa a **`2.0.0`**.

📌 **INDEPENDENCIA DE VERSIÓN POR APLICACIÓN:**  
Cada aplicación del ecosistema (`SisBom.exe`, `MiSisBom`, `Asistencia.apk`) mantiene su **propio historial de versión totalmente independiente**. Un parche en `MiSisBom` incrementa únicamente la versión de `MiSisBom` sin afectar o forzar la actualización de versión en `SisBom.exe` ni en `Asistencia.apk`.

⚠️ **REGLA DE SINCRONIZACIÓN INTERNA DE VERSIÓN:**  
Al incrementar la versión de una app específica, es **OBLIGATORIO** actualizar el nuevo número en **TODOS los archivos pertenecientes a esa aplicación**, por ejemplo:
- **Si se actualiza `SisBom.exe` (Central y Comandancia):** Sincronizar versión en `main.py`, `build.py`, `package.json`, footers HTML y changelog de SisBom Desktop.
- **Si se actualiza `MiSisBom` (iOS & Android):** Sincronizar simultáneamente la misma versión en `build.gradle.kts` (`versionName` y `versionCode`) (Android), `Info.plist` / `project.yml` (iOS), vistas de ajustes/acerca de y `SisBomViewModel`.
- **Si se actualiza `Asistencia.apk`:** Sincronizar versión en `build.gradle` y pantallas del tótem.

### Paso 2: Lista de Comprobación Pre-Release (Checklist)
Antes de compilar la versión final:
- [ ] ¿Se probaron las notificaciones/tonos de alarma?
- [ ] ¿La conexión a Firestore se recupera tras cortar y reconectar el Wi-Fi?
- [ ] ¿Se verificó que los roles de usuario sigan funcionando correctamente?
- [ ] ¿La interfaz se adapta a distintas resoluciones de pantalla?
- [ ] ¿Se generaron los ejecutables (`build.py` para `.exe`) y los paquetes móviles sin advertencias críticas?

### Paso 3: Registro de Cambios (`CHANGELOG.md`)
Toda actualización debe ir acompañada de una breve nota con:
* **Fecha y Versión**
* **Novedades / Mejoras**
* **Errores Corregidos**
* **Módulos Afectados** (Central, Comandancia, MiSisBom o Asistencia)

---

## 📌 Reglas de Preservación de Código (Para Desarrolladores e IA)
1. **No alterar contratos de datos en Firestore** sin crear un script de migración previo.
2. **No remover librerías de respaldo (fallback)** en las conexiones de red en Python (`SisBom`).
3. **Mantener comentarios explicativos** en secciones críticas de despacho, audio y sincronización.
