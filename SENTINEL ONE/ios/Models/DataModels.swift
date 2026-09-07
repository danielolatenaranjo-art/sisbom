import Foundation

// MARK: - UserPersonal Model
struct UserPersonal: Identifiable, Codable, Equatable {
    var id: String { idRegistro }
    let idRegistro: String
    let nombreBombero: String
    let idRadial: String
    let contrasena: String
    let activo: Bool
    let conductor: Int
    let enServicio: String
    let cargo: String
    let foto: String
    let estado: String
    var deviceId: String
    let puerta: Bool

    enum CodingKeys: String, CodingKey {
        case idRegistro, nombreBombero, idRadial, contrasena, activo, conductor, enServicio, cargo, foto, estado, deviceId, puerta
    }

    init(
        idRegistro: String = "",
        nombreBombero: String = "",
        idRadial: String = "",
        contrasena: String = "",
        activo: Bool = false,
        conductor: Int = 0,
        enServicio: String = "0",
        cargo: String = "",
        foto: String = "",
        estado: String = "",
        deviceId: String = "",
        puerta: Bool = false
    ) {
        self.idRegistro = idRegistro
        self.nombreBombero = nombreBombero
        self.idRadial = idRadial
        self.contrasena = contrasena
        self.activo = activo
        self.conductor = conductor
        self.enServicio = enServicio
        self.cargo = cargo
        self.foto = foto
        self.estado = estado
        self.deviceId = deviceId
        self.puerta = puerta
    }

    // Direct initialization from Firestore document dictionary
    init(docId: String, data: [String: Any]) {
        // idRegistro
        if let regStr = data["idRegistro"] as? String, !regStr.isEmpty {
            self.idRegistro = regStr
        } else if let regNum = data["idRegistro"] as? NSNumber {
            self.idRegistro = regNum.stringValue
        } else {
            self.idRegistro = docId
        }

        self.nombreBombero = data["nombreBombero"] as? String ?? ""

        // idRadial
        if let radStr = data["idRadial"] as? String {
            self.idRadial = radStr
        } else if let radNum = data["idRadial"] as? NSNumber {
            self.idRadial = radNum.stringValue
        } else {
            self.idRadial = ""
        }

        // contrasena
        if let passStr = data["contrasena"] as? String {
            self.contrasena = passStr
        } else if let passNum = data["contrasena"] as? NSNumber {
            self.contrasena = passNum.stringValue
        } else {
            self.contrasena = ""
        }

        // activo (Bool, NSNumber/Int, or String "SI")
        if let b = data["activo"] as? Bool {
            self.activo = b
        } else if let num = data["activo"] as? NSNumber {
            self.activo = (num.intValue == 1)
        } else if let str = data["activo"] as? String {
            let s = str.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            self.activo = (s == "SI" || s == "1" || s == "TRUE" || s == "S")
        } else {
            self.activo = false
        }

        // conductor (Int, Bool, or String "SI"/"NO")
        if let num = data["conductor"] as? NSNumber {
            self.conductor = num.intValue
        } else if let b = data["conductor"] as? Bool {
            self.conductor = b ? 1 : 0
        } else if let str = data["conductor"] as? String {
            let s = str.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            self.conductor = (s == "SI" || s == "1" || s == "TRUE" || s == "S") ? 1 : 0
        } else {
            self.conductor = 0
        }

        // enServicio
        if let s = data["enServicio"] as? String {
            self.enServicio = s
        } else if let num = data["enServicio"] as? NSNumber {
            self.enServicio = num.stringValue
        } else if let b = data["enServicio"] as? Bool {
            self.enServicio = b ? "1" : "0"
        } else {
            self.enServicio = "0"
        }

        self.cargo = data["cargo"] as? String ?? ""
        self.foto = data["foto"] as? String ?? ""
        self.estado = data["estado"] as? String ?? ""
        self.deviceId = data["deviceId"] as? String ?? ""

        if let p = data["puerta"] as? Bool {
            self.puerta = p
        } else if let num = data["puerta"] as? NSNumber {
            self.puerta = (num.intValue == 1)
        } else if let str = data["puerta"] as? String {
            let s = str.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            self.puerta = (s == "SI" || s == "1" || s == "TRUE" || s == "S")
        } else {
            self.puerta = false
        }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)

