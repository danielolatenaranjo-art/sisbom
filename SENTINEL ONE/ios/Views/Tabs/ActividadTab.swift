import SwiftUI
import WebKit

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
                // Central Operator Status Card
                GlassCard(viewModel: viewModel) {
                    VStack(spacing: 0) {
                        HStack(spacing: 12) {
                            ZStack {
                                Circle()
                                    .fill(viewModel.centralOperatorName.isEmpty ? Color.gray.opacity(0.15) : Color.goGreen.opacity(0.15))
                                    .frame(width: 40, height: 40)
                                
                                Image(systemName: viewModel.centralOperatorName.isEmpty ? "building.2.crop.circle" : "antenna.radiowaves.left.and.right")
                                    .font(.system(size: 18))
                                    .foregroundColor(viewModel.centralOperatorName.isEmpty ? .textSecondary : .goGreen)
                            }
                            
                            VStack(alignment: .leading, spacing: 2) {
                                Text("OPERADOR CENTRAL DE ALARMAS")
                                    .font(.system(size: 9, weight: .black))
                                    .foregroundColor(isDark ? Color.textSecondaryDark : .textSecondary)
                                
                                if !viewModel.centralOperatorName.isEmpty {
                                    Text("EN CONSOLA: \(viewModel.centralOperatorName.uppercased())")
                                        .font(.system(size: 13, weight: .black))
                                        .foregroundColor(isDark ? .white : .textDark)
                                } else {
                                    Text("CENTRAL CERRADA / SIN OPERADOR")
                                        .font(.system(size: 11, weight: .bold))
                                        .foregroundColor(isDark ? Color.textSecondaryDark : .textSecondary)
                                }
                            }
                            
                            Spacer()
                            
                            Text(viewModel.centralOperatorName.isEmpty ? "CERRADA" : "ACTIVO")
                                .font(.system(size: 9, weight: .black))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .foregroundColor(viewModel.centralOperatorName.isEmpty ? (isDark ? Color.textSecondaryDark : .textSecondary) : .goGreen)
                                .background(viewModel.centralOperatorName.isEmpty ? (isDark ? Color.white.opacity(0.05) : Color.black.opacity(0.05)) : Color.goGreen.opacity(0.15))
                                .cornerRadius(8)
                        }
                        
                        let isComandante = (user?.cargo.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == "COMANDANTE") && ["1", "01", "2", "02", "3", "03"].contains(user?.idRadial.trimmingCharacters(in: .whitespacesAndNewlines) ?? "")
                        let canCloseOp = !viewModel.centralOperatorName.isEmpty && (viewModel.isCentralActive || (!viewModel.centralOperatorId.isEmpty && viewModel.centralOperatorId == user?.idRegistro) || isComandante)
                        
                        if canCloseOp {
                            Button(action: {
                                viewModel.closeCentralOperatorSession()
                            }) {
                                HStack(spacing: 6) {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.system(size: 14))
                                    Text("CERRAR TURNO DE CENTRAL")
                                        .font(.system(size: 11, weight: .bold))
                                }
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .frame(height: 36)
                                .background(Color.bomberosRed)
                                .cornerRadius(8)
                            }
                            .padding(.top, 10)
                        }
                    }
                    .padding(14)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                
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
                
                if let lat = dispatch.lat, let lng = dispatch.lng, lat != 0, lng != 0 {
                    Spacer().frame(height: 10)
                    IncidentMapPreview(lat: lat, lng: lng, clave: dispatch.clave, isDark: isDark)
                    Spacer().frame(height: 10)
                } else {
                    Spacer().frame(height: 12)
                }
                
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

struct IncidentMapPreview: View {
    let lat: Double
    let lng: Double
    let clave: String
    let isDark: Bool

    var body: some View {
        ZStack(alignment: .topTrailing) {
            IncidentWebView(lat: lat, lng: lng, clave: clave, isDark: isDark)
                .frame(height: 160)
                .cornerRadius(12)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(isDark ? Color.white.opacity(0.15) : Color.black.opacity(0.1), lineWidth: 1)
                )

            Text("RADIO 500M")
                .font(.system(size: 9, weight: .black))
                .foregroundColor(.white)
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(Color.black.opacity(0.75))
                .cornerRadius(6)
                .overlay(
                    RoundedRectangle(cornerRadius: 6)
                        .stroke(Color.white.opacity(0.2), lineWidth: 1)
                )
                .padding(8)
        }
    }
}

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
        let cleanKey = clave.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let pinColor: String
        switch cleanKey {
        case "10-0": pinColor = "#dc2626"
        case "10-1": pinColor = "#ea580c"
        case "10-2": pinColor = "#b45309"
        case "10-3", "10-8": pinColor = "#0284c7"
        case "10-4": pinColor = "#e11d48"
        case "10-5": pinColor = "#7c3aed"
        case "10-6": pinColor = "#0d9488"
        case "10-7": pinColor = "#eab308"
        case "10-9": pinColor = "#c2410c"
        case "10-10": pinColor = "#0369a1"
        case "10-11": pinColor = "#2563eb"
        case "10-12": pinColor = "#4f46e5"
        case "10-14": pinColor = "#059669"
        default: pinColor = "#dc2626"
        }

        let html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #0f172a; overflow: hidden; }
                .leaflet-control-attribution { display: none !important; }
                .incident-pin {
                    background: \(pinColor);
                    width: 22px;
                    height: 22px;
                    border-radius: 50%;
                    border: 2.5px solid #ffffff;
                    box-shadow: 0 0 14px rgba(0, 0, 0, 0.8), 0 0 6px \(pinColor);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                }
                .pulse-ring {
                    position: absolute;
                    width: 44px;
                    height: 44px;
                    top: -11px;
                    left: -11px;
                    border-radius: 50%;
                    border: 2px solid \(pinColor);
                    animation: pulse 2s infinite ease-out;
                    pointer-events: none;
                }
                @keyframes pulse {
                    0% { transform: scale(0.5); opacity: 1; }
                    100% { transform: scale(1.5); opacity: 0; }
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

                var satLayer = L.tileLayer('https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
                    maxZoom: 20,
                    subdomains: ['0', '1', '2', '3']
                });

                var esriLayer = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
                    maxZoom: 19
                });

                var fallbackDone = false;
                satLayer.on('tileerror', function() {
                    if (!fallbackDone) {
                        fallbackDone = true;
                        try {
                            map.removeLayer(satLayer);
                            esriLayer.addTo(map);
                        } catch(e){}
                    }
                });

                satLayer.addTo(map);

                L.circle([\(lat), \(lng)], {
                    radius: 400,
                    color: '\(pinColor)',
                    fillColor: '\(pinColor)',
                    fillOpacity: 0.14,
                    weight: 2,
                    dashArray: '4, 4'
                }).addTo(map);

                var iconHtml = '<div style="position:relative;"><div class="pulse-ring"></div><div class="incident-pin"></div></div>';
                var icon = L.divIcon({
                    html: iconHtml,
                    className: 'custom-incident-marker',
                    iconSize: [22, 22],
                    iconAnchor: [11, 11]
                });
                L.marker([\(lat), \(lng)], { icon: icon }).addTo(map);
            </script>
        </body>
        </html>
        """
        webView.loadHTMLString(html, baseURL: URL(string: "https://sisbom.com"))
    }
}
