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
const APNS_LEGACY_BUNDLE_ID = "com.misisbom.sisbom";
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

function sendSingleApnsHttp2(host, apnsToken, payloadObj, collapseId, priority = "10", topic = APNS_BUNDLE_ID) {
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
        "apns-topic": String(topic || APNS_BUNDLE_ID),
        "apns-push-type": "alert",
        "apns-priority": String(priority),
        "apns-expiration": String(Math.floor(Date.now() / 1000) + 3600)
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
  if (!/^[0-9a-f]{64}$/.test(cleanHex)) {
    return {delivered: false, status: 0, body: "InvalidHex", via: "none"};
  }
  const prodRes = await sendSingleApnsHttp2("https://api.push.apple.com", cleanHex, payloadObj, collapseId, priority, APNS_BUNDLE_ID);
  if (prodRes.status === 200) {
    return {delivered: true, status: 200, body: "", via: "apns-prod"};
  }
  const prodBodyStr = String(prodRes.body || "");
  if (prodRes.status === 400 && prodBodyStr.includes("BadDeviceToken")) {
    const sandRes = await sendSingleApnsHttp2("https://api.sandbox.push.apple.com", cleanHex, payloadObj, collapseId, priority, APNS_BUNDLE_ID);
    if (sandRes.status === 200) {
      return {delivered: true, status: 200, body: "", via: "apns-sandbox"};
    }
  }
  if (prodRes.status === 400 && (prodBodyStr.includes("DeviceTokenNotForTopic") || prodBodyStr.includes("BadDeviceToken"))) {
    const legProdRes = await sendSingleApnsHttp2("https://api.push.apple.com", cleanHex, payloadObj, collapseId, priority, APNS_LEGACY_BUNDLE_ID);
    if (legProdRes.status === 200) {
      return {delivered: true, status: 200, body: "", via: "apns-legacy"};
    }
    if (legProdRes.status === 400 && String(legProdRes.body || "").includes("BadDeviceToken")) {
      const legSandRes = await sendSingleApnsHttp2("https://api.sandbox.push.apple.com", cleanHex, payloadObj, collapseId, priority, APNS_LEGACY_BUNDLE_ID);
      if (legSandRes.status === 200) {
        return {delivered: true, status: 200, body: "", via: "apns-legacy-sandbox"};
      }
    }
  }
  console.warn("[APNs] Rejected token", cleanHex.slice(0, 12), "status=", prodRes.status, "body=", prodRes.body || prodRes.reason || "");
  return {delivered: false, status: prodRes.status, body: String(prodRes.body || prodRes.reason || ""), via: "none"};
}

const APNS_CRITICAL_ALERTS_ENABLED = String(process.env.APNS_CRITICAL_ALERTS_ENABLED || "").trim().toLowerCase() === "true";

function buildApnsApsObject({
  title,
  body,
  sound = "despacho.mp3",
  category = "EMERGENCY_DISPATCH",
  interruptionLevel = "active",
  relevanceScore = 1.0,
  includeSound = true
}) {
  const safeLevel = APNS_CRITICAL_ALERTS_ENABLED && includeSound
    ? "critical"
    : (interruptionLevel === "passive" ? "passive" : "active");
  const aps = {
    alert: {
      title: String(title || "SENTINEL ONE"),
      body: String(body || "")
    },
    badge: 1,
    category: String(category || "EMERGENCY_DISPATCH"),
    "interruption-level": safeLevel,
    "relevance-score": Number(relevanceScore || 1.0),
    "content-available": 1
  };
  if (includeSound && sound) {
    aps.sound = APNS_CRITICAL_ALERTS_ENABLED
      ? {critical: 1, name: String(sound), volume: 1.0}
      : String(sound);
  }
  return aps;
}

