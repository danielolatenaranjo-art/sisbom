import SwiftUI

struct OrdenesTab: View {
    @ObservedObject var viewModel: SisBomViewModel

    var body: some View {
        let isDark = viewModel.isDarkMode
        let ordenes = viewModel.alertsList
            .filter { $0.tipo == "orden" }
            .sorted(by: { $0.idAlerta > $1.idAlerta })
        
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("ÓRDENES DEL DÍA")
                    .font(.system(size: 16, weight: .black))
                    .foregroundColor(isDark ? .white : .textDark)
                    .padding(.top, 16)
                    .padding(.horizontal, 16)
                
                if ordenes.isEmpty {
                    VStack {
                        Spacer().frame(height: 100)
                        Text("No se registran órdenes oficiales este mes.")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                } else {
                    // Display in 2-column Grid matching Android chunking
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                        ForEach(ordenes) { orden in
                            OrdenItemCard(orden: orden, viewModel: viewModel)
                        }
                    }
                    .padding(.horizontal, 16)
                }
                
                Spacer(minLength: 20)
            }
        }
    }
}

// MARK: - OrdenItemCard Component
struct OrdenItemCard: View {
    let orden: Alert
    @ObservedObject var viewModel: SisBomViewModel
    @State private var showDetailSheet: Bool = false

    var body: some View {
        let isDark = viewModel.isDarkMode
        let myRadial = viewModel.currentUser?.idRadial.uppercased() ?? ""
        let isConforme = orden.conforme.split(separator: ",")
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
            .contains(myRadial)
        
        Button(action: {
            showDetailSheet = true
        }) {
            GlassCard(viewModel: viewModel) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Text("Orden N° \(orden.numeroOrden)")
                                .font(.system(size: 14, weight: .black))
                                .foregroundColor(isDark ? .white : .textDark)
                                .lineLimit(1)
                            
                            // Blue dot indicator if not conformed
                            if !isConforme {
                                Circle()
                                    .fill(Color.infoBlue)
                                    .frame(width: 6, height: 6)
                            }
                        }
                        
                        Text("Fecha: \(orden.fechaOrden)")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                    }
                    Spacer()
                }
                .padding(16)
            }
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(isConforme ? Color.goGreen.opacity(0.5) : Color.gray.opacity(0.3), lineWidth: 1.5)
            )
        }
        .fullScreenCover(isPresented: $showDetailSheet) {
            OrdenDetailView(orden: orden, viewModel: viewModel, isPresented: $showDetailSheet)
        }
    }
}

// MARK: - OrdenDetailView (Full Screen Modal Sheet)
struct OrdenDetailView: View {
    let orden: Alert
    @ObservedObject var viewModel: SisBomViewModel
    @Binding var isPresented: Bool

    var body: some View {
        let isDark = viewModel.isDarkMode
        let myRadial = viewModel.currentUser?.idRadial.uppercased() ?? ""
        let isConforme = orden.conforme.split(separator: ",")
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
            .contains(myRadial)
        
        ZStack {
            // Background
            (isDark ? Color.navyDeep : Color(red: 0.8, green: 0.84, blue: 0.88))
                .ignoresSafeArea()
            
            VStack(spacing: 0) {
                // Header Row (unifies status bar area)
                HStack(spacing: 12) {
                    Image(systemName: "doc.text.fill")
                        .foregroundColor(.white)
                    
                    VStack(alignment: .leading, spacing: 2) {
                        Text("ORDEN DEL DÍA \(orden.numeroOrden.uppercased())")
                            .font(.system(size: 12, weight: .black))
                            .foregroundColor(.white)
                        
                        Text("\(orden.fechaOrden.uppercased())")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(Color.white.opacity(0.6))
                    }
                    
                    Spacer()
                    
                    Button(action: {
                        isPresented = false
                    }) {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .bold))
                            .padding(8)
                            .background(isDark ? Color.white.opacity(0.15) : Color.black.opacity(0.2))
                            .foregroundColor(.white)
                            .clipShape(Circle())
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 50)
                .padding(.bottom, 12)
                .background(isDark ? Color.navyDark : Color(red: 0.28, green: 0.33, blue: 0.41))
                
                // Scrollable Document Sheet
                ScrollView {
                    VStack(spacing: 20) {
                        // White Document Card
                        VStack(spacing: 16) {
                            // Official Seal Logo
                            Image("logo")
                                .resizable()
                                .aspectRatio(contentMode: .fit)
                                .frame(width: 64, height: 64)
                                .padding(.top, 8)
                            
                            VStack(spacing: 2) {
                                Text("CUERPO DE BOMBEROS")
                                    .font(.system(size: 14, weight: .black))
                                    .foregroundColor(isDark ? .white : Color(red: 0.12, green: 0.16, blue: 0.22))
                                
                                Text("COMANDANCIA")
                                    .font(.system(size: 11, weight: .black))
                                    .foregroundColor(.bomberosRed)
                            }
                            
                            Divider()
                                .frame(height: 2)
                                .background(Color.bomberosRed)
                            
                            // Order message body
                            Text(orden.mensajeAlerta.replacingOccurrences(of: "|", with: "\n"))
                                .font(.system(size: 13))
                                .lineSpacing(6)
                                .foregroundColor(isDark ? Color(red: 0.88, green: 0.91, blue: 0.94) : Color(red: 0.22, green: 0.25, blue: 0.32))
                                .multilineTextAlignment(.center)
                                .padding(.vertical, 8)
                            
                            // Decree label
                            Text("TODA MODIFICACION A LA PRESENTE SE HARA DE FORMA ESCRITA O VERBAL\n\nCOMUNIQUESE Y CUMPLASE")
                                .font(.system(size: 9, weight: .black))
                                .foregroundColor(isDark ? .textSecondaryDark : Color(red: 0.29, green: 0.33, blue: 0.39))
                                .multilineTextAlignment(.center)
                                .padding(.vertical, 4)
                            
                            Divider()
                                .background(isDark ? Color.white.opacity(0.1) : Color.black.opacity(0.1))
                            
                            // Signature
                            VStack(spacing: 2) {
                                Text(orden.firmaNombre.uppercased())
                                    .font(.system(size: 12, weight: .black))
                                    .foregroundColor(isDark ? .white : Color(red: 0.12, green: 0.16, blue: 0.22))
                                
                                Text(orden.firmaCargo.uppercased())
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundColor(.bomberosRed)
                            }
                        }
                        .padding(24)
                        .background(isDark ? Color.navyDark : Color.white)
                        .cornerRadius(12)
                        .shadow(radius: 5)
                        
                        // Conforme Action Button
                        if !isConforme {
                            Button(action: {
                                viewModel.registerConforme(alert: orden)
                                isPresented = false
                            }) {
                                Text("MARCAR COMO CONFORME")
                                    .font(.system(size: 12, weight: .black))
                                    .foregroundColor(.white)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 48)
                                    .background(Color.goGreen)
                                    .cornerRadius(12)
                            }
                        } else {
                            HStack {
                                Image(systemName: "checkmark.circle.fill")
                                Text("✓ CONFORME REGISTRADO")
                                    .font(.system(size: 12, weight: .black))
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 48)
                            .foregroundColor(.goGreen)
                            .background(Color.goGreen.opacity(0.15))
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.goGreen.opacity(0.5), lineWidth: 1)
                            )
                        }
                    }
                    .padding(16)
                }
            }
        }
    }
}
