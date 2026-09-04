import SwiftUI
import FirebaseCore
import UserNotifications

// MARK: - AppDelegate for Firebase Core Initialization
class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    static var launchChatId: String? = nil

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
        
        UNUserNotificationCenter.current().delegate = self
        
        // Check if launched from a notification response in launchOptions
        if let notificationPayload = launchOptions?[.remoteNotification] as? [AnyHashable: Any] {
            if let type = notificationPayload["type"] as? String, type == "CHAT",
               let payloadId = notificationPayload["payloadId"] as? String {
                AppDelegate.launchChatId = payloadId
            }
        }
        
        return true
    }
    
    // Handle notification tap when app is in background/closed
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        
        if let type = userInfo["type"] as? String, type == "CHAT",
           let payloadId = userInfo["payloadId"] as? String {
            AppDelegate.launchChatId = payloadId
            
            NotificationCenter.default.post(
                name: NSNotification.Name("OpenChatRoom"),
                object: nil,
                userInfo: ["chatId": payloadId]
            )
        }
        
        completionHandler()
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
