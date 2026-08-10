# Reglas del Proyecto SisBom (Central, Comandancia, MiSisBom, Asistencia)

Al trabajar en este repositorio, se deben seguir estrictamente las siguientes directrices operativas:

## 1. Contexto de Dominio (Central de Alarmas y Bomberos)
* Las aplicaciones del ecosistema **SisBom** se utilizan en operaciones de emergencia en tiempo real.
* La disponibilidad, resiliencia de red y claridad de las notificaciones son la máxima prioridad.

## 2. Aplicaciones y Arquitectura
* **`SisBom.exe` (Central de Alarmas - Python):** Sistema de despacho en tiempo real. Debe garantizar no bloquear el hilo principal de la UI, soportar multimonitor y manejar desconexiones de Firestore con reconexión transparente (evitando bloqueos por QUIC/UDP).
* **`SisBom.exe` (Comandancia - Python):** Sistema administrativo y de reportes. No debe saturar la base de datos con consultas masivas que afecten los canales en tiempo real de la Central. En `estadisticas.html` y `personal.html`, la asistencia debe calcularse procesando únicamente listas obligatorias donde figure activamente el `idRegistro` del bombero.
* **`MiSisBom` (`.apk` / `.ipa`):** App móvil personal para voluntarios. 
  - **Sonidos y Tono por Clave:** Sonidos dedicados por clave de despacho (`c10_0.mp3` a `c10_30.mp3`). Prohibido usar `despacho.mp3` como fallback para claves 10-X.
  - **Citaciones 12-10 y 6-6:**
    * `12-10`: "Se solicita conductor para unidad X". Solo suena si `conductor == 1`, estado `0-9` y no ha marcado "Asistir". Tono: `alerta.mp3`.
    * `6-6`: "Se solicita personal para unidad X". Solo suena si está en estado `0-9` y no ha marcado "Asistir". Tono: `alerta.mp3`.
    * Si el bombero marcó "No Asistir", las citaciones 12-10 o 6-6 IGUAL deben sonar con `alerta.mp3` (pero no vuelve a sonar el despacho inicial).
  - **Temporizadores y Botones (1er y 2do Tono):**
    * **1er Tono:** Suena el tono de clave `c10_X.mp3` en tiempo real. Si han transcurrido > 2 minutos desde `horaDespacho` por falta de conexión/señal, NO suena el tono de despacho inicial; en su lugar llega una **notificación push con vibración y sonido `alerta.mp3`** con el título `"⚠️ DESPACHO DIFERIDO POR RED"` y mensaje `"[Clave] • [Lugar]. Notificación entregada diferida por reconexión de red en el móvil."`.
    * **2do Tono:** Suena 1 minuto (60s) después del 1er tono SI Y SOLO SI no ha presionado "Asistir" ni "No Asistir".
    * Presionar "No Asistir" o "Asistir" elimina/cancela el 2do tono.
  - **Bypass de Silencio:** Las notificaciones de despacho/Grado 3 fuerzan el volumen al 100% y en iOS usan `AVAudioSession` categoría `.playback`.
  - **Filtros por Estado:** 
    * `0-8` ABSOLUTO: Silencio total, NO hay notificación push, NI sonido, NI vibración.
    * `CDS` (Comisión de Servicio): Solo notificación push silenciosa sin reproducciones de sonido fuerte ni sirenas.
    * **Silencio por Botones de Volumen:** Al reproducirse cualquier audio/alarma (`c10_X.mp3`, `despacho.mp3`, `alerta.mp3`), presionar cualquier botón físico de volumen (Subir/Bajar/Mute) en Android o iOS insonoriza e interrumpe inmediatamente el tono y detiene la vibración.
    * **Push Silencioso de Estado (Data-Only):** Al cambiar el estado de un bombero desde la Central, se envía un Data Push para actualizar el estado local e interfaz en segundo plano en Android e iOS al instante, desplegando la tarjeta visual con el escudo de la compañía de la licencia activa y vibración.
    * **Limpieza Automática de Notificaciones:** Al abrir o ingresar a la app en Android e iOS, se cancelan y limpian automáticamente todas las notificaciones de la barra de estado.
    * **Audio Clave 9-0:** Mapeado para reproducir `c10_9.mp3` o `despacho.mp3`, prohibido usar `alerta.mp3`.
  - **Grados de Alerta:**
    * Grado 1: Solo notificación push normal.
    * Grado 2: Notificación push + vibración.
    * Grado 3: Notificación push + vibración larga + sonido `alerta.mp3` fuerte (bypass silencio).
  - **Pestaña Asistencia:**
    * Estado `SUSPENDIDO` debe mostrarse siempre en color **Morado (Purple)**.
    * Mostrar el número de lista limpio sin el prefijo del ciclo (ej: `#15` en lugar de `202615`).
    * **Filtrado Visual y Cálculo:** NO mostrar en pantalla ni incluir en el cálculo de porcentaje las listas anteriores a la incorporación del bombero (solo procesar y desplegar listas donde figure activamente el `idRegistro` del bombero).
  - **Simetría Multiplataforma:** Mantener paridad total de funciones entre Android e iOS.
* **`Asistencia.apk`:** App de tótem para cuartel. Debe operar en Modo Kiosco, pantalla encendida y permitir marcación offline con sincronización diferida.

## 3. Normas de Desarrollo y Actualización
* Consultar [GUIA_Y_REGLAS_DE_ACTUALIZACION.md](file:///c:/Users/danie/Desktop/SisBom/DEV/GUIA_Y_REGLAS_DE_ACTUALIZACION.md) antes de generar nuevas versiones o parches.
* **Versionado Semántico Estricto (`MAJOR.MINOR.PATCH`):**
  - **Corrección de Errores / Bugs:** Incrementar dífito PATCH (`X.X.+1`, ej: `1.0.0` → `1.0.1`).
  - **Nuevas Funcionalidades:** Incrementar dígito MINOR (`X.+1.0`, ej: `1.0.1` → `1.1.0`).
  - **Cambio Radical / App Renovada:** Incrementar dígito MAJOR (`+1.0.0`, ej: `1.2.0` → `2.0.0`).
* **Independencia de Versión por App:** Cada aplicación (`SisBom.exe`, `MiSisBom`, `Asistencia.apk`) maneja su propio historial de versión independiente.
* **Sincronización Interna de Versión:** Al compilar una versión de una app específica, actualizar obligatoriamente el número de versión en **todos los archivos propios de esa app donde aparezca mencionado** (`build.py`, `main.py`, `package.json`, `build.gradle.kts`, `Info.plist`, viewmodels, etc.).
* Respetar los contratos de datos de Firestore y los esquemas JSON existentes.