        // Flexible idRegistro
        if let s = try? container.decode(String.self, forKey: .idRegistro) {
            idRegistro = s
        } else if let n = try? container.decode(Int.self, forKey: .idRegistro) {
            idRegistro = String(n)
        } else {
            idRegistro = ""
        }

        nombreBombero = try container.decodeIfPresent(String.self, forKey: .nombreBombero) ?? ""

        // Flexible idRadial
        if let s = try? container.decode(String.self, forKey: .idRadial) {
            idRadial = s
        } else if let n = try? container.decode(Int.self, forKey: .idRadial) {
            idRadial = String(n)
        } else {
            idRadial = ""
        }

        // Flexible contrasena
        if let s = try? container.decode(String.self, forKey: .contrasena) {
            contrasena = s
        } else if let n = try? container.decode(Int.self, forKey: .contrasena) {
            contrasena = String(n)
        } else {
            contrasena = ""
        }

        // Flexible conductor (Int, Bool, or String "SI"/"NO")
        if let intVal = try? container.decode(Int.self, forKey: .conductor) {
            conductor = intVal
        } else if let boolVal = try? container.decode(Bool.self, forKey: .conductor) {
            conductor = boolVal ? 1 : 0
        } else if let strVal = try? container.decode(String.self, forKey: .conductor) {
            let s = strVal.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            conductor = (s == "SI" || s == "1" || s == "TRUE" || s == "S") ? 1 : 0
        } else {
            conductor = 0
        }

        // Flexible enServicio
        if let s = try? container.decode(String.self, forKey: .enServicio) {
            enServicio = s
        } else if let n = try? container.decode(Int.self, forKey: .enServicio) {
            enServicio = String(n)
        } else if let b = try? container.decode(Bool.self, forKey: .enServicio) {
            enServicio = b ? "1" : "0"
        } else {
            enServicio = "0"
        }

        cargo = try container.decodeIfPresent(String.self, forKey: .cargo) ?? ""
        foto = try container.decodeIfPresent(String.self, forKey: .foto) ?? ""
        estado = try container.decodeIfPresent(String.self, forKey: .estado) ?? ""
        deviceId = try container.decodeIfPresent(String.self, forKey: .deviceId) ?? ""

        // Flexible decoding for 'activo' (could be Bool, Int, or String)
        if let boolVal = try? container.decode(Bool.self, forKey: .activo) {
            activo = boolVal
        } else if let intVal = try? container.decode(Int.self, forKey: .activo) {
            activo = (intVal == 1)
        } else if let strVal = try? container.decode(String.self, forKey: .activo) {
            let s = strVal.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            activo = (s == "1" || s == "SI" || s == "TRUE" || s == "S")
        } else {
            activo = false
        }

        if let boolVal = try? container.decode(Bool.self, forKey: .puerta) {
            puerta = boolVal
        } else if let intVal = try? container.decode(Int.self, forKey: .puerta) {
            puerta = (intVal == 1)
        } else if let strVal = try? container.decode(String.self, forKey: .puerta) {
            let s = strVal.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            puerta = (s == "1" || s == "SI" || s == "TRUE" || s == "S")
        } else {
            puerta = false
        }
    }
}

// MARK: - Unit Info Model
struct UnitInfo: Codable, Equatable {
    var solicitudConductorAt: String = ""
    var solicitudConductorTimestamp: Int64 = 0
    var solicitudPersonalAt: String = ""
    var solicitudPersonalTimestamp: Int64 = 0
    var driverRad: String = ""
    var obacRad: String = ""
    var count: String = ""
    var status: String = ""
}

// MARK: - Dispatch Model
struct Dispatch: Identifiable, Codable, Equatable {
    var id: String { idServicio }
    let idServicio: String
    let clave: String
    let lugar: String
    let preinforme: String
    let carros: String // Can be parsed flexibly inside Repository
    let horaDespacho: String
    let fechaDespacho: String
    let hora67: String
    let quienDespacha: String
    let operadorFinal: String
    