async function sendIosFcmFallbackToDevice(fcmToken, {
  title,
  body,
  sound = "despacho.mp3",
  category = "EMERGENCY_DISPATCH",
  interruptionLevel = "time-sensitive",
  relevanceScore = 1.0,
  priority = "10",
  collapseId = "",
  data = {},
  includeSound = true
}) {
  const tok = String(fcmToken || "").trim();
  if (!isValidFcmRegistrationToken(tok)) return false;

  const stringData = {};
  for (const [k, v] of Object.entries(data || {})) {
    stringData[k] = String(v ?? "");
  }

  const aps = buildApnsApsObject({
    title,
    body,
    sound,
    category,
    interruptionLevel,
    relevanceScore,
    includeSound
  });

  const apnsHeaders = {
    "apns-priority": String(priority || "10"),
    "apns-push-type": "alert"
  };
  if (collapseId) {
    apnsHeaders["apns-collapse-id"] = String(collapseId).slice(0, 64);
  }

  try {
    await admin.messaging().send({
      token: tok,
      data: stringData,
      apns: {
        headers: apnsHeaders,
        payload: {aps}
      }
    });
    return true;
  } catch (err) {
    console.warn("[iOS FCM Fallback] Failed for token", tok.slice(0, 16), err.message);
    return false;
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
  excludeUserIds = null,
  onlyConductors = false,
  respectStatus09ForSound = true,
  unrespondedOnlyForDispatchId = "",
  respondedUserIds = null
}) {
  try {
    const db = admin.firestore();
    let docs = [];
    if (Array.isArray(targetUserIds)) {
      if (targetUserIds.length === 0) return;
      const snaps = await Promise.all(
        targetUserIds.map((uid) => db.collection("personal").doc(String(uid).trim()).get().catch(() => null))
      );
      docs = snaps.filter((s) => s && s.exists);
    } else {
      const snap = await db.collection("personal").get();
      docs = snap.docs;
    }

    const excludeSet = new Set(
      Array.isArray(excludeUserIds) ? excludeUserIds.map((id) => String(id || "").trim()).filter(Boolean) : []
    );

    const targetsMap = new Map();
    for (const d of docs) {
      const p = d.data() || {};
      if (d.id === "init" || p.placeholder === true) continue;
      const actStr = String(p.activo ?? "SI").trim().toUpperCase();
      if (p.activo === false || actStr === "NO" || actStr === "0" || actStr === "INACTIVO") continue;
      if (p.modoAvion === true || p.silencioAbsoluto === true) continue;
      const docUid = String(p.idRegistro || d.id || "").trim();
      if (docUid && excludeSet.has(docUid)) continue;
      const cleanApns = String(p.apnsToken || "").trim().toLowerCase();
      const hasApns = /^[0-9a-f]{64}$/.test(cleanApns);
      const rawPlat = String(p.platform || "").trim().toLowerCase();
      if (rawPlat === "android" && !hasApns) continue;
      if (onlyConductors && !isConductorFlag(p)) continue;
      const est = String(p.estado || (p.disponible === false ? "0-8" : "0-9")).trim().toUpperCase();
      if (unrespondedOnlyForDispatchId) {
        if (est !== "0-9") continue;
        const enSvc = String(p.enServicio || "").trim();
        if (enSvc === unrespondedOnlyForDispatchId || enSvc === `-${unrespondedOnlyForDispatchId}`) continue;
        const uid = String(p.idRegistro || d.id || "").trim();
        if (respondedUserIds && respondedUserIds.has(uid)) continue;
      }

      const fcmTok = String(p.deviceId || p.fcmToken || p.tokenFcm || p.tokenIos || "").trim();
      const hasFcm = isValidFcmRegistrationToken(fcmTok);
      if (!(rawPlat === "ios" || hasApns)) continue;
      if (!hasApns && !hasFcm) continue;

      const includeSound = !respectStatus09ForSound || est === "0-9";
      const targetKey = hasApns ? `apns:${cleanApns}` : `fcm:${fcmTok}`;
      const prev = targetsMap.get(targetKey);
      if (!prev) {
        targetsMap.set(targetKey, {cleanApns, hasApns, fcmTok, hasFcm, includeSound});
      } else if (includeSound && !prev.includeSound) {
        prev.includeSound = true;
      }
    }

    if (targetsMap.size === 0) return;

    const buildApnsPayload = (includeSound) => Object.assign({}, data, {
      aps: buildApnsApsObject({
        title,
        body,
        sound,
        category,
        interruptionLevel,
        relevanceScore,
        includeSound
      })
    });

    const audiblePayload = buildApnsPayload(true);
    const silentPayload = buildApnsPayload(false);

    let apnsOk = 0;
    let fcmFallbackOk = 0;
    let failed = 0;

    await Promise.all(Array.from(targetsMap.values()).map(async (target) => {
      const payloadObj = target.includeSound ? audiblePayload : silentPayload;
      if (target.hasApns) {
        const res = await sendDirectApnsToDevice(target.cleanApns, payloadObj, collapseId, priority);
        if (res.delivered) {
          apnsOk += 1;
          return;
        }
        if (target.hasFcm) {
          const okFcm = await sendIosFcmFallbackToDevice(target.fcmTok, {
            title,
            body,
            sound,
            category,
            interruptionLevel,
            relevanceScore,
            priority,
            collapseId,
            data,
            includeSound: target.includeSound
          });
          if (okFcm) {
            fcmFallbackOk += 1;
            return;
          }
        }
        failed += 1;
        return;
      }

      if (target.hasFcm) {
        const okFcm = await sendIosFcmFallbackToDevice(target.fcmTok, {
          title,
          body,
          sound,
          category,
          interruptionLevel,
          relevanceScore,
          priority,
          collapseId,
          data,
          includeSound: target.includeSound
        });
        if (okFcm) {
          fcmFallbackOk += 1;
        } else {
          failed += 1;
        }
      }
    }));

    console.info("[iOS Push Summary]", JSON.stringify({
      collapseId,
      targets: targetsMap.size,
      apnsOk,
      fcmFallbackOk,
      failed
    }));
  } catch (err) {
    console.warn("APNs direct send warning:", err.message);
  }
}

function isConductorFlag(p) {
  if (!p || typeof p !== "object") return false;
  if (p.conductor === true || p.esConductor === true) return true;
  if (typeof p.conductor === "number" && p.conductor !== 0) return true;
  const s = String(p.conductor ?? p.esConductor ?? "").trim().toUpperCase();
  return s === "SI" || s === "SÍ" || s === "TRUE" || s === "1";
}

function isCommanderFlag(p) {
  if (!p || typeof p !== "object") return false;
  const rad = String(p.idRadial ?? "").trim();
  const modoAdmin =
    p.modoAdmin === true ||
    p.ModoAdmin === true ||
    String(p.modoAdmin || "").trim().toUpperCase() === "SI" ||
    String(p.modoAdmin || "").trim().toUpperCase() === "TRUE";
  return rad === "1" || parseInt(rad, 10) === 1 || modoAdmin;
}

async function resolveActiveCentralOperatorId(db) {
  try {
    const centralSnap = await db.collection("accesos").doc("central").get();
    if (!centralSnap || !centralSnap.exists) return "";
    const cData = centralSnap.data() || {};
    const estado = String(cData.estado || "").trim().toLowerCase();
    const idReg = String(cData.idRegistro || "").trim();
    return (estado === "activo" && idReg) ? idReg : "";
  } catch (_) {
    return "";
  }
}

