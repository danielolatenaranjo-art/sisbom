import SwiftUI

struct OrdenesTab: View {
    @ObservedObject var viewModel: SisBomViewModel
    var topPadding: CGFloat = 0
    var bottomPadding: CGFloat = 0
    @State private var showCreateOrderDialog: Bool = false

    var body: some View {
        let isDark = viewModel.isDarkMode
        let myRadial = viewModel.currentUser?.idRadial.uppercased().trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let canPublishOrders = (myRadial == "1" || myRadial == "01" || (viewModel.currentUser?.cargo.uppercased().contains("COMANDANTE") == true))
        
        let ordenes = viewModel.alertsList
            .filter { $0.tipo == "orden" }
            .sorted(by: { $0.idAlerta > $1.idAlerta })
        
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                VStack(spacing: 10) {
                    if ordenes.isEmpty {
                        VStack {
                            Spacer().frame(height: 100)
                            Text("No se registran órdenes oficiales este mes.")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(.textSecondary)
                                .frame(maxWidth: .infinity, alignment: .center)
                        }
                    } else {
                        // Display in 2-column Grid matching Android chunking (ANDROID 3.jpeg)
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                            ForEach(ordenes) { orden in
                                OrdenItemCard(orden: orden, viewModel: viewModel)
                            }
                        }
                        .padding(.horizontal, 8)
                    }
                    
                    Spacer(minLength: 20)
                }
                .padding(.top, topPadding)
                .padding(.bottom, bottomPadding)
            }

            if canPublishOrders {
                Button(action: { showCreateOrderDialog = true }) {
                    Image(systemName: "plus")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 56, height: 56)
                        .background(Color.bomberosRed)
                        .clipShape(Circle())
                        .shadow(color: Color.black.opacity(0.35), radius: 6, x: 0, y: 3)
                }
                .padding(.trailing, 20)
                .padding(.bottom, (bottomPadding > 0 ? bottomPadding : 20) + 12)
            }
        }
        .sheet(isPresented: $showCreateOrderDialog) {
            CreateOrderModal(viewModel: viewModel, isPresented: $showCreateOrderDialog)
        }
    }
}

// MARK: - OrdenItemCard Component (Exact ANDROID 3.jpeg Design)
struct OrdenItemCard: View {
    let orden: AlertaItem
    @ObservedObject var viewModel: SisBomViewModel
    @State private var showDetailSheet: Bool = false
    @State private var showDeleteConfirm: Bool = false

