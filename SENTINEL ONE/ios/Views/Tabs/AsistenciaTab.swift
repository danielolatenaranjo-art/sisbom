import SwiftUI

// MARK: - Cycle Statistics Model
struct CycleStats {
    let pct: Double
    let totalObligatorias: Int
    let obligatoriasAsistidas: Int
    let totalAbonosAsiste: Int
}

// MARK: - Date Helpers
func parseDateToDate(fechaStr: String) -> Date? {
    let clean = fechaStr.trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: "\"", with: "")
        .replacingOccurrences(of: "'", with: "")
        .replacingOccurrences(of: "/", with: "-")
    let formats = ["dd-MM-yyyy", "d-M-yyyy", "dd-MM-yy", "d-M-yy", "yyyy-MM-dd", "yyyy-M-d"]
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "es_CL")
    for fmt in formats {
        formatter.dateFormat = fmt
        if let d = formatter.date(from: clean) {
            return d
        }
    }
    return nil
}

func getCycleYear(date: Date) -> Int {
    let calendar = Calendar.current
    let month = calendar.component(.month, from: date)
    let year = calendar.component(.year, from: date)
    return month == 12 ? year + 1 : year
}

func isAbonoValue(value: Any?, clave: String = "", tipo: String = "") -> Bool {
    if let b = value as? Bool { return b }
    if let n = value as? NSNumber { return n.intValue == 1 || n.doubleValue == 1.0 }
    if let d = value as? Double { return d == 1.0 }
    let str = String(describing: value ?? "").uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
    if str == "SÍ" || str == "SI" || str == "S" || str == "TRUE" || str == "1" || str == "1.0" || str.contains("ABONO") || str.contains("EXTRA") { return true }
    if clave.uppercased().contains("ABONO") || clave.uppercased().contains("EXTRA") { return true }
    if tipo.uppercased().contains("ABONO") || tipo.uppercased().contains("EXTRA") { return true }
    return false
}

func calculateCycleStats(history: [AttendanceSheet]) -> CycleStats {
    let filtered = history.filter { h in
        let st = h.userEstado.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        if st.isEmpty || st == "NO_REGISTRA" || st == "NO REGISTRA" { return false }
        let isAbono = isAbonoValue(value: h.userAbono, clave: h.clave, tipo: h.tipo)
        let isPresent = st == "A" || st == "ASISTE" || st == "CDS"
        return !(isAbono && !isPresent)
    }

    var obligatoriasAsistidas = 0
    var totalObligatorias = 0
    var totalAbonosAsiste = 0

    for h in filtered {
        let st = h.userEstado.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        let isAbono = isAbonoValue(value: h.userAbono, clave: h.clave, tipo: h.tipo)
        let isPresent = st == "A" || st == "ASISTE" || st == "CDS"
        if !isAbono {
            totalObligatorias += 1
            if isPresent {
                obligatoriasAsistidas += 1
            }
        } else {
            if isPresent {
                totalAbonosAsiste += 1
            }
        }
    }

    let factor = Double(totalObligatorias) / 100.0
    let suma = Double(obligatoriasAsistidas + totalAbonosAsiste)
    let pctAsist: Double = {
        if factor > 0.0 {
            return min(100.0, suma / factor)
        } else {
            return totalAbonosAsiste > 0 ? 100.0 : 0.0
        }
    }()

    return CycleStats(
        pct: pctAsist,
        totalObligatorias: totalObligatorias,
        obligatoriasAsistidas: obligatoriasAsistidas,
        totalAbonosAsiste: totalAbonosAsiste
    )
}

struct AsistenciaTab: View {
    @ObservedObject var viewModel: SisBomViewModel
    var topPadding: CGFloat = 0
    var bottomPadding: CGFloat = 0
    @State private var selectedAttendance: AttendanceSheet? = nil

