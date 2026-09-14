import SwiftUI
import WebKit

struct ActividadTab: View {
    @ObservedObject var viewModel: SisBomViewModel
    @State private var isRequested: Bool = false
    @State private var isRequesting: Bool = false

    var body: some View {
        let isDark = viewModel.isDarkMode
        let user = viewModel.currentUser
        let rawEstado = user?.estado.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
        let inService = !(user?.enServicio.trimmingCharacters(in: .whitespacesAndNewlines) == "0") && !(user?.enServicio.isEmpty ?? true)
        let isSpecial = rawEstado.contains("SUSPENDIDO") || rawEstado == "CDS" || rawEstado.contains("LICENCIA") || rawEstado == "PERMISO"
        
        let is09Active = rawEstado == "0-9" && !isSpecial
        let is08Active = rawEstado == "0-8" || isSpecial
        
        let is09Enabled = !inService && !isSpecial
        let is08Enabled = !inService
        
        let activeDispatches = viewModel.dispatchesList.filter { $0.operadorFinal.isEmpty }
        
        ScrollView {
            VStack(spacing: 10) {
                // Central Operator Status Card (Compact)
                let isOpActive = !viewModel.centralOperatorName.isEmpty
                let formattedOpName = isOpActive ? formatOperatorCadName(viewModel.centralOperatorName).uppercased() : "SIN OPERADOR"

                GlassCard(viewModel: viewModel) {
                    HStack(spacing: 10) {
                        Circle()
                            .fill(isOpActive ? Color.goGreen : Color.gray.opacity(0.6))
                            .frame(width: 10, height: 10)

                        Text("OPERADOR CAD: \(formattedOpName)")
                            .font(.system(size: 12, weight: .black))
                            .foregroundColor(isDark ? .white : Color(red: 0.12, green: 0.16, blue: 0.23))
                            .lineLimit(1)

                        Spacer()

                        // Botón TIMBRE para solicitar apertura de puerta al Operador CAD (con cooldown de 30 segundos)
                        Button(action: {
                            if !isRequested && !isRequesting {
                                isRequesting = true
                                viewModel.solicitarAperturaPuerta(
                                    onSuccess: {
                                        isRequesting = false
                                        isRequested = true
                                        DispatchQueue.main.asyncAfter(deadline: .now() + 30) {
                                            isRequested = false
                                        }
                                    },
                                    onFailure: { _ in
                                        isRequesting = false
                                    }
                                )
                            }
                        }) {
                            Text(isRequested ? "TOCADO" : "TIMBRE")
                                .font(.system(size: 10, weight: .black))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .foregroundColor(isRequested ? Color.goGreen : (isDark ? Color(red: 0.99, green: 0.65, blue: 0.65) : Color(red: 0.86, green: 0.15, blue: 0.15)))
                                .background(isRequested ? Color.goGreen.opacity(0.2) : (isDark ? Color(red: 0.5, green: 0.11, blue: 0.11).opacity(0.6) : Color(red: 0.86, green: 0.15, blue: 0.15).opacity(0.15)))
                                .cornerRadius(8)
                        }
                        .disabled(isRequested || isRequesting)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                }
                .padding(.horizontal, 16)
                .padding(.top, 4)
                
                // List of Incidents
                ForEach(activeDispatches) { dispatch in
                    DispatchItemCard(dispatch: dispatch, viewModel: viewModel)
                        .padding(.horizontal, 16)
                }
                
                Spacer(minLength: 20)
            }
        }
    }
}

// MARK: - StatusButton Helper Component
struct StatusButton: View {
    let title: String
    let subtitle: String
    let icon: String
    let isActive: Bool
    let isEnabled: Bool
    let activeColor: Color
    let inactiveColor: Color
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.title3)
                
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 16, weight: .black))
                    Text(subtitle)
                        .font(.system(size: 9, weight: .black))
                }
                Spacer()
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .foregroundColor(isActive ? .white : inactiveColor)
            .background(isActive ? activeColor : Color.clear)
            .cornerRadius(12)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isActive ? Color.clear : inactiveColor.opacity(0.3), lineWidth: 1.5)
            )
        }
        .disabled(!isEnabled)
        .opacity(isEnabled ? 1.0 : 0.4)
    }
}

