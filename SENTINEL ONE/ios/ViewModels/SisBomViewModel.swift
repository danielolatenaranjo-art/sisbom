import Foundation
import Combine
import FirebaseCore
import FirebaseFirestore
import FirebaseMessaging
import FirebaseAuth
import AVFoundation
import AudioToolbox
import UIKit

// Screen definitions matching Android enum classes
enum AppScreen: String, Codable {
    case setup
    case login
    case main
    case chat
}

enum MainTab: String, Codable, CaseIterable {
    case actividad
    case despacho
    case ordenes
    case alertas
    case asistencia
    case disponibles
}

class SisBomViewModel: ObservableObject {
    private let repository = FirebaseRepository()
    private var listeners: [ListenerRegistration] = []
    
    // MARK: - Reactive UI States
    @Published var currentScreen: AppScreen = .login
    @Published var currentTab: MainTab = .actividad
    @Published var currentUser: UserPersonal? = nil
    @Published var isSyncing: Bool = false
    @Published var isLoggingIn: Bool = false
    
    // MARK: - SaaS License States
    @Published var saasLicenseKey: String = ""
    @Published var saasClientName: String = ""
    @Published var saasLogoUrl: String = ""
    @Published var saasActivationError: String = ""
    @Published var isActivatingLicense: Bool = false
    @Published var requiresAppRestartAfterLicenseChange: Bool = false
    
    @Published var isDarkMode: Bool = false {
        didSet {
            UserDefaults.standard.set(isDarkMode, forKey: "app_dark_mode")
            updateInterfaceStyle()
        }
    }
    
    // Data lists
    @Published var personnelList: [UserPersonal] = []
    @Published var dispatchesList: [Dispatch] = []
    @Published var alertsList: [AlertaItem] = []
    @Published var vehiclesList: [Vehicle] = []
    @Published var attendanceList: [AttendanceSheet] = []
    @Published var isCentralActive: Bool = false
    @Published var centralOperatorName: String = ""
    @Published var centralOperatorId: String = ""
    @Published var isAirplaneMode: Bool = false
    @Published var isSyncingAttendance: Bool = false
    
    // Navigation details
    @Published var selectedDispatchId: String? = nil
    @Published var activeChatId: String? = nil
    @Published var activeChatAlert: AlertaItem? = nil
    @Published var selectedOrdenId: String? = nil
    @Published var showChangelogDialog: Bool = false
    @Published var fullscreenDispatchId: String? = nil
    private var pendingChatId: String? = nil
    
    // Feedback strings
    @Published var changePasswordError: String = ""
    @Published var changePasswordSuccess: String = ""
    
    // Known items to avoid duplicate sound triggers
    private var knownDispatchIds = Set<String>()
    private var knownAlertIds = Set<String>()
    private var isFirstCheck = true
    
    // Bloqueo temporal para evitar efecto rebote (race conditions de Firestore)
    private var lastStatusChangeTime: Date = Date.distantPast
    private var pendingStatus: String? = nil
    private var lastServiceChangeTime: Date = Date.distantPast
    private var pendingService: String? = nil
    
    // Audio Player
    private var audioPlayer: AVAudioPlayer?
    
    init() {
        // Load Dark Mode Preference
        if UserDefaults.standard.object(forKey: "app_dark_mode") != nil {
            self.isDarkMode = UserDefaults.standard.bool(forKey: "app_dark_mode")
        } else {
            // Fallback to system setting
            self.isDarkMode = UITraitCollection.current.userInterfaceStyle == .dark
        }

        if UserDefaults.standard.object(forKey: "MODO_AVION") != nil {
            self.isAirplaneMode = UserDefaults.standard.bool(forKey: "MODO_AVION")
        }
        
        updateInterfaceStyle()
        
        // Load local cache for instant offline view
        loadLocalCache()
        
        // Check SaaS License configuration
        let savedLicense = UserDefaults.standard.string(forKey: "saas_license_key") ?? ""
        let savedConfigStr = UserDefaults.standard.string(forKey: "saas_firebase_config") ?? ""
        self.saasClientName = UserDefaults.standard.string(forKey: "saas_client_name") ?? ""
        self.saasLogoUrl = UserDefaults.standard.string(forKey: "saas_logo_url") ?? ""
        self.saasLicenseKey = savedLicense
        
        if savedLicense.isEmpty || savedConfigStr.isEmpty {
            self.currentScreen = .setup
        } else {
            AppDelegate.configureDynamicFirebase(configStr: savedConfigStr)
            
            // Load saved user session
            if let savedUser: UserPersonal = loadCache(key: "fire_user") {
                self.currentUser = savedUser
                self.currentScreen = .main
                self.startFirebaseSync(userId: savedUser.idRegistro)
            } else {
                self.currentScreen = .login
            }
            
            // Re-check license validity asynchronously
            checkLicenseStatus()
        }
        
        // Timer to skip initial sound triggers for historical items
        DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) {
            self.isFirstCheck = false
        }
        
