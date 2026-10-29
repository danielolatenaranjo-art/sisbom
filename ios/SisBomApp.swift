import SwiftUI
import UIKit
import WebKit
import UserNotifications
import AVFoundation
import AudioToolbox
import CoreLocation
import FirebaseCore
import FirebaseAuth
import FirebaseFirestore
import FirebaseMessaging
import FirebaseDatabase

// ============================================================================
// 1. GESTOR DE UBICACIÓN GPS EN SEGUNDO PLANO EN iOS -> EXCLUSIVAMENTE A RTDB
// ============================================================================
final class IOSLocationService: NSObject, CLLocationManagerDelegate {
    static let shared = IOSLocationService()
    private let manager = CLLocationManager()
    private(set) var isTracking: Bool = false
    private var activeServiceId: String?
    private var activeUserId: String?
    private var activeNombre: String?
    private var activeRango: String?
    private var activeCompania: String?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.pausesLocationUpdatesAutomatically = false
    }

    func requestAlwaysPermissions() {
        let status: CLAuthorizationStatus
        if #available(iOS 14.0, *) {
            status = manager.authorizationStatus
        } else {
            status = CLLocationManager.authorizationStatus()
        }
        if status == .notDetermined {
            manager.requestWhenInUseAuthorization()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
                self?.manager.requestAlwaysAuthorization()
            }
        } else if status == .authorizedWhenInUse {
            manager.requestAlwaysAuthorization()
        }
        syncPermissionsToWebView()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if #available(iOS 14.0, *) {
            if manager.authorizationStatus == .authorizedWhenInUse {
                manager.requestAlwaysAuthorization()
            }
        }
        syncPermissionsToWebView()
    }

    func syncPermissionsToWebView() {
        let status: CLAuthorizationStatus
        if #available(iOS 14.0, *) {
            status = manager.authorizationStatus
        } else {
            status = CLLocationManager.authorizationStatus()
        }
        let fine = (status == .authorizedAlways || status == .authorizedWhenInUse)
        let bg = (status == .authorizedAlways || status == .authorizedWhenInUse)
        guard let wv = AppDelegate.sharedWebView else { return }
        let js = """
        (function() {
            try {
                window.__SENTINEL_IOS_PERMS__ = {
                    fineLocation: \(fine ? "true" : "false"),
                    backgroundLocation: \(bg ? "true" : "false"),
                    overlay: true,
                    batteryUnrestricted: true,
                    notifications: true
                };
                window.dispatchEvent(new CustomEvent('sentinelPermissionsChanged'));
            } catch (e) {}
        })();
        """
        DispatchQueue.main.async {
            wv.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    func startTracking(serviceId: String, userId: String, nombre: String, rango: String, compania: String) {
        let clean = serviceId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, clean != "0", !clean.hasPrefix("-"),
              clean != "EN EL CUARTEL", clean != "EN LA COMPAÑIA", clean != "EN LA COMPAÑÍA",
              !clean.hasPrefix("UNIDAD:") else {
            stopTracking()
            return
        }
        if isTracking && activeServiceId == clean { return }

        activeServiceId = clean
        activeUserId = userId
        activeNombre = nombre
        activeRango = rango
        activeCompania = compania
        isTracking = true

        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.distanceFilter = 5.0
        manager.startUpdatingLocation()
    }

    func stopTracking() {
        guard isTracking else { return }
        let oldUserId = activeUserId
        isTracking = false
        activeServiceId = nil
        activeUserId = nil
        manager.stopUpdatingLocation()
        manager.showsBackgroundLocationIndicator = false

        if let uid = oldUserId, !uid.isEmpty {
            removeFromRtdb(userId: uid)
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard isTracking, let loc = locations.last, let userId = activeUserId, !userId.isEmpty else { return }
        sendTelemetryToRtdbOnly(location: loc, userId: userId)
    }

    private func sendTelemetryToRtdbOnly(location: CLLocation, userId: String) {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let payload: [String: Any] = [
            "id": userId,
            "idRegistro": userId,
            "nombre": activeNombre ?? "Bombero",
            "rango": activeRango ?? "Voluntario",
            "compania": activeCompania ?? "1",
            "lat": location.coordinate.latitude,
            "lng": location.coordinate.longitude,
            "heading": max(0.0, location.course),
            "speed": max(0.0, location.speed),
            "accuracy": location.horizontalAccuracy,
            "timestamp": nowMs,
            "idServicio": activeServiceId ?? ""
        ]

        let projectId = UserDefaults.standard.string(forKey: "saas_project_id") ?? "sisbom-de5f8"
        let dbUrl = UserDefaults.standard.string(forKey: "saas_database_url") ?? "https://\(projectId)-default-rtdb.firebaseio.com"
        let cleanBase = dbUrl.hasSuffix("/") ? String(dbUrl.dropLast()) : dbUrl
        guard let url = URL(string: "\(cleanBase)/telemetria/bomberos/\(userId).json") else { return }

        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        URLSession.shared.dataTask(with: req) { _, _, _ in }.resume()
    }

    private func removeFromRtdb(userId: String) {
        let projectId = UserDefaults.standard.string(forKey: "saas_project_id") ?? "sisbom-de5f8"
        let dbUrl = UserDefaults.standard.string(forKey: "saas_database_url") ?? "https://\(projectId)-default-rtdb.firebaseio.com"
        let cleanBase = dbUrl.hasSuffix("/") ? String(dbUrl.dropLast()) : dbUrl
        guard let url = URL(string: "\(cleanBase)/telemetria/bomberos/\(userId).json") else { return }

        var req = URLRequest(url: url)
        req.httpMethod = "DELETE"
        URLSession.shared.dataTask(with: req) { _, _, _ in }.resume()
    }
}

// ============================================================================
// 2. GESTOR DE SONIDOS Y VIBRACIÓN TÁCTICA EN iOS (BYPASS SILENT SWITCH EN 0-9)
// ============================================================================
final class IOSSoundPlayer: NSObject, AVAudioPlayerDelegate {
    static let shared = IOSSoundPlayer()
    private var audioPlayer: AVAudioPlayer?
    private var repeatTimers: [String: Timer] = [:]

    func resolveSoundFileName(for claveOrName: String) -> String {
        let c = claveOrName.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ".MP3", with: "")
        if c.contains("10-30") || c.contains("FORESTAL") || c == "C10_30" { return "c10_30" }
        if c.contains("9-0") || c == "C9_0" { return "c9_0" }
        if c.contains("LLAMADO") || c.contains("COMANDANCIA") { return "llamado_comandancia" }
        if c.hasPrefix("10-10") || c == "C10_10" { return "c10_10" }
        if c.hasPrefix("10-12") || c == "C10_12" { return "c10_12" }
        if c.hasPrefix("10-15") || c == "C10_15" { return "c10_15" }
        if c.hasPrefix("10-0") || c == "C10_0" { return "c10_0" }
        if c.hasPrefix("10-1") || c == "C10_1" { return "c10_1" }
        if c.hasPrefix("10-2") || c == "C10_2" { return "c10_2" }
        if c.hasPrefix("10-3") || c == "C10_3" { return "c10_3" }
        if c.hasPrefix("10-4") || c == "C10_4" { return "c10_4" }
        if c.hasPrefix("10-5") || c == "C10_5" { return "c10_5" }
        if c.hasPrefix("10-6") || c == "C10_6" { return "c10_6" }
        if c.hasPrefix("10-7") || c == "C10_7" { return "c10_7" }
        if c.hasPrefix("10-8") || c == "C10_8" { return "c10_8" }
        if c.hasPrefix("10-9") || c == "C10_9" { return "c10_9" }
        if c == "ALERTA" || c == "ALERTAS" { return "alerta" }
        if c == "IMPORTANTE" { return "importante" }
        return "despacho"
    }

    func play(nameOrClave: String, forceMaxVolume: Bool = true) {
        stop()
        let fileName = resolveSoundFileName(for: nameOrClave)
        guard let url = Bundle.main.url(forResource: fileName, withExtension: "mp3")
                ?? Bundle.main.url(forResource: "despacho", withExtension: "mp3")
                ?? Bundle.main.url(forResource: "alertas", withExtension: "mp3") else {
            AudioServicesPlayAlertSound(kSystemSoundID_Vibrate)
            return
        }

        do {
            let session = AVAudioSession.sharedInstance()
            if forceMaxVolume {
                // .playback ignora el interruptor físico de silencio en iOS para emergencias en 0-9
                try session.setCategory(.playback, mode: .default, options: [.duckOthers])
            } else {
                try session.setCategory(.ambient, mode: .default, options: [.mixWithOthers])
            }
            try session.setActive(true)

            let player = try AVAudioPlayer(contentsOf: url)
            player.delegate = self
            player.volume = 1.0
            player.prepareToPlay()
            player.play()
            self.audioPlayer = player
            vibrateEmergency()
        } catch {
            AudioServicesPlayAlertSound(kSystemSoundID_Vibrate)
        }
    }

    func stop() {
        audioPlayer?.stop()
        audioPlayer = nil
    }

    func vibrateEmergency() {
        AudioServicesPlayAlertSound(kSystemSoundID_Vibrate)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            AudioServicesPlayAlertSound(kSystemSoundID_Vibrate)
        }
    }

    func cancelRepeat(for dispatchId: String) {
        let keys = repeatTimers.keys.filter { $0 == dispatchId || $0.hasPrefix("\(dispatchId)_") }
        for k in keys {
            repeatTimers[k]?.invalidate()
            repeatTimers.removeValue(forKey: k)
        }
    }

    func scheduleRepeat(dispatchId: String, clave: String) {
        cancelRepeat(for: dispatchId)
        let timer = Timer.scheduledTimer(withTimeInterval: 60.0, repeats: false) { [weak self] _ in
            guard let self = self else { return }
            self.repeatTimers.removeValue(forKey: dispatchId)
            let defaults = UserDefaults.standard
            if defaults.bool(forKey: "MODO_AVION") || defaults.bool(forKey: "IS_CENTRAL_ACTIVE") { return }
            let estado = defaults.string(forKey: "USER_ESTADO") ?? "0-8"
            if estado != "0-9" { return }
            let declined = defaults.stringArray(forKey: "DECLINED_DISPATCH_IDS") ?? []
            let attended = defaults.stringArray(forKey: "ATTENDED_DISPATCH_IDS") ?? []
            if declined.contains(dispatchId) || attended.contains(dispatchId) { return }

            let enServicio = defaults.string(forKey: "USER_EN_SERVICIO") ?? ""
            if enServicio == dispatchId || enServicio == "-\(dispatchId)" { return }
            if UIApplication.shared.applicationState == .active { return }

            self.play(nameOrClave: clave, forceMaxVolume: true)
        }
        repeatTimers[dispatchId] = timer
    }
}

