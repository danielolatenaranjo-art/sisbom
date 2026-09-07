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

func isAbonoValue(value: Any?, clave: String) -> Bool {
    if let b = value as? Bool { return b }
    if let n = value as? NSNumber { return n.intValue == 1 }
    if let d = value as? Double { return d == 1.0 }
    let str = String(describing: value ?? "").uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
    return str == "SÍ" || str == "SI" || str == "S" || str == "TRUE" || str == "1" || clave.uppercased().contains("ABONO")
}

func calculateCycleStats(history: [AttendanceSheet]) -> CycleStats {
    let filtered = history.filter { h in
        let st = h.userEstado.isEmpty ? "FALTA" : h.userEstado.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        let isAbono = isAbonoValue(value: h.userAbono, clave: h.clave)
        let isPresent = st == "A" || st == "ASISTE" || st == "CDS"
        return !(isAbono && !isPresent)
    }

    var obligatoriasAsistidas = 0
    var totalObligatorias = 0
    var totalAbonosAsiste = 0

    for h in filtered {
        let st = h.userEstado.isEmpty ? "FALTA" : h.userEstado.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
        let isAbono = isAbonoValue(value: h.userAbono, clave: h.clave)
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
            let st = h.userEstado.isEmpty ? "FALTA" : h.userEstado.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
            let isAbono = isAbonoValue(value: h.userAbono, clave: h.clave)
            let isPresent = st == "A" || st == "ASISTE" || st == "CDS"
            return !(isAbono && !isPresent)
        }

        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Top Annual Summary Card (Exact ANDROID 5.jpeg)
                AnnualAttendanceHeaderCard(
                    cycleYear: currentCycleYear,
                    stats: currentCycleStats,
                    isDark: isDark
                )
                .padding(.horizontal, 16)

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
                .padding(.horizontal, 16)
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
                            AttendanceGridCard(item: item, isDark: isDark)
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
            case "F", "FALTA": return "FALTA"
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

        let statusColor: Color = {
            switch cleanStatus {
            case "ASISTE", "CDS": return Color(red: 0.02, green: 0.59, blue: 0.41)
            case "FALTA", "SUSPENDIDO": return Color(red: 0.85, green: 0.18, blue: 0.18)
            case "PERMISO": return Color(red: 0.85, green: 0.48, blue: 0.05)
            case "LICENCIA": return Color(red: 0.15, green: 0.45, blue: 0.85)
            default: return Color.gray
            }
        }()

        let cardBg: Color = {
            if isDark {
                switch cleanStatus {
                case "ASISTE", "CDS": return Color.goGreen.opacity(0.12)
                case "FALTA", "SUSPENDIDO": return Color.bomberosRed.opacity(0.12)
                case "PERMISO": return Color.alertAmber.opacity(0.12)
                default: return Color.white.opacity(0.06)
                }
            } else {
                switch cleanStatus {
                case "ASISTE", "CDS": return Color(red: 0.92, green: 0.98, blue: 0.95)
                case "FALTA", "SUSPENDIDO": return Color(red: 0.99, green: 0.93, blue: 0.93)
                case "PERMISO": return Color(red: 1.0, green: 0.97, blue: 0.91)
                default: return Color(red: 0.95, green: 0.96, blue: 0.98)
                }
            }
        }()

        let borderColor: Color = {
            if isDark {
                switch cleanStatus {
                case "ASISTE", "CDS": return Color.goGreen.opacity(0.35)
                case "FALTA", "SUSPENDIDO": return Color.bomberosRed.opacity(0.35)
                case "PERMISO": return Color.alertAmber.opacity(0.35)
                default: return Color.white.opacity(0.12)
                }
            } else {
                switch cleanStatus {
                case "ASISTE", "CDS": return Color(red: 0.65, green: 0.90, blue: 0.78)
                case "FALTA", "SUSPENDIDO": return Color(red: 0.96, green: 0.78, blue: 0.78)
                case "PERMISO": return Color(red: 0.98, green: 0.88, blue: 0.70)
                default: return Color(red: 0.88, green: 0.90, blue: 0.94)
                }
            }
        }()

        VStack(alignment: .leading, spacing: 4) {
            // Clave
            Text(item.clave)
                .font(.system(size: 14, weight: .black))
                .foregroundColor(isDark ? .white : .textDark)

            // Status Badge Text
            Text(statusText)
                .font(.system(size: 11, weight: .black))
                .foregroundColor(statusColor)

            // Date & Time
            Text("\(item.fecha) \(item.hora)")
                .font(.system(size: 9, weight: .bold))
                .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                .lineLimit(1)

            // Location
            Text(item.lugar.isEmpty ? "Cuartel General" : item.lugar)
                .font(.system(size: 9.5, weight: .semibold))
                .foregroundColor(isDark ? Color.white.opacity(0.7) : Color(red: 0.32, green: 0.38, blue: 0.45))
                .lineLimit(2)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(cardBg)
        .cornerRadius(14)
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(borderColor, lineWidth: 1.2)
        )
    }
}
