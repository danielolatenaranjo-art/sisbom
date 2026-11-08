import SwiftUI
import UIKit
import WebKit
import UserNotifications
import AVFoundation
import AudioToolbox
import MediaPlayer
import CoreLocation
import Security
import FirebaseCore
import FirebaseAuth
import FirebaseFirestore
import FirebaseMessaging
import FirebaseDatabase

// ============================================================================
// 0. KEYCHAIN PERSISTENTE (SOBREVIVE A DESINSTALACIÓN Y REINSTALACIÓN EN iOS)
// ============================================================================
enum SentinelKeychain {
    private static let service = "com.misisbom.sisbom.v3.keychain"

    static func get(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func set(_ key: String, value: String) {
        guard let data = value.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
        var addQuery = query
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(addQuery as CFDictionary, nil)
    }

    static func remove(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }

    static func getOrCreateDeviceId() -> String {
        if let existing = get("hw_device_id"), !existing.isEmpty {
            return existing
        }
        let vendorId = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        let newId = "ios-hw-\(vendorId)"
        set("hw_device_id", value: newId)
        return newId
    }
}

// ============================================================================
// 1. GESTOR DE UBICACIÓN GPS EN SEGUNDO PLANO EN iOS -> EXCLUSIVAMENTE A RTDB
// ============================================================================
final class IOSLocationService: NSObject, CLLocationManagerDelegate {
    static let shared = IOSLocationService()
    private let manager = CLLocationManager()
    private(set) var isTracking: Bool = false
    private(set) var isStandbyKeepAlive: Bool = false
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
            let defaults = UserDefaults.standard
            let askedAlwaysBefore = defaults.bool(forKey: "IOS_ASKED_ALWAYS_LOCATION")
            defaults.set(true, forKey: "IOS_ASKED_ALWAYS_LOCATION")
            manager.requestAlwaysAuthorization()
            if askedAlwaysBefore {
                DispatchQueue.main.async {
                    if let settingsUrl = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(settingsUrl, options: [:], completionHandler: nil)
                    }
                }
            }
        } else if status == .denied || status == .restricted {
            DispatchQueue.main.async {
                if let settingsUrl = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(settingsUrl, options: [:], completionHandler: nil)
                }
            }
        }
        syncPermissionsToWebView()
        updateStandbyKeepAlive()
    }

    func requestNotificationsPermission() {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            if settings.authorizationStatus == .notDetermined {
                center.requestAuthorization(options: [.alert, .badge, .sound, .criticalAlert]) { granted, _ in
                    if !granted {
                        center.requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in
                            self.syncPermissionsToWebView()
                        }
                    } else {
                        self.syncPermissionsToWebView()
                    }
                    DispatchQueue.main.async {
                        UIApplication.shared.registerForRemoteNotifications()
                    }
                }
            } else if settings.authorizationStatus == .denied {
                DispatchQueue.main.async {
                    if let settingsUrl = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(settingsUrl, options: [:], completionHandler: nil)
                    }
                }
            } else {
                DispatchQueue.main.async {
                    UIApplication.shared.registerForRemoteNotifications()
                }
                self.syncPermissionsToWebView()
            }
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if #available(iOS 14.0, *) {
            if manager.authorizationStatus == .authorizedWhenInUse {
                UserDefaults.standard.set(true, forKey: "IOS_ASKED_ALWAYS_LOCATION")
                manager.requestAlwaysAuthorization()
            }
        }
        syncPermissionsToWebView()
        updateStandbyKeepAlive()
    }

    func currentSynchronousPerms() -> (fine: Bool, bg: Bool, notif: Bool) {
        let status: CLAuthorizationStatus
        if #available(iOS 14.0, *) {
            status = manager.authorizationStatus
        } else {
            status = CLLocationManager.authorizationStatus()
        }
        let fine = (status == .authorizedAlways || status == .authorizedWhenInUse)
        let bg = (status == .authorizedAlways)
        let notif = UserDefaults.standard.bool(forKey: "IOS_NOTIF_GRANTED")
        return (fine, bg, notif)
    }

    func syncPermissionsToWebView() {
        let status: CLAuthorizationStatus
        if #available(iOS 14.0, *) {
            status = manager.authorizationStatus
        } else {
            status = CLLocationManager.authorizationStatus()
        }
        let fine = (status == .authorizedAlways || status == .authorizedWhenInUse)
        let bg = (status == .authorizedAlways)
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let notifGranted = (
                settings.authorizationStatus == .authorized ||
                settings.authorizationStatus == .provisional ||
                settings.authorizationStatus == .ephemeral
            )
            let defaults = UserDefaults.standard
            defaults.set(notifGranted, forKey: "IOS_NOTIF_GRANTED")
            if fine && bg && notifGranted {
                defaults.set("true", forKey: "WEBSTORAGE_@sentinel_one_location_disclosed_v3")
            }
            DispatchQueue.main.async {
                guard let wv = AppDelegate.sharedWebView else { return }
                let js = """
                (function() {
                    try {
                        window.__SENTINEL_IOS_PERMS__ = {
                            fineLocation: \(fine ? "true" : "false"),
                            backgroundLocation: \(bg ? "true" : "false"),
                            overlay: true,
                            batteryUnrestricted: true,
                            notifications: \(notifGranted ? "true" : "false")
                        };
                        if (\(fine && bg && notifGranted ? "true" : "false") && window.localStorage) {
                            window.localStorage.setItem('@sentinel_one_location_disclosed_v3', 'true');
                        }
                        window.dispatchEvent(new CustomEvent('sentinelPermissionsChanged'));
                    } catch (e) {}
                })();
                """
                wv.evaluateJavaScript(js, completionHandler: nil)
            }
        }
    }

    func updateStandbyKeepAlive() {
        let defaults = UserDefaults.standard
        let isAirplane = defaults.bool(forKey: "MODO_AVION")
        let estado = (defaults.string(forKey: "USER_ESTADO") ?? "0-8").uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        let enServicio = (defaults.string(forKey: "USER_EN_SERVICIO") ?? "0").trimmingCharacters(in: .whitespacesAndNewlines)

        // REGLA ESTRICTA GPS: Jamás activar GPS en standby ni si el bombero está 0-8, enServicio == "0" o "-X"
        if isAirplane || estado != "0-9" || enServicio.isEmpty || enServicio == "0" || enServicio.hasPrefix("-") ||
           enServicio == "EN EL CUARTEL" || enServicio == "EN LA COMPAÑIA" || enServicio == "EN LA COMPAÑÍA" ||
           enServicio.hasPrefix("UNIDAD:") {
            if isStandbyKeepAlive {
                isStandbyKeepAlive = false
            }
            stopTracking()
            return
        }

        if !isTracking {
            if isStandbyKeepAlive {
                isStandbyKeepAlive = false
            }
            manager.stopUpdatingLocation()
            manager.stopMonitoringSignificantLocationChanges()
            manager.showsBackgroundLocationIndicator = false
        }
    }

    func startTracking(serviceId: String, userId: String, nombre: String, rango: String, compania: String) {
        let defaults = UserDefaults.standard
        let isAirplane = defaults.bool(forKey: "MODO_AVION")
        let estado = (defaults.string(forKey: "USER_ESTADO") ?? "0-8").uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        let clean = serviceId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !isAirplane, estado == "0-9",
              !clean.isEmpty, clean != "0", !clean.hasPrefix("-"),
              clean != "EN EL CUARTEL", clean != "EN LA COMPAÑIA", clean != "EN LA COMPAÑÍA",
              !clean.hasPrefix("UNIDAD:") else {
            stopTracking()
            return
        }
        if isTracking && activeServiceId == clean { return }

        isStandbyKeepAlive = false
        activeServiceId = clean
        activeUserId = userId
        activeNombre = nombre
        activeRango = rango
        activeCompania = compania
        isTracking = true

        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = 5.0
        manager.startUpdatingLocation()
    }

    func stopTracking() {
        let oldUserId = activeUserId ?? UserDefaults.standard.string(forKey: "USER_ID")
        isTracking = false
        isStandbyKeepAlive = false
        activeServiceId = nil
        activeUserId = nil
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        manager.showsBackgroundLocationIndicator = false
        if let uid = oldUserId, !uid.isEmpty {
            removeFromRtdb(userId: uid)
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let defaults = UserDefaults.standard
        let isAirplane = defaults.bool(forKey: "MODO_AVION")
        let estado = (defaults.string(forKey: "USER_ESTADO") ?? "0-8").uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        let enServicio = (defaults.string(forKey: "USER_EN_SERVICIO") ?? "0").trimmingCharacters(in: .whitespacesAndNewlines)
        guard isTracking, !isAirplane, estado == "0-9",
              !enServicio.isEmpty, enServicio != "0", !enServicio.hasPrefix("-"),
              let loc = locations.last, let userId = activeUserId, !userId.isEmpty else {
            stopTracking()
            return
        }
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

        guard let projectId = UserDefaults.standard.string(forKey: "saas_project_id"), !projectId.isEmpty else { return }
        let dbUrl = UserDefaults.standard.string(forKey: "saas_database_url") ?? "https://\(projectId)-default-rtdb.firebaseio.com"
        let cleanBase = dbUrl.hasSuffix("/") ? String(dbUrl.dropLast()) : dbUrl
        guard let url = URL(string: "\(cleanBase)/telemetria/bomberos/\(userId).json") else { return }

        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        URLSession.shared.dataTask(with: req) { _, _, _ in }.resume()
    }

    func removeFromRtdb(userId: String) {
        guard let projectId = UserDefaults.standard.string(forKey: "saas_project_id"), !projectId.isEmpty else { return }
        let dbUrl = UserDefaults.standard.string(forKey: "saas_database_url") ?? "https://\(projectId)-default-rtdb.firebaseio.com"
        let cleanBase = dbUrl.hasSuffix("/") ? String(dbUrl.dropLast()) : dbUrl
        guard let url = URL(string: "\(cleanBase)/telemetria/bomberos/\(userId).json") else { return }

        var req = URLRequest(url: url)
        req.httpMethod = "DELETE"
        URLSession.shared.dataTask(with: req) { _, _, _ in }.resume()
    }

    func clearFinishedServiceNodesInRtdb() {
        guard let projectId = UserDefaults.standard.string(forKey: "saas_project_id"), !projectId.isEmpty else { return }
        let dbUrl = UserDefaults.standard.string(forKey: "saas_database_url") ?? "https://\(projectId)-default-rtdb.firebaseio.com"
        let cleanBase = dbUrl.hasSuffix("/") ? String(dbUrl.dropLast()) : dbUrl
        for path in ["telemetria/bomberos", "telemetria/alertantes"] {
            if let url = URL(string: "\(cleanBase)/\(path).json") {
                var req = URLRequest(url: url)
                req.httpMethod = "DELETE"
                URLSession.shared.dataTask(with: req) { _, _, _ in }.resume()
            }
        }
    }
}

// ============================================================================
// 2. GESTOR DE SONIDOS Y VIBRACIÓN TÁCTICA EN iOS (BYPASS SILENT SWITCH EN 0-9)
// ============================================================================
final class IOSSoundPlayer: NSObject, AVAudioPlayerDelegate {
    static let shared = IOSSoundPlayer()
    private var audioPlayer: AVAudioPlayer?
    private var keepAlivePlayer: AVAudioPlayer?
    private var repeatTimers: [String: Timer] = [:]
    private var hiddenVolumeView: MPVolumeView?
    private(set) var lastAlarmStartMs: Int64 = 0
    private var lastPlayedSoundKey: String = ""

