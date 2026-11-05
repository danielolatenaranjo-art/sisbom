const {onDocumentCreated, onDocumentUpdated, onDocumentWritten} = require("firebase-functions/v2/firestore");
const {setGlobalOptions} = require("firebase-functions/v2");
const admin = require("firebase-admin");
const crypto = require("crypto");
const http2 = require("http2");

admin.initializeApp();
setGlobalOptions({maxInstances: 10});

// ============================================================================
// DIRECT HTTP/2 APNs SENDER (BYPASSES FIREBASE CONSOLE APNs CREDENTIAL ISSUES)
// Key ID: 5LAKW2U93F | Team ID: NFR3L3CC3L | Environment: Sandbox & Production
// ============================================================================
const APNS_KEY_ID = "5LAKW2U93F";
const APNS_TEAM_ID = "NFR3L3CC3L";
const APNS_BUNDLE_ID = "com.misisbom.sisbom.NFR3L3CC3L";
const APNS_P8_KEY = [
  "-----BEGIN PRIVATE KEY-----",
  "MIGTAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBHkwdwIBAQQgZq+ZjJqT3FVw7uPJ",
  "asrKEqqdSX1W+Tw91JLjhNuO/gKgCgYIKoZIzj0DAQehRANCAAQqqufVBTeS4nD6",
  "u278mfmTx8IzESZBMjI33kiFaLES4bolidmABM12l4PIzIPrHSQzKBAxsk7mGNPP",
  "RqGC5IA9",
  "-----END PRIVATE KEY-----"
].join("\n");

let cachedApnsJwt = "";
let cachedApnsJwtIssuedAt = 0;

function getApnsJwt() {
  const nowSec = Math.floor(Date.now() / 1000);
  if (cachedApnsJwt && (nowSec - cachedApnsJwtIssuedAt) < 2700) {
    return cachedApnsJwt;
  }
  const header = Buffer.from(JSON.stringify({alg: "ES256", kid: APNS_KEY_ID})).toString("base64url");
  const claims = Buffer.from(JSON.stringify({iss: APNS_TEAM_ID, iat: nowSec})).toString("base64url");
  const sign = crypto.createSign("SHA256");
  sign.update(`${header}.${claims}`);
  const sig = sign.sign({key: APNS_P8_KEY, dsaEncoding: "ieee-p1363"}).toString("base64url");
  cachedApnsJwt = `${header}.${claims}.${sig}`;
  cachedApnsJwtIssuedAt = nowSec;
  return cachedApnsJwt;
}

function sendSingleApnsHttp2(host, apnsToken, payloadObj, collapseId, priority = "10") {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (res) => {
      if (!settled) {
        settled = true;
        resolve(res);
      }
    };
    try {
      const client = http2.connect(host);
      client.on("error", (err) => {
        try { client.close(); } catch (_) {}
        finish({status: 0, reason: err.message});
      });
      client.setTimeout(6000, () => {
        try { client.close(); } catch (_) {}
        finish({status: 0, reason: "timeout"});
      });
      const headers = {
        ":method": "POST",
        ":path": `/3/device/${apnsToken}`,
        "authorization": `bearer ${getApnsJwt()}`,
        "apns-topic": APNS_BUNDLE_ID,
        "apns-push-type": "alert",
        "apns-priority": String(priority)
      };
      if (collapseId) {
        headers["apns-collapse-id"] = String(collapseId).slice(0, 64);
      }
      const req = client.request(headers);
      let status = 0;
      let respBody = "";
      req.on("response", (h) => { status = Number(h[":status"] || 0); });
      req.on("data", (chunk) => { respBody += chunk; });
      req.on("end", () => {
        try { client.close(); } catch (_) {}
        finish({status, body: respBody});
      });
      req.on("error", (err) => {
        try { client.close(); } catch (_) {}
        finish({status: 0, reason: err.message});
      });
      req.end(JSON.stringify(payloadObj));
    } catch (err) {
      finish({status: 0, reason: err.message});
    }
  });
}

async function sendDirectApnsToDevice(apnsToken, payloadObj, collapseId, priority = "10") {
  const cleanHex = String(apnsToken || "").trim().toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(cleanHex)) return;
  const prodRes = await sendSingleApnsHttp2("https://api.push.apple.com", cleanHex, payloadObj, collapseId, priority);
  if (prodRes.status === 400 && String(prodRes.body || "").includes("BadDeviceToken")) {
    await sendSingleApnsHttp2("https://api.sandbox.push.apple.com", cleanHex, payloadObj, collapseId, priority);
  }
}