    var body: some View {
        let isDark = viewModel.isDarkMode
        
        let myHistory = viewModel.attendanceList.filter { row in
            !row.aprobadoPor.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !row.anulada
        }.sorted(by: {
            let idA = Int($0.idLista) ?? 0
            let idB = Int($1.idLista) ?? 0
            return idA > idB
        })

        let calendar = Calendar.current
        let today = Date()
        let todayMonth = calendar.component(.month, from: today)
        let todayYear = calendar.component(.year, from: today)
        let currentCycleYear = todayMonth == 12 ? todayYear + 1 : todayYear

        let historyWithCycle: [(AttendanceSheet, Int)] = myHistory.compactMap { row in
            if let date = parseDateToDate(fechaStr: row.fecha) {
                let cycleYear = getCycleYear(date: date)
                if row.idLista.hasPrefix(String(cycleYear)) {
                    return (row, cycleYear)
                }
                return nil
            } else {
                if row.idLista.hasPrefix(String(currentCycleYear)) {
                    return (row, currentCycleYear)
                }
                return nil
            }
        }

        let currentCycleHistory = historyWithCycle.filter { $0.1 == currentCycleYear }.map { $0.0 }
        let currentCycleStats = calculateCycleStats(history: currentCycleHistory)

        let displayedHistory = currentCycleHistory.filter { h in
            let st = h.userEstado.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
            if st.isEmpty || st == "NO_REGISTRA" || st == "NO REGISTRA" { return false }
            let isAbono = isAbonoValue(value: h.userAbono, clave: h.clave, tipo: h.tipo)
            let isPresent = st == "A" || st == "ASISTE" || st == "CDS"
            return !(isAbono && !isPresent)
        }

        ZStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    // Top Annual Summary Card (Exact ANDROID 5.jpeg)
                    AnnualAttendanceHeaderCard(
                        cycleYear: currentCycleYear,
                        stats: currentCycleStats,
                        isDark: isDark
                    )
                    .padding(.horizontal, 8)

                    // Historial Section Header with Sincronizar Button
                    HStack {
                        Text("HISTORIAL")
                            .font(.system(size: 14, weight: .black))
                            .foregroundColor(isDark ? .white : .textDark)
                        
                        Spacer()
                        
                        Button(action: {
                            viewModel.refreshAttendance()
                        }) {
                            HStack(spacing: 4) {
                                Image(systemName: "arrow.triangle.2.circlepath")
                                    .font(.system(size: 12, weight: .bold))
                                Text("Sincronizar")
                                    .font(.system(size: 12, weight: .bold))
                            }
                            .foregroundColor(Color(red: 0.90, green: 0.20, blue: 0.20))
                        }
                    }
                    .padding(.horizontal, 8)
                    .padding(.top, 4)

                    // 2-Column Grid of History Items (Exact ANDROID 5.jpeg)
                    if displayedHistory.isEmpty {
                        VStack {
                            Spacer().frame(height: 60)
                            Text("No registra historial de asistencias en este ciclo.")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(.textSecondary)
                                .frame(maxWidth: .infinity, alignment: .center)
                        }
                    } else {
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                            ForEach(displayedHistory) { item in
                                Button(action: {
                                    selectedAttendance = item
                                }) {
                                    AttendanceGridCard(item: item, isDark: isDark)
                                }
                                .buttonStyle(PlainButtonStyle())
                            }
                        }
                        .padding(.horizontal, 8)
                    }

                    Spacer(minLength: 20)
                }
                .padding(.top, topPadding)
                .padding(.bottom, bottomPadding)
            }

            // Attendance Detail Dialog (Exact Android Parity)
            if let selectedItem = selectedAttendance {
                DetalleAsistenciaDialog(item: selectedItem, isDark: isDark, onDismiss: {
                    selectedAttendance = nil
                })
                .zIndex(100)
                .transition(.opacity.combined(with: .scale(scale: 0.95)))
            }
        }
    }
}