    override init() {
        super.init()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance()
        )
    }

    @objc private func handleAudioInterruption(_ notification: Notification) {
        guard let info = notification.userInfo,
              let typeVal = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeVal) else { return }
        if type == .ended {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                self?.updateBackgroundKeepAlive()
            }
        }
    }

    var isPlayingAlarm: Bool {
        return audioPlayer?.isPlaying == true
    }

    func isAlarmProtectedFromAutoStop(windowMs: Int64 = 25_000) -> Bool {
        guard let active = audioPlayer, active.isPlaying else { return false }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        return (nowMs - lastAlarmStartMs) < windowMs
    }

    func prepareVolumeViewInWindow() {
        let attachBlock: () -> Void = { [weak self] in
            guard let self = self else { return }
            if let existing = self.hiddenVolumeView, existing.window != nil {
                return
            }
            let vv = self.hiddenVolumeView ?? MPVolumeView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
            vv.frame = CGRect(x: 0, y: 0, width: 1, height: 1)
            vv.alpha = 0.015
            vv.clipsToBounds = true
            vv.isUserInteractionEnabled = false
            vv.showsRouteButton = false
            if let winScene = UIApplication.shared.connectedScenes.first(where: { $0 is UIWindowScene }) as? UIWindowScene,
               let win = winScene.windows.first(where: { $0.isKeyWindow }) ?? winScene.windows.first {
                win.addSubview(vv)
                self.hiddenVolumeView = vv
            } else if let win = UIApplication.shared.windows.first {
                win.addSubview(vv)
                self.hiddenVolumeView = vv
            }
        }
        if Thread.isMainThread {
            attachBlock()
        } else {
            DispatchQueue.main.async(execute: attachBlock)
        }
    }

    func forceHardwareSystemVolumeToMax() {
        let applyMax: () -> Void = { [weak self] in
            guard let self = self else { return }
            self.prepareVolumeViewInWindow()
            guard let vv = self.hiddenVolumeView else { return }
            vv.setNeedsLayout()
            vv.layoutIfNeeded()
            for subview in vv.subviews {
                if let slider = subview as? UISlider {
                    slider.setValue(1.0, animated: false)
                    slider.sendActions(for: [.touchUpInside, .valueChanged])
                }
            }
        }
        if Thread.isMainThread {
            applyMax()
        } else {
            DispatchQueue.main.async(execute: applyMax)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.08, execute: applyMax)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.22, execute: applyMax)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45, execute: applyMax)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.85, execute: applyMax)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.35, execute: applyMax)
    }

    func resolveSoundFileName(for claveOrName: String) -> String {
        let c = claveOrName.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ".MP3", with: "")
        if c.contains("FORESTAL") || c == "C10_2FORESTAL" { return "c10_2Forestal" }
        if c.contains("10-30") || c == "C10_30" { return "c10_30" }
        if c.contains("9-0") || c == "C9_0" { return "c9_0" }
        if c.contains("LLAMADO") || c.contains("COMANDANCIA") || c == "C_LLAMADOCOMANDANCIA" { return "c_llamadoComandancia" }
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
        if c == "ALERTA" || c == "ALERTAS" { return "alertas" }
        if c == "IMPORTANTE" { return "importante" }
        return "despacho"
    }

    private static func makeSilentWavData() -> Data {
        let sampleRate: UInt32 = 8000
        let numSamples: UInt32 = 8000 // 1 segundo de silencio digital puro (muestras PCM = 0)
        let bytesPerSample: UInt16 = 2
        let numChannels: UInt16 = 1
        let dataSize: UInt32 = numSamples * UInt32(bytesPerSample)
        let riffChunkSize: UInt32 = 36 + dataSize
        let byteRate: UInt32 = sampleRate * UInt32(numChannels) * UInt32(bytesPerSample)
        let blockAlign: UInt16 = numChannels * bytesPerSample
        let bitsPerSample: UInt16 = 16

        var data = Data(capacity: Int(44 + dataSize))
        data.append(contentsOf: [0x52, 0x49, 0x46, 0x46]) // "RIFF"
        var leRiff = riffChunkSize.littleEndian
        withUnsafeBytes(of: &leRiff) { data.append(contentsOf: $0) }
        data.append(contentsOf: [0x57, 0x41, 0x56, 0x45]) // "WAVE"
        data.append(contentsOf: [0x66, 0x6D, 0x74, 0x20]) // "fmt "
        var subchunk1Size: UInt32 = 16
        withUnsafeBytes(of: &subchunk1Size) { data.append(contentsOf: $0) }
        var audioFormat: UInt16 = 1 // PCM
        withUnsafeBytes(of: &audioFormat) { data.append(contentsOf: $0) }
        var leChannels = numChannels.littleEndian
        withUnsafeBytes(of: &leChannels) { data.append(contentsOf: $0) }
        var leSampleRate = sampleRate.littleEndian
        withUnsafeBytes(of: &leSampleRate) { data.append(contentsOf: $0) }
        var leByteRate = byteRate.littleEndian
        withUnsafeBytes(of: &leByteRate) { data.append(contentsOf: $0) }
        var leBlockAlign = blockAlign.littleEndian
        withUnsafeBytes(of: &leBlockAlign) { data.append(contentsOf: $0) }
        var leBits = bitsPerSample.littleEndian
        withUnsafeBytes(of: &leBits) { data.append(contentsOf: $0) }
        data.append(contentsOf: [0x64, 0x61, 0x74, 0x61]) // "data"
        var leDataSize = dataSize.littleEndian
        withUnsafeBytes(of: &leDataSize) { data.append(contentsOf: $0) }
        data.append(Data(count: Int(dataSize))) // 100% muestras en 0 (silencio absoluto)
        return data
    }

    func updateBackgroundKeepAlive() {
        let defaults = UserDefaults.standard
        let isAirplane = defaults.bool(forKey: "MODO_AVION")
        let isCentral = defaults.bool(forKey: "IS_CENTRAL_ACTIVE")
        let estado = (defaults.string(forKey: "USER_ESTADO") ?? "0-9").uppercased().trimmingCharacters(in: .whitespacesAndNewlines)

        guard !isAirplane && !isCentral && estado == "0-9" else {
            stopBackgroundKeepAlive()
            return
        }

        // En primer plano NO necesitamos reproducir ningún stream keep-alive
        if AppDelegate.isAppInForeground {
            stopBackgroundKeepAlive()
            return
        }

        // Si ya está sonando la alarma real o ya está corriendo el keep-alive silencioso, mantener activo
        if let active = audioPlayer, active.isPlaying {
            return
        }
        if let ka = keepAlivePlayer, ka.isPlaying {
            ka.volume = 0.0
            return
        }

        do {
            let session = AVAudioSession.sharedInstance()
            try? session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try? session.setActive(true)
            let silentWav = Self.makeSilentWavData()
            let player = try AVAudioPlayer(data: silentWav, fileTypeHint: AVFileType.wav.rawValue)
            // Silencio PCM puro (muestras = 0) + volumen 0.0: jamás emite ningún tono ni ruido por el parlante
            player.volume = 0.0
            player.numberOfLoops = -1
            player.prepareToPlay()
            player.play()
            self.keepAlivePlayer = player
        } catch {}
    }

    func stopBackgroundKeepAlive() {
        keepAlivePlayer?.stop()
        keepAlivePlayer = nil
    }

    func play(nameOrClave: String, forceMaxVolume: Bool = true) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in
                self?.play(nameOrClave: nameOrClave, forceMaxVolume: forceMaxVolume)
            }
            return
        }
        let defaults = UserDefaults.standard
        let upper = nameOrClave.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if defaults.bool(forKey: "MODO_AVION") {
            return
        }
        if defaults.bool(forKey: "IS_CENTRAL_ACTIVE") && upper != "ALERTA" && upper != "ALERTAS" && upper != "IMPORTANTE" {
            return
        }
        let fileName = resolveSoundFileName(for: nameOrClave)
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)

        // Evitar que Push + Firestore Snapshot disparen el mismo MP3 con milisegundos de diferencia cortándose entre sí
        if let active = audioPlayer, active.isPlaying, fileName == lastPlayedSoundKey, (nowMs - lastAlarmStartMs) < 3_500 {
            if forceMaxVolume {
                forceHardwareSystemVolumeToMax()
            }
            vibrateEmergency()
            return
        }

        audioPlayer?.stop()
        audioPlayer = nil
        keepAlivePlayer?.volume = 0.0

        guard let url = Bundle.main.url(forResource: fileName, withExtension: "mp3")
                ?? Bundle.main.url(forResource: fileName.lowercased(), withExtension: "mp3")
                ?? Bundle.main.url(forResource: "despacho", withExtension: "mp3")
                ?? Bundle.main.url(forResource: "alertas", withExtension: "mp3") else {
            vibrateEmergency()
            return
        }

        let session = AVAudioSession.sharedInstance()
        if forceMaxVolume {
            // CRÍTICO: Usar [.mixWithOthers, .duckOthers] con .playback en segundo plano
            // para saltar el interruptor físico de silencio (botón de silencio) SIN lanzar error 561015905 (!pla)
            if (try? session.setCategory(.playback, mode: .default, options: [.mixWithOthers, .duckOthers])) == nil {
                try? session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            }
        } else {
            try? session.setCategory(.ambient, mode: .default, options: [.mixWithOthers])
        }
        try? session.setActive(true)

        do {
            let player = try AVAudioPlayer(contentsOf: url)
            player.delegate = self
            player.volume = 1.0
            player.prepareToPlay()
            player.play()
            self.audioPlayer = player
            self.lastAlarmStartMs = nowMs
            self.lastPlayedSoundKey = fileName
            if forceMaxVolume {
                forceHardwareSystemVolumeToMax()
            }
            vibrateEmergency()
        } catch {
            vibrateEmergency()
        }
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        if player === audioPlayer {
            audioPlayer = nil
            lastAlarmStartMs = 0
            lastPlayedSoundKey = ""
            updateBackgroundKeepAlive()
        }
    }

    func stop() {
        audioPlayer?.stop()
        audioPlayer = nil
        lastAlarmStartMs = 0
        lastPlayedSoundKey = ""
        updateBackgroundKeepAlive()
    }

    func vibrateEmergency() {
        AudioServicesPlayAlertSound(kSystemSoundID_Vibrate)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            AudioServicesPlayAlertSound(kSystemSoundID_Vibrate)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
            AudioServicesPlayAlertSound(kSystemSoundID_Vibrate)
        }
    }

    func cancelRepeat(for dispatchId: String) {
        let cancelBlock: () -> Void = { [weak self] in
            guard let self = self else { return }
            let keys = self.repeatTimers.keys.filter { $0 == dispatchId || $0.hasPrefix("\(dispatchId)_") }
            for k in keys {
                self.repeatTimers[k]?.invalidate()
                self.repeatTimers.removeValue(forKey: k)
            }
            let notifId = "repeat_60s_\(dispatchId)"
            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [notifId])
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [notifId])
        }
        if Thread.isMainThread {
            cancelBlock()
        } else {
            DispatchQueue.main.async(execute: cancelBlock)
        }
    }

    func scheduleRepeat(dispatchId: String, clave: String) {
        let scheduleBlock: () -> Void = { [weak self] in
            guard let self = self else { return }
            self.cancelRepeat(for: dispatchId)

            // 1. Respaldo del sistema iOS a los 60s exactos por si el dispositivo bloqueado congela el proceso
            let fileName = self.resolveSoundFileName(for: clave)
            let content = UNMutableNotificationContent()
            content.title = "🚨 RECORDATORIO DESPACHO \(clave)"
            content.body = "Aún no has confirmado asistencia (ASISTIR / NO ASISTIR)"
            content.categoryIdentifier = "EMERGENCY_DISPATCH"
            content.userInfo = ["type": "DISPATCH", "payloadId": dispatchId, "clave": clave, "isRepeat60s": "true"]
            content.sound = UNNotificationSound(named: UNNotificationSoundName("\(fileName).mp3"))
            if #available(iOS 15.0, *) {
                content.interruptionLevel = .timeSensitive
                content.relevanceScore = 1.0
            }
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 60.0, repeats: false)
            let req = UNNotificationRequest(identifier: "repeat_60s_\(dispatchId)", content: content, trigger: trigger)
            UNUserNotificationCenter.current().add(req, withCompletionHandler: nil)

            // 2. Timer en RunLoop.main (.common) para disparar el segundo tono al 100% de volumen saltando modo silencio
            let timer = Timer(timeInterval: 60.0, repeats: false) { [weak self] _ in
                guard let self = self else { return }
                self.repeatTimers.removeValue(forKey: dispatchId)
                let defaults = UserDefaults.standard
                if defaults.bool(forKey: "MODO_AVION") || defaults.bool(forKey: "IS_CENTRAL_ACTIVE") { return }
                let estado = (defaults.string(forKey: "USER_ESTADO") ?? "0-8").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                if estado != "0-9" { return }
                let declined = defaults.stringArray(forKey: "DECLINED_DISPATCH_IDS") ?? []
                let attended = defaults.stringArray(forKey: "ATTENDED_DISPATCH_IDS") ?? []
                if declined.contains(dispatchId) || attended.contains(dispatchId) { return }

                let enServicio = (defaults.string(forKey: "USER_EN_SERVICIO") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                if enServicio == dispatchId || enServicio == "-\(dispatchId)" { return }

                // El segundo tono a los 60 segundos SIEMPRE debe sonar si el bombero no ha puesto Asistir o No Asistir
                self.play(nameOrClave: clave, forceMaxVolume: true)
                AppDelegate.dispatchNativeIntentToWebView(dispatchId: dispatchId, showFullscreen: true)
            }
            RunLoop.main.add(timer, forMode: .common)
            self.repeatTimers[dispatchId] = timer
        }
        if Thread.isMainThread {
            scheduleBlock()
        } else {
            DispatchQueue.main.async(execute: scheduleBlock)
        }
    }
}

// ============================================================================
// 2.5. SERVICIO NATIVO DE TIEMPO REAL EN iOS (DESPACHOS, CENTRAL Y ALERTAS)
// ============================================================================
final class IOSRealtimeService {
    static let shared = IOSRealtimeService()
    private var despachosListener: ListenerRegistration?
    private var centralListener: ListenerRegistration?
    private var vehiculosListener: ListenerRegistration?
    private var alertasListener: ListenerRegistration?
    private var personalListener: ListenerRegistration?
    private var sciIncidentesListener: ListenerRegistration?
    private var sciSubcolListeners: [String: ListenerRegistration] = [:]
    private var alertedSciIds = Set<String>()
    private var activeProjectId: String?
    private var isFirstDispatchSnapshot = true
    private var isFirstPersonalSnapshot = true
    private var knownDispatchIds = Set<String>()
    private var lastDispatchClaves: [String: String] = [:]
    private var lastDispatchEstados: [String: String] = [:]
    private var lastUnitConductorReq: [String: Int64] = [:]
    private var lastUnitPersonalReq: [String: Int64] = [:]
    private var latestCentralPayload: [String: Any]?
    private var latestDespachosPayload: [[String: Any]]?
    private var latestVehiculosPayload: [[String: Any]]?
    private var latestAlertasPayload: [[String: Any]]?
    private var latestPersonalPayload: [[String: Any]]?
    private var lastNativeSnapshotAt: TimeInterval = 0

    private init() {}

    func sanitizeFirestoreValue(_ val: Any?) -> Any {
        guard let v = val else { return NSNull() }
        if v is NSNull {
            return NSNull()
        }
        if let ts = v as? Timestamp {
            return Int64(ts.dateValue().timeIntervalSince1970 * 1000)
        }
        if let dt = v as? Date {
            return Int64(dt.timeIntervalSince1970 * 1000)
        }
        if let gp = v as? GeoPoint {
            return ["lat": gp.latitude, "lng": gp.longitude]
        }
        if let ref = v as? DocumentReference {
            return ref.path
        }
        if let dict = v as? [String: Any] {
            var out: [String: Any] = [:]
            for (k, subVal) in dict {
                out[k] = sanitizeFirestoreValue(subVal)
            }
            return out
        }
        if let arr = v as? [Any] {
            return arr.map { sanitizeFirestoreValue($0) }
        }
        if let str = v as? String {
            let trimmed = str.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed == "<null>" || trimmed == "null" {
                return NSNull()
            }
            return str
        }
        if v is NSNumber || v is Bool || v is Int || v is Int64 || v is Double {
            return v
        }
        return "\(v)"
    }