    var body: some View {
        let isDark = viewModel.isDarkMode
        let myRadial = viewModel.currentUser?.idRadial.uppercased() ?? ""
        let isConforme = orden.conforme.split(separator: ",")
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
            .contains(myRadial)
        let isComandante = (viewModel.currentUser?.idRadial == "1" || viewModel.currentUser?.idRadial == "01" || (viewModel.currentUser?.cargo.uppercased().contains("COMANDANTE") == true))
        
        ZStack(alignment: .topTrailing) {
            Button(action: {
                showDetailSheet = true
                if !isConforme {
                    viewModel.registerConforme(alert: orden)
                }
            }) {
                VStack(spacing: 10) {
                    // Circle with Clipboard Icon
                    ZStack {
                        Circle()
                            .fill(isDark ? Color.white.opacity(0.1) : Color(red: 0.88, green: 0.91, blue: 0.95))
                            .frame(width: 48, height: 48)
                        
                        Image(systemName: "list.clipboard.fill")
                            .font(.system(size: 20))
                            .foregroundColor(isDark ? .white : Color(red: 0.32, green: 0.38, blue: 0.48))
                    }
                    .padding(.top, 14)
                    
                    // N° Number
                    HStack(spacing: 8) {
                        Text("N°")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(isDark ? .white : .textDark)
                        Text(orden.numeroOrden.isEmpty ? orden.idAlerta : orden.numeroOrden)
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(isDark ? .white : .textDark)
                    }
                    
                    // Date
                    Text(orden.fechaOrden.isEmpty ? orden.fechaAlerta : orden.fechaOrden)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundColor(isDark ? .textSecondaryDark : .textSecondary)
                    
                    // Divider
                    Divider()
                        .background(isDark ? Color.white.opacity(0.1) : Color.black.opacity(0.08))
                        .padding(.horizontal, 12)
                    
                    // Status VISTO / PENDIENTE
                    if isConforme {
                        Text("✓ VISTO")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(Color(red: 0.02, green: 0.59, blue: 0.41))
                            .padding(.bottom, 8)
                    } else {
                        Text("PENDIENTE")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.bomberosRed)
                            .padding(.bottom, 8)
                    }
                }
                .frame(maxWidth: .infinity)
                .background(isDark ? Color.navyDark : Color.white)
                .cornerRadius(20)
                .shadow(color: isDark ? Color.clear : Color.black.opacity(0.04), radius: 6, x: 0, y: 2)
                .overlay(
                    RoundedRectangle(cornerRadius: 20)
                        .stroke(isConforme ? Color.goGreen.opacity(0.35) : (isDark ? Color.white.opacity(0.1) : Color(red: 0.85, green: 0.94, blue: 0.90)), lineWidth: 1.5)
                )
            }
            .buttonStyle(PlainButtonStyle())
            
            // Delete trash button for Comandante in top trailing corner
            if isComandante {
                Button(action: {
                    showDeleteConfirm = true
                }) {
                    Image(systemName: "trash")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(Color.bomberosRed.opacity(0.85))
                        .padding(8)
                }
            }
        }
        .fullScreenCover(isPresented: $showDetailSheet) {
            OrdenDetailView(orden: orden, viewModel: viewModel, isPresented: $showDetailSheet)
        }
        .alert(isPresented: $showDeleteConfirm) {
            Alert(
                title: Text("Eliminar Orden del Día"),
                message: Text("¿Está seguro de eliminar permanentemente la Orden N° \(orden.numeroOrden)?"),
                primaryButton: .destructive(Text("Eliminar")) {
                    viewModel.deleteAlert(alertId: orden.idAlerta)
                },
                secondaryButton: .cancel(Text("Cancelar"))
            )
        }
    }
}

// MARK: - OrdenDetailView (Full Screen Modal Sheet - Exact Android Parity)
struct OrdenDetailView: View {
    let orden: AlertaItem
    @ObservedObject var viewModel: SisBomViewModel
    @Binding var isPresented: Bool

