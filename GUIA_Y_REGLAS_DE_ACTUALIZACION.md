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

---

### 4. 🚒 `SisBomCar.apk` (Tablet / Consola Táctica de a bordo de la unidad)
* **Función Principal:** Consola de a bordo montada en el vehículo bomberil (CarPlay / Tablet táctica).
* **Deprecación:** Sustituye definitivamente a `MiMaterialMayor.apk`.
* **Regla de Equivalencia:** Funciona **exactamente como la tarjeta individual de la unidad en `materialMayor.html`**:
  * **Salidas 6-13 / 6-14 extraordinarias:** Se registran con ID correlativo numérico entero en `bitacora` (`idSalida: "1"`, `"2"`, `"14"`).
  * **Ciclo Operativo Táctico:**
    1. **`pending_departure`**: Botón **`🚒 6-0 EN TRAYECTO`** (requiere conductor y OBAC; si faltan, abre modal para asignarlos).
    2. **`en_trayecto` (6-0)**: Botón **`📍 6-3 EN EL LUGAR`**.
    3. **`en_lugar` (6-3)**: Botones **`🔄 6-9 RETORNO`**, **`🚒 6-13 10-X`** y **`🏥 6-15 SALUD`**.
    4. **`retorno` (6-9)**: Botón **`🏢 6-10 EN CUARTEL`**.
    5. **`en_cuartel` (6-10)**: Botón **`🏁 6-8 FIN DE SERVICIO`** (abre diálogo de Odómetro final en KM, actualiza `bitacora` con `hora68`/`fecha68` y libera la unidad a `0-9` o `0-8`).

---

### 5. ⏱️ `Lista.apk` (Tablet / Tótem de Cuartel)
* **Función Principal:** Registro de asistencia presencial y guardias de los bomberos.
* **Deprecación:** Sustituye definitivamente a `Asistencia.apk`.
* **Reglas de Actualización:**
  * **Modo Kiosco / Pantalla Encendida:** La app debe mantener la pantalla encendida permanentemente (*Keep Screen On*) y evitar salidas accidentales mediante gestos.
  * **Modo Offline con Sincronización:** Si el Wi-Fi del cuartel se cae, la app DEBE guardar las marcas de asistencia de forma local en la memoria de la Tablet y sincronizarlas automáticamente cuando la red se restablezca.
  * **Velocidad de Marcación:** El proceso de registro de un bombero no debe tomar más de 2 segundos.
  * **Estandarización de Abono:** Debe escribir `abono: "SÍ"` / `"NO"` y `esAbono: 1` / `0`.

---

## 🗄️ Diccionario Canónico de Campos (Esquemas Firestore)

### 1. `bitacora` (Historial y Salidas Operativas)
* **ID de documento:** Correlativo entero como String (`"1"`, `"2"`, ..., `"14"`).
* **Campos:**
  - `idSalida`, `id`, `ID`, `idRegistro` (`String`): ID numérico correlativo.
  - `idServicio` (`String`): ID de despacho central o `""` si es salida extraordinaria.
  - `carro`, `idCarro` (`String`): Código del carro (ej: `"B1"`).
  - `clave` (`String`): `"10-2"`, `"6-13"`, `"6-14"`.
  - `lugar` (`String`): Dirección o destino.
  - `preInforme` (`String`): Pre-informe / motivo.
  - `informe63` (`String`): Informe radial 6-3.
  - `observacion` (`String`): Observación de cierre.
  - `conductor` (`String`): `"1 - CRISTIAN LOPEZ MELLA"`.
  - `obac` (`String`): `"2 - JUAN MARTINEZ CORNEJO"`.
  - `cuantosBomberos` (`String`): Conteo de dotación (`"0"`, `"4"`).
  - `tripulantes` (`String`): Resumen de dotación.
  - `tripulantesNombres` (`String`): Nombres concatenados.
  - `tripulantesDetalle` (`Array[Map]`): Lista de objetos bomberos.
  - `fecha60`, `hora60`, `fecha63`, `hora63`, `fecha69`, `hora69`, `fecha610`, `hora610`, `fecha68`, `hora68` (`String`): Tiempos en `dd-MM-yyyy` y `HH:mm`.
  - `km` (`String`): Odómetro final en kilómetros.
  - `estadoMovil` (`String`): `"en trayecto"`, `"en el lugar"`, `"retorno"`, `"en cuartel"`, `"en servicio"`.
  - `timestamp` (`Number`): Epoch ms.
* **Subcolección:** `bitacora/{idSalida}/tripulantes/{idRegistroBombero}`.

