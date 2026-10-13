import SwiftUI
import FirebaseCore
import FirebaseFirestore
import FirebaseMessaging
import UserNotifications
import CoreLocation
import AVFoundation
import AudioToolbox

// MARK: - Location Service
class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationService()
    private let manager = CLLocationManager()
    @Published var lastLocation: CLLocation?

    private(set) var isTracking: Bool = false
    private var activeServiceId: String? = nil
    private var activeUserId: String? = nil
    private var activeRadial: String? = nil
    private var activeNombre: String? = nil

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.pausesLocationUpdatesAutomatically = false
    }

    func requestPermissions() {
        manager.requestWhenInUseAuthorization()
        manager.requestAlwaysAuthorization()
    }

    func startTracking(serviceId: String, userId: String, radial: String, nombre: String) {
        let clean = serviceId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, clean != "0", !clean.contains("-"), clean.rangeOfCharacter(from: .decimalDigits) != nil else {
            stopTracking()
            return
        }

        if isTracking && activeServiceId == clean { return }

        self.activeServiceId = clean
        self.activeUserId = userId
        self.activeRadial = radial
        self.activeNombre = nombre
        self.isTracking = true

        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.distanceFilter = 5.0
        manager.startUpdatingLocation()
        print("🛰️ GPS ACTIVATED for service \(clean) (User: \(userId))")
    }

    func stopTracking() {
        guard isTracking else { return }
        isTracking = false
        activeServiceId = nil
        activeUserId = nil
        activeRadial = nil
        activeNombre = nil

        manager.stopUpdatingLocation()
        manager.showsBackgroundLocationIndicator = false
        print("🛑 GPS STOPPED (Not attending service)")
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        lastLocation = location

        guard isTracking, let serviceId = activeServiceId, let userId = activeUserId, !userId.isEmpty else {
            return
        }

        let now = Date().timeIntervalSince1970 * 1000
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        let horaStr = formatter.string(from: Date())

        let locData: [String: Any] = [
            "idRegistro": userId,
            "idRadial": activeRadial ?? "",
            "nombre": activeNombre ?? "Bombero",
            "asistira": true,
            "enServicio": serviceId,
            "lat": location.coordinate.latitude,
            "lng": location.coordinate.longitude,
            "accuracy": location.horizontalAccuracy,
            "speedKmH": max(0, location.speed * 3.6),
            "hora": horaStr,
            "timestamp": Int64(now)
        ]

        let db = Firestore.firestore()
        db.collection("despachos").document(serviceId).collection("asistencias").document(userId).setData(locData, merge: true)
    }
}

// MARK: - AppDelegate for Firebase Core & Permissions Initialization
class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {
    static var launchChatId: String? = nil
    static var launchDispatchId: String? = nil

