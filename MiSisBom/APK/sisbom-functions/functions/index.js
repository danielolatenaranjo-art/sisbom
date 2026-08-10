const { onDocumentCreated, onDocumentUpdated, onDocumentDeleted } = require("firebase-functions/v2/firestore");
const { onRequest } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();

setGlobalOptions(new Object({ maxInstances: 10 }));

// Parse chat message to get senderId and cleaned text
function parseRawChatMessage(rawMsg) {
  if (!rawMsg) return { senderId: "", cleanText: "" };
  
  const firstSlash = rawMsg.indexOf("/");
  if (firstSlash === -1) return { senderId: "", cleanText: rawMsg };
  const secondSlash = rawMsg.indexOf("/", firstSlash + 1);
  if (secondSlash === -1) return { senderId: "", cleanText: rawMsg };
  const colonIndex = rawMsg.indexOf(":", secondSlash + 1);
  if (colonIndex === -1) return { senderId: "", cleanText: rawMsg };

  const header = rawMsg.substring(0, colonIndex).trim(); // "dd-mm-yyyy/hh:mm/userId"
  const msgText = rawMsg.substring(colonIndex + 1).trim();

  const headerParts = header.split("/");
  if (headerParts.length < 3) return { senderId: "", cleanText: rawMsg };

  const userId = headerParts[2].trim();
  return { senderId: userId, cleanText: msgText };
}

// Helper to format chat message header to human-readable name
async function cleanChatMessage(rawMsg) {
  const { senderId, cleanText } = parseRawChatMessage(rawMsg);
  if (!senderId) return rawMsg;

  try {
    const db = admin.firestore();
    const snap = await db.collection("personal").where("idRegistro", "==", senderId).get();
    if (!snap.empty) {
      const userData = snap.docs[0].data();
      const nombre = userData.nombreBombero || "Bombero";
      const displayName = formatFirefighterName(nombre);
      return `${displayName}: ${cleanText}`;
    }
  } catch (e) {
    console.error("Error looking up user for chat format:", e);
  }

  return `ID ${senderId}: ${cleanText}`;
}

function formatFirefighterName(name) {
  if (!name) return "Bombero";
  const parts = name.split(" ").map(s => s.trim()).filter(s => s !== "");
  if (parts.length === 0) return name;
  const first = parts[0];
  if (parts.length >= 3) {
    return `${first} ${parts[2]}`; // First name + Father's last name
  } else if (parts.length === 2) {
    return `${first} ${parts[1]}`;
  }
  return first;
}

// 1. DESPACHOS
exports.enviarDespacho = onDocumentCreated(
  "despachos/{id}",
  async (event) => {
    const data = event.data ? event.data.data() : null;

    if (!data) return null;

    const id = event.params.id;

    if (data.pushSent) {
      console.log("Push ya enviado, ignorando:", id);
      return null;
    }

    const clave = data.clave || "10-0";
    const claveApoyo = data.claveApoyo || "";
    const lugar = data.lugar || "Sin ubicación";

    let unidadesTexto = "Unidades en despacho";
    if (data.carrosTexto && data.carrosTexto !== "") {
        unidadesTexto = data.carrosTexto;
    } else if (data.unidades) {
      try {
        const unidades = Object.keys(data.unidades);
        if (unidades.length > 0) {
          unidadesTexto = unidades.join(" / ");
        }
      } catch (e) {
        console.log("No se pudieron leer unidades:", e);
      }
    }

    const titleText = (clave === "10-12" && claveApoyo) ? `${clave} (${claveApoyo}) en ${lugar}` : `${clave} en ${lugar}`;

    const payload = new Object({
      topic: "despachos",
      data: new Object({
        title: String(titleText),
        body: String(unidadesTexto),
        type: "DISPATCH",
        payloadId: String(id),
        clave: String(clave),
        claveApoyo: String(claveApoyo),
        lugar: String(lugar)
      }),
      android: new Object({
        priority: "high"
      })
    });

    try {
      await admin.messaging().send(payload);
      console.log("PUSH ENVIADO:", id);

      await event.data.ref.update(new Object({
        pushSent: true,
        pushSentAt: Date.now()
      }));

      return null;
    } catch (error) {
      console.error("Error enviando push:", error);
      return null;
    }
  }
);