// ============================================================================
// 3. APP DELEGATE: FIREBASE DINÁMICO, RECORDATORIOS DIARIOS Y NOTIFICACIONES PUSH
// ============================================================================
class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {
    static var sharedWebView: WKWebView?
    static var currentTenantProjectId: String?

    static func configureDynamicFirebase(configDict: [String: Any]) {
        guard let apiKey = configDict["apiKey"] as? String, !apiKey.isEmpty,
              let projectId = configDict["projectId"] as? String, !projectId.isEmpty else {
            return
        }
        let rawAppId = (configDict["appId"] as? String) ?? "1:432594591351:ios:99a442ea6ae05276beb072"
        let formattedAppId = rawAppId.replacingOccurrences(of: ":web:", with: ":ios:").replacingOccurrences(of: ":android:", with: ":ios:")
        let senderId = (configDict["messagingSenderId"] as? String) ?? "432594591351"
        let storageBucket = (configDict["storageBucket"] as? String) ?? "\(projectId).appspot.com"
        let dbUrl = (configDict["databaseURL"] as? String) ?? "https://\(projectId)-default-rtdb.firebaseio.com"
        let bundleId = Bundle.main.bundleIdentifier ?? "com.misisbom.sisbom.NFR3L3CC3L"

        UserDefaults.standard.set(projectId, forKey: "saas_project_id")
        UserDefaults.standard.set(dbUrl, forKey: "saas_database_url")
        if let jsonData = try? JSONSerialization.data(withJSONObject: configDict),
           let jsonStr = String(data: jsonData, encoding: .utf8) {
            UserDefaults.standard.set(jsonStr, forKey: "saas_firebase_config")
        }

        let options = FirebaseOptions(googleAppID: formattedAppId, gcmSenderID: senderId)
        options.apiKey = apiKey
        options.projectID = projectId
        options.storageBucket = storageBucket
        options.databaseURL = dbUrl
        options.bundleID = bundleId

        if FirebaseApp.app() == nil {
            FirebaseApp.configure(options: options)
            currentTenantProjectId = projectId
            authenticateNativeApp(FirebaseApp.app())
        } else if currentTenantProjectId != projectId {
            let appName = "tenant_\(projectId)"
            if FirebaseApp.app(name: appName) == nil {
                FirebaseApp.configure(name: appName, options: options)
            }
            currentTenantProjectId = projectId
            authenticateNativeApp(FirebaseApp.app(name: appName))
        }
    }

