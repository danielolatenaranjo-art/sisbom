import SwiftUI

func formatFirefighterName(_ fullName: String) -> String {
    let words = fullName.trimmingCharacters(in: .whitespacesAndNewlines).components(separatedBy: .whitespaces).filter { !$0.isEmpty }
    if words.count <= 2 { return fullName }
    let first = words.first ?? ""
    let penultimate = words[words.count - 2]
    return "\(first) \(penultimate)"
}

struct MainView: View {
    @ObservedObject var viewModel: SisBomViewModel
    @State private var isDrawerOpen: Bool = false
    @State private var showingChangePassword: Bool = false
    @State private var newPassword: String = ""

    var visibleTabs: [MainTab] {
        getVisibleTabs(isCentralActive: viewModel.isCentralActive, user: viewModel.currentUser)
    }

    var showFullscreenAlert: Dispatch? {
        guard let user = viewModel.currentUser, !viewModel.isCentralActive else { return nil }
        let userStatus = user.estado.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let is09 = userStatus == "0-9"
        let is08 = userStatus == "0-8" || userStatus == "10-8"
        guard let fId = viewModel.fullscreenDispatchId, (is09 || is08) else { return nil }
        guard let d = viewModel.dispatchesList.first(where: { $0.idServicio == fId && $0.operadorFinal.isEmpty }) else { return nil }
        let isAttending = user.enServicio.trimmingCharacters(in: .whitespacesAndNewlines) == fId
        let isEscalationAlarm = d.clave.contains("10-30") || d.clave.uppercased().contains("FORESTAL")
        if !isAttending && (isEscalationAlarm || (is09 && userStatus != "NO ASISTIR")) {
            return d
        }
        return nil
    }

    var body: some View {
        let isDark = viewModel.isDarkMode
        
        ZStack {
            // Main App Background with Firefighter Illustration & Glows
            SisBomBackground(viewModel: viewModel) {
                ZStack(alignment: .top) {
                    // Tab Content Wrapper (with padding for top card and bottom floating dock)
                    ZStack {
                        switch viewModel.currentTab {
                        case .actividad:
                            ActividadTab(viewModel: viewModel)
                        case .despacho:
                            DespachoTab(viewModel: viewModel)
                        case .ordenes:
                            OrdenesTab(viewModel: viewModel)
                        case .alertas:
                            AlertasTab(viewModel: viewModel)
                        case .asistencia:
                            AsistenciaTab(viewModel: viewModel)
                        case .disponibles:
                            DisponiblesTab(viewModel: viewModel)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.top, 175) // Space for TopAppBarView
                    .padding(.bottom, 80) // Space for BottomNavigationBarView

                    // Floating Top Header & Firefighter Card (Exact Android Layout)
                    VStack(spacing: 0) {
                        TopAppBarView(viewModel: viewModel, onMenuClick: {
                            withAnimation(.spring()) {
                                isDrawerOpen = true
                            }
                        })
                    }
                    .padding(.top, 44) // Status bar safe area

                    // Floating Bottom Navigation Dock (Exact Android Layout)
                    VStack {
                        Spacer()
                        BottomNavigationBarView(viewModel: viewModel, visibleTabs: visibleTabs)
                    }
                }
            }
            .blur(radius: isDrawerOpen || showingChangePassword ? 4 : 0)
            
            // Side Drawer Overlay Dimmer Background
            if isDrawerOpen {
                Color.black.opacity(0.4)
                    .ignoresSafeArea()
                    .onTapGesture {
                        withAnimation(.spring()) {
                            isDrawerOpen = false
                        }
                    }
            }
            
            // Side Navigation Drawer
            GeometryReader { geo in
                HStack(spacing: 0) {
                    ProfileDrawerContent(viewModel: viewModel, isDrawerOpen: $isDrawerOpen, showingChangePassword: $showingChangePassword)
                        .frame(width: geo.size.width * 0.78)
                    Spacer()
                }
                .offset(x: isDrawerOpen ? 0 : -geo.size.width)
            }
            .ignoresSafeArea()
            
            // Change Password Popup Dialog
            if showingChangePassword {
                ChangePasswordDialog(viewModel: viewModel, newPassword: $newPassword, isPresented: $showingChangePassword)
            }
            
            // Changelog Popup Dialog
            if viewModel.showChangelogDialog {
                ChangelogDialog(viewModel: viewModel)
            }

            // Emergency Fullscreen Overlay Alert
            if let dispatch = showFullscreenAlert {
                FullscreenEmergencyAlertView(dispatch: dispatch, viewModel: viewModel)
                    .transition(.opacity)
                    .zIndex(100)
            }
        }
        .onAppear {
            ensureCurrentTabValid()
        }
        .onChange(of: viewModel.isCentralActive) { _ in
            ensureCurrentTabValid()
        }
        .fullScreenCover(item: Binding<AlertaItem?>(
            get: { viewModel.activeChatAlert },
            set: { viewModel.activeChatAlert = $0 }
        )) { alert in
            ChatView(alert: alert, viewModel: viewModel)
        }
    }
    
    private func ensureCurrentTabValid() {
        if !visibleTabs.contains(viewModel.currentTab), let first = visibleTabs.first {
            viewModel.currentTab = first
        }
    }

    private func getVisibleTabs(isCentralActive: Bool, user: UserPersonal?) -> [MainTab] {
        var list: [MainTab] = []
        let cargo = user?.cargo.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
        let isHonorario = cargo == "BOMBERO HONORARIO"
        let idRadial = user?.idRadial.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        if !isHonorario {
            if isCentralActive {
                list.append(.despacho)
            } else {
                list.append(.actividad)
            }
        }
        
        list.append(.ordenes)
        list.append(.alertas)
        
        if !isHonorario {
            list.append(.asistencia)
            if isCentralActive || idRadial == "1" {
                list.append(.disponibles)
            }
        }
        return list
    }
}

// MARK: - TopAppBarView (Exact Android SENTINEL ONE Top Card)
struct TopAppBarView: View {
    @ObservedObject var viewModel: SisBomViewModel
    let onMenuClick: () -> Void