// MARK: - Tactical Clave Color Helpers
func getClaveTacticalColor(clave: String) -> Color {
    let cleanKey = clave.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    switch cleanKey {
    case "10-0", "10-30":
        return Color(red: 0.86, green: 0.15, blue: 0.15) // Crimson Fire #DC2626
    case "10-1":
        return Color(red: 0.92, green: 0.35, blue: 0.05) // Blaze Orange #EA580C
    case "10-2":
        return Color(red: 0.85, green: 0.47, blue: 0.02) // Amber Forest #D97706
    case "10-3", "10-8":
        return Color(red: 0.01, green: 0.52, blue: 0.78) // Rescue Cyan #0284C7
    case "10-4":
        return Color(red: 0.88, green: 0.11, blue: 0.28) // Accident Rose #E11D48
    case "10-5":
        return Color(red: 0.49, green: 0.23, blue: 0.93) // Hazmat Purple #7C3AED
    case "10-6":
        return Color(red: 0.05, green: 0.58, blue: 0.53) // Gas Teal #0D9488
    case "10-7":
        return Color(red: 0.92, green: 0.70, blue: 0.03) // Electric Yellow #EAB308
    case "10-9":
        return Color(red: 0.76, green: 0.25, blue: 0.05) // Other Amber #C2410C
    case "10-10":
        return Color(red: 0.01, green: 0.41, blue: 0.63) // Collapse Slate #0369A1
    case "10-11":
        return Color(red: 0.15, green: 0.39, blue: 0.92) // Special Blue #2563EB
    case "10-12":
        return Color(red: 0.31, green: 0.27, blue: 0.90) // Mutual Aid Indigo #4F46E5
    case "10-14":
        return Color(red: 0.02, green: 0.59, blue: 0.41) // Emerald #059669
    default:
        return Color(red: 0.86, green: 0.15, blue: 0.15)
    }
}

func getClavePinHex(clave: String) -> String {
    let cleanKey = clave.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    switch cleanKey {
    case "10-0", "10-30": return "#dc2626"
    case "10-1": return "#ea580c"
    case "10-2": return "#d97706"
    case "10-3", "10-8": return "#0284c7"
    case "10-4": return "#e11d48"
    case "10-5": return "#7c3aed"
    case "10-6": return "#0d9488"
    case "10-7": return "#eab308"
    case "10-9": return "#c2410c"
    case "10-10": return "#0369a1"
    case "10-11": return "#2563eb"
    case "10-12": return "#4f46e5"
    case "10-14": return "#059669"
    default: return "#dc2626"
    }
}

// MARK: - TacticalRadarScanner Component
struct TacticalRadarScanner: View {
    let clave: String
    let isDark: Bool
    var compact: Bool = false
    @State private var radarAngle: Double = 0.0
    @State private var pulseScale: CGFloat = 0.8
    @State private var pulseOpacity: Double = 0.9

    var body: some View {
        let claveColor = getClaveTacticalColor(clave: clave)
        ZStack {
            (isDark ? Color(red: 0.02, green: 0.03, blue: 0.06) : Color(red: 0.06, green: 0.09, blue: 0.16))

            // Radar Grid Canvas
            Canvas { context, size in
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let maxRadius = min(size.width, size.height) * 0.44
                
                // Concentric rings
                for i in 1...3 {
                    let r = maxRadius * CGFloat(i) / 3.0
                    let path = Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2))
                    context.stroke(path, with: .color(claveColor.opacity(0.2)), lineWidth: 1.2)
                }
                
                // Crosshairs
                var hLine = Path()
                hLine.move(to: CGPoint(x: center.x - maxRadius, y: center.y))
                hLine.addLine(to: CGPoint(x: center.x + maxRadius, y: center.y))
                context.stroke(hLine, with: .color(claveColor.opacity(0.25)), lineWidth: 1)
                
                var vLine = Path()
                vLine.move(to: CGPoint(x: center.x, y: center.y - maxRadius))
                vLine.addLine(to: CGPoint(x: center.x, y: center.y + maxRadius))
                context.stroke(vLine, with: .color(claveColor.opacity(0.25)), lineWidth: 1)
            }
            