    static func authenticateNativeApp(_ app: FirebaseApp?) {
        guard let targetApp = app else { return }
        let auth = Auth.auth(app: targetApp)
        if auth.currentUser != nil { return }
        auth.signIn(withEmail: "137@sisbom.com", password: "ADASME137_secure_sisbom") { result, error in
            if error != nil || result == nil {
                auth.signInAnonymously(completion: nil)
            }
        }
    }

    static func activeFirestore() -> Firestore {
        if let pid = currentTenantProjectId, let namedApp = FirebaseApp.app(name: "tenant_\(pid)") {
            return Firestore.firestore(app: namedApp)
        }
        return Firestore.firestore()
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UIApplication.shared.isIdleTimerDisabled = true

        if let cachedStr = UserDefaults.standard.string(forKey: "saas_firebase_config"),
           let data = cachedStr.data(using: .utf8),
           let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            Self.configureDynamicFirebase(configDict: dict)
        }
        if FirebaseApp.app() == nil {
            FirebaseApp.configure()
            Self.authenticateNativeApp(FirebaseApp.app())
        }

        Messaging.messaging().delegate = self
        UNUserNotificationCenter.current().delegate = self

        configureNotificationCategories()
        requestNotificationPermissions(application: application)
        Self.scheduleDailyReminders()
        return true
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        // Al abrir o volver a la app manualmente, silenciar la alarma y actualizar permisos en el WebView
        IOSSoundPlayer.shared.stop()
        IOSLocationService.shared.syncPermissionsToWebView()
    }

    static func scheduleDailyReminders() {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [
            "sentinel_reminder_0800",
            "sentinel_reminder_1830",
            "sentinel_reminder_2200"
        ])

        let defaults = UserDefaults.standard
        if defaults.bool(forKey: "MODO_AVION") { return }

        // 1. 08:00 y 18:30 -> Recordatorio cambio de estado con sonido normal
        let normalTimes = [
            ("sentinel_reminder_0800", 8, 0),
            ("sentinel_reminder_1830", 18, 30)
        ]
        for (id, hour, minute) in normalTimes {
            let content = UNMutableNotificationContent()
            content.title = "Actualizar Estado 🚨"
            content.body = "Recuerda actualizar tu estado (0-8 o 0-9) en la app."
            content.sound = .default

            var comps = DateComponents()
            comps.hour = hour
            comps.minute = minute
            let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: true)
            let req = UNNotificationRequest(identifier: id, content: content, trigger: trigger)
            center.add(req, withCompletionHandler: nil)
        }

        // 2. 22:00 -> Si está en 0-8, recordatorio nocturno silencioso (sin sonido)
        let userEstado = (defaults.string(forKey: "USER_ESTADO") ?? "0-8").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let estadoEspecial = (defaults.string(forKey: "USER_ESTADO_ESPECIAL") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if userEstado == "0-8" && estadoEspecial.isEmpty {
            let nightContent = UNMutableNotificationContent()
            nightContent.title = "Recordatorio de Disponibilidad"
            nightContent.body = "¿Pasarás la noche en la comuna? Recuerda actualizar tu estado a 0-9 si estás disponible para emergencias."
            nightContent.sound = nil

            var nightComps = DateComponents()
            nightComps.hour = 22
            nightComps.minute = 0
            let nightTrigger = UNCalendarNotificationTrigger(dateMatching: nightComps, repeats: true)
            let nightReq = UNNotificationRequest(identifier: "sentinel_reminder_2200", content: nightContent, trigger: nightTrigger)
            center.add(nightReq, withCompletionHandler: nil)
        }
    }

    private func configureNotificationCategories() {
        let attendAction = UNNotificationAction(
            identifier: "ACTION_ATTEND_DISPATCH",
            title: "ASISTIR",
            options: [.foreground]
        )
        let declineAction = UNNotificationAction(
            identifier: "ACTION_DECLINE_DISPATCH",
            title: "NO ASISTIR",
            options: [.destructive]
        )
        let dispatchCategory = UNNotificationCategory(
            identifier: "EMERGENCY_DISPATCH",
            actions: [attendAction, declineAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )

        let openDoorAction = UNNotificationAction(
            identifier: "ACTION_OPEN_DOOR",
            title: "ABRIR PUERTA",
            options: [.authenticationRequired]
        )
        let doorCategory = UNNotificationCategory(
            identifier: "DOOR_REQUEST_ALERT",
            actions: [openDoorAction],
            intentIdentifiers: [],
            options: []
        )

        let replyAction = UNTextInputNotificationAction(
            identifier: "ACTION_REPLY_CHAT",
            title: "Responder",
            options: [],
            textInputButtonTitle: "Enviar",
            textInputPlaceholder: "Escribir respuesta..."
        )
        let chatCategory = UNNotificationCategory(
            identifier: "CHAT_ALERT",
            actions: [replyAction],
            intentIdentifiers: [],
            options: []
        )

        let ackAction = UNNotificationAction(
            identifier: "ACTION_ACK_ALERT",
            title: "Dar Conforme",
            options: []
        )
        let generalAlertCategory = UNNotificationCategory(
            identifier: "GENERAL_ALERT",
            actions: [ackAction],
            intentIdentifiers: [],
            options: []
        )

        UNUserNotificationCenter.current().setNotificationCategories([
            dispatchCategory,
            doorCategory,
            chatCategory,
            generalAlertCategory
        ])
    }

    private func requestNotificationPermissions(application: UIApplication) {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .badge, .sound, .criticalAlert]) { granted, _ in
            if !granted {
                center.requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in }
            }
            DispatchQueue.main.async {
                application.registerForRemoteNotifications()
            }
        }
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Messaging.messaging().apnsToken = deviceToken
        let userId = UserDefaults.standard.string(forKey: "USER_ID") ?? ""
        if !userId.isEmpty {
            Self.syncFcmTopicsAndToken(userId: userId)
        }
    }

    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let token = fcmToken, !token.isEmpty else { return }
        UserDefaults.standard.set(token, forKey: "FCM_TOKEN")
        let userId = UserDefaults.standard.string(forKey: "USER_ID") ?? ""
        if !userId.isEmpty {
            Self.syncFcmTopicsAndToken(userId: userId, tokenOverride: token)
        }
    }

    static func syncFcmTopicsAndToken(userId: String, tokenOverride: String? = nil) {
        guard !userId.isEmpty else { return }
        let defaults = UserDefaults.standard
        let isConductor = defaults.bool(forKey: "USER_CONDUCTOR")
        let isCentral = defaults.bool(forKey: "IS_CENTRAL_ACTIVE")

        var topics = [
            "all",
            "despachos",
            "alertas",
            "alertas_generales",
            "usuario_\(userId)",
            "personal_\(userId)"
        ]
        if isConductor { topics.append("conductores") }
        if isCentral { topics.append("central_operador") }

        for t in topics {
            Messaging.messaging().subscribe(toTopic: t)
        }
        if !isConductor { Messaging.messaging().unsubscribe(fromTopic: "conductores") }
        if !isCentral { Messaging.messaging().unsubscribe(fromTopic: "central_operador") }

        let saveTokenToFirestore: (String) -> Void = { token in
            let db = activeFirestore()
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            db.collection("personal").document(userId).setData([
                "deviceId": token,
                "tokenFcm": token,
                "fcmToken": token,
                "tokenIos": token,
                "platform": "ios",
                "_metaTimestamp": nowMs
            ], merge: true)

            db.collection("fcm_tokens").document(token).setData([
                "token": token,
                "platform": "ios",
                "userId": userId,
                "topics": topics,
                "updatedAt": FieldValue.serverTimestamp()
            ], merge: true)
        }

        if let tok = tokenOverride, !tok.isEmpty {
            saveTokenToFirestore(tok)
        } else {
            Messaging.messaging().token { tok, _ in
                if let tok = tok, !tok.isEmpty {
                    saveTokenToFirestore(tok)
                }
            }
        }
    }

    // Recepción de Push silencioso / background
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        Messaging.messaging().appDidReceiveMessage(userInfo)
        let defaults = UserDefaults.standard
        if defaults.bool(forKey: "MODO_AVION") {
            completionHandler(.noData)
            return
        }

        let type = (userInfo["type"] as? String) ?? ""
        let payloadId = (userInfo["payloadId"] as? String) ?? (userInfo["dispatchId"] as? String) ?? ""
        let clave = (userInfo["clave"] as? String) ?? ""
        let userEstado = (defaults.string(forKey: "USER_ESTADO") ?? "0-9").uppercased()
        let is09 = (userEstado == "0-9")
        let isEscalation = type == "DISPATCH_UPDATE" && (clave.contains("10-30") || clave.contains("FORESTAL"))

        let attended = defaults.stringArray(forKey: "ATTENDED_DISPATCH_IDS") ?? []
        let declined = defaults.stringArray(forKey: "DECLINED_DISPATCH_IDS") ?? []
        let enServicio = defaults.string(forKey: "USER_EN_SERVICIO") ?? ""
        let isAttending = !payloadId.isEmpty && (attended.contains(payloadId) || enServicio == payloadId)
        let hasDeclined = !payloadId.isEmpty && (declined.contains(payloadId) || enServicio == "-\(payloadId)")

        if isAttending {
            completionHandler(.noData)
            return
        }
        if type == "DISPATCH_UPDATE" && !isEscalation {
            completionHandler(.noData)
            return
        }
        if hasDeclined && !isEscalation {
            completionHandler(.noData)
            return
        }

        if (type == "DISPATCH" || isEscalation) && (is09 || isEscalation) {
            if application.applicationState != .active {
                IOSSoundPlayer.shared.play(nameOrClave: clave, forceMaxVolume: true)
                if !payloadId.isEmpty {
                    IOSSoundPlayer.shared.scheduleRepeat(dispatchId: payloadId, clave: clave)
                }
            }
            if !payloadId.isEmpty {
                Self.dispatchNativeIntentToWebView(dispatchId: payloadId, showFullscreen: true)
            }
        }
        completionHandler(.newData)
    }

    // Recepción de Push con la app en primer plano: si ya tiene la app abierta, mostrar pantalla del despacho sin hacer sonar la sirena
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let userInfo = notification.request.content.userInfo
        let type = (userInfo["type"] as? String) ?? ""
        let payloadId = (userInfo["payloadId"] as? String) ?? (userInfo["dispatchId"] as? String) ?? ""
        let clave = (userInfo["clave"] as? String) ?? ""
        let defaults = UserDefaults.standard

        if defaults.bool(forKey: "MODO_AVION") {
            completionHandler([])
            return
        }

        let attended = defaults.stringArray(forKey: "ATTENDED_DISPATCH_IDS") ?? []
        let declined = defaults.stringArray(forKey: "DECLINED_DISPATCH_IDS") ?? []
        let enServicio = defaults.string(forKey: "USER_EN_SERVICIO") ?? ""
        let isAttending = !payloadId.isEmpty && (attended.contains(payloadId) || enServicio == payloadId)
        let hasDeclined = !payloadId.isEmpty && (declined.contains(payloadId) || enServicio == "-\(payloadId)")

        let userEstado = defaults.string(forKey: "USER_ESTADO") ?? "0-9"
        let is09 = (userEstado == "0-9")
        let isEscalation = type == "DISPATCH_UPDATE" && (clave.contains("10-30") || clave.contains("FORESTAL"))

        if type == "DISPATCH" || type == "DISPATCH_UPDATE" {
            if isAttending || (type == "DISPATCH_UPDATE" && !isEscalation) || (hasDeclined && !isEscalation) {
                completionHandler([])
                return
            }
            if is09 || isEscalation {
                if !payloadId.isEmpty {
                    Self.dispatchNativeIntentToWebView(dispatchId: payloadId, showFullscreen: true)
                }
            }
            completionHandler([.banner, .list])
            return
        }
        completionHandler([.banner, .list, .sound])
    }

    // Respuesta a botones interactivos o tap en notificación Push
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        let payloadId = (userInfo["payloadId"] as? String) ?? (userInfo["dispatchId"] as? String) ?? ""
        let userId = UserDefaults.standard.string(forKey: "USER_ID") ?? ""

        IOSSoundPlayer.shared.stop()
        if !payloadId.isEmpty {
            IOSSoundPlayer.shared.cancelRepeat(for: payloadId)
        }

        switch response.actionIdentifier {
        case "ACTION_ATTEND_DISPATCH":
            if !userId.isEmpty && !payloadId.isEmpty {
                var attended = UserDefaults.standard.stringArray(forKey: "ATTENDED_DISPATCH_IDS") ?? []
                var declined = UserDefaults.standard.stringArray(forKey: "DECLINED_DISPATCH_IDS") ?? []
                if !attended.contains(payloadId) { attended.append(payloadId) }
                declined.removeAll { $0 == payloadId }
                UserDefaults.standard.set(attended, forKey: "ATTENDED_DISPATCH_IDS")
                UserDefaults.standard.set(declined, forKey: "DECLINED_DISPATCH_IDS")
                UserDefaults.standard.set(payloadId, forKey: "USER_EN_SERVICIO")
                let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                Self.activeFirestore().collection("personal").document(userId).setData([
                    "enServicio": payloadId,
                    "_metaTimestamp": nowMs
                ], merge: true)
                Self.dispatchNativeIntentToWebView(dispatchId: payloadId, showFullscreen: false)
            }
        case "ACTION_DECLINE_DISPATCH":
            if !userId.isEmpty && !payloadId.isEmpty {
                var declined = UserDefaults.standard.stringArray(forKey: "DECLINED_DISPATCH_IDS") ?? []
                var attended = UserDefaults.standard.stringArray(forKey: "ATTENDED_DISPATCH_IDS") ?? []
                if !declined.contains(payloadId) { declined.append(payloadId) }
                attended.removeAll { $0 == payloadId }
                UserDefaults.standard.set(declined, forKey: "DECLINED_DISPATCH_IDS")
                UserDefaults.standard.set(attended, forKey: "ATTENDED_DISPATCH_IDS")
                UserDefaults.standard.set("-\(payloadId)", forKey: "USER_EN_SERVICIO")
                let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                Self.activeFirestore().collection("personal").document(userId).setData([
                    "enServicio": "-\(payloadId)",
                    "_metaTimestamp": nowMs
                ], merge: true)
            }
        default:
            if !payloadId.isEmpty {
                Self.dispatchNativeIntentToWebView(dispatchId: payloadId, showFullscreen: true)
            }
        }
        completionHandler()
    }

    static func dispatchNativeIntentToWebView(dispatchId: String, showFullscreen: Bool) {
        guard let wv = sharedWebView else { return }
        let js = """
        (function() {
            try {
                var payload = { dispatchId: "\(dispatchId)", showFullscreenAlert: \(showFullscreen ? "true" : "false"), timestamp: Date.now() };
                window.__SENTINEL_PENDING_INTENT__ = payload;
                window.dispatchEvent(new CustomEvent('sentinelNativeIntent', { detail: payload }));
            } catch (e) {}
        })();
        """
        DispatchQueue.main.async {
            wv.evaluateJavaScript(js, completionHandler: nil)
        }
    }
}