async function sendDirectApnsToIosUsers({
  title,
  body,
  sound = "despacho.mp3",
  category = "EMERGENCY_DISPATCH",
  interruptionLevel = "time-sensitive",
  relevanceScore = 1.0,
  priority = "10",
  collapseId = "",
  data = {},
  targetUserIds = null,
  onlyConductors = false
}) {
  try {
    const db = admin.firestore();
    let docs = [];
    if (Array.isArray(targetUserIds) && targetUserIds.length > 0) {
      const snaps = await Promise.all(
        targetUserIds.map((uid) => db.collection("personal").doc(String(uid).trim()).get().catch(() => null))
      );
      docs = snaps.filter((s) => s && s.exists);
    } else {
      const snap = await db.collection("personal").get();
      docs = snap.docs;
    }

    const apnsTokens = new Set();
    for (const d of docs) {
      const p = d.data() || {};
      if (p.modoAvion === true || p.silencioAbsoluto === true) continue;
      if (onlyConductors && !p.conductor) continue;
      const candidate = String(p.apnsToken || "").trim().toLowerCase();
      if (/^[0-9a-f]{64}$/.test(candidate)) {
        apnsTokens.add(candidate);
      }
    }

    if (apnsTokens.size === 0) return;

    const payloadObj = Object.assign({}, data, {
      aps: {
        alert: {
          title: String(title || "SENTINEL ONE"),
          body: String(body || "")
        },
        sound: String(sound || "default"),
        badge: 1,
        category: String(category || "EMERGENCY_DISPATCH"),
        "interruption-level": String(interruptionLevel || "time-sensitive"),
        "relevance-score": Number(relevanceScore || 1.0),
        "content-available": 1,
        "mutable-content": 1
      }
    });

    await Promise.all(
      Array.from(apnsTokens).map((tok) => sendDirectApnsToDevice(tok, payloadObj, collapseId, priority))
    );
  } catch (err) {
    console.warn("APNs direct send warning:", err.message);
  }
}

function resolveApnsSound(clave) {
  if (!clave) return "despacho.mp3";
  const c = String(clave).toUpperCase().trim();
  if (c.includes("FORESTAL")) return "c10_2Forestal.mp3";
  if (c.includes("10-30")) return "c10_30.mp3";
  if (c.includes("9-0")) return "c9_0.mp3";
  if (c.includes("LLAMADO") && c.includes("COMANDANCIA")) return "c_llamadoComandancia.mp3";
  if (c.startsWith("10-10")) return "c10_10.mp3";
  if (c.startsWith("10-12")) return "c10_12.mp3";
  if (c.startsWith("10-15")) return "c10_15.mp3";
  if (c.startsWith("10-0")) return "c10_0.mp3";
  if (c.startsWith("10-1")) return "c10_1.mp3";
  if (c.startsWith("10-2")) return "c10_2.mp3";
  if (c.startsWith("10-3")) return "c10_3.mp3";
  if (c.startsWith("10-4")) return "c10_4.mp3";
  if (c.startsWith("10-5")) return "c10_5.mp3";
  if (c.startsWith("10-6")) return "c10_6.mp3";
  if (c.startsWith("10-7")) return "c10_7.mp3";
  if (c.startsWith("10-8")) return "c10_8.mp3";
  if (c.startsWith("10-9")) return "c10_9.mp3";
  return "despacho.mp3";
}

function isValidFcmRegistrationToken(raw) {
  const tok = String(raw || "").trim();
  return tok.length > 60 && tok.includes(":");
}

async function subscribeFcmTokenForUser(token, userId, isConductor, isCentral) {
  if (!isValidFcmRegistrationToken(token)) return;
  const cleanId = String(userId || "").trim().replace(/\s+/g, "");
  const topics = ["all", "despachos", "alertas", "alertas_generales"];
  if (cleanId) {
    topics.push(`usuario_${cleanId}`);
    topics.push(`personal_${cleanId}`);
  }
  if (isConductor) topics.push("conductores");
  if (isCentral) topics.push("central_operador");

  await Promise.all(
    topics.map((t) =>
      admin.messaging().subscribeToTopic([token], t).catch(() => null)
    )
  );
  if (!isConductor) {
    await admin.messaging().unsubscribeFromTopic([token], "conductores").catch(() => null);
  }
  if (!isCentral) {
    await admin.messaging().unsubscribeFromTopic([token], "central_operador").catch(() => null);
  }
}

function extractUnitsString(data) {
  if (!data) return "";
  if (Array.isArray(data.carros) && data.carros.length > 0) {
    return data.carros.join(" / ");
  }
  if (typeof data.carrosTexto === "string" && data.carrosTexto.trim() !== "") {
    return data.carrosTexto.split(/[,/]+/).map((s) => s.trim()).filter(Boolean).join(" / ");
  }
  if (typeof data.carros === "string" && data.carros.trim() !== "") {
    return data.carros.split(/[,/]+/).map((s) => s.trim()).filter(Boolean).join(" / ");
  }
  if (Array.isArray(data.unidades)) {
    return data.unidades.join(" / ");
  }
  if (data.unidades && typeof data.unidades === "object") {
    return Object.keys(data.unidades).join(" / ");
  }
  return "";
}

