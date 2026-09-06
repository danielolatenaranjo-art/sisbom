import SwiftUI

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
            // Main App Background
            SisBomBackground(viewModel: viewModel) {
                VStack(spacing: 0) {
                    // Header Bar (Merged with status bar)
                    HStack {
                        Button(action: {
                            withAnimation(.spring()) {
                                isDrawerOpen.toggle()
                            }
                        }) {
                            Image(systemName: "line.3.horizontal")
                                .font(.title2)
                                .foregroundColor(isDark ? .white : .textDark)
                        }
                        
                        Spacer()
                        
                        // Header title
                        Text(headerTitle(for: viewModel.currentTab))
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(isDark ? .white : .textDark)
                        
                        Spacer()
                        
                        // Sync status dot
                        SyncIndicatorDot(isSyncing: viewModel.isSyncing)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 50) // Padding for status bar on iOS
                    .padding(.bottom, 12)
                    .background(isDark ? Color.navyDark : Color.white)
                    
                    // Tab Content Wrapper
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
                    
                    // Custom Bottom Navigation Bar
                    HStack {
                        ForEach(visibleTabs, id: \.self) { tab in
                            Button(action: {
                                viewModel.currentTab = tab
                            }) {
                                VStack(spacing: 4) {
                                    Image(systemName: tabIcon(for: tab))
                                        .font(.system(size: 20))
                                    Text(tabLabel(for: tab))
                                        .font(.system(size: 9, weight: .bold))
                                }
                                .frame(maxWidth: .infinity)
                                .foregroundColor(viewModel.currentTab == tab ? Color.bomberosRed : Color.textSecondary)
                            }
                        }
                    }
                    .padding(.vertical, 10)
                    .padding(.bottom, 20) // Safe area padding for bottom bar
                    .background(isDark ? Color.navyDark : Color.white)
                    .overlay(
                        Divider()
                            .background(isDark ? Color.white.opacity(0.1) : Color(red: 0.9, green: 0.9, blue: 0.9)),
                        alignment: .top
                    )
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
    
    private func headerTitle(for tab: MainTab) -> String {
        switch tab {
        case .actividad: return "Actividad"
        case .despacho: return "Despacho Operador"
        case .ordenes: return "Órdenes del Día"
        case .alertas: return "Alertas Generales"
        case .asistencia: return "Asistencia"
        case .disponibles: return "Disponibles"
        }
    }
    
    private func tabIcon(for tab: MainTab) -> String {
        switch tab {
        case .actividad: return "waveform.path.ecg"
        case .despacho: return "phone.fill"
        case .ordenes: return "doc.plaintext.fill"
        case .alertas: return "bell.fill"
        case .asistencia: return "person.3.fill"
        case .disponibles: return "person.crop.circle.badge.checkmark"
        }
    }
    
    private func tabLabel(for tab: MainTab) -> String {
        switch tab {
        case .actividad: return "Actividad"
        case .despacho: return "Despacho"
        case .ordenes: return "Orden del Día"
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