// ============================================================================
// 4. PUENTE WKWEBVIEW <-> SWIFT (SENTINEL ONE V3)
// ============================================================================
final class SentinelWebBridge: NSObject, WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let dict = message.body as? [String: Any],
              let action = dict["action"] as? String else { return }
        let defaults = UserDefaults.standard

        switch action {
        case "syncFirebaseConfig":
            if let cfg = dict["config"] as? [String: Any] {
                AppDelegate.configureDynamicFirebase(configDict: cfg)
            }
        case "requestLocationAlways":
            IOSLocationService.shared.requestAlwaysPermissions()
        case "syncUserSession":
            if let user = dict["user"] as? [String: Any] {
                let userId = (user["idRegistro"] as? String) ?? (user["id"] as? String) ?? ""
                let nombre = (user["nombre"] as? String) ?? "Bombero"
                let rango = (user["rango"] as? String) ?? "Voluntario"
                let compania = (user["compania"] as? String) ?? "1"
                let estado = (user["estado"] as? String) ?? "0-9"
                let conductor = (user["conductor"] as? Bool) ?? false
                let estadoEspecial = (user["estadoEspecial"] as? String) ?? ""
                let enServicio = (user["enServicio"] as? String) ?? ""

                defaults.set(userId, forKey: "USER_ID")
                defaults.set(nombre, forKey: "USER_NOMBRE")
                defaults.set(rango, forKey: "USER_RANGO")
                defaults.set(compania, forKey: "USER_COMPANIA")
                defaults.set(estado, forKey: "USER_ESTADO")
                defaults.set(conductor, forKey: "USER_CONDUCTOR")
                defaults.set(estadoEspecial, forKey: "USER_ESTADO_ESPECIAL")
                defaults.set(enServicio, forKey: "USER_EN_SERVICIO")

                AppDelegate.syncFcmTopicsAndToken(userId: userId)
                AppDelegate.scheduleDailyReminders()
                IOSLocationService.shared.startTracking(
                    serviceId: enServicio,
                    userId: userId,
                    nombre: nombre,
                    rango: rango,
                    compania: compania
                )
            }
        case "clearUserSession":
            let oldId = defaults.string(forKey: "USER_ID") ?? ""
            if !oldId.isEmpty {
                Messaging.messaging().unsubscribe(fromTopic: "usuario_\(oldId)")
                Messaging.messaging().unsubscribe(fromTopic: "personal_\(oldId)")
                Messaging.messaging().unsubscribe(fromTopic: "conductores")
                Messaging.messaging().unsubscribe(fromTopic: "central_operador")
            }
            defaults.removeObject(forKey: "USER_ID")
            IOSSoundPlayer.shared.stop()
            IOSLocationService.shared.stopTracking()
        case "setCentralOperatorMode":
            let active = (dict["active"] as? Bool) ?? false
            defaults.set(active, forKey: "IS_CENTRAL_ACTIVE")
            if active {
                Messaging.messaging().subscribe(toTopic: "central_operador")
            } else {
                Messaging.messaging().unsubscribe(fromTopic: "central_operador")
            }
        case "setAirplaneMode":
            let active = (dict["active"] as? Bool) ?? false
            defaults.set(active, forKey: "MODO_AVION")
            if active { IOSSoundPlayer.shared.stop() }
            AppDelegate.scheduleDailyReminders()
        case "acknowledgeDispatch":
            let dispatchId = (dict["dispatchId"] as? String) ?? ""
            let declined = (dict["declined"] as? Bool) ?? false
            IOSSoundPlayer.shared.stop()
            if !dispatchId.isEmpty {
                IOSSoundPlayer.shared.cancelRepeat(for: dispatchId)
                var declinedSet = defaults.stringArray(forKey: "DECLINED_DISPATCH_IDS") ?? []
                var attendedSet = defaults.stringArray(forKey: "ATTENDED_DISPATCH_IDS") ?? []
                if declined {
                    if !declinedSet.contains(dispatchId) { declinedSet.append(dispatchId) }
                    attendedSet.removeAll { $0 == dispatchId }
                    defaults.set("-\(dispatchId)", forKey: "USER_EN_SERVICIO")
                } else {
                    if !attendedSet.contains(dispatchId) { attendedSet.append(dispatchId) }
                    declinedSet.removeAll { $0 == dispatchId }
                    defaults.set(dispatchId, forKey: "USER_EN_SERVICIO")
                }
                defaults.set(declinedSet, forKey: "DECLINED_DISPATCH_IDS")
                defaults.set(attendedSet, forKey: "ATTENDED_DISPATCH_IDS")
            }
        case "playSound":
            let soundName = (dict["soundName"] as? String) ?? "despacho"
            let forceMax = (dict["forceMaxVolume"] as? Bool) ?? true
            IOSSoundPlayer.shared.play(nameOrClave: soundName, forceMaxVolume: forceMax)
        case "stopSound":
            IOSSoundPlayer.shared.stop()
        case "vibrate":
            IOSSoundPlayer.shared.vibrateEmergency()
        case "syncStorageCache":
            if let key = dict["key"] as? String, !key.isEmpty {
                if let val = dict["value"] as? String, !val.isEmpty {
                    defaults.set(val, forKey: "WEBSTORAGE_\(key)")
                } else {
                    defaults.removeObject(forKey: "WEBSTORAGE_\(key)")
                }
            }
        default:
            break
        }
    }
}

