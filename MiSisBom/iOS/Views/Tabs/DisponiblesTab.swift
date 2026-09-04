import SwiftUI

struct DisponiblesTab: View {
    @ObservedObject var viewModel: SisBomViewModel

    var availableFirefighters: [UserPersonal] {
        viewModel.personnelList.filter { p in
            p.activo &&
            p.estado.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == "0-9" &&
            p.idRadial.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() != "C1"
        }.sorted { p1, p2 in
            let num1 = Int(p1.idRadial.filter { $0.isNumber }) ?? 9999
            let num2 = Int(p2.idRadial.filter { $0.isNumber }) ?? 9999
            if num1 != num2 {
                return num1 < num2
            }
            return p1.idRadial < p2.idRadial
        }
    }

    var body: some View {
        let isDark = viewModel.isDarkMode

        ScrollView {
            VStack(spacing: 12) {
                // Top Status Header: Count Badge
                HStack {
                    Spacer()
                    Text("\(availableFirefighters.count) ACTIVO(S)")
                        .font(.system(size: 11, weight: .black))
                        .foregroundColor(.goGreen)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Color.goGreen.opacity(0.15))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color.goGreen.opacity(0.5), lineWidth: 1)
                        )
                        .cornerRadius(12)
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)

                // List of Available Firefighters or Empty State
                if availableFirefighters.isEmpty {
                    GlassCard(viewModel: viewModel) {
                        VStack {
                            Text("NO HAY BOMBEROS DISPONIBLES EN ESTE MOMENTO")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(isDark ? Color.textSecondaryDark : .textSecondary)
                                .multilineTextAlignment(.center)
                                .padding(24)
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .padding(.horizontal, 16)
                } else {
                    LazyVStack(spacing: 12) {
                        ForEach(availableFirefighters) { p in
                            FirefighterRowCard(personnel: p, viewModel: viewModel)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
            .padding(.bottom, 24)
        }
    }
}

// MARK: - Firefighter Row Card
struct FirefighterRowCard: View {
    let personnel: UserPersonal
    @ObservedObject var viewModel: SisBomViewModel

    var body: some View {
        let isDark = viewModel.isDarkMode

        GlassCard(viewModel: viewModel) {
            HStack(spacing: 16) {
                // Avatar
                FirefighterAvatarView(photoString: personnel.foto, isDark: isDark)
                    .frame(width: 48, height: 48)

                // Name & Rank
                VStack(alignment: .leading, spacing: 2) {
                    Text(personnel.nombreBombero.uppercased())
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(isDark ? .white : .textDark)
                        .lineLimit(1)

                    let cargoDisplay = personnel.cargo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        ? "VOLUNTARIO"
                        : personnel.cargo.trimmingCharacters(in: .whitespacesAndNewlines)

                    Text(cargoDisplay.uppercased())
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(isDark ? Color.textSecondaryDark : .textSecondary)
                        .lineLimit(1)
                }

                Spacer()

                // Radial ID Badge
                Text(personnel.idRadial.uppercased())
                    .font(.system(size: 12, weight: .black))
                    .foregroundColor(isDark ? .white : .textDark)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(isDark ? Color(red: 0.12, green: 0.16, blue: 0.23) : Color(red: 0.95, green: 0.96, blue: 0.98))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(isDark ? Color.white.opacity(0.15) : Color(red: 0.88, green: 0.91, blue: 0.94), lineWidth: 1)
                    )
                    .cornerRadius(12)
            }
            .padding(12)
            .frame(maxWidth: .infinity)
        }
    }
}

// MARK: - Avatar Image Supporting Base64, URL & System Icon
struct FirefighterAvatarView: View {
    let photoString: String
    let isDark: Bool

    var decodedImage: UIImage? {
        let clean = photoString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return nil }

        if clean.hasPrefix("data:image") {
            if let commaIndex = clean.firstIndex(of: ",") {
                let base64Part = String(clean[clean.index(after: commaIndex)...])
                if let data = Data(base64Encoded: base64Part, options: .ignoreUnknownCharacters) {
                    return UIImage(data: data)
                }
            }
        } else if clean.count > 100 && !clean.hasPrefix("http") {
            // Direct Base64 without data: prefix
            if let data = Data(base64Encoded: clean, options: .ignoreUnknownCharacters) {
                return UIImage(data: data)
            }
        }
        return nil
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(isDark ? Color(red: 0.12, green: 0.16, blue: 0.23) : Color(red: 0.95, green: 0.96, blue: 0.98))

            if let uiImage = decodedImage {
                Image(uiImage: uiImage)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .clipShape(Circle())
            } else if let url = URL(string: photoString.trimmingCharacters(in: .whitespacesAndNewlines)), photoString.hasPrefix("http") {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                            .clipShape(Circle())
                    default:
                        placeholderView
                    }
                }
            } else {
                placeholderView
            }
        }
        .frame(width: 48, height: 48)
    }

    private var placeholderView: some View {
        Image(systemName: "person.fill")
            .font(.system(size: 20))
            .foregroundColor(isDark ? Color.textSecondaryDark : .textSecondary)
    }
}
