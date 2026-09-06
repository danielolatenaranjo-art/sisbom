# 🛡️ SENTINEL — Sistema Canónico de Gobernanza y Arquitectura

> **Documento Oficial de Reglas, Arquitectura y Esquema Canónico (SSOT)**  
> **Versión:** 1.1.0 | **Despliegue Multi-Cuerpo (SaaS)**

---

## 🌳 1. Módulos de la Suite SENTINEL

```mermaid
graph TD
    SAAS["⚙️ SENTINEL SAAS\n(Panel Maestro de Clientes y Licencias)"]
    
    subgraph Gestion_y_Despacho ["Gestión y Despacho"]
        CORE["👑 SENTINEL CORE\n(Comandancia / Dotación)"]
        CAD["🚨 SENTINEL CAD\n(Central de Alarmas)"]
        LINK["📞 SENTINEL LINK\n(Enlace Teléfono Fijo / 132)"]
    end

    subgraph En_Terreno ["Operaciones en Terreno"]
        ONE["📱 SENTINEL ONE\n(App Móvil Bomberos)"]
        NAV["🚒 SENTINEL NAV\n(Tablet Carros)"]
        CREW["📋 SENTINEL CREW\n(Asistencia Cuartel)"]
        SCI["🎖️ SENTINEL SCI\n(Comando de Incidentes)"]
    end

    SAAS -->|"Activa y Configura"| CAD
    SAAS -->|"Activa y Configura"| ONE
    SAAS -->|"Activa y Configura"| CORE
    CAD <-->|"Sincronización en Vivo"| ONE
    CAD <-->|"Sincronización en Vivo"| NAV
```

---

## ⚡ 2. Flujo Simple de un Despacho

```mermaid
sequenceDiagram
    autonumber
    actor Alerta as Origen (Fijo / Celular / Radio VHF San Fernando)
    participant CAD as 🚨 SENTINEL CAD (Central Placilla)
    participant DB as 🔥 Firestore (SSOT)
    participant ONE as 📱 SENTINEL ONE (Bomberos)
    participant NAV as 🚒 SENTINEL NAV (Carros)

    Alerta->>CAD: Notifica emergencia y ubicación
    CAD->>DB: Operador digita Clave y Carros asignados
    par En simultáneo
        DB->>ONE: Alarma Sonora c10_X + Notificación
        DB->>NAV: Ruteo y Grifos hacia el lugar
    end
    ONE->>DB: Bombero confirma "ASISTIR" / "NO ASISTIR"
    NAV->>DB: Carro marca 6-0 (Salida) y 6-3 (En el lugar)
    CAD->>DB: Cierre del servicio
```

---

## 📋 3. Diccionario Canónico (Campos Clave)

### `despachos`
* `idServicio`: String (ID único del llamado).
* `clave`: String (`10-0-1`, `10-30`, `10-2`).
* `lugar`: String (Dirección o sector).
* `preinforme`: String (**Siempre minúscula**).
* `carros`: String (`B-1, BX-1`).
* `horaDespacho`: String (`HH:mm:ss`).
* `fechaDespacho`: String (`dd/MM/yyyy`).
* `operadorFinal`: String (Vacío `""` = Activo | Con texto = Cerrado).
* `unidades`: Map con estado (`6-0`, `6-3`), chofer y OBAC de cada carro.

### `personal`
* `idRegistro`: String (Ficha del bombero).
* `nombreBombero`: String (Nombre oficial).
* `idRadial`: String (`1`, `101`, `C1`).
* `estado`: String (`0-9`, `0-8`, `CDS`, `LICENCIA`, `SUSPENDIDO`).
* `enServicio`: String (`"0"` = Libre | `"{id}"` = Asiste | `"-{id}"` = No asiste).
* `activo`: Boolean (`true` / `false`).
* `cargo`: String (`"COMANDANTE"`, `"VOLUNTARIO"`, `"BOMBERO HONORARIO"`).

### `organizaciones` (SaaS Multi-Tenant)
* `idOrganizacion`: String (Ej: `"cb_placilla"`).
* `nombre`: String (`"Cuerpo de Bomberos de Placilla"`).
* `licenciaActiva`: Boolean.
* `tipoRecepcionAlarma`: String (`"RADIO_VHF"`, `"FIJO_LOCAL"`, `"132"`).
* `telefonoCentral`: String.