// 2. NUEVAS ALERTAS Y ÓRDENES
exports.enviarAlerta = onDocumentCreated(
  "alertas/{id}",
  async (event) => {
    const data = event.data ? event.data.data() : null;
    if (!data) return null;

    const id = event.params.id;
    const razon = data.razonAlerta || "Nueva Notificación";
    let mensaje = data.mensajeAlerta || "Tienes nueva información";

    const duracion = String(data.duracion || "").trim().toUpperCase();
    let typePush = (duracion === "C") ? "CHAT" : "ALERT";

    let senderId = "";
    if (typePush === "CHAT") {
        const partes = mensaje.split("|").filter(s => s.trim() !== "");
        if (partes.length > 0) {
            const rawMsg = partes.at(partes.length - 1).trim();
            const parsed = parseRawChatMessage(rawMsg);
            senderId = parsed.senderId;
            mensaje = await cleanChatMessage(rawMsg);
        }
    }

    const aQuien = String(data.aQuienAlerta || "").trim().toUpperCase();

    const dbName = event.params.database || "(default)";
    const cuerpoId = data.cuerpoId || data.licenseKey || data.cuerpo || (dbName !== "(default)" ? dbName : "");
    const safeTenant = String(cuerpoId).replace(/[^a-zA-Z0-9-_.~%]/g, "_").trim();

    const payload = new Object({
      data: new Object({
        title: String(razon),
        body: String(mensaje),
        type: String(typePush),
        payloadId: String(id),
        gradoAlerta: String(duracion),
        clave: "",
        senderId: String(senderId),
        cuerpoId: String(safeTenant)
      }),
      android: new Object({
        priority: "high"
      })
    });

    try {
      if (aQuien === "TC" || aQuien === "1") {
        const topicName = safeTenant ? `alertas_generales_${safeTenant}` : "alertas_generales";
        Reflect.set(payload, "topic", topicName);
        await admin.messaging().send(payload);
        if (topicName !== "alertas_generales") {
            try {
                const fbPayload = Object.assign(new Object(), payload, new Object({ topic: "alertas_generales" }));
                await admin.messaging().send(fbPayload);
            } catch (_e) {}
        }
      } else if (aQuien === "CONDUCTORES") {
        const topicName = safeTenant ? `conductores_${safeTenant}` : "conductores";
        Reflect.set(payload, "topic", topicName);
        await admin.messaging().send(payload);
      }
      } else {
        const targets = aQuien.split(",").map(s => s.trim()).filter(s => s !== "");
        const promises = targets.map(async (t) => {
            const rawId = t.split(" ").at(0).trim();
            let finalTopic = rawId; // Valor por defecto

            try {
                const db = admin.firestore();
                // MAGIA: Busca si "rawId" es un idRadial
                const snapRadial = await db.collection("personal").where("idRadial", "==", rawId).get();
                if (!snapRadial.empty) {
                    const regId = snapRadial.docs.at(0).data().idRegistro;
                    if (regId) finalTopic = String(regId).trim();
                } else {
                    // Por seguridad, busca si también ingresaron un idRegistro directo
                    const snapReg = await db.collection("personal").where("idRegistro", "==", rawId).get();
                    if (!snapReg.empty) {
                        const regId2 = snapReg.docs.at(0).data().idRegistro;
                        if (regId2) finalTopic = String(regId2).trim();
                    }
                }
            } catch (e) {
                console.log("Error buscando ID:", e);
            }

            // Exclude sender from direct push
            if (senderId && finalTopic.trim() === senderId.trim()) {
                console.log("Excluyendo remitente de push directo:", finalTopic);
                return null;
            }

            // Eliminar espacios en blanco por seguridad de FCM (No se borran guiones)
            const safeTopic = finalTopic.replace(new RegExp(" ", "g"), "");
            const userPayload = Object.assign(new Object(), payload, new Object({ topic: "usuario_" + safeTopic }));
            return admin.messaging().send(userPayload).catch(e => console.log(e));
        });
        await Promise.all(promises);
      }
      return null;
    } catch (error) {
      console.error("Error push alerta:", error);
      return null;
    }
  }
);