            // Rotating Radar Beam
            AngularGradient(
                gradient: Gradient(colors: [
                    Color.clear,
                    Color.clear,
                    claveColor.opacity(0.15),
                    claveColor.opacity(0.55)
                ]),
                center: .center
            )
            .clipShape(Circle())
            .frame(width: compact ? 100 : 130, height: compact ? 100 : 130)
            .rotationEffect(.degrees(radarAngle))
            
            // Pulsing Center Beacon
            Circle()
                .fill(claveColor.opacity(pulseOpacity * 0.4))
                .frame(width: 32 * pulseScale, height: 32 * pulseScale)
            
            Circle()
                .fill(Color.white)
                .frame(width: 8, height: 8)
            
            // Top-Right Status Badge
            VStack {
                HStack {
                    Spacer()
                    HStack(spacing: 5) {
                        Circle()
                            .fill(Color(red: 0.96, green: 0.62, blue: 0.04)) // Amber
                            .frame(width: 6, height: 6)
                        Text("FASE 1: SIN GPS")
                            .font(.system(size: 9, weight: .black))
                            .foregroundColor(Color(red: 0.99, green: 0.90, blue: 0.54))
                    }
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color.black.opacity(0.85))
                    .cornerRadius(6)
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(red: 0.96, green: 0.62, blue: 0.04).opacity(0.5), lineWidth: 1))
                    .padding(8)
                }
                Spacer()
                // Bottom tactical description
                VStack(spacing: 2) {
                    Text("🛰️ RASTREANDO GEORREFERENCIA...")
                        .font(.system(size: 11, weight: .black))
                        .foregroundColor(.white)
                        .tracking(0.6)
                    Text("Central despachando coordenadas y pre-informe")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundColor(Color(red: 0.58, green: 0.64, blue: 0.72))
                }
                .padding(.bottom, 8)
            }
        }
        .frame(height: compact ? 125 : 165)
        .cornerRadius(16)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(claveColor.opacity(0.35), lineWidth: 1)
        )
        .onAppear {
            withAnimation(Animation.linear(duration: 2.8).repeatForever(autoreverses: false)) {
                radarAngle = 360
            }
            withAnimation(Animation.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) {
                pulseScale = 1.3
                pulseOpacity = 0.2
            }
        }
    }
}

// MARK: - PulsingPerimeterBorder Component
struct PulsingPerimeterBorder: View {
    var color: Color = Color(red: 0.94, green: 0.25, blue: 0.25)
    var strokeWidth: CGFloat = 3.5
    @State private var isPulsing: Bool = false

    var body: some View {
        RoundedRectangle(cornerRadius: 24)
            .stroke(color.opacity(isPulsing ? 0.95 : 0.35), lineWidth: strokeWidth)
            .shadow(color: color.opacity(isPulsing ? 0.8 : 0.2), radius: 8)
            .padding(strokeWidth / 2)
            .onAppear {
                withAnimation(Animation.easeInOut(duration: 0.75).repeatForever(autoreverses: true)) {
                    isPulsing = true
                }
            }
    }
}

