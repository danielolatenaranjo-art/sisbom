const {onDocumentCreated, onDocumentUpdated, onDocumentWritten} = require("firebase-functions/v2/firestore");
const {setGlobalOptions} = require("firebase-functions/v2");
const admin = require("firebase-admin");

admin.initializeApp();
setGlobalOptions({maxInstances: 10});

function resolveApnsSound(clave) {
  if (!clave) return "alertas.mp3";
  const c = String(clave).toUpperCase().trim();
  if (c.includes("10-30") || c.includes("FORESTAL")) return "c10_30.mp3";
  if (c.includes("9-0")) return "c9_0.mp3";
  if (c.includes("LLAMADO") && c.includes("COMANDANCIA")) return "llamado_comandancia.mp3";
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
  return "alertas.mp3";
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

// 1. NUEVO DESPACHO (Dual-Read V2 + V3 Canónico + Retrocompatibilidad Total con V2.1.0, V2.2.5 y V3.0.0)
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
          sound: {
            critical: 1,
            name: resolveApnsSound(clave),
            volume: 1.0
          },
          category: "EMERGENCY_DISPATCH",
          "interruption-level": "critical",
          "content-available": 1,
          "mutable-content": 1
        }
      }
    }
  };

  await admin.messaging().send(message);
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
      await admin.messaging().send({
        topic: "despachos",
        data: {
          type: "DISPATCH_UPDATE",
          payloadId: String(dispatchId),
          clave: newClave,
          title: `CAMBIO DE CLAVE A ${newClave}`,
          body: `${direccion} (${unidadesStr}) - ¡SE REQUIERE PERSONAL!`,
          fechaDespacho,
          horaDespacho,
          createdAt: String(createdAt)
        },
        android: {priority: "high", ttl: 300000},
        apns: {
          headers: {"apns-priority": "10", "apns-push-type": "alert"},
          payload: {
            aps: {
              alert: {
                title: `🔥 CAMBIO DE CLAVE A ${newClave}`,
                body: `${direccion} (${unidadesStr}) - ¡SE REQUIERE PERSONAL!`
              },
              sound: {critical: 1, name: "c10_30.mp3", volume: 1.0},
              category: "EMERGENCY_DISPATCH",
              "interruption-level": "critical",
              "content-available": 1
            }
          }
        }
      });
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
      await admin.messaging().send({
        topic: "conductores",
        data: {
          type: "DISPATCH_UPDATE",
          payloadId: String(dispatchId),
          clave: String(clave),
          title: `SOLICITUD 12-10 (${unitName})`,
          body: `Se solicita conductor para unidad ${unitName} en ${direccion}`,
          fechaDespacho,
          horaDespacho,
          createdAt: new Date().toISOString()
        },
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
              category: "EMERGENCY_DISPATCH",
              "interruption-level": "time-sensitive",
              "content-available": 1
            }
          }
        }
      });
    }

    const pTsBefore = Number(unitB.solicitudPersonalTimestamp || 0);
    const pTsAfter = Number(unitA.solicitudPersonalTimestamp || 0);
    if (pTsAfter > pTsBefore && pTsAfter > 0) {
      await admin.messaging().send({
        topic: "despachos",
        data: {
          type: "DISPATCH_UPDATE",
          payloadId: String(dispatchId),
          clave: String(clave),
          title: `SOLICITUD 6-6 (${unitName})`,
          body: `Se solicita personal para unidad ${unitName} en ${direccion}`,
          fechaDespacho,
          horaDespacho,
          createdAt: new Date().toISOString()
        },
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
              category: "EMERGENCY_DISPATCH",
              "interruption-level": "time-sensitive",
              "content-available": 1
            }
          }
        }
      });
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
            sound: isG3
              ? {critical: 1, name: "importante.mp3", volume: 1.0}
              : isG2
                ? "importante.mp3"
                : "default",
            category: "GENERAL_ALERT",
            "interruption-level": isG3 ? "critical" : isG2 ? "time-sensitive" : "active"
          }
        }
      }
    };
    return admin.messaging().send(msg);
  });

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

  await admin.messaging().send({
    topic: `usuario_${safeTopic}`,
    data: {
      title: titleText,
      body: bodyText,
      type: "STATUS_CHANGE",
      newStatus: String(estadoAfter),
      idRegistro: String(userId)
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
            title: titleText,
            body: bodyText
          },
          sound: "default",
          category: "GENERAL_ALERT",
          "content-available": 1
        }
      }
    }
  });
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
          sound: {
            critical: 1,
            name: "importante.mp3",
            volume: 1.0
          },
          category: "DOOR_REQUEST_ALERT",
          "interruption-level": "critical"
        }
      }
    }
  });
});

// Alias compatibles con despliegue existente en proyectos Firebase (enviarDespacho / actualizarDespacho / etc.)
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

  const isInitialCreate = !before;
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
    }
  };

  if (!alreadySent || isInitialCreate) {
    msg.apns = {
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
          sound: {
            critical: 1,
            name: resolveApnsSound(clave),
            volume: 1.0
          },
          category: "EMERGENCY_DISPATCH",
          "interruption-level": "critical",
          "content-available": 1,
          "mutable-content": 1
        }
      }
    };
  }

  await admin.messaging().send(msg);
  await afterSnap.ref.update(Object.assign({}, backfillPatch, {
    pushSent: true,
    pushSentAt: Date.now()
  }));
});

exports.actualizarDespacho = exports.onDispatchUpdated;
exports.actualizarEstadoPersonal = exports.onPersonalUpdated;
exports.solicitarAperturaPuerta = exports.onDoorRequestCreated;