// 3. ACTUALIZACIONES DE CHAT
exports.actualizarChat = onDocumentUpdated(
  "alertas/{id}",
  async (event) => {
    const newData = event.data.after.data();
    const oldData = event.data.before.data();
    
    if (!newData || !oldData) return null;
    if (String(newData.duracion).trim().toUpperCase() !== "C") return null; 
    if (newData.mensajeAlerta === oldData.mensajeAlerta) return null;

    const id = event.params.id;
    const razon = newData.razonAlerta || "Chat";
    const rawMsgs = String(newData.mensajeAlerta || "").split("|").filter(s => s.trim() !== "");
    if (rawMsgs.length === 0) return null;
    
    const ultimoMensajeRaw = rawMsgs.at(rawMsgs.length - 1).trim();
    const parsed = parseRawChatMessage(ultimoMensajeRaw);
    const senderId = parsed.senderId;
    const ultimoMensaje = await cleanChatMessage(ultimoMensajeRaw);
    const aQuien = String(newData.aQuienAlerta || "").trim().toUpperCase();

    const payload = new Object({
      data: new Object({
        title: String(razon),
        body: String(ultimoMensaje),
        type: "CHAT",
        payloadId: String(id),
        clave: "",
        senderId: String(senderId)
      }),
      android: new Object({
        priority: "high"
      })
    });

    try {
      if (aQuien === "TC" || aQuien === "1") {
        Reflect.set(payload, "topic", "alertas_generales");
        await admin.messaging().send(payload);
      } else {
        const targets = aQuien.split(",").map(s => s.trim()).filter(s => s !== "");
        const promises = targets.map(async (t) => {
            const rawId = t.split(" ").at(0).trim();
            let finalTopic = rawId;

            try {
                const db = admin.firestore();
                const snapRadial = await db.collection("personal").where("idRadial", "==", rawId).get();
                if (!snapRadial.empty) {
                    const regId = snapRadial.docs.at(0).data().idRegistro;
                    if (regId) finalTopic = String(regId).trim();
                } else {
                    const snapReg = await db.collection("personal").where("idRegistro", "==", rawId).get();
                    if (!snapReg.empty) {
                        const regId2 = snapReg.docs.at(0).data().idRegistro;
                        if (regId2) finalTopic = String(regId2).trim();
                    }
                }
            } catch (e) {
                console.log("Error buscando ID:", e);
            }

            // Exclude sender from direct push
            if (senderId && finalTopic.trim() === senderId.trim()) {
                console.log("Excluyendo remitente de push directo:", finalTopic);
                return null;
            }

            const safeTopic = finalTopic.replace(new RegExp(" ", "g"), "");
            const userPayload = Object.assign(new Object(), payload, new Object({ topic: "usuario_" + safeTopic }));
            return admin.messaging().send(userPayload).catch(e => console.log(e));
        });
        await Promise.all(promises);
      }
      return null;
    } catch (error) {
      console.error("Error push chat:", error);
      return null;
    }
  }
);

// 4. ACTUALIZACIONES DE DESPACHOS (SOLICITUDES 12-10 / 6-6)
exports.actualizarDespacho = onDocumentUpdated(
  "despachos/{id}",
  async (event) => {
    const newData = event.data.after ? event.data.after.data() : null;
    const oldData = event.data.before ? event.data.before.data() : null;

    if (!newData || !oldData) return null;

    const id = event.params.id;
    const oldUnidades = oldData.unidades || {};
    const newUnidades = newData.unidades || {};

    for (const unitName of Object.keys(newUnidades)) {
      const oldUnit = oldUnidades[unitName] || {};
      const newUnit = newUnidades[unitName] || {};

      // 12-10 Conductor check
      const old1210At = oldUnit.solicitudConductorAt || "";
      const new1210At = newUnit.solicitudConductorAt || "";
      const old1210Ts = oldUnit.solicitudConductorTimestamp || 0;
      const new1210Ts = newUnit.solicitudConductorTimestamp || 0;

      if (new1210At && (new1210At !== old1210At || (new1210Ts > 0 && new1210Ts !== old1210Ts))) {
        console.log(`Nueva solicitud 12-10 detectada para unidad ${unitName} en despacho ${id}`);
        const payload = {
          data: {
            title: "SOLICITUD 12-10",
            body: `Se solicita Conductor para la unidad ${unitName}`,
            type: "DISPATCH_UPDATE",
            payloadId: String(id),
            clave: String(newData.clave || ""),
            lugar: String(newData.lugar || "")
          },
          android: {
            priority: "high"
          },
          topic: "conductores"
        };
        try {
          await admin.messaging().send(payload);
          console.log(`Notificación 12-10 enviada para ${unitName}`);
        } catch (e) {
          console.error(`Error enviando notificación 12-10:`, e);
        }
      }

      // 6-6 Personal check
      const old66At = oldUnit.solicitudPersonalAt || "";
      const new66At = newUnit.solicitudPersonalAt || "";
      const old66Ts = oldUnit.solicitudPersonalTimestamp || 0;
      const new66Ts = newUnit.solicitudPersonalTimestamp || 0;

      if (new66At && (new66At !== old66At || (new66Ts > 0 && new66Ts !== old66Ts))) {
        console.log(`Nueva solicitud 6-6 detectada para unidad ${unitName} en despacho ${id}`);
        const payload = {
          data: {
            title: "SOLICITUD 6-6",
            body: `Se solicita Personal para la unidad ${unitName}`,
            type: "DISPATCH_UPDATE",
            payloadId: String(id),
            clave: String(newData.clave || ""),
            lugar: String(newData.lugar || "")
          },
          android: {
            priority: "high"
          },
          topic: "alertas_generales"
        };
        try {
          await admin.messaging().send(payload);
          console.log(`Notificación 6-6 enviada para ${unitName}`);
        } catch (e) {
          console.error(`Error enviando notificación 6-6:`, e);
        }
      }
    }

    return null;
  }
);