    var body: some View {
        guard let user = viewModel.currentUser else { return AnyView(EmptyView()) }
        let isDark = viewModel.isDarkMode
        let rawEstado = user.estado.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let inService = !(user.enServicio.trimmingCharacters(in: .whitespacesAndNewlines) == "0") && !user.enServicio.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !user.enServicio.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("-")
        
        let is09Active = rawEstado == "0-9"
        let is08Active = rawEstado == "0-8" || rawEstado.contains("SUSPENDIDO") || rawEstado == "CDS" || rawEstado.contains("LICENCIA") || rawEstado == "PERMISO"
        
        let is09Enabled = !inService && !rawEstado.contains("SUSPENDIDO")
        let is08Enabled = !inService

        // Card glass background color based on status
        let glassBgColor: Color = {
            if rawEstado == "0-9" {
                return isDark ? Color(red: 0.059, green: 0.239, blue: 0.110) : Color(red: 0.784, green: 0.902, blue: 0.788) // #C8E6C9
            } else if rawEstado == "0-8" {
                return isDark ? Color(red: 0.361, green: 0.078, blue: 0.078) : Color(red: 1.0, green: 0.804, blue: 0.824) // #FFCDD2
            } else if rawEstado.contains("PERMISO") {
                return isDark ? Color(red: 0.361, green: 0.200, blue: 0.031) : Color(red: 1.0, green: 0.878, blue: 0.698) // #FFE0B2
            } else if rawEstado.contains("LICENCIA") {
                return isDark ? Color(red: 0.035, green: 0.243, blue: 0.361) : Color(red: 0.702, green: 0.898, blue: 0.988) // #B3E5FC
            } else if rawEstado == "CDS" {
                return isDark ? Color(red: 0.361, green: 0.310, blue: 0.031) : Color(red: 1.0, green: 0.976, blue: 0.769) // #FFF9C4
            } else if rawEstado.contains("SUSPENDIDO") {
                return isDark ? Color(red: 0.231, green: 0.047, blue: 0.329) : Color(red: 0.882, green: 0.745, blue: 0.906) // #E1BEE7
            } else {
                return isDark ? Color(red: 0.118, green: 0.161, blue: 0.231) : Color(red: 0.945, green: 0.961, blue: 0.976) // #F1F5F9
            }
        }()

        let cardBorderColor: Color = {
            if rawEstado == "0-9" {
                return isDark ? Color(red: 0.0, green: 0.690, blue: 0.314).opacity(0.5) : Color(red: 0.506, green: 0.780, blue: 0.518)
            } else if rawEstado == "0-8" {
                return isDark ? Color(red: 1.0, green: 0.231, blue: 0.188).opacity(0.5) : Color(red: 0.898, green: 0.451, blue: 0.451)
            } else if rawEstado.contains("PERMISO") {
                return isDark ? Color(red: 1.0, green: 0.584, blue: 0.0).opacity(0.5) : Color(red: 1.0, green: 0.718, blue: 0.302)
            } else if rawEstado.contains("LICENCIA") {
                return isDark ? Color(red: 0.204, green: 0.659, blue: 0.875).opacity(0.5) : Color(red: 0.310, green: 0.765, blue: 0.969)
            } else if rawEstado == "CDS" {
                return isDark ? Color(red: 0.820, green: 0.631, blue: 0.0).opacity(0.5) : Color(red: 1.0, green: 0.945, blue: 0.463)
            } else if rawEstado.contains("SUSPENDIDO") {
                return isDark ? Color(red: 0.557, green: 0.141, blue: 0.667).opacity(0.5) : Color(red: 0.729, green: 0.408, blue: 0.784)
            } else {
                return isDark ? Color.white.opacity(0.15) : Color.black.opacity(0.1)
            }
        }()

        let cardTextColor = isDark ? Color.white : Color(red: 0.059, green: 0.090, blue: 0.165)

        return AnyView(
            VStack(spacing: 6) {
                // SENTINEL ONE Adaptive Logo
                if let logoImg = UIImage(named: isDark ? "sentinel_one_logo" : "sentinel_one_logo_light") {
                    Image(uiImage: logoImg)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(height: 24)
                        .padding(.top, 2)
                        .padding(.bottom, 2)
                }

                // Firefighter Card
                VStack(spacing: 8) {
                    // Cuerpo de Bomberos Title (Centered at top)
                    Text(viewModel.saasClientName.isEmpty ? "CUERPO DE BOMBEROS" : viewModel.saasClientName.uppercased())
                        .font(.system(size: 12, weight: .black))
                        .foregroundColor(isDark ? Color.white.opacity(0.9) : Color(red: 0.118, green: 0.161, blue: 0.231))
                        .tracking(0.5)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 2)

                    // Avatar, Name, Radial/Cargo and 09/08 Buttons
                    HStack(spacing: 12) {
                        // Square Rounded Avatar (Clickable to open drawer)
                        Button(action: {
                            onMenuClick()
                        }) {
                            ZStack {
                                if !user.foto.isEmpty, let url = URL(string: user.foto) {
                                    AsyncImage(url: url) { image in
                                        image.resizable()
                                            .aspectRatio(contentMode: .fill)
                                    } placeholder: {
                                        Image(uiImage: viewModel.getInstitutionLogo())
                                            .resizable()
                                            .aspectRatio(contentMode: .fit)
                                    }
                                    .frame(width: 50, height: 50)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                                } else {
                                    Image(uiImage: viewModel.getInstitutionLogo())
                                        .resizable()
                                        .aspectRatio(contentMode: .fit)
                                        .frame(width: 50, height: 50)
                                        .clipShape(RoundedRectangle(cornerRadius: 8))
                                }
                            }
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(Color.white.opacity(0.3), lineWidth: 1)
                            )
                        }

                        // Firefighter Name & Cargo
                        Button(action: {
                            onMenuClick()
                        }) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(formatFirefighterName(user.nombreBombero).uppercased())
                                    .font(.system(size: 15, weight: .bold))
                                    .foregroundColor(cardTextColor)
                                    .lineLimit(1)
                                
                                Text("\(user.idRadial.isEmpty ? user.idRegistro : user.idRadial) — \(user.cargo.uppercased())")
                                    .font(.system(size: 12, weight: .medium))
                                    .foregroundColor(cardTextColor.opacity(0.7))
                                    .lineLimit(1)
                            }
                        }

                        Spacer()

                        // 09 / 08 Status Buttons
                        if viewModel.isCentralActive {
                            Text("OPERADOR")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(.white)
                                .frame(width: 110, height: 42)
                                .background(Color.goGreen)
                                .cornerRadius(8)
                        } else {
                            HStack(spacing: 8) {
                                // 09 Button (Green)
                                Button(action: {
                                    viewModel.changeStatus(newStatus: "0-9")
                                }) {
                                    Text("09")
                                        .font(.system(size: 15, weight: .black))
                                        .foregroundColor(is09Active ? .white : (isDark ? Color.white.opacity(0.7) : Color.goGreen))
                                        .frame(width: 52, height: 42)
                                        .background(is09Active ? Color.goGreen : Color.goGreen.opacity(0.15))
                                        .cornerRadius(8)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 8)
                                                .stroke(Color.goGreen.opacity(is09Active ? 1.0 : 0.5), lineWidth: 1)
                                        )
                                }
                                .disabled(!is09Enabled)

                                // 08 Button (Red)
                                Button(action: {
                                    viewModel.changeStatus(newStatus: "0-8")
                                }) {
                                    Text("08")
                                        .font(.system(size: 15, weight: .black))
                                        .foregroundColor(is08Active ? .white : (isDark ? Color.white.opacity(0.7) : Color.bomberosRed))
                                        .frame(width: 52, height: 42)
                                        .background(is08Active ? Color(red: 1.0, green: 0.231, blue: 0.188) : Color.bomberosRed.opacity(0.15))
                                        .cornerRadius(8)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 8)
                                                .stroke(Color.bomberosRed.opacity(is08Active ? 1.0 : 0.5), lineWidth: 1)
                                        )
                                }
                                .disabled(!is08Enabled)
                            }
                        }
                    }

                    // Divider
                    Divider()
                        .background(cardTextColor.opacity(0.15))
                        .padding(.vertical, 2)

                    // Dynamic Section Subtitle
                    HStack(spacing: 4) {
                        let (prefix, suffix) = sectionTitleParts(for: viewModel.currentTab)
                        Text(prefix)
                            .font(.system(size: 13, weight: .regular))
                            .foregroundColor(cardTextColor.opacity(0.6))
                        Text(suffix)
                            .font(.system(size: 13, weight: .black))
                            .foregroundColor(cardTextColor)
                        Spacer()
                    }
                    .padding(.leading, 4)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: 24)
                        .fill(glassBgColor)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 24)
                        .stroke(cardBorderColor, lineWidth: 1)
                )
            }
            .padding(.horizontal, 16)
        )
    }

    private func sectionTitleParts(for tab: MainTab) -> (String, String) {
        switch tab {
        case .actividad, .despacho: return ("DESPACHOS", "ACTIVOS")
        case .ordenes: return ("ORDENES", "DEL DIA")
        case .alertas: return ("MURO DE", "COMUNICACIONES")
        case .asistencia: return ("HISTORIAL DE", "ASISTENCIAS")
        case .disponibles: return ("PERSONAL", "DISPONIBLE")
        }
    }
}

