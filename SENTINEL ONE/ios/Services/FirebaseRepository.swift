import Foundation
import FirebaseCore
import FirebaseFirestore
import FirebaseAuth

// Since we'll import Firebase via Swift Package Manager (SPM) in Xcode, we can use the Firestore APIs directly.
class FirebaseRepository {
    private var db: Firestore {
        return Firestore.firestore()
    }
    
    // Check if Firebase Auth is currently logged in (anonymous fallback)
    func isUserActive() -> Bool {
        guard FirebaseApp.app() != nil else { return false }
        return Auth.auth().currentUser != nil
    }
    
    // MARK: - Realtime Listeners
    
    func getPersonnel(onChange: @escaping ([UserPersonal]) -> Void) -> ListenerRegistration {
        return db.collection("personal").addSnapshotListener { snapshot, error in
            guard let documents = snapshot?.documents else {
                print("Error fetching personnel: \(error?.localizedDescription ?? "Unknown error")")
                return
            }
            let list = documents.map { doc -> UserPersonal in
                UserPersonal(docId: doc.documentID, data: doc.data())
            }
            onChange(list)
        }
    }
    
    func getPersonnelSelf(userId: String, onChange: @escaping (UserPersonal?) -> Void) -> ListenerRegistration {
        return db.collection("personal").document(userId).addSnapshotListener { snapshot, error in
            guard let document = snapshot, document.exists, let data = document.data() else {
                print("Error fetching personnel self: \(error?.localizedDescription ?? "Document does not exist")")
                onChange(nil)
                return
            }
            let user = UserPersonal(docId: document.documentID, data: data)
            onChange(user)
        }
    }
    
    func getDispatches(onChange: @escaping ([Dispatch]) -> Void) -> ListenerRegistration {
        return db.collection("despachos").addSnapshotListener { snapshot, error in
            guard let documents = snapshot?.documents else {
                print("Error fetching dispatches: \(error?.localizedDescription ?? "Unknown error")")
                return
            }
            let list = documents.compactMap { doc -> Dispatch? in
                var data = doc.data()
                // Inject document ID if missing in the map
                if data["idServicio"] == nil {
                    data["idServicio"] = doc.documentID
                }
                if data["lat"] == nil {
                    if let geo = data["geo"] as? [String: Any], let lat = geo["lat"] as? Double {
                        data["lat"] = lat
                    } else if let gps = data["ubicacionGps"] as? [String: Any], let lat = gps["lat"] as? Double {
                        data["lat"] = lat
                    } else if let alertGeo = data["alertanteGeo"] as? [String: Any], let lat = alertGeo["lat"] as? Double {
                        data["lat"] = lat
                    }
                }
                if data["lng"] == nil {
                    if let geo = data["geo"] as? [String: Any], let lng = (geo["lng"] ?? geo["lon"]) as? Double {
                        data["lng"] = lng
                    } else if let gps = data["ubicacionGps"] as? [String: Any], let lng = (gps["lng"] ?? gps["lon"]) as? Double {
                        data["lng"] = lng
                    } else if let alertGeo = data["alertanteGeo"] as? [String: Any], let lng = (alertGeo["lng"] ?? alertGeo["lon"]) as? Double {
                        data["lng"] = lng
                    }
                }
                guard let jsonData = try? JSONSerialization.data(withJSONObject: data) else { return nil }
                return try? JSONDecoder().decode(Dispatch.self, from: jsonData)
            }
            onChange(list)
        }
    }
    
    func getAlerts(onChange: @escaping ([AlertaItem]) -> Void) -> ListenerRegistration {
        return db.collection("alertas").addSnapshotListener { snapshot, error in
            guard let documents = snapshot?.documents else {
                print("Error fetching alerts: \(error?.localizedDescription ?? "Unknown error")")
                return
            }
            let list = documents.compactMap { doc -> AlertaItem? in
                var data = doc.data()
                if data["idAlerta"] == nil {
                    data["idAlerta"] = doc.documentID
                }
                guard let jsonData = try? JSONSerialization.data(withJSONObject: data) else { return nil }
                return try? JSONDecoder().decode(AlertaItem.self, from: jsonData)
            }.filter { isAlertActive(duracion: $0.duracion) }
            onChange(list)
        }
    }
    