    func sanitizeDoc(_ dict: [String: Any]) -> [String: Any] {
        var out: [String: Any] = [:]
        for (k, v) in dict {
            out[k] = sanitizeFirestoreValue(v)
        }
        return out
    }

    func stopAll() {
        despachosListener?.remove()
        despachosListener = nil
        centralListener?.remove()
        centralListener = nil
        vehiculosListener?.remove()
        vehiculosListener = nil
        alertasListener?.remove()
        alertasListener = nil
        personalListener?.remove()
        personalListener = nil
        sciIncidentesListener?.remove()
        sciIncidentesListener = nil
        for (_, subL) in sciSubcolListeners { subL.remove() }
        sciSubcolListeners.removeAll()
        activeProjectId = nil
    }

    func ensureListenersRunning() {
        let currentPid = AppDelegate.currentTenantProjectId ?? FirebaseApp.app()?.options.projectID ?? ""
        let nowSec = Date().timeIntervalSince1970
        let isStale = (lastNativeSnapshotAt > 0) && ((nowSec - lastNativeSnapshotAt) > 120.0)
        if despachosListener != nil && centralListener != nil && !currentPid.isEmpty && activeProjectId == currentPid && !isStale {
            // Re-sincronizar los últimos snapshots cacheados al WebView sin reiniciar los listeners ni perder despachos en curso
            if let c = latestCentralPayload {
                pushSnapshotToWebView(collection: "central", payload: c)
            }
            if let d = latestDespachosPayload {
                pushSnapshotToWebView(collection: "despachos", payload: d)
            }
            if let v = latestVehiculosPayload {
                pushSnapshotToWebView(collection: "vehiculos", payload: v)
            }
            if let a = latestAlertasPayload {
                pushSnapshotToWebView(collection: "alertas", payload: a)
            }
            if let p = latestPersonalPayload {
                pushSnapshotToWebView(collection: "personal", payload: p)
            }
            return
        }
        startListeners(resetFirstFlags: activeProjectId != currentPid)
    }

    func restartListenersPreservingState() {
        let currentPid = AppDelegate.currentTenantProjectId ?? FirebaseApp.app()?.options.projectID ?? ""
        guard !currentPid.isEmpty else { return }
        startListeners(resetFirstFlags: false)
    }