final class SentinelLocalAssetSchemeHandler: NSObject, WKURLSchemeHandler {
    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url else {
            urlSchemeTask.didFailWithError(NSError(domain: "SentinelAsset", code: 400))
            return
        }
        var relPath = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if relPath.isEmpty { relPath = "index.html" }

        let wwwUrl = Bundle.main.resourceURL?.appendingPathComponent("Resources/www", isDirectory: true)
            ?? Bundle.main.resourceURL?.appendingPathComponent("www", isDirectory: true)
            ?? Bundle.main.bundleURL.appendingPathComponent("www", isDirectory: true)
        var targetFile = wwwUrl.appendingPathComponent(relPath)

        // Si pide /_expo/ o /expo_bundle/, soportar ambos nombres de carpeta de forma transparente
        if !FileManager.default.fileExists(atPath: targetFile.path) {
            let swappedPath: String
            if relPath.hasPrefix("_expo/") {
                swappedPath = "expo_bundle/" + String(relPath.dropFirst("_expo/".count))
            } else if relPath.hasPrefix("expo_bundle/") {
                swappedPath = "_expo/" + String(relPath.dropFirst("expo_bundle/".count))
            } else {
                swappedPath = relPath
            }
            let swappedCandidate = wwwUrl.appendingPathComponent(swappedPath)
            if FileManager.default.fileExists(atPath: swappedCandidate.path) {
                targetFile = swappedCandidate
            }
        }