// MARK: - BottomNavigationBarView (Floating Pill Dock)
struct BottomNavigationBarView: View {
    @ObservedObject var viewModel: SisBomViewModel
    let visibleTabs: [MainTab]

    var body: some View {
        let isDark = viewModel.isDarkMode
        
        HStack(spacing: 0) {
            ForEach(visibleTabs, id: \.self) { tab in
                let isSelected = viewModel.currentTab == tab
                Button(action: {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.78)) {
                        viewModel.currentTab = tab
                    }
                }) {
                    VStack(spacing: 4) {
                        Image(systemName: tabIcon(for: tab))
                            .font(.system(size: 20, weight: isSelected ? .bold : .regular))
                            .foregroundColor(isSelected ? Color.bomberosRed : (isDark ? Color(red: 0.58, green: 0.64, blue: 0.72) : Color.textSecondary))
                        
                        Text(tabLabel(for: tab))
                            .font(.system(size: 9, weight: isSelected ? .bold : .medium))
                            .foregroundColor(isSelected ? Color.bomberosRed : (isDark ? Color(red: 0.58, green: 0.64, blue: 0.72) : Color.textSecondary))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .background(
                        isSelected ?
                            RoundedRectangle(cornerRadius: 20)
                                .fill(isDark ? Color.bomberosRed.opacity(0.16) : Color.bomberosRed.opacity(0.10))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 20)
                                        .stroke(isDark ? Color.bomberosRed.opacity(0.35) : Color.bomberosRed.opacity(0.25), lineWidth: 1)
                                )
                            : nil
                    )
                }
            }
        }
        .padding(.horizontal, 8)
        .frame(height: 68)
        .background(
            RoundedRectangle(cornerRadius: 28)
                .fill(isDark ? Color(red: 0.059, green: 0.090, blue: 0.165).opacity(0.95) : Color.white.opacity(0.95))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 28)
                .stroke(isDark ? Color.white.opacity(0.12) : Color(red: 0.886, green: 0.910, blue: 0.941), lineWidth: 1)
        )
        .shadow(color: isDark ? Color.black.opacity(0.4) : Color.black.opacity(0.15), radius: 12, x: 0, y: 4)
        .padding(.horizontal, 14)
        .padding(.bottom, 8)
    }

    private func tabIcon(for tab: MainTab) -> String {
        switch tab {
        case .actividad: return "flame.fill"
        case .despacho: return "bolt.fill"
        case .ordenes: return "doc.text.fill"
        case .alertas: return "bell.fill"
        case .asistencia: return "calendar"
        case .disponibles: return "person.2.fill"
        }
    }

    private func tabLabel(for tab: MainTab) -> String {
        switch tab {
        case .actividad: return "Actividad"
        case .despacho: return "Despacho"
        case .ordenes: return "Órdenes"
        case .alertas: return "Alertas"
        case .asistencia: return "Asistencia"
        case .disponibles: return "Disponibles"
        }
    }
}