    // Optional helper fields matching despacho.html
    let carrosTexto: String?
    let source: String?
    let obacServicio: String?
    let informeObac: String?
    let fechaTermino: String?
    let operadorInicial: String?
    let lat: Double?
    let lng: Double?
    var unidades: [String: UnitInfo] = [:]

    enum CodingKeys: String, CodingKey {
        case idServicio, clave, lugar, preinforme, carros, horaDespacho, fechaDespacho, hora67, quienDespacha, operadorFinal
        case carrosTexto, source, obacServicio, informeObac, fechaTermino, operadorInicial, lat, lng, unidades
    }

    init(
        idServicio: String = "",
        clave: String = "",
        lugar: String = "",
        preinforme: String = "",
        carros: String = "",
        horaDespacho: String = "",
        fechaDespacho: String = "",
        hora67: String = "",
        quienDespacha: String = "",
        operadorFinal: String = "",
        carrosTexto: String? = nil,
        source: String? = nil,
        obacServicio: String? = nil,
        informeObac: String? = nil,
        fechaTermino: String? = nil,
        operadorInicial: String? = nil,
        lat: Double? = nil,
        lng: Double? = nil,
        unidades: [String: UnitInfo] = [:]
    ) {
        self.idServicio = idServicio
        self.clave = clave
        self.lugar = lugar
        self.preinforme = preinforme
        self.carros = carros
        self.horaDespacho = horaDespacho
        self.fechaDespacho = fechaDespacho
        self.hora67 = hora67
        self.quienDespacha = quienDespacha
        self.operadorFinal = operadorFinal
        self.carrosTexto = carrosTexto
        self.source = source
        self.obacServicio = obacServicio
        self.informeObac = informeObac
        self.fechaTermino = fechaTermino
        self.operadorInicial = operadorInicial
        self.lat = lat
        self.lng = lng
        self.unidades = unidades
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        idServicio = try container.decodeIfPresent(String.self, forKey: .idServicio) ?? ""
        clave = try container.decodeIfPresent(String.self, forKey: .clave) ?? ""
        lugar = try container.decodeIfPresent(String.self, forKey: .lugar) ?? ""
        preinforme = try container.decodeIfPresent(String.self, forKey: .preinforme) ?? ""
        horaDespacho = try container.decodeIfPresent(String.self, forKey: .horaDespacho) ?? ""
        fechaDespacho = try container.decodeIfPresent(String.self, forKey: .fechaDespacho) ?? ""
        hora67 = try container.decodeIfPresent(String.self, forKey: .hora67) ?? ""
        quienDespacha = try container.decodeIfPresent(String.self, forKey: .quienDespacha) ?? ""
        operadorFinal = try container.decodeIfPresent(String.self, forKey: .operadorFinal) ?? ""
        
        carrosTexto = try container.decodeIfPresent(String.self, forKey: .carrosTexto)
        source = try container.decodeIfPresent(String.self, forKey: .source)
        obacServicio = try container.decodeIfPresent(String.self, forKey: .obacServicio)
        informeObac = try container.decodeIfPresent(String.self, forKey: .informeObac)
        fechaTermino = try container.decodeIfPresent(String.self, forKey: .fechaTermino)
        operadorInicial = try container.decodeIfPresent(String.self, forKey: .operadorInicial)
        unidades = try container.decodeIfPresent([String: UnitInfo].self, forKey: .unidades) ?? [:]

        // Flexible decoding for lat/lng
        if let dLat = try? container.decodeIfPresent(Double.self, forKey: .lat) {
            lat = dLat
        } else if let sLat = try? container.decodeIfPresent(String.self, forKey: .lat), let parsedLat = Double(sLat) {
            lat = parsedLat
        } else {
            lat = nil
        }

        if let dLng = try? container.decodeIfPresent(Double.self, forKey: .lng) {
            lng = dLng
        } else if let sLng = try? container.decodeIfPresent(String.self, forKey: .lng), let parsedLng = Double(sLng) {
            lng = parsedLng
        } else {
            lng = nil
        }

        // Flexible decoding for 'carros' (can be String or Array/List)
        if let stringCarros = try? container.decode(String.self, forKey: .carros) {
            carros = stringCarros
        } else if let arrayCarros = try? container.decode([String].self, forKey: .carros) {
            carros = arrayCarros.joined(separator: ", ")
        } else {
            carros = ""
        }
    }
}

