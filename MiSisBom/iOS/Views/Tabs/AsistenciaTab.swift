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
        let todayDay = calendar.component(.day, from: today)
        let todayYear = calendar.component(.year, from: today)
        let currentCycleYear = todayMonth == 12 ? todayYear + 1 : todayYear
        let isDecember8th = todayMonth == 12 && todayDay == 8

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

        let prevCycleHistory = historyWithCycle.filter { $0.1 == (currentCycleYear - 1) }.map { $0.0 }
        let prevCycleStats = calculateCycleStats(history: prevCycleHistory)

        let displayedHistory = currentCycleHistory.filter { h in
            let st = h.userEstado.isEmpty ? "FALTA" : h.userEstado.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
            let isAbono = isAbonoValue(value: h.userAbono, clave: h.clave)
            let isPresent = st == "A" || st == "ASISTE" || st == "CDS"
            return !(isAbono && !isPresent)
        }

        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("CONTROL DE ASISTENCIAS")
                    .font(.system(size: 16, weight: .black))
                    .foregroundColor(isDark ? .white : .textDark)
                    .padding(.top, 16)
                    .padding(.horizontal, 16)

                if isDecember8th {
                    // Card 1: Finalized Previous Cycle on Dec 8
                    AttendanceCycleCard(
                        title: "CICLO ANTERIOR FINALIZADO",
                        subtitle: "8 DE DICIEMBRE",
                        pctAsist: prevCycleStats.pct,
                        totalObligatorias: prevCycleStats.totalObligatorias,
                        totalAbonosAsiste: prevCycleStats.totalAbonosAsiste,
                        showRights: true,
                        viewModel: viewModel
                    )
                    .padding(.horizontal, 16)

                    // Card 2: New ongoing cycle
                    AttendanceCycleCard(
                        title: "NUEVO CICLO EN CURSO",
                        subtitle: "CICLO \(currentCycleYear)",
                        pctAsist: currentCycleStats.pct,
                        totalObligatorias: currentCycleStats.totalObligatorias,
                        totalAbonosAsiste: currentCycleStats.totalAbonosAsiste,
                        showRights: false,
                        viewModel: viewModel
                    )
                    .padding(.horizontal, 16)
                } else {
                    // Card 1: Current Annual Cycle
                    AttendanceCycleCard(
                        title: "ASISTENCIA ANUAL",
                        subtitle: "CICLO \(currentCycleYear)",
                        pctAsist: currentCycleStats.pct,
                        totalObligatorias: currentCycleStats.totalObligatorias,
                        totalAbonosAsiste: currentCycleStats.totalAbonosAsiste,
                        showRights: false,
                        viewModel: viewModel
                    )
                    .padding(.horizontal, 16)

                    // Card 2: Previous Cycle Card with Statutory Rights (Vota / Cargo)
                    if !prevCycleHistory.isEmpty {
                        AttendanceCycleCard(
                            title: "CICLO ANTERIOR",
                            subtitle: "CICLO \(currentCycleYear - 1)",
                            pctAsist: prevCycleStats.pct,
                            totalObligatorias: prevCycleStats.totalObligatorias,
                            totalAbonosAsiste: prevCycleStats.totalAbonosAsiste,
                            showRights: true,
                            viewModel: viewModel
                        )
                        .padding(.horizontal, 16)
                    }
                }

                // History Header
                Text("HISTORIAL DE ACTIVIDADES (\(currentCycleYear))")
                    .font(.system(size: 14, weight: .black))
                    .foregroundColor(isDark ? .white : .textDark)
                    .padding(.top, 8)
                    .padding(.horizontal, 16)

                // History List
                if displayedHistory.isEmpty {
                    VStack {
                        Spacer().frame(height: 60)
                        Text("No registra historial de asistencias en este ciclo.")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                } else {
                    LazyVStack(spacing: 12) {
                        ForEach(displayedHistory) { item in
                            AttendanceItemRow(item: item, viewModel: viewModel)
                        }
                    }
                    .padding(.horizontal, 16)
                }

                Spacer(minLength: 20)
            }
        }
    }
}

// MARK: - AttendanceCycleCard Component
struct AttendanceCycleCard: View {
    let title: String
    let subtitle: String
    let pctAsist: Double
    let totalObligatorias: Int
    let totalAbonosAsiste: Int
    let showRights: Bool
    @ObservedObject var viewModel: SisBomViewModel

