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
            manager.requestAlwaysAuthorization()
        }
        syncPermissionsToWebView()
        updateStandbyKeepAlive()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if #available(iOS 14.0, *) {
            if manager.authorizationStatus == .authorizedWhenInUse {
                manager.requestAlwaysAuthorization()
            }
        }
        syncPermissionsToWebView()
        updateStandbyKeepAlive()
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

    func updateStandbyKeepAlive() {
        if isTracking { return }
        let defaults = UserDefaults.standard
        let isAirplane = defaults.bool(forKey: "MODO_AVION")
        let isCentral = defaults.bool(forKey: "IS_CENTRAL_ACTIVE")
        let estado = (defaults.string(forKey: "USER_ESTADO") ?? "0-9").uppercased().trimmingCharacters(in: .whitespacesAndNewlines)

        let status: CLAuthorizationStatus
        if #available(iOS 14.0, *) {
            status = manager.authorizationStatus
        } else {
            status = CLLocationManager.authorizationStatus()
        }
        let canBg = (status == .authorizedAlways || status == .authorizedWhenInUse)

        guard canBg && !isAirplane && !isCentral && estado == "0-9" else {
            if isStandbyKeepAlive {
                isStandbyKeepAlive = false
                manager.stopUpdatingLocation()
                manager.stopMonitoringSignificantLocationChanges()
            }
            return
        }

        if !isStandbyKeepAlive {
            isStandbyKeepAlive = true
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = false
            manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
            manager.distanceFilter = 500.0
            manager.startUpdatingLocation()
            manager.startMonitoringSignificantLocationChanges()
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
        let oldUserId = activeUserId
        if isTracking {
            isTracking = false
            activeServiceId = nil
            activeUserId = nil
            manager.stopUpdatingLocation()
            manager.showsBackgroundLocationIndicator = false
            if let uid = oldUserId, !uid.isEmpty {
                removeFromRtdb(userId: uid)
            }
        }
        updateStandbyKeepAlive()
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

    private func removeFromRtdb(userId: String) {
        guard let projectId = UserDefaults.standard.string(forKey: "saas_project_id"), !projectId.isEmpty else { return }
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
    private var keepAlivePlayer: AVAudioPlayer?
    private var repeatTimers: [String: Timer] = [:]

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

    func updateBackgroundKeepAlive() {
        let defaults = UserDefaults.standard
        let isAirplane = defaults.bool(forKey: "MODO_AVION")
        let isCentral = defaults.bool(forKey: "IS_CENTRAL_ACTIVE")
        let estado = (defaults.string(forKey: "USER_ESTADO") ?? "0-9").uppercased().trimmingCharacters(in: .whitespacesAndNewlines)

        guard !isAirplane && !isCentral && estado == "0-9" else {
            stopBackgroundKeepAlive()
            return
        }

        // Si ya está sonando la alarma real o ya está corriendo el keep-alive, mantener activo
        if let active = audioPlayer, active.isPlaying {
            return
        }
        if let ka = keepAlivePlayer, ka.isPlaying {
            return
        }

        guard let url = Bundle.main.url(forResource: "despacho", withExtension: "mp3")
                ?? Bundle.main.url(forResource: "alertas", withExtension: "mp3") else {
            return
        }

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
            let player = try AVAudioPlayer(contentsOf: url)
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
        stop()
        stopBackgroundKeepAlive()
        let defaults = UserDefaults.standard
        let upper = nameOrClave.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if defaults.bool(forKey: "MODO_AVION") {
            return
        }
        if defaults.bool(forKey: "IS_CENTRAL_ACTIVE") && upper != "ALERTA" && upper != "ALERTAS" && upper != "IMPORTANTE" {
            return
        }
        let fileName = resolveSoundFileName(for: nameOrClave)
        guard let url = Bundle.main.url(forResource: fileName, withExtension: "mp3")
                ?? Bundle.main.url(forResource: fileName.lowercased(), withExtension: "mp3")
                ?? Bundle.main.url(forResource: "despacho", withExtension: "mp3")
                ?? Bundle.main.url(forResource: "alertas", withExtension: "mp3") else {
            vibrateEmergency()
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
            vibrateEmergency()
        }
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        if player === audioPlayer {
            audioPlayer = nil
            updateBackgroundKeepAlive()
        }
    }

    func stop() {
        audioPlayer?.stop()
        audioPlayer = nil
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
// 2.5. SERVICIO NATIVO DE TIEMPO REAL EN iOS (DESPACHOS, CENTRAL Y ALERTAS)
// ============================================================================
final class IOSRealtimeService {
    static let shared = IOSRealtimeService()
    private var despachosListener: ListenerRegistration?
    private var centralListener: ListenerRegistration?
    private var vehiculosListener: ListenerRegistration?
    private var alertasListener: ListenerRegistration?
    private var activeProjectId: String?
    private var isFirstDispatchSnapshot = true
    private var knownDispatchIds = Set<String>()
    private var lastDispatchClaves: [String: String] = [:]
    private var lastUnitConductorReq: [String: Int64] = [:]
    private var lastUnitPersonalReq: [String: Int64] = [:]
    private var latestCentralPayload: [String: Any]?
    private var latestDespachosPayload: [[String: Any]]?
    private var latestVehiculosPayload: [[String: Any]]?
    private var latestAlertasPayload: [[String: Any]]?

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
        activeProjectId = nil
    }

    func ensureListenersRunning() {
        let currentPid = AppDelegate.currentTenantProjectId ?? FirebaseApp.app()?.options.projectID ?? ""
        if despachosListener != nil && !currentPid.isEmpty && activeProjectId == currentPid {
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
            return
        }
        startListeners()
    }

    func startListeners() {
        stopAll()
        guard AppDelegate.currentTenantProjectId != nil || FirebaseApp.app() != nil else { return }
        activeProjectId = AppDelegate.currentTenantProjectId ?? FirebaseApp.app()?.options.projectID
        isFirstDispatchSnapshot = true
        let db = AppDelegate.activeFirestore()

        // 1. Listener nativo de accesos/central
        centralListener = db.collection("accesos").document("central").addSnapshotListener { [weak self] snap, error in
            guard let self = self, error == nil else { return }
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
        despachosListener = db.collection("despachos").addSnapshotListener { [weak self] snap, error in
            guard let self = self, let documents = snap?.documents, error == nil else { return }
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
            let isAppForeground = (UIApplication.shared.applicationState == .active)
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

                let createdTs = (cleanData["creadoEn"] as? NSNumber)?.int64Value
                    ?? (cleanData["timestamp"] as? NSNumber)?.int64Value
                    ?? (cleanData["_metaTimestamp"] as? NSNumber)?.int64Value
                    ?? 0
                let isRecentDispatch = createdTs > 0 && (nowMs - createdTs) < 600_000 // últimos 10 minutos

                let isAttending = attendedIds.contains(docId) || userEnServicio == docId
                let hasDeclined = declinedIds.contains(docId) || userEnServicio == "-\(docId)"

                if self.isFirstDispatchSnapshot {
                    self.knownDispatchIds.insert(docId)
                    self.lastDispatchClaves[docId] = clave
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
                    // Si es un despacho activo reciente (< 10 min) que aún no ha sonado en este dispositivo, disparar alerta urgente
                    if isRecentDispatch && !alertedIds.contains(docId) && !isAttending && !hasDeclined && !isAirplane && !isCentral {
                        alertedIds.append(docId)
                        didModifyAlerted = true
                        if is09 {
                            if isAppForeground {
                                IOSSoundPlayer.shared.vibrateEmergency()
                            } else {
                                IOSSoundPlayer.shared.play(nameOrClave: clave, forceMaxVolume: true)
                                IOSSoundPlayer.shared.scheduleRepeat(dispatchId: docId, clave: clave)
                            }
                            AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                        } else {
                            IOSSoundPlayer.shared.vibrateEmergency()
                        }
                        self.postLocalBanner(
                            id: "disp_\(docId)",
                            title: "🚨 DESPACHO \(clave)",
                            body: direccion,
                            soundName: is09 ? "\(IOSSoundPlayer.shared.resolveSoundFileName(for: clave)).mp3" : nil,
                            userInfo: ["type": "DISPATCH", "payloadId": docId, "clave": clave]
                        )
                    } else if !alertedIds.contains(docId) {
                        alertedIds.append(docId)
                        didModifyAlerted = true
                    }
                    continue
                }

                if isAirplane || isCentral {
                    self.knownDispatchIds.insert(docId)
                    self.lastDispatchClaves[docId] = clave
                    if !alertedIds.contains(docId) {
                        alertedIds.append(docId)
                        didModifyAlerted = true
                    }
                    continue
                }

                // A. Nuevo despacho o pre-despacho en vivo
                if !self.knownDispatchIds.contains(docId) {
                    self.knownDispatchIds.insert(docId)
                    self.lastDispatchClaves[docId] = clave
                    if !alertedIds.contains(docId) {
                        alertedIds.append(docId)
                        didModifyAlerted = true
                    }
                    if !isAttending && !hasDeclined {
                        if is09 {
                            if isAppForeground {
                                // Si la app está abierta en primer plano: NO hacer sonar el tono, solo vibrar fuerte y levantar pantalla completa
                                IOSSoundPlayer.shared.vibrateEmergency()
                            } else {
                                // Con la app en recientes / segundo plano / pantalla bloqueada: hacer sonar el tono oficial de la clave al 100%
                                IOSSoundPlayer.shared.play(nameOrClave: clave, forceMaxVolume: true)
                                IOSSoundPlayer.shared.scheduleRepeat(dispatchId: docId, clave: clave)
                            }
                            AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                        } else {
                            IOSSoundPlayer.shared.vibrateEmergency()
                        }
                        self.postLocalBanner(
                            id: "disp_\(docId)",
                            title: "🚨 DESPACHO \(clave)",
                            body: direccion,
                            soundName: is09 ? "\(IOSSoundPlayer.shared.resolveSoundFileName(for: clave)).mp3" : nil,
                            userInfo: ["type": "DISPATCH", "payloadId": docId, "clave": clave]
                        )
                    }
                } else {
                    // B. Escalamiento (10-30 / FORESTAL / 9-0)
                    let prevClave = self.lastDispatchClaves[docId] ?? ""
                    if !prevClave.isEmpty && prevClave != clave {
                        self.lastDispatchClaves[docId] = clave
                        let cUp = clave.uppercased()
                        let isEscalation = cUp.contains("10-30") || cUp.contains("FORESTAL") || cUp.contains("9-0") || cUp.contains("COMANDANCIA")
                        if isEscalation && !isAttending {
                            if isAppForeground {
                                IOSSoundPlayer.shared.vibrateEmergency()
                            } else {
                                IOSSoundPlayer.shared.play(nameOrClave: clave, forceMaxVolume: true)
                            }
                            AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                            self.postLocalBanner(
                                id: "esc_\(docId)_\(clave)",
                                title: "🔥 CAMBIO DE CLAVE A \(clave)",
                                body: "\(direccion) - ¡SE REQUIERE PERSONAL!",
                                soundName: "\(IOSSoundPlayer.shared.resolveSoundFileName(for: clave)).mp3",
                                userInfo: ["type": "DISPATCH_UPDATE", "payloadId": docId, "clave": clave]
                            )
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
                                if is09 {
                                    if isAppForeground {
                                        IOSSoundPlayer.shared.vibrateEmergency()
                                    } else {
                                        IOSSoundPlayer.shared.play(nameOrClave: clave.isEmpty ? "despacho" : clave, forceMaxVolume: true)
                                    }
                                    AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                                } else {
                                    IOSSoundPlayer.shared.vibrateEmergency()
                                }
                                self.postLocalBanner(
                                    id: "req1210_\(key)_\(cTs)",
                                    title: "🚨 SOLICITUD 12-10 (\(uName))",
                                    body: "Se solicita conductor para unidad \(uName) en \(direccion)",
                                    soundName: is09 ? "importante.mp3" : nil,
                                    userInfo: ["type": "DISPATCH_UPDATE", "payloadId": docId, "clave": clave]
                                )
                            }
                        } else if prevCTs == 0 {
                            self.lastUnitConductorReq[key] = cTs
                        }

                        let prevPTs = self.lastUnitPersonalReq[key] ?? 0
                        if pTs > prevPTs && pTs > 0 {
                            self.lastUnitPersonalReq[key] = pTs
                            if !isAttending && !hasDeclined {
                                if is09 {
                                    if isAppForeground {
                                        IOSSoundPlayer.shared.vibrateEmergency()
                                    } else {
                                        IOSSoundPlayer.shared.play(nameOrClave: clave.isEmpty ? "despacho" : clave, forceMaxVolume: true)
                                    }
                                    AppDelegate.dispatchNativeIntentToWebView(dispatchId: docId, showFullscreen: true)
                                } else {
                                    IOSSoundPlayer.shared.vibrateEmergency()
                                }
                                self.postLocalBanner(
                                    id: "req66_\(key)_\(pTs)",
                                    title: "🚨 SOLICITUD 6-6 (\(uName))",
                                    body: "Se solicita personal para unidad \(uName) en \(direccion)",
                                    soundName: is09 ? "importante.mp3" : nil,
                                    userInfo: ["type": "DISPATCH_UPDATE", "payloadId": docId, "clave": clave]
                                )
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

            self.isFirstDispatchSnapshot = false
            self.latestDespachosPayload = rawDocsArray
            self.pushSnapshotToWebView(collection: "despachos", payload: rawDocsArray)
        }

        // 3. Listener nativo de vehículos
        vehiculosListener = db.collection("vehiculos").addSnapshotListener { [weak self] snap, error in
            guard let self = self, let documents = snap?.documents, error == nil else { return }
            let rawDocsArray: [[String: Any]] = documents.map { doc in
                [
                    "id": doc.documentID,
                    "data": self.sanitizeDoc(doc.data()),
                    "hasPendingWrites": doc.metadata.hasPendingWrites
                ]
            }
            self.latestVehiculosPayload = rawDocsArray
            self.pushSnapshotToWebView(collection: "vehiculos", payload: rawDocsArray)
        }

        // 4. Listener nativo de alertas / órdenes del día
        alertasListener = db.collection("alertas").addSnapshotListener { [weak self] snap, error in
            guard let self = self, let documents = snap?.documents, error == nil else { return }
            let rawDocsArray: [[String: Any]] = documents.map { doc in
                [
                    "id": doc.documentID,
                    "data": self.sanitizeDoc(doc.data()),
                    "hasPendingWrites": doc.metadata.hasPendingWrites
                ]
            }
            self.latestAlertasPayload = rawDocsArray
            self.pushSnapshotToWebView(collection: "alertas", payload: rawDocsArray)
        }
    }

    private func postLocalBanner(id: String, title: String, body: String, soundName: String?, userInfo: [String: Any]) {
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
        } else {
            content.sound = .default
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
    static var sharedWebView: WKWebView?
    static var currentTenantProjectId: String?
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

    static func configureDynamicFirebase(configDict: [String: Any]) {
        guard let apiKey = configDict["apiKey"] as? String, !apiKey.isEmpty,
              let projectId = configDict["projectId"] as? String, !projectId.isEmpty else {
            return
        }
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
            // También reconfigurar la app por defecto para que Messaging.messaging() apunte al proyecto del tenant
            FirebaseApp.app()?.delete { _ in
                DispatchQueue.main.async {
                    if FirebaseApp.app() == nil {
                        FirebaseApp.configure(options: options)
                    }
                    finalizeTenantSetup()
                }
            }
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

    static func authenticateNativeApp(_ app: FirebaseApp?, completion: (() -> Void)? = nil) {
        guard let targetApp = app else {
            completion?()
            return
        }
        let auth = Auth.auth(app: targetApp)
        if auth.currentUser != nil {
            completion?()
            return
        }
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
                        auth.signInAnonymously { _, _ in
                            completion?()
                        }
                    } else {
                        completion?()
                    }
                }
                return
            }
        }
        auth.signInAnonymously { _, _ in
            completion?()
        }
    }

    static func activeFirestore() -> Firestore {
        if let pid = currentTenantProjectId, let namedApp = FirebaseApp.app(name: "tenant_\(pid)") {
            return Firestore.firestore(app: namedApp)
        }
        return Firestore.firestore()
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
        UIApplication.shared.isIdleTimerDisabled = true
        Self.restoreUserDefaultsFromCachedWebSessionIfNeeded()

        if let tenantDict = Self.resolveStoredTenantConfig() {
            Self.configureDynamicFirebase(configDict: tenantDict)
        } else if FirebaseApp.app() == nil {
            // Solo inicializar FirebaseApp base para APNs/Messaging sin arrancar listeners de despachos contra proyecto incorrecto
            FirebaseApp.configure()
        }

        Messaging.messaging().delegate = self
        UNUserNotificationCenter.current().delegate = self

        configureNotificationCategories()
        requestNotificationPermissions(application: application)
        Self.scheduleDailyReminders()
        IOSSoundPlayer.shared.updateBackgroundKeepAlive()
        IOSLocationService.shared.updateStandbyKeepAlive()
        return true
    }

    func applicationWillResignActive(_ application: UIApplication) {
        // Al pasar a Recientes / segundo plano, mantener vivo el canal de audio, GPS standby y tarea de fondo si está en 0-9
        IOSSoundPlayer.shared.updateBackgroundKeepAlive()
        IOSLocationService.shared.updateStandbyKeepAlive()
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
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
        if bgTaskId != .invalid {
            application.endBackgroundTask(bgTaskId)
            bgTaskId = .invalid
        }
        // Al abrir o volver a la app manualmente, silenciar la alarma audible y mantener vivo el keep-alive + listeners sin reiniciarlos
        IOSSoundPlayer.shared.stop()
        IOSLocationService.shared.syncPermissionsToWebView()
        IOSLocationService.shared.updateStandbyKeepAlive()
        if Self.currentTenantProjectId != nil {
            IOSRealtimeService.shared.ensureListenersRunning()
        } else if let tenantDict = Self.resolveStoredTenantConfig() {
            Self.configureDynamicFirebase(configDict: tenantDict)
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
        let title = (userInfo["title"] as? String) ?? ""
        let body = (userInfo["body"] as? String) ?? ""
        let is1210or66 = title.contains("12-10") || title.contains("6-6") || body.contains("12-10") || body.contains("6-6")

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
        if type == "DISPATCH_UPDATE" && !isEscalation && !is1210or66 {
            completionHandler(.noData)
            return
        }
        if hasDeclined && !isEscalation {
            completionHandler(.noData)
            return
        }

        if is1210or66 {
            if is09 && application.applicationState != .active {
                IOSSoundPlayer.shared.play(nameOrClave: clave.isEmpty ? "despacho" : clave, forceMaxVolume: true)
            }
            if is09 && !payloadId.isEmpty {
                Self.dispatchNativeIntentToWebView(dispatchId: payloadId, showFullscreen: true)
            }
        } else if (type == "DISPATCH" || isEscalation) && (is09 || isEscalation) {
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
            if UIApplication.shared.applicationState != .active {
                completionHandler([.banner, .list, .sound])
            } else {
                completionHandler([.banner, .list])
            }
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
        if scheme == "geo" || scheme == "tel" || scheme == "mailto" || scheme == "sms" || scheme == "whatsapp" || scheme == "maps" ||
           host.contains("wa.me") || host.contains("whatsapp.com") || host.contains("maps.apple.com") || host.contains("google.com") {
            _ = Self.handleExternalUrl(url)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
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
        case "syncUserSession":
            if let user = dict["user"] as? [String: Any] {
                let userId = AppDelegate.stringFromAny(user["idRegistro"] ?? user["id"])
                let nombre = AppDelegate.stringFromAny(user["nombre"], fallback: "Bombero")
                let rango = AppDelegate.stringFromAny(user["rango"], fallback: "Voluntario")
                let compania = AppDelegate.stringFromAny(user["compania"], fallback: "1")
                let estado = AppDelegate.stringFromAny(user["estado"], fallback: "0-9").uppercased()
                let conductor = (user["conductor"] as? Bool) ?? (user["conductor"] as? NSNumber)?.boolValue ?? false
                let estadoEspecial = AppDelegate.stringFromAny(user["estadoEspecial"])
                let enServicio = AppDelegate.stringFromAny(user["enServicio"])

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
                IOSSoundPlayer.shared.updateBackgroundKeepAlive()
                IOSLocationService.shared.updateStandbyKeepAlive()
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
                IOSSoundPlayer.shared.stop()
                IOSSoundPlayer.shared.stopBackgroundKeepAlive()
                IOSLocationService.shared.updateStandbyKeepAlive()
            } else {
                IOSSoundPlayer.shared.updateBackgroundKeepAlive()
                IOSLocationService.shared.updateStandbyKeepAlive()
            }
            AppDelegate.scheduleDailyReminders()
        case "acknowledgeDispatch":
            let dispatchId = AppDelegate.stringFromAny(dict["dispatchId"])
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