// MARK: - Alert Model
struct AlertaItem: Identifiable, Codable, Equatable {
    var id: String { idAlerta }
    let idAlerta: String
    let tipo: String // "orden" or "alerta"
    let gradoAlerta: String
    let aQuienAlerta: String
    let quienAlerta: String
    let razonAlerta: String
    let mensajeAlerta: String
    let fechaAlerta: String
    let horaAlerta: String
    let duracion: String
    let conforme: String // Can contain comma-separated IDs
    let fijar: String // Can contain comma-separated IDs
    let numeroOrden: String
    let fechaOrden: String
    let firmaNombre: String
    let firmaCargo: String

    enum CodingKeys: String, CodingKey {
        case idAlerta, tipo, gradoAlerta, aQuienAlerta, quienAlerta, razonAlerta, mensajeAlerta, fechaAlerta, horaAlerta, duracion, conforme, fijar, numeroOrden, fechaOrden, firmaNombre, firmaCargo
    }

    init(
        idAlerta: String = "",
        tipo: String = "",
        gradoAlerta: String = "1",
        aQuienAlerta: String = "TC",
        quienAlerta: String = "",
        razonAlerta: String = "",
        mensajeAlerta: String = "",
        fechaAlerta: String = "",
        horaAlerta: String = "",
        duracion: String = "",
        conforme: String = "",
        fijar: String = "",
        numeroOrden: String = "",
        fechaOrden: String = "",
        firmaNombre: String = "",
        firmaCargo: String = ""
    ) {
        self.idAlerta = idAlerta
        self.tipo = tipo
        self.gradoAlerta = gradoAlerta
        self.aQuienAlerta = aQuienAlerta
        self.quienAlerta = quienAlerta
        self.razonAlerta = razonAlerta
        self.mensajeAlerta = mensajeAlerta
        self.fechaAlerta = fechaAlerta
        self.horaAlerta = horaAlerta
        self.duracion = duracion
        self.conforme = conforme
        self.fijar = fijar
        self.numeroOrden = numeroOrden
        self.fechaOrden = fechaOrden
        self.firmaNombre = firmaNombre
        self.firmaCargo = firmaCargo
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        idAlerta = try container.decodeIfPresent(String.self, forKey: .idAlerta) ?? ""
        tipo = try container.decodeIfPresent(String.self, forKey: .tipo) ?? ""
        gradoAlerta = try container.decodeIfPresent(String.self, forKey: .gradoAlerta) ?? "1"
        aQuienAlerta = try container.decodeIfPresent(String.self, forKey: .aQuienAlerta) ?? "TC"
        quienAlerta = try container.decodeIfPresent(String.self, forKey: .quienAlerta) ?? ""
        razonAlerta = try container.decodeIfPresent(String.self, forKey: .razonAlerta) ?? ""
        mensajeAlerta = try container.decodeIfPresent(String.self, forKey: .mensajeAlerta) ?? ""
        fechaAlerta = try container.decodeIfPresent(String.self, forKey: .fechaAlerta) ?? ""
        horaAlerta = try container.decodeIfPresent(String.self, forKey: .horaAlerta) ?? ""
        duracion = try container.decodeIfPresent(String.self, forKey: .duracion) ?? ""
        conforme = try container.decodeIfPresent(String.self, forKey: .conforme) ?? ""
        fijar = try container.decodeIfPresent(String.self, forKey: .fijar) ?? ""
        numeroOrden = try container.decodeIfPresent(String.self, forKey: .numeroOrden) ?? ""
        fechaOrden = try container.decodeIfPresent(String.self, forKey: .fechaOrden) ?? ""
        firmaNombre = try container.decodeIfPresent(String.self, forKey: .firmaNombre) ?? ""
        firmaCargo = try container.decodeIfPresent(String.self, forKey: .firmaCargo) ?? ""
    }
}