// MARK: - Vehicle Emoji Helper
func getVehicleEmoji(carro: String) -> String {
    let upper = carro.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    if upper.hasPrefix("B") || upper.hasPrefix("BX") || upper.hasPrefix("BF") {
        return "🚒"
    } else if upper.hasPrefix("R") || upper.hasPrefix("RX") || upper.hasPrefix("RH") {
        return "🚑"
    } else if upper.hasPrefix("Q") || upper.hasPrefix("M") || upper.hasPrefix("MX") {
        return "🪜"
    } else if upper.hasPrefix("Z") || upper.hasPrefix("BT") {
        return "🚚"
    } else if upper.hasPrefix("K") || upper.hasPrefix("J") || upper.hasPrefix("UT") {
        return "🚙"
    } else if upper.hasPrefix("H") || upper.hasPrefix("HZ") {
        return "☣️"
    } else {
        func formatOperatorCadName(_ fullName: String) -> String {
    let words = fullName.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }
    switch words.count {
    case 0: return ""
    case 1: return words[0]
    case 2: return "\(words[0]) \(words[1])"
    default: return "\(words.first!) \(words[words.count - 2])"
    }
}

// MARK: - DispatchItemCard Component
struct DispatchItemCard: View {
    let dispatch: Dispatch
    @ObservedObject var viewModel: SisBomViewModel

    var body: some View {
        let isDark = viewModel.isDarkMode
        let user = viewModel.currentUser
        let isAttending = user?.enServicio == dispatch.idServicio
        let isDeclined = user?.enServicio == "-\(dispatch.idServicio)"
        let baseState = user?.estado.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
        let inService = !(user?.enServicio.trimmingCharacters(in: .whitespacesAndNewlines) == "0") && !(user?.enServicio.isEmpty ?? true)
        let isSpecial = baseState.contains("SUSPENDIDO") || baseState == "CDS" || baseState.contains("LICENCIA") || baseState == "PERMISO"
        
        let hasValidLocation = (dispatch.lat != nil && dispatch.lat != 0 && dispatch.lng != nil && dispatch.lng != 0)
        
        let cardBorderColor = isAttending
            ? Color.goGreen
            : (isDark ? Color(red: 0.35, green: 0.08, blue: 0.08) : Color(red: 0.73, green: 0.11, blue: 0.11))
        
        let titleText = isAttending ? "SALIENDO A SERVICIO" : "¡DESPACHO ACTIVO!"
        let titleColor = isAttending ? Color.goGreen : Color(red: 0.94, green: 0.27, blue: 0.27)
        let operadorName = !dispatch.quienDespacha.isEmpty ? dispatch.quienDespacha : dispatch.operadorFinal
        
        let openNavigation: () -> Void = {
            if hasValidLocation, let lat = dispatch.lat, let lng = dispatch.lng {
                let urlStr = "maps://?q=\(lat),\(lng)&ll=\(lat),\(lng)"
                if let url = URL(string: urlStr), UIApplication.shared.canOpenURL(url) {
                    UIApplication.shared.open(url)
                } else if let gUrl = URL(string: "https://www.google.com/maps/search/?api=1&query=\(lat),\(lng)") {
                    UIApplication.shared.open(gUrl)
                }
            }
        }
        
        ZStack {
            // LAYER 0: MAP / RADAR AS THE INMERSIVE BACKGROUND CANVAS (anchored so pin stays fixed at y=155pt when card expands downwards)
            GeometryReader { geo in
                let mapHeight: CGFloat = 800
                let pinTargetY: CGFloat = 155
                
                Group {
                    if hasValidLocation, let lat = dispatch.lat, let lng = dispatch.lng {
                        IncidentMapPreview(lat: lat, lng: lng, clave: dispatch.clave, lugar: dispatch.lugar, isDark: isDark)
                    } else {
                        TacticalRadarScanner(clave: dispatch.clave, isDark: isDark, compact: false)
                    }
                }
                .frame(width: geo.size.width, height: mapHeight)
                .position(x: geo.size.width / 2, y: pinTargetY)
            }
            .clipped()
            
            // LAYER 1: CONTENT WITH TOP & BOTTOM DARK RED GRADIENT OVERLAYS
            VStack(spacing: 0) {
                // TOP HEADER: Dark Red Gradient fading downwards into transparency over the map
                VStack(alignment: .leading, spacing: 4) {
                    // Top row: ¡DESPACHO ACTIVO! and Time
                    HStack {
                        Text(titleText)
                            .font(.system(size: 12, weight: .black))
                            .foregroundColor(titleColor)
                            .tracking(0.5)
                        
                        Spacer()
                        
                        Text(dispatch.horaDespacho.isEmpty ? "--:--" : dispatch.horaDespacho)
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(Color(red: 0.8, green: 0.84, blue: 0.88))
                    }
                    
                    Spacer().frame(height: 2)
                    
                    // Clave (Large bold title)
                    let claveText = (dispatch.clave == "10-12" && !dispatch.claveApoyo.isEmpty) ? "\(dispatch.clave) (\(dispatch.claveApoyo))" : (dispatch.clave.isEmpty ? "10-0" : dispatch.clave)
                    Text(claveText)
                        .font(.system(size: 34, weight: .black))
                        .foregroundColor(.white)
                        .lineLimit(1)
                    
                    // Location / Address (Primary address below Clave)
                    Text(!dispatch.lugar.isEmpty ? dispatch.lugar.uppercased() : "UBICACIÓN EN PROCESO DE GEORREFERENCIACIÓN")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(Color(red: 0.88, green: 0.91, blue: 0.94))
                        .lineLimit(2)
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    LinearGradient(
                        colors: [
                            Color(red: 0.18, green: 0.02, blue: 0.02),
                            Color(red: 0.14, green: 0.02, blue: 0.02).opacity(0.95),
                            Color(red: 0.12, green: 0.02, blue: 0.02).opacity(0.8),
                            Color.clear
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                
                // CENTER VIEWPORT: Compact transparent clickable area to view and tap the map
                Button(action: openNavigation) {
                    Color.clear
                        .frame(maxWidth: .infinity)
                        .frame(height: 75)
                }
                .buttonStyle(PlainButtonStyle())
                
                // BOTTOM CONTROLS: Dark Red Gradient fading upwards into transparency over the map
                VStack(alignment: .leading, spacing: 12) {
                    // Pre-informe (if available)
                    let cleanPre = dispatch.preinforme.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "---", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
                    if !cleanPre.isEmpty && !cleanPre.localizedCaseInsensitiveContains("A la espera") {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("📋 PRE-INFORME:")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(Color(red: 0.58, green: 0.64, blue: 0.72))
                            Text(cleanPre)
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(.white)
                        }
                        .padding(10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.black.opacity(0.35))
                        .cornerRadius(14)
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .stroke(Color.white.opacity(0.12), lineWidth: 1)
                        )
                    }
                    
                    // UNIDADES DESPACHADAS (Direct mini-cards without outer card wrapper)
                    let carrosList = dispatch.carros.components(separatedBy: CharacterSet(charactersIn: ",/ ")).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
                    
                    if carrosList.isEmpty {
                        Text("---")
                            .font(.system(size: 13, weight: .black))
                            .foregroundColor(Color(red: 0.97, green: 0.44, blue: 0.44))
                    } else if carrosList.count == 1 && carrosList[0].caseInsensitiveCompare("PERSONAL") == .orderedSame {
                        Text("🧑‍🚒 PERSONAL")
                            .font(.system(size: 13, weight: .black))
                            .foregroundColor(Color(red: 0.97, green: 0.44, blue: 0.44))
                    } else {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(carrosList, id: \.self) { carroName in
                                    let vehicleData = dispatch.unidades[carroName]
                                    let solConductor = vehicleData?["solicitudConductorAt"] as? String ?? ""
                                    let solPersonal = vehicleData?["solicitudPersonalAt"] as? String ?? ""
                                    let emoji = getVehicleEmoji(carro: carroName)
                                    
                                    HStack(spacing: 6) {
                                        Text(emoji)
                                            .font(.system(size: 15))
                                        Text(carroName)
                                            .font(.system(size: 13, weight: .black))
                                            .foregroundColor(.white)
                                        
                                        if !solConductor.isEmpty {
                                            Text("12-10")
                                                .font(.system(size: 9, weight: .black))
                                                .foregroundColor(Color(red: 0.98, green: 0.75, blue: 0.14))
                                                .padding(.horizontal, 5)
                                                .padding(.vertical, 2)
                                                .background(Color(red: 0.96, green: 0.62, blue: 0.04).opacity(0.25))
                                                .cornerRadius(6)
                                                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(red: 0.96, green: 0.62, blue: 0.04).opacity(0.7), lineWidth: 1))
                                        }
                                        
                                        if !solPersonal.isEmpty {
                                            Text("6-6")
                                                .font(.system(size: 9, weight: .black))
                                                .foregroundColor(Color(red: 0.38, green: 0.65, blue: 0.98))
                                                .padding(.horizontal, 5)
                                                .padding(.vertical, 2)
                                                .background(Color(red: 0.23, green: 0.51, blue: 0.96).opacity(0.25))
                                                .cornerRadius(6)
                                                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(red: 0.23, green: 0.51, blue: 0.96).opacity(0.7), lineWidth: 1))
                                        }
                                    }
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 6)
                                    .background(Color.black.opacity(0.4))
                                    .cornerRadius(10)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 10)
                                            .stroke(Color.white.opacity(0.18), lineWidth: 1)
                                    )
                                }
                            }
                        }
                    }
                    
                    // Control de emergencia (6-7)
                    if !dispatch.hora67.isEmpty {
                        Text("✓ CONTROL DE EMERGENCIA (6-7): \(dispatch.hora67)")
                            .font(.system(size: 11, weight: .black))
                            .foregroundColor(Color(red: 0.20, green: 0.83, blue: 0.60))
                            .padding(8)
                            .frame(maxWidth: .infinity)
                            .background(Color.goGreen.opacity(0.12))
                            .cornerRadius(8)
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.goGreen.opacity(0.3), lineWidth: 1))
                    }
                    
                    // Action Buttons: Visible only when firefighter is 0-9 or already attending
                    if (baseState == "0-9" || isAttending) && !isSpecial {
                        VStack(spacing: 0) {
                            Spacer().frame(height: 12)

                            if isAttending {
                                Button(action: {
                                    viewModel.attendService(dispatchId: dispatch.idServicio, attend: false)
                                }) {
                                    Text("CANCELAR ASISTENCIA")
                                        .font(.system(size: 13, weight: .black))
                                        .frame(maxWidth: .infinity)
                                        .frame(height: 48)
                                        .foregroundColor(Color.bomberosRed)
                                        .background(Color.bomberosRed.opacity(0.15))
                                        .cornerRadius(24)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 24)
                                                .stroke(Color.bomberosRed.opacity(0.5), lineWidth: 1)
                                        )
                                }
                            } else {
                                let canAttend = baseState == "0-9" && !inService && !isSpecial
                                
                                HStack(spacing: 10) {
                                    // ASISTIR (Crimson / Ruby Red Filled Pill Button)
                                    Button(action: {
                                        viewModel.attendService(dispatchId: dispatch.idServicio, attend: true)
                                    }) {
                                        HStack(spacing: 6) {
                                            Image(systemName: "checkmark")
                                                .font(.system(size: 13, weight: .bold))
                                            Text("ASISTIR")
                                                .font(.system(size: 13, weight: .black))
                                        }
                                        .frame(maxWidth: .infinity)
                                        .frame(height: 48)
                                        .foregroundColor(canAttend ? .white : Color(red: 0.58, green: 0.64, blue: 0.72))
                                        .background(canAttend ? Color(red: 0.53, green: 0.10, blue: 0.10) : Color.white.opacity(0.08))
                                        .cornerRadius(24)
                                    }
                                    .disabled(!canAttend)
                                    
                                    // NO ASISTIR (Outlined Pill Button)
                                    Button(action: {
                                        viewModel.declineService(dispatchId: dispatch.idServicio)
                                    }) {
                                        HStack(spacing: 6) {
                                            Image(systemName: "xmark")
                                                .font(.system(size: 13, weight: .bold))
                                            Text(isDeclined ? "NO ASISTIRÉ" : "NO ASISTIR")
                                                .font(.system(size: 13, weight: .black))
                                        }
                                        .frame(maxWidth: .infinity)
                                        .frame(height: 48)
                                        .foregroundColor(isDeclined ? .white : Color(red: 0.94, green: 0.27, blue: 0.27))
                                        .background(isDeclined ? Color.bomberosRed : Color.black.opacity(0.1))
                                        .cornerRadius(24)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 24)
                                                .stroke(isDeclined ? Color.clear : Color(red: 0.94, green: 0.27, blue: 0.27).opacity(0.5), lineWidth: 1.2)
                                        )
                                    }
                                    .disabled(isDeclined || inService || baseState != "0-9")
                                }
                            }
                        }
                        .transition(.asymmetric(
                            insertion: .opacity.combined(with: .move(edge: .bottom)),
                            removal: .opacity.combined(with: .move(edge: .bottom))
                        ))
                    }
                    
                    // Operator Console Badge ("EN CONSOLA: ...")
                    if !operadorName.isEmpty {
                        Text("EN CONSOLA: \(formatOperatorCadName(operadorName).uppercased())")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(Color(red: 0.8, green: 0.84, blue: 0.88))
                            .tracking(0.5)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 9)
                            .frame(maxWidth: .infinity)
                            .background(Color.black.opacity(0.35))
                            .cornerRadius(14)
                            .overlay(
                                RoundedRectangle(cornerRadius: 14)
                                    .stroke(Color.white.opacity(0.12), lineWidth: 1)
                            )
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    LinearGradient(
                        colors: [
                            Color.clear,
                            Color(red: 0.12, green: 0.02, blue: 0.02).opacity(0.8),
                            Color(red: 0.10, green: 0.02, blue: 0.02).opacity(0.95),
                            Color(red: 0.06, green: 0.01, blue: 0.01)
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(
            RoundedRectangle(cornerRadius: 24)
                .stroke(cardBorderColor, lineWidth: 1.2)
        )
        .animation(.spring(response: 0.45, dampingFraction: 0.8), value: baseState)
    }
}

