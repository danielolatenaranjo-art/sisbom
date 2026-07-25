import SwiftUI

struct LoginView: View {
    @ObservedObject var viewModel: SisBomViewModel
    @State private var userId: String = ""
    @State private var password: String = ""
    @State private var passwordVisible: Bool = false
    @State private var showingError: Bool = false
    @State private var errorMessage: String = ""

    var body: some View {
        let isDark = viewModel.isDarkMode
        
        SisBomBackground(viewModel: viewModel) {
            ScrollView {
                VStack(spacing: 0) {
                    Spacer(minLength: 40)
                    
                    // Dynamic Institution Logo
                    Image(uiImage: viewModel.getInstitutionLogo())
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 100, height: 100)
                        .padding(.bottom, 14)
                    
                    // Header Title & Client Name
                    Text("SISBOM")
                        .font(.system(size: 24, weight: .black))
                        .foregroundColor(isDark ? .white : .textDark)
                        .padding(.bottom, 2)
                    
                    Text(viewModel.saasClientName.isEmpty ? "PORTAL MÓVIL DE PERSONAL" : viewModel.saasClientName.uppercased())
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(isDark ? Color.red.opacity(0.9) : .textSecondary)
                        .tracking(1.0)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                        .padding(.bottom, 24)
                    
                    // Glass Card Login Form
                    GlassCard(viewModel: viewModel) {
                        VStack(spacing: 16) {
                            // User Field
                            VStack(alignment: .leading, spacing: 6) {
                                Text("USUARIO")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(isDark ? Color.white.opacity(0.6) : .textSecondary)
                                
                                HStack {
                                    Image(systemName: "person.fill")
                                        .foregroundColor(isDark ? Color.white.opacity(0.6) : Color(red: 0.39, green: 0.45, blue: 0.54))
                                    
                                    TextField("Ej. 9-1", text: $userId)
                                        .autocapitalization(.none)
                                        .disableAutocorrection(true)
                                        .foregroundColor(isDark ? .white : .textDark)
                                }
                                .padding()
                                .background(
                                    RoundedRectangle(cornerRadius: 14)
                                        .stroke(isDark ? Color.white.opacity(0.15) : Color(red: 0.88, green: 0.91, blue: 0.94), lineWidth: 1.5)
                                        .background(isDark ? Color.navyDark.opacity(0.2) : Color.white.opacity(0.5))
                                )
                            }
                            
                            // Password Field
                            VStack(alignment: .leading, spacing: 6) {
                                Text("CONTRASEÑA")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(isDark ? Color.white.opacity(0.6) : .textSecondary)
                                
                                HStack {
                                    Image(systemName: "lock.fill")
                                        .foregroundColor(isDark ? Color.white.opacity(0.6) : Color(red: 0.39, green: 0.45, blue: 0.54))
                                    
                                    if passwordVisible {
                                        TextField("Contraseña", text: $password)
                                            .autocapitalization(.none)
                                            .disableAutocorrection(true)
                                            .foregroundColor(isDark ? .white : .textDark)
                                    } else {
                                        SecureField("Contraseña", text: $password)
                                            .foregroundColor(isDark ? .white : .textDark)
                                    }
                                    
                                    Button(action: {
                                        passwordVisible.toggle()
                                    }) {
                                        Image(systemName: passwordVisible ? "eye.fill" : "eye.slash.fill")
                                            .foregroundColor(isDark ? Color.white.opacity(0.6) : Color(red: 0.39, green: 0.45, blue: 0.54))
                                    }
                                }
                                .padding()
                                .background(
                                    RoundedRectangle(cornerRadius: 14)
                                        .stroke(isDark ? Color.white.opacity(0.15) : Color(red: 0.88, green: 0.91, blue: 0.94), lineWidth: 1.5)
                                        .background(isDark ? Color.navyDark.opacity(0.2) : Color.white.opacity(0.5))
                                )
                            }
                            
                            // Login Button
                            Button(action: {
                                triggerLogin()
                            }) {
                                HStack {
                                    if viewModel.isLoggingIn {
                                        ProgressView()
                                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                            .padding(.trailing, 8)
                                    }
                                    Text("INGRESAR")
                                        .font(.system(size: 14, weight: .bold))
                                        .tracking(1.0)
                                }
                                .frame(maxWidth: .infinity)
                                .frame(height: 50)
                                .foregroundColor(.white)
                                .background(Color.bomberosRed)
                                .cornerRadius(14)
                            }
                            .disabled(viewModel.isLoggingIn)
                            .padding(.top, 8)
                        }
                        .padding(24)
                    }
                    .padding(.horizontal, 24)
                    
                    // Option to change license
                    Button(action: {
                        viewModel.clearLicense()
                    }) {
                        HStack(spacing: 6) {
                            Image(systemName: "key.fill")
                            Text("CAMBIAR LICENCIA DE INSTITUCIÓN")
                                .font(.system(size: 10, weight: .bold))
                                .tracking(0.5)
                        }
                        .foregroundColor(isDark ? Color.white.opacity(0.4) : Color.black.opacity(0.4))
                        .padding(.top, 20)
                    }
                    
                    Spacer(minLength: 40)
                }
            }
        }
        .alert(isPresented: $showingError) {
            Alert(
                title: Text("Error de Ingreso"),
                message: Text(errorMessage),
                dismissButton: .default(Text("Entendido"))
            )
        }
    }
    
    private func triggerLogin() {
        viewModel.performLogin(idReg: userId, pass: password) { success in
            if !success {
                errorMessage = "ID o Clave incorrectos, o el usuario está inactivo."
                showingError = true
            }
        }
    }
}

struct LoginView_Previews: PreviewProvider {
    static var previews: some View {
        LoginView(viewModel: SisBomViewModel())
    }
}