// MARK: - Vehicle Model
struct Vehicle: Identifiable, Codable, Equatable {
    var id: String { idCarro }
    let idCarro: String
    let clave: String
    let estado: String
    let enServicio: String

    enum CodingKeys: String, CodingKey {
        case idCarro, clave, estado, enServicio
    }

    init(idCarro: String = "", clave: String = "", estado: String = "0-8", enServicio: String = "0") {
        self.idCarro = idCarro
        self.clave = clave
        self.estado = estado
        self.enServicio = enServicio
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        idCarro = try container.decodeIfPresent(String.self, forKey: .idCarro) ?? ""
        clave = try container.decodeIfPresent(String.self, forKey: .clave) ?? ""
        estado = try container.decodeIfPresent(String.self, forKey: .estado) ?? "0-8"
        enServicio = try container.decodeIfPresent(String.self, forKey: .enServicio) ?? "0"
    }
}

// MARK: - AttendanceSheet Model
struct AttendanceSheet: Identifiable, Codable, Equatable {
    var id: String { idLista }
    let idLista: String
    let clave: String
    let tipo: String
    let fecha: String
    let hora: String
    let lugar: String
    let aprobadoPor: String
    let anulada: Bool
    var userEstado: String // Populated locally from attendance subcollection
    var userAbono: Double   // Populated locally from attendance subcollection

    enum CodingKeys: String, CodingKey {
        case idLista, clave, tipo, fecha, hora, lugar, aprobadoPor, anulada, userEstado, userAbono
    }

    init(
        idLista: String = "",
        clave: String = "",
        tipo: String = "",
        fecha: String = "",
        hora: String = "",
        lugar: String = "",
        aprobadoPor: String = "",
        anulada: Bool = false,
        userEstado: String = "",
        userAbono: Double = 0.0
    ) {
        self.idLista = idLista
        self.clave = clave
        self.tipo = tipo
        self.fecha = fecha
        self.hora = hora
        self.lugar = lugar
        self.aprobadoPor = aprobadoPor
        self.anulada = anulada
        self.userEstado = userEstado
        self.userAbono = userAbono
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        idLista = try container.decodeIfPresent(String.self, forKey: .idLista) ?? ""
        clave = try container.decodeIfPresent(String.self, forKey: .clave) ?? ""
        tipo = try container.decodeIfPresent(String.self, forKey: .tipo) ?? ""
        fecha = try container.decodeIfPresent(String.self, forKey: .fecha) ?? ""
        hora = try container.decodeIfPresent(String.self, forKey: .hora) ?? ""
        lugar = try container.decodeIfPresent(String.self, forKey: .lugar) ?? ""
        aprobadoPor = try container.decodeIfPresent(String.self, forKey: .aprobadoPor) ?? ""
        userEstado = try container.decodeIfPresent(String.self, forKey: .userEstado) ?? ""

        // Flexible decoding for 'anulada' (Bool, Int, or String)
        if let boolVal = try? container.decode(Bool.self, forKey: .anulada) {
            anulada = boolVal
        } else if let intVal = try? container.decode(Int.self, forKey: .anulada) {
            anulada = (intVal == 1)
        } else if let strVal = try? container.decode(String.self, forKey: .anulada) {
            anulada = (strVal == "1" || strVal.uppercased() == "SI")
        } else {
            anulada = false
        }

        // Flexible decoding for 'userAbono' (Double, Int, or String)
        if let doubleVal = try? container.decode(Double.self, forKey: .userAbono) {
            userAbono = doubleVal
        } else if let intVal = try? container.decode(Int.self, forKey: .userAbono) {
            userAbono = Double(intVal)
        } else if let strVal = try? container.decode(String.self, forKey: .userAbono) {
            userAbono = Double(strVal) ?? 0.0
        } else {
            userAbono = 0.0
        }
    }
}

// MARK: - ChatMsgItem Model
struct ChatMsgItem: Identifiable, Codable, Equatable {
    var id: String { "\(senderId)-\(time)-\(message.hashValue)" }
    let senderName: String
    let senderId: String
    let message: String
    let time: String
    let isMe: Bool
}
