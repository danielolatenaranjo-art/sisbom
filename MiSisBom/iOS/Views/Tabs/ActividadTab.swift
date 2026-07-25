import SwiftUI

struct ActividadTab: View {
    @ObservedObject var viewModel: SisBomViewModel

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
            VStack(spacing: 16) {
                // Availability Panel Card
                GlassCard(viewModel: viewModel) {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("ESTADO DE DISPONIBILIDAD")
                            .font(.system(size: 11, weight: .black))
                            .foregroundColor(isDark ? Color.textSecondaryDark : .textSecondary)
                        
                        HStack(spacing: 12) {
                            // Button 0-9
                            StatusButton(
                                title: "0-9",
                                subtitle: "DISPONIBLE",
                                icon: "checkmark.circle.fill",
                                isActive: is09Active,
                                isEnabled: is09Enabled,
                                activeColor: .goGreen,
                                inactiveColor: .goGreen,
                                onClick: {
                                    viewModel.changeStatus(newStatus: "0-9")
                                }
                            )
                            
                            // Button 0-8
                            StatusButton(
                                title: "0-8",
                                subtitle: "NO DISPONIBLE",
                                icon: "xmark.circle.fill",
                                isActive: is08Active,
                                isEnabled: is08Enabled,
                                activeColor: .bomberosRed,
                                inactiveColor: .bomberosRed,
                                onClick: {
                                    viewModel.changeStatus(newStatus: "0-8")
                                }
                            )
                        }
                    }
                    .padding(16)
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
                
                // Section Title
                HStack {
                    Text("EMERGENCIAS ACTIVAS")
                        .font(.system(size: 14, weight: .black))
                        .foregroundColor(isDark ? .white : .textDark)
                    Spacer()
                }
                .padding(.horizontal, 16)
                
                // Emergencies Content
                if activeDispatches.isEmpty {
                    // No Incidents Card
                    GlassCard(viewModel: viewModel) {
                        VStack(spacing: 16) {
                            ZStack {
                                Circle()
                                    .fill(Color.white.opacity(0.15))
                                    .frame(width: 64, height: 64)
                                    .overlay(
                                        Circle()
                                            .stroke(isDark ? Color.white.opacity(0.2) : Color.black.opacity(0.1), lineWidth: 1)
                                    )
                                
                                Image(systemName: "checkmark.circle.fill")
                                    .font(.title)
                                    .foregroundColor(isDark ? .white : .textDark)
                            }
                            
                            Text("Sin emergencias activas en este momento")
                                .font(.system(size: 15, weight: .black))
                                .foregroundColor(isDark ? .white : .textDark)
                        }
                        .padding(.vertical, 24)
                        .frame(maxWidth: .infinity)
                    }
                    .padding(.horizontal, 16)
                } else {
                    // List of Incidents
                    ForEach(activeDispatches) { dispatch in
                        DispatchItemCard(dispatch: dispatch, viewModel: viewModel)
                            .padding(.horizontal, 16)
                    }
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

// MARK: - DispatchItemCard Component
struct DispatchItemCard: View {
    let dispatch: Dispatch
    @ObservedObject var viewModel: SisBomViewModel

    var body: some View {
        let isDark = viewModel.isDarkMode
        let user = viewModel.currentUser
        let isAttending = user?.enServicio == dispatch.idServicio
        let baseState = user?.estado.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
        let inService = !(user?.enServicio.trimmingCharacters(in: .whitespacesAndNewlines) == "0") && !(user?.enServicio.isEmpty ?? true)
        let isSpecial = baseState.contains("SUSPENDIDO") || baseState == "CDS" || baseState.contains("LICENCIA") || baseState == "PERMISO"
        
        let cardBorderColor = isAttending ? Color.goGreen : (isDark ? Color.bomberosRed : Color.bomberosRedLight.opacity(0.5))
        let titleText = isAttending ? "SALIENDO A SERVICIO" : "¡DESPACHO ACTIVO!"
        let titleColor = isAttending ? Color.goGreen : Color.bomberosRedLight
        
        GlassCard(viewModel: viewModel) {
            VStack(alignment: .leading, spacing: 0) {
                // Header row
                HStack {
                    Text(titleText)
                        .font(.system(size: 11, weight: .black))
                        .foregroundColor(titleColor)
                    
                    Spacer()
                    
                    Text(dispatch.horaDespacho.isEmpty ? "--:--" : dispatch.horaDespacho)
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(isDark ? Color.black.opacity(0.4) : Color.black.opacity(0.06))
                        .cornerRadius(4)
                }
                
                Spacer().frame(height: 8)
                
                // Clave & Location
                Text(dispatch.clave)
                    .font(.system(size: 30, weight: .black))
                    .foregroundColor(isDark ? .white : .textDark)
                    .lineLimit(1)
                
                Text(dispatch.lugar.isEmpty ? "Ubicación no precisada" : dispatch.lugar)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(isDark ? Color(red: 0.8, green: 0.84, blue: 0.88) : Color(red: 0.28, green: 0.33, blue: 0.41))
                    .lineLimit(2)
                
                Spacer().frame(height: 12)
                
                // Pre-informe and vehicles subcard
                VStack(alignment: .leading, spacing: 8) {
                    Text("PRE-INFORME:")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                    
                    Text(dispatch.preinforme.isEmpty ? "A la espera de pre-informe oficial de primera máquina." : dispatch.preinforme)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(isDark ? .white : .textDark)
                    
                    Divider()
                        .background(isDark ? Color.white.opacity(0.1) : Color.black.opacity(0.1))
                        .padding(.vertical, 4)
                    
                    Text("UNIDADES DESPACHADAS:")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                    
                    Text(dispatch.carros.isEmpty ? "---" : dispatch.carros)
                        .font(.system(size: 15, weight: .black))
                        .foregroundColor(isDark ? Color.bomberosRedLight : Color.bomberosRed)
                }
                .padding(12)
                .background(isDark ? Color.black.opacity(0.3) : Color.white.opacity(0.1))
                .cornerRadius(12)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(isDark ? Color.white.opacity(0.05) : Color.black.opacity(0.05), lineWidth: 1)
                )
                
                // Control de emergencia (6-7) notice if present
                if !dispatch.hora67.isEmpty {
                    Spacer().frame(height: 12)
                    VStack(spacing: 2) {
                        Text("✓ CONTROL DE EMERGENCIA (6-7)")
                            .font(.system(size: 9, weight: .black))
                        Text(dispatch.hora67)
                            .font(.system(size: 12, weight: .bold))
                    }
                    .padding(8)
                    .frame(maxWidth: .infinity)
                    .foregroundColor(isDark ? Color.goGreen : Color(red: 0.02, green: 0.59, blue: 0.41))
                    .background(isDark ? Color.goGreen.opacity(0.15) : Color.goGreen.opacity(0.08))
                    .cornerRadius(8)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color.goGreen.opacity(0.3), lineWidth: 1)
                    )
                }
                
                Spacer().frame(height: 12)
                
                // Attendance Action Buttons
                if isAttending {
                    Button(action: {
                        viewModel.attendService(dispatchId: dispatch.idServicio, attend: false)
                    }) {
                        Text("CANCELAR ASISTENCIA")
                            .font(.system(size: 12, weight: .black))
                            .frame(maxWidth: .infinity)
                            .frame(height: 44)
                            .foregroundColor(.bomberosRed)
                            .background(isDark ? Color.bomberosRed.opacity(0.15) : Color.bomberosRed.opacity(0.08))
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.bomberosRed.opacity(0.5), lineWidth: 1)
                            )
                    }
                } else {
                    let canAttend = baseState == "0-9" && !inService && !isSpecial
                    
                    Button(action: {
                        viewModel.attendService(dispatchId: dispatch.idServicio, attend: true)
                    }) {
                        HStack(spacing: 6) {
                            Image(systemName: "checkmark.circle.fill")
                            Text("TRIPULAR / ASISTIR")
                                .font(.system(size: 12, weight: .black))
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 44)
                        .foregroundColor(canAttend ? .white : (isDark ? Color.white.opacity(0.3) : .textSecondary))
                        .background(canAttend ? Color.goGreen : (isDark ? Color.white.opacity(0.05) : Color.black.opacity(0.06)))
                        .cornerRadius(12)
                    }
                    .disabled(!canAttend)
                }
            }
            .padding(16)
        }
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(cardBorderColor, lineWidth: 1.5)
        )
    }
}