        if !FileManager.default.fileExists(atPath: targetFile.path) {
            let altWww = Bundle.main.resourceURL?.appendingPathComponent("www", isDirectory: true)
            if let altFile = altWww?.appendingPathComponent(relPath),
               FileManager.default.fileExists(atPath: altFile.path) {
                targetFile = altFile
            }
        }

        if !FileManager.default.fileExists(atPath: targetFile.path) {
            let alt = relPath.replacingOccurrences(of: "assets/assets/", with: "assets/")
            let altFile = wwwUrl.appendingPathComponent(alt)
            if FileManager.default.fileExists(atPath: altFile.path) {
                targetFile = altFile
            } else if let rootAlt = Bundle.main.resourceURL?.appendingPathComponent(relPath),
                      FileManager.default.fileExists(atPath: rootAlt.path) {
                targetFile = rootAlt
            }
        }

        guard let data = try? Data(contentsOf: targetFile) else {
            urlSchemeTask.didFailWithError(NSError(domain: "SentinelAsset", code: 404))
            return
        }

        let ext = targetFile.pathExtension.lowercased()
        let mime: String
        switch ext {
        case "html": mime = "text/html"
        case "js": mime = "application/javascript"
        case "css": mime = "text/css"
        case "json": mime = "application/json"
        case "png": mime = "image/png"
        case "jpg", "jpeg": mime = "image/jpeg"
        case "gif": mime = "image/gif"
        case "svg": mime = "image/svg+xml"
        case "ico": mime = "image/x-icon"
        case "mp3": mime = "audio/mpeg"
        case "ttf": mime = "font/ttf"
        case "woff": mime = "font/woff"
        case "woff2": mime = "font/woff2"
        default: mime = "application/octet-stream"
        }