// MARK: - Profile Drawer Content
struct ProfileDrawerContent: View {
    @ObservedObject var viewModel: SisBomViewModel
    @Binding var isDrawerOpen: Bool
    @Binding var showingChangePassword: Bool

    var body: some View {
        let isDark = viewModel.isDarkMode
        
        VStack(alignment: .leading, spacing: 0) {
            // Profile Card Top Area
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    // Close button
                    Button(action: {
                        withAnimation(.spring()) {
                            isDrawerOpen = false
                        }
                    }) {
                        Image(systemName: "xmark")
                            .foregroundColor(isDark ? .white : .textDark)
                    }
                    
                    Spacer()
                }
                
                // User info
                if let user = viewModel.currentUser {
                    HStack(spacing: 12) {
                        // User Avatar Image or Placeholder
                        if !user.foto.isEmpty, let url = URL(string: user.foto) {
                            AsyncImage(url: url) { image in
                                image.resizable()
                                     .aspectRatio(contentMode: .fill)
                            } placeholder: {
                                Image(systemName: "person.circle.fill")
                                    .resizable()
                                    .foregroundColor(.textSecondary)
                            }
                            .frame(width: 54, height: 54)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                        } else {
                            Image(systemName: "person.crop.square.fill")
                                .resizable()
                                .aspectRatio(contentMode: .fit)
                                .frame(width: 54, height: 54)
                                .foregroundColor(.textSecondary)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                        }
                        
                        VStack(alignment: .leading, spacing: 4) {
                            Text(user.nombreBombero)
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(isDark ? .white : .textDark)
                                .lineLimit(1)
                            
                            Text("Radial: \(user.idRadial.isEmpty ? user.idRegistro : user.idRadial)")
                                .font(.system(size: 12))
                                .foregroundColor(.textSecondary)
                        }
                    }
                    
                    if !user.cargo.isEmpty {
                        Text(user.cargo.uppercased())
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color.bomberosRed)
                            .cornerRadius(4)
                    }
                }
            }
            .padding(24)
            .padding(.top, 50)
            .background(isDark ? Color.navyDark : Color(red: 0.96, green: 0.97, blue: 0.98))
            
            // Drawer Menu List Items
            VStack(alignment: .leading, spacing: 20) {
                // Dark Mode Switcher Row
                HStack {
                    Image(systemName: isDark ? "moon.stars.fill" : "sun.max.fill")
                        .foregroundColor(Color.bomberosRed)
                        .frame(width: 24)
                    Text("Modo Oscuro")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(isDark ? .white : .textDark)
                    Spacer()
                    Toggle("", isOn: $viewModel.isDarkMode)
                        .labelsHidden()
                        .toggleStyle(SwitchToggleStyle(tint: Color.bomberosRed))
                }
                
                // Change Password Menu Button
                Button(action: {
                    showingChangePassword = true
                    withAnimation(.spring()) {
                        isDrawerOpen = false
                    }
                }) {
                    HStack(spacing: 12) {
                        Image(systemName: "key.fill")
                            .foregroundColor(Color.bomberosRed)
                            .frame(width: 24)
                        Text("Cambiar Contraseña")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(isDark ? .white : .textDark)
                    }
                }
                
                // Central operator shortcut (Only for dispatchers)
                if viewModel.isCentralActive {
                    Button(action: {
                        viewModel.currentTab = .despacho
                        withAnimation(.spring()) {
                            isDrawerOpen = false
                        }
                    }) {
                        HStack(spacing: 12) {
                            Image(systemName: "phone.fill")
                                .foregroundColor(Color.bomberosRed)
                                .frame(width: 24)
                            Text("Consola Despacho")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(isDark ? .white : .textDark)
                        }
                    }
                }
                
                Spacer()
                
                // Logout Button at Bottom
                Button(action: {
                    viewModel.logout()
                }) {
                    HStack(spacing: 12) {
                        Image(systemName: "rectangle.portrait.and.arrow.right")
                            .foregroundColor(Color.bomberosRed)
                            .frame(width: 24)
                        Text("Cerrar Sesión")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(Color.bomberosRed)
                    }
                }
                
                VStack(spacing: 4) {
                    Image(isDark ? "sentinel_one_logo" : "sentinel_one_logo_light")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(height: 28)
                    Text("V 2.1.4")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(Color(red: 0.85, green: 0.47, blue: 0.02))
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 12)
                .padding(.bottom, 30)
            }
            .padding(24)
            .background(isDark ? Color.navyDeep : Color.white)
        }
        .frame(maxHeight: .infinity)
        .background(isDark ? Color.navyDeep : Color.white)
        .edgesIgnoringSafeArea(.vertical)
    }
}

