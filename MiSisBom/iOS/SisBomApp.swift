import SwiftUI
import FirebaseCore
import UserNotifications

// MARK: - AppDelegate for Firebase Core Initialization
class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    static var launchChatId: String? = nil

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey : Any]? = nil
    ) -> Bool {
        // Attempt dynamic Firebase configuration if cached config exists
        if let cachedConfigStr = UserDefaults.standard.string(forKey: "saas_firebase_config"),
           let data = cachedConfigStr.data(using: .utf8),
           let config = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let apiKey = config["apiKey"] as? String,
           let projectId = config["projectId"] as? String,
           let appId = config["appId"] as? String,
           let messagingSenderId = config["messagingSenderId"] as? String,
           let storageBucket = config["storageBucket"] as? String {
            
            let options = FirebaseOptions(googleAppID: appId, gcmSenderID: messagingSenderId)
            options.apiKey = apiKey
            options.projectID = projectId
            options.storageBucket = storageBucket
            
            if FirebaseApp.app() == nil {
                FirebaseApp.configure(options: options)
            }
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