// Helper to sync Firestore personal document to Firebase Auth
async function syncUser(idRegistro, data) {
  const uid = String(idRegistro).trim();
  if (!uid) return;

  const contrasena = String(data.contrasena || '').trim();
  const activo = data.activo;
  const email = `${uid}@sisbom.com`;
  const authPassword = contrasena + "_secure_sisbom";

  let disabled = true;
  if (contrasena && (activo === 'SI' || activo === 1 || activo === '1' || activo === true)) {
    disabled = false;
  }

  try {
    await admin.auth().getUser(uid);
    // Update user
    await admin.auth().updateUser(uid, {
      email: email,
      password: authPassword,
      disabled: disabled
    });
    console.log(`User ${uid} updated in Auth.`);
  } catch (error) {
    if (error.code === 'auth/user-not-found') {
      // Create user
      await admin.auth().createUser({
        uid: uid,
        email: email,
        password: authPassword,
        disabled: disabled
      });
      console.log(`User ${uid} created in Auth.`);
    } else {
      console.error(`Error syncing user ${uid}:`, error);
      throw error;
    }
  }
}

// Triggers for personal collection changes
exports.syncPersonalToAuthCreated = onDocumentCreated(
  "personal/{id}",
  async (event) => {
    const data = event.data ? event.data.data() : null;
    if (!data) return null;
    try {
      await syncUser(event.params.id, data);
    } catch (e) {
      console.error("Error running syncPersonalToAuthCreated:", e);
    }
    return null;
  }
);

exports.syncPersonalToAuthUpdated = onDocumentUpdated(
  "personal/{id}",
  async (event) => {
    const data = event.data.after ? event.data.after.data() : null;
    if (!data) return null;
    try {
      await syncUser(event.params.id, data);
    } catch (e) {
      console.error("Error running syncPersonalToAuthUpdated:", e);
    }
    return null;
  }
);