// MARK: - AnnualAttendanceHeaderCard Component (Exact ANDROID 5.jpeg)
struct AnnualAttendanceHeaderCard: View {
    let cycleYear: Int
    let stats: CycleStats
    let isDark: Bool

    var body: some View {
        HStack(spacing: 12) {
            // Circular Progress Gauge
            AttendanceCircle(percentage: stats.pct, size: 68, strokeWidth: 7, isDarkTheme: isDark)

            // Titles
            VStack(alignment: .leading, spacing: 3) {
                Text("Asistencia \(cycleYear)")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(isDark ? .white : .textDark)
                    .lineLimit(1)

                Text("RENDIMIENTO ANUAL EN CURSO")
                    .font(.system(size: 8.5, weight: .bold))
                    .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                    .lineLimit(1)
            }

            Spacer()

            // Count Badges
            HStack(spacing: 6) {
                // Asiste Box
                VStack(spacing: 2) {
                    Text("\(stats.obligatoriasAsistidas) de \(stats.totalObligatorias)")
                        .font(.system(size: 11, weight: .black))
                        .foregroundColor(isDark ? .white : .textDark)
                    Text("ASISTE")
                        .font(.system(size: 7.5, weight: .bold))
                        .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                }
                .frame(minWidth: 54)
                .padding(.horizontal, 6)
                .padding(.vertical, 6)
                .background(isDark ? Color.white.opacity(0.06) : Color(red: 0.96, green: 0.97, blue: 0.99))
                .cornerRadius(10)
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(isDark ? Color.white.opacity(0.1) : Color(red: 0.88, green: 0.90, blue: 0.94), lineWidth: 1)
                )

                // Abono Box
                VStack(spacing: 2) {
                    Text("\(stats.totalAbonosAsiste)")
                        .font(.system(size: 11, weight: .black))
                        .foregroundColor(isDark ? .white : .textDark)
                    Text("ABONOS")
                        .font(.system(size: 7.5, weight: .bold))
                        .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                }
                .frame(minWidth: 44)
                .padding(.horizontal, 6)
                .padding(.vertical, 6)
                .background(isDark ? Color.white.opacity(0.06) : Color(red: 0.96, green: 0.97, blue: 0.99))
                .cornerRadius(10)
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(isDark ? Color.white.opacity(0.1) : Color(red: 0.88, green: 0.90, blue: 0.94), lineWidth: 1)
                )
            }
        }
        .padding(14)
        .background(isDark ? Color.navyDark : Color.white)
        .cornerRadius(20)
        .shadow(color: isDark ? Color.clear : Color.black.opacity(0.04), radius: 6, x: 0, y: 2)
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(isDark ? Color.white.opacity(0.1) : Color(red: 0.85, green: 0.94, blue: 0.90), lineWidth: 1.5)
        )
    }
}

// MARK: - AttendanceCircle Gauge Ring
struct AttendanceCircle: View {
    let percentage: Double
    var size: CGFloat = 68
    var strokeWidth: CGFloat = 7
    let isDarkTheme: Bool

    var body: some View {
        let trackColor = isDarkTheme ? Color.white.opacity(0.1) : Color(red: 0.88, green: 0.91, blue: 0.94)
        let progressColor = percentage >= 50 ? Color(red: 0.02, green: 0.59, blue: 0.41) : Color.bomberosRed
        
        ZStack {
            // Background track circle
            Circle()
                .stroke(trackColor, style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round))
                .frame(width: size, height: size)
            
            // Progress arc circle
            Circle()
                .trim(from: 0.0, to: CGFloat(min(max(self.percentage, 0.0), 100.0)) / 100.0)
                .stroke(progressColor, style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round))
                .frame(width: size, height: size)
                .rotationEffect(Angle(degrees: -90))
                .animation(.linear(duration: 0.5), value: percentage)
            
