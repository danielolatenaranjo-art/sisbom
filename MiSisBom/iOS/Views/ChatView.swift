import SwiftUI
import Combine

struct ChatView: View {
    let alert: AlertaItem
    @ObservedObject var viewModel: SisBomViewModel
    @Environment(\.presentationMode) var presentationMode
    
    @State private var textMessage: String = ""
    
    var body: some View {
        let isDark = viewModel.isDarkMode
        let myId = viewModel.currentUser?.idRegistro ?? ""
        let personnel = viewModel.personnelList
        
        let messages = parseMessages(alert: alert, personnel: personnel, myId: myId)
        
        ZStack {
            // Background
            (isDark ? Color.navyDeep : Color(red: 0.95, green: 0.96, blue: 0.98))
                .ignoresSafeArea()
            
            VStack(spacing: 0) {
                // Header (Full width)
                HStack(spacing: 12) {
                    Button(action: {
                        dismissChat()
                    }) {
                        Image(systemName: "arrow.left")
                            .font(.title3)
                            .foregroundColor(isDark ? .white : .textDark)
                            .padding(4)
                    }
                    
                    VStack(alignment: .leading, spacing: 2) {
                        Text(alert.razonAlerta.lowercased().capitalizingFirstLetter())
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(isDark ? .white : .textDark)
                            .lineLimit(1)
                        
                        Text(alert.aQuienAlerta.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == "TC" ? "TODA LA COMPAÑÍA" : "SALA DE COMUNICACIÓN COMPAÑÍA")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.bomberosRed)
                    }
                    
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.top, 50) // Unified status bar padding
                .padding(.bottom, 14)
                .background(isDark ? Color.navyDark : Color.white)
                
                // Messages Area with Watermark Logo
                ZStack {
                    // Watermark Logo
                    Image("logo")
                        .renderingMode(isDark ? .template : .original)
                        .resizable()
                        .foregroundColor(.white)
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 280, height: 280)
                        .opacity(isDark ? 0.04 : 0.06)
                        .alignmentGuide(.center) { d in d[HorizontalAlignment.center] }
                    
                    // Messages List
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(spacing: 10) {
                                ForEach(messages) { msg in
                                    ChatBubble(
                                        senderName: msg.senderName,
                                        message: msg.message,
                                        time: msg.time,
                                        isMe: msg.isMe,
                                        isDarkTheme: isDark
                                    )
                                    .id(msg.id)
                                }
                            }
                            .padding(.horizontal, 16)
                            .padding(.top, 16)
                            .padding(.bottom, 50)
                        }
                        .onAppear {
                            if let lastMsg = messages.last {
                                proxy.scrollTo(lastMsg.id, anchor: .bottom)
                            }
                        }
                        .onChange(of: messages.count) { _ in
                            if let lastMsg = messages.last {
                                withAnimation {
                                    proxy.scrollTo(lastMsg.id, anchor: .bottom)
                                }
                            }
                        }
                        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { _ in
                            if let lastMsg = messages.last {
                                withAnimation {
                                    proxy.scrollTo(lastMsg.id, anchor: .bottom)
                                }
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(isDark ? Color.navyDeep : Color(red: 0.97, green: 0.98, blue: 0.99))
                
                // Bottom Input Panel
                VStack(spacing: 0) {
                    HStack(spacing: 10) {
                        // Text Field
                        TextField("Escribir mensaje...", text: $textMessage)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(isDark ? Color.navyDeep : Color.white)
                            .foregroundColor(isDark ? .white : .textDark)
                            .cornerRadius(24)
                            .overlay(
                                RoundedRectangle(cornerRadius: 24)
                                    .stroke(isDark ? Color.white.opacity(0.1) : Color(red: 0.88, green: 0.91, blue: 0.94), lineWidth: 1.5)
                            )
                        
                        // Send Button
                        Button(action: {
                            sendMessage()
                        }) {
                            Image(systemName: "paperplane.fill")
                                .font(.system(size: 18))
                                .foregroundColor(.white)
                                .frame(width: 48, height: 48)
                                .background(textMessage.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? Color.bomberosRed.opacity(0.5) : Color.bomberosRed)
                                .clipShape(Circle())
                        }
                        .disabled(textMessage.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .padding(.bottom, 20) // Padding for bottom safe area
                    .background(isDark ? Color.navyDark : Color.white)
                    .shadow(radius: 5)
                }
            }
        }
    }
    
    private func sendMessage() {
        let trimmed = textMessage.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            viewModel.sendChatMessage(alert: alert, messageText: trimmed)
            textMessage = ""
        }
    }
    
    private func dismissChat() {
        viewModel.activeChatAlert = nil
        viewModel.activeChatId = nil
        presentationMode.wrappedValue.dismiss()
    }
    
    // MARK: - Message Parsing Logic
    private func parseMessages(alert: AlertaItem, personnel: [UserPersonal], myId: String) -> [ChatMsgItem] {
        if alert.mensajeAlerta.isEmpty { return [] }
        
        return alert.mensajeAlerta.split(separator: "|").compactMap { rawSub in
            let trimmed = rawSub.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { return nil }
            
            guard let firstColon = findSeparatorColonIndex(trimmed) else { return nil }
            let prefix = String(trimmed[..<firstColon]).trimmingCharacters(in: .whitespacesAndNewlines)
            let msgText = String(trimmed[trimmed.index(after: firstColon)...]).trimmingCharacters(in: .whitespacesAndNewlines)
            
            let prefixParts = prefix.split(separator: "/")
            var datePart = ""
            var timePart = ""
            var senderId = ""
            
            if prefixParts.count >= 3 {
                datePart = cleanPrefixPart(String(prefixParts[0]))
                timePart = cleanPrefixPart(String(prefixParts[1]))
                senderId = cleanPrefixPart(String(prefixParts[2]))
            } else if prefixParts.count == 2 {
                let part0 = cleanPrefixPart(String(prefixParts[0]))
                let part1 = cleanPrefixPart(String(prefixParts[1]))
                if part0.contains(":") {
                    timePart = part0
                    senderId = part1
                } else {
                    datePart = part0
                    senderId = part1
                }
            } else {
                senderId = cleanPrefixPart(prefixParts.first.map { String($0) } ?? "")
            }
            
            if senderId.isEmpty { return nil }
            
            let senderUser = personnel.first {
                $0.idRegistro.uppercased() == senderId.uppercased()
            }
            
            let senderName = senderUser != nil ? formatFirefighterName(senderUser!.nombreBombero) : senderId
            let isMe = senderUser != nil ?
                       (senderUser!.idRegistro.uppercased() == myId.uppercased()) :
                       (senderId.uppercased() == myId.uppercased())
            
            return ChatMsgItem(
                senderName: senderName,
                senderId: senderId,
                message: msgText,
                time: timePart.isEmpty ? "12:00" : timePart,
                isMe: isMe
            )
        }
    }
    
    private func cleanPrefixPart(_ s: String) -> String {
        return s.replacingOccurrences(of: "[", with: "")
            .replacingOccurrences(of: "]", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
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

// Helper extension for capitalizing string
extension String {
    func capitalizingFirstLetter() -> String {
        return prefix(1).uppercased() + dropFirst()
    }
}
