# SaaS Schema & Configuration

This document specifies the Firestore collection schema and Firebase Cloud Function configuration for the SisBom SaaS Licensing & Multitenancy system.

## Firestore Collection: `saas_clientes`

This collection holds the settings, active modules, devices, and custom database configuration for each client. Access to this collection is restricted via `firestore.rules` (default deny) so that only the Admin SDK (Cloud Functions) can read and write to it.

### Document Path
`saas_clientes/{licenseKey}`

Example License Key: `SB-PLACILLA-OH-8829` (the key is the document ID).

### Fields

| Field Name | Type | Description |
| :--- | :--- | :--- |
| `nombreCliente` | `string` | The organization name, e.g. "Cuerpo de Bomberos Placilla". |
| `nombreMostrar` | `string` | The commercial/display name, e.g. "BOMBEROS PLACILLA". |
| `logoUrl` | `string` | Public download URL of the uploaded custom organization logo/shield. |
| `estadoSuscripcion` | `string` | Subscription status: `"activo"`, `"read_only"`, or `"bloqueado"`. |
| `hardwareUUIDs` | `array of strings` | Whitelist of unique hardware IDs (HWID/MAC) authorized to run under this license. Populated manually via SaaS dashboard. |
| `modulos` | `map` | Module activation status. |
| `modulos.central` | `boolean` | If `true`, the `Central.exe` app is allowed to run. |
| `modulos.comandancia` | `boolean` | If `true`, the `Comandancia.exe` app is allowed to run. |
| `modulos.apk` | `boolean` | If `true`, the `MiSisBom.apk` app is allowed to run. |
| `firebaseConfig` | `map` | The target Firebase project credentials for this tenant. |
| `firebaseConfig.apiKey` | `string` | Firebase Web API Key. |
| `firebaseConfig.authDomain` | `string` | Firebase Auth Domain. |
| `firebaseConfig.projectId` | `string` | Firebase Project ID. |
| `firebaseConfig.storageBucket`| `string` | Firebase Storage Bucket. |
| `firebaseConfig.messagingSenderId` | `string` | Firebase Messaging Sender ID. |
| `firebaseConfig.appId` | `string` | Firebase App ID. |

---

## Firebase Cloud Function: `validateLicense`

An HTTPS endpoint that processes license validation requests from desktop and mobile apps.

### Endpoint URL
`https://<region>-<saas-project>.cloudfunctions.net/validateLicense`

### Request Payload (JSON POST)
```json
{
  "licenseKey": "SB-PLACILLA-OH-8829",
  "hwid": "00000000-0000-0000-0000-D8BBC189684C",
  "module": "central"
}
```

### Response (200 OK - Authorized)
```json
{
  "authorized": true,
  "status": "activo",
  "clientName": "Cuerpo de Bomberos Placilla",
  "firebaseConfig": {
    "apiKey": "AIzaSy...",
    "authDomain": "sisbom-de5f8.firebaseapp.com",
    "projectId": "sisbom-de5f8",
    "storageBucket": "sisbom-de5f8.firebasestorage.app",
    "messagingSenderId": "39449538456",
    "appId": "1:39449538456:web:64f767264356ae587485ed"
  }
}
```

### Response (403 Forbidden - Locked/Suspended)
```json
{
  "authorized": false,
  "reason": "La suscripción de este cliente ha sido bloqueada por falta de pago o suspensión del servicio."
}
```