// 5. VALIDACIÓN DE LICENCIA SAAS
exports.validateLicense = onRequest({ cors: true }, async (req, res) => {
  const licenseKey = String(req.body.licenseKey || req.query.licenseKey || "").trim();
  const hwid = String(req.body.hwid || req.query.hwid || "").trim();
  let moduleName = String(req.body.module || req.query.module || "").trim().toLowerCase();

  try {
    const db = admin.firestore();
    let clientSnap;
    let clientData;

    if (licenseKey) {
      // Lookup by license key explicitly
      const clientRef = db.collection("saas_clientes").doc(licenseKey);
      clientSnap = await clientRef.get();
      if (!clientSnap.exists) {
        return res.status(404).json({ authorized: false, reason: "La clave de licencia ingresada no existe o es inválida." });
      }
      clientData = clientSnap.data();
    } else {
      // HWID-only validation (automatic MAC activation)
      if (!hwid) {
        return res.status(400).json({ authorized: false, reason: "Debe proporcionar una clave de licencia o un Hardware ID (HWID)." });
      }
      const querySnap = await db.collection("saas_clientes").where("hardwareUUIDs", "array-contains", hwid).get();
      if (querySnap.empty) {
        return res.status(403).json({
          authorized: false,
          reason: `Este equipo no está autorizado ni registrado en SisBom SaaS. HWID: ${hwid}`
        });
      }
      clientSnap = querySnap.docs[0];
      clientData = clientSnap.data();
    }

    const subscriptionState = String(clientData.estadoSuscripcion || "bloqueado").toLowerCase(); // 'activo' | 'read_only' | 'bloqueado'
    
    // Determine the module for this specific HWID or module name
    let assignedModule = "";
    if (hwid && hwid !== "apk" && hwid !== "lista") {
      const dispositivos = clientData.dispositivos || {};
      const devObj = dispositivos[hwid];
      if (devObj) {
        if (typeof devObj === "object" && devObj !== null && devObj.module) {
          assignedModule = devObj.module;
        } else if (typeof devObj === "string") {
          assignedModule = devObj;
        }
      }
    }
    
    if (!assignedModule && moduleName) {
      assignedModule = moduleName;
    }
    
    // Fallback if not mapped explicitly
    if (!assignedModule) {
      const authorizedModules = clientData.modulos || {};
      if (authorizedModules.central && !authorizedModules.comandancia) {
        assignedModule = "central";
      } else if (authorizedModules.comandancia && !authorizedModules.central) {
        assignedModule = "comandancia";
      } else {
        assignedModule = "central"; // default fallback
      }
    }

    // Check total lock
    if (subscriptionState === "bloqueado") {
      return res.status(403).json({
        authorized: false,
        reason: "La suscripción de esta licencia ha sido bloqueada por falta de pago o suspensión del servicio.",
        nombreMostrar: clientData.nombreMostrar || clientData.nombreCliente || "Cliente SisBom",
        logoUrl: clientData.logoUrl || ""
      });
    }

    // Check if the assigned module is enabled for the client
    const authorizedModules = clientData.modulos || {};
    const isModuleAuthorized = !!authorizedModules[assignedModule];
    if (!isModuleAuthorized) {
      return res.status(403).json({
        authorized: false,
        reason: `El módulo '${assignedModule}' no está habilitado bajo esta licencia.`,
        nombreMostrar: clientData.nombreMostrar || clientData.nombreCliente || "Cliente SisBom",
        logoUrl: clientData.logoUrl || ""
      });
    }

    // Hardware verification (sanity check - bypass for mobile modules)
    if (hwid && assignedModule !== "apk" && assignedModule !== "lista") {
      const hwids = clientData.hardwareUUIDs || [];
      if (!hwids.includes(hwid)) {
        return res.status(403).json({
          authorized: false,
          reason: "Este equipo no está autorizado para ejecutar la aplicación.",
          nombreMostrar: clientData.nombreMostrar || clientData.nombreCliente || "Cliente SisBom",
          logoUrl: clientData.logoUrl || ""
        });
      }
    }

    // Return success details and config
    return res.status(200).json({
      authorized: true,
      status: subscriptionState, // 'activo' or 'read_only'
      clientName: clientData.nombreCliente || "Cliente SisBom",
      nombreMostrar: clientData.nombreMostrar || clientData.nombreCliente || "Cliente SisBom",
      logoUrl: clientData.logoUrl || "",
      module: assignedModule,
      firebaseConfig: clientData.firebaseConfig || {},
      vapidKey: clientData.vapidKey || (clientData.firebaseConfig ? clientData.firebaseConfig.vapidKey : "") || ""
    });

  } catch (error) {
    console.error("Error al validar licencia SaaS:", error);
    return res.status(500).json({ authorized: false, reason: "Error interno del servidor al procesar la validación." });
  }
});

// 6. AUTO-SUBSCRIBIR TOKENS WEB A TEMAS FCM
exports.subscribeTokenToTopics = onDocumentCreated(
  "fcm_tokens/{token}",
  async (event) => {
    const data = event.data ? event.data.data() : null;
    if (!data) return null;
    const token = event.params.token;
    const topics = data.topics || [];
    
    console.log(`FCM SUBSCRIBE: Token ${token} registering for topics:`, topics);
    
    for (const topic of topics) {
      try {
        await admin.messaging().subscribeToTopic(token, topic);
        console.log(`FCM SUBSCRIBE SUCCESS: Token ${token} -> Topic ${topic}`);
      } catch (e) {
        console.error(`FCM SUBSCRIBE ERROR: Token ${token} -> Topic ${topic}:`, e);
      }
    }
    return null;
  }
);

