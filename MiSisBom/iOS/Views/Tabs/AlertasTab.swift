import SwiftUI

struct AlertasTab: View {
    @ObservedObject var viewModel: SisBomViewModel

    var body: some View {
        let isDark = viewModel.isDarkMode
        let myRadial = viewModel.currentUser?.idRadial.uppercased().trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let myReg = viewModel.currentUser?.idRegistro.uppercased().trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        
        let alerts = viewModel.alertsList
            .filter { alert in
                guard alert.tipo != "orden" else { return false }
                guard isAlertActive(duracion: alert.duracion) else { return false }
                let aQuien = alert.aQuienAlerta.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
                if aQuien.isEmpty || aQuien == "TC" || aQuien == "SC" {
                    return true
                }
                return aQuien.contains(myRadial) || aQuien.contains(myReg)
            }
            .sorted { a, b in
                let aPinned = a.fijar.split(separator: ",")
                    .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
                    .contains(myRadial)
                let bPinned = b.fijar.split(separator: ",")
                    .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
                    .contains(myRadial)
                
                if aPinned != bPinned {
                    return aPinned && !bPinned // Pinned items first
                }
                return a.idAlerta > b.idAlerta // Then newest first
            }
        
        ScrollView {
            VStack(spacing: 16) {
                if alerts.isEmpty {
                    VStack {
                        Spacer().frame(height: 100)
                        Text("No se registran alertas activas en la cartelera.")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                } else {
                    LazyVStack(spacing: 14) {
                        ForEach(alerts) { alert in
                            AlertItemCard(alert: alert, viewModel: viewModel) {
                                viewModel.activeChatAlert = alert
                                viewModel.activeChatId = alert.idAlerta
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                }
                
                Spacer(minLength: 20)
            }
            .padding(.top, 8)
        }
    }
}

// MARK: - AlertItemCard Component (Exact ANDROID 4.jpeg Design)
struct AlertItemCard: View {
    let alert: AlertaItem
    @ObservedObject var viewModel: SisBomViewModel
    let onChatClick: () -> Void
    @State private var showDetailSheet: Bool = false

    var body: some View {
        let isDark = viewModel.isDarkMode
        let myRadial = viewModel.currentUser?.idRadial.uppercased() ?? ""
        let isPinned = alert.fijar.split(separator: ",")
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
            .contains(myRadial)
        let isConforme = alert.conforme.split(separator: ",")
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
            .contains(myRadial)
        let isChat = alert.duracion.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == "C"
        
        VStack(spacing: 8) {
            // Bookmark Ribbon Row
            HStack {
                Spacer()
                Button(action: {
                    viewModel.toggleAlertPin(alert: alert)
                }) {
                    Image(systemName: isPinned ? "bookmark.fill" : "bookmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(isPinned ? Color.alertAmber : (isDark ? Color.white.opacity(0.5) : Color(red: 0.60, green: 0.65, blue: 0.72)))
                        .padding(4)
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)

            // Centered Circular Blue Icon
            ZStack {
                Circle()
                    .fill(isDark ? Color.infoBlue.opacity(0.2) : Color(red: 0.88, green: 0.92, blue: 0.98))
                    .frame(width: 44, height: 44)
                
                Image(systemName: isChat ? "message.and.waveform.fill" : "info.circle.fill")
                    .font(.system(size: 20))
                    .foregroundColor(isChat ? .bomberosRed : Color(red: 0.23, green: 0.51, blue: 0.96))
            }
            .onTapGesture {
                if isChat {
                    onChatClick()
                } else if !alert.mensajeAlerta.isEmpty {
                    showDetailSheet = true
                }
            }
            
            // Centered Title
            Text(alert.razonAlerta.uppercased())
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(isDark ? .white : .textDark)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .padding(.horizontal, 16)
                .padding(.top, 2)
                .onTapGesture {
                    if isChat {
                        onChatClick()
                    } else if !alert.mensajeAlerta.isEmpty {
                        showDetailSheet = true
                    }
                }
            
            // Centered Date & Time
            Text("\(alert.fechaAlerta) • \(alert.horaAlerta)")
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                .padding(.bottom, 4)
            
            // Divider
            Divider()
                .background(isDark ? Color.white.opacity(0.1) : Color.black.opacity(0.08))
                .padding(.horizontal, 24)
            
            // Bottom Status / Action
            if isChat {
                Button(action: onChatClick) {
                    HStack(spacing: 6) {
                        Image(systemName: "bubble.left.and.bubble.right.fill")
                            .font(.system(size: 11))
                        Text(isConforme ? "ABRIR CANAL DE CHAT" : "RESPONDER / ABRIR CANAL")
                            .font(.system(size: 11, weight: .bold))
                    }
                    .foregroundColor(isDark ? Color(red: 0.97, green: 0.44, blue: 0.44) : .bomberosRed)
                    .padding(.vertical, 8)
                }
                .buttonStyle(PlainButtonStyle())
            } else {
                if isConforme {
                    Text("✓ VISTO")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(Color(red: 0.02, green: 0.59, blue: 0.41))
                        .padding(.vertical, 8)
                } else {
                    Button(action: {
                        viewModel.registerConforme(alert: alert)
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "checkmark.circle.fill")
                            Text("MARCAR COMO VISTO")
                        }
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(.goGreen)
                        .padding(.vertical, 8)
                    }
                    .buttonStyle(PlainButtonStyle())
                }
            }
        }
        .frame(maxWidth: .infinity)
        .background(isDark ? Color.navyDark : Color.white)
        .cornerRadius(20)
        .shadow(color: isDark ? Color.clear : Color.black.opacity(0.04), radius: 6, x: 0, y: 2)
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(isPinned ? Color.alertAmber.opacity(0.7) : (isConforme ? Color.goGreen.opacity(0.35) : (isDark ? Color.white.opacity(0.1) : Color(red: 0.85, green: 0.94, blue: 0.90))), lineWidth: 1.5)
        )
        .sheet(isPresented: $showDetailSheet) {
            AlertDetailModal(alert: alert, viewModel: viewModel, isPresented: $showDetailSheet)
        }
    }
}

// MARK: - AlertDetailModal
struct AlertDetailModal: View {
    let alert: AlertaItem
    @ObservedObject var viewModel: SisBomViewModel
    @Binding var isPresented: Bool

    var body: some View {
        let isDark = viewModel.isDarkMode
        let myRadial = viewModel.currentUser?.idRadial.uppercased() ?? ""
        let isConforme = alert.conforme.split(separator: ",")
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
            .contains(myRadial)
        
        ZStack {
            (isDark ? Color.navyDeep : Color(red: 0.94, green: 0.96, blue: 0.98))
                .ignoresSafeArea()
            
            VStack(spacing: 0) {
                // Header Row
                HStack {
                    Text(alert.razonAlerta.uppercased())
                        .font(.system(size: 14, weight: .black))
                        .foregroundColor(isDark ? .white : .textDark)
                        .lineLimit(1)
                    
                    Spacer()
                    
                    Button(action: { isPresented = false }) {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .bold))
                            .padding(8)
                            .background(isDark ? Color.white.opacity(0.15) : Color.black.opacity(0.1))
                            .foregroundColor(isDark ? .white : .textDark)
                            .clipShape(Circle())
                    }
                }
                .padding(16)
                
                Divider()
                
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        HStack {
                            Text("Fecha: \(alert.fechaAlerta) • \(alert.horaAlerta)")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(.textSecondary)
                            Spacer()
                        }
                        
                        Text(alert.mensajeAlerta.replacingOccurrences(of: "|", with: "\n"))
                            .font(.system(size: 14))
                            .lineSpacing(4)
                            .foregroundColor(isDark ? .white : .textDark)
                        
                        if !alert.quienAlerta.isEmpty {
                            Text("Emitido por: \(alert.quienAlerta)")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.textSecondary)
                        }
                        
                        if !isConforme {
                            Button(action: {
                                viewModel.registerConforme(alert: alert)
                                isPresented = false
                            }) {
                                Text("MARCAR COMO VISTO")
                                    .font(.system(size: 12, weight: .black))
                                    .foregroundColor(.white)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 48)
                                    .background(Color.goGreen)
                                    .cornerRadius(12)
                            }
                            .padding(.top, 16)
                        }
                    }
                    .padding(16)
                }
            }
        }
    }
}
