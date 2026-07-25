/*
  =============================================================================
  SISBOM - CONTROLADOR DE PUERTA AUTOMÁTICA CON ESP8266 (DIRECT HTTPS REST API)
  =============================================================================
  
  Este sketch permite a un módulo ESP8266 conectarse directamente a la API REST
  de Firebase Firestore para escuchar y actualizar el estado del campo 
  "puerta" en el documento "accesos/central".
  
  Esta versión es ultra-ligera, no requiere bibliotecas externas pesadas de Firebase,
  evita problemas de falta de memoria (RAM) y compila en segundos.
*/

#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <WiFiClientSecure.h>

// ============================================================================
// CONFIGURACIÓN DE RED Y FIREBASE
// ============================================================================
const char* ssid = "BomberosPlacilla25";
const char* password = "bomberos2025";

const char* apiKey = "AIzaSyB6alPrjMaahB2r7-9y75_Zw1sPVSJA5CA";
const char* projectId = "sisbom-de5f8";
const char* email = "224@sisbom.com";
const char* pass = "1805_secure_sisbom"; // Contraseña encriptada/segura del sistema

// ============================================================================
// CONFIGURACIÓN DE HARDWARE (RELÉ)
// ============================================================================
#define RELAY_PIN 5  // GPIO5 es D1 en NodeMCU (Pin seguro durante arranque)

// Lógica del Relé (Para relés Normalmente Abiertos / Active HIGH)
#define RELAY_ON HIGH
#define RELAY_OFF LOW

// ============================================================================
// VARIABLES GLOBALES
// ============================================================================
String idToken = "";
unsigned long tokenExpireTime = 0;
unsigned long lastPollTime = 0;
const unsigned long pollInterval = 2000; // Consulta cada 2 segundos

// ============================================================================
// ACCIONAMIENTO DEL RELÉ (DOBLE PULSO)
// ============================================================================
void triggerRelay() {
  Serial.println("\n[RELÉ] >>> INICIANDO ACCIONAMIENTO DE PUERTA (1 SEG) <<<");
  digitalWrite(RELAY_PIN, RELAY_ON);
  delay(1000);
  digitalWrite(RELAY_PIN, RELAY_OFF);
  Serial.println("[RELÉ] >>> ACCIONAMIENTO COMPLETO <<<\n");
}

// ============================================================================
// AUTENTICACIÓN FIREBASE REST
// ============================================================================
bool getFirebaseToken() {
  WiFiClientSecure client;
  client.setInsecure(); // No verificar certificados para evitar caducidad en IoT
  HTTPClient http;
  
  String url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + String(apiKey);
  http.begin(client, url);
  http.addHeader("Content-Type", "application/json");
  
  String payload = "{\"email\":\"" + String(email) + "\",\"password\":\"" + String(pass) + "\",\"returnSecureToken\":true}";
  
  Serial.println("[Auth] Solicitando token de sesión a Firebase...");
  int httpCode = http.POST(payload);
  
  if (httpCode == 200) {
    String res = http.getString();
    
    // Extraer idToken
    int tokenIdx = res.indexOf("\"idToken\": \"");
    if (tokenIdx != -1) {
      tokenIdx += 12;
      int endIdx = res.indexOf("\"", tokenIdx);
      idToken = res.substring(tokenIdx, endIdx);
      
      // Extraer tiempo de expiración
      int expireIdx = res.indexOf("\"expiresIn\": \"");
      if (expireIdx != -1) {
        expireIdx += 14;
        int endExpire = res.indexOf("\"", expireIdx);
        long expiresSec = res.substring(expireIdx, endExpire).toInt();
        tokenExpireTime = millis() + (expiresSec - 300) * 1000; // Refrescar 5 minutos antes
      }
      Serial.println("[Auth] Autenticación Exitosa.");
      http.end();
      return true;
    }
  }
  
  Serial.print("[Auth] ERROR en autenticación. Código HTTP: ");
  Serial.println(httpCode);
  if (httpCode > 0) Serial.println(http.getString());
  http.end();
  return false;
}

// ============================================================================
// RESTABLECER CAMPO PUERTA EN FIRESTORE
// ============================================================================
void resetPuertaField() {
  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;
  
  String url = "https://firestore.googleapis.com/v1/projects/" + String(projectId) + "/databases/(default)/documents/accesos/central?updateMask.fieldPaths=puerta";
  http.begin(client, url);
  http.addHeader("Authorization", "Bearer " + idToken);
  http.addHeader("Content-Type", "application/json");
  
  // Usar POST con sobreescritura de método a PATCH (máxima compatibilidad en microcontroladores)
  http.addHeader("X-HTTP-Method-Override", "PATCH");
  
  String payload = "{\"fields\":{\"puerta\":{\"booleanValue\":false}}}";
  
  Serial.println("[Firestore] Restableciendo campo 'puerta' a false...");
  int httpCode = http.POST(payload);
  
  if (httpCode == 200) {
    Serial.println("[Firestore] Éxito: Campo 'puerta' restablecido a false.");
  } else {
    Serial.print("[Firestore] Error al parchear documento. Código HTTP: ");
    Serial.println(httpCode);
    Serial.println(http.getString());
  }
  http.end();
}

