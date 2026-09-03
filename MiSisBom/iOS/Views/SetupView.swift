import SwiftUI
import UIKit

struct SetupView: View {
    @ObservedObject var viewModel: SisBomViewModel
    @State private var licenseKeyInput: String = ""
    @State private var copiedHwidNotification: Bool = false

    private var hardwareId: String {
        if let idfv = UIDevice.current.identifierForVendor?.uuidString {
            return "IOS-\(idfv.prefix(12))"
        }
        return "IOS-UNKNOWN"
    }

    var body: some View {
        ZStack {
            // Dark Background with Subtle Red Gradient Accent
            Color(red: 0.05, green: 0.05, blue: 0.07)
                .ignoresSafeArea()
            
            RadialGradient(
                gradient: Gradient(colors: [Color.red.opacity(0.15), Color.clear]),
                center: .top,
                startRadius: 50,
                endRadius: 400
            )
            .ignoresSafeArea()

            ScrollView {
                VStack(spacing: 28) {
                    Spacer(minLength: 40)

                    // Logo & App Header
                    VStack(spacing: 14) {
                        Image(uiImage: viewModel.getInstitutionLogo())
                            .resizable()
                            .scaledToFit()
                            .frame(width: 90, height: 90)
                            .shadow(color: Color.red.opacity(0.3), radius: 10, x: 0, y: 4)

                        Text("miSisBom SaaS")
                            .font(.system(size: 26, weight: .black, design: .rounded))
                            .foregroundColor(.white)
                            .tracking(1.5)

                        Text("ACTIVACIÓN DE LICENCIA")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(Color.red.opacity(0.9))
                            .tracking(2.0)
                    }

                    // Card Form Container
                    VStack(spacing: 20) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("CLAVE DE LICENCIA")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(.gray)
                                .tracking(1.0)

                            TextField("INGRESE SU LICENCIA", text: $licenseKeyInput)
                                .autocapitalization(.allCharacters)
                                .disableAutocorrection(true)
                                .font(.system(size: 14, weight: .bold, design: .monospaced))
                                .padding()
                                .background(Color.black.opacity(0.5))
                                .cornerRadius(12)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color.white.opacity(0.1), lineWidth: 1)
                                )
                                .foregroundColor(.white)
                        }

                        // Hardware ID section
                        VStack(alignment: .leading, spacing: 6) {
                            Text("ID DE DISPOSITIVO (HWID)")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(.gray)
                                .tracking(1.0)

                            HStack {
                                Text(hardwareId)
                                    .font(.system(size: 12, weight: .semibold, design: .monospaced))
                                    .foregroundColor(.white.opacity(0.8))
                                    .lineLimit(1)
                                
                                Spacer()
                                
                                Button(action: {
                                    UIPasteboard.general.string = hardwareId
                                    withAnimation {
                                        copiedHwidNotification = true
                                    }
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                                        withAnimation {
                                            copiedHwidNotification = false
                                        }
                                    }
                                }) {
                                    HStack(spacing: 4) {
                                        Image(systemName: copiedHwidNotification ? "checkmark.circle.fill" : "doc.on.doc")
                                        Text(copiedHwidNotification ? "COPIADO" : "COPIAR")
                                            .font(.system(size: 10, weight: .bold))
                                    }
                                    .foregroundColor(copiedHwidNotification ? .green : .red)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 6)
                                    .background(Color.white.opacity(0.05))
                                    .cornerRadius(8)
                                }
                            }
                            .padding()
                            .background(Color.black.opacity(0.3))
                            .cornerRadius(12)
                        }

                        // Error Banner
                        if !viewModel.saasActivationError.isEmpty {
                            HStack(spacing: 10) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundColor(.red)
                                Text(viewModel.saasActivationError)
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundColor(.red)
                                    .multilineTextAlignment(.leading)
                            }
                            .padding()
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.red.opacity(0.12))
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.red.opacity(0.3), lineWidth: 1)
                            )
                        }

                        // Action Button
                        Button(action: {
                            hideKeyboard()
                            viewModel.activateLicense(key: licenseKeyInput)
                        }) {
                            HStack {
                                if viewModel.isActivatingLicense {
                                    ProgressView()
                                        .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                        .padding(.trailing, 6)
                                }
                                Text(viewModel.isActivatingLicense ? "VALIDANDO..." : "ACTIVAR LICENCIA")
                                    .font(.system(size: 14, weight: .bold))
                                    .tracking(1.0)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .background(
                                licenseKeyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                ? AnyView(Color.gray.opacity(0.3))
                                : AnyView(LinearGradient(gradient: Gradient(colors: [Color.red, Color(red: 0.7, green: 0, blue: 0)]), startPoint: .leading, endPoint: .trailing))
                            )
                            .foregroundColor(.white)
                            .cornerRadius(12)
                            .shadow(color: Color.red.opacity(0.4), radius: 8, x: 0, y: 4)
                        }
                        .disabled(licenseKeyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || viewModel.isActivatingLicense)
                    }
                    .padding(24)
                    .background(Color.white.opacity(0.04))
                    .cornerRadius(20)
                    .overlay(
                        RoundedRectangle(cornerRadius: 20)
                            .stroke(Color.white.opacity(0.08), lineWidth: 1)
                    )
                    .padding(.horizontal, 20)

                    Spacer(minLength: 40)
                }
            }
        }
        .onAppear {
            if let savedKey = UserDefaults.standard.string(forKey: "saas_license_key") {
                licenseKeyInput = savedKey
            }
        }
        .alert(isPresented: $viewModel.requiresAppRestartAfterLicenseChange) {
            Alert(
                title: Text("Nueva Institución Activada"),
                message: Text("Se ha conectado con éxito a \(viewModel.saasClientName). Para aplicar los cambios y conectarse a los servidores de la nueva institución, presione Continuar para reiniciar la aplicación."),
                dismissButton: .default(Text("Continuar"), action: {
                    exit(0)
                })
            )
        }
    }

    private func hideKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}
