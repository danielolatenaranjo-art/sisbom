# Guía de Configuración del Proyecto iOS en Xcode - SisBom

Esta guía te guiará paso a paso para importar el código fuente Swift/SwiftUI generado a Xcode dentro de tu máquina virtual de macOS (VirtualBox), configurar las dependencias de Firebase y habilitar las notificaciones push en segundo plano.

---

## Requisitos Previos en la Máquina Virtual macOS
1. Tener **Xcode** instalado (disponible gratis en la App Store de macOS).
2. Conexión a internet en la máquina virtual.

---

## Paso 1: Crear el Proyecto en Xcode
1. Abre Xcode en tu máquina virtual de macOS.
2. Selecciona **Create a new Xcode project**.
3. Elige la pestaña **iOS** y selecciona la plantilla **App**. Presiona *Next*.
4. Configura las opciones del proyecto:
   - **Product Name**: `SisBom`
   - **Organization Identifier**: `com.misisbom` (el identificador final será `com.misisbom.sisbom` o el que definas)
   - **Interface**: `SwiftUI`
   - **Language**: `Swift`
5. Guarda el proyecto en tu máquina virtual.

---

## Paso 2: Importar los Archivos Swift
1. Copia la carpeta `iOS` que creamos en tu máquina virtual (puedes usar una carpeta compartida en VirtualBox, GitHub, o Google Drive).
2. En Xcode, en el panel izquierdo (Project Navigator), elimina los archivos creados por defecto:
   - `ContentView.swift`
   - `SisBomApp.swift` (o el archivo con `@main` por defecto)
3. Arrastra las siguientes carpetas y archivos desde la carpeta `iOS` al navegador de tu proyecto en Xcode:
   - `SisBomApp.swift`
   - La carpeta `Models/`
   - La carpeta `Services/`
   - La carpeta `ViewModels/`
   - La carpeta `Views/`
4. Al arrastrar los archivos, asegúrate de marcar la casilla **"Copy items if needed"** y seleccionar **"Create groups"**.

---

## Paso 3: Agregar las dependencias de Firebase (SPM)
Xcode incluye Swift Package Manager (SPM) integrado, lo que hace la instalación de Firebase muy simple:
1. En Xcode, ve al menú superior: **File** > **Add Packages...**
2. En el cuadro de búsqueda superior derecho, ingresa la URL de Firebase:
   `https://github.com/firebase/firebase-ios-sdk`
3. En *Dependency Rule*, selecciona **Up to Next Major Version** (la versión sugerida por defecto). Presiona *Add Package*.
4. Xcode descargará los paquetes. En la lista de componentes a instalar, selecciona únicamente:
   - **FirebaseAnalytics** (análisis)
   - **FirebaseAuth** (autenticación)
   - **FirebaseFirestore** (base de datos en tiempo real)
   - **FirebaseMessaging** (notificaciones push/FCM)
5. Presiona *Add Package* para finalizar.

---

## Paso 4: Descargar y Añadir `GoogleService-Info.plist`
Para enlazar la app a tu base de datos de Firebase:
1. Ve a tu consola de Firebase en la web.
2. Agrega una nueva aplicación de **iOS** en la configuración de tu proyecto.
   - El *Bundle ID* debe ser idéntico al de tu proyecto Xcode (ej. `com.misisbom.sisbom`).
3. Descarga el archivo de configuración **`GoogleService-Info.plist`**.
4. Arrastra el archivo `GoogleService-Info.plist` dentro del Project Navigator en Xcode (colócalo en la raíz del proyecto). *Asegúrate de marcar "Copy items if needed".*

---

## Paso 5: Habilitar Notificaciones Push y Modos en Segundo Plano
Para recibir las alertas de despacho en tiempo real mientras la app está cerrada:
1. En el navegador del proyecto en Xcode, haz clic en la raíz del proyecto (el icono azul superior de `SisBom`).
2. Selecciona la pestaña **Signing & Capabilities**.
3. Haz clic en el botón **+ Capability** (en la esquina superior izquierda de esta pestaña).
4. Agrega las siguientes dos capacidades:
   - **Push Notifications**
   - **Background Modes**
5. En la capacidad de *Background Modes*, marca las casillas:
   - **Background fetch**
   - **Remote notifications** (para permitir que los mensajes en segundo plano despierten la app e inicien la sincronización).

*Nota: Para probar notificaciones en un iPhone físico necesitarás una cuenta de desarrollador de Apple ($99/año). No obstante, el simulador de iOS permite probar notificaciones locales y simular alertas.*

---

## Paso 6: Agregar Sonidos a los Despachos
1. Copia los archivos de audio (como `alerta.wav` y los sonidos de claves de carro `c10_0.mp3`, etc.) que están en Android (`app/src/main/res/raw/`) a tu máquina virtual.
2. Arrástralos a tu Project Navigator en Xcode.
3. Asegúrate de marcar **"Copy items if needed"** y que tu target de aplicación `SisBom` esté seleccionado en la casilla **"Add to targets"**.
4. El ViewModel los cargará automáticamente por nombre usando `AVAudioPlayer`.