// MARK: - IncidentMapPreview Component
struct IncidentMapPreview: View {
    let lat: Double
    let lng: Double
    let clave: String
    var lugar: String = ""
    let isDark: Bool

    var body: some View {
        IncidentWebView(lat: lat, lng: lng, clave: clave, isDark: isDark)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - IncidentWebView with Adaptive Carto Dark/Light Tiles & Clave-Specific Pin
struct IncidentWebView: UIViewRepresentable {
    let lat: Double
    let lng: Double
    let clave: String
    let isDark: Bool

    func makeUIView(context: Context) -> WKWebView {
        let webView = WKWebView()
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        loadMap(in: webView)
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        loadMap(in: uiView)
    }

    private func loadMap(in webView: WKWebView) {
        let pinColor = getClavePinHex(clave: clave)
        let tileUrl = isDark
            ? "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png"
            : "https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}@2x.png"
        let mapBg = isDark ? "#0f172a" : "#f1f5f9"

        let html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: \(mapBg); overflow: hidden; }
                .leaflet-control-attribution { display: none !important; }
                .incident-pin {
                    background: \(pinColor);
                    width: 24px;
                    height: 24px;
                    border-radius: 50%;
                    border: 2.5px solid #ffffff;
                    box-shadow: 0 0 16px rgba(0, 0, 0, 0.8), 0 0 8px \(pinColor);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    color: #ffffff;
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                    font-size: 13px;
                    font-weight: 900;
                }
                .pulse-ring {
                    position: absolute;
                    width: 48px;
                    height: 48px;
                    top: -12px;
                    left: -12px;
                    border-radius: 50%;
                    border: 2px solid \(pinColor);
                    animation: pulse 2s infinite ease-out;
                    pointer-events: none;
                }
                @keyframes pulse {
                    0% { transform: scale(0.5); opacity: 1; }
                    100% { transform: scale(1.6); opacity: 0; }
                }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map', {
                    zoomControl: false,
                    attributionControl: false,
                    dragging: false,
                    touchZoom: false,
                    doubleClickZoom: false,
                    scrollWheelZoom: false,
                    boxZoom: false,
                    keyboard: false
                }).setView([\(lat), \(lng)], 16);