    var body: some View {
        let isDark = viewModel.isDarkMode
        
        GeometryReader { windowGeo in
            let topInset = windowGeo.safeAreaInsets.top
            
            ZStack {
                // Background
                (isDark ? Color.navyDeep : Color(red: 0.8, green: 0.84, blue: 0.88))
                    .ignoresSafeArea()
                
                VStack(spacing: 0) {
                    // Header Row (unifies status bar area with zero artificial gap)
                    HStack(spacing: 12) {
                        Image(systemName: "doc.text.fill")
                            .foregroundColor(.white)
                        
                        VStack(alignment: .leading, spacing: 2) {
                            Text("ORDEN DEL DÍA \(orden.numeroOrden.uppercased())")
                                .font(.system(size: 12, weight: .black))
                                .foregroundColor(.white)
                            
                            Text("\(viewModel.saasClientName.isEmpty ? "SISBOM" : viewModel.saasClientName.uppercased()), \(orden.fechaOrden.uppercased())")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(Color.white.opacity(0.6))
                        }
                        
                        Spacer()
                        
                        Button(action: {
                            isPresented = false
                        }) {
                            Image(systemName: "xmark")
                                .font(.system(size: 13, weight: .bold))
                                .padding(8)
                                .background(isDark ? Color.white.opacity(0.15) : Color.black.opacity(0.2))
                                .foregroundColor(.white)
                                .clipShape(Circle())
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, max(topInset, 16))
                    .padding(.bottom, 12)
                    .background(isDark ? Color.navyDark : Color(red: 0.28, green: 0.33, blue: 0.41))
                    
                    // Scrollable Document Sheet
                    ScrollView {
                        VStack(spacing: 16) {
                            // Official Document Card (Always White Page for Authentic PDF Look)
                            VStack(spacing: 0) {
                                // Official Letterhead Top Row
                                HStack(alignment: .top) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("CUERPO DE BOMBEROS")
                                            .font(.system(size: 12, weight: .black))
                                            .foregroundColor(Color(red: 0.12, green: 0.16, blue: 0.22))

                                        Text("“Abnegación y Sacrificio”")
                                            .font(.system(size: 9, weight: .bold))
                                            .italic()
                                            .foregroundColor(.bomberosRed)

                                        Text("Fundado 20-Nov-1963")
                                            .font(.system(size: 9, weight: .bold))
                                            .foregroundColor(Color(red: 0.39, green: 0.45, blue: 0.55))

                                        Text("COMANDANCIA")
                                            .font(.system(size: 11, weight: .black))
                                            .foregroundColor(Color(red: 0.12, green: 0.16, blue: 0.22))

                                        Text(viewModel.saasClientName.isEmpty ? "CUERPO DE BOMBEROS DE PLACILLA" : "CUERPO DE BOMBEROS DE \(viewModel.saasClientName.uppercased())")
                                            .font(.system(size: 11, weight: .black))
                                            .foregroundColor(Color(red: 0.12, green: 0.16, blue: 0.22))
                                    }

                                    Spacer()

                                    if let logoImg = UIImage(named: "logo") {
                                        Image(uiImage: logoImg)
                                            .resizable()
                                            .aspectRatio(contentMode: .fit)
                                            .frame(width: 64, height: 64)
                                    }
                                }

                                Rectangle()
                                    .fill(Color.black)
                                    .frame(height: 1.5)
                                    .padding(.vertical, 10)

                                // Date on the right
                                Text("\(viewModel.saasClientName.isEmpty ? "Placilla" : viewModel.saasClientName), \(orden.fechaOrden)")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(Color(red: 0.22, green: 0.25, blue: 0.32))
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                                    .padding(.bottom, 14)

                                // Centered Title
                                Text("ORDEN DEL DIA \(orden.numeroOrden)")
                                    .font(.system(size: 14, weight: .black))
                                    .foregroundColor(Color(red: 0.12, green: 0.16, blue: 0.22))
                                    .frame(maxWidth: .infinity, alignment: .center)
                                    .padding(.bottom, 16)

                                // Order message body (Left aligned / Justified reading)
                                Text(orden.mensajeAlerta.replacingOccurrences(of: "|", with: "\n"))
                                    .font(.system(size: 13, weight: .regular))
                                    .lineSpacing(5)
                                    .foregroundColor(Color(red: 0.22, green: 0.25, blue: 0.32))
                                    .multilineTextAlignment(.leading)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.bottom, 20)

                                // Decree label
                                Text("TODA MODIFICACION A LA PRESENTE SE HARA DE FORMA ESCRITA O VERBAL\n\nCOMUNIQUESE Y CUMPLASE")
                                    .font(.system(size: 9, weight: .black))
                                    .foregroundColor(Color(red: 0.12, green: 0.16, blue: 0.22))
                                    .multilineTextAlignment(.leading)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.bottom, 16)

                                // Stamp & Signature of Comandante
                                if let firmaImg = UIImage(named: "firma_comandante") {
                                    Image(uiImage: firmaImg)
                                        .resizable()
                                        .aspectRatio(contentMode: .fit)
                                        .frame(width: 240, height: 110)
                                        .frame(maxWidth: .infinity, alignment: .center)
                                        .padding(.bottom, 4)
                                }

                                Text(orden.firmaNombre.isEmpty ? "CRISTIAN LOPEZ MELLA" : orden.firmaNombre.uppercased())
                                    .font(.system(size: 12, weight: .black))
                                    .foregroundColor(Color(red: 0.12, green: 0.16, blue: 0.22))
                                    .frame(maxWidth: .infinity, alignment: .center)

                                Text(orden.firmaCargo.isEmpty ? "COMANDANTE" : orden.firmaCargo.uppercased())
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundColor(Color(red: 0.12, green: 0.16, blue: 0.22))
                                    .frame(maxWidth: .infinity, alignment: .center)
                                    .padding(.bottom, 10)

                                Rectangle()
                                    .fill(Color.black)
                                    .frame(height: 1.5)
                                    .padding(.vertical, 8)

                                // Footer text
                                Text("MIRAFLORES Nº 697-FONO 722858606- placilla@bomberos.cl -PLACILLA VI REGION")
                                    .font(.system(size: 8, weight: .bold))
                                    .foregroundColor(Color(red: 0.39, green: 0.45, blue: 0.55))
                                    .multilineTextAlignment(.center)
                                    .frame(maxWidth: .infinity, alignment: .center)
                            }
                            .padding(22)
                            .background(Color.white)
                            .cornerRadius(10)
                            .shadow(color: Color.black.opacity(0.18), radius: 8, x: 0, y: 3)
                        }
                        .padding(16)
                        .padding(.bottom, 20)
                    }
                }
            }
            .frame(width: windowGeo.size.width, height: windowGeo.size.height)
        }
        .ignoresSafeArea()
    }
}

