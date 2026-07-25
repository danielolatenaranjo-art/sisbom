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
        // Initialize Firebase SDK when the app launches
        FirebaseApp.configure()
        
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