            // Percentage Text
            Text(String(format: "%.2f%%", percentage))
                .font(.system(size: 9.5, weight: .black))
                .foregroundColor(isDarkTheme ? .white : .textDark)
        }
    }
}

// MARK: - AttendanceGridCard Component (Exact ANDROID 5.jpeg 2-Column Grid Card)
struct AttendanceGridCard: View {
    let item: AttendanceSheet
    let isDark: Bool

    var body: some View {
        let rawStatus = item.userEstado.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanStatus: String = {
            switch rawStatus {
            case "A", "ASISTE": return "ASISTE"
            case "F", "FALTA", "": return "FALTA"
            case "L", "LICENCIA": return "LICENCIA"
            case "P", "PERMISO": return "PERMISO"
            case "S", "SUSPENDIDO": return "SUSPENDIDO"
            case "CDS": return "CDS"
            default: return rawStatus.isEmpty ? "FALTA" : rawStatus
            }
        }()

        let statusText: String = {
            switch cleanStatus {
            case "ASISTE", "CDS": return "✓ ASISTE"
            case "FALTA": return "✗ FALTA"
            case "PERMISO": return "✗ PERMISO"
            case "LICENCIA": return "✗ LICENCIA"
            case "SUSPENDIDO": return "✗ SUSPENDIDO"
            default: return cleanStatus
            }
        }()

        let badgeColor: Color = {
            switch cleanStatus {
            case "ASISTE", "CDS": return Color(red: 0.063, green: 0.725, blue: 0.506)
            case "FALTA", "SUSPENDIDO": return Color.bomberosRed
            case "PERMISO": return Color.alertAmber
            case "LICENCIA": return Color.infoBlue
            default: return Color.gray
            }
        }()

        let isAbono = isAbonoValue(value: item.userAbono, clave: item.clave, tipo: item.tipo)

        // Solid dense background (exact Android parity: solid dark slate base + dense tint)
        let baseBg = isDark ? Color(red: 0.12, green: 0.16, blue: 0.23) : Color.white
        let tintColor = badgeColor.opacity(isDark ? 0.26 : 0.14)
        let borderColor = badgeColor.opacity(0.38)

        VStack(alignment: .leading, spacing: 5) {
            // Clave & optional ABONO badge
            HStack(alignment: .center) {
                Text(item.clave)
                    .font(.system(size: 14, weight: .black))
                    .foregroundColor(isDark ? .white : .textDark)
                    .lineLimit(1)

                Spacer()

                if isAbono {
                    Text("ABONO")
                        .font(.system(size: 8, weight: .black))
                        .foregroundColor(.white)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(Color(red: 0.19, green: 0.18, blue: 0.51)) // #312E81
                        .cornerRadius(4)
                }
            }

            // Status Badge Text
            Text(statusText)
                .font(.system(size: 11, weight: .black))
                .foregroundColor(badgeColor)

            // Date & Time
            Text("\(item.fecha) \(item.hora)")
                .font(.system(size: 9, weight: .bold))
                .foregroundColor(isDark ? Color(red: 0.58, green: 0.64, blue: 0.72) : Color(red: 0.28, green: 0.33, blue: 0.41))
                .lineLimit(1)

            // Location
            Text(item.lugar.isEmpty ? "Cuartel General" : item.lugar)
                .font(.system(size: 9.5, weight: .semibold))
                .foregroundColor(isDark ? Color.white.opacity(0.8) : Color(red: 0.20, green: 0.25, blue: 0.32))
                .lineLimit(1)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            ZStack {
                baseBg
                tintColor
            }
        )
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(borderColor, lineWidth: 1.2)
        )
    }
}

// MARK: - DetalleAsistenciaDialog (Exact Android Alert Dialog)
struct DetalleAsistenciaDialog: View {
    let item: AttendanceSheet
    let isDark: Bool
    let onDismiss: () -> Void