// MARK: - Change Password Popup Dialog
struct ChangePasswordDialog: View {
    @ObservedObject var viewModel: SisBomViewModel
    @Binding var newPassword: String
    @Binding var isPresented: Bool

    var body: some View {
        let isDark = viewModel.isDarkMode
        
        ZStack {
            Color.black.opacity(0.5)
                .ignoresSafeArea()
            
            VStack(spacing: 16) {
                Text("CAMBIAR CONTRASEÑA")
                    .font(.system(size: 16, weight: .black))
                    .foregroundColor(isDark ? .white : .textDark)
                
                TextField("Nueva Contraseña", text: $newPassword)
                    .padding()
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(isDark ? Color.white.opacity(0.15) : Color(red: 0.88, green: 0.91, blue: 0.94), lineWidth: 1.5)
                    )
                    .foregroundColor(isDark ? .white : .textDark)
                    .autocapitalization(.none)
                
                if !viewModel.changePasswordError.isEmpty {
                    Text(viewModel.changePasswordError)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(Color.bomberosRedLight)
                }
                
                if !viewModel.changePasswordSuccess.isEmpty {
                    Text(viewModel.changePasswordSuccess)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(Color.goGreen)
                }
                
                HStack(spacing: 12) {
                    Button(action: {
                        newPassword = ""
                        viewModel.changePasswordError = ""
                        viewModel.changePasswordSuccess = ""
                        isPresented = false
                    }) {
                        Text("Cerrar")
                            .frame(maxWidth: .infinity)
                            .frame(height: 44)
                            .foregroundColor(isDark ? .white : .textDark)
                            .background(isDark ? Color.white.opacity(0.1) : Color(red: 0.9, green: 0.9, blue: 0.9))
                            .cornerRadius(10)
                    }
                    
                    Button(action: {
                        viewModel.changePassword(newPass: newPassword)
                    }) {
                        Text("Guardar")
                            .frame(maxWidth: .infinity)
                            .frame(height: 44)
                            .foregroundColor(.white)
                            .background(Color.bomberosRed)
                            .cornerRadius(10)
                    }
                }
            }
            .padding(20)
            .frame(width: 300)
            .background(isDark ? Color.navyDark : Color.white)
            .cornerRadius(14)
            .shadow(radius: 10)
        }
    }
}