    func startListeners(resetFirstFlags: Bool = true) {
        stopAll()
        guard AppDelegate.currentTenantProjectId != nil || FirebaseApp.app() != nil else { return }
        activeProjectId = AppDelegate.currentTenantProjectId ?? FirebaseApp.app()?.options.projectID
        if resetFirstFlags {
            isFirstDispatchSnapshot = true
            isFirstPersonalSnapshot = true
        }
        let db = AppDelegate.activeFirestore()
        db.enableNetwork(completion: nil)

        // 1. Listener nativo de accesos/central
        centralListener = db.collection("accesos").document("central").addSnapshotListener { [weak self] snap, error in
            guard let self = self else { return }
            if error != nil {
                self.centralListener?.remove()
                self.centralListener = nil
                return
            }
            self.lastNativeSnapshotAt = Date().timeIntervalSince1970
            let raw = snap?.data() ?? [:]
            let clean = self.sanitizeDoc(raw)

            let estadoCentral = ((clean["estado"] as? String) ?? "").lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
            let isClosed = estadoCentral.isEmpty || estadoCentral == "cerrado" || estadoCentral == "inactivo"
            let rawOpId = clean["idRegistro"]
            let opId = isClosed ? "" : (rawOpId != nil && !(rawOpId is NSNull) ? "\(rawOpId!)" : "").trimmingCharacters(in: .whitespacesAndNewlines)
            let opName = isClosed ? "" : ((clean["operador"] as? String) ?? (clean["nombreBombero"] as? String) ?? "").uppercased().trimmingCharacters(in: .whitespacesAndNewlines)

            let defaults = UserDefaults.standard
            let myId = (defaults.string(forKey: "USER_ID") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let myName = (defaults.string(forKey: "USER_NOMBRE") ?? "").uppercased().trimmingCharacters(in: .whitespacesAndNewlines)

            let isMeCentral = !isClosed && (
                (!opId.isEmpty && !myId.isEmpty && opId == myId) ||
                (!opName.isEmpty && !myName.isEmpty && opName == myName)
            )
            defaults.set(isMeCentral, forKey: "IS_CENTRAL_ACTIVE")
            if isMeCentral {
                IOSSoundPlayer.shared.stop()
            }

            self.latestCentralPayload = clean
            self.pushSnapshotToWebView(collection: "central", payload: clean)
        }

        // 2. Listener nativo de despachos activos
        despachosListener = db.collection("despachos")
            .whereField("estado", in: ["activa", "ACTIVA", "activo", "ACTIVO", "pre-despacho", "PRE-DESPACHO", "en curso", "EN CURSO"])
            .addSnapshotListener { [weak self] snap, error in
            guard let self = self else { return }
            if error != nil {
                self.despachosListener?.remove()
                self.despachosListener = nil
                DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
                    AppDelegate.authenticateNativeApp(AppDelegate.activeFirebaseApp(), forceRefresh: true) {
                        self.restartListenersPreservingState()
                    }
                }
                return
            }
            self.lastNativeSnapshotAt = Date().timeIntervalSince1970
            guard let documents = snap?.documents else { return }
            var rawDocsArray: [[String: Any]] = []
            let defaults = UserDefaults.standard
            let isAirplane = defaults.bool(forKey: "MODO_AVION")
            let isCentral = defaults.bool(forKey: "IS_CENTRAL_ACTIVE")
            let userEstado = (defaults.string(forKey: "USER_ESTADO") ?? "0-9").uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
            let is09 = (userEstado == "0-9")
            let isConductor = defaults.bool(forKey: "USER_CONDUCTOR")
            let attendedIds = defaults.stringArray(forKey: "ATTENDED_DISPATCH_IDS") ?? []
            let declinedIds = defaults.stringArray(forKey: "DECLINED_DISPATCH_IDS") ?? []
            var alertedIds = defaults.stringArray(forKey: "ALERTED_DISPATCH_IDS") ?? []
            var didModifyAlerted = false
            let userEnServicio = (defaults.string(forKey: "USER_EN_SERVICIO") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)

            for doc in documents {
                let docId = doc.documentID
                let cleanData = self.sanitizeDoc(doc.data())
                rawDocsArray.append([
                    "id": docId,
                    "data": cleanData,
                    "hasPendingWrites": doc.metadata.hasPendingWrites
                ])

                let estado = ((cleanData["estado"] as? String) ?? "activa").lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
                let rawOpFinal = ((cleanData["operadorFinal"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                let opFinal = (rawOpFinal == "<null>" || rawOpFinal == "null") ? "" : rawOpFinal
                let finalizadoAt = (cleanData["finalizadoAt"] as? NSNumber)?.int64Value ?? 0
                let cerrado = (cleanData["cerrado"] as? Bool) ?? false
                let isClosed = cerrado || !opFinal.isEmpty || finalizadoAt > 0 || ["cerrado", "cerrada", "finalizado", "finalizada", "cancelado", "cancelada", "terminado", "terminada"].contains(estado)
                if isClosed {
                    IOSSoundPlayer.shared.cancelRepeat(for: docId)
                    continue
                }

                let clave = ((cleanData["clave"] as? String) ?? "10-0").trimmingCharacters(in: .whitespacesAndNewlines)
                let rawDir = ((cleanData["direccion"] as? String) ?? (cleanData["lugar"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                let direccion = !rawDir.isEmpty ? rawDir : (estado == "pre-despacho" ? "Pre-despacho en preparación..." : "Ubicación en curso")

                var createdTs: Int64 = 0
                if let n = cleanData["createdAt"] as? NSNumber {
                    createdTs = n.int64Value
                } else if let s = cleanData["createdAt"] as? String, let v = Int64(s) {
                    createdTs = v
                } else if let n = cleanData["creadoEn"] as? NSNumber {
                    createdTs = n.int64Value
                } else if let n = cleanData["timestamp"] as? NSNumber {
                    createdTs = n.int64Value
                } else if let s = cleanData["timestamp"] as? String, let v = Int64(s) {
                    createdTs = v
                }
                let isLateFiveMin: Bool = (createdTs > 1_000_000_000_000) && ((nowMs - createdTs) > 300_000) // > 5 minutos

                let isAttending = attendedIds.contains(docId) || userEnServicio == docId
                let hasDeclined = declinedIds.contains(docId) || userEnServicio == "-\(docId)"

                if self.isFirstDispatchSnapshot {
                    self.knownDispatchIds.insert(docId)
                    self.lastDispatchClaves[docId] = clave
                    self.lastDispatchEstados[docId] = estado
                    if let unidades = (cleanData["unidades"] as? [String: Any]) ?? (cleanData["unidadesEnServicio"] as? [String: Any]) {
                        for (uName, uVal) in unidades {
                            if let uMap = uVal as? [String: Any] {
                                let cTs = (uMap["solicitudConductorTimestamp"] as? NSNumber)?.int64Value ?? 0
                                let pTs = (uMap["solicitudPersonalTimestamp"] as? NSNumber)?.int64Value ?? 0
                                self.lastUnitConductorReq["\(docId)_\(uName)"] = cTs
                                self.lastUnitPersonalReq["\(docId)_\(uName)"] = pTs
                            }
                        }
                    }
                    // Al abrir la app por primera vez:
                    // - Si el despacho tiene < 5 min y la app está en primer plano: solo vibrar y abrir pantalla sobre pantalla.
                    // - Si el despacho tiene > 5 min (llegó tardío por mala señal): NO sonar sirena, solo vibrar y mostrar notificación silenciosa.
                    if !alertedIds.contains(docId) && !isAttending && !hasDeclined && !isAirplane && !isCentral {
                        alertedIds.append(docId)
                        didModifyAlerted = true
                        if isLateFiveMin {
                            IOSSoundPlayer.shared.vibrateEmergency()
                            if !AppDelegate.isAppInForeground {
                                self.postLocalBanner(
                                    id: "disp_\(docId)",
                                    title: "⚠️ NOTIFICACIÓN TARDÍA POR MALA COBERTURA TELEFÓNICA",
                                    body: "DESPACHO \(clave): \(direccion)",
                                    soundName: nil,
                                    playDefaultIfNil: false,
                                    userInfo: ["type": "DISPATCH", "payloadId": docId, "clave": clave]
                                )
                            }
                        } else if AppDelegate.isAppInForeground {
                            IOSSoundPlayer.shared.vibrateEmergency()
                            if is09 {
                                AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                            }
                        } else {
                            if is09 {
                                IOSSoundPlayer.shared.play(nameOrClave: clave, forceMaxVolume: true)
                                IOSSoundPlayer.shared.scheduleRepeat(dispatchId: docId, clave: clave)
                                AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                            } else {
                                IOSSoundPlayer.shared.vibrateEmergency()
                            }
                            self.postLocalBanner(
                                id: "disp_\(docId)",
                                title: "🚨 DESPACHO \(clave)",
                                body: direccion,
                                soundName: nil,
                                playDefaultIfNil: !is09,
                                userInfo: ["type": "DISPATCH", "payloadId": docId, "clave": clave]
                            )
                        }
                    }
                    continue
                }

                if isAirplane || isCentral {
                    self.knownDispatchIds.insert(docId)
                    self.lastDispatchClaves[docId] = clave
                    self.lastDispatchEstados[docId] = estado
                    if !alertedIds.contains(docId) {
                        alertedIds.append(docId)
                        didModifyAlerted = true
                    }
                    continue
                }

                    // A. Nuevo despacho o pre-despacho en vivo (Primer tono + programación del segundo tono a los 60s; si tiene > 5 min por mala señal, solo vibrar y mostrar notificación)
                if !self.knownDispatchIds.contains(docId) {
                    self.knownDispatchIds.insert(docId)
                    self.lastDispatchClaves[docId] = clave
                    self.lastDispatchEstados[docId] = estado
                    if !alertedIds.contains(docId) {
                        alertedIds.append(docId)
                        didModifyAlerted = true
                    }
                    if !isAttending && !hasDeclined {
                        if isLateFiveMin {
                            IOSSoundPlayer.shared.vibrateEmergency()
                            self.postLocalBanner(
                                id: "disp_\(docId)",
                                title: "⚠️ NOTIFICACIÓN TARDÍA POR MALA COBERTURA TELEFÓNICA",
                                body: "DESPACHO \(clave): \(direccion)",
                                soundName: nil,
                                playDefaultIfNil: false,
                                userInfo: ["type": "DISPATCH", "payloadId": docId, "clave": clave]
                            )
                        } else if AppDelegate.isAppInForeground {
                            IOSSoundPlayer.shared.vibrateEmergency()
                            if is09 {
                                IOSSoundPlayer.shared.scheduleRepeat(dispatchId: docId, clave: clave)
                                AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                            }
                        } else {
                            if is09 {
                                IOSSoundPlayer.shared.play(nameOrClave: clave, forceMaxVolume: true)
                                IOSSoundPlayer.shared.scheduleRepeat(dispatchId: docId, clave: clave)
                                AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                            } else {
                                IOSSoundPlayer.shared.vibrateEmergency()
                            }
                            self.postLocalBanner(
                                id: "disp_\(docId)",
                                title: "🚨 DESPACHO \(clave)",
                                body: direccion,
                                soundName: nil,
                                playDefaultIfNil: !is09,
                                userInfo: ["type": "DISPATCH", "payloadId": docId, "clave": clave]
                            )
                        }
                    }
                } else {
                    // A2. Transición de pre-despacho -> activa / en curso:
                    // NO debe volver a sonar ni reiniciar el contador de 60 segundos; el segundo tono es únicamente a los 60s del primer tono.
                    let prevEstado = self.lastDispatchEstados[docId] ?? ""
                    self.lastDispatchEstados[docId] = estado
                    let isPreToActive = (prevEstado == "pre-despacho") && (estado == "activa" || estado == "activo" || estado == "en curso")
                    if isPreToActive && !isAttending && !hasDeclined && is09 {
                        AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                    }

                    // B. Escalamiento (10-30 / FORESTAL / 9-0)
                    let prevClave = self.lastDispatchClaves[docId] ?? ""
                    if !prevClave.isEmpty && prevClave != clave {
                        self.lastDispatchClaves[docId] = clave
                        let cUp = clave.uppercased()
                        let isEscalation = cUp.contains("10-30") || cUp.contains("FORESTAL") || cUp.contains("9-0") || cUp.contains("COMANDANCIA")
                        if isEscalation && !isAttending {
                            AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                            if AppDelegate.isAppInForeground {
                                IOSSoundPlayer.shared.vibrateEmergency()
                            } else {
                                IOSSoundPlayer.shared.play(nameOrClave: clave, forceMaxVolume: true)
                                self.postLocalBanner(
                                    id: "esc_\(docId)_\(clave)",
                                    title: "🔥 CAMBIO DE CLAVE A \(clave)",
                                    body: "\(direccion) - ¡SE REQUIERE PERSONAL!",
                                    soundName: nil,
                                    playDefaultIfNil: false,
                                    userInfo: ["type": "DISPATCH_UPDATE", "payloadId": docId, "clave": clave]
                                )
                            }
                        }
                    } else {
                        self.lastDispatchClaves[docId] = clave
                    }
                }

                // C. Solicitudes 12-10 (Conductor) y 6-6 (Personal)
                if let unidades = (cleanData["unidades"] as? [String: Any]) ?? (cleanData["unidadesEnServicio"] as? [String: Any]) {
                    for (uName, uVal) in unidades {
                        guard let uMap = uVal as? [String: Any] else { continue }
                        let cTs = (uMap["solicitudConductorTimestamp"] as? NSNumber)?.int64Value ?? 0
                        let pTs = (uMap["solicitudPersonalTimestamp"] as? NSNumber)?.int64Value ?? 0
                        let key = "\(docId)_\(uName)"

                        let prevCTs = self.lastUnitConductorReq[key] ?? 0
                        if cTs > prevCTs && cTs > 0 {
                            self.lastUnitConductorReq[key] = cTs
                            if isConductor && !isAttending && !hasDeclined {
                                if AppDelegate.isAppInForeground {
                                    IOSSoundPlayer.shared.vibrateEmergency()
                                    if is09 {
                                        AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                                    }
                                } else {
                                    if is09 {
                                        IOSSoundPlayer.shared.play(nameOrClave: clave.isEmpty ? "despacho" : clave, forceMaxVolume: true)
                                        AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                                    } else {
                                        IOSSoundPlayer.shared.vibrateEmergency()
                                    }
                                    self.postLocalBanner(
                                        id: "req1210_\(key)_\(cTs)",
                                        title: "🚨 SOLICITUD 12-10 (\(uName))",
                                        body: "Se solicita conductor para unidad \(uName) en \(direccion)",
                                        soundName: nil,
                                        playDefaultIfNil: !is09,
                                        userInfo: ["type": "DISPATCH_UPDATE", "payloadId": docId, "clave": clave]
                                    )
                                }
                            }
                        } else if prevCTs == 0 {
                            self.lastUnitConductorReq[key] = cTs
                        }

                        let prevPTs = self.lastUnitPersonalReq[key] ?? 0
                        if pTs > prevPTs && pTs > 0 {
                            self.lastUnitPersonalReq[key] = pTs
                            if !isAttending && !hasDeclined {
                                if AppDelegate.isAppInForeground {
                                    IOSSoundPlayer.shared.vibrateEmergency()
                                    if is09 {
                                        AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                                    }
                                } else {
                                    if is09 {
                                        IOSSoundPlayer.shared.play(nameOrClave: clave.isEmpty ? "despacho" : clave, forceMaxVolume: true)
                                        AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                                    } else {
                                        IOSSoundPlayer.shared.vibrateEmergency()
                                    }
                                    self.postLocalBanner(
                                        id: "req66_\(key)_\(pTs)",
                                        title: "🚨 SOLICITUD 6-6 (\(uName))",
                                        body: "Se solicita personal para unidad \(uName) en \(direccion)",
                                        soundName: nil,
                                        playDefaultIfNil: !is09,
                                        userInfo: ["type": "DISPATCH_UPDATE", "payloadId": docId, "clave": clave]
                                    )
                                }
                            }
                        } else if prevPTs == 0 {
                            self.lastUnitPersonalReq[key] = pTs
                        }
                    }
                }
            }

            if didModifyAlerted {
                if alertedIds.count > 100 {
                    alertedIds = Array(alertedIds.suffix(100))
                }
                defaults.set(alertedIds, forKey: "ALERTED_DISPATCH_IDS")
            }

            let hasAnyOpenDispatch = rawDocsArray.contains { item in
                guard let d = item["data"] as? [String: Any] else { return false }
                let est = ((d["estado"] as? String) ?? "activa").lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
                let rawOp = ((d["operadorFinal"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                let opF = (rawOp == "<null>" || rawOp == "null") ? "" : rawOp
                let finAt = (d["finalizadoAt"] as? NSNumber)?.int64Value ?? 0
                let cerr = (d["cerrado"] as? Bool) ?? false
                return !(cerr || !opF.isEmpty || finAt > 0 || ["cerrado", "cerrada", "finalizado", "finalizada", "cancelado", "cancelada", "terminado", "terminada"].contains(est))
            }
            if !hasAnyOpenDispatch {
                let curSvc = (defaults.string(forKey: "USER_EN_SERVICIO") ?? "0").trimmingCharacters(in: .whitespacesAndNewlines)
                if !curSvc.isEmpty && curSvc != "0" {
                    defaults.set("0", forKey: "USER_EN_SERVICIO")
                }
                IOSLocationService.shared.stopTracking()
                IOSLocationService.shared.clearFinishedServiceNodesInRtdb()
            }

            self.isFirstDispatchSnapshot = false
            self.latestDespachosPayload = rawDocsArray
            self.pushSnapshotToWebView(collection: "despachos", payload: rawDocsArray)
        }

        // 3. Listener nativo individual de personal/{myId} (sincronización de estado y aviso si la Central cambia el estado del usuario, 1 solo documento igual que en Android)
        let currentDefaults = UserDefaults.standard
        let currentMyId = (currentDefaults.string(forKey: "USER_ID") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !currentMyId.isEmpty {
            personalListener = db.collection("personal").document(currentMyId).addSnapshotListener { [weak self] doc, error in
                guard let self = self else { return }
                if error != nil {
                    self.personalListener?.remove()
                    self.personalListener = nil
                    return
                }
                guard let doc = doc, doc.exists, let rawData = doc.data() else { return }
                let defaults = UserDefaults.standard
                let cleanData = self.sanitizeDoc(rawData)
                let rawEst = ((cleanData["estado"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                let newEst = (!rawEst.isEmpty ? rawEst : (((cleanData["disponible"] as? Bool) == true) ? "0-9" : "0-8")).uppercased()
                let oldEst = (defaults.string(forKey: "USER_ESTADO") ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                let newSvc = AppDelegate.stringFromAny(cleanData["enServicio"])
                defaults.set(newSvc, forKey: "USER_EN_SERVICIO")
                if newEst != "0-9" || newSvc.isEmpty || newSvc == "0" || newSvc.hasPrefix("-") {
                    IOSLocationService.shared.stopTracking()
                }
                let changedByCentral = (cleanData["_changedByCentral"] as? Bool) ?? false

                if !newEst.isEmpty && newEst != oldEst {
                    defaults.set(newEst, forKey: "USER_ESTADO")
                    IOSSoundPlayer.shared.updateBackgroundKeepAlive()
                    IOSLocationService.shared.updateStandbyKeepAlive()

                    if !self.isFirstPersonalSnapshot && changedByCentral {
                        let lastSelfStatus = (defaults.string(forKey: "LAST_SELF_STATUS_CHANGE") ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                        let lastSelfTime = defaults.double(forKey: "LAST_SELF_STATUS_TIME")
                        let nowMs = Date().timeIntervalSince1970 * 1000.0
                        let isSelfChange = (newEst == lastSelfStatus) && ((nowMs - lastSelfTime) < 20_000.0)

                        let lastNotifStatus = (defaults.string(forKey: "LAST_NOTIFIED_STATUS_CHANGE") ?? "").uppercased()
                        let lastNotifTime = defaults.double(forKey: "LAST_NOTIFIED_STATUS_TIME")
                        let alreadyNotified = (newEst == lastNotifStatus) && ((nowMs - lastNotifTime) < 10_000.0)

                        if !isSelfChange && !alreadyNotified && !defaults.bool(forKey: "MODO_AVION") {
                            defaults.set(newEst, forKey: "LAST_NOTIFIED_STATUS_CHANGE")
                            defaults.set(nowMs, forKey: "LAST_NOTIFIED_STATUS_TIME")
                            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                            self.postLocalBanner(
                                id: "status_\(Int(nowMs))",
                                title: "ESTADO ACTUALIZADO",
                                body: "Tu estado ha sido cambiado a \(newEst) por la Central",
                                soundName: nil,
                                userInfo: ["type": "STATUS_CHANGE", "newStatus": newEst]
                            )
                        }
                    }
                }
                self.isFirstPersonalSnapshot = false
            }
        }

        // 4. Listener nativo de vehiculos (sincronización hacia WebView)
        vehiculosListener = db.collection("vehiculos").addSnapshotListener { [weak self] snap, error in
            guard let self = self else { return }
            if error != nil {
                self.vehiculosListener?.remove()
                self.vehiculosListener = nil
                return
            }
            self.lastNativeSnapshotAt = Date().timeIntervalSince1970
            guard let documents = snap?.documents else { return }
            var docsArr: [[String: Any]] = []
            for doc in documents {
                docsArr.append([
                    "id": doc.documentID,
                    "data": self.sanitizeDoc(doc.data()),
                    "hasPendingWrites": doc.metadata.hasPendingWrites
                ])
            }
            self.latestVehiculosPayload = docsArr
            self.pushSnapshotToWebView(collection: "vehiculos", payload: docsArr)
        }

        // 5. Listener nativo de alertas (sincronización hacia WebView)
        alertasListener = db.collection("alertas").addSnapshotListener { [weak self] snap, error in
            guard let self = self else { return }
            if error != nil {
                self.alertasListener?.remove()
                self.alertasListener = nil
                return
            }
            self.lastNativeSnapshotAt = Date().timeIntervalSince1970
            guard let documents = snap?.documents else { return }
            var docsArr: [[String: Any]] = []
            for doc in documents {
                docsArr.append([
                    "id": doc.documentID,
                    "data": self.sanitizeDoc(doc.data()),
                    "hasPendingWrites": doc.metadata.hasPendingWrites
                ])
            }
            self.latestAlertasPayload = docsArr
            self.pushSnapshotToWebView(collection: "alertas", payload: docsArr)
        }

        // 6. Listener nativo de sisbom-central/sci_incidentes (para Comandante idRadial == "1" en segundo plano)
        startCentralSciListenerIfNeeded()
    }

    func startCentralSciListenerIfNeeded() {
        let defaults = UserDefaults.standard
        let idRadial = (defaults.string(forKey: "USER_ID_RADIAL") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let licenseKey = (defaults.string(forKey: "LICENSE_KEY") ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard idRadial == "1", !licenseKey.isEmpty else {
            sciIncidentesListener?.remove()
            sciIncidentesListener = nil
            for (_, subL) in sciSubcolListeners { subL.remove() }
            sciSubcolListeners.removeAll()
            return
        }
        if sciIncidentesListener != nil { return }
        guard let centralDb = AppDelegate.centralFirestore() else { return }

        sciIncidentesListener = centralDb.collection("sci_incidentes").addSnapshotListener { [weak self] snap, error in
            guard let self = self, error == nil, let docs = snap?.documents else { return }
            let myLic = (UserDefaults.standard.string(forKey: "LICENSE_KEY") ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            guard !myLic.isEmpty else { return }

            var activeIds = Set<String>()
            for incDoc in docs {
                let rawInc = incDoc.data()
                let estInc = AppDelegate.stringFromAny(rawInc["estado"]).uppercased()
                let cerrado = (rawInc["cerrado"] as? Bool) == true || ["FINALIZADO", "FINALIZADA", "CERRADO", "CERRADA", "CANCELADO", "CANCELADA"].contains(estInc)
                if cerrado { continue }
                let origenLic = AppDelegate.stringFromAny(rawInc["licenciaOrigen"] ?? rawInc["cuerpoSolicitanteLicencia"]).uppercased()
                if origenLic == myLic { continue }

                let incId = incDoc.documentID
                activeIds.insert(incId)
                if self.sciSubcolListeners[incId] == nil {
                    let subL = centralDb.collection("sci_incidentes").document(incId).collection("solicitudes").addSnapshotListener { [weak self] solSnap, solErr in
                        guard let self = self, solErr == nil, let solDocs = solSnap?.documents else { return }
                        let curLic = (UserDefaults.standard.string(forKey: "LICENSE_KEY") ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                        let isAirplane = UserDefaults.standard.bool(forKey: "MODO_AVION")
                        guard !curLic.isEmpty, !isAirplane else { return }

                        var storedIds = Set(UserDefaults.standard.stringArray(forKey: "ALERTED_SCI_REQ_IDS") ?? [])
                        var didAdd = false
                        for solDoc in solDocs {
                            let sData = solDoc.data()
                            let destLic = AppDelegate.stringFromAny(sData["licenciaDestino"] ?? sData["cuerpoReceptorLicencia"] ?? sData["idCuerpo"]).uppercased()
                            if destLic != curLic { continue }
                            let estSol = AppDelegate.stringFromAny(sData["estado"]).uppercased()
                            if ["RECHAZADA", "RECHAZADO", "CANCELADA", "CANCELADO", "CERRADO", "CERRADA"].contains(estSol) { continue }
                            if estSol == "PENDIENTE" || estSol == "ALERTA" || estSol == "SOLICITADA" || estSol.isEmpty {
                                let alertKey = "sci_req_\(solDoc.documentID)"
                                if !self.alertedSciIds.contains(alertKey) && !storedIds.contains(alertKey) {
                                    self.alertedSciIds.insert(alertKey)
                                    storedIds.insert(alertKey)
                                    didAdd = true
                                    let cSol = AppDelegate.stringFromAny(sData["cuerpoSolicitanteNombre"] ?? rawInc["cuerpoSolicitanteNombre"], fallback: "Cuerpo Vecino")
                                    let uSol = AppDelegate.stringFromAny(sData["idCarroSolicitado"] ?? sData["unidad"])
                                    let claveInc = AppDelegate.stringFromAny(rawInc["clave"], fallback: "SCI")
                                    let dirInc = AppDelegate.stringFromAny(rawInc["direccion"] ?? rawInc["lugar"])
                                    let isGeneral = uSol.isEmpty || uSol.uppercased() == "ALERTA GENERAL" || uSol.uppercased() == "CUALQUIERA"
                                    let title = !isGeneral ? "🚨 SOLICITUD SCI [\(uSol)] [\(solDoc.documentID)]" : "🚨 ALERTA PREVENTIVA SCI [\(solDoc.documentID)]"
                                    let body = !isGeneral ? "\(cSol) solicita despacho de \(uSol) (\(claveInc) \(dirInc))" : "\(cSol) solicita apoyo inter-cuerpos (\(claveInc) \(dirInc))"
                                    if AppDelegate.isAppInForeground {
                                        IOSSoundPlayer.shared.vibrateEmergency()
                                    } else {
                                        IOSSoundPlayer.shared.play(nameOrClave: "alertas", forceMaxVolume: true)
                                        self.postLocalBanner(
                                            id: alertKey,
                                            title: title,
                                            body: body,
                                            soundName: "alertas.mp3",
                                            playDefaultIfNil: false,
                                            userInfo: ["type": "SCI_ALERT", "payloadId": solDoc.documentID]
                                        )
                                    }
                                }
                            }
                        }
                        if didAdd {
                            UserDefaults.standard.set(Array(storedIds.suffix(150)), forKey: "ALERTED_SCI_REQ_IDS")
                        }
                    }
                    self.sciSubcolListeners[incId] = subL
                }
            }
            for oldKey in Array(self.sciSubcolListeners.keys) where !activeIds.contains(oldKey) {
                self.sciSubcolListeners[oldKey]?.remove()
                self.sciSubcolListeners.removeValue(forKey: oldKey)
            }
        }
    }

    func postLocalBanner(id: String, title: String, body: String, soundName: String?, playDefaultIfNil: Bool = true, userInfo: [String: Any]) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.userInfo = userInfo
        content.categoryIdentifier = "EMERGENCY_DISPATCH"
        if #available(iOS 15.0, *) {
            content.interruptionLevel = .timeSensitive
            content.relevanceScore = 1.0
        }
        if let sName = soundName, !sName.isEmpty {
            content.sound = UNNotificationSound(named: UNNotificationSoundName(rawValue: sName))
        } else if playDefaultIfNil {
            content.sound = .default
        } else {
            content.sound = nil
        }
        let req = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req, withCompletionHandler: nil)
    }

    func pushSnapshotToWebView(collection: String, payload: Any) {
        let envelope: [String: Any] = [
            "collection": collection,
            "payload": payload,
            "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
        ]
        guard JSONSerialization.isValidJSONObject(envelope),
              let data = try? JSONSerialization.data(withJSONObject: envelope),
              let jsonStr = String(data: data, encoding: .utf8) else { return }

        DispatchQueue.main.async {
            guard let wv = AppDelegate.sharedWebView else { return }
            let js = """
            (function() {
                try {
                    var msg = \(jsonStr);
                    window.__SENTINEL_NATIVE_SNAPSHOTS__ = window.__SENTINEL_NATIVE_SNAPSHOTS__ || {};
                    window.__SENTINEL_NATIVE_SNAPSHOTS__[msg.collection] = msg.payload;
                    window.dispatchEvent(new CustomEvent('sentinelNativeFirestoreSync', { detail: msg }));
                } catch (e) {}
            })();
            """
            wv.evaluateJavaScript(js, completionHandler: nil)
        }
    }
}

// ============================================================================
// 3. APP DELEGATE: FIREBASE DINÁMICO, RECORDATORIOS DIARIOS Y NOTIFICACIONES PUSH
// ============================================================================
class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {
    static weak var shared: AppDelegate?
    static var sharedWebView: WKWebView?
    static var currentTenantProjectId: String?
    static var cachedApnsDeviceToken: Data?
    static var isAppInForeground: Bool = true
    static var lastBackgroundTimestamp: TimeInterval = 0
    private var bgTaskId: UIBackgroundTaskIdentifier = .invalid

    private static let knownIosAppIdsByProject: [String: String] = [
        "sisbom-central": "1:432594591351:ios:a2be8d98704f46d5beb072",
        "pruebas-sisbom": "1:306101642399:ios:a7ba95a2a0a0130c91aeaa",
        "sisbom-de5f8": "1:39449538456:ios:784ffe39d1b3b8857485ed"
    ]

    static func resolveValidIosAppId(projectId: String, configDict: [String: Any]) -> String {
        if let explicitIos = configDict["iosAppId"] as? String, explicitIos.contains(":ios:") {
            return explicitIos
        }
        if let mapped = knownIosAppIdsByProject[projectId] {
            return mapped
        }
        if let rawAppId = configDict["appId"] as? String, rawAppId.contains(":ios:") {
            return rawAppId
        }
        let senderId = (configDict["messagingSenderId"] as? String) ?? "432594591351"
        return "1:\(senderId):ios:a2be8d98704f46d5beb072"
    }

    static func unlinkPreviousProjectSessionIfNeeded(newProjectId: String) {
        let prevPid = SentinelKeychain.get("last_project_id") ?? ""
        let prevApiKey = SentinelKeychain.get("last_api_key") ?? ""
        let prevUid = SentinelKeychain.get("last_user_id") ?? ""
        guard !prevPid.isEmpty, !prevApiKey.isEmpty, !prevUid.isEmpty, prevPid != newProjectId else {
            return
        }
        // Al desinstalar/reinstalar e ingresar a OTRA licencia distinta, desvincular automáticamente el usuario del proyecto anterior vía REST
        let authUrlStr = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=\(prevApiKey)"
        guard let authUrl = URL(string: authUrlStr) else { return }
        var authReq = URLRequest(url: authUrl)
        authReq.httpMethod = "POST"
        authReq.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let authBody: [String: Any] = [
            "email": "v304.app@sisbom.com",
            "password": "SentinelOne_V304_Auth!2026",
            "returnSecureToken": true
        ]
        authReq.httpBody = try? JSONSerialization.data(withJSONObject: authBody)
        URLSession.shared.dataTask(with: authReq) { data, _, error in
            guard let data = data, error == nil,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let idToken = json["idToken"] as? String, !idToken.isEmpty else {
                return
            }
            let encodedUid = prevUid.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? prevUid
            let patchUrlStr = "https://firestore.googleapis.com/v1/projects/\(prevPid)/databases/(default)/documents/personal/\(encodedUid)?updateMask.fieldPaths=deviceId&updateMask.fieldPaths=apnsToken&updateMask.fieldPaths=_metaTimestamp"
            guard let patchUrl = URL(string: patchUrlStr) else { return }
            var patchReq = URLRequest(url: patchUrl)
            patchReq.httpMethod = "PATCH"
            patchReq.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
            patchReq.setValue("application/json", forHTTPHeaderField: "Content-Type")
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let patchPayload: [String: Any] = [
                "fields": [
                    "deviceId": ["stringValue": ""],
                    "apnsToken": ["stringValue": ""],
                    "_metaTimestamp": ["integerValue": "\(nowMs)"]
                ]
            ]
            patchReq.httpBody = try? JSONSerialization.data(withJSONObject: patchPayload)
            URLSession.shared.dataTask(with: patchReq) { _, _, _ in
                SentinelKeychain.remove("last_user_id")
            }.resume()
        }.resume()
    }

    static func configureDynamicFirebase(configDict: [String: Any]) {
        guard let apiKey = configDict["apiKey"] as? String, !apiKey.isEmpty,
              let projectId = configDict["projectId"] as? String, !projectId.isEmpty else {
            return
        }
        unlinkPreviousProjectSessionIfNeeded(newProjectId: projectId)
        SentinelKeychain.set("last_project_id", value: projectId)
        SentinelKeychain.set("last_api_key", value: apiKey)
        let iosAppId = resolveValidIosAppId(projectId: projectId, configDict: configDict)
        let senderId = (configDict["messagingSenderId"] as? String) ?? "306101642399"
        let storageBucket = (configDict["storageBucket"] as? String) ?? "\(projectId).firebasestorage.app"
        let dbUrl = (configDict["databaseURL"] as? String) ?? "https://\(projectId)-default-rtdb.firebaseio.com"
        let bundleId = Bundle.main.bundleIdentifier ?? "com.misisbom.sisbom.NFR3L3CC3L"

        var enrichedConfig = configDict
        enrichedConfig["iosAppId"] = iosAppId

        UserDefaults.standard.set(projectId, forKey: "saas_project_id")
        UserDefaults.standard.set(dbUrl, forKey: "saas_database_url")
        if let jsonData = try? JSONSerialization.data(withJSONObject: enrichedConfig),
           let jsonStr = String(data: jsonData, encoding: .utf8) {
            UserDefaults.standard.set(jsonStr, forKey: "saas_firebase_config")
        }

        let options = FirebaseOptions(googleAppID: iosAppId, gcmSenderID: senderId)
        options.apiKey = apiKey
        options.projectID = projectId
        options.storageBucket = storageBucket
        options.databaseURL = dbUrl
        options.bundleID = bundleId

        let finalizeTenantSetup: () -> Void = {
            currentTenantProjectId = projectId
            if let delegate = shared {
                Messaging.messaging().delegate = delegate
            }
            if let apnsTok = cachedApnsDeviceToken {
                Messaging.messaging().apnsToken = apnsTok
            }
            restoreUserDefaultsFromCachedWebSessionIfNeeded()
            let targetApp = activeFirebaseApp()
            authenticateNativeApp(targetApp) {
                IOSRealtimeService.shared.ensureListenersRunning()
                IOSSoundPlayer.shared.updateBackgroundKeepAlive()
                IOSLocationService.shared.updateStandbyKeepAlive()
                let userId = UserDefaults.standard.string(forKey: "USER_ID") ?? ""
                if !userId.isEmpty {
                    syncFcmTopicsAndToken(userId: userId)
                }
            }
        }

        if FirebaseApp.app() == nil {
            FirebaseApp.configure(options: options)
            finalizeTenantSetup()
        } else if FirebaseApp.app()?.options.projectID != projectId {
            IOSRealtimeService.shared.stopAll()
            let appName = "tenant_\(projectId)"
            if FirebaseApp.app(name: appName) == nil {
                FirebaseApp.configure(name: appName, options: options)
            }
            finalizeTenantSetup()
        } else {
            finalizeTenantSetup()
        }
    }

    static func activeFirebaseApp() -> FirebaseApp? {
        if let pid = currentTenantProjectId, let namedApp = FirebaseApp.app(name: "tenant_\(pid)") {
            return namedApp
        }
        return FirebaseApp.app()
    }

    static func stringFromAny(_ val: Any?, fallback: String = "") -> String {
        guard let v = val, !(v is NSNull) else { return fallback }
        if let s = v as? String {
            let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
            return (trimmed == "<null>" || trimmed == "null") ? fallback : trimmed
        }
        return "\(v)".trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func restoreUserDefaultsFromCachedWebSessionIfNeeded() {
        let defaults = UserDefaults.standard
        guard let rawSession = defaults.string(forKey: "WEBSTORAGE_@sentinel_one_v3_user_session"),
              let data = rawSession.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return
        }
        let userId = stringFromAny(dict["idRegistro"] ?? dict["id"])
        if !userId.isEmpty {
            defaults.set(userId, forKey: "USER_ID")
        }
        let nombre = stringFromAny(dict["nombre"], fallback: "Bombero")
        if !nombre.isEmpty {
            defaults.set(nombre, forKey: "USER_NOMBRE")
        }
        let rango = stringFromAny(dict["rango"], fallback: "Voluntario")
        if !rango.isEmpty {
            defaults.set(rango, forKey: "USER_RANGO")
        }
        let compania = stringFromAny(dict["compania"], fallback: "1")
        if !compania.isEmpty {
            defaults.set(compania, forKey: "USER_COMPANIA")
        }
        let estado = stringFromAny(dict["estado"])
        if !estado.isEmpty {
            defaults.set(estado.uppercased(), forKey: "USER_ESTADO")
        }
        if let cond = dict["conductor"] as? Bool {
            defaults.set(cond, forKey: "USER_CONDUCTOR")
        } else if let condNum = dict["conductor"] as? NSNumber {
            defaults.set(condNum.boolValue, forKey: "USER_CONDUCTOR")
        }
    }

    static func authenticateNativeApp(_ app: FirebaseApp?, forceRefresh: Bool = false, completion: (() -> Void)? = nil) {
        guard let targetApp = app else {
            completion?()
            return
        }
        let auth = Auth.auth(app: targetApp)
        // IMPORTANTE: firestore.rules bloquea usuarios anónimos (sign_in_provider != 'anonymous').
        // Si hay una sesión anónima cacheada de versiones anteriores, debemos reemplazarla por login autenticado.
        if let currentUser = auth.currentUser, !currentUser.isAnonymous {
            if forceRefresh {
                currentUser.getIDTokenForcingRefresh(true) { token, error in
                    if error == nil && token != nil {
                        completion?()
                    } else {
                        signInNativeWithStoredCredentials(auth: auth, completion: completion)
                    }
                }
                return
            }
            completion?()
            return
        }
        signInNativeWithStoredCredentials(auth: auth, completion: completion)
    }

    private static func signInNativeWithStoredCredentials(auth: Auth, completion: (() -> Void)?) {
        if let rawSession = UserDefaults.standard.string(forKey: "WEBSTORAGE_@sentinel_one_v3_user_session"),
           let data = rawSession.data(using: .utf8),
           let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            let idReg = stringFromAny(dict["idRegistro"] ?? dict["id"])
            let pass = stringFromAny(dict["contrasena"] ?? dict["password"])
            if !idReg.isEmpty && !pass.isEmpty {
                let email = idReg.contains("@") ? idReg : "\(idReg)@sisbom.com"
                let securePass = pass.hasSuffix("_secure_sisbom") ? pass : "\(pass)_secure_sisbom"
                auth.signIn(withEmail: email, password: securePass) { result, error in
                    if error != nil || result == nil {
                        auth.signIn(withEmail: "v304.app@sisbom.com", password: "SentinelOne_V304_Auth!2026") { _, _ in
                            completion?()
                        }
                    } else {
                        completion?()
                    }
                }
                return
            }
        }
        auth.signIn(withEmail: "v304.app@sisbom.com", password: "SentinelOne_V304_Auth!2026") { _, _ in
            completion?()
        }
    }

    static func activeFirestore() -> Firestore {
        if let pid = currentTenantProjectId, let namedApp = FirebaseApp.app(name: "tenant_\(pid)") {
            return Firestore.firestore(app: namedApp)
        }
        return Firestore.firestore()
    }

    static func centralFirestore() -> Firestore? {
        let appName = "sentinel_central_saas"
        if let existing = FirebaseApp.app(name: appName) {
            return Firestore.firestore(app: existing)
        }
        let options = FirebaseOptions(googleAppID: "1:432594591351:ios:a2be8d98704f46d5beb072", gcmSenderID: "432594591351")
        options.apiKey = "AIzaSyBjjSBwmUevGC0vT0GdxtQUw-yvawdrH44"
        options.projectID = "sisbom-central"
        options.storageBucket = "sisbom-central.firebasestorage.app"
        options.bundleID = Bundle.main.bundleIdentifier ?? "com.misisbom.sisbom.NFR3L3CC3L"
        FirebaseApp.configure(name: appName, options: options)
        if let created = FirebaseApp.app(name: appName) {
            return Firestore.firestore(app: created)
        }
        return nil
    }

    private static func resolveStoredTenantConfig() -> [String: Any]? {
        let defaults = UserDefaults.standard
        if let cachedStr = defaults.string(forKey: "saas_firebase_config"),
           let data = cachedStr.data(using: .utf8),
           let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let pid = dict["projectId"] as? String, !pid.isEmpty {
            return dict
        }
        if let webLic = defaults.string(forKey: "WEBSTORAGE_@sentinel_one_v3_license_cache"),
           let data = webLic.data(using: .utf8),
           let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if let fbCfg = dict["firebaseConfig"] as? [String: Any],
               let pid = fbCfg["projectId"] as? String, !pid.isEmpty {
                return fbCfg
            }
            if let pid = dict["projectId"] as? String, !pid.isEmpty {
                return dict
            }
        }
        return nil
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        Self.shared = self
        UIApplication.shared.isIdleTimerDisabled = true
        Self.restoreUserDefaultsFromCachedWebSessionIfNeeded()
        UNUserNotificationCenter.current().delegate = self

        // CRÍTICO: Solo configurar FirebaseApp([DEFAULT]) cuando ya existe una licencia de cliente anexada.
        // Jamás llamar FirebaseApp.configure() con GoogleService-Info.plist por defecto antes de anexar licencia,
        // para que FirebaseApp.app() ([DEFAULT]) y Messaging.messaging() pertenezcan siempre al Sender ID del cliente.
        if let tenantDict = Self.resolveStoredTenantConfig() {
            Self.configureDynamicFirebase(configDict: tenantDict)
        }

        configureNotificationCategories()
        requestNotificationPermissions(application: application)
        Self.scheduleDailyReminders()
        IOSSoundPlayer.shared.prepareVolumeViewInWindow()
        IOSSoundPlayer.shared.updateBackgroundKeepAlive()
        IOSLocationService.shared.updateStandbyKeepAlive()
        return true
    }

    func applicationWillResignActive(_ application: UIApplication) {
        Self.isAppInForeground = false
        if Self.lastBackgroundTimestamp == 0 {
            Self.lastBackgroundTimestamp = Date().timeIntervalSince1970
        }
        // Al pasar a Recientes / segundo plano, mantener vivo el canal de audio, GPS standby y tarea de fondo si está en 0-9
        IOSSoundPlayer.shared.prepareVolumeViewInWindow()
        IOSSoundPlayer.shared.updateBackgroundKeepAlive()
        IOSLocationService.shared.updateStandbyKeepAlive()
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        Self.isAppInForeground = false
        if Self.lastBackgroundTimestamp == 0 {
            Self.lastBackgroundTimestamp = Date().timeIntervalSince1970
        }
        IOSSoundPlayer.shared.updateBackgroundKeepAlive()
        IOSLocationService.shared.updateStandbyKeepAlive()
        if bgTaskId != .invalid {
            application.endBackgroundTask(bgTaskId)
            bgTaskId = .invalid
        }
        bgTaskId = application.beginBackgroundTask(withName: "SentinelRealtimeKeepAlive") { [weak self] in
            guard let self = self else { return }
            IOSSoundPlayer.shared.updateBackgroundKeepAlive()
            IOSLocationService.shared.updateStandbyKeepAlive()
            if self.bgTaskId != .invalid {
                application.endBackgroundTask(self.bgTaskId)
                self.bgTaskId = .invalid
            }
        }
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        Self.isAppInForeground = true
        let bgSeconds = Self.lastBackgroundTimestamp > 0 ? max(0, Date().timeIntervalSince1970 - Self.lastBackgroundTimestamp) : 0
        Self.lastBackgroundTimestamp = 0
        if bgTaskId != .invalid {
            application.endBackgroundTask(bgTaskId)
            bgTaskId = .invalid
        }
        // Al abrir o volver a la app en primer plano: NUNCA debe sonar la alarma fuerte, se detiene el audio y solo vibra
        let wasPlaying = IOSSoundPlayer.shared.isPlayingAlarm
        IOSSoundPlayer.shared.stop()
        if wasPlaying {
            IOSSoundPlayer.shared.vibrateEmergency()
        }
        IOSSoundPlayer.shared.prepareVolumeViewInWindow()
        IOSLocationService.shared.syncPermissionsToWebView()
        IOSLocationService.shared.updateStandbyKeepAlive()
        if Self.currentTenantProjectId != nil {
            Self.activeFirestore().enableNetwork(completion: nil)
            let shouldRefresh = bgSeconds >= 15.0
            Self.authenticateNativeApp(Self.activeFirebaseApp(), forceRefresh: shouldRefresh) {
                if shouldRefresh {
                    IOSRealtimeService.shared.restartListenersPreservingState()
                } else {
                    IOSRealtimeService.shared.ensureListenersRunning()
                }
            }
        } else if let tenantDict = Self.resolveStoredTenantConfig() {
            Self.configureDynamicFirebase(configDict: tenantDict)
        }

        // Notificar al WKWebView para que reactive de inmediato los canales Long-Polling y refresque tokens
        DispatchQueue.main.async {
            guard let wv = Self.sharedWebView else { return }
            let js = """
            (function() {
                try {
                    window.dispatchEvent(new CustomEvent('sentinelAppResume', {
                        detail: { backgroundSeconds: \(Int(bgSeconds)), timestamp: Date.now() }
                    }));
                } catch (e) {}
            })();
            """
            wv.evaluateJavaScript(js, completionHandler: nil)
        }
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
        var opts: UNAuthorizationOptions = [.alert, .badge, .sound]
        if #available(iOS 15.0, *) {
            opts.insert(.timeSensitive)
        }
        center.requestAuthorization(options: opts) { granted, _ in
            if !granted {
                center.requestAuthorization(options: [.alert, .badge, .sound]) { _, _ in }
            }
            DispatchQueue.main.async {
                application.registerForRemoteNotifications()
            }
        }
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Self.cachedApnsDeviceToken = deviceToken
        let apnsHex = deviceToken.map { String(format: "%02x", $0) }.joined()
        if !apnsHex.isEmpty {
            UserDefaults.standard.set(apnsHex, forKey: "APNS_DEVICE_TOKEN_HEX")
            SentinelKeychain.set("apns_token_hex", value: apnsHex)
            DispatchQueue.main.async {
                if let wv = Self.sharedWebView {
                    let js = "try { window.__SENTINEL_APNS_TOKEN__ = '\(apnsHex)'; } catch(e) {}"
                    wv.evaluateJavaScript(js, completionHandler: nil)
                }
            }
        }
        guard FirebaseApp.app() != nil else { return }
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
        Self.emitFcmTokenToWebView(token)
    }

    static func emitFcmTokenToWebView(_ token: String) {
        guard !token.isEmpty else { return }
        let escaped = token.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
        DispatchQueue.main.async {
            guard let wv = sharedWebView else { return }
            let js = """
            (function() {
                try {
                    window.__SENTINEL_FCM_TOKEN__ = "\(escaped)";
                    window.dispatchEvent(new CustomEvent('sentinelNativeFcmToken', { detail: { token: "\(escaped)" } }));
                } catch (e) {}
            })();
            """
            wv.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    static func syncFcmTopicsAndToken(userId: String, tokenOverride: String? = nil) {
        guard !userId.isEmpty, FirebaseApp.app() != nil else { return }
        if let apnsTok = cachedApnsDeviceToken {
            Messaging.messaging().apnsToken = apnsTok
        }
        let defaults = UserDefaults.standard
        let isConductor = defaults.bool(forKey: "USER_CONDUCTOR")
        let isCentral = defaults.bool(forKey: "IS_CENTRAL_ACTIVE")
        let activePid = currentTenantProjectId ?? FirebaseApp.app()?.options.projectID ?? ""

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

        let subscribeAllTopics: () -> Void = {
            for t in topics {
                Messaging.messaging().subscribe(toTopic: t)
            }
            if !isConductor { Messaging.messaging().unsubscribe(fromTopic: "conductores") }
            if !isCentral { Messaging.messaging().unsubscribe(fromTopic: "central_operador") }
        }

        let hwId = SentinelKeychain.getOrCreateDeviceId()
        let apnsHex = cachedApnsDeviceToken?.map { String(format: "%02x", $0) }.joined()
            ?? defaults.string(forKey: "APNS_DEVICE_TOKEN_HEX")
            ?? ""
        if !apnsHex.isEmpty {
            defaults.set(apnsHex, forKey: "APNS_DEVICE_TOKEN_HEX")
            DispatchQueue.main.async {
                if let wv = sharedWebView {
                    let js = "try { window.__SENTINEL_APNS_TOKEN__ = '\(apnsHex)'; } catch(e) {}"
                    wv.evaluateJavaScript(js, completionHandler: nil)
                }
            }
            authenticateNativeApp(activeFirebaseApp()) {
                let db = activeFirestore()
                let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                db.collection("personal").document(userId).setData([
                    "apnsToken": apnsHex,
                    "platform": "ios",
                    "_metaTimestamp": nowMs
                ], merge: true)
            }
        }

        let saveTokenToFirestore: (String) -> Void = { token in
            defaults.set(token, forKey: "FCM_TOKEN")
            if !activePid.isEmpty {
                defaults.set(activePid, forKey: "FCM_TOKEN_PROJECT_ID")
                SentinelKeychain.set("last_project_id", value: activePid)
            }
            SentinelKeychain.set("last_user_id", value: userId)
            emitFcmTokenToWebView(token)
            subscribeAllTopics()
            authenticateNativeApp(activeFirebaseApp()) {
                let db = activeFirestore()
                let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                var patch: [String: Any] = [
                    "deviceId": token,
                    "platform": "ios",
                    "_metaTimestamp": nowMs
                ]
                if !apnsHex.isEmpty {
                    patch["apnsToken"] = apnsHex
                }
                db.collection("personal").document(userId).setData(patch, merge: true)
            }
        }

        let storedTokenPid = defaults.string(forKey: "FCM_TOKEN_PROJECT_ID") ?? ""
        if !activePid.isEmpty && storedTokenPid != activePid && tokenOverride == nil {
            // Si el token previo en Keychain fue emitido para otro proyecto (ej. pruebas-sisbom), regenerarlo para el proyecto activo
            Messaging.messaging().deleteToken { _ in
                if let apnsTok = cachedApnsDeviceToken {
                    Messaging.messaging().apnsToken = apnsTok
                }
                Messaging.messaging().token { tok, _ in
                    if let tok = tok, !tok.isEmpty {
                        saveTokenToFirestore(tok)
                    }
                }
            }
            return
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
        if FirebaseApp.app() != nil {
            Messaging.messaging().appDidReceiveMessage(userInfo)
        }
        let defaults = UserDefaults.standard
        if defaults.bool(forKey: "MODO_AVION") {
            completionHandler(.noData)
            return
        }

        let type = (userInfo["type"] as? String) ?? ""
        let payloadId = (userInfo["payloadId"] as? String) ?? (userInfo["dispatchId"] as? String) ?? ""
        let clave = (userInfo["clave"] as? String) ?? ""
        let title = (userInfo["title"] as? String) ?? ""
        let body = (userInfo["body"] as? String) ?? ""
        let is1210or66 = title.contains("12-10") || title.contains("6-6") || body.contains("12-10") || body.contains("6-6")

        if type == "STATUS_CHANGE" {
            let newStatus = ((userInfo["newStatus"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            if !newStatus.isEmpty {
                defaults.set(newStatus, forKey: "USER_ESTADO")
                IOSSoundPlayer.shared.updateBackgroundKeepAlive()
                IOSLocationService.shared.updateStandbyKeepAlive()

                let lastSelfStatus = (defaults.string(forKey: "LAST_SELF_STATUS_CHANGE") ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                let lastSelfTime = defaults.double(forKey: "LAST_SELF_STATUS_TIME")
                let nowMs = Date().timeIntervalSince1970 * 1000.0
                let isSelfChange = (newStatus == lastSelfStatus) && ((nowMs - lastSelfTime) < 20_000.0)
                if !isSelfChange {
                    defaults.set(newStatus, forKey: "LAST_NOTIFIED_STATUS_CHANGE")
                    defaults.set(nowMs, forKey: "LAST_NOTIFIED_STATUS_TIME")
                    AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                }
            }
            completionHandler(.newData)
            return
        }

        // Si el usuario es el Operador Central activo, NO hacer sonar el teléfono ni levantar alerta de asistencia en despachos
        if defaults.bool(forKey: "IS_CENTRAL_ACTIVE") && (type == "DISPATCH" || type == "DISPATCH_UPDATE" || type.isEmpty) {
            if !payloadId.isEmpty {
                IOSSoundPlayer.shared.cancelRepeat(for: payloadId)
            }
            completionHandler(.noData)
            return
        }
        let userEstado = (defaults.string(forKey: "USER_ESTADO") ?? "0-9").uppercased()
        let is09 = (userEstado == "0-9")
        let isEscalation = type == "DISPATCH_UPDATE" && (clave.contains("10-30") || clave.contains("FORESTAL") || clave.contains("9-0"))

        let attended = defaults.stringArray(forKey: "ATTENDED_DISPATCH_IDS") ?? []
        let declined = defaults.stringArray(forKey: "DECLINED_DISPATCH_IDS") ?? []
        let enServicio = defaults.string(forKey: "USER_EN_SERVICIO") ?? ""
        let isAttending = !payloadId.isEmpty && (attended.contains(payloadId) || enServicio == payloadId)
        let hasDeclined = !payloadId.isEmpty && (declined.contains(payloadId) || enServicio == "-\(payloadId)")

        if isAttending {
            completionHandler(.noData)
            return
        }
        if type == "DISPATCH_UPDATE" && !isEscalation && !is1210or66 {
            completionHandler(.noData)
            return
        }
        if hasDeclined && !isEscalation {
            completionHandler(.noData)
            return
        }

        let isRepeat60s = ((userInfo["isRepeat60s"] as? String) == "true")
        var createdMs: Int64 = 0
        if let s = userInfo["createdAt"] as? String, let v = Int64(s) {
            createdMs = v
        } else if let n = userInfo["createdAt"] as? NSNumber {
            createdMs = n.int64Value
        } else if let s = userInfo["timestamp"] as? String, let v = Int64(s) {
            createdMs = v
        } else if let n = userInfo["timestamp"] as? NSNumber {
            createdMs = n.int64Value
        }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let isLateDispatch: Bool = !isRepeat60s && !isEscalation && !is1210or66 && (createdMs > 1_000_000_000_000) && ((nowMs - createdMs) > 300_000)

        if is1210or66 {
            if Self.isAppInForeground {
                IOSSoundPlayer.shared.vibrateEmergency()
            } else if is09 {
                IOSSoundPlayer.shared.play(nameOrClave: clave.isEmpty ? "despacho" : clave, forceMaxVolume: true)
            }
            if is09 && !payloadId.isEmpty {
                Self.dispatchNativeIntentToWebView(dispatchId: payloadId, showFullscreen: true)
            }
        } else if (type == "DISPATCH" || isEscalation) && (is09 || isEscalation) {
            if isLateDispatch {
                // Si el despacho llega tardío (> 5 min) por mala señal: NO sonar sirena, solo vibrar
                IOSSoundPlayer.shared.vibrateEmergency()
            } else if Self.isAppInForeground && !isRepeat60s {
                IOSSoundPlayer.shared.vibrateEmergency()
                if !payloadId.isEmpty {
                    IOSSoundPlayer.shared.scheduleRepeat(dispatchId: payloadId, clave: clave)
                    Self.dispatchNativeIntentToWebView(dispatchId: payloadId, showFullscreen: true)
                }
            } else {
                IOSSoundPlayer.shared.play(nameOrClave: clave.isEmpty ? "despacho" : clave, forceMaxVolume: true)
                if !payloadId.isEmpty {
                    if !isRepeat60s {
                        IOSSoundPlayer.shared.scheduleRepeat(dispatchId: payloadId, clave: clave)
                    }
                    Self.dispatchNativeIntentToWebView(dispatchId: payloadId, showFullscreen: true)
                }
            }
        }
        completionHandler(.newData)
    }

    // Recepción de Push con la app en primer plano o segundo plano
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let userInfo = notification.request.content.userInfo
        let type = (userInfo["type"] as? String) ?? ""
        let payloadId = (userInfo["payloadId"] as? String) ?? (userInfo["dispatchId"] as? String) ?? ""
        let clave = (userInfo["clave"] as? String) ?? ""
        let isRepeat60s = ((userInfo["isRepeat60s"] as? String) == "true")
        let defaults = UserDefaults.standard

        if defaults.bool(forKey: "MODO_AVION") {
            completionHandler([])
            return
        }

        if type == "STATUS_CHANGE" {
            let newStatus = ((userInfo["newStatus"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            if !newStatus.isEmpty {
                defaults.set(newStatus, forKey: "USER_ESTADO")
                IOSSoundPlayer.shared.updateBackgroundKeepAlive()
                IOSLocationService.shared.updateStandbyKeepAlive()
            }
            let lastSelfStatus = (defaults.string(forKey: "LAST_SELF_STATUS_CHANGE") ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            let lastSelfTime = defaults.double(forKey: "LAST_SELF_STATUS_TIME")
            let nowMs = Date().timeIntervalSince1970 * 1000.0
            let isSelfChange = !newStatus.isEmpty && (newStatus == lastSelfStatus) && ((nowMs - lastSelfTime) < 20_000.0)
            if isSelfChange {
                completionHandler([])
            } else {
                defaults.set(newStatus, forKey: "LAST_NOTIFIED_STATUS_CHANGE")
                defaults.set(nowMs, forKey: "LAST_NOTIFIED_STATUS_TIME")
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                completionHandler([.banner, .list, .sound])
            }
            return
        }

        if defaults.bool(forKey: "IS_CENTRAL_ACTIVE") && (type == "DISPATCH" || type == "DISPATCH_UPDATE") {
            if !payloadId.isEmpty {
                IOSSoundPlayer.shared.cancelRepeat(for: payloadId)
            }
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
        let isEscalation = type == "DISPATCH_UPDATE" && (clave.contains("10-30") || clave.contains("FORESTAL") || clave.contains("9-0"))

        if type == "DISPATCH" || type == "DISPATCH_UPDATE" {
            if isAttending || (type == "DISPATCH_UPDATE" && !isEscalation) || (hasDeclined && !isEscalation) {
                completionHandler([])
                return
            }
            var createdMs: Int64 = 0
            if let s = userInfo["createdAt"] as? String, let v = Int64(s) {
                createdMs = v
            } else if let n = userInfo["createdAt"] as? NSNumber {
                createdMs = n.int64Value
            } else if let s = userInfo["timestamp"] as? String, let v = Int64(s) {
                createdMs = v
            } else if let n = userInfo["timestamp"] as? NSNumber {
                createdMs = n.int64Value
            }
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let isLateDispatch: Bool = !isRepeat60s && !isEscalation && (createdMs > 1_000_000_000_000) && ((nowMs - createdMs) > 300_000)

            if isLateDispatch {
                // Si el despacho llega tardío (> 5 min) por mala señal: NO sonar sirena, solo vibrar y mostrar notificación sin sonido
                IOSSoundPlayer.shared.vibrateEmergency()
                completionHandler([.banner, .list])
                return
            }
            if !payloadId.isEmpty && (is09 || isEscalation) {
                Self.dispatchNativeIntentToWebView(dispatchId: payloadId, showFullscreen: true)
            }
            if Self.isAppInForeground && !isRepeat60s {
                IOSSoundPlayer.shared.vibrateEmergency()
                if !payloadId.isEmpty && is09 {
                    IOSSoundPlayer.shared.scheduleRepeat(dispatchId: payloadId, clave: clave)
                }
                completionHandler([])
                return
            }
            if is09 || isEscalation {
                IOSSoundPlayer.shared.play(nameOrClave: clave.isEmpty ? "despacho" : clave, forceMaxVolume: true)
                if !payloadId.isEmpty && !isRepeat60s {
                    IOSSoundPlayer.shared.scheduleRepeat(dispatchId: payloadId, clave: clave)
                }
                completionHandler([.banner, .list])
                return
            }
            completionHandler([.banner, .list, .sound])
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
final class SentinelWebBridge: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
    static func handleExternalUrl(_ url: URL) -> Bool {
        let scheme = (url.scheme ?? "").lowercased()
        let host = (url.host ?? "").lowercased()

        if scheme == "sentinel" || scheme == "file" || scheme == "about" || scheme == "data" || scheme == "blob" {
            return false
        }
        if (scheme == "https" || scheme == "http") && host == "appassets.androidplatform.net" {
            return false
        }

        DispatchQueue.main.async {
            if scheme == "geo" {
                let raw = url.absoluteString.replacingOccurrences(of: "geo:", with: "")
                let coords = raw.components(separatedBy: "?").first ?? ""
                if let appleMapsUrl = URL(string: "http://maps.apple.com/?ll=\(coords)&q=\(coords)") {
                    UIApplication.shared.open(appleMapsUrl, options: [:], completionHandler: nil)
                    return
                }
            }

            if scheme == "whatsapp" || host.contains("wa.me") || host.contains("whatsapp.com") {
                var waSchemeUrl: URL? = (scheme == "whatsapp") ? url : nil
                var waUniversalUrl: URL? = (scheme == "https" || scheme == "http") ? url : nil

                if waSchemeUrl == nil, let components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
                    let queryItems = components.queryItems ?? []
                    let phoneParam = queryItems.first(where: { $0.name == "phone" })?.value
                        ?? url.path.replacingOccurrences(of: "/", with: "")
                    let textParam = queryItems.first(where: { $0.name == "text" })?.value ?? ""
                    let cleanDigits = phoneParam.components(separatedBy: CharacterSet.decimalDigits.inverted).joined()
                    if !cleanDigits.isEmpty {
                        var comps = URLComponents()
                        comps.scheme = "whatsapp"
                        comps.host = "send"
                        var items = [URLQueryItem(name: "phone", value: cleanDigits)]
                        if !textParam.isEmpty {
                            items.append(URLQueryItem(name: "text", value: textParam))
                        }
                        comps.queryItems = items
                        waSchemeUrl = comps.url
                    }
                }

                if waUniversalUrl == nil, let components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
                    let queryItems = components.queryItems ?? []
                    let phoneParam = queryItems.first(where: { $0.name == "phone" })?.value ?? ""
                    let textParam = queryItems.first(where: { $0.name == "text" })?.value ?? ""
                    let cleanDigits = phoneParam.components(separatedBy: CharacterSet.decimalDigits.inverted).joined()
                    if !cleanDigits.isEmpty {
                        var comps = URLComponents(string: "https://wa.me/\(cleanDigits)")
                        if !textParam.isEmpty {
                            comps?.queryItems = [URLQueryItem(name: "text", value: textParam)]
                        }
                        waUniversalUrl = comps?.url
                    }
                }

                if let primaryUrl = waSchemeUrl {
                    UIApplication.shared.open(primaryUrl, options: [:]) { success in
                        if !success, let fallbackUrl = waUniversalUrl {
                            UIApplication.shared.open(fallbackUrl, options: [:], completionHandler: nil)
                        }
                    }
                    return
                }
            }

            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
        return true
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        let scheme = (url.scheme ?? "").lowercased()
        let host = (url.host ?? "").lowercased()

        // Permitir siempre el esquema local de la app y recursos embebidos
        if scheme == "sentinel" || scheme == "file" || scheme == "about" || scheme == "data" || scheme == "blob" {
            decisionHandler(.allow)
            return
        }

        // Permitir iframes secundarios (como el mapa táctico Leaflet y tiles de Google Maps dentro de srcDoc)
        if let targetFrame = navigationAction.targetFrame, !targetFrame.isMainFrame {
            if scheme == "https" || scheme == "http" {
                decisionHandler(.allow)
                return
            }
        }

        if scheme == "geo" || scheme == "tel" || scheme == "mailto" || scheme == "sms" || scheme == "whatsapp" || scheme == "maps" ||
           host.contains("wa.me") || host.contains("whatsapp.com") || host.contains("maps.apple.com") ||
           (host.contains("google.com") && url.path.contains("/maps")) {
            _ = Self.handleExternalUrl(url)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    // Si iOS termina el proceso WebContent en segundo plano tras muchas horas de inactividad,
    // recargar automáticamente el WebView local para que nunca quede congelado ni obligue a cerrar la app de Recientes.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        DispatchQueue.main.async {
            if let startUrl = URL(string: "sentinel://appassets.androidplatform.net/index.html") {
                webView.load(URLRequest(url: startUrl))
            } else {
                webView.reload()
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                IOSLocationService.shared.syncPermissionsToWebView()
                IOSRealtimeService.shared.ensureListenersRunning()
            }
        }
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let dict = message.body as? [String: Any],
              let action = dict["action"] as? String else { return }
        let defaults = UserDefaults.standard

        switch action {
        case "openExternalUrl":
            if let rawUrl = dict["url"] as? String,
               let url = URL(string: rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)) {
                _ = Self.handleExternalUrl(url)
            }
        case "syncFirebaseConfig":
            if let cfg = dict["config"] as? [String: Any] {
                AppDelegate.configureDynamicFirebase(configDict: cfg)
            }
        case "requestLocationAlways":
            IOSLocationService.shared.requestAlwaysPermissions()
        case "requestNotificationsPermission":
            IOSLocationService.shared.requestNotificationsPermission()
        case "openAppSettings":
            DispatchQueue.main.async {
                if let settingsUrl = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(settingsUrl, options: [:], completionHandler: nil)
                }
            }
        case "syncUserSession":
            if let user = dict["user"] as? [String: Any] {
                let userId = AppDelegate.stringFromAny(user["idRegistro"] ?? user["id"])
                let idRadial = AppDelegate.stringFromAny(user["idRadial"])
                let licenseKey = AppDelegate.stringFromAny(user["licenseKey"]).uppercased()
                let nombre = AppDelegate.stringFromAny(user["nombre"], fallback: "Bombero")
                let rango = AppDelegate.stringFromAny(user["rango"], fallback: "Voluntario")
                let compania = AppDelegate.stringFromAny(user["compania"], fallback: "1")
                let estado = AppDelegate.stringFromAny(user["estado"], fallback: "0-9").uppercased()
                let conductor = (user["conductor"] as? Bool) ?? (user["conductor"] as? NSNumber)?.boolValue ?? false
                let estadoEspecial = AppDelegate.stringFromAny(user["estadoEspecial"])
                let enServicio = AppDelegate.stringFromAny(user["enServicio"])

                defaults.set(userId, forKey: "USER_ID")
                if !idRadial.isEmpty { defaults.set(idRadial, forKey: "USER_ID_RADIAL") }
                if !licenseKey.isEmpty { defaults.set(licenseKey, forKey: "LICENSE_KEY") }
                defaults.set(nombre, forKey: "USER_NOMBRE")
                defaults.set(rango, forKey: "USER_RANGO")
                defaults.set(compania, forKey: "USER_COMPANIA")
                defaults.set(estado, forKey: "USER_ESTADO")
                defaults.set(conductor, forKey: "USER_CONDUCTOR")
                defaults.set(estadoEspecial, forKey: "USER_ESTADO_ESPECIAL")
                defaults.set(enServicio, forKey: "USER_EN_SERVICIO")

                if !userId.isEmpty {
                    SentinelKeychain.set("last_user_id", value: userId)
                    if let pid = AppDelegate.currentTenantProjectId, !pid.isEmpty {
                        SentinelKeychain.set("last_project_id", value: pid)
                    }
                }

                AppDelegate.syncFcmTopicsAndToken(userId: userId)
                IOSRealtimeService.shared.startCentralSciListenerIfNeeded()
                AppDelegate.scheduleDailyReminders()
                IOSSoundPlayer.shared.updateBackgroundKeepAlive()
                IOSLocationService.shared.updateStandbyKeepAlive()
                if estado == "0-9" && !enServicio.isEmpty && enServicio != "0" && !enServicio.hasPrefix("-") {
                    IOSLocationService.shared.startTracking(
                        serviceId: enServicio,
                        userId: userId,
                        nombre: nombre,
                        rango: rango,
                        compania: compania
                    )
                } else {
                    IOSLocationService.shared.stopTracking()
                }
            }
        case "clearUserSession":
            let oldId = defaults.string(forKey: "USER_ID") ?? SentinelKeychain.get("last_user_id") ?? ""
            if !oldId.isEmpty {
                Messaging.messaging().unsubscribe(fromTopic: "usuario_\(oldId)")
                Messaging.messaging().unsubscribe(fromTopic: "personal_\(oldId)")
                Messaging.messaging().unsubscribe(fromTopic: "conductores")
                Messaging.messaging().unsubscribe(fromTopic: "central_operador")
                let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                AppDelegate.activeFirestore().collection("personal").document(oldId).setData([
                    "deviceId": "",
                    "apnsToken": "",
                    "_metaTimestamp": nowMs
                ], merge: true)
            }
            SentinelKeychain.remove("last_user_id")
            defaults.removeObject(forKey: "USER_ID")
            IOSSoundPlayer.shared.stop()
            IOSSoundPlayer.shared.stopBackgroundKeepAlive()
            IOSLocationService.shared.stopTracking()
        case "setCentralOperatorMode":
            let active = (dict["active"] as? Bool) ?? false
            defaults.set(active, forKey: "IS_CENTRAL_ACTIVE")
            if active {
                IOSSoundPlayer.shared.stop()
                IOSSoundPlayer.shared.stopBackgroundKeepAlive()
                IOSLocationService.shared.updateStandbyKeepAlive()
                Messaging.messaging().subscribe(toTopic: "central_operador")
            } else {
                Messaging.messaging().unsubscribe(fromTopic: "central_operador")
                IOSSoundPlayer.shared.updateBackgroundKeepAlive()
                IOSLocationService.shared.updateStandbyKeepAlive()
            }
        case "setAirplaneMode":
            let active = (dict["active"] as? Bool) ?? false
            defaults.set(active, forKey: "MODO_AVION")
            if active {
                defaults.set("0-8", forKey: "USER_ESTADO")
                defaults.set("0", forKey: "USER_EN_SERVICIO")
                defaults.set("0-8", forKey: "LAST_SELF_STATUS_CHANGE")
                defaults.set(Date().timeIntervalSince1970 * 1000.0, forKey: "LAST_SELF_STATUS_TIME")
                IOSSoundPlayer.shared.stop()
                IOSSoundPlayer.shared.stopBackgroundKeepAlive()
                IOSLocationService.shared.stopTracking()
            } else {
                IOSSoundPlayer.shared.updateBackgroundKeepAlive()
                IOSLocationService.shared.updateStandbyKeepAlive()
            }
            AppDelegate.scheduleDailyReminders()
        case "markSelfStatusChange":
            let st = AppDelegate.stringFromAny(dict["status"]).uppercased()
            if !st.isEmpty {
                defaults.set(st, forKey: "USER_ESTADO")
                defaults.set(st, forKey: "LAST_SELF_STATUS_CHANGE")
                defaults.set(Date().timeIntervalSince1970 * 1000.0, forKey: "LAST_SELF_STATUS_TIME")
                if st != "0-9" {
                    defaults.set("0", forKey: "USER_EN_SERVICIO")
                    IOSLocationService.shared.stopTracking()
                }
                IOSSoundPlayer.shared.updateBackgroundKeepAlive()
                IOSLocationService.shared.updateStandbyKeepAlive()
            }
        case "acknowledgeDispatch":
            let dispatchId = AppDelegate.stringFromAny(dict["dispatchId"]).trimmingCharacters(in: .whitespacesAndNewlines)
            let declined = (dict["declined"] as? Bool) ?? false
            IOSSoundPlayer.shared.stop()
            if !dispatchId.isEmpty {
                var declinedSet = defaults.stringArray(forKey: "DECLINED_DISPATCH_IDS") ?? []
                var attendedSet = defaults.stringArray(forKey: "ATTENDED_DISPATCH_IDS") ?? []
                if dispatchId == "0" || dispatchId.uppercased() == "LIBRE" {
                    let prevSvc = (defaults.string(forKey: "USER_EN_SERVICIO") ?? "").replacingOccurrences(of: "-", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
                    if !prevSvc.isEmpty {
                        IOSSoundPlayer.shared.cancelRepeat(for: prevSvc)
                        attendedSet.removeAll { $0 == prevSvc }
                        declinedSet.removeAll { $0 == prevSvc }
                    }
                    defaults.set("0", forKey: "USER_EN_SERVICIO")
                    IOSLocationService.shared.stopTracking()
                } else if declined {
                    IOSSoundPlayer.shared.cancelRepeat(for: dispatchId)
                    if !declinedSet.contains(dispatchId) { declinedSet.append(dispatchId) }
                    attendedSet.removeAll { $0 == dispatchId }
                    defaults.set("-\(dispatchId)", forKey: "USER_EN_SERVICIO")
                    IOSLocationService.shared.stopTracking()
                } else {
                    IOSSoundPlayer.shared.cancelRepeat(for: dispatchId)
                    if !attendedSet.contains(dispatchId) { attendedSet.append(dispatchId) }
                    declinedSet.removeAll { $0 == dispatchId }
                    defaults.set(dispatchId, forKey: "USER_EN_SERVICIO")
                }
                defaults.set(declinedSet, forKey: "DECLINED_DISPATCH_IDS")
                defaults.set(attendedSet, forKey: "ATTENDED_DISPATCH_IDS")
            }
        case "showLocalNotification":
            let title = AppDelegate.stringFromAny(dict["title"], fallback: "🚨 ALERTA SENTINEL")
            let body = AppDelegate.stringFromAny(dict["body"])
            let soundName = AppDelegate.stringFromAny(dict["soundName"], fallback: "alertas")
            let fileName = IOSSoundPlayer.shared.resolveSoundFileName(for: soundName)
            if AppDelegate.isAppInForeground {
                IOSSoundPlayer.shared.vibrateEmergency()
            } else {
                IOSSoundPlayer.shared.play(nameOrClave: fileName, forceMaxVolume: true)
                IOSRealtimeService.shared.postLocalBanner(
                    id: "local_\(Int(Date().timeIntervalSince1970 * 1000))",
                    title: title,
                    body: body,
                    soundName: "\(fileName).mp3",
                    playDefaultIfNil: false,
                    userInfo: ["type": "SCI_ALERT"]
                )
            }
        case "playSound":
            let soundName = (dict["soundName"] as? String) ?? "despacho"
            let forceMax = (dict["forceMaxVolume"] as? Bool) ?? true
            let upperS = soundName.uppercased()
            let isAlertMsg = upperS.contains("ALERTA") || upperS.contains("IMPORTANTE")
            if AppDelegate.isAppInForeground && !isAlertMsg {
                IOSSoundPlayer.shared.vibrateEmergency()
            } else {
                IOSSoundPlayer.shared.play(nameOrClave: soundName, forceMaxVolume: forceMax)
            }
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
        case "cacheInstitutionLogo":
            if let rawUrl = dict["url"] as? String,
               let url = URL(string: rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)) {
                URLSession.shared.dataTask(with: url) { data, response, error in
                    guard let data = data, !data.isEmpty, error == nil else { return }
                    let mime = response?.mimeType ?? "image/png"
                    let b64 = data.base64EncodedString()
                    let dataUri = "data:\(mime);base64,\(b64)"
                    UserDefaults.standard.set(dataUri, forKey: "WEBSTORAGE_@sentinel_one_v3_logo_data_uri")
                    DispatchQueue.main.async {
                        guard let wv = AppDelegate.sharedWebView else { return }
                        let escaped = dataUri.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
                        let js = """
                        (function() {
                            try {
                                var uri = "\(escaped)";
                                if (window.localStorage) {
                                    window.localStorage.setItem('@sentinel_one_v3_logo_data_uri', uri);
                                }
                                window.dispatchEvent(new CustomEvent('sentinelNativeLogoCached', { detail: { dataUri: uri } }));
                            } catch (e) {}
                        })();
                        """
                        wv.evaluateJavaScript(js, completionHandler: nil)
                    }
                }.resume()
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

        // Si aún no existe el archivo JS con hash exacto (por ejemplo tras actualización de versión), buscar cualquier index-*.js en expo_bundle/static/js/web/
        if !FileManager.default.fileExists(atPath: targetFile.path) && relPath.hasSuffix(".js") && (relPath.contains("expo_bundle/") || relPath.contains("_expo/")) {
            let candidateDirs = [
                wwwUrl.appendingPathComponent("expo_bundle/static/js/web", isDirectory: true),
                wwwUrl.appendingPathComponent("_expo/static/js/web", isDirectory: true)
            ]
            for dir in candidateDirs {
                if let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil),
                   let firstJs = files.first(where: { $0.lastPathComponent.hasPrefix("index-") && $0.pathExtension.lowercased() == "js" }) {
                    targetFile = firstJs
                    break
                }
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
            "@sentinel_one_v3_logo_data_uri",
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
        let syncPerms = IOSLocationService.shared.currentSynchronousPerms()
        if syncPerms.fine && syncPerms.bg && syncPerms.notif {
            storageMap["@sentinel_one_location_disclosed_v3"] = "true"
            defaults.set("true", forKey: "WEBSTORAGE_@sentinel_one_location_disclosed_v3")
        }
        let storageJson: String
        if let jsonData = try? JSONSerialization.data(withJSONObject: storageMap),
           let jsonStr = String(data: jsonData, encoding: .utf8) {
            storageJson = jsonStr
        } else {
            storageJson = "{}"
        }

        let hwId = SentinelKeychain.getOrCreateDeviceId()
        let apnsHex = AppDelegate.cachedApnsDeviceToken?.map { String(format: "%02x", $0) }.joined()
            ?? defaults.string(forKey: "APNS_DEVICE_TOKEN_HEX")
            ?? SentinelKeychain.get("apns_token_hex")
            ?? ""
        let lastKeychainPid = SentinelKeychain.get("last_project_id") ?? ""
        let lastKeychainUid = SentinelKeychain.get("last_user_id") ?? ""
        let lastSessionStr = (!lastKeychainPid.isEmpty && !lastKeychainUid.isEmpty) ? "\(lastKeychainPid):\(lastKeychainUid)" : ""

        let bootJs = """
        (function() {
            try {
                window.__SENTINEL_PLATFORM__ = 'ios';
                window.__SENTINEL_HW_DEVICE_ID__ = "\(hwId)";
                window.__SENTINEL_APNS_TOKEN__ = "\(apnsHex)";
                window.__SENTINEL_KEYCHAIN_LAST_SESSION__ = "\(lastSessionStr)";
                window.__SENTINEL_IOS_PERMS__ = {
                    fineLocation: \(syncPerms.fine ? "true" : "false"),
                    backgroundLocation: \(syncPerms.bg ? "true" : "false"),
                    overlay: true,
                    batteryUnrestricted: true,
                    notifications: \(syncPerms.notif ? "true" : "false")
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
        wv.navigationDelegate = bridge
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
        IOSLocationService.shared.syncPermissionsToWebView()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
            IOSLocationService.shared.syncPermissionsToWebView()
            IOSSoundPlayer.shared.prepareVolumeViewInWindow()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            IOSLocationService.shared.syncPermissionsToWebView()
            IOSSoundPlayer.shared.prepareVolumeViewInWindow()
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