function getChileDateTimeStrings(epochMs) {
  const ts = Number(epochMs) > 0 ? Number(epochMs) : Date.now();
  const d = new Date(ts);
  try {
    const parts = new Intl.DateTimeFormat("es-CL", {
      timeZone: "America/Santiago",
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false
    }).formatToParts(d);
    const map = {};
    for (const p of parts) {
      map[p.type] = p.value;
    }
    const day = (map.day || "01").padStart(2, "0");
    const month = (map.month || "01").padStart(2, "0");
    const year = map.year || String(d.getUTCFullYear());
    let hour = (map.hour || "00").padStart(2, "0");
    if (hour === "24") hour = "00";
    const minute = (map.minute || "00").padStart(2, "0");
    return {
      fechaDespacho: `${day}-${month}-${year}`,
      horaDespacho: `${hour}:${minute}`
    };
  } catch (e) {
    const cl = new Date(ts - 3 * 3600 * 1000);
    const day = String(cl.getUTCDate()).padStart(2, "0");
    const month = String(cl.getUTCMonth() + 1).padStart(2, "0");
    const year = String(cl.getUTCFullYear());
    const hour = String(cl.getUTCHours()).padStart(2, "0");
    const minute = String(cl.getUTCMinutes()).padStart(2, "0");
    return {
      fechaDespacho: `${day}-${month}-${year}`,
      horaDespacho: `${hour}:${minute}`
    };
  }
}

// 1. NUEVO DESPACHO (Dual-Read V2 + V3 Canónico + Retrocompatibilidad Total con V2.1.0, V2.2.5 y V3.0.4)
exports.onNewDispatch = onDocumentCreated("despachos/{dispatchId}", async (event) => {
  const snap = event.data;
  if (!snap) return;
  const data = snap.data() || {};
  const dispatchId = event.params.dispatchId;

  const estado = String(data.estado || "").toUpperCase().trim();
  const finalizadoAt = Number(data.finalizadoAt || 0);
  if ((data.operadorFinal && data.operadorFinal !== "") || finalizadoAt > 0 || estado === "CERRADO" || estado === "FINALIZADO") {
    return;
  }

  const epochMs = Number(data.createdAt || data.timestamp || Date.now());
  const clDt = getChileDateTimeStrings(epochMs);
  const clave = data.clave || "Emergencia";
  const direccion = data.direccion || data.lugar || (estado === "PRE-DESPACHO" ? "Pre-despacho en preparación..." : "Sin dirección");
  const unidades = extractUnitsString(data);
  const fechaDespacho = String(data.fechaDespacho || data.fecha || clDt.fechaDespacho);
  const horaDespacho = String(data.horaDespacho || data.hora || clDt.horaDespacho);
  const createdAt = String(epochMs);
  const apnsSound = resolveApnsSound(clave);

  const message = {
    topic: "despachos",
    data: {
      type: "DISPATCH",
      payloadId: String(dispatchId),
      dispatchId: String(dispatchId),
      idServicio: String(dispatchId),
      clave: String(clave),
      key: String(clave),
      lugar: String(direccion),
      direccion: String(direccion),
      carrosTexto: String(unidades),
      unidades: String(unidades),
      title: `DESPACHO: ${clave}`,
      body: unidades ? `${direccion}\nUnidades: ${unidades}` : `${direccion}`,
      fechaDespacho,
      horaDespacho,
      createdAt: String(createdAt)
    },
    android: {
      priority: "high",
      ttl: 300000
    },
    apns: {
      headers: {
        "apns-priority": "10",
        "apns-push-type": "alert"
      },
      payload: {
        aps: {
          alert: {
            title: `🚨 DESPACHO: ${clave}`,
            body: unidades ? `${direccion}\nUnidades: ${unidades}` : `${direccion}`
          },
          sound: apnsSound,
          badge: 1,
          category: "EMERGENCY_DISPATCH",
          "interruption-level": "time-sensitive",
          "relevance-score": 1.0,
          "content-available": 1,
          "mutable-content": 1
        }
      }
    }
  };

  await Promise.all([
    admin.messaging().send(message).catch((e) => console.warn("FCM onNewDispatch warning:", e.message)),
    sendDirectApnsToIosUsers({
      title: `🚨 DESPACHO: ${clave}`,
      body: unidades ? `${direccion}\nUnidades: ${unidades}` : `${direccion}`,
      sound: apnsSound,
      category: "EMERGENCY_DISPATCH",
      collapseId: `disp_${dispatchId}`,
      data: message.data
    })
  ]);
});