                var tacticalLayer = L.tileLayer('\(tileUrl)', {
                    maxZoom: 20,
                    subdomains: ['a', 'b', 'c', 'd']
                });

                var fallbackOsm = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19
                });

                var fallbackDone = false;
                tacticalLayer.on('tileerror', function() {
                    if (!fallbackDone) {
                        fallbackDone = true;
                        try {
                            map.removeLayer(tacticalLayer);
                            fallbackOsm.addTo(map);
                        } catch(e){}
                    }
                });

                tacticalLayer.addTo(map);

                L.circle([\(lat), \(lng)], {
                    radius: 350,
                    color: '\(pinColor)',
                    fillColor: '\(pinColor)',
                    fillOpacity: 0.14,
                    weight: 2,
                    dashArray: '4, 4'
                }).addTo(map);

                var iconHtml = '<div style="position:relative;"><div class="pulse-ring"></div><div class="incident-pin">!</div></div>';
                var icon = L.divIcon({
                    html: iconHtml,
                    className: 'custom-incident-marker',
                    iconSize: [24, 24],
                    iconAnchor: [12, 12]
                });
                L.marker([\(lat), \(lng)], { icon: icon }).addTo(map);
            </script>
        </body>
        </html>
        """
        webView.loadHTMLString(html, baseURL: URL(string: "https://sisbom.com"))
    }
}