async function sendDirectFcmToAndroidUsers({
  data = {},
  priority = "high",
  ttl = 300000,
  targetUserIds = null,
  excludeUserIds = null,
  onlyConductors = false,
  onlyCentralOperator = false,
  tag = "push"
}) {
  try {
    const db = admin.firestore();
    let effectiveTargetIds = targetUserIds;

    if (onlyCentralOperator) {
      const centralId = await resolveActiveCentralOperatorId(db);
      if (!centralId) {
        console.info("[Android Push Summary]", JSON.stringify({
          tag,
          targets: 0,
          successCount: 0,
          failureCount: 0,
          reason: "no-active-central-operator"
        }));
        return;
      }
      effectiveTargetIds = Array.isArray(effectiveTargetIds)
        ? effectiveTargetIds.filter((id) => String(id).trim() === centralId)
        : [centralId];
    }

    let docs = [];
    if (Array.isArray(effectiveTargetIds)) {
      const cleanTargets = Array.from(new Set(
        effectiveTargetIds.map((uid) => String(uid || "").trim()).filter(Boolean)
      ));
      if (cleanTargets.length === 0) return;
      const snaps = await Promise.all(
        cleanTargets.map((uid) => db.collection("personal").doc(uid).get().catch(() => null))
      );
      docs = snaps.filter((s) => s && s.exists);
      if (docs.length < cleanTargets.length) {
        const foundIds = new Set(docs.map((s) => {
          const pd = s.data() || {};
          return String(pd.idRegistro || s.id || "").trim();
        }));
        const missing = cleanTargets.filter((uid) => !foundIds.has(uid));
        if (missing.length > 0) {
          const fullSnap = await db.collection("personal").get();
          const missingSet = new Set(missing);
          fullSnap.docs.forEach((d) => {
            const pd = d.data() || {};
            const pUid = String(pd.idRegistro || d.id || "").trim();
            if (missingSet.has(pUid)) {
              docs.push(d);
            }
          });
        }
      }
    } else {
      const snap = await db.collection("personal").get();
      docs = snap.docs;
    }

    const excludeSet = new Set(
      Array.isArray(excludeUserIds)
        ? excludeUserIds.map((id) => String(id || "").trim()).filter(Boolean)
        : []
    );

    const tokenSet = new Set();
    for (const d of docs) {
      const p = d.data() || {};
      if (d.id === "init" || p.placeholder === true) continue;
      const actStr = String(p.activo ?? "SI").trim().toUpperCase();
      if (p.activo === false || actStr === "NO" || actStr === "0" || actStr === "INACTIVO") continue;
      if (p.modoAvion === true || p.silencioAbsoluto === true) continue;

      const uid = String(p.idRegistro || d.id || "").trim();
      if (uid && excludeSet.has(uid)) continue;
      if (onlyConductors && !isConductorFlag(p)) continue;
      if (resolveEffectivePlatform(p) === "ios") continue;

      const fcmTok = String(p.deviceId || p.fcmToken || p.tokenFcm || "").trim();
      if (isValidFcmRegistrationToken(fcmTok)) {
        tokenSet.add(fcmTok);
      }
    }

    const tokens = Array.from(tokenSet);
    if (tokens.length === 0) {
      console.info("[Android Push Summary]", JSON.stringify({
        tag,
        targets: 0,
        successCount: 0,
        failureCount: 0
      }));
      return;
    }

    const stringData = {};
    for (const [k, v] of Object.entries(data || {})) {
      stringData[k] = String(v ?? "");
    }

    const androidConfig = Number(ttl) > 0
      ? {priority: String(priority || "high"), ttl: Number(ttl)}
      : {priority: String(priority || "high")};

    let successCount = 0;
    let failureCount = 0;
    const sampleErrors = [];

    for (let i = 0; i < tokens.length; i += 500) {
      const chunk = tokens.slice(i, i + 500);
      const batchRes = await admin.messaging().sendEachForMulticast({
        tokens: chunk,
        data: stringData,
        android: androidConfig
      });
      successCount += Number(batchRes.successCount || 0);
      failureCount += Number(batchRes.failureCount || 0);
      if (Array.isArray(batchRes.responses)) {
        for (const resp of batchRes.responses) {
          if (!resp.success && resp.error && sampleErrors.length < 3) {
            sampleErrors.push({
              code: resp.error.code || "",
              message: resp.error.message || ""
            });
          }
        }
      }
    }

    console.info("[Android Push Summary]", JSON.stringify({
      tag,
      targets: tokens.length,
      successCount,
      failureCount,
      ...(sampleErrors.length > 0 ? {sampleErrors} : {})
    }));
  } catch (err) {
    console.warn("FCM direct send warning:", err.message);
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

function resolveEffectivePlatform(docData) {
  const hasApns = /^[0-9a-f]{64}$/.test(String((docData && docData.apnsToken) || "").trim().toLowerCase());
  if (hasApns) return "ios";
  const raw = String((docData && docData.platform) || "").trim().toLowerCase();
  if (raw === "android" || raw === "ios") return raw;
  return "";
}

async function subscribeFcmTokenForUser(token, userId, isConductor, isCentral, platform = "", hasValidApns = false) {
  if (!isValidFcmRegistrationToken(token)) return;
  const cleanId = String(userId || "").trim().replace(/\s+/g, "");
  const topics = ["all", "despachos", "alertas", "alertas_generales"];
  if (cleanId) {
    topics.push(`usuario_${cleanId}`);
    topics.push(`personal_${cleanId}`);
  }
  if (isConductor) topics.push("conductores");
  if (isCentral) topics.push("central_operador");

  const isIosDevice = String(platform || "").trim().toLowerCase() === "ios";
  if (isIosDevice && hasValidApns) {
    // Los dispositivos iOS con apnsToken directo reciben push individualizado vía sendDirectApnsToIosUsers (con filtro 0-9/0-8).
    // Desuscribirlos de los tópicos FCM grupales evita recibir doble push y doble audio (eco).
    await Promise.all(
      topics.map((t) =>
        admin.messaging().unsubscribeFromTopic([token], t).catch(() => null)
      )
    );
    return;
  }

  const subResults = await Promise.all(
    topics.map((t) =>
      admin.messaging().subscribeToTopic([token], t)
        .then((res) => ({topic: t, res}))
        .catch((err) => ({topic: t, err: err.message}))
    )
  );
  for (const item of subResults) {
    if (item.err) {
      console.warn("[FCM Topic Subscribe Error]", item.topic, item.err);
    } else if (item.res && item.res.failureCount > 0) {
      const firstErr = item.res.errors && item.res.errors[0] ? item.res.errors[0].error : {};
      console.warn("[FCM Topic Subscribe Warning]", item.topic, JSON.stringify(firstErr));
    }
  }
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

// 2. ACTUALIZACIÓN DE DESPACHO (Escalamiento 10-0 -> 10-30, 10-2 -> FORESTAL y Solicitudes 12-10 / 6-6)
const onDispatchUpdated = onDocumentUpdated("despachos/{dispatchId}", async (event) => {
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
        sendDirectFcmToAndroidUsers({
          data: escData,
          priority: "high",
          ttl: 300000,
          tag: `esc_${dispatchId}`
        }),
        sendDirectApnsToIosUsers({
          title: `🔥 CAMBIO DE CLAVE A ${newClave}`,
          body: `${direccion} (${unidadesStr}) - ¡SE REQUIERE PERSONAL!`,
          sound: resolveApnsSound(newClave),
          category: "EMERGENCY_DISPATCH",
          collapseId: `esc_${dispatchId}_${newClave}`,
          respectStatus09ForSound: false,
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
        sendDirectFcmToAndroidUsers({
          data: req1210Data,
          priority: "high",
          ttl: 300000,
          onlyConductors: true,
          tag: `req1210_${dispatchId}_${unitName}`
        }),
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
        sendDirectFcmToAndroidUsers({
          data: req66Data,
          priority: "high",
          ttl: 300000,
          tag: `req66_${dispatchId}_${unitName}`
        }),
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

  // D. Detectar orden de EVACUACIÓN (`evacuar.evacuacion === true`) — SOLO para bomberos en este incidente
  const evacBefore = (before.evacuar && typeof before.evacuar === "object")
    ? before.evacuar
    : ((before.evacuationAlert && typeof before.evacuationAlert === "object")
      ? {evacuacion: Boolean(before.evacuationAlert.active), triggeredAt: before.evacuationAlert.triggeredAt}
      : {});
  const evacAfter = (after.evacuar && typeof after.evacuar === "object")
    ? after.evacuar
    : ((after.evacuationAlert && typeof after.evacuationAlert === "object")
      ? {
          evacuacion: Boolean(after.evacuationAlert.active),
          lat: after.evacuationAlert.lat ?? null,
          lng: after.evacuationAlert.lng ?? null,
          instrucciones: after.evacuationAlert.motivo || "",
          triggeredAt: after.evacuationAlert.triggeredAt || null
        }
      : {});

  const isNewEvacOrder =
    evacAfter.evacuacion === true &&
    (evacBefore.evacuacion !== true || Number(evacAfter.triggeredAt || 0) !== Number(evacBefore.triggeredAt || 0));

  if (isNewEvacOrder) {
    try {
      const db = admin.firestore();
      const targetUserIdsSet = new Set();
      const targetRadialsSet = new Set();

      // 1. Asistentes directos en el despacho
      if (Array.isArray(after.asistentes)) {
        for (const item of after.asistentes) {
          if (!item) continue;
          if (typeof item === "string" || typeof item === "number") {
            targetUserIdsSet.add(String(item).trim());
          } else if (typeof item === "object") {
            const uid = String(item.idRegistro || item.id || item.bomberoId || "").trim();
            const rad = String(item.idRadial || "").trim().toUpperCase();
            if (uid) targetUserIdsSet.add(uid);
            if (rad) targetRadialsSet.add(rad);
          }
        }
      }

      // 2. Tripulación en bitácoras activas (sin h68) de este despacho
      const bitSnap = await db.collection("bitacora").where("idServicio", "==", String(dispatchId)).get().catch(() => null);
      if (bitSnap && !bitSnap.empty) {
        bitSnap.forEach((bDoc) => {
          const b = bDoc.data() || {};
          if (b?.tiempos?.h68 || b?.h68 || b?.hora68) return;
          const extractRad = (v) => {
            if (!v) return "";
            if (typeof v === "object") return String(v.idRadial || v.id || "").trim().toUpperCase();
            return String(v).split(/[•-]/)[0].trim().toUpperCase();
          };
          const cRad = extractRad(b.conductor);
          const oRad = extractRad(b.obac);
          if (cRad) targetRadialsSet.add(cRad);
          if (oRad) targetRadialsSet.add(oRad);
          if (Array.isArray(b.tripulacion)) {
            for (const m of b.tripulacion) {
              const mRad = extractRad(m);
              const mReg = typeof m === "object" ? String(m?.idRegistro || "").trim() : "";
              if (mRad) targetRadialsSet.add(mRad);
              if (mReg) targetUserIdsSet.add(mReg);
            }
          }
        });
      }

      // 3. Personal con `enServicio == dispatchId` o radial en tripulación activa
      const persSnap = await db.collection("personal").get();
      persSnap.forEach((pDoc) => {
        const p = pDoc.data() || {};
        const uid = String(p.idRegistro || pDoc.id || "").trim();
        const rad = String(p.idRadial || "").trim().toUpperCase();
        const enSvc = String(p.enServicio || "").trim();
        if (!uid) return;
        if (enSvc === `-${dispatchId}`) return;
        if (enSvc === String(dispatchId) || (rad && targetRadialsSet.has(rad))) {
          targetUserIdsSet.add(uid);
        }
      });

      const incidentUserIds = Array.from(targetUserIdsSet).filter(Boolean);
      if (incidentUserIds.length > 0) {
        const instr = String(
          evacAfter.instrucciones || "ABANDONAR ZONA DE RIESGO DE INMEDIATO Y DIRIGIRSE AL PUNTO DE EVACUACIÓN"
        ).trim();
        const evacPayloadData = {
          type: "EVACUATION_ALERT",
          payloadId: String(dispatchId),
          dispatchId: String(dispatchId),
          idServicio: String(dispatchId),
          evacuacion: "true",
          lat: evacAfter.lat != null ? String(evacAfter.lat) : "",
          lng: evacAfter.lng != null ? String(evacAfter.lng) : "",
          instrucciones: instr,
          triggeredAt: String(evacAfter.triggeredAt || Date.now()),
          title: `🚨 EVACUACIÓN INMEDIATA (${clave})`,
          body: instr
        };

        await Promise.all([
          sendDirectFcmToAndroidUsers({
            data: evacPayloadData,
            priority: "high",
            ttl: 300000,
            targetUserIds: incidentUserIds,
            tag: `evac_${dispatchId}`
          }),
          sendDirectApnsToIosUsers({
            title: `🚨 EVACUACIÓN INMEDIATA (${clave})`,
            body: instr,
            sound: "importante.mp3",
            category: "EMERGENCY_DISPATCH",
            interruptionLevel: "time-sensitive",
            relevanceScore: 1.0,
            priority: "10",
            collapseId: `evac_${dispatchId}_${evacAfter.triggeredAt || Date.now()}`,
            targetUserIds: incidentUserIds,
            respectStatus09ForSound: false,
            data: evacPayloadData
          })
        ]);
      }
    } catch (evacErr) {
      console.warn("Error enviando push selectivo de evacuacion:", evacErr.message);
    }
  }
});

// 3. NUEVA ALERTA U ORDEN DEL DÍA (onDocumentWritten para soportar IDs reutilizados como alertas/1)
const onNewAlert = onDocumentWritten("alertas/{alertId}", async (event) => {
  const afterSnap = event.data && event.data.after;
  const beforeSnap = event.data && event.data.before;
  if (!afterSnap || !afterSnap.exists) return;

  const after = afterSnap.data() || {};
  const alertId = String(event.params.alertId || "").trim();
  if (!alertId || alertId === "init" || after.placeholder === true) return;
  if (alertId.startsWith("SCI_CHAT_")) return;

  const isOrden = String(after.tipo || "").toLowerCase() === "orden" || Boolean(after.numeroOrden);
  const duracionRaw = String(after.duracion ?? "").replace(/^['"]+|['"]+$/g, "").trim();
  const estadoRaw = String(after.estado || "").trim().toUpperCase();
  if (
    duracionRaw.toUpperCase() === "C" ||
    (after.duracion !== undefined && duracionRaw === "" && !isOrden) ||
    estadoRaw === "CANCELADA" ||
    estadoRaw === "ELIMINADA" ||
    after.eliminada === true ||
    String(after.oculta || "") === "1"
  ) {
    return;
  }

  if (beforeSnap && beforeSnap.exists) {
    const before = beforeSnap.data() || {};
    const wasPlaceholder = before.placeholder === true;
    const timeChanged =
      String(after.fechaAlerta || "") !== String(before.fechaAlerta || "") ||
      String(after.horaAlerta || "") !== String(before.horaAlerta || "") ||
      Number(after.timestamp || after.createdAt || 0) !== Number(before.timestamp || before.createdAt || 0);
    const hasNoConforme = String(after.conforme || "").replace(/^['"]+|['"]+$/g, "").trim() === "";
    const hasNoLeido = !Array.isArray(after.leidoPor) || after.leidoPor.length === 0;
    const hasActiveDuracion = duracionRaw !== "";
    const isFreshOverwrite = wasPlaceholder || (timeChanged && hasNoConforme && hasNoLeido && hasActiveDuracion);
    if (!isFreshOverwrite) return;
  }

  const titulo = isOrden
    ? `ORDEN DEL DÍA N° ${after.numeroOrden || ""}`.trim()
    : (after.titulo || after.razonAlerta || "Nueva Alerta");
  const mensaje = String(after.cuerpo || after.mensaje || after.mensajeAlerta || "Revisa el muro de alertas")
    .replace(/\|/g, " ")
    .slice(0, 140);
  const grado = isOrden ? "3" : String(after.grado || after.gradoAlerta || "1").replace(/^['"]+|['"]+$/g, "").trim();
  const senderId = String(after.creadorId || after.autorId || after.idRegistroEmisor || "").trim();

  const rawDest = after.destinatarios !== undefined ? after.destinatarios : after.aQuienAlerta;
  let parsedDestList = null;
  if (Array.isArray(rawDest)) {
    parsedDestList = rawDest.map((id) => String(id || "").replace(/^['"]+|['"]+$/g, "").trim()).filter(Boolean);
  } else if (typeof rawDest === "string") {
    const cleanStr = rawDest.replace(/^['"]+|['"]+$/g, "").trim();
    if (cleanStr && cleanStr.toUpperCase() !== "TODOS" && cleanStr.toUpperCase() !== "TC") {
      parsedDestList = cleanStr.split(",").map((id) => id.replace(/^['"]+|['"]+$/g, "").trim()).filter(Boolean);
    }
  }

  let targetIds = null;
  if (Array.isArray(parsedDestList) && parsedDestList.length > 0 && !parsedDestList.some((x) => x.toUpperCase() === "TODOS" || x.toUpperCase() === "TC")) {
    const db = admin.firestore();
    const resolvedSet = new Set();
    for (const tid of parsedDestList) {
      if (tid === "1") {
        const p1Snap = await db.collection("personal").doc("1").get().catch(() => null);
        if (p1Snap && p1Snap.exists) {
          const p1Data = p1Snap.data() || {};
          const p1Act = String(p1Data.activo ?? "SI").trim().toUpperCase();
          if (p1Data.activo !== false && p1Act !== "NO" && p1Act !== "0" && p1Act !== "INACTIVO") {
            resolvedSet.add(String(p1Data.idRegistro || "1").trim());
          }
        }
        const fullPersSnap = await db.collection("personal").get();
        fullPersSnap.forEach((docSnap) => {
          const p = docSnap.data() || {};
          if (docSnap.id === "init" || p.placeholder === true) return;
          const actStr = String(p.activo ?? "SI").trim().toUpperCase();
          if (p.activo === false || actStr === "NO" || actStr === "0" || actStr === "INACTIVO") return;
          if (isCommanderFlag(p)) {
            const uid = String(p.idRegistro || docSnap.id || "").trim();
            if (uid) resolvedSet.add(uid);
          }
        });
      } else {
        resolvedSet.add(tid);
      }
    }
    if (senderId) resolvedSet.delete(senderId);
    targetIds = Array.from(resolvedSet);
    if (targetIds.length === 0) return;
  }

  const isG3 = String(grado) === "3";
  const isG2 = String(grado) === "2";
  const alertTitle = isOrden ? `📋 ${titulo}` : `ALERTA GRADO ${grado}: ${titulo}`;
  const alertData = {
    type: isOrden ? "ORDEN" : "ALERT",
    payloadId: String(alertId),
    gradoAlerta: String(grado),
    senderId: String(senderId),
    title: alertTitle,
    body: mensaje
  };

  await Promise.all([
    sendDirectFcmToAndroidUsers({
      data: alertData,
      priority: (isG3 || isG2) ? "high" : "normal",
      ttl: 300000,
      targetUserIds: targetIds,
      excludeUserIds: senderId ? [senderId] : [],
      tag: `alert_${alertId}`
    }),
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
      excludeUserIds: senderId ? [senderId] : [],
      data: alertData
    })
  ]);
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
    sendDirectFcmToAndroidUsers({
      data: statusData,
      priority: "high",
      ttl: 300000,
      targetUserIds: [userId],
      tag: `status_${userId}`
    }),
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
  const tok = String(data.deviceId || "").trim();
  const isConductor = Boolean(data.conductor);
  const platform = resolveEffectivePlatform(data);
  const hasValidApns = /^[0-9a-f]{64}$/.test(String(data.apnsToken || "").trim().toLowerCase());
  if (isValidFcmRegistrationToken(tok)) {
    await subscribeFcmTokenForUser(tok, userId, isConductor, false, platform, hasValidApns);
  }
});

exports.syncTokenTopicsOnUpdate = onDocumentUpdated("personal/{userId}", async (event) => {
  const before = event.data.before.data() || {};
  const after = event.data.after.data() || {};
  const userId = String(after.idRegistro || event.params.userId || "").trim();
  const tokAfter = String(after.deviceId || "").trim();
  const tokBefore = String(before.deviceId || "").trim();
  const condAfter = Boolean(after.conductor);
  const condBefore = Boolean(before.conductor);
  const platAfter = resolveEffectivePlatform(after);
  const platBefore = resolveEffectivePlatform(before);
  const apnsAfterValid = /^[0-9a-f]{64}$/.test(String(after.apnsToken || "").trim().toLowerCase());
  const apnsBeforeValid = /^[0-9a-f]{64}$/.test(String(before.apnsToken || "").trim().toLowerCase());
  if (
    isValidFcmRegistrationToken(tokAfter) &&
    (tokAfter !== tokBefore || condAfter !== condBefore || platAfter !== platBefore || apnsAfterValid !== apnsBeforeValid)
  ) {
    await subscribeFcmTokenForUser(tokAfter, userId, condAfter, false, platAfter, apnsAfterValid);
  }
});

// 5. SOLICITUD DE APERTURA DE PUERTA (TIMBRE CUARTEL)
const onDoorRequestCreated = onDocumentCreated("solicitudes_puerta/{requestId}", async (event) => {
  const snap = event.data;
  if (!snap) return;
  const data = snap.data() || {};
  const requestId = event.params.requestId;

  const estado = String(data.estado || "PENDIENTE").toUpperCase();
  if (estado !== "PENDIENTE") return;

  const solicitanteNombre = data.solicitanteNombre || data.nombreBombero || data.nombre || "Un bombero";
  const solicitanteId = String(data.solicitanteId || data.idRegistro || "").trim();
  const doorBody = `${solicitanteNombre} solicita abrir el portón del cuartel.`;
  const doorData = {
    type: "DOOR_REQUEST",
    payloadId: String(requestId),
    senderId: solicitanteId,
    title: "SOLICITUD DE APERTURA DE PUERTA",
    body: doorBody
  };

  const db = admin.firestore();
  const centralId = await resolveActiveCentralOperatorId(db);
  const excludeIds = solicitanteId ? [solicitanteId] : [];
  const tasks = [
    sendDirectFcmToAndroidUsers({
      data: doorData,
      priority: "high",
      ttl: 300000,
      onlyCentralOperator: true,
      excludeUserIds: excludeIds,
      tag: `door_${requestId}`
    })
  ];

  if (centralId && centralId !== solicitanteId) {
    tasks.push(
      sendDirectApnsToIosUsers({
        title: "🚪 SOLICITUD DE APERTURA DE PUERTA",
        body: doorBody,
        sound: "importante.mp3",
        category: "DOOR_REQUEST_ALERT",
        interruptionLevel: "time-sensitive",
        relevanceScore: 1.0,
        priority: "10",
        collapseId: `door_${requestId}`,
        targetUserIds: [centralId],
        respectStatus09ForSound: false,
        data: doorData
      })
    );
  }

  await Promise.all(tasks);
});

// Alias compatibles con despliegue existente en proyectos Firebase (enviarDespacho / actualizarDespacho / enviarAlerta / etc.)
exports.enviarDespacho = onDocumentWritten({document: "despachos/{dispatchId}", timeoutSeconds: 120}, async (event) => {
  const beforeSnap = event.data.before;
  const afterSnap = event.data.after;
  if (!afterSnap || !afterSnap.exists) return;

  const before = (beforeSnap && beforeSnap.exists) ? (beforeSnap.data() || {}) : null;
  const after = afterSnap.data() || {};
  const dispatchId = event.params.dispatchId;

  const estadoAfterLower = String(after.estado || "").toLowerCase().trim();
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
  const fechaDespacho = String(after.fechaDespacho || after.fecha || clDt.fechaDespacho).trim();
  const horaDespacho = String(after.horaDespacho || after.hora || clDt.horaDespacho).trim();
  const createdAt = String(epochMs);

  // El primer tono suena UNA SOLA VEZ al crearse el despacho (ya sea en pre-despacho o activa).
  // El paso de pre-despacho a activa NO vuelve a disparar tono; el segundo tono es estrictamente a los 60 segundos si no ha respondido.
  const alreadySent = after.pushSent === true || (before && before.pushSent === true);

  const shouldSendPush =
    (estadoAfterLower === "pre-despacho" || estadoAfterLower === "activa" || estadoAfterLower === "activo" || estadoAfterLower === "en curso") &&
    after.visibleMovil !== false &&
    !alreadySent;

  if (!shouldSendPush) {
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

  const dispatchData = {
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
  };

  await Promise.all([
    sendDirectFcmToAndroidUsers({
      data: dispatchData,
      priority: "high",
      ttl: 300000,
      tag: `disp_${dispatchId}`
    }),
    sendDirectApnsToIosUsers({
      title: `🚨 ${titleText}`,
      body: String(bodyText),
      sound: apnsSound,
      category: "EMERGENCY_DISPATCH",
      collapseId: `disp_${dispatchId}`,
      data: dispatchData
    }),
    afterSnap.ref.update({
      pushSent: true,
      _metaTimestamp: Date.now()
    })
  ]);

  // Segundo tono exactamente a los 60 segundos después del primer tono,
  // únicamente para los bomberos en 0-9 que NO hayan marcado Asistir ni No Asistir.
  await new Promise((resolve) => setTimeout(resolve, 60000));

  try {
    const freshSnap = await afterSnap.ref.get();
    if (!freshSnap || !freshSnap.exists) return;
    const fresh = freshSnap.data() || {};
    const freshEstado = String(fresh.estado || "").toLowerCase().trim();
    const freshFinalizadoAt = Number(fresh.finalizadoAt || 0);
    if (
      (fresh.operadorFinal && String(fresh.operadorFinal).trim() !== "") ||
      freshFinalizadoAt > 0 ||
      ["cerrado", "cerrada", "finalizado", "finalizada", "cancelado", "cancelada", "anulado", "terminado", "terminada"].includes(freshEstado)
    ) {
      return;
    }

    const respondedSet = new Set();
    const collectIds = (arr) => {
      if (!Array.isArray(arr)) return;
      for (const item of arr) {
        if (!item) continue;
        if (typeof item === "string") respondedSet.add(item.trim());
        else if (typeof item === "object") {
          const idVal = String(item.id || item.idRegistro || item.bomberoId || "").trim();
          if (idVal) respondedSet.add(idVal);
        }
      }
    };
    collectIds(fresh.asistentes);
    collectIds(fresh.noAsistentes);

    const freshClave = String(fresh.clave || clave || "10-0").trim();
    const freshDir = String(fresh.direccion || fresh.lugar || direccion || "Ubicación en curso").trim();
    const freshUnidades = extractUnitsString(fresh) || unidades || "Unidades en despacho";
    const freshTitle = `${freshClave} en ${freshDir}`;
    const freshSound = resolveApnsSound(freshClave);

    await sendDirectApnsToIosUsers({
      title: `🚨 ${freshTitle}`,
      body: String(freshUnidades),
      sound: freshSound,
      category: "EMERGENCY_DISPATCH",
      collapseId: `disp_rep60_${dispatchId}`,
      unrespondedOnlyForDispatchId: String(dispatchId),
      respondedUserIds: respondedSet,
      data: Object.assign({}, dispatchData, {
        clave: freshClave,
        direccion: freshDir,
        lugar: freshDir,
        unidades: freshUnidades,
        carrosTexto: freshUnidades,
        estado: String(fresh.estado || "activa"),
        isRepeat60s: "true"
      })
    });
  } catch (repErr) {
    console.warn("Repeat 60s APNs warning:", repErr.message);
  }
});

exports.actualizarDespacho = onDispatchUpdated;
exports.enviarAlerta = onNewAlert;
exports.actualizarEstadoPersonal = onDocumentUpdated("personal/{userId}", async () => {});
exports.solicitarAperturaPuerta = onDoorRequestCreated;

// 6. RELAY PUSH DE ALERTAS Y CHAT INTER-CUERPOS SCI DESDE sisbom-central (GARANTIZA SENDER_ID NATIVO EN ANDROID)
exports.enviarPushSciRelay = onDocumentCreated(
  "sci_incidentes/{incidentId}/notificaciones_push/{pushId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return null;
    const data = snap.data() || {};
    const title = String(data.title || "🚨 ALERTA SCI").trim();
    const body = String(data.body || "Nueva notificación en Sistema de Comando de Incidentes").trim();
    const type = String(data.type || "SCI_ALERT").trim();

    let extra = {};
    if (typeof data.payloadJson === "string" && data.payloadJson.trim()) {
      try {
        extra = JSON.parse(data.payloadJson) || {};
      } catch (_) {}
    }

    const db = admin.firestore();
    const targetIds = new Set();
    if (Array.isArray(data.commanderIds)) {
      data.commanderIds.forEach((id) => {
        const clean = String(id || "").trim();
        if (clean) targetIds.add(clean);
      });
    }

    if (targetIds.size === 0 || (targetIds.size === 1 && targetIds.has("1"))) {
      targetIds.clear();
      const persSnap = await db.collection("personal").get();
      persSnap.forEach((docSnap) => {
        const p = docSnap.data() || {};
        if (docSnap.id === "init" || p.placeholder === true) return;
        const actStr = String(p.activo ?? "SI").trim().toUpperCase();
        if (p.activo === false || actStr === "NO" || actStr === "0" || actStr === "INACTIVO") return;
        if (isCommanderFlag(p) && p.modoAvion !== true && p.silencioAbsoluto !== true) {
          const uid = String(p.idRegistro || docSnap.id || "").trim();
          if (uid) {
            targetIds.add(uid);
          }
        }
      });
    }

    const stringData = {};
    for (const [k, v] of Object.entries(Object.assign({}, extra, {
      type,
      title,
      body,
      gradoAlerta: "3"
    }))) {
      stringData[k] = String(v ?? "");
    }

    const targetList = Array.from(targetIds).filter(Boolean);
    if (targetList.length === 0) return null;

    await Promise.all([
      sendDirectFcmToAndroidUsers({
        data: stringData,
        priority: "high",
        ttl: 300000,
        targetUserIds: targetList,
        tag: `sci_${event.params.pushId}`
      }),
      sendDirectApnsToIosUsers({
        title,
        body,
        sound: String(data.sound || "importante.mp3").trim(),
        category: type === "EVACUATION_ALERT" ? "EMERGENCY_DISPATCH" : "GENERAL_ALERT",
        interruptionLevel: "time-sensitive",
        relevanceScore: 1.0,
        priority: "10",
        collapseId: String(event.params.pushId || ""),
        targetUserIds: targetList,
        respectStatus09ForSound: false,
        data: stringData
      })
    ]);
    return null;
  }
);

// 7. SINCRONIZACION AUTOMATICA FIRESTORE -> FIREBASE AUTH PARA PERSONAL
// Mantiene creadas/actualizadas las cuentas ${idRegistro}@sisbom.com (${contrasena}_secure_sisbom)
// sin modificar Firestore (cero riesgo de bucles) cuando se crea o edita personal en CORE/ONE.
exports.sincronizarAuthPersonal = onDocumentWritten("personal/{userId}", async (event) => {
  const userId = String(event.params?.userId || "").trim();
  const afterSnap = event.data && event.data.after;
  const beforeSnap = event.data && event.data.before;

  if (!afterSnap || !afterSnap.exists) {
    if (userId && userId !== "init") {
      await admin.auth().deleteUser(userId).catch(() => {});
    }
    return null;
  }

  const after = afterSnap.data() || {};
  if (userId === "init" || after.placeholder === true) {
    return null;
  }

  const uid = String(after.idRegistro || userId).trim();
  if (!uid) return null;

  const rawPass = String(after.contrasena ?? "").trim();
  const actStr = String(after.activo ?? "SI").trim().toUpperCase();
  const disabled = after.activo === false || actStr === "NO" || actStr === "0" || actStr === "INACTIVO";
  const displayName = String(after.nombreBombero || after.nombre || uid).trim();

  if (beforeSnap && beforeSnap.exists) {
    const before = beforeSnap.data() || {};
    const prevUid = String(before.idRegistro || userId).trim();
    const prevRawPass = String(before.contrasena ?? "").trim();
    const prevActStr = String(before.activo ?? "SI").trim().toUpperCase();
    const prevDisabled = before.activo === false || prevActStr === "NO" || prevActStr === "0" || prevActStr === "INACTIVO";
    const prevDisplayName = String(before.nombreBombero || before.nombre || prevUid).trim();

    if (
      prevUid === uid &&
      prevRawPass === rawPass &&
      prevDisabled === disabled &&
      prevDisplayName === displayName
    ) {
      return null;
    }
  }

  const email = `${uid}@sisbom.com`.toLowerCase();
  const securePass = rawPass
    ? (rawPass.endsWith("_secure_sisbom") ? rawPass : `${rawPass}_secure_sisbom`)
    : "";

  try {
    if (!securePass || securePass.length < 6) {
      try {
        await admin.auth().getUser(uid);
        await admin.auth().updateUser(uid, { displayName, disabled });
      } catch (err) {
        if (err && err.code !== "auth/user-not-found") {
          console.warn(`sincronizarAuthPersonal update metadata ${uid} warning:`, err.message);
        }
      }
      return null;
    }

    try {
      await admin.auth().getUser(uid);
      await admin.auth().updateUser(uid, {
        email,
        emailVerified: true,
        password: securePass,
        displayName,
        disabled
      });
    } catch (getErr) {
      if (!getErr || getErr.code !== "auth/user-not-found") {
        throw getErr;
      }

      try {
        const existingByEmail = await admin.auth().getUserByEmail(email);
        if (existingByEmail && existingByEmail.uid !== uid) {
          await admin.auth().deleteUser(existingByEmail.uid);
        }
      } catch (_) {}

      try {
        await admin.auth().createUser({
          uid,
          email,
          emailVerified: true,
          password: securePass,
          displayName,
          disabled
        });
      } catch (createErr) {
        if (createErr && (createErr.code === "auth/uid-already-exists" || createErr.code === "auth/email-already-exists")) {
          await admin.auth().updateUser(uid, {
            email,
            emailVerified: true,
            password: securePass,
            displayName,
            disabled
          }).catch(() => {});
        } else {
          throw createErr;
        }
      }
    }
  } catch (err) {
    console.warn(`sincronizarAuthPersonal(${uid}) error:`, err.message);
  }

  return null;
});