// 2. ACTUALIZACIÓN DE DESPACHO (Escalamiento 10-0 -> 10-30, 10-2 -> FORESTAL y Solicitudes 12-10 / 6-6)
exports.onDispatchUpdated = onDocumentUpdated("despachos/{dispatchId}", async (event) => {
  const before = event.data.before.data() || {};
  const after = event.data.after.data() || {};
  const dispatchId = event.params.dispatchId;

  const estadoAfter = String(after.estado || "").toUpperCase().trim();
  const finalizadoAt = Number(after.finalizadoAt || 0);
  if ((after.operadorFinal && String(after.operadorFinal).trim() !== "") || finalizadoAt > 0 || estadoAfter === "CERRADO") return;

  const clave = after.clave || "Emergencia";
  const direccion = after.direccion || after.lugar || (estadoAfter === "PRE-DESPACHO" ? "Pre-despacho en preparación..." : "Sin dirección");
  const unidadesStr = extractUnitsString(after);
  const fechaDespacho = String(after.fechaDespacho || after.fecha || "");
  const horaDespacho = String(after.horaDespacho || after.hora || "");
  const createdAt = after.timestamp || after.createdAt
    ? String(after.timestamp || after.createdAt)
    : new Date().toISOString();

  // B. Detectar escalamiento (10-30 / ALARMA FORESTAL)
  const oldClave = String(before.clave || "").trim();
  const newClave = String(after.clave || "").trim();
  if (oldClave && oldClave !== newClave) {
    const isEscalation =
      newClave.includes("10-30") ||
      newClave.includes("FORESTAL") ||
      newClave.includes("9-0");
    if (isEscalation) {
      const escData = {
        type: "DISPATCH_UPDATE",
        payloadId: String(dispatchId),
        clave: newClave,
        title: `CAMBIO DE CLAVE A ${newClave}`,
        body: `${direccion} (${unidadesStr}) - ¡SE REQUIERE PERSONAL!`,
        fechaDespacho,
        horaDespacho,
        createdAt: String(createdAt)
      };
      await Promise.all([
        admin.messaging().send({
          topic: "despachos",
          data: escData,
          android: {priority: "high", ttl: 300000},
          apns: {
            headers: {"apns-priority": "10", "apns-push-type": "alert"},
            payload: {
              aps: {
                alert: {
                  title: `🔥 CAMBIO DE CLAVE A ${newClave}`,
                  body: `${direccion} (${unidadesStr}) - ¡SE REQUIERE PERSONAL!`
                },
                sound: resolveApnsSound(newClave),
                badge: 1,
                category: "EMERGENCY_DISPATCH",
                "interruption-level": "time-sensitive",
                "relevance-score": 1.0,
                "content-available": 1,
                "mutable-content": 1
              }
            }
          }
        }).catch((e) => console.warn("FCM escalation warning:", e.message)),
        sendDirectApnsToIosUsers({
          title: `🔥 CAMBIO DE CLAVE A ${newClave}`,
          body: `${direccion} (${unidadesStr}) - ¡SE REQUIERE PERSONAL!`,
          sound: resolveApnsSound(newClave),
          category: "EMERGENCY_DISPATCH",
          collapseId: `esc_${dispatchId}_${newClave}`,
          data: escData
        })
      ]);
    }
  }

  // C. Detectar solicitudes 12-10 (Conductor) y 6-6 (Personal) en unidades o unidadesEnServicio
  const uBefore = before.unidadesEnServicio || (typeof before.unidades === "object" && !Array.isArray(before.unidades) ? before.unidades : {});
  const uAfter = after.unidadesEnServicio || (typeof after.unidades === "object" && !Array.isArray(after.unidades) ? after.unidades : {});

  for (const unitName of Object.keys(uAfter)) {
    const unitB = uBefore[unitName] || {};
    const unitA = uAfter[unitName] || {};

    const cTsBefore = Number(unitB.solicitudConductorTimestamp || 0);
    const cTsAfter = Number(unitA.solicitudConductorTimestamp || 0);
    if (cTsAfter > cTsBefore && cTsAfter > 0) {
      const req1210Data = {
        type: "DISPATCH_UPDATE",
        payloadId: String(dispatchId),
        clave: String(clave),
        title: `SOLICITUD 12-10 (${unitName})`,
        body: `Se solicita conductor para unidad ${unitName} en ${direccion}`,
        fechaDespacho,
        horaDespacho,
        createdAt: new Date().toISOString()
      };
      await Promise.all([
        admin.messaging().send({
          topic: "conductores",
          data: req1210Data,
          android: {priority: "high", ttl: 300000},
          apns: {
            headers: {"apns-priority": "10", "apns-push-type": "alert"},
            payload: {
              aps: {
                alert: {
                  title: `🚨 SOLICITUD 12-10 (${unitName})`,
                  body: `Se solicita conductor para unidad ${unitName} en ${direccion}`
                },
                sound: "importante.mp3",
                badge: 1,
                category: "EMERGENCY_DISPATCH",
                "interruption-level": "time-sensitive",
                "relevance-score": 1.0,
                "content-available": 1,
                "mutable-content": 1
              }
            }
          }
        }).catch((e) => console.warn("FCM 12-10 warning:", e.message)),
        sendDirectApnsToIosUsers({
          title: `🚨 SOLICITUD 12-10 (${unitName})`,
          body: `Se solicita conductor para unidad ${unitName} en ${direccion}`,
          sound: "importante.mp3",
          category: "EMERGENCY_DISPATCH",
          collapseId: `req1210_${dispatchId}_${unitName}`,
          onlyConductors: true,
          data: req1210Data
        })
      ]);
    }

    const pTsBefore = Number(unitB.solicitudPersonalTimestamp || 0);
    const pTsAfter = Number(unitA.solicitudPersonalTimestamp || 0);
    if (pTsAfter > pTsBefore && pTsAfter > 0) {
      const req66Data = {
        type: "DISPATCH_UPDATE",
        payloadId: String(dispatchId),
        clave: String(clave),
        title: `SOLICITUD 6-6 (${unitName})`,
        body: `Se solicita personal para unidad ${unitName} en ${direccion}`,
        fechaDespacho,
        horaDespacho,
        createdAt: new Date().toISOString()
      };
      await Promise.all([
        admin.messaging().send({
          topic: "despachos",
          data: req66Data,
          android: {priority: "high", ttl: 300000},
          apns: {
            headers: {"apns-priority": "10", "apns-push-type": "alert"},
            payload: {
              aps: {
                alert: {
                  title: `🚨 SOLICITUD 6-6 (${unitName})`,
                  body: `Se solicita personal para unidad ${unitName} en ${direccion}`
                },
                sound: "importante.mp3",
                badge: 1,
                category: "EMERGENCY_DISPATCH",
                "interruption-level": "time-sensitive",
                "relevance-score": 1.0,
                "content-available": 1,
                "mutable-content": 1
              }
            }
          }
        }).catch((e) => console.warn("FCM 6-6 warning:", e.message)),
        sendDirectApnsToIosUsers({
          title: `🚨 SOLICITUD 6-6 (${unitName})`,
          body: `Se solicita personal para unidad ${unitName} en ${direccion}`,
          sound: "importante.mp3",
          category: "EMERGENCY_DISPATCH",
          collapseId: `req66_${dispatchId}_${unitName}`,
          data: req66Data
        })
      ]);
    }
  }
});

