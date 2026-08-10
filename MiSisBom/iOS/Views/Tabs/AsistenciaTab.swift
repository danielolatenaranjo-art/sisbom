import SwiftUI

struct AsistenciaTab: View {
    @ObservedObject var viewModel: SisBomViewModel

    var body: some View {
        let isDark = viewModel.isDarkMode
        
        let myHistory = viewModel.attendanceList.filter { row in
            !row.userEstado.isEmpty &&
            !row.aprobadoPor.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !row.anulada
        }.sorted(by: {
            let idA = Int($0.idLista) ?? 0
            let idB = Int($1.idLista) ?? 0
            return idA > idB
        })
        
        let displayedHistory = myHistory.filter { h in
            let st = h.userEstado.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
            let isAbono = h.userAbono == 1.0 || h.clave.uppercased().contains("ABONO")
            if isAbono && st != "A" && st != "ASISTE" {
                return false
            }
            return true
        }
        
        // Calculate statistics
        let stats: (mandatoryCount: Int, attendedCount: Int, totalObligatorias: Int, totalAbonosAsiste: Int) = {
            var mCount = 0
            var aCount = 0
            var tOblig = 0
            var tAbonos = 0
            
            for h in displayedHistory {
                let st = h.userEstado.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)
                let isAbono = h.userAbono == 1.0 || h.clave.uppercased().contains("ABONO")
                
                if !isAbono {
                    tOblig += 1
                    if st == "A" || st == "ASISTE" {
                        aCount += 1
                        mCount += 1
                    } else if st == "F" || st == "FALTA" {
                        mCount += 1
                    }
                } else if st == "A" || st == "ASISTE" {
                    tAbonos += 1
                    aCount += 1
                }
            }
            return (mCount, aCount, tOblig, tAbonos)
        }()
        
        let mandatoryCount = stats.mandatoryCount
        let attendedCount = stats.attendedCount
        let totalObligatorias = stats.totalObligatorias
        let totalAbonosAsiste = stats.totalAbonosAsiste
        
        let pctAsist = mandatoryCount > 0 ? Int((Double(attendedCount) / Double(mandatoryCount)) * 100.0) : 0
        
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("CONTROL DE ASISTENCIAS")
                    .font(.system(size: 16, weight: .black))
                    .foregroundColor(isDark ? .white : .textDark)
                    .padding(.top, 16)
                    .padding(.horizontal, 16)
                
                // Summary Card with Gauge Ring
                GlassCard(viewModel: viewModel) {
                    HStack(spacing: 24) {
                        AttendanceCircle(percentage: pctAsist, isDarkTheme: isDark)
                        
                        VStack(alignment: .leading, spacing: 8) {
                            Text("RESUMEN ANUAL")
                                .font(.system(size: 11, weight: .black))
                                .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                            
                            Text("Listas Obligatorias: \(totalObligatorias)")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(isDark ? .white : .textDark)
                            
                            Text("Abonos Asistidos: \(totalAbonosAsiste)")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(.goGreen)
                        }
                        
                        Spacer()
                    }
                    .padding(14)
                }
                .padding(.horizontal, 16)
                
                // History Header
                Text("HISTORIAL DE ACTIVIDADES")
                    .font(.system(size: 14, weight: .black))
                    .foregroundColor(isDark ? .white : .textDark)
                    .padding(.top, 8)
                    .padding(.horizontal, 16)
                
                // History List
                if displayedHistory.isEmpty {
                    VStack {
                        Spacer().frame(height: 80)
                        Text("No registra historial de asistencias este año.")
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
            default: return item.userEstado.uppercased()
            }
        }()
        
        let badgeColor: Color = {
            switch cleanStatus {
            case "ASISTE": return .goGreen
            case "FALTA": return .bomberosRed
            case "PERMISO": return .alertAmber
            case "LICENCIA": return .infoBlue
            case "SUSPENDIDO": return .purple
            default: return .gray
            }
        }()
        
        let cleanListId: String = {
            let raw = item.idLista.trimmingCharacters(in: .whitespacesAndNewlines)
            if raw.count > 4 && (raw.hasPrefix("202") || raw.hasPrefix("203")) {
                return "#" + String(raw.dropFirst(4))
            }
            return "#" + raw
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