exports.unsubscribeTokenFromTopics = onDocumentDeleted(
  "fcm_tokens/{token}",
  async (event) => {
    const oldData = event.data ? event.data.data() : null;
    if (!oldData) return null;
    const token = event.params.token;
    const topics = oldData.topics || [];
    
    console.log(`FCM UNSUBSCRIBE: Token ${token} unregistering from topics:`, topics);
    
    for (const topic of topics) {
      try {
        await admin.messaging().unsubscribeFromTopic(token, topic);
        console.log(`FCM UNSUBSCRIBE SUCCESS: Token ${token} -X- Topic ${topic}`);
      } catch (e) {
        console.error(`FCM UNSUBSCRIBE ERROR: Token ${token} -X- Topic ${topic}:`, e);
      }
    }
    return null;
  }
);

exports.syncTokenTopicsOnUpdate = onDocumentUpdated(
  "fcm_tokens/{token}",
  async (event) => {
    const newData = event.data.after ? event.data.after.data() : null;
    const oldData = event.data.before ? event.data.before.data() : null;
    if (!newData || !oldData) return null;
    
    const token = event.params.token;
    const newTopics = new Set(newData.topics || []);
    const oldTopics = new Set(oldData.topics || []);
    
    // Subscribe to new topics
    for (const topic of newTopics) {
      if (!oldTopics.has(topic)) {
        try {
          await admin.messaging().subscribeToTopic(token, topic);
          console.log(`FCM UPDATE SUBSCRIBE: Token ${token} -> Topic ${topic}`);
        } catch (e) {
          console.error(`FCM UPDATE SUBSCRIBE ERROR: Token ${token} -> Topic ${topic}:`, e);
        }
      }
    }
    
    // Unsubscribe from removed topics
    for (const topic of oldTopics) {
      if (!newTopics.has(topic)) {
        try {
          await admin.messaging().unsubscribeFromTopic(token, topic);
          console.log(`FCM UPDATE UNSUBSCRIBE: Token ${token} -X- Topic ${topic}`);
        } catch (e) {
          console.error(`FCM UPDATE UNSUBSCRIBE ERROR: Token ${token} -X- Topic ${topic}:`, e);
        }
      }
    }
    return null;
  }
);

// 6. NOTIFICAR INICIO DE SESIÓN DE OPERADOR DE CENTRAL EN TIEMPO REAL (SOLO AL CAMBIAR OPERADOR EN FIRESTORE)
exports.notificarOperadorCentral = onDocumentWritten(
  { database: "{database}", document: "accesos/central" },
  async (event) => {
    const newData = event.data.after ? event.data.after.data() : null;
    const oldData = event.data.before ? event.data.before.data() : null;

    if (!newData) return null;

    const newEstado = String(newData.estado || "").toLowerCase();
    const oldEstado = oldData ? String(oldData.estado || "").toLowerCase() : "";

    const newOperator = String(newData.nombreBombero || newData.operador || "").trim();
    const oldOperator = oldData ? String(oldData.nombreBombero || oldData.operador || "").trim() : "";

    const newId = String(newData.idRegistro || "").trim();
    const oldId = oldData ? String(oldData.idRegistro || "").trim() : "";

    const isNowActive = newEstado === "activo" && (newOperator || newId);
    const wasActiveSameUser = oldEstado === "activo" && ((newId && newId === oldId) || (newOperator && newOperator === oldOperator));

    if (isNowActive && !wasActiveSameUser) {
      const dbName = event.params.database || "(default)";
      const cuerpoId = newData.cuerpoId || newData.licenseKey || newData.cuerpo || (dbName !== "(default)" ? dbName : "");
      const safeTenant = String(cuerpoId).replace(/[^a-zA-Z0-9-_.~%]/g, "_").trim();

      const topicName = safeTenant ? `alertas_generales_${safeTenant}` : "alertas_generales";
      const opName = newOperator || `ID ${newId}`;

      const payload = new Object({
        topic: topicName,
        data: new Object({
          title: "OPERADOR DE CENTRAL ACTIVO",
          body: `${opName} ha iniciado sesión como Operador Central de Alarmas.`,
          type: "STATUS_CHANGE",
          gradoAlerta: "1",
          cuerpoId: String(safeTenant)
        }),
        android: new Object({
          priority: "high"
        })
      });

      try {
        await admin.messaging().send(payload);
        console.log(`NOTIFICACION PUSH OPERADOR CENTRAL ENVIADA: ${opName} en topic ${topicName}`);

        if (topicName !== "alertas_generales") {
          try {
            const fbPayload = Object.assign(new Object(), payload, new Object({ topic: "alertas_generales" }));
            await admin.messaging().send(fbPayload);
          } catch (_e) {}
        }
      } catch (e) {
        console.error("Error enviando push de operador central:", e);
      }
    }

    return null;
  }
);






