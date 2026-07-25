import SwiftUI

struct DespachoTab: View {
    @ObservedObject var viewModel: SisBomViewModel
    
    @State private var clave: String = ""
    @State private var lugar: String = ""
    @State private var preinforme: String = ""
    @State private var selectedVehicles: Set<String> = []
    @State private var showConfirmDialog: Bool = false
    
    let clavesRapidas = ["10-0", "10-1", "10-2", "10-3", "10-4", "10-5", "10-6", "10-7", "10-8", "10-9", "10-10", "10-12", "10-15", "9-0"]
    let lugaresSugeridos = ["Ruta 90", "El Corte", "Peñuelas", "San Luis", "Manantiales", "La Tuna", "Santa Isabel", "Taulemu", "Porvenir", "Cementerio", "Cruce Principal", "Grinvic", "Chacarillas", "La Dehesa Arriba", "La Dehesa Abajo", "Arica", "Camaron", "Cruce Principal", "Villa Alegre", "Villa La Torre", "Villa Carranza", "Villa San Francisco", "Villa Rucalemu", "Villa San Eduardo", "Villa Eben ezer", "Estadio"]

    var body: some View {
        let isDark = viewModel.isDarkMode
        let isCentral = viewModel.isCentralActive
        
        ZStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("CENTRAL DE ALARMAS - DESPACHO MÓVIL")
                        .font(.system(size: 15, weight: .black))
                        .foregroundColor(isDark ? .white : .textDark)
                        .padding(.top, 16)
                    
                    if !isCentral {
                        // Access Denied Card
                        GlassCard(viewModel: viewModel) {
                            VStack(spacing: 16) {
                                ZStack {
                                    Circle()
                                        .fill(Color.bomberosRed.opacity(0.1))
                                        .frame(width: 64, height: 64)
                                        .overlay(
                                            Circle()
                                                .stroke(Color.bomberosRed.opacity(0.3), lineWidth: 1)
                                        )
                                    
                                    Image(systemName: "info.circle.fill")
                                        .font(.title)
                                        .foregroundColor(.bomberosRed)
                                }
                                
                                Text("ACCESO RESTRINGIDO")
                                    .font(.system(size: 15, weight: .black))
                                    .foregroundColor(isDark ? .white : .textDark)
                                
                                Text("Solo el Operador Central activo de Comandancia puede emitir despachos de emergencia.")
                                    .font(.system(size: 12, weight: .medium))
                                    .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal, 16)
                            }
                            .padding(.vertical, 24)
                        }
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .stroke(Color.bomberosRed.opacity(0.3), lineWidth: 1.5)
                        )
                    } else {
                        // Dispatch Console Form
                        GlassCard(viewModel: viewModel) {
                            VStack(alignment: .leading, spacing: 20) {
                                // 1. Selection Clave
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("1. SELECCIONAR CLAVE")
                                        .font(.system(size: 10, weight: .bold))
                                        .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                                    
                                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 64, maximum: 74), spacing: 8)], spacing: 8) {
                                        ForEach(clavesRapidas, id: \.self) { c in
                                            let isSel = clave == c
                                            Button(action: { clave = c }) {
                                                Text(c)
                                                    .font(.system(size: 11, weight: .black))
                                                    .frame(maxWidth: .infinity)
                                                    .frame(height: 36)
                                                    .foregroundColor(isSel ? .white : (isDark ? Color(red: 0.8, green: 0.84, blue: 0.88) : Color(red: 0.28, green: 0.33, blue: 0.41)))
                                                    .background(isSel ? Color.bomberosRed : (isDark ? Color.white.opacity(0.08) : Color.black.opacity(0.04)))
                                                    .cornerRadius(10)
                                                    .overlay(
                                                        RoundedRectangle(cornerRadius: 10)
                                                            .stroke(isSel ? Color.clear : (isDark ? Color.white.opacity(0.1) : Color.black.opacity(0.06)), lineWidth: 1)
                                                    )
                                            }
                                        }
                                    }
                                }
                                
                                // 2. Location
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("2. DETALLES DE UBICACIÓN")
                                        .font(.system(size: 10, weight: .bold))
                                        .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                                    
                                    TextField("Ubicación exacta", text: $lugar)
                                        .padding()
                                        .background(
                                            RoundedRectangle(cornerRadius: 14)
                                                .stroke(isDark ? Color.white.opacity(0.1) : Color.black.opacity(0.06), lineWidth: 1.5)
                                                .background(isDark ? Color.navyDark.opacity(0.2) : Color.white.opacity(0.5))
                                        )
                                        .foregroundColor(isDark ? .white : .textDark)
                                    
                                    ScrollView(.horizontal, showsIndicators: false) {
                                        HStack(spacing: 8) {
                                            ForEach(lugaresSugeridos, id: \.self) { l in
                                                Button(action: {
                                                    let trimmed = lugar.trimmingCharacters(in: .whitespacesAndNewlines)
                                                    lugar = trimmed.isEmpty ? l : "\(trimmed), \(l)"
                                                }) {
                                                    Text(l.uppercased())
                                                        .font(.system(size: 9, weight: .black))
                                                        .padding(.horizontal, 12)
                                                        .padding(.vertical, 6)
                                                        .foregroundColor(isDark ? Color(red: 0.8, green: 0.84, blue: 0.88) : Color(red: 0.28, green: 0.33, blue: 0.41))
                                                        .background(isDark ? Color.white.opacity(0.08) : Color.black.opacity(0.04))
                                                        .cornerRadius(12)
                                                        .overlay(
                                                            RoundedRectangle(cornerRadius: 12)
                                                                .stroke(isDark ? Color.white.opacity(0.05) : Color.black.opacity(0.05), lineWidth: 1)
                                                        )
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                // 3. Pre-informe
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("3. PRE-INFORME INICIAL")
                                        .font(.system(size: 10, weight: .bold))
                                        .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                                    
                                    TextField("Pre-informe preliminar", text: $preinforme)
                                        .padding()
                                        .background(
                                            RoundedRectangle(cornerRadius: 14)
                                                .stroke(isDark ? Color.white.opacity(0.1) : Color.black.opacity(0.06), lineWidth: 1.5)
                                                .background(isDark ? Color.navyDark.opacity(0.2) : Color.white.opacity(0.5))
                                        )
                                        .foregroundColor(isDark ? .white : .textDark)
                                }
                                
                                // 4. Vehicles Selection Grid
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("4. CARROS A DESPACHAR")
                                        .font(.system(size: 10, weight: .bold))
                                        .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                                    
                                    let sortedVehicles = viewModel.vehiclesList.sorted(by: { $0.idCarro < $1.idCarro })
                                    
                                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                                        ForEach(sortedVehicles) { v in
                                            let isAvailable = (v.estado == "1" || v.estado == "0-9") && (v.enServicio == "0" || v.enServicio.isEmpty)
                                            let isSel = selectedVehicles.contains(v.idCarro)
                                            
                                            if !isAvailable {
                                                // Unavailble vehicle card
                                                VStack(spacing: 4) {
                                                    Text(v.idCarro)
                                                        .font(.system(size: 18, weight: .black))
                                                        .foregroundColor(isDark ? Color.textSecondary : .textSecondaryDark)
                                                    
                                                    Text("EN SERVICIO")
                                                        .font(.system(size: 8, weight: .bold))
                                                        .foregroundColor(isDark ? Color(red: 0.28, green: 0.33, blue: 0.41) : .textSecondaryDark)
                                                }
                                                .frame(maxWidth: .infinity)
                                                .frame(height: 64)
                                                .background(isDark ? Color.white.opacity(0.05) : Color.black.opacity(0.03))
                                                .cornerRadius(12)
                                                .overlay(
                                                    RoundedRectangle(cornerRadius: 12)
                                                        .stroke(isDark ? Color.white.opacity(0.05) : Color.black.opacity(0.05), lineWidth: 1)
                                                )
                                                .opacity(0.5)
                                            } else {
                                                // Available vehicle card button
                                                Button(action: {
                                                    if isSel {
                                                        selectedVehicles.remove(v.idCarro)
                                                    } else {
                                                        selectedVehicles.insert(v.idCarro)
                                                    }
                                                }) {
                                                    VStack(spacing: 4) {
                                                        Text(v.idCarro)
                                                            .font(.system(size: 18, weight: .black))
                                                            .foregroundColor(isSel ? .white : (isDark ? Color(red: 0.8, green: 0.84, blue: 0.88) : Color(red: 0.28, green: 0.33, blue: 0.41)))
                                                        
                                                        Text(isSel ? "SEL" : "DISP")
                                                            .font(.system(size: 7, weight: .black))
                                                            .padding(.horizontal, 6)
                                                            .padding(.vertical, 2)
                                                            .foregroundColor(isSel ? Color(red: 0.99, green: 0.8, blue: 0.8) : .goGreen)
                                                            .background(isSel ? Color.white.opacity(0.15) : Color.goGreen.opacity(0.15))
                                                            .cornerRadius(12)
                                                    }
                                                    .frame(maxWidth: .infinity)
                                                    .frame(height: 64)
                                                    .background(isSel ? Color.bomberosRed : (isDark ? Color.white.opacity(0.08) : Color.black.opacity(0.04)))
                                                    .cornerRadius(12)
                                                    .overlay(
                                                        RoundedRectangle(cornerRadius: 12)
                                                            .stroke(isSel ? Color.clear : (isDark ? Color.white.opacity(0.1) : Color.black.opacity(0.06)), lineWidth: 1)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                // Dispatch Trigger Button
                                let ready = !clave.isEmpty && !lugar.isEmpty && !selectedVehicles.isEmpty
                                Button(action: {
                                    if ready { showConfirmDialog = true }
                                }) {
                                    Text("DESPACHAR ALARMA GENERAL")
                                        .font(.system(size: 12, weight: .black))
                                        .foregroundColor(.white)
                                        .frame(maxWidth: .infinity)
                                        .frame(height: 50)
                                        .background(ready ? Color.bomberosRed : Color.bomberosRed.opacity(0.5))
                                        .cornerRadius(12)
                                }
                                .disabled(!ready)
                                .padding(.top, 10)
                            }
                            .padding(16)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            
            // Custom Confirmation Alert Dialog Overlay
            if showConfirmDialog {
                ZStack {
                    Color.black.opacity(0.6)
                        .ignoresSafeArea()
                    
                    VStack(spacing: 16) {
                        Text("CONFIRMAR DESPACHO")
                            .font(.system(size: 16, weight: .black))
                            .foregroundColor(.white)
                        
                        Text("¿Seguro que desea despachar la clave \(clave) a \(lugar) con los carros \(selectedVehicles.sorted().joined(separator: ", "))?")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.white)
                            .multilineTextAlignment(.center)
                        
                        HStack(spacing: 12) {
                            Button(action: {
                                showConfirmDialog = false
                            }) {
                                Text("CANCELAR")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(.red)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 44)
                                    .background(Color.white.opacity(0.1))
                                    .cornerRadius(10)
                            }
                            
                            Button(action: {
                                let vehiclesToDispatch = viewModel.vehiclesList.filter { selectedVehicles.contains($0.idCarro) }
                                viewModel.dispatchFromCentral(
                                    clave: clave,
                                    lugar: lugar,
                                    preinforme: preinforme,
                                    selectedVehicles: vehiclesToDispatch
                                )
                                showConfirmDialog = false
                                clave = ""
                                lugar = ""
                                preinforme = ""
                                selectedVehicles.removeAll()
                            }) {
                                Text("SÍ, DESPACHAR")
                                    .font(.system(size: 12, weight: .black))
                                    .foregroundColor(.white)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 44)
                                    .background(Color.goGreen)
                                    .cornerRadius(10)
                            }
                        }
                    }
                    .padding(20)
                    .frame(width: 320)
                    .background(Color.navyDark)
                    .cornerRadius(14)
                    .shadow(radius: 10)
                }
            }
        }
    }
}
