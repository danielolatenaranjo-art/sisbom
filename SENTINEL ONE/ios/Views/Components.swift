import SwiftUI

// MARK: - Color Palette Extensions
extension Color {
    static let bomberosRed = Color(red: 0.725, green: 0.110, blue: 0.110)      // #B91C1C
    static let bomberosRedLight = Color(red: 0.937, green: 0.267, blue: 0.267) // #EF4444
    static let alertAmber = Color(red: 0.961, green: 0.620, blue: 0.043)       // #F59E0B
    static let alertAmberLight = Color(red: 0.984, green: 0.749, blue: 0.141)  // #FBBF24
    static let goGreen = Color(red: 0.063, green: 0.725, blue: 0.506)          // #10B981
    static let goGreenLight = Color(red: 0.204, green: 0.827, blue: 0.600)     // #34D399
    static let infoBlue = Color(red: 0.231, green: 0.510, blue: 0.965)         // #3B82F6
    static let navyDark = Color(red: 0.059, green: 0.090, blue: 0.165)         // #0F172A
    static let navyDeep = Color(red: 0.008, green: 0.024, blue: 0.090)         // #020617
    
    static let lightBg = Color(red: 0.973, green: 0.980, blue: 0.988)          // #F8FAFC
    static let darkBg = Color(red: 0.020, green: 0.020, blue: 0.031)           // #050508
    
    static let textDark = Color(red: 0.118, green: 0.161, blue: 0.231)         // #1E293B
    static let textSecondary = Color(red: 0.392, green: 0.455, blue: 0.545)    // #64748B
    static let textSecondaryDark = Color(red: 0.580, green: 0.639, blue: 0.722) // #94A3B8
    
    static let lightCardSurface = Color.white.opacity(0.45)
    static let darkCardSurface = Color.navyDark.opacity(0.45)
    
    static let lightCardBorder = Color.white.opacity(0.4)
    static let darkCardBorder = Color.white.opacity(0.05)
}

// MARK: - SisBomBackground View
struct SisBomBackground<Content: View>: View {
    @ObservedObject var viewModel: SisBomViewModel
    let content: Content

    init(viewModel: SisBomViewModel, @ViewBuilder content: () -> Content) {
        self.viewModel = viewModel
        self.content = content()
    }

    var body: some View {
        let isDark = viewModel.isDarkMode
        let redGlowAlpha = isDark ? 0.08 : 0.12
        let amberGlowAlpha = isDark ? 0.05 : 0.10
        
        ZStack {
            // Main Background Gradient / Color
            if isDark {
                LinearGradient(
                    gradient: Gradient(colors: [
                        Color(red: 0.059, green: 0.004, blue: 0.004), // #0F0101
                        Color(red: 0.157, green: 0.008, blue: 0.008), // #280202
                        Color(red: 0.290, green: 0.012, blue: 0.012)  // #4A0303
                    ]),
                    startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea()
            } else {
                Color.lightBg
                    .ignoresSafeArea()
            }
            
            // Firefighter Illustration Background Image
            if let bgImage = UIImage(named: isDark ? "escudo_bg_dark" : "escudo_bg_light") {
                Image(uiImage: bgImage)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .opacity(isDark ? 0.50 : 0.15)
                    .ignoresSafeArea()
            }
            
            // Radial Glows (Glows in corners)
            GeometryReader { geo in
                ZStack {
                    // Top Right Red Glow
                    Circle()
                        .fill(RadialGradient(
                            colors: [Color.bomberosRed.opacity(redGlowAlpha), .transparent],
                            center: .center,
                            startRadius: 0,
                            endRadius: geo.size.width * 0.8
                        ))
                        .frame(width: geo.size.width * 1.6, height: geo.size.width * 1.6)
                        .position(x: geo.size.width, y: 0)
                    
                    // Bottom Left Amber Glow
                    Circle()
                        .fill(RadialGradient(
                            colors: [Color.alertAmber.opacity(amberGlowAlpha), .transparent],
                            center: .center,
                            startRadius: 0,
                            endRadius: geo.size.width * 0.8
                        ))
                        .frame(width: geo.size.width * 1.6, height: geo.size.width * 1.6)
                        .position(x: 0, y: geo.size.height)
                }
            }
            .ignoresSafeArea()
            
            // Watermark Shield Logo of Tenant at Bottom-Right
            GeometryReader { geo in
                Image(uiImage: viewModel.getInstitutionLogo())
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 240, height: 240)
                    .opacity(isDark ? 0.18 : 0.22)
                    .position(x: geo.size.width - 60, y: geo.size.height - 60)
            }
            .ignoresSafeArea()
            
            // App Screen Content
            content
        }
    }
}

// Custom transparent color extension for gradients
extension Color {
    static let transparent = Color.white.opacity(0.0)
}

// MARK: - Glassmorphic Card View
struct GlassCard<Content: View>: View {
    @ObservedObject var viewModel: SisBomViewModel
    let content: Content

    init(viewModel: SisBomViewModel, @ViewBuilder content: () -> Content) {
        self.viewModel = viewModel
        self.content = content()
    }

    var body: some View {
        let isDark = viewModel.isDarkMode
        
        VStack(spacing: 0) {
            content
        }
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(isDark ? Color(red: 0.059, green: 0.090, blue: 0.165).opacity(0.92) : Color.white.opacity(0.95))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(isDark ? Color.white.opacity(0.12) : Color(red: 0.886, green: 0.910, blue: 0.941), lineWidth: 1)
        )
        .shadow(color: isDark ? Color.black.opacity(0.4) : Color.black.opacity(0.05), radius: 8, x: 0, y: 4)
    }
}

// MARK: - Sync Status Indicator Dot
struct SyncIndicatorDot: View {
    let isSyncing: Bool
    @State private var scale: CGFloat = 1.0

    var body: some View {
        Circle()
            .fill(isSyncing ? Color.alertAmber : Color.goGreen)
            .frame(width: 8, height: 8)
            .scaleEffect(scale)
            .onAppear {
                if isSyncing {
                    withAnimation(Animation.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) {
                        scale = 1.3
                    }
                }
            }
            .onChange(of: isSyncing, perform: { newValue in
                if newValue {
                    withAnimation(Animation.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) {
                        scale = 1.3
                    }
                } else {
                    withAnimation(.default) {
                        scale = 1.0
                    }
                }
            })
    }
}

// MARK: - Chat Bubble View
struct ChatBubble: View {
    let senderName: String
    let message: String
    let time: String
    let isMe: Bool
    let isDarkTheme: Bool

    var body: some View {
        VStack(alignment: isMe ? .trailing : .leading, spacing: 3) {
            // Sender Name (only if not me)
            if !isMe {
                Text(senderName.uppercased())
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(.textSecondary)
                    .padding(.leading, 8)
            }
            
            // Bubble Content
            VStack(alignment: .trailing, spacing: 4) {
                Text(message)
                    .font(.system(size: 14))
                    .foregroundColor(isMe ? .white : (isDarkTheme ? .white : .textDark))
                
                Text(time)
                    .font(.system(size: 9))
                    .foregroundColor(isMe ? Color.white.opacity(0.7) : .textSecondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 14)
                    .fill(isMe ? Color.bomberosRed : (isDarkTheme ? Color.navyDark : Color.white))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(isMe ? .clear : (isDarkTheme ? Color.white.opacity(0.05) : Color(red: 0.91, green: 0.91, blue: 0.91)), lineWidth: 1)
            )
        }
        .frame(maxWidth: .infinity, alignment: isMe ? .trailing : .leading)
    }
}
