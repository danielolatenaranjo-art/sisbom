# 📱 Ficha Técnica y Metadatos para Google Play Console: MiSisBom

Este documento contiene todos los textos oficiales, descripciones y configuraciones necesarias para completar la ficha de la aplicación en **Google Play Console**.

---

## 1. Detalles Principales de la Aplicación (Ficha de Play Store Principal)

### 🏷️ Nombre de la Aplicación
* **Título (máximo 30 caracteres):**
  `MiSisBom`
  *(Opción alternativa: `MiSisBom - Bomberos de Chile`)*

### 📝 Descripción Breve
* **Texto (máximo 80 caracteres):**
  `Sistema de despacho, alerta temprana y control operativo para Cuerpos de Bomberos.`

### 📄 Descripción Completa (máximo 4000 caracteres)
```text
MiSisBom es la aplicación móvil oficial de respuesta y gestión operativa diseñada para Cuerpos de Bomberos y su personal voluntario.

Permite una comunicación fluida y en tiempo real entre la Central de Alarmas y Telecomunicaciones (CAD) y los voluntarios de las distintas compañías.

CARACTERÍSTICAS PRINCIPALES:

🚨 DESPACHOS Y ALERTAS EN TIEMPO REAL:
- Recepción instantánea de alarmas de emergencia con tonos oficiales bomberiles (10-0 a 10-15, 9-0).
- Visualización de la clave de emergencia, dirección exacta, carros despachados y preinforme de la Central.
- Mapa táctico con radio de alcance de la emergencia y visualización de grifos contra incendios cercanos.

🚒 CONTROL DE DISPONIBILIDAD Y SERVICIO:
- Cambio de estado operativo con un toque: En Servicio (0-8), No Disponible (0-9), Comisión de Servicio (CDS), Licencia Médica o Permiso.
- Confirmación de asistencia a llamados de emergencia (Voy en Camino / No Asisto).
- Consulta de personal activo en servicio por compañía.

📋 CONTROL DE ASISTENCIA Y CICLO ANUAL:
- Historial completo de listas de asistencia a llamados, academias y citaciones reglamentarias.
- Cálculo automático de porcentaje de asistencia para el ciclo bomberil anual (Ciclo 8 de Diciembre).
- Verificación del cumplimiento estatutario para derecho a voto y postulación a cargos.

📟 CENTRAL DE ALARMAS MÓVIL:
- Consola de despacho rápida para operadores autorizados de Central.
- Emisión de llamados de Comandancia, citaciones urgentes y alarmas generales.

MiSisBom moderniza la respuesta ante emergencias, optimizando los tiempos de reacción y la coordinación de los recursos bomberiles.
```

---

## 2. Categorización y Datos de Contacto

* **Tipo de Aplicación:** `Aplicación`
* **Categoría:** `Productividad` (o `Herramientas`)
* **Etiquetas (Tags):** `Productividad`, `Herramientas`, `Comunicación`, `Emergencias`
* **Correo electrónico de soporte:** `contacto@sisbom.cl` (o el correo corporativo del desarrollador/administrador)
* **Sitio web:** `https://sisbom.cl`
* **Enlace a la Política de Privacidad:** `https://sisbom.cl/privacidad.html` (o URL donde se aloje `POLITICA_DE_PRIVACIDAD.html`)

---

## 3. Acceso a la Aplicación (Para los Revisores de Google)

Google Play requiere credenciales de prueba para que los revisores de Google puedan ingresar y probar la app:
* **Tipo de acceso:** `Todas las funciones o algunas están restringidas`
* **Instrucciones para el revisor:**
  - **Licencia / Código de Activación:** `PRUEBA-DEMO-2026`
  - **Identificador / RUT:** `11111111-1`
  - **Clave:** `1234`
  - **Instrucciones adicionales:** "Ingresar código de licencia demo para cargar la compañía de prueba y probar las pestañas de Actividad, Asistencia y Despacho."

---

## 4. Clasificación del Contenido (Cuestionario IARC)

* **¿La app contiene contenido violento o sangriento?** `No`
* **¿La app contiene contenido sexual o desnudez?** `No`
* **¿La app permite a los usuarios interactuar o intercambiar mensajes?** `Sí` (mensajería interna institucional / bitácora de novedades).
* **¿La app comparte la ubicación física actual del usuario con otros usuarios?** `Sí` (ubicación de respuesta a emergencias operativas dentro de la red del Cuerpo de Bomberos).
* **¿La app permite a los usuarios comprar productos digitales?** `No` (es una herramienta institucional privada).

---

## 5. Declaración de Seguridad de los Datos (Data Safety Section)

Al completar el cuestionario de **Seguridad de los Datos** en Google Play Console:

| Campo | Respuesta | Justificación |
|---|---|---|
| **¿La app recopila o comparte datos de los usuarios?** | `Sí` | Para autenticación y despacho de emergencias. |
| **¿Todos los datos recopilados están cifrados en tránsito?** | `Sí` | Transmisión segura TLS/HTTPS con Google Cloud / Firebase. |
| **¿La app ofrece una forma para que los usuarios soliciten la eliminación de sus datos?** | `Sí` | A través de la oficialidad de su Cuerpo de Bomberos o correo de soporte. |
| **Ubicación (Aproximada y Precisa):** | Recopilada (Opcional en respuesta a emergencia) | Cálculo de tiempos de respuesta y mapa táctico del incidente. |
| **Información Personal (Nombre, RUT, Teléfono):** | Recopilada | Identificación del bombero en la lista de asistencia y autenticación. |
| **IDs del Dispositivo (FCM Token):** | Recopilada | Entrega de notificaciones push de alarmas en tiempo real. |
