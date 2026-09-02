# 🚀 Guía Paso a Paso para Publicar MiSisBom en Google Play Console

Esta guía te orienta en cada pantalla de **Google Play Console** para subir y publicar **MiSisBom**.

---

## 📁 Paquete de Archivos Preparado
Todos los recursos necesarios se encuentran listos en la carpeta:
`c:\Users\danie\Desktop\SisBom\DEV\MiSisBom\GOOGLE_PLAY_PUBLISH\`

1. **`MiSisBom.aab`** *(Android App Bundle firmado con release key `sisbom.jks`, Version 2.1.1, Code 211, Target SDK 36).*
2. **`POLITICA_DE_PRIVACIDAD.html`** *(Página web oficial de Política de Privacidad lista para subir).*
3. **`FICHA_GOOGLE_PLAY_STORE.md`** *(Textos oficiales, títulos, descripciones y respuestas para todos los formularios de Google).*

---

## 🛠️ Pasos en Google Play Console

### Paso 1: Completar la Verificación de Identidad
- En la barra superior de Google Play Console, completa el proceso de verificación de identidad (documento de identidad o datos de la organización).

### Paso 2: Crear la Aplicación
1. Haz clic en el botón azul **"Crear aplicación"**.
2. **Nombre de la app:** `MiSisBom`
3. **Idioma predeterminado:** `Español (Latinoamérica) - es-419` o `Español (Chile)`.
4. **¿Es una aplicación o un juego?:** Selecciona `Aplicación`.
5. **¿Es gratuita o de pago?:** Selecciona `Gratis`.
6. Acepta las declaraciones de políticas de desarrollador y haz clic en **Crear aplicación**.

### Paso 3: Configurar la Ficha de Play Store Principal
Ve a la sección lateral izquierda: **Crecimiento > Presencia en Google Play Store > Ficha de Play Store principal**:
- Copia y pega el **Título**, la **Descripción breve** y la **Descripción completa** desde [FICHA_GOOGLE_PLAY_STORE.md](file:///c:/Users/danie/Desktop/SisBom/DEV/MiSisBom/GOOGLE_PLAY_PUBLISH/FICHA_GOOGLE_PLAY_STORE.md).
- **Ícono de la aplicación:** Sube una imagen de 512 x 512 px.
- **Gráfico de funciones:** Sube una imagen de 1024 x 500 px.
- **Capturas de pantalla del teléfono:** Sube al menos 2 capturas de pantalla de la app.

### Paso 4: Configurar el Contenido de la Aplicación
Ve a la sección lateral izquierda: **Políticas > Contenido de la aplicación**:
1. **Política de Privacidad:** Pega la URL donde alojes el archivo `POLITICA_DE_PRIVACIDAD.html`.
2. **Acceso a la app:** Selecciona "Todas las funciones o algunas están restringidas" y añade las credenciales de prueba descritas en `FICHA_GOOGLE_PLAY_STORE.md`.
3. **Anuncios:** Selecciona "No, mi aplicación no contiene anuncios".
4. **Clasificación de contenido:** Inicia el cuestionario IARC respondiendo a las preguntas (no violencia, no contenido para adultos).
5. **Público objetivo:** Selecciona mayores de 18 años.
6. **Seguridad de los datos (Data Safety):** Completa el cuestionario siguiendo la tabla en `FICHA_GOOGLE_PLAY_STORE.md`.

### Paso 5: Subir el Paquete `.aab` a Producción
1. En el menú izquierdo, ve a **Lanzamiento > Producción** (o **Pruebas cerradas** si tu cuenta es personal y requiere testers).
2. Haz clic en **Crear nueva versión**.
3. En la sección "Subir paquetes de aplicaciones", arrastra el archivo:
   `c:\Users\danie\Desktop\SisBom\DEV\MiSisBom\GOOGLE_PLAY_PUBLISH\MiSisBom.aab`
4. Nombre de la versión: `2.1.1 (211)`
5. Notas de la versión (es-419):
   ```text
   - Actualización del sistema de alertas y despacho de emergencias en tiempo real.
   - Mejoras en el cálculo de asistencia anual de bomberos (Ciclo 8 de Diciembre).
   - Optimización de mapas tácticos y visualización de grifos.
   ```
6. Haz clic en **Guardar** y luego en **Revisar versión**.
7. Si no hay advertencias bloqueantes, haz clic en **Enviar para revisión**.