    var body: some View {
        let isDark = viewModel.isDarkMode
        let canVote = pctAsist >= 30.0
        let canHoldCargo = pctAsist >= 40.0

        GlassCard(viewModel: viewModel) {
            VStack(spacing: 12) {
                HStack(spacing: 16) {
                    AttendanceCircle(percentage: Int(pctAsist.rounded()), size: 80, strokeWidth: 8, isDarkTheme: isDark)

                    VStack(alignment: .leading, spacing: 6) {
                        Text(title)
                            .font(.system(size: 16, weight: .black))
                            .foregroundColor(isDark ? .white : .textDark)

                        Text(subtitle)
                            .font(.system(size: 10, weight: .black))
                            .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)

                        HStack(spacing: 8) {
                            // Obligatorias count
                            VStack(spacing: 2) {
                                Text("\(totalObligatorias)")
                                    .font(.system(size: 14, weight: .black))
                                    .foregroundColor(isDark ? .white : .textDark)
                                Text("OBLIGATORIAS")
                                    .font(.system(size: 8, weight: .black))
                                    .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                            .background(isDark ? Color(red: 0.12, green: 0.12, blue: 0.18).opacity(0.5) : Color(red: 0.95, green: 0.96, blue: 0.98))
                            .cornerRadius(8)

                            // Abono count
                            VStack(spacing: 2) {
                                Text("\(totalAbonosAsiste)")
                                    .font(.system(size: 14, weight: .black))
                                    .foregroundColor(isDark ? .white : .textDark)
                                Text("ABONO")
                                    .font(.system(size: 8, weight: .black))
                                    .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                            .background(isDark ? Color(red: 0.12, green: 0.12, blue: 0.18).opacity(0.5) : Color(red: 0.95, green: 0.96, blue: 0.98))
                            .cornerRadius(8)
                        }
                    }
                }

                if showRights {
                    HStack(spacing: 8) {
                        // Voto Badge
                        Text(canVote ? "DERECHO VOTO: SÍ" : "DERECHO VOTO: NO")
                            .font(.system(size: 9, weight: .heavy))
                            .foregroundColor(canVote ? .goGreen : .bomberosRed)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                            .background((canVote ? Color.goGreen : Color.bomberosRed).opacity(0.15))
                            .cornerRadius(6)
                            .overlay(
                                RoundedRectangle(cornerRadius: 6)
                                    .stroke((canVote ? Color.goGreen : Color.bomberosRed).opacity(0.4), lineWidth: 1)
                            )

                        // Cargo Badge
                        Text(canHoldCargo ? "DERECHO CARGO: SÍ" : "DERECHO CARGO: NO")
                            .font(.system(size: 9, weight: .heavy))
                            .foregroundColor(canHoldCargo ? .goGreen : .bomberosRed)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                            .background((canHoldCargo ? Color.goGreen : Color.bomberosRed).opacity(0.15))
                            .cornerRadius(6)
                            .overlay(
                                RoundedRectangle(cornerRadius: 6)
                                    .stroke((canHoldCargo ? Color.goGreen : Color.bomberosRed).opacity(0.4), lineWidth: 1)
                            )
                    }
                }
            }
            .padding(14)
        }
    }
}

// MARK: - AttendanceCircle Gauge Ring
struct AttendanceCircle: View {
    let percentage: Int
    var size: CGFloat = 100
    var strokeWidth: CGFloat = 10
    let isDarkTheme: Bool

    var body: some View {
        let trackColor = isDarkTheme ? Color.white.opacity(0.1) : Color(red: 0.88, green: 0.91, blue: 0.94)
        let progressColor = percentage >= 50 ? Color.goGreen : Color.bomberosRed
        
        ZStack {
            // Background track circle
            Circle()
                .stroke(trackColor, style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round))
                .frame(width: size, height: size)
            
            // Progress arc circle
            Circle()
                .trim(from: 0.0, to: CGFloat(min(self.percentage, 100)) / 100.0)
                .stroke(progressColor, style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round))
                .frame(width: size, height: size)
                .rotationEffect(Angle(degrees: -90))
                .animation(.linear(duration: 0.5), value: percentage)
            
            // Percentage Text
            VStack(spacing: 1) {
                Text("\(percentage)%")
                    .font(.system(size: 20, weight: .black))
                    .foregroundColor(isDarkTheme ? .white : .textDark)
                
                Text("Asistencia")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundColor(isDarkTheme ? .textSecondaryDark : .textSecondary)
            }
        }
    }
}

// MARK: - AttendanceItemRow Component
struct AttendanceItemRow: View {
    let item: AttendanceSheet
    @ObservedObject var viewModel: SisBomViewModel

    var body: some View {
        let isDark = viewModel.isDarkMode
        let cleanStatus: String = {
            switch item.userEstado.uppercased().trimmingCharacters(in: .whitespacesAndNewlines) {
            case "A", "ASISTE": return "ASISTE"
            case "F", "FALTA": return "FALTA"
            case "L", "LICENCIA": return "LICENCIA"
            case "P", "PERMISO": return "PERMISO"
            case "S", "SUSPENDIDO": return "SUSPENDIDO"
            case "CDS": return "CDS"
            default: return item.userEstado.uppercased()
            }
        }()
        
        let badgeColor: Color = {
            switch cleanStatus {
            case "ASISTE", "CDS": return .goGreen
            case "FALTA": return .bomberosRed
            case "PERMISO": return .alertAmber
            case "LICENCIA": return .infoBlue
            case "SUSPENDIDO": return .purple
            default: return .gray
            }
        }()
        
        GlassCard(viewModel: viewModel) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.clave)
                        .font(.system(size: 14, weight: .black))
                        .foregroundColor(isDark ? .white : .textDark)
                    
                    Text("\(item.fecha) - \(item.hora) | \(item.lugar)")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                        .lineLimit(1)
                }
                
                Spacer()
                
                Text(cleanStatus)
                    .font(.system(size: 10, weight: .black))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .foregroundColor(badgeColor)
                    .background(badgeColor.opacity(0.15))
                    .cornerRadius(8)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(badgeColor.opacity(0.5), lineWidth: 1)
                    )
            }
            .padding(14)
        }
    }
}