    static func configureDynamicFirebase(configStr: String) {
        guard let data = configStr.data(using: .utf8),
              let config = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let apiKey = config["apiKey"] as? String, !apiKey.isEmpty,
              let projectId = config["projectId"] as? String, !projectId.isEmpty,
              let rawAppId = config["appId"] as? String, !rawAppId.isEmpty,
              let messagingSenderId = config["messagingSenderId"] as? String, !messagingSenderId.isEmpty else {
            print("Error: Invalid Firebase configuration payload")
            return
        }

        // Format appId for iOS SDK (SaaS returns Web App IDs containing ':web:', Firebase iOS requires ':ios:')
        let formattedAppId = rawAppId.replacingOccurrences(of: ":web:", with: ":ios:")
        let storageBucket = (config["storageBucket"] as? String) ?? "\(projectId).appspot.com"
        let bundleId = Bundle.main.bundleIdentifier ?? "com.misisbom.sisbom"

        let options = FirebaseOptions(googleAppID: formattedAppId, gcmSenderID: messagingSenderId)
        options.apiKey = apiKey
        options.projectID = projectId
        options.storageBucket = storageBucket
        options.bundleID = bundleId

        if let currentApp = FirebaseApp.app() {
            if currentApp.options.projectID == projectId {
                print("Firebase already configured for project: \(projectId)")
                return
            }
            print("Different Firebase project detected (\(currentApp.options.projectID ?? "") -> \(projectId)). Restart required.")
        } else {
            FirebaseApp.configure(options: options)
            print("Dynamic Firebase successfully configured for project: \(projectId)")
        }
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey : Any]? = nil
    ) -> Bool {
        // Attempt dynamic Firebase configuration if cached config exists
        if let cachedConfigStr = UserDefaults.standard.string(forKey: "saas_firebase_config"), !cachedConfigStr.isEmpty {
            AppDelegate.configureDynamicFirebase(configStr: cachedConfigStr)
        }
        
        // Setup User Notifications with Interactive Categories
        UNUserNotificationCenter.current().delegate = self
        
        let attendAction = UNNotificationAction(
            identifier: "ATTEND_ACTION",
            title: "ASISTIR",
            options: [.foreground]
        )
        let declineAction = UNNotificationAction(
            identifier: "DECLINE_ACTION",
            title: "NO ASISTIR",
            options: [.destructive]
        )
        let dispatchCategory = UNNotificationCategory(
            identifier: "EMERGENCY_DISPATCH",
            actions: [attendAction, declineAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        
        let generalAlertCategory = UNNotificationCategory(
            identifier: "GENERAL_ALERT",
            actions: [],
            intentIdentifiers: [],
            options: []
        )
        
        let chatCategory = UNNotificationCategory(
            identifier: "CHAT_ALERT",
            actions: [],
            intentIdentifiers: [],
            options: []
        )
        
        let openDoorAction = UNNotificationAction(
            identifier: "OPEN_DOOR_ACTION",
            title: "ABRIR PUERTA",
            options: [.foreground]
        )
        let doorRequestCategory = UNNotificationCategory(
            identifier: "DOOR_REQUEST_ALERT",
            actions: [openDoorAction],
            intentIdentifiers: [],
            options: []
        )
        
        UNUserNotificationCenter.current().setNotificationCategories([dispatchCategory, generalAlertCategory, chatCategory, doorRequestCategory])
        
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            if granted {
                DispatchQueue.main.async {
                    application.registerForRemoteNotifications()
                }
            }
        }
        
        // Setup Location
        LocationService.shared.requestPermissions()
        
        // Setup Audio Session for Siren / Tone playback
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.duckOthers])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("Audio session configuration error: \(error)")
        }
        
        // Setup Messaging Delegate
        Messaging.messaging().delegate = self
        
        // Check if launched from a notification response in launchOptions
        if let notificationPayload = launchOptions?[.remoteNotification] as? [AnyHashable: Any] {
            if let type = notificationPayload["type"] as? String {
                if type == "CHAT", let payloadId = notificationPayload["payloadId"] as? String {
                    AppDelegate.launchChatId = payloadId
                } else if type == "DISPATCH", let payloadId = notificationPayload["payloadId"] as? String {
                    AppDelegate.launchDispatchId = payloadId
                }
            }
        }
        
        return true
    }
    
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Messaging.messaging().apnsToken = deviceToken
    }
    
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        print("Error registering for remote notifications: \(error.localizedDescription)")
    }
    
    // Background Remote Notification (Wake-up on silent/data/content-available pushes)
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        Messaging.messaging().appDidReceiveMessage(userInfo)
        
        let type = (userInfo["type"] as? String) ?? ((userInfo["aps"] as? [String: Any])?["type"] as? String) ?? ""
        let payloadId = (userInfo["payloadId"] as? String) ?? (userInfo["doorKey"] as? String) ?? ""
        let clave = (userInfo["clave"] as? String) ?? (userInfo["key"] as? String) ?? ""
        
        if (type == "DISPATCH" || type == "DISPATCH_UPDATE") && !payloadId.isEmpty {
            AppDelegate.launchDispatchId = payloadId
            NotificationCenter.default.post(
                name: NSNotification.Name("OpenFullscreenDispatch"),
                object: nil,
                userInfo: ["dispatchId": payloadId, "clave": clave]
            )
            
            // Play alarm sound and trigger emergency vibration
            do {
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.duckOthers])
                try AVAudioSession.sharedInstance().setActive(true)
                let soundName = clave.isEmpty ? "alerta" : clave
                if let url = Bundle.main.url(forResource: soundName, withExtension: "mp3") ??
                             Bundle.main.url(forResource: soundName, withExtension: "wav") ??
                             Bundle.main.url(forResource: "alerta", withExtension: "wav") {
                    let player = try AVAudioPlayer(contentsOf: url)
                    player.volume = 1.0
                    player.play()
                }
            } catch {
                print("Error playing background dispatch sound: \(error)")
            }
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        }
        
        completionHandler(.newData)
    }
    
    // Foreground Notification Presentation
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        if #available(iOS 14.0, *) {
            completionHandler([.banner, .sound, .badge, .list])
        } else {
            completionHandler([.alert, .sound, .badge])
        }
    }
    
    // Handle notification tap & action buttons when app is in background/foreground/closed
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        let actionIdentifier = response.actionIdentifier
        
        if let type = userInfo["type"] as? String {
            if type == "DOOR_REQUEST" {
                if actionIdentifier == "OPEN_DOOR_ACTION" {
                    FirebaseRepository.shared.setDoorOpen {
                        print("Puerta abierta exitosamente desde notificación iOS")
                    } onFailure: { err in
                        print("Error abriendo puerta desde notificación iOS: \(err)")
                    }
                }
            } else if type == "CHAT", let payloadId = userInfo["payloadId"] as? String {
                AppDelegate.launchChatId = payloadId
                
                NotificationCenter.default.post(
                    name: NSNotification.Name("OpenChatRoom"),
                    object: nil,
                    userInfo: ["chatId": payloadId]
                )
            } else if (type == "DISPATCH" || type == "DISPATCH_UPDATE"), let payloadId = userInfo["payloadId"] as? String {
                if actionIdentifier == "ATTEND_ACTION" {
                    NotificationCenter.default.post(
                        name: NSNotification.Name("AttendDispatch"),
                        object: nil,
                        userInfo: ["dispatchId": payloadId, "attend": true]
                    )
                } else if actionIdentifier == "DECLINE_ACTION" {
                    NotificationCenter.default.post(
                        name: NSNotification.Name("AttendDispatch"),
                        object: nil,
                        userInfo: ["dispatchId": payloadId, "attend": false]
                    )
                } else {
                    // Notification tapped directly -> Bring up the emergency red overlay
                    AppDelegate.launchDispatchId = payloadId
                    NotificationCenter.default.post(
                        name: NSNotification.Name("OpenFullscreenDispatch"),
                        object: nil,
                        userInfo: ["dispatchId": payloadId]
                    )
                }
            }
        }
        
        completionHandler()
    }
    
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        print("Firebase registration token: \(String(describing: fcmToken))")
        guard let token = fcmToken, !token.isEmpty else { return }
        UserDefaults.standard.set(token, forKey: "fcm_token")
        
        Messaging.messaging().subscribe(toTopic: "all")
        Messaging.messaging().subscribe(toTopic: "despachos")
        Messaging.messaging().subscribe(toTopic: "alertas_generales")
        
        if let cachedUser = UserDefaults.standard.string(forKey: "fire_user"),
           let data = cachedUser.data(using: .utf8),
           let userDict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let idRegistro = userDict["idRegistro"] as? String, !idRegistro.isEmpty {
            
            let db = Firestore.firestore()
            db.collection("personal").document(idRegistro).setData([
                "tokenFcm": token,
                "tokenIos": token,
                "platform": "ios",
                "lastTokenUpdate": FieldValue.serverTimestamp()
            ], merge: true)
            
            db.collection("fcm_tokens").document(token).setData([
                "token": token,
                "platform": "ios",
                "userId": idRegistro,
                "topics": ["all", "despachos", "alertas_generales", "usuario_\(idRegistro)", "personal_\(idRegistro)"],
                "updatedAt": FieldValue.serverTimestamp()
            ], merge: true)
        }
    }
}

@main
struct SisBomApp: App {
    // Register app delegate for Firebase setup
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    
    // Instantiate our shared ViewModel
    @StateObject private var viewModel = SisBomViewModel()

    var body: some Scene {
        WindowGroup {
            Group {
                switch viewModel.currentScreen {
                case .setup:
                    SetupView(viewModel: viewModel)
                case .login:
                    LoginView(viewModel: viewModel)
                case .main, .chat:
                    MainView(viewModel: viewModel)
                }
            }
            .animation(.default, value: viewModel.currentScreen)
        }
    }
}