    func getVehicles(onChange: @escaping ([Vehicle]) -> Void) -> ListenerRegistration {
        return db.collection("moviles").addSnapshotListener { snapshot, error in
            guard let documents = snapshot?.documents else {
                print("Error fetching vehicles: \(error?.localizedDescription ?? "Unknown error")")
                return
            }
            let list = documents.compactMap { doc -> Vehicle? in
                var data = doc.data()
                if data["idCarro"] == nil {
                    data["idCarro"] = doc.documentID
                }
                guard let jsonData = try? JSONSerialization.data(withJSONObject: data) else { return nil }
                return try? JSONDecoder().decode(Vehicle.self, from: jsonData)
            }
            onChange(list)
        }
    }
    
    func getAttendance(userId: String, onChange: @escaping ([AttendanceSheet]) -> Void) -> ListenerRegistration {
        return db.collection("asistencia").addSnapshotListener { snapshot, error in
            guard let documents = snapshot?.documents else {
                print("Error fetching attendance sheets: \(error?.localizedDescription ?? "Unknown error")")
                return
            }
            
            var attendanceList = documents.compactMap { doc -> AttendanceSheet? in
                var data = doc.data()
                if data["idLista"] == nil {
                    data["idLista"] = doc.documentID
                }
                guard let jsonData = try? JSONSerialization.data(withJSONObject: data) else { return nil }
                return try? JSONDecoder().decode(AttendanceSheet.self, from: jsonData)
            }
            
            // For each attendance sheet, fetch the personal subcollection entry for this user to get userEstado/userAbono
            let group = DispatchGroup()
            for i in 0..<attendanceList.count {
                let sheetId = attendanceList[i].idLista
                group.enter()
                self.db.collection("asistencia").document(sheetId).collection("personal").document(userId).getDocument { subDoc, subErr in
                    defer { group.leave() }
                    if let subDoc = subDoc, subDoc.exists, let subData = subDoc.data() {
                        attendanceList[i].userEstado = subData["estado"] as? String ?? ""
                        if let abono = subData["abono"] {
                            if let doubleAbono = abono as? Double {
                                attendanceList[i].userAbono = doubleAbono
                            } else if let intAbono = abono as? Int {
                                attendanceList[i].userAbono = Double(intAbono)
                            } else if let strAbono = abono as? String {
                                attendanceList[i].userAbono = Double(strAbono) ?? 0.0
                            }
                        }
                    }
                }
            }
            
            group.notify(queue: .main) {
                onChange(attendanceList)
            }
        }
    }
    
    func getCentralState(onChange: @escaping ([String: Any]) -> Void) -> ListenerRegistration {
        return db.collection("accesos").document("central").addSnapshotListener { snapshot, error in
            if let error = error {
                print("Error listening to central access: \(error.localizedDescription)")
                return
            }
            onChange(snapshot?.data() ?? [:])
        }
    }
    
    // MARK: - Firestore Write Operations
    
    func updatePersonalStatus(userId: String, status: String, completion: @escaping (Result<Void, Error>) -> Void) {
        db.collection("personal").document(userId).updateData(["estado": status]) { error in
            if let error = error {
                completion(.failure(error))
            } else {
                completion(.success(()))
            }
        }
    }
    
    func updatePersonalService(userId: String, serviceId: String, completion: @escaping (Result<Void, Error>) -> Void) {
        db.collection("personal").document(userId).updateData(["enServicio": serviceId]) { error in
            if let error = error {
                completion(.failure(error))
            } else {
                completion(.success(()))
            }
        }
    }
    
    func updatePersonalPassword(userId: String, newPass: String, completion: @escaping (Result<Void, Error>) -> Void) {
        db.collection("personal").document(userId).updateData(["contrasena": newPass]) { error in
            if let error = error {
                completion(.failure(error))
            } else {
                completion(.success(()))
            }
        }
    }
    
    func updateAlertPin(alertId: String, newFijar: String, completion: @escaping (Result<Void, Error>) -> Void) {
        db.collection("alertas").document(alertId).updateData(["fijar": newFijar]) { error in
            if let error = error {
                completion(.failure(error))
            } else {
                completion(.success(()))
            }
        }
    }
    
    func updateAlertConforme(alertId: String, newConforme: String, completion: @escaping (Result<Void, Error>) -> Void) {
        db.collection("alertas").document(alertId).updateData(["conforme": newConforme]) { error in
            if let error = error {
                completion(.failure(error))
            } else {
                completion(.success(()))
            }
        }
    }
    