// MARK: - CreateOrderModal Component (Comandancia)
struct CreateOrderModal: View {
    @ObservedObject var viewModel: SisBomViewModel
    @Binding var isPresented: Bool
    
    @State private var numero: String = ""
    @State private var fecha: String = ""
    @State private var cuerpo: String = ""
    @State private var firmaNombre: String = ""
    @State private var firmaCargo: String = ""
    @State private var isSaving: Bool = false
    @State private var errorMessage: String? = nil
    
    init(viewModel: SisBomViewModel, isPresented: Binding<Bool>) {
        self.viewModel = viewModel
        self._isPresented = isPresented
        let dateFmt = DateFormatter()
        dateFmt.dateFormat = "dd-MM-yyyy"
        let todayStr = dateFmt.string(from: Date())
        _fecha = State(initialValue: todayStr)
        _firmaNombre = State(initialValue: viewModel.currentUser?.nombreBombero.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? "")
        _firmaCargo = State(initialValue: viewModel.currentUser?.cargo.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? "")
    }
    
    var body: some View {
        let isDark = viewModel.isDarkMode
        
        NavigationView {
            ZStack {
                (isDark ? Color.navyDeep : Color(red: 0.94, green: 0.96, blue: 0.98))
                    .ignoresSafeArea()
                
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        if let error = errorMessage {
                            Text(error)
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(.bomberosRed)
                                .padding(10)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(Color.bomberosRed.opacity(0.1))
                                .cornerRadius(8)
                        }
                        
                        // Número de Orden
                        VStack(alignment: .leading, spacing: 6) {
                            Text("NÚMERO DE ORDEN *")
                                .font(.system(size: 11, weight: .black))
                                .foregroundColor(isDark ? .white.opacity(0.7) : .textSecondary)
                            TextField("EJ: 12/2024", text: $numero)
                                .padding(12)
                                .background(isDark ? Color.navyDark : Color.white)
                                .cornerRadius(10)
                                .foregroundColor(isDark ? .white : .textDark)
                        }
                        
                        // Fecha
                        VStack(alignment: .leading, spacing: 6) {
                            Text("FECHA (DD-MM-YYYY) *")
                                .font(.system(size: 11, weight: .black))
                                .foregroundColor(isDark ? .white.opacity(0.7) : .textSecondary)
                            TextField("DD-MM-YYYY", text: $fecha)
                                .padding(12)
                                .background(isDark ? Color.navyDark : Color.white)
                                .cornerRadius(10)
                                .foregroundColor(isDark ? .white : .textDark)
                        }
                        
                        // Cuerpo / Contenido
                        VStack(alignment: .leading, spacing: 6) {
                            Text("CUERPO DE LA ORDEN DEL DÍA *")
                                .font(.system(size: 11, weight: .black))
                                .foregroundColor(isDark ? .white.opacity(0.7) : .textSecondary)
                            TextEditor(text: $cuerpo)
                                .frame(height: 120)
                                .padding(8)
                                .background(isDark ? Color.navyDark : Color.white)
                                .cornerRadius(10)
                                .foregroundColor(isDark ? .white : .textDark)
                        }
                        
                        // Firma Nombre
                        VStack(alignment: .leading, spacing: 6) {
                            Text("NOMBRE DE QUIEN FIRMA *")
                                .font(.system(size: 11, weight: .black))
                                .foregroundColor(isDark ? .white.opacity(0.7) : .textSecondary)
                            TextField("NOMBRE", text: $firmaNombre)
                                .padding(12)
                                .background(isDark ? Color.navyDark : Color.white)
                                .cornerRadius(10)
                                .foregroundColor(isDark ? .white : .textDark)
                        }
                        
                        // Firma Cargo
                        VStack(alignment: .leading, spacing: 6) {
                            Text("CARGO DE QUIEN FIRMA *")
                                .font(.system(size: 11, weight: .black))
                                .foregroundColor(isDark ? .white.opacity(0.7) : .textSecondary)
                            TextField("CARGO", text: $firmaCargo)
                                .padding(12)
                                .background(isDark ? Color.navyDark : Color.white)
                                .cornerRadius(10)
                                .foregroundColor(isDark ? .white : .textDark)
                        }
                        
                        Spacer().frame(height: 10)
                        
                        // Submit Button
                        Button(action: {
                            let cleanNum = numero.trimmingCharacters(in: .whitespacesAndNewlines)
                            let cleanFecha = fecha.trimmingCharacters(in: .whitespacesAndNewlines)
                            let cleanCuerpo = cuerpo.trimmingCharacters(in: .whitespacesAndNewlines)
                            let cleanFNombre = firmaNombre.trimmingCharacters(in: .whitespacesAndNewlines)
                            let cleanFCargo = firmaCargo.trimmingCharacters(in: .whitespacesAndNewlines)
                            
                            guard !cleanNum.isEmpty, !cleanFecha.isEmpty, !cleanCuerpo.isEmpty,
                                  !cleanFNombre.isEmpty, !cleanFCargo.isEmpty else {
                                errorMessage = "Complete todos los campos obligatorios."
                                return
                            }
                            
                            isSaving = true
                            errorMessage = nil
                            viewModel.publishOrder(
                                numero: cleanNum,
                                fecha: cleanFecha,
                                cuerpo: cleanCuerpo,
                                fNombre: cleanFNombre.uppercased(),
                                fCargo: cleanFCargo.uppercased(),
                                onSuccess: {
                                    isSaving = false
                                    isPresented = false
                                },
                                onFailure: { err in
                                    isSaving = false
                                    errorMessage = err.localizedDescription
                                }
                            )
                        }) {
                            HStack {
                                if isSaving {
                                    ProgressView().progressViewStyle(CircularProgressViewStyle(tint: .white))
                                } else {
                                    Image(systemName: "doc.text.fill")
                                    Text("PUBLICAR ORDEN DEL DÍA")
                                        .font(.system(size: 13, weight: .black))
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 50)
                            .foregroundColor(.white)
                            .background(Color.bomberosRed)
                            .cornerRadius(14)
                        }
                        .disabled(isSaving)
                    }
                    .padding(20)
                }
            }
            .navigationTitle("Emitir Orden")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancelar") {
                        isPresented = false
                    }
                    .foregroundColor(isDark ? .white : .textDark)
                }
            }
        }
    }
}