    var body: some View {
        let rawStatus = item.userEstado.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanStatus: String = {
            switch rawStatus {
            case "A", "ASISTE": return "ASISTE"
            case "F", "FALTA", "": return "FALTA"
            case "L", "LICENCIA": return "LICENCIA"
            case "P", "PERMISO": return "PERMISO"
            case "S", "SUSPENDIDO": return "SUSPENDIDO"
            case "CDS": return "CDS"
            default: return rawStatus.isEmpty ? "FALTA" : rawStatus
            }
        }()

        let statusColor: Color = {
            switch cleanStatus {
            case "ASISTE", "CDS": return Color(red: 0.063, green: 0.725, blue: 0.506)
            case "FALTA", "SUSPENDIDO": return Color.bomberosRed
            case "PERMISO": return Color.alertAmber
            case "LICENCIA": return Color.infoBlue
            default: return Color.gray
            }
        }()

        let rawId = item.idLista.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanListId = (rawId.count > 4 && (rawId.hasPrefix("202") || rawId.hasPrefix("203")))
            ? "#" + String(rawId.dropFirst(4))
            : "#" + rawId

        let isAbono = isAbonoValue(value: item.userAbono, clave: item.clave, tipo: item.tipo)

        ZStack {
            // Backdrop
            Color.black.opacity(0.65)
                .ignoresSafeArea()
                .onTapGesture {
                    onDismiss()
                }

            // Dialog Card
            VStack(alignment: .leading, spacing: 14) {
                Text("Detalle de Asistencia")
                    .font(.system(size: 16, weight: .black))
                    .foregroundColor(isDark ? .white : .textDark)
                    .padding(.bottom, 2)

                VStack(spacing: 9) {
                    DetailRowView(label: "Clave", value: item.clave, isDark: isDark)
                    DetailRowView(label: "Nº Lista", value: cleanListId, isDark: isDark)
                    DetailRowView(label: "Estado", value: cleanStatus, valueColor: statusColor, isDark: isDark)
                    DetailRowView(label: "Fecha", value: item.fecha, isDark: isDark)
                    DetailRowView(label: "Hora", value: item.hora, isDark: isDark)
                    DetailRowView(label: "Lugar", value: item.lugar.isEmpty ? "Cuartel General" : item.lugar, isDark: isDark)
                    DetailRowView(label: "Abono", value: isAbono ? "Sí" : "No", isDark: isDark)

                    if !item.obac.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        DetailRowView(label: "OBAC", value: item.obac, isDark: isDark)
                    }

                    if !item.detalle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        DetailRowView(label: "Detalle", value: item.detalle, isDark: isDark)
                    }

                    if !item.aprobadoPor.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        DetailRowView(label: "Aprobado por", value: item.aprobadoPor, isDark: isDark)
                    }
                }

                HStack {
                    Spacer()
                    Button(action: onDismiss) {
                        Text("Cerrar")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.bomberosRed)
                            .padding(.top, 6)
                    }
                }
            }
            .padding(22)
            .frame(maxWidth: min(UIScreen.main.bounds.width - 48, 340))
            .background(isDark ? Color(red: 0.12, green: 0.16, blue: 0.23) : Color.white)
            .cornerRadius(18)
            .shadow(color: Color.black.opacity(0.4), radius: 16, x: 0, y: 8)
        }
    }
}

// MARK: - DetailRowView
struct DetailRowView: View {
    let label: String
    let value: String
    var valueColor: Color? = nil
    let isDark: Bool

    var body: some View {
        HStack(alignment: .top) {
            Text(label)
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(isDark ? Color(red: 0.58, green: 0.64, blue: 0.72) : Color(red: 0.39, green: 0.45, blue: 0.55))

            Spacer(minLength: 12)

            Text(value)
                .font(.system(size: 13, weight: .black))
                .foregroundColor(valueColor ?? (isDark ? .white : .textDark))
                .multilineTextAlignment(.trailing)
        }
    }
}