// ============================================================================
// CONFIGURACIÓN INICIAL
// ============================================================================
void setup() {
  Serial.begin(115200);
  Serial.println("\n\nIniciando Controlador de Puerta SisBom...");

  // Configurar Pin del Relé
  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, RELAY_OFF); // Asegurar que inicie apagado

  // Conexión Wi-Fi
  WiFi.begin(ssid, password);
  Serial.print("Conectando a Wi-Fi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nConexión Wi-Fi Establecida!");
  Serial.print("IP del Dispositivo: ");
  Serial.println(WiFi.localIP());
}

// ============================================================================
// BUCLE PRINCIPAL
// ============================================================================
void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[System] Wi-Fi desconectado. Forzando relé APAGADO (Abierto) y reconectando...");
    digitalWrite(RELAY_PIN, RELAY_OFF); // Seguridad: Relé abierto/desactivado sin señal
    WiFi.begin(ssid, password);
    delay(5000);
    return;
  }

  if (millis() - lastPollTime > pollInterval || lastPollTime == 0) {
    lastPollTime = millis();

    // Validar/Refrescar Token de Firebase Auth
    if (idToken == "" || millis() > tokenExpireTime) {
      if (!getFirebaseToken()) {
        Serial.println("[System] Falló autenticación. Forzando relé APAGADO (Abierto)...");
        digitalWrite(RELAY_PIN, RELAY_OFF);
        delay(5000);
        return;
      }
    }

    WiFiClientSecure client;
    client.setInsecure();
    HTTPClient http;
    
    String url = "https://firestore.googleapis.com/v1/projects/" + String(projectId) + "/databases/(default)/documents/accesos/central";
    http.begin(client, url);
    http.addHeader("Authorization", "Bearer " + idToken);
    
    int httpCode = http.GET();
    if (httpCode == 200) {
      String res = http.getString();
      
      // Buscar campo 'puerta' y verificar si es true
      int puertaIdx = res.indexOf("\"puerta\"");
      if (puertaIdx != -1) {
        int boolIdx = res.indexOf("booleanValue", puertaIdx);
        if (boolIdx != -1 && boolIdx < puertaIdx + 100) {
          int trueIdx = res.indexOf("true", boolIdx);
          if (trueIdx != -1 && trueIdx < boolIdx + 30) {
            Serial.println("[Firestore] Alerta: ¡Campo 'puerta' detectado en TRUE!");
            
            // 1. Reconfigurar URL para PATCH sobre la misma conexión Keep-Alive
            String patchUrl = url + "?updateMask.fieldPaths=puerta";
            http.begin(client, patchUrl);
            http.addHeader("Authorization", "Bearer " + idToken);
            http.addHeader("Content-Type", "application/json");
            http.addHeader("X-HTTP-Method-Override", "PATCH"); // Sobreescribir POST a PATCH
            
            String payload = "{\"fields\":{\"puerta\":{\"booleanValue\":false}}}";
            Serial.println("[Firestore] Restableciendo campo 'puerta' a false...");
            int patchCode = http.POST(payload);
            
            if (patchCode == 200) {
              Serial.println("[Firestore] Éxito: Campo 'puerta' restablecido a false.");
            } else {
              Serial.print("[Firestore] Error al parchear. Código HTTP: ");
              Serial.println(patchCode);
              String errPayload = http.getString();
              Serial.println(errPayload);
              
              // Escribir traza de diagnóstico en el perfil del usuario (personal/224)
              String diagUrl = "https://firestore.googleapis.com/v1/projects/" + String(projectId) + "/databases/(default)/documents/personal/224?updateMask.fieldPaths=espDebug";
              http.begin(client, diagUrl);
              http.addHeader("Authorization", "Bearer " + idToken);
              http.addHeader("Content-Type", "application/json");
              http.addHeader("X-HTTP-Method-Override", "PATCH");
              String diagPayload = "{\"fields\":{\"espDebug\":{\"stringValue\":\"PATCH Err: " + String(patchCode) + " - " + errPayload.substring(0, 100) + "\"}}}";
              http.POST(diagPayload);
            }
            
            // 2. Cerrar la conexión HTTP para liberar RAM antes del delay
            http.end(); 
            
            // 3. Accionar Relé físicamente
            triggerRelay();
            return;
          }
        }
      }
      Serial.println("[Firestore] Puerta: cerrada (false).");
    } else if (httpCode == 401) {
      Serial.println("[Firestore] Token de sesión expirado. Forzando actualización...");
      idToken = "";
      digitalWrite(RELAY_PIN, RELAY_OFF); // Seguridad extra
    } else {
      Serial.print("[Firestore] Error de lectura. Código HTTP: ");
      Serial.println(httpCode);
      digitalWrite(RELAY_PIN, RELAY_OFF); // Seguridad extra: Apagar relé en caso de error de red
    }
    http.end();
  }
}