### 2. `despachos` (Servicios Activos y Finalizados)
* **ID de documento:** Correlativo entero como String (`"34"`, `"35"`, `"41"`).
* **Campos Principales:** `id`, `ID`, `idRegistro`, `idServicio`, `clave`, `claveApoyo`, `lugar`, `preinforme`, `informeObac`, `estado`, `fechaDespacho`, `horaDespacho`, `fechaTermino`, `horaTermino`, `fecha67`, `hora67`, `operadorInicial`, `operadorFinal`, `carros`, `carrosTexto`, `geo`, `geolocalizacionAlertante`, `pushSent`, `visibleMovil`, `solicitarConfirmacion`.
* **Mapa Anidado `unidades.<carro>`:**
  ```json
  "unidades": {
    "B1": {
      "estado": "en_lugar",
      "conductor": "1 - CRISTIAN LOPEZ MELLA",
      "driverRad": "1",
      "obac": "2 - JUAN MARTINEZ CORNEJO",
      "obacRad": "2",
      "count": "0",
      "cuantosBomberos": "0",
      "tripulantes": ["CRISTIAN LOPEZ MELLA"],
      "tripulantesNombres": "1 CRISTIAN LOPEZ MELLA",
      "tripulantesDetalle": [],
      "horaSalida": "09:06",
      "hora60": "09:06", "fecha60": "01-09-2026",
      "hora63": "09:12", "fecha63": "01-09-2026",
      "hora69": "", "fecha69": "",
      "hora610": "", "fecha610": "",
      "hora68": "", "fecha68": "",
      "km": "0",
      "gps": { "lat": -34.6368, "lng": -71.1199, "speed": 0, "heading": 0, "hora": "14:42:11", "fecha": "30-08-2026", "timestamp": 1788115331232 }
    }
  }
  ```

### 3. `vehiculos` (Material Mayor)
* **ID de documento:** Código del carro (`"B1"`).
* **Campos:** `idCarro`, `estado` (`0-9`, `0-8`, `6-13`, `6-14`), `enServicio` (`0` o clave), `conductor`, `obac`, `aCargo`, `lugar`, `notas`, `kmActual`, `lat`, `lng`, `heading`, `speed`, `lastUpdate`, `solicitudConductorTimestamp`, `solicitudPersonalTimestamp`.

### 4. `asistencia` (Listas y Guardias)
* **ID de documento:** `idLista` (`"2026156"`).
* **Campos:** `idLista`, `idServicio`, `clave`, `evento`, `fecha`, `hora`, `lugar`, `obac`, `listaPor`, `aprobadoPor`, `fechaAprobacion`, `abono` (`"SÍ"`/`"NO"`), `esAbono` (`1`/`0`), `bomberos` (`Array[Map]`).

### 5. `personal` (Padrón de Voluntarios)
* **ID de documento:** `idRegistro` (`"114"`).
* **Campos:** `idRegistro`, `idRadial`, `nombreBombero`, `cargo`, `compania`, `estado` (`0-9`, `0-8`, `CDS`), `enServicio`, `conductor` (`"SI"`/`"NO"`), `activo` (`"SI"`/`"NO"`), `autorizadoAdmin`, `contrasena`, `deviceId`, `lat`, `lng`, `gpsTimestamp`, `estadosHistorico`.

---

## 🔄 Guía Paso a Paso para Construir una Actualización

### Paso 1: Regla General Estricta de Versionado Semántico (`MAJOR.MINOR.PATCH`)
* **`PATCH` (X.X.+1):** Hotfixes y solución de bugs.
* **`MINOR` (X.+1.0):** Nuevas pantallas o funciones.
* **`MAJOR` (+1.0.0):** Cambios de arquitectura o rediseños completos.

📌 **INDEPENDENCIA DE VERSIÓN POR APLICACIÓN:**  
Cada aplicación del ecosistema (`SisBom.exe`, `MiSisBom`, `SisBomCar.apk`, `Lista.apk`, `SaaS.exe`) mantiene su propio historial de versión independiente.

---

## 📌 Reglas de Preservación de Código (Para Desarrolladores e IA)
1. **No alterar contratos de datos en Firestore** sin verificar el Diccionario Canónico de Campos.
2. **Uso Exclusivo de Proyecto de Pruebas:** Prohibido conectar o modificar el proyecto de producción de clientes (`sisbom-de5f8`). Usar únicamente `pruebas-sisbom`.
3. **Mantener comentarios explicativos** en secciones críticas de despacho, audio y sincronización.