// 3. NUEVA ALERTA U ORDEN DEL DÍA
exports.onNewAlert = onDocumentCreated("alertas/{alertId}", async (event) => {
  const snap = event.data;
  if (!snap) return;
  const data = snap.data() || {};
  const alertId = event.params.alertId;

  const isOrden = String(data.tipo || "").toLowerCase() === "orden" || Boolean(data.numeroOrden);
  const titulo = isOrden
    ? `ORDEN DEL DÍA N° ${data.numeroOrden || ""}`.trim()
    : (data.titulo || data.razonAlerta || "Nueva Alerta");
  const mensaje = String(data.cuerpo || data.mensaje || data.mensajeAlerta || "Revisa el muro de alertas")
    .replace(/\|/g, " ")
    .slice(0, 140);
  const grado = isOrden ? "3" : String(data.grado || data.gradoAlerta || "1").replace(/^'+/, "");
  const destinatarios = data.destinatarios || data.aQuienAlerta || "TC";

  let senderId = String(data.creadorId || data.autorId || "");
  if (!senderId && data.quienAlerta) {
    const match = String(data.quienAlerta).match(/\b(\d+)\b/);
    if (match) senderId = match[1];
  }

  let topics = [];
  if (!destinatarios || destinatarios === "TODOS" || destinatarios === "TC") {
    topics.push("alertas_generales");
  } else if (Array.isArray(destinatarios)) {
    topics = destinatarios.map((id) => `usuario_${String(id).trim()}`);
  } else if (typeof destinatarios === "string") {
    topics = destinatarios.split(",").map((id) => `usuario_${id.trim()}`).filter((t) => t !== "usuario_");
  }

  const isG3 = String(grado) === "3";
  const isG2 = String(grado) === "2";

  const promises = topics.map((topic) => {
    const msg = {
      topic,
      data: {
        type: isOrden ? "ORDEN" : "ALERT",
        payloadId: String(alertId),
        gradoAlerta: String(grado),
        senderId: String(senderId),
        title: isOrden ? `📋 ${titulo}` : `ALERTA GRADO ${grado}: ${titulo}`,
        body: mensaje
      },
      android: {
        priority: isG3 || isG2 ? "high" : "normal"
      },
      apns: {
        headers: {
          "apns-priority": isG3 || isG2 ? "10" : "5",
          "apns-push-type": "alert"
        },
        payload: {
          aps: {
            alert: {
              title: isOrden ? `📋 ${titulo}` : `🔔 ALERTA GRADO ${grado}: ${titulo}`,
              body: mensaje
            },
            sound: (isG3 || isG2) ? "importante.mp3" : "alertas.mp3",
            badge: 1,
            category: "GENERAL_ALERT",
            "interruption-level": (isG3 || isG2) ? "time-sensitive" : "active",
            "relevance-score": isG3 ? 1.0 : 0.8,
            "content-available": 1,
            "mutable-content": 1
          }
        }
      }
    };
    return admin.messaging().send(msg).catch((e) => console.warn("FCM alert warning:", e.message));
  });

  const targetIds = (!destinatarios || destinatarios === "TODOS" || destinatarios === "TC")
    ? null
    : (Array.isArray(destinatarios)
      ? destinatarios.map((id) => String(id).trim())
      : String(destinatarios).split(",").map((id) => id.trim()).filter(Boolean));

  promises.push(
    sendDirectApnsToIosUsers({
      title: isOrden ? `📋 ${titulo}` : `🔔 ALERTA GRADO ${grado}: ${titulo}`,
      body: mensaje,
      sound: (isG3 || isG2) ? "importante.mp3" : "alertas.mp3",
      category: "GENERAL_ALERT",
      interruptionLevel: (isG3 || isG2) ? "time-sensitive" : "active",
      relevanceScore: isG3 ? 1.0 : 0.8,
      priority: (isG3 || isG2) ? "10" : "5",
      collapseId: `alert_${alertId}`,
      targetUserIds: targetIds,
      data: {
        type: isOrden ? "ORDEN" : "ALERT",
        payloadId: String(alertId),
        gradoAlerta: String(grado),
        senderId: String(senderId),
        title: isOrden ? `📋 ${titulo}` : `ALERTA GRADO ${grado}: ${titulo}`,
        body: mensaje
      }
    })
  );

  await Promise.all(promises);
});

// 4. SINCRONIZACIÓN DE ESTADO DE PERSONAL (0-9 / 0-8) DESDE LA CENTRAL (CON VIBRACIÓN Y NOTIFICACIÓN VISUAL)
exports.onPersonalUpdated = onDocumentUpdated("personal/{userId}", async (event) => {
  const before = event.data.before.data() || {};
  const after = event.data.after.data() || {};
  const userId = String(after.idRegistro || event.params.userId || "").trim();
  if (!userId) return;

  const estadoBefore = String(before.estado || (before.disponible ? "0-9" : "0-8")).trim().toUpperCase();
  const estadoAfter = String(after.estado || (after.disponible ? "0-9" : "0-8")).trim().toUpperCase();
  if (estadoBefore === estadoAfter) return;
  if (after._changedByCentral !== true) return;

  const safeTopic = userId.replace(/\s+/g, "");
  const titleText = "ESTADO ACTUALIZADO";
  const bodyText = `Tu estado ha sido cambiado a ${estadoAfter} por la Central`;
  const statusData = {
    title: titleText,
    body: bodyText,
    type: "STATUS_CHANGE",
    newStatus: String(estadoAfter),
    idRegistro: String(userId)
  };

  await Promise.all([
    admin.messaging().send({
      topic: `usuario_${safeTopic}`,
      data: statusData,
      android: {
        priority: "high",
        ttl: 300000
      },
      apns: {
        headers: {
          "apns-priority": "10",
          "apns-push-type": "alert"
        },
        payload: {
          aps: {
            alert: {
              title: titleText,
              body: bodyText
            },
            sound: "default",
            category: "GENERAL_ALERT",
            "interruption-level": "time-sensitive",
            "content-available": 1
          }
        }
      }
    }).catch((e) => console.warn("FCM status warning:", e.message)),
    sendDirectApnsToIosUsers({
      title: titleText,
      body: bodyText,
      sound: "default",
      category: "GENERAL_ALERT",
      collapseId: `status_${userId}`,
      targetUserIds: [userId],
      data: statusData
    })
  ]);
});

// 4B. SUSCRIPCIÓN AUTOMÁTICA DE TOKENS FCM A TÓPICOS EN CREACIÓN Y ACTUALIZACIÓN DE PERSONAL
exports.subscribeTokenToTopics = onDocumentCreated("personal/{userId}", async (event) => {
  const snap = event.data;
  if (!snap) return;
  const data = snap.data() || {};
  const userId = String(data.idRegistro || event.params.userId || "").trim();
  const tok = String(data.deviceId || data.tokenFcm || data.fcmToken || "").trim();
  const isConductor = Boolean(data.conductor);
  if (isValidFcmRegistrationToken(tok)) {
    await subscribeFcmTokenForUser(tok, userId, isConductor, false);
  }
});

exports.syncTokenTopicsOnUpdate = onDocumentUpdated("personal/{userId}", async (event) => {
  const before = event.data.before.data() || {};
  const after = event.data.after.data() || {};
  const userId = String(after.idRegistro || event.params.userId || "").trim();
  const tokAfter = String(after.deviceId || after.tokenFcm || after.fcmToken || "").trim();
  const tokBefore = String(before.deviceId || before.tokenFcm || before.fcmToken || "").trim();
  const condAfter = Boolean(after.conductor);
  const condBefore = Boolean(before.conductor);
  if (isValidFcmRegistrationToken(tokAfter) && (tokAfter !== tokBefore || condAfter !== condBefore)) {
    await subscribeFcmTokenForUser(tokAfter, userId, condAfter, false);
  }
});

// 5. SOLICITUD DE APERTURA DE PUERTA (TIMBRE CUARTEL)
exports.onDoorRequestCreated = onDocumentCreated("solicitudes_puerta/{requestId}", async (event) => {
  const snap = event.data;
  if (!snap) return;
  const data = snap.data() || {};
  const requestId = event.params.requestId;

  const estado = String(data.estado || "PENDIENTE").toUpperCase();
  if (estado !== "PENDIENTE") return;

  const solicitanteNombre = data.solicitanteNombre || data.nombreBombero || data.nombre || "Un bombero";
  const solicitanteId = data.solicitanteId || data.idRegistro || "";

  await admin.messaging().send({
    topic: "central_operador",
    data: {
      type: "DOOR_REQUEST",
      payloadId: String(requestId),
      senderId: String(solicitanteId),
      title: "SOLICITUD DE APERTURA DE PUERTA",
      body: `${solicitanteNombre} solicita abrir el portón del cuartel.`
    },
    android: {priority: "high"},
    apns: {
      headers: {
        "apns-priority": "10",
        "apns-push-type": "alert"
      },
      payload: {
        aps: {
          alert: {
            title: "🚪 SOLICITUD DE APERTURA DE PUERTA",
            body: `${solicitanteNombre} solicita abrir el portón del cuartel.`
          },
          sound: "importante.mp3",
          badge: 1,
          category: "DOOR_REQUEST_ALERT",
          "interruption-level": "time-sensitive",
          "relevance-score": 1.0,
          "content-available": 1,
          "mutable-content": 1
        }
      }
    }
  });
});

// Alias compatibles con despliegue existente en proyectos Firebase (enviarDespacho / actualizarDespacho / enviarAlerta / etc.)
exports.enviarDespacho = onDocumentWritten("despachos/{dispatchId}", async (event) => {
  const beforeSnap = event.data.before;
  const afterSnap = event.data.after;
  if (!afterSnap || !afterSnap.exists) return;

  const before = (beforeSnap && beforeSnap.exists) ? (beforeSnap.data() || {}) : null;
  const after = afterSnap.data() || {};
  const dispatchId = event.params.dispatchId;

  const estadoAfterLower = String(after.estado || "").toLowerCase().trim();
  const estadoBeforeLower = before ? String(before.estado || "").toLowerCase().trim() : "";
  const finalizadoAt = Number(after.finalizadoAt || 0);
  if (
    (after.operadorFinal && String(after.operadorFinal).trim() !== "") ||
    finalizadoAt > 0 ||
    estadoAfterLower === "cerrado" ||
    estadoAfterLower === "finalizado" ||
    estadoAfterLower === "cancelada" ||
    estadoAfterLower === "anulado"
  ) {
    return;
  }

  const epochMs = Number(after.createdAt || after.timestamp || Date.now());
  const clDt = getChileDateTimeStrings(epochMs);
  const clave = String(after.clave || after.key || "10-0").trim();
  const claveApoyo = String(after.claveApoyo || "").trim();
  const direccionCanonica = String(after.direccion || after.lugar || "").trim();
  const direccion = direccionCanonica || (estadoAfterLower === "pre-despacho" ? "PRE-DESPACHO EN CURSO" : "Sin ubicación");
  const unidades = extractUnitsString(after);
  const carrosList = unidades ? unidades.split(" / ").map((s) => s.trim()).filter(Boolean) : [];
  const fechaDespacho = String(after.fechaDespacho || after.fecha || clDt.fechaDespacho).trim();
  const horaDespacho = String(after.horaDespacho || after.hora || clDt.horaDespacho).trim();
  const createdAt = String(epochMs);

  // Backfill automático en Firestore para apps V2.1.0 y V2.2.5
  const backfillPatch = {};
  if (!after.fechaDespacho && fechaDespacho) backfillPatch.fechaDespacho = fechaDespacho;
  if (!after.horaDespacho && horaDespacho) backfillPatch.horaDespacho = horaDespacho;
  if (direccionCanonica && after.lugar !== direccionCanonica) {
    backfillPatch.lugar = direccionCanonica;
  } else if (!after.lugar && direccion) {
    backfillPatch.lugar = direccion;
  }
  if (unidades && after.carrosTexto !== unidades) backfillPatch.carrosTexto = unidades;
  if (carrosList.length > 0 && !Array.isArray(after.carros)) backfillPatch.carros = carrosList;

  const isTransitionFromPreToActive =
    estadoBeforeLower === "pre-despacho" &&
    (estadoAfterLower === "activa" || estadoAfterLower === "activo" || estadoAfterLower === "en curso");
  const alreadySent = after.pushSent === true || (before && before.pushSent === true);

  const shouldSendPush =
    (estadoAfterLower === "pre-despacho" || estadoAfterLower === "activa" || estadoAfterLower === "activo" || estadoAfterLower === "en curso") &&
    after.visibleMovil !== false &&
    (!alreadySent || isTransitionFromPreToActive);

  if (!shouldSendPush) {
    if (Object.keys(backfillPatch).length > 0) {
      await afterSnap.ref.update(backfillPatch);
    }
    return;
  }

  let titleText = "";
  let bodyText = unidades || "Unidades en despacho";
  if (estadoAfterLower === "pre-despacho") {
    titleText = (clave === "10-12" && claveApoyo) ? `PRE-DESPACHO: ${clave} (${claveApoyo})` : `PRE-DESPACHO: ${clave}`;
    if (direccionCanonica && direccionCanonica !== "Sin ubicación" && direccionCanonica !== "Cuartel General") {
      titleText += ` en ${direccionCanonica}`;
    }
    if (!unidades) bodyText = "PRE-DESPACHO EN CURSO";
  } else {
    titleText = (clave === "10-12" && claveApoyo) ? `${clave} (${claveApoyo}) en ${direccion}` : `${clave} en ${direccion}`;
  }

  const apnsSound = resolveApnsSound(clave);

  // SIEMPRE incluir bloque APNs tanto en creación inicial como en transición de pre-despacho a activa
  const msg = {
    topic: "despachos",
    data: {
      type: "DISPATCH",
      payloadId: String(dispatchId),
      dispatchId: String(dispatchId),
      idServicio: String(dispatchId),
      clave: String(clave),
      key: String(clave),
      claveApoyo: String(claveApoyo),
      lugar: String(direccion),
      direccion: String(direccion),
      carrosTexto: String(unidades),
      unidades: String(unidades),
      estado: String(after.estado || "activa"),
      title: String(titleText),
      body: String(bodyText),
      fechaDespacho,
      horaDespacho,
      createdAt: String(createdAt)
    },
    android: {
      priority: "high",
      ttl: 300000
    },
    apns: {
      headers: {
        "apns-priority": "10",
        "apns-push-type": "alert"
      },
      payload: {
        aps: {
          alert: {
            title: `🚨 ${titleText}`,
            body: String(bodyText)
          },
          sound: apnsSound,
          badge: 1,
          category: "EMERGENCY_DISPATCH",
          "interruption-level": "time-sensitive",
          "relevance-score": 1.0,
          "content-available": 1,
          "mutable-content": 1
        }
      }
    }
  };

  await Promise.all([
    admin.messaging().send(msg).catch((e) => console.warn("FCM enviarDespacho warning:", e.message)),
    sendDirectApnsToIosUsers({
      title: `🚨 ${titleText}`,
      body: String(bodyText),
      sound: apnsSound,
      category: "EMERGENCY_DISPATCH",
      collapseId: `disp_${dispatchId}`,
      data: msg.data
    })
  ]);
  await afterSnap.ref.update(Object.assign({}, backfillPatch, {
    pushSent: true,
    pushSentAt: Date.now()
  }));
});

exports.actualizarDespacho = exports.onDispatchUpdated;
exports.enviarAlerta = exports.onNewAlert;
exports.actualizarEstadoPersonal = onDocumentUpdated("personal/{userId}", async () => {});
exports.solicitarAperturaPuerta = exports.onDoorRequestCreated;