        let response = HTTPURLResponse(
            url: url,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: [
                "Content-Type": mime,
                "Content-Length": "\(data.count)",
                "Access-Control-Allow-Origin": "*"
            ]
        )!
        urlSchemeTask.didReceive(response)
        urlSchemeTask.didReceive(data)
        urlSchemeTask.didFinish()
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}
}

struct SentinelWebViewContainer: UIViewRepresentable {
    private let bridge = SentinelWebBridge()
    private let schemeHandler = SentinelLocalAssetSchemeHandler()

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.userContentController.add(bridge, name: "iOSApp")
        config.setURLSchemeHandler(schemeHandler, forURLScheme: "sentinel")

        // Pre-inyectar plataforma iOS y restaurar caché nativo en window.localStorage antes de que arranque React
        let defaults = UserDefaults.standard
        let storageKeys = [
            "@sentinel_one_v3_license_cache",
            "@sentinel_one_v3_user_session",
            "@sentinel_one_v3_user_prefs",
            "@sentinel_one_location_disclosed_v3"
        ]
        var storageMap: [String: String] = [:]
        for k in storageKeys {
            if let val = defaults.string(forKey: "WEBSTORAGE_\(k)"), !val.isEmpty {
                storageMap[k] = val
            }
        }
        let storageJson: String
        if let jsonData = try? JSONSerialization.data(withJSONObject: storageMap),
           let jsonStr = String(data: jsonData, encoding: .utf8) {
            storageJson = jsonStr
        } else {
            storageJson = "{}"
        }