    func sendChatMessage(alertId: String, finalChatString: String, completion: @escaping (Result<Void, Error>) -> Void) {
        db.collection("alertas").document(alertId).updateData(["mensajeAlerta": finalChatString]) { error in
            if let error = error {
                completion(.failure(error))
            } else {
                completion(.success(()))
            }
        }
    }
    
    func updateVehicleService(vehicleId: String, enServicio: String, completion: @escaping (Result<Void, Error>) -> Void) {
        db.collection("moviles").document(vehicleId).updateData(["enServicio": enServicio]) { error in
            if let error = error {
                completion(.failure(error))
            } else {
                completion(.success(()))
            }
        }
    }
    
    func updateCentralSession(updates: [String: Any], completion: @escaping (Result<Void, Error>) -> Void) {
        db.collection("accesos").document("central").updateData(updates) { error in
            if let error = error {
                completion(.failure(error))
            } else {
                completion(.success(()))
            }
        }
    }
    
    func createDispatchNew(dispatchId: String, data: [String: Any], completion: @escaping (Result<Void, Error>) -> Void) {
        db.collection("despachos").document(dispatchId).setData(data) { error in
            if let error = error {
                completion(.failure(error))
            } else {
                completion(.success(()))
            }
        }
    }
    
    func addStatusHistoryEntry(userId: String, status: String, completion: @escaping (Result<Void, Error>) -> Void) {
        let ref = db.collection("personal").document(userId).collection("celular")
        ref.getDocuments { snapshot, error in
            if let error = error {
                completion(.failure(error))
                return
            }
            
            var maxId = 0
            if let documents = snapshot?.documents {
                for doc in documents {
                    if let num = Int(doc.documentID) {
                        if num > maxId {
                            maxId = num
                        }
                    }
                }
            }
            
            let nextId = String(maxId + 1)
            
            let date = Date()
            let dateFormatter = DateFormatter()
            dateFormatter.dateFormat = "dd-MM-yyyy"
            let dateString = dateFormatter.string(from: date)
            
            let timeFormatter = DateFormatter()
            timeFormatter.dateFormat = "HH:mm"
            let timeString = timeFormatter.string(from: date)
            
            let timestamp = Int64(date.timeIntervalSince1970 * 1000)
            
            let data: [String: Any] = [
                "estado": status,
                "fecha": dateString,
                "hora": timeString,
                "idEstado": nextId,
                "timestamp": timestamp
            ]
            
            ref.document(nextId).setData(data) { err in
                if let err = err {
                    completion(.failure(err))
                } else {
                    completion(.success(()))
                }
            }
        }
    }

    func setDoorOpen(onSuccess: @escaping () -> Void, onFailure: @escaping (Error) -> Void) {
        db.collection("accesos").document("central").updateData(["puerta": true]) { err in
            if let err = err {
                onFailure(err)
            } else {
                onSuccess()
            }
        }
    }
}

func isAlertActive(duracion: String) -> Bool {
    let durStr = duracion.replacingOccurrences(of: "'", with: "").replacingOccurrences(of: "\"", with: "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    if durStr == "i" || durStr == "c" { return true }
    if durStr.isEmpty { return false }
    
    var dPart = durStr
    var tPart = "23:59:59"
    
    if durStr.contains(" ") {
        let parts = durStr.components(separatedBy: " ")
        if parts.count >= 2 {
            dPart = parts[0]
            tPart = parts[1]
            if tPart.filter({ $0 == ":" }).count == 1 {
                tPart += ":00"
            }
        }
    }
    
    if dPart.contains("-") && dPart.components(separatedBy: "-").first?.count == 2 {
        let parts = dPart.components(separatedBy: "-")
        let tParts = tPart.components(separatedBy: ":")
        if parts.count >= 3 {
            guard let year = Int(parts[2]),
                  let month = Int(parts[1]),
                  let day = Int(parts[0]) else { return true }
            let hour = Int(tParts.first ?? "23") ?? 23
            let min = (tParts.count > 1 ? Int(tParts[1]) : 59) ?? 59
            let sec = (tParts.count > 2 ? Int(tParts[2]) : 59) ?? 59
            
            var comp = DateComponents()
            comp.year = year
            comp.month = month
            comp.day = day
            comp.hour = hour
            comp.minute = min
            comp.second = sec
            
            if let targetDate = Calendar.current.date(from: comp) {
                return targetDate >= Date()
            }
            return true
        }
    }
    
    let formats = ["yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"]
    for f in formats {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = f
        if let d = df.date(from: durStr) {
            return d >= Date()
        }
    }
    return true
}
