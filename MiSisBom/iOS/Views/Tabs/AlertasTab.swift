import SwiftUI

struct AlertasTab: View {
    @ObservedObject var viewModel: SisBomViewModel

    var body: some View {
        let isDark = viewModel.isDarkMode
        let myRadial = viewModel.currentUser?.idRadial.uppercased() ?? ""
        
        let alerts = viewModel.alertsList
            .filter { $0.tipo != "orden" }
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
            VStack(alignment: .leading, spacing: 16) {
                Text("MURO DE COMUNICACIONES")
                    .font(.system(size: 16, weight: .black))
                    .foregroundColor(isDark ? .white : .textDark)
                    .padding(.top, 16)
                    .padding(.horizontal, 16)
                
                if alerts.isEmpty {
                    VStack {
                        Spacer().frame(height: 100)
                        Text("No se registran alertas en la cartelera.")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                } else {
                    LazyVStack(spacing: 12) {
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
        }
    }
}

// MARK: - AlertItemCard Component
struct AlertItemCard: View {
    let alert: AlertaItem
    @ObservedObject var viewModel: SisBomViewModel
    let onChatClick: () -> Void

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
        
        let borderColor: Color = {
            switch alert.gradoAlerta {
            case "3": return .bomberosRed
            case "2": return .alertAmber
            default: return .infoBlue
            }
        }()
        
        let iconVector: String = isChat ? "message.and.waveform.fill" : "exclamationmark.circle.fill"
        
        let iconColor: Color = {
            if isChat {
                return isDark ? Color(red: 0.97, green: 0.44, blue: 0.44) : .bomberosRed
            } else {
                switch alert.gradoAlerta {
                case "3": return isDark ? Color(red: 0.94, green: 0.27, blue: 0.27) : .bomberosRed
                case "2": return isDark ? Color(red: 0.96, green: 0.62, blue: 0.04) : .alertAmber
                default: return isDark ? Color(red: 0.23, green: 0.51, blue: 0.96) : .infoBlue
                }
            }
        }()
        
        let cardBg: Color = {
            if isChat && !isConforme {
                return Color.bomberosRed.opacity(isDark ? 0.08 : 0.05)
            } else if !isChat && !isConforme {
                return isDark ? Color.navyDark.opacity(0.4) : Color.black.opacity(0.05)
            } else {
                return .clear
            }
        }()
        
        GlassCard(viewModel: viewModel) {
            VStack(alignment: .leading, spacing: 0) {
                // Header (interactive if chat)
                Button(action: {
                    if isChat { onChatClick() }
                }) {
                    HStack(spacing: 12) {
                        ZStack {
                            Circle()
                                .fill(isDark ? Color.black.opacity(0.2) : Color.white.opacity(0.6))
                                .frame(width: 36, height: 36)
                                .overlay(
                                    Circle()
                                        .stroke(isDark ? Color.white.opacity(0.05) : Color.black.opacity(0.05), lineWidth: 1)
                                )
                            
                            Image(systemName: iconVector)
                                .font(.system(size: 16))
                                .foregroundColor(iconColor)
                        }
                        
                        VStack(alignment: .leading, spacing: 2) {
                            Text(alert.razonAlerta)
                                .font(.system(size: 13, weight: .black))
                                .foregroundColor(isChat && !isConforme ? (isDark ? Color(red: 0.97, green: 0.44, blue: 0.44) : .bomberosRed) : (isDark ? .white : .textDark))
                                .lineLimit(1)
                            
                            Text("\(alert.fechaAlerta) • \(alert.horaAlerta)")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                        }
                        Spacer()
                    }
                }
                .disabled(!isChat)
                .buttonStyle(PlainButtonStyle())
                
                Spacer().frame(height: 10)
                
                // Preview message content
                let previewText = parseAlertPreview(alert: alert)
                Button(action: {
                    if isChat { onChatClick() }
                }) {
                    Text(previewText)
                        .font(.system(size: 12))
                        .lineSpacing(4)
                        .foregroundColor(isDark ? Color(red: 0.8, green: 0.84, blue: 0.88) : Color(red: 0.28, green: 0.33, blue: 0.41))
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 2)
                }
                .disabled(!isChat)
                .buttonStyle(PlainButtonStyle())
                
                Spacer().frame(height: 12)
                Divider()
                    .background(isDark ? Color.white.opacity(0.05) : Color.black.opacity(0.05))
                Spacer().frame(height: 10)
                
                // Actions Footer Row
                HStack {
                    if isChat {
                        Button(action: onChatClick) {
                            HStack(spacing: 6) {
                                Image(systemName: "bubble.left.and.bubble.right.fill")
                                    .font(.system(size: 12))
                                Text("RESPONDER")
                                    .font(.system(size: 10, weight: .black))
                            }
                            .padding(.horizontal, 14)
                            .frame(height: 36)
                            .foregroundColor(isDark ? Color(red: 0.97, green: 0.44, blue: 0.44) : .bomberosRed)
                            .background(isDark ? Color.bomberosRed.opacity(0.15) : Color.bomberosRed.opacity(0.08))
                            .cornerRadius(10)
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(Color.bomberosRed.opacity(0.2), lineWidth: 1)
                            )
                        }
                    } else {
                        HStack(spacing: 8) {
                            Text(isConforme ? "CONFORME REGISTRADO" : "MARCAR CONFORME")
                                .font(.system(size: 9, weight: .black))
                                .foregroundColor(isConforme ? .goGreen : (isDark ? Color.textSecondary : .textSecondaryDark))
                            
                            Button(action: {
                                if !isConforme {
                                    viewModel.registerConforme(alert: alert)
                                }
                            }) {
                                Image(systemName: isConforme ? "checkmark.square.fill" : "square")
                                    .font(.title3)
                                    .foregroundColor(isConforme ? .goGreen : (isDark ? Color.white.opacity(0.2) : Color.black.opacity(0.2)))
                            }
                            .disabled(isConforme)
                        }
                    }
                    
                    Spacer()
                    
                    // Pin Button
                    Button(action: {
                        viewModel.toggleAlertPin(alert: alert)
                    }) {
                        Image(systemName: isPinned ? "bookmark.fill" : "bookmark")
                            .font(.system(size: 14))
                            .padding(10)
                            .background(isPinned ? Color.bomberosRed.opacity(isDark ? 0.15 : 0.08) : Color.black.opacity(0.03))
                            .foregroundColor(isPinned ? (isDark ? Color(red: 0.97, green: 0.44, blue: 0.44) : .bomberosRed) : (isDark ? Color.textSecondary : .textSecondaryDark))
                            .cornerRadius(10)
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(isPinned ? Color.bomberosRed.opacity(0.3) : Color.clear, lineWidth: 1)
                            )
                    }
                }
            }
            .padding(14)
            .background(cardBg)
            .cornerRadius(16)
        }
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(isPinned ? Color.alertAmber : borderColor.opacity(0.3), lineWidth: 1.5)
        )
    }
    
    private func parseAlertPreview(alert: AlertaItem) -> String {
        if alert.duracion.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == "C" {
            let msgs = alert.mensajeAlerta.split(separator: "|").map { String($0) }.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            if let lastMsg = msgs.last {
                if let colonIdx = findSeparatorColonIndex(lastMsg) {
                    let prefix = String(lastMsg[..<colonIdx])
                    let body = String(lastMsg[lastMsg.index(after: colonIdx)...]).trimmingCharacters(in: .whitespacesAndNewlines)
                    let senderId = (prefix.split(separator: "/").last.map { String($0) } ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    let trimmedSenderId = senderId.uppercased()
                    let myIdReg = viewModel.currentUser?.idRegistro.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
                    let senderUser = viewModel.personnelList.first(where: {
                        $0.idRegistro.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == trimmedSenderId
                    })
                    let isMe = senderUser != nil ?
                               (senderUser!.idRegistro.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == myIdReg) :
                               (trimmedSenderId == myIdReg)
                    let senderName = isMe ? "Tú" : (senderUser != nil ? formatFirefighterName(senderUser!.nombreBombero) : senderId)
                    return "\(senderName): \(body)"
                }
                return lastMsg
            }
            return "Sin mensajes en el canal."
        } else {
            return alert.mensajeAlerta.replacingOccurrences(of: "|", with: "\n")
        }
    }
    
    private func formatFirefighterName(_ name: String) -> String {
        let parts = name.split(separator: " ").map { String($0) }
        guard let first = parts.first else { return name }
        if parts.count >= 3 {
            return "\(first) \(parts[2])"
        } else if parts.count == 2 {
            return "\(first) \(parts[1])"
        }
        return first
    }
    
    private func findSeparatorColonIndex(_ text: String) -> String.Index? {
        guard let firstSlash = text.firstIndex(of: "/") else {
            return text.firstIndex(of: ":")
        }
        let afterFirstSlash = text.index(after: firstSlash)
        guard let secondSlash = text[afterFirstSlash...].firstIndex(of: "/") else {
            return text[afterFirstSlash...].firstIndex(of: ":")
        }
        let afterSecondSlash = text.index(after: secondSlash)
        return text[afterSecondSlash...].firstIndex(of: ":")
    }
}