// MARK: - Changelog Dialog Overlay
struct ChangelogDialog: View {
    @ObservedObject var viewModel: SisBomViewModel

    var body: some View {
        let isDark = viewModel.isDarkMode
        
        ZStack {
            Color.black.opacity(0.5)
                .ignoresSafeArea()
            
            VStack(spacing: 20) {
                // Header
                VStack(spacing: 8) {
                    Image(systemName: "bolt.fill")
                        .font(.system(size: 36))
                        .foregroundColor(Color.bomberosRed)
                    
                    Text("¡NUEVA ACTUALIZACIÓN!")
                        .font(.system(size: 18, weight: .black))
                        .foregroundColor(isDark ? .white : .textDark)
                    
                    Text("SENTINEL ONE V 2.1.4")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.gray)
                }
                
                // Bullet points
                VStack(alignment: .leading, spacing: 14) {
                    bulletPoint(
                        icon: "gearshape.fill",
                        title: "Logo e Icono Personalizado",
                        desc: "Soporte para cambiar dinámicamente el icono de lanzamiento y almacenar en caché el logotipo oficial de su institución.",
                        isDark: isDark
                    )
                    
                    bulletPoint(
                        icon: "cloud.fill",
                        title: "Descarga de Actualizaciones",
                        desc: "Corrección en la visualización del progreso de descargas OTA al evitar la compresión en tránsito.",
                        isDark: isDark
                    )

                    bulletPoint(
                        icon: "lock.fill",
                        title: "Seguridad del Portal",
                        desc: "Se removió la opción de cambiar organización en la pantalla de login para evitar desvinculaciones accidentales.",
                        isDark: isDark
                    )
                }
                .padding(.horizontal, 4)
                
                Button(action: {
                    viewModel.dismissChangelog()
                }) {
                    Text("ENTENDIDO")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color.bomberosRed)
                        .cornerRadius(12)
                }
                .padding(.top, 10)
            }
            .padding(24)
            .background(isDark ? Color.navyDeep : Color.white)
            .cornerRadius(20)
            .overlay(
                RoundedRectangle(cornerRadius: 20)
                    .stroke(isDark ? Color.white.opacity(0.15) : Color(red: 0.88, green: 0.91, blue: 0.94), lineWidth: 1.5)
            )
            .padding(.horizontal, 32)
        }
    }
    
    @ViewBuilder
    private func bulletPoint(icon: String, title: String, desc: String, isDark: Bool) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 16))
                .foregroundColor(Color.bomberosRed)
                .frame(width: 24, height: 24)
                .background(Color.bomberosRed.opacity(0.1))
                .cornerRadius(6)
            
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(isDark ? .white : .textDark)
                Text(desc)
                    .font(.system(size: 11))
                    .foregroundColor(.gray)
                    .lineLimit(nil)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