        // Listen for open chat room notification
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleOpenChatRoomNotification(_:)),
            name: NSNotification.Name("OpenChatRoom"),
            object: nil
        )
        
        // Check if there is a cached launchChatId
        if let launchChatId = AppDelegate.launchChatId {
            self.openChatRoom(chatId: launchChatId)
            AppDelegate.launchChatId = nil
        }
        
        let lastSeenVersion = UserDefaults.standard.string(forKey: "last_seen_version") ?? ""
        if lastSeenVersion != "2.1.4" {
            self.showChangelogDialog = true
        }
    }
    
    @objc private func handleOpenChatRoomNotification(_ notification: Foundation.Notification) {
        if let chatId = notification.userInfo?["chatId"] as? String {
            DispatchQueue.main.async {
                self.openChatRoom(chatId: chatId)
            }
        }
    }
    
    func dismissChangelog() {
        showChangelogDialog = false
        UserDefaults.standard.set("2.1.4", forKey: "last_seen_version")
    }
    
    func openChatRoom(chatId: String) {
        self.currentScreen = .main
        self.currentTab = .alertas
        
        if let alert = alertsList.first(where: { $0.idAlerta == chatId }) {
            self.activeChatId = chatId
            self.activeChatAlert = alert
            self.pendingChatId = nil
        } else {
            self.pendingChatId = chatId
        }
    }
    
    private func updateInterfaceStyle() {
        DispatchQueue.main.async {
            if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
                windowScene.windows.forEach { window in
                    window.overrideUserInterfaceStyle = self.isDarkMode ? .dark : .light
                }
            }
        }
    }
    
    // MARK: - Local Cache Persistence
    
    private func saveCache<T: Codable>(_ value: T, key: String) {
        if let encoded = try? JSONEncoder().encode(value) {
            UserDefaults.standard.set(encoded, forKey: key)
        }
    }
    
    private func loadCache<T: Codable>(key: String) -> T? {
        if let data = UserDefaults.standard.data(forKey: key) {
            return try? JSONDecoder().decode(T.self, from: data)
        }
        return nil
    }
    
    private func loadLocalCache() {
        if let list: [UserPersonal] = loadCache(key: "cache_personnel") { personnelList = list }
        if let list: [Dispatch] = loadCache(key: "cache_dispatches") {
            dispatchesList = list
            list.forEach { knownDispatchIds.insert($0.idServicio) }
        }
        if let list: [AlertaItem] = loadCache(key: "cache_alerts") {
            alertsList = list
            list.forEach { knownAlertIds.insert($0.idAlerta) }
        }
        if let list: [Vehicle] = loadCache(key: "cache_vehicles") { vehiclesList = list }
        if let list: [AttendanceSheet] = loadCache(key: "cache_attendance") { attendanceList = list }
    }
    
    // MARK: - Firebase Syncing
    
    func startFirebaseSync(userId: String) {
        isSyncing = true
        
        // Unsubscribe from previous listeners if any
        stopFirebaseSync()
        
        // FCM Subscriptions
        Messaging.messaging().subscribe(toTopic: "all")
        Messaging.messaging().subscribe(toTopic: "despachos")
        Messaging.messaging().subscribe(toTopic: "alertas")
        Messaging.messaging().subscribe(toTopic: "alertas_generales")
        if !userId.isEmpty {
            Messaging.messaging().subscribe(toTopic: "usuario_\(userId)")
            Messaging.messaging().subscribe(toTopic: "personal_\(userId)")
        }
        
        self.setupListeners(userId: userId)
    }
    
    private func setupListeners(userId: String) {
        let user = self.currentUser
        let cargo = user?.cargo.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
        let radial = user?.idRadial.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let isComandante = cargo == "COMANDANTE" && ["1", "01", "2", "02", "3", "03"].contains(radial)

        // Listener 1: Central State
        let l1 = repository.getCentralState { [weak self] data in
            guard let self = self else { return }
            let estado = data["estado"] as? String ?? ""
            let idReg = data["idRegistro"] as? String ?? ""
            let opName = (data["nombreBombero"] as? String) ?? (data["operador"] as? String) ?? ""
            let isActive = estado.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "activo"
            
            let myId = self.currentUser?.idRegistro ?? ""
            let myName = self.currentUser?.nombreBombero ?? ""
            
            let isMeActive = isActive &&
                ((!myId.isEmpty && idReg.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == myId.lowercased()) ||
                 (!myName.isEmpty && opName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == myName.lowercased()))
            
            self.isCentralActive = isMeActive
            self.centralOperatorName = isActive ? opName : ""
            self.centralOperatorId = isActive ? idReg : ""
            
            if isMeActive {
                if self.currentUser?.estado != "0-9" {
                    self.changeStatus(newStatus: "0-9")
                }
                self.currentTab = .despacho
            }
        }
        listeners.append(l1)
        
        // Listener 2: Personnel list or Personnel self
        let l2: ListenerRegistration
        if isComandante {
            l2 = repository.getPersonnel { [weak self] list in
                guard let self = self else { return }
                self.personnelList = list
                self.saveCache(list, key: "cache_personnel")
                if let my = self.currentUser, let fresh = list.first(where: { $0.idRegistro == my.idRegistro }) {
                    self.updateCurrentUserData(fresh: fresh)
                }
            }
        } else {
            l2 = repository.getPersonnelSelf(userId: userId) { [weak self] selfUser in
                guard let self = self else { return }
                if let fresh = selfUser {
                    self.personnelList = [fresh]
                    self.saveCache(self.personnelList, key: "cache_personnel")
                    self.updateCurrentUserData(fresh: fresh)
                }
            }
        }
        listeners.append(l2)
        
        // Listener 3: Dispatches list
        let l3 = repository.getDispatches { [weak self] list in
            guard let self = self else { return }
            let oldList = self.dispatchesList
            self.dispatchesList = list
            self.saveCache(list, key: "cache_dispatches")
            
            for d in list {
                let oldDispatch = oldList.first(where: { $0.idServicio == d.idServicio })
                let oldClave = oldDispatch?.clave.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
                let newClave = d.clave.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()

                let isEscalation1030 = (oldClave == "10-0" || oldClave.contains("10-0")) && newClave.contains("10-30")
                let isEscalationForestal = (oldClave == "10-2" || oldClave.contains("10-2")) && newClave.contains("FORESTAL")
                let isEscalation = isEscalation1030 || isEscalationForestal

                let cleanClave = d.clave.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                let is1030 = cleanClave == "10-30" || cleanClave == "10_30" || cleanClave.contains("10-30") || newClave.contains("FORESTAL")
                let trackerKey1030 = "\(d.idServicio)_\(isEscalationForestal ? "FORESTAL" : "10_30")"

                let userStatus = self.currentUser?.estado.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
                let isSpecial = userStatus.contains("SUSPENDIDO") || userStatus == "CDS" || userStatus.contains("LICENCIA") || userStatus == "PERMISO"
                let is09 = userStatus == "0-9" && !isSpecial
                let is08 = userStatus == "0-8" || userStatus == "10-8"
                let isAbsoluteSilence = UserDefaults.standard.bool(forKey: "SILENCIO_ABSOLUTO") || userStatus.contains("ABSOLUTO")
                let userEnServicio = self.currentUser?.enServicio.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let isAttending = !userEnServicio.isEmpty && userEnServicio == d.idServicio

                // 1. Escalamiento a alarma declarada (10-0 -> 10-30 o 10-2 -> FORESTAL)
                if isEscalation && d.operadorFinal.isEmpty && !self.isFirstCheck {
                    if !self.knownDispatchIds.contains(trackerKey1030) {
                        self.knownDispatchIds.insert(trackerKey1030)
                        self.knownDispatchIds.insert(d.idServicio)

                        if !isSpecial && !isAbsoluteSilence && !isAttending && (is09 || is08) && !self.isCentralActive {
                            self.playSound(soundName: "c10_30")
                            self.triggerVibration()
                            self.fullscreenDispatchId = d.idServicio
                        }
                    }
                } else if is1030 && !self.knownDispatchIds.contains(trackerKey1030) {
                    self.knownDispatchIds.insert(trackerKey1030)
                    self.knownDispatchIds.insert(d.idServicio)
                    
                    // Alarma 10-30: Suena c10_30 para 0-9 (sin asistir o no asistir) y 0-8 (sin silencio absoluto)
                    if !self.isFirstCheck && !isSpecial && !isAbsoluteSilence && !isAttending {
                        self.playSound(soundName: "c10_30")
                        self.triggerVibration()
                        if d.operadorFinal.isEmpty && !self.isCentralActive && (is09 || is08) {
                            self.fullscreenDispatchId = d.idServicio
                        }
                    }
                } else if !self.knownDispatchIds.contains(d.idServicio) {
                    self.knownDispatchIds.insert(d.idServicio)
                    let soundName = cleanClave.contains("llamado") || cleanClave.contains("comandancia") ? "llamado_comandancia" : (cleanClave == "9-0" || cleanClave == "9_0" ? "c9_0" : "c\(cleanClave.replacingOccurrences(of: "-", with: "_"))")
                    
                    if !self.isFirstCheck && !is08 && !isSpecial && !isAbsoluteSilence {
                        self.playSound(soundName: soundName)
                        self.triggerVibration()
                    }

                    if !self.isFirstCheck && d.operadorFinal.isEmpty && !self.isCentralActive && is09 && !isAttending {
                        self.fullscreenDispatchId = d.idServicio
                    }
                }

                // 2. Unit-level 12-10 and 6-6 Checks
                for (unitName, unit) in d.unidades {
                    let userEnServicio = self.currentUser?.enServicio.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    let isAssignedToThis = userEnServicio == d.idServicio
                    let hasDeclined = userEnServicio.hasPrefix("-")
                    let userStatus = self.currentUser?.estado.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
                    let cargoUpper = self.currentUser?.cargo.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
                    let radialUpper = self.currentUser?.idRadial.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
                    let isConductor = (self.currentUser?.conductor ?? 0) == 1 || cargoUpper.contains("CONDUCTOR") || cargoUpper.contains("MAQUINISTA") || radialUpper.hasPrefix("C")

                    // 12-10 Conductor Request
                    let condTs = unit.solicitudConductorTimestamp
                    let condAt = unit.solicitudConductorAt
                    if (condTs > 0 || !condAt.isEmpty) && isConductor && !isAssignedToThis {
                        let key1210 = "\(d.idServicio)_1210_\(unitName)_\(condTs > 0 ? String(condTs) : condAt)"
                        if !self.knownDispatchIds.contains(key1210) {
                            self.knownDispatchIds.insert(key1210)
                            if !self.isFirstCheck && is09 && !hasDeclined {
                                self.playSound(soundName: "alerta")
                                self.triggerVibration()
                            }
                        }
                    }

                    // 6-6 Personal Request
                    let persTs = unit.solicitudPersonalTimestamp
                    let persAt = unit.solicitudPersonalAt
                    if (persTs > 0 || !persAt.isEmpty) && !isAssignedToThis {
                        let key66 = "\(d.idServicio)_66_\(unitName)_\(persTs > 0 ? String(persTs) : persAt)"
                        if !self.knownDispatchIds.contains(key66) {
                            self.knownDispatchIds.insert(key66)
                            if !self.isFirstCheck && is09 && !hasDeclined {
                                self.playSound(soundName: "alerta")
                                self.triggerVibration()
                            }
                        }
                    }
                }
            }
            
            // Clean local service if dispatch is closed or assigned to someone else
            if let my = self.currentUser {
                let mySvcId = my.enServicio.trimmingCharacters(in: .whitespacesAndNewlines)
                if !mySvcId.isEmpty && mySvcId != "0" {
                    let svc = list.first(where: { $0.idServicio == mySvcId })
                    if svc == nil || !(svc?.operadorFinal.isEmpty ?? true) {
                        self.changePersonalService(newServiceId: "0")
                    }
                }
            }
            
            if self.isFirstCheck {
                self.isFirstCheck = false
            }
        }
        listeners.append(l3)
        
        // Listener 4: Alerts list
        let l4 = repository.getAlerts { [weak self] list in
            guard let self = self else { return }
            self.alertsList = list
            self.saveCache(list, key: "cache_alerts")
            
            for a in list {
                if !self.knownAlertIds.contains(a.idAlerta) {
                    self.knownAlertIds.insert(a.idAlerta)
                    if !self.isFirstCheck {
                        self.playSound(soundName: "alerta")
                        self.triggerVibration()
                    }
                }
            }
            
            // Update active chat alert if it updates
            if let activeChatId = self.activeChatId,
               let updated = list.first(where: { $0.idAlerta == activeChatId }) {
                self.activeChatAlert = updated
            }
            
            // Resolve pending chat ID if present
            if let pendingId = self.pendingChatId,
               let alert = list.first(where: { $0.idAlerta == pendingId }) {
                self.activeChatId = pendingId
                self.activeChatAlert = alert
                self.pendingChatId = nil
            }
        }
        listeners.append(l4)
        
        // Listener 5: Vehicles list
        let l5 = repository.getVehicles { [weak self] list in
            guard let self = self else { return }
            self.vehiclesList = list
            self.saveCache(list, key: "cache_vehicles")
        }
        listeners.append(l5)
        
        // Listener 6: Attendance list
        let l6 = repository.getAttendance(userId: userId) { [weak self] list in
            guard let self = self else { return }
            self.attendanceList = list
            self.saveCache(list, key: "cache_attendance")
        }
        listeners.append(l6)
        
        self.isSyncing = false
    }
    
    func stopFirebaseSync() {
        listeners.forEach { $0.remove() }
        listeners.removeAll()
    }
    
    // MARK: - Actions
    
    func performLogin(idReg: String, pass: String, completion: @escaping (Bool) -> Void) {
        let cleanId = idReg.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanPass = pass.trimmingCharacters(in: .whitespacesAndNewlines)
        
        if cleanId.isEmpty || cleanPass.isEmpty {
            completion(false)
            return
        }
        
        isLoggingIn = true
        
        let email = cleanId.lowercased() + "@sisbom.com"
        let securePass = cleanPass + "_secure_sisbom"
        
        // Helper to validate and finalize login once user document data is found
        func processUserLogin(docId: String, data: [String: Any]) {
            let user = UserPersonal(docId: docId, data: data)
            
            // Validate password against Firestore
            let storedPass = user.contrasena.trimmingCharacters(in: .whitespacesAndNewlines)
            if !storedPass.isEmpty && storedPass != cleanPass {
                self.isLoggingIn = false
                completion(false)
                return
            }
            
            // Validate active
            if !user.activo {
                self.isLoggingIn = false
                completion(false)
                return
            }
            
            self.currentUser = user
            self.saveCache(user, key: "fire_user")
            self.currentScreen = .main
            self.isLoggingIn = false
            self.startFirebaseSync(userId: user.idRegistro)
            completion(true)
        }
        
        // 1. Try Firebase Auth sign-in
        Auth.auth().signIn(withEmail: email, password: securePass) { [weak self] authResult, authError in
            guard let self = self else { return }
            
            let db = Firestore.firestore()
            
            // 2. Query Firestore personal collection
            db.collection("personal").document(cleanId).getDocument { doc, docError in
                if let doc = doc, doc.exists, let data = doc.data() {
                    processUserLogin(docId: doc.documentID, data: data)
                } else {
                    // Try lowercase ID in case doc is keyed with lowercase
                    db.collection("personal").document(cleanId.lowercased()).getDocument { docLower, _ in
                        if let docLower = docLower, docLower.exists, let dataLower = docLower.data() {
                            processUserLogin(docId: docLower.documentID, data: dataLower)
                        } else {
                            // Query by idRegistro field
                            db.collection("personal").whereField("idRegistro", isEqualTo: cleanId).getDocuments { snap, _ in
                                if let queryDoc = snap?.documents.first {
                                    let qData = queryDoc.data()
                                    processUserLogin(docId: queryDoc.documentID, data: qData)
                                } else {
                                    // Local cache fallback
                                    if let matchLocal = self.personnelList.first(where: {
                                        ($0.idRegistro.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == cleanId.lowercased()) &&
                                        ($0.contrasena.trimmingCharacters(in: .whitespacesAndNewlines) == cleanPass)
                                    }) {
                                        if !matchLocal.activo {
                                            self.isLoggingIn = false
                                            completion(false)
                                            return
                                        }
                                        self.currentUser = matchLocal
                                        self.saveCache(matchLocal, key: "fire_user")
                                        self.currentScreen = .main
                                        self.isLoggingIn = false
                                        self.startFirebaseSync(userId: matchLocal.idRegistro)
                                        completion(true)
                                    } else {
                                        self.isLoggingIn = false
                                        completion(false)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    func logout() {
        let oldUserId = currentUser?.idRegistro ?? ""
        
        // Unsubscribe from FCM topics
        Messaging.messaging().unsubscribe(fromTopic: "alertas_generales")
        Messaging.messaging().unsubscribe(fromTopic: "despachos")
        if !oldUserId.isEmpty {
            Messaging.messaging().unsubscribe(fromTopic: "usuario_\(oldUserId)")
        }
        
        stopFirebaseSync()
        
        try? Auth.auth().signOut()
        
        // Clear UserDefaults Cache
        let domain = Bundle.main.bundleIdentifier!
        UserDefaults.standard.removePersistentDomain(forName: domain)
        UserDefaults.standard.synchronize()
        
        // Reset state variables
        currentUser = nil
        currentScreen = .login
        currentTab = .actividad
        isCentralActive = false
        personnelList = []
        dispatchesList = []
        alertsList = []
        vehiclesList = []
        attendanceList = []
        knownDispatchIds.removeAll()
        knownAlertIds.removeAll()
        isFirstCheck = true
    }
    
    func changeStatus(newStatus: String) {
        guard let user = currentUser else { return }
        
        // Optimistic update
        let updated = UserPersonal(
            idRegistro: user.idRegistro,
            nombreBombero: user.nombreBombero,
            idRadial: user.idRadial,
            contrasena: user.contrasena,
            activo: user.activo,
            conductor: user.conductor,
            enServicio: user.enServicio,
            cargo: user.cargo,
            foto: user.foto,
            estado: newStatus
        )
        self.currentUser = updated
        self.saveCache(updated, key: "fire_user")
        
        self.pendingStatus = newStatus
        self.lastStatusChangeTime = Date()
        
        UserDefaults.standard.set(newStatus, forKey: "LAST_SELF_STATUS_CHANGE")
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "LAST_SELF_STATUS_TIME")

        repository.updatePersonalStatus(userId: user.idRegistro, status: newStatus) { [weak self] result in
            guard let self = self else { return }
            if case .success = result {
                self.repository.addStatusHistoryEntry(userId: user.idRegistro, status: newStatus) { _ in }
            }
        }
    }
    
    func changePersonalService(newServiceId: String) {
        guard let user = currentUser else { return }
        
        // Optimistic update
        let updated = UserPersonal(
            idRegistro: user.idRegistro,
            nombreBombero: user.nombreBombero,
            idRadial: user.idRadial,
            contrasena: user.contrasena,
            activo: user.activo,
            conductor: user.conductor,
            enServicio: newServiceId,
            cargo: user.cargo,
            foto: user.foto,
            estado: user.estado
        )
        self.currentUser = updated
        self.saveCache(updated, key: "fire_user")
        
        self.pendingService = newServiceId
        self.lastServiceChangeTime = Date()

        repository.updatePersonalService(userId: user.idRegistro, serviceId: newServiceId) { _ in }
    }
    
    func declineService(dispatchId: String) {
        fullscreenDispatchId = nil
        changePersonalService(newServiceId: "-\(dispatchId)")
    }
    
    func attendService(dispatchId: String, attend: Bool) {
        fullscreenDispatchId = nil
        if attend {
            changePersonalService(newServiceId: dispatchId)
        } else {
            declineService(dispatchId: dispatchId)
        }
    }
    
    // ANCLAR / DESANCLAR ALERTA
    func toggleAlertPin(alert: AlertaItem) {
        guard let myRadial = currentUser?.idRadial, !myRadial.isEmpty else { return }
        var list = alert.fijar.split(separator: ",").map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        
        if list.contains(myRadial) {
            list.removeAll { $0 == myRadial }
        } else {
            list.append(myRadial)
        }
        let finalString = list.joined(separator: ",")
        repository.updateAlertPin(alertId: alert.idAlerta, newFijar: finalString) { _ in }
    }
    
    // REGISTRAR VISTO / CONFORME
    func registerConforme(alert: AlertaItem) {
        guard let myRadial = currentUser?.idRadial, !myRadial.isEmpty else { return }
        var list = alert.conforme.split(separator: ",").map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        
        if !list.contains(myRadial) {
            list.append(myRadial)
            let finalString = list.joined(separator: ",")
            repository.updateAlertConforme(alertId: alert.idAlerta, newConforme: finalString) { _ in }
        }
    }
    
    func changePassword(newPass: String) {
        guard let user = currentUser else { return }
        if newPass.isEmpty {
            self.changePasswordError = "Ingrese una nueva contraseña"
            return
        }
        
        repository.updatePersonalPassword(userId: user.idRegistro, newPass: newPass) { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .success:
                self.changePasswordSuccess = "Contraseña cambiada con éxito"
                self.changePasswordError = ""
                let updated = UserPersonal(
                    idRegistro: user.idRegistro,
                    nombreBombero: user.nombreBombero,
                    idRadial: user.idRadial,
                    contrasena: newPass,
                    activo: user.activo,
                    conductor: user.conductor,
                    enServicio: user.enServicio,
                    cargo: user.cargo,
                    foto: user.foto,
                    estado: user.estado
                )
                self.currentUser = updated
                self.saveCache(updated, key: "fire_user")
            case .failure(let error):
                self.changePasswordError = "Error: \(error.localizedDescription)"
                self.changePasswordSuccess = ""
            }
        }
    }
    
    func sendChatMessage(alert: AlertaItem, messageText: String) {
        guard let user = currentUser else { return }
        
        let date = Date()
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "dd-MM-yyyy"
        let dateString = dateFormatter.string(from: date)
        
        let timeFormatter = DateFormatter()
        timeFormatter.dateFormat = "HH:mm"
        let timeString = timeFormatter.string(from: date)
        
        let sanitizedText = messageText.replacingOccurrences(of: "|", with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        
        let prefix = "\(dateString)/\(timeString)/\(user.idRegistro)"
        let newEntry = "\(prefix): \(sanitizedText)"
        
        let finalChatString = alert.mensajeAlerta.isEmpty ? newEntry : "\(alert.mensajeAlerta)|\(newEntry)"
        
        repository.sendChatMessage(alertId: alert.idAlerta, finalChatString: finalChatString) { _ in }
    }
    
    // MARK: - Central Dispatch Console
    
    func dispatchFromCentral(clave: String, lugar: String, preinforme: String, selectedVehicles: [Vehicle]) {
        guard isCentralActive, let op = currentUser else { return }
        
        // Find next sequential integer ID
        var maxId = 0
        for d in dispatchesList {
            if let num = Int(d.idServicio) {
                if num > maxId { maxId = num }
            }
        }
        let nextId = String(maxId + 1)
        
        let date = Date()
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "dd-MM-yyyy"
        let dateStr = dateFormatter.string(from: date)
        
        let timeFormatter = DateFormatter()
        timeFormatter.dateFormat = "HH:mm"
        let timeStr = timeFormatter.string(from: date)
        
        let opName = op.nombreBombero
        let vehicleClaves = selectedVehicles.map { $0.clave }
        let vehicleIds = selectedVehicles.map { $0.idCarro }
        
        let carrosTexto = vehicleClaves.joined(separator: " / ")
        
        // Create units map with initial state matching despacho.html
        var unidadesMap: [String: [String: String]] = [:]
        for vId in vehicleIds {
            unidadesMap[vId] = [
                "estado": "pending_departure",
                "horaSalida": ""
            ]
        }
        
        let safeCuerpo = (UserDefaults.standard.string(forKey: "saas_cuerpo") ?? "").replacingOccurrences(of: " ", with: "_")
        
        let dispatchData: [String: Any] = [
            "id": nextId,
            "idServicio": nextId,
            "estado": "activa",
            "clave": clave,
            "lugar": lugar,
            "preinforme": preinforme,
            "carros": vehicleClaves,
            "carrosTexto": carrosTexto,
            "horaDespacho": timeStr,
            "fechaDespacho": dateStr,
            "quienDespacha": opName,
            "operadorInicial": opName,
            "operadorFinal": "",
            "hora67": "",
            "source": "despacho.html",
            "unidades": unidadesMap,
            "obacServicio": "",
            "informeObac": "",
            "fechaTermino": "",
            "createdAt": Int64(Date().timeIntervalSince1970 * 1000),
            "pushSent": false,
            "cuerpoId": safeCuerpo
        ]
        
        repository.createDispatchNew(dispatchId: nextId, data: dispatchData) { [weak self] result in
            guard let self = self else { return }
            if case .success = result {
                // Update vehicle service status
                for vId in vehicleIds {
                    self.repository.updateVehicleService(vehicleId: vId, enServicio: nextId) { _ in }
                }
                
                // Set dispatching operator as active in service
                self.changePersonalService(newServiceId: nextId)
            }
        }
    }
    
    // MARK: - Audio and Vibration System
    
    private var volumeObservation: NSKeyValueObservation?
    
    var hasActiveCDS: Bool {
        guard let my = currentUser else { return false }
        let st = my.estado.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return st == "CDS" || st.contains("CDS") || st.contains("COMISION") || st.contains("COMISIÓN")
    }
    
    func stopAudio() {
        if audioPlayer?.isPlaying == true {
            audioPlayer?.stop()
        }
        audioPlayer = nil
    }
    
    private func setupVolumeObserver() {
        if volumeObservation != nil { return }
        volumeObservation = AVAudioSession.sharedInstance().observe(\.outputVolume) { [weak self] session, _ in
            guard let self = self else { return }
            if self.audioPlayer?.isPlaying == true {
                DispatchQueue.main.async {
                    self.stopAudio()
                }
            }
        }
    }
    
    func playSound(soundName: String) {
        if hasActiveCDS { return }
        setupVolumeObserver()
        // Look up resources in main bundle
        guard let url = Bundle.main.url(forResource: soundName, withExtension: "mp3") ??
                        Bundle.main.url(forResource: soundName, withExtension: "wav") ??
                        Bundle.main.url(forResource: "alerta", withExtension: "wav") else {
            return
        }
        
        do {
            // Configure Audio Session for playing sounds even if phone is on silent switch (ambient/playback)
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(true)
            
            audioPlayer = try AVAudioPlayer(contentsOf: url)
            audioPlayer?.volume = 1.0
            audioPlayer?.play()
        } catch {
            print("Sound playing error: \(error.localizedDescription)")
        }
    }
    
    func triggerVibration() {
        if hasActiveCDS { return }
        let generator = UIImpactFeedbackGenerator(style: .heavy)
        generator.prepare()
        generator.impactOccurred()
        
        // Fallback standard vibration
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    }
    
    private func updateCurrentUserData(fresh: UserPersonal) {
        guard let my = self.currentUser else { return }
        var needsUpdate = false
        var targetEstado = my.estado
        var targetEnServicio = my.enServicio
        
        let timeStatusElapsed = Date().timeIntervalSince(self.lastStatusChangeTime)
        if let pending = self.pendingStatus, timeStatusElapsed < 4.0 {
            if fresh.estado == pending {
                self.pendingStatus = nil
            }
        } else {
            if fresh.estado != my.estado {
                targetEstado = fresh.estado
                needsUpdate = true
            }
        }
        
        let timeServiceElapsed = Date().timeIntervalSince(self.lastServiceChangeTime)
        if let pendingSvc = self.pendingService, timeServiceElapsed < 4.0 {
            if fresh.enServicio == pendingSvc {
                self.pendingService = nil
            }
        } else {
            if fresh.enServicio != my.enServicio {
                targetEnServicio = fresh.enServicio
                needsUpdate = true
            }
        }
        
        var targetActivo = my.activo
        if fresh.activo != my.activo {
            targetActivo = fresh.activo
            needsUpdate = true
        }
        
        if needsUpdate {
            let updated = UserPersonal(
                idRegistro: my.idRegistro,
                nombreBombero: my.nombreBombero,
                idRadial: my.idRadial,
                contrasena: my.contrasena,
                activo: targetActivo,
                conductor: my.conductor,
                enServicio: targetEnServicio,
                cargo: my.cargo,
                foto: my.foto,
                estado: targetEstado
            )
            self.currentUser = updated
            self.saveCache(updated, key: "fire_user")
        }
    }
    
    // MARK: - Dynamic Firebase & SaaS License Methods
    
    func initializeDynamicFirebase(configStr: String) {
        AppDelegate.configureDynamicFirebase(configStr: configStr)
    }

    func activateLicense(key: String, onComplete: ((Bool) -> Void)? = nil) {
        let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !trimmedKey.isEmpty else { return }
        
        isActivatingLicense = true
        saasActivationError = ""
        
        let urlString = "https://validatelicense-3kkeukidtq-uc.a.run.app"
        guard let url = URL(string: urlString) else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("SisBom-iOS/1.1.7", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 10
        
        let body: [String: Any] = [
            "licenseKey": trimmedKey,
            "module": "apk"
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.isActivatingLicense = false
                
                if let error = error {
                    self.saasActivationError = "Error de conexión: \(error.localizedDescription)"
                    onComplete?(false)
                    return
                }
                
                guard let data = data,
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    self.saasActivationError = "Respuesta del servidor inválida."
                    onComplete?(false)
                    return
                }
                
                let authorized = json["authorized"] as? Bool ?? false
                if authorized, let firebaseConfig = json["firebaseConfig"] as? [String: Any],
                   let configData = try? JSONSerialization.data(withJSONObject: firebaseConfig),
                   let configStr = String(data: configData, encoding: .utf8),
                   let newProjectId = firebaseConfig["projectId"] as? String {
                    
                    let clientName = json["clientName"] as? String ?? json["nombreMostrar"] as? String ?? "SisBom"
                    let logoUrl = json["logoUrl"] as? String ?? ""
                    
                    // Save to UserDefaults
                    UserDefaults.standard.set(trimmedKey, forKey: "saas_license_key")
                    UserDefaults.standard.set(configStr, forKey: "saas_firebase_config")
                    UserDefaults.standard.set(clientName, forKey: "saas_client_name")
                    UserDefaults.standard.set(logoUrl, forKey: "saas_logo_url")
                    
                    self.saasLicenseKey = trimmedKey
                    self.saasClientName = clientName
                    self.saasLogoUrl = logoUrl
                    
                    if !logoUrl.isEmpty {
                        self.downloadClientLogo(logoUrl)
                    }
                    
                    // Update launcher app icon based on license/institution
                    self.updateAppIcon(for: trimmedKey, clientName: clientName)
                    
                    // Check if Firebase was already configured for a different project in this running session
                    if let currentApp = FirebaseApp.app(), currentApp.options.projectID != newProjectId {
                        self.saasActivationError = "Licencia activada con éxito para \(clientName). Para conectar con la nueva institución, presione Continuar para reiniciar la aplicación."
                        self.requiresAppRestartAfterLicenseChange = true
                        onComplete?(true)
                        return
                    }
                    
                    self.initializeDynamicFirebase(configStr: configStr)
                    self.currentScreen = .login
                    onComplete?(true)
                } else {
                    let reason = json["reason"] as? String ?? "Licencia no autorizada o vencida."
                    self.saasActivationError = reason
                    onComplete?(false)
                }
            }
        }.resume()
    }

    func updateAppIcon(for key: String, clientName: String) {
        DispatchQueue.main.async {
            guard UIApplication.shared.supportsAlternateIcons else { return }
            
            let upperKey = key.uppercased()
            let upperName = clientName.uppercased()
            
            var targetIconName: String? = nil
            if upperKey.contains("CBPL") || upperKey.contains("PLACILLA") || upperName.contains("PLACILLA") {
                targetIconName = "SB-CBPL-OH"
            } else if upperKey == "PRUEBA" {
                targetIconName = "PRUEBA"
            }
            
            if UIApplication.shared.alternateIconName != targetIconName {
                UIApplication.shared.setAlternateIconName(targetIconName) { error in
                    if let error = error {
                        print("Error setting alternate icon: \(error.localizedDescription)")
                    } else {
                        print("Switched app icon to: \(targetIconName ?? "primary")")
                    }
                }
            }
        }
    }

    func checkLicenseStatus() {
        guard let licenseKey = UserDefaults.standard.string(forKey: "saas_license_key"), !licenseKey.isEmpty else {
            self.currentScreen = .setup
            return
        }
        
        let urlString = "https://validatelicense-3kkeukidtq-uc.a.run.app"
        guard let url = URL(string: urlString) else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("SisBom-iOS/1.1.7", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 8
        
        let body: [String: Any] = [
            "licenseKey": licenseKey,
            "module": "apk"
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self = self else { return }
                if let data = data,
                   let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    let authorized = json["authorized"] as? Bool ?? false
                    if !authorized {
                        self.clearLicense()
                    }
                }
            }
        }.resume()
    }

    func clearLicense() {
        let fileManager = FileManager.default
        if let docsDir = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first {
            let logoPath = docsDir.appendingPathComponent("client_logo.png")
            try? fileManager.removeItem(at: logoPath)
        }
        
        UserDefaults.standard.removeObject(forKey: "saas_license_key")
        UserDefaults.standard.removeObject(forKey: "saas_firebase_config")
        UserDefaults.standard.removeObject(forKey: "saas_client_name")
        UserDefaults.standard.removeObject(forKey: "saas_logo_url")
        UserDefaults.standard.removeObject(forKey: "fire_user")
        
        self.currentUser = nil
        self.saasLicenseKey = ""
        self.saasClientName = ""
        self.saasLogoUrl = ""
        self.currentScreen = .setup
        
        // Reset app icon to default primary icon
        if UIApplication.shared.supportsAlternateIcons {
            UIApplication.shared.setAlternateIconName(nil)
        }
    }

    func downloadClientLogo(_ urlString: String) {
        guard let url = URL(string: urlString) else { return }
        URLSession.shared.dataTask(with: url) { data, response, error in
            guard let data = data, error == nil else { return }
            if let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
                let fileUrl = docsDir.appendingPathComponent("client_logo.png")
                try? data.write(to: fileUrl)
            }
        }.resume()
    }

    func getInstitutionLogo() -> UIImage {
        if let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            let fileUrl = docsDir.appendingPathComponent("client_logo.png")
            if let data = try? Data(contentsOf: fileUrl), let image = UIImage(data: data) {
                return image
            }
        }
        return UIImage(named: "logo") ?? UIImage(systemName: "shield.fill") ?? UIImage()
    }

    func closeCentralOperatorSession() {
        let formatter = DateFormatter()
        formatter.dateFormat = "dd-MM-yyyy HH:mm:ss"
        let nowStr = formatter.string(from: Date())
        
        repository.updateCentralSession(
            updates: [
                "estado": "inactivo",
                "fechaSalida": nowStr,
                "operadorActivo": "",
                "idRegistro": ""
            ],
            completion: { [weak self] result in
                guard let self = self else { return }
                DispatchQueue.main.async {
                    switch result {
                    case .success:
                        self.centralOperatorName = ""
                        self.centralOperatorId = ""
                        self.isCentralActive = false
                    case .failure(let error):
                        print("Error closing central session: \(error.localizedDescription)")
                    }
                }
            }
        )
    }

    func openDoor(onSuccess: @escaping () -> Void, onFailure: @escaping (Error) -> Void) {
        repository.setDoorOpen(onSuccess: onSuccess, onFailure: onFailure)
    }

    func setAirplaneModeEnabled(_ enabled: Bool) {
        if isCentralActive { return }
        isAirplaneMode = enabled
        UserDefaults.standard.set(enabled, forKey: "MODO_AVION")
        if enabled {
            changeStatus(newStatus: "0-8")
        } else {
            changeStatus(newStatus: "0-9")
        }
    }

    func setDarkModeEnabled(_ enabled: Bool) {
        isDarkMode = enabled
        UserDefaults.standard.set(enabled, forKey: "app_dark_mode")
        updateInterfaceStyle()
    }

    func refreshAttendance() {
        guard let my = currentUser, !my.idRegistro.isEmpty else { return }
        isSyncingAttendance = true
        _ = repository.getAttendance(userId: my.idRegistro) { [weak self] list in
            guard let self = self else { return }
            self.attendanceList = list
            self.saveCache(list, key: "cache_attendance")
            self.isSyncingAttendance = false
        }
    }
}