        let bootJs = """
        (function() {
            try {
                window.__SENTINEL_PLATFORM__ = 'ios';
                window.__SENTINEL_IOS_PERMS__ = window.__SENTINEL_IOS_PERMS__ || {
                    fineLocation: false,
                    backgroundLocation: false,
                    overlay: true,
                    batteryUnrestricted: true,
                    notifications: true
                };
                var restored = \(storageJson);
                if (window.localStorage && restored) {
                    Object.keys(restored).forEach(function(k) {
                        if (!window.localStorage.getItem(k) && restored[k]) {
                            window.localStorage.setItem(k, restored[k]);
                        }
                    });
                }
            } catch (e) {}
        })();
        """
        let userScript = WKUserScript(source: bootJs, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        config.userContentController.addUserScript(userScript)

        let wv = WKWebView(frame: .zero, configuration: config)
        wv.isOpaque = false
        wv.backgroundColor = UIColor(red: 6/255, green: 9/255, blue: 17/255, alpha: 1.0)
        wv.scrollView.bounces = false
        wv.scrollView.isScrollEnabled = false
        wv.scrollView.showsVerticalScrollIndicator = false
        wv.scrollView.showsHorizontalScrollIndicator = false

        AppDelegate.sharedWebView = wv

        if let startUrl = URL(string: "sentinel://appassets.androidplatform.net/index.html") {
            wv.load(URLRequest(url: startUrl))
        } else if let indexUrl = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "www") {
            wv.loadFileURL(indexUrl, allowingReadAccessTo: indexUrl.deletingLastPathComponent())
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            IOSLocationService.shared.syncPermissionsToWebView()
        }
        return wv
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

@main
struct SisBomApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            SentinelWebViewContainer()
                .ignoresSafeArea()
                .preferredColorScheme(.dark)
        }
    }
}