// MARK: - Fullscreen Emergency Alert View
struct FullscreenEmergencyAlertView: View {
    let dispatch: Dispatch
    @ObservedObject var viewModel: SisBomViewModel

    var body: some View {
        ZStack {
            Color(red: 0.725, green: 0.11, blue: 0.11) // #B91C1C
                .ignoresSafeArea()

            VStack(spacing: 0) {
                // Header: Logo and Title
                VStack(spacing: 8) {
                    Image(uiImage: viewModel.getInstitutionLogo())
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 72, height: 72)
                        .clipShape(Circle())
                        .overlay(Circle().stroke(Color.white, lineWidth: 2))

                    Text("🚨 DESPACHO DE EMERGENCIA 🚨")
                        .font(.system(size: 18, weight: .black))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)

                    Text("¡CONFIRMA TU ASISTENCIA AHORA!")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(Color.white.opacity(0.9))
                        .multilineTextAlignment(.center)
                }
                .padding(.top, 40)

                Spacer()

                // Center: Clave, Time, Date, Preinforme, Units
                VStack(spacing: 12) {
                    Text("CLAVE")
                        .font(.system(size: 15, weight: .heavy))
                        .tracking(2)
                        .foregroundColor(Color.white.opacity(0.75))

                    let claveText = dispatch.clave.isEmpty ? "10-0" : dispatch.clave
                    Text(claveText)
                        .font(.system(size: claveText.count > 5 ? 64 : 80, weight: .black))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.6)
                        .lineLimit(1)

                    HStack(spacing: 14) {
                        let hora = dispatch.horaDespacho.isEmpty ? "--:--" : dispatch.horaDespacho
                        Text("HORA: \(hora)")
                            .font(.system(size: 16, weight: .black))
                            .foregroundColor(.white)

                        Text("•")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(Color.white.opacity(0.6))

                        let fecha = dispatch.fechaDespacho.isEmpty ? DateFormatter.localizedString(from: Date(), dateStyle: .short, timeStyle: .none) : dispatch.fechaDespacho
                        Text("FECHA: \(fecha)")
                            .font(.system(size: 16, weight: .black))
                            .foregroundColor(.white)
                    }

                    if !dispatch.preinforme.isEmpty {
                        Text(dispatch.preinforme)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(Color.white.opacity(0.9))
                            .multilineTextAlignment(.center)
                            .lineLimit(2)
                            .padding(.horizontal, 24)
                            .padding(.top, 6)
                    }

                    if !dispatch.carros.isEmpty {
                        Text("UNIDADES: \(dispatch.carros)")
                            .font(.system(size: 15, weight: .black))
                            .foregroundColor(Color(red: 0.98, green: 0.75, blue: 0.14)) // Amber #FBBF24
                            .multilineTextAlignment(.center)
                            .padding(.top, 4)
                    }
                }

                Spacer()

                // Action Buttons: ASISTIR & NO ASISTIR
                VStack(spacing: 12) {
                    Button(action: {
                        viewModel.attendService(dispatchId: dispatch.idServicio, attend: true)
                        viewModel.fullscreenDispatchId = nil
                    }) {
                        Text("ASISTIR")
                            .font(.system(size: 18, weight: .black))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 56)
                            .background(Color(red: 0.06, green: 0.73, blue: 0.51)) // #10B981
                            .cornerRadius(18)
                    }

                    Button(action: {
                        viewModel.declineService(dispatchId: dispatch.idServicio)
                        viewModel.fullscreenDispatchId = nil
                    }) {
                        Text("NO ASISTIR")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 48)
                            .background(Color.black.opacity(0.45))
                            .overlay(
                                RoundedRectangle(cornerRadius: 18)
                                    .stroke(Color.white.opacity(0.6), lineWidth: 1.5)
                            )
                            .cornerRadius(18)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 44)
            }
        }
    }
}
