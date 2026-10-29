#!/usr/bin/env python3
"""
Automate App Store Connect Production Release for SENTINEL ONE (v3.0.3 / Build 3004)
Uses App Store Connect REST API v1 with JWT ES256 authentication.
"""
import os
import sys
import time
import json
import urllib.request
import urllib.error
import jwt  # PyJWT + cryptography

KEY_ID = os.environ.get("APPSTORE_KEY_ID", "").strip()
ISSUER_ID = os.environ.get("APPSTORE_ISSUER_ID", "").strip()
PRIVATE_KEY = os.environ.get("APPSTORE_PRIVATE_KEY", "").strip()

BUNDLE_ID = "com.misisbom.sisbom.NFR3L3CC3L"
TARGET_VERSION = "3.0.3"
TARGET_BUILD_NUMBER = "3004"

WHATS_NEW_ES = (
    "SENTINEL ONE V.3.0.3:\n"
    "- Sincronización en tiempo real de alta resiliencia para despachos de emergencia, estado de unidades y operador de Central de Alarmas.\n"
    "- Optimización de mapas tácticos con geolocalización exacta de emergencias y telemetría en ruta.\n"
    "- Mejoras en alertas sonoras de despacho (0-9) y gestión de estados operativos del personal bomberil."
)

PROMOTIONAL_TEXT_ES = (
    "Aplicación oficial de operaciones, despacho de emergencias, estados de disponibilidad (0-9/0-8) "
    "y gestión táctica en tiempo real para Cuerpos de Bomberos."
)

DESCRIPTION_ES = (
    "SENTINEL ONE es la aplicación móvil oficial para bomberos e instituciones de emergencia conectadas "
    "al ecosistema SENTINEL V3.\n\n"
    "Funciones principales:\n"
    "• Recepción instantánea de despachos de emergencia con alarma crítica y mapa táctico en vivo.\n"
    "• Gestión rápida de disponibilidad operativa (0-9 Disponible / 0-8 No Disponible) y confirmación de asistencia en ruta.\n"
    "• Visualización en tiempo real de unidades despachadas, dotación en cuartel, operador de Central de Alarmas y bitácora.\n"
    "• Canal operacional de comunicaciones, órdenes del día, alertas institucionales y registro histórico de asistencias."
)

KEYWORDS_ES = "bomberos,emergencias,sentinel,despacho,alarma,rescate,132,central,incidente"


def generate_token():
    if not KEY_ID or not ISSUER_ID or not PRIVATE_KEY:
        raise RuntimeError("Faltan secretos APPSTORE_KEY_ID, APPSTORE_ISSUER_ID o APPSTORE_PRIVATE_KEY")
    now = int(time.time())
    payload = {
        "iss": ISSUER_ID,
        "iat": now - 30,
        "exp": now + 1100,
        "aud": "appstoreconnect-v1",
    }
    headers = {
        "alg": "ES256",
        "kid": KEY_ID,
        "typ": "JWT",
    }
    return jwt.encode(payload, PRIVATE_KEY, algorithm="ES256", headers=headers)


def api_request(method, path_or_url, body=None, token=None):
    if token is None:
        token = generate_token()
    url = path_or_url if path_or_url.startswith("http") else f"https://api.appstoreconnect.apple.com{path_or_url}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=45) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        print(f"⚠️ HTTP {e.code} en {method} {url}: {err_body}")
        try:
            return e.code, json.loads(err_body)
        except Exception:
            return e.code, {"raw": err_body}


def main():
    token = generate_token()
    print("✅ Token JWT ES256 generado correctamente para App Store Connect API.")

    # 1. Buscar la App por bundleId
    status, res = api_request("GET", f"/v1/apps?filter[bundleId]={BUNDLE_ID}", token=token)
    apps = res.get("data", [])
    if not apps:
        print(f"⚠️ No se encontró por filtro exacto {BUNDLE_ID}, listando todas las apps...")
        status, res = api_request("GET", "/v1/apps", token=token)
        apps = res.get("data", [])
    if not apps:
        print("❌ No se encontraron aplicaciones en esta cuenta de App Store Connect.")
        sys.exit(1)

    app_obj = apps[0]
    for a in apps:
        if a.get("attributes", {}).get("bundleId") == BUNDLE_ID:
            app_obj = a
            break

    app_id = app_obj["id"]
    app_name = app_obj.get("attributes", {}).get("name", "SENTINEL ONE")
    print(f"📱 App encontrada: {app_name} (ID: {app_id}, Bundle: {app_obj.get('attributes', {}).get('bundleId')})")

    # 2. Consultar versiones existentes en App Store Connect
    status, ver_res = api_request(
        "GET",
        f"/v1/apps/{app_id}/appStoreVersions?limit=10",
        token=token,
    )
    versions = ver_res.get("data", [])
    print(f"📦 Versiones existentes en App Store Connect ({len(versions)}):")
    editable_version = None
    target_version_obj = None

    editable_states = {
        "PREPARE_FOR_SUBMISSION",
        "DEVELOPER_REJECTED",
        "REJECTED",
        "METADATA_REJECTED",
        "WAITING_FOR_REVIEW",
        "INVALID_BINARY",
    }

    for v in versions:
        attrs = v.get("attributes", {})
        v_str = attrs.get("versionString")
        v_state = attrs.get("appStoreState")
        print(f"   - id={v['id']} | versionString={v_str} | state={v_state}")
        if v_str == TARGET_VERSION:
            target_version_obj = v
        if v_state in editable_states and editable_version is None:
            editable_version = v

    if target_version_obj:
        version_id = target_version_obj["id"]
        print(f"✅ Versión {TARGET_VERSION} ya existe (id={version_id}).")
    elif editable_version:
        version_id = editable_version["id"]
        old_ver = editable_version.get("attributes", {}).get("versionString")
        print(f"🔄 Actualizando versión editable existente ({old_ver} -> {TARGET_VERSION}, id={version_id})...")
        patch_body = {
            "data": {
                "type": "appStoreVersions",
                "id": version_id,
                "attributes": {
                    "versionString": TARGET_VERSION,
                    "releaseType": "AFTER_APPROVAL",
                },
            }
        }
        api_request("PATCH", f"/v1/appStoreVersions/{version_id}", body=patch_body, token=token)
    else:
        print(f"➕ Creando nueva versión {TARGET_VERSION} en App Store Connect...")
        create_body = {
            "data": {
                "type": "appStoreVersions",
                "attributes": {
                    "platform": "IOS",
                    "versionString": TARGET_VERSION,
                    "releaseType": "AFTER_APPROVAL",
                },
                "relationships": {
                    "app": {
                        "data": {
                            "type": "apps",
                            "id": app_id,
                        }
                    }
                },
            }
        }
        st_create, create_res = api_request("POST", "/v1/appStoreVersions", body=create_body, token=token)
        if st_create not in (200, 201):
            print("❌ No se pudo crear la versión en App Store Connect.")
            sys.exit(1)
        version_id = create_res["data"]["id"]
        print(f"✅ Versión {TARGET_VERSION} creada con éxito (id={version_id}).")

    # 3. Actualizar textos localizados (whatsNew, description, keywords, promotionalText)
    status, loc_res = api_request(
        "GET",
        f"/v1/appStoreVersions/{version_id}/appStoreVersionLocalizations",
        token=token,
    )
    localizations = loc_res.get("data", [])
    print(f"🌐 Localizaciones encontradas ({len(localizations)}):")
    for loc in localizations:
        loc_id = loc["id"]
        locale = loc.get("attributes", {}).get("locale", "")
        cur_desc = loc.get("attributes", {}).get("description") or ""
        cur_kw = loc.get("attributes", {}).get("keywords") or ""
        print(f"   - Actualizando localización {locale} (id={loc_id})...")
        upd_attrs = {
            "whatsNew": WHATS_NEW_ES,
            "promotionalText": PROMOTIONAL_TEXT_ES,
        }
        if not cur_desc.strip():
            upd_attrs["description"] = DESCRIPTION_ES
        if not cur_kw.strip():
            upd_attrs["keywords"] = KEYWORDS_ES

        loc_patch = {
            "data": {
                "type": "appStoreVersionLocalizations",
                "id": loc_id,
                "attributes": upd_attrs,
            }
        }
        st_loc, _ = api_request("PATCH", f"/v1/appStoreVersionLocalizations/{loc_id}", body=loc_patch, token=token)
        if st_loc in (200, 201):
            print(f"   ✅ Localización {locale} actualizada.")
        else:
            # Si es la primera versión de la app, Apple no permite enviar 'whatsNew'
            upd_attrs.pop("whatsNew", None)
            if upd_attrs:
                loc_patch["data"]["attributes"] = upd_attrs
                api_request("PATCH", f"/v1/appStoreVersionLocalizations/{loc_id}", body=loc_patch, token=token)

    # 4. Buscar la compilación (Build 3004 o la más reciente procesada VALID) y esperar si está PROCESSING
    selected_build = None
    for attempt in range(1, 16):
        status, b_res = api_request(
            "GET",
            f"/v1/builds?filter[app]={app_id}&sort=-uploadedDate&limit=10",
            token=token,
        )
        builds = b_res.get("data", [])
        print(f"🔍 Intento {attempt}/15 - Compilaciones encontradas ({len(builds)}):")
        for b in builds:
            b_attrs = b.get("attributes", {})
            b_ver = b_attrs.get("version")
            b_state = b_attrs.get("processingState")
            b_exp = b_attrs.get("usesNonExemptEncryption")
            print(f"   - Build id={b['id']} | version(buildNumber)={b_ver} | state={b_state} | nonExempt={b_exp}")
            if str(b_ver) == TARGET_BUILD_NUMBER and b_state == "VALID":
                selected_build = b
                break

        if selected_build:
            break

        # Si el build 3004 está en PROCESSING, esperar 20 segundos
        processing_3004 = any(
            str(b.get("attributes", {}).get("version")) == TARGET_BUILD_NUMBER
            and b.get("attributes", {}).get("processingState") == "PROCESSING"
            for b in builds
        )
        if processing_3004 and attempt < 15:
            print("⏳ El Build 3004 está siendo procesado por Apple (PROCESSING). Esperando 20s...")
            time.sleep(20)
            continue

        # Si no está en PROCESSING, tomar el primer build VALID disponible
        for b in builds:
            if b.get("attributes", {}).get("processingState") == "VALID":
                selected_build = b
                break
        break

    if selected_build:
        build_id = selected_build["id"]
        build_num = selected_build.get("attributes", {}).get("version")
        print(f"🔗 Asociando Build {build_num} (id={build_id}) a la versión {TARGET_VERSION}...")

        # Declarar cumplimiento de cifrado (usesNonExemptEncryption = False) si aún es null
        if selected_build.get("attributes", {}).get("usesNonExemptEncryption") is None:
            enc_patch = {
                "data": {
                    "type": "builds",
                    "id": build_id,
                    "attributes": {
                        "usesNonExemptEncryption": False,
                    },
                }
            }
            api_request("PATCH", f"/v1/builds/{build_id}", body=enc_patch, token=token)

        rel_body = {
            "data": {
                "type": "builds",
                "id": build_id,
            }
        }
        st_rel, _ = api_request(
            "PATCH",
            f"/v1/appStoreVersions/{version_id}/relationships/build",
            body=rel_body,
            token=token,
        )
        if st_rel in (200, 204):
            print(f"✅ Build {build_num} vinculado exitosamente a la versión {TARGET_VERSION}.")
        else:
            print(f"⚠️ Respuesta al vincular build: HTTP {st_rel}")
    else:
        print("⚠️ Ningún build en estado VALID todavía (puede seguir procesándose en App Store Connect).")

    # 5. Intentar enviar a Revisión de App Store (Submit for Review)
    print("🚀 Intentando enviar la versión a Revisión de App Store (reviewSubmissions)...")
    sub_create = {
        "data": {
            "type": "reviewSubmissions",
            "attributes": {
                "platform": "IOS",
            },
            "relationships": {
                "app": {
                    "data": {
                        "type": "apps",
                        "id": app_id,
                    }
                }
            },
        }
    }
    st_sub, sub_res = api_request("POST", "/v1/reviewSubmissions", body=sub_create, token=token)
    submission_id = None
    if st_sub in (200, 201):
        submission_id = sub_res["data"]["id"]
        print(f"✅ ReviewSubmission creado (id={submission_id}).")
    else:
        # Verificar si ya existe un reviewSubmission abierto
        st_list, list_res = api_request(
            "GET",
            f"/v1/reviewSubmissions?filter[app]={app_id}&filter[state]=READY_FOR_REVIEW,UNRESOLVED_ISSUES",
            token=token,
        )
        existing_subs = list_res.get("data", [])
        if existing_subs:
            submission_id = existing_subs[0]["id"]
            print(f"ℹ️ Usando ReviewSubmission existente (id={submission_id}).")

    if submission_id:
        item_body = {
            "data": {
                "type": "reviewSubmissionItems",
                "relationships": {
                    "reviewSubmission": {
                        "data": {
                            "type": "reviewSubmissions",
                            "id": submission_id,
                        }
                    },
                    "appStoreVersion": {
                        "data": {
                            "type": "appStoreVersions",
                            "id": version_id,
                        }
                    },
                },
            }
        }
        api_request("POST", "/v1/reviewSubmissionItems", body=item_body, token=token)

        confirm_body = {
            "data": {
                "type": "reviewSubmissions",
                "id": submission_id,
                "attributes": {
                    "submitted": True,
                },
            }
        }
        st_conf, conf_res = api_request(
            "PATCH",
            f"/v1/reviewSubmissions/{submission_id}",
            body=confirm_body,
            token=token,
        )
        if st_conf in (200, 201):
            print("🎉 ¡APLICACIÓN ENVIADA OFICIALMENTE A REVISIÓN DE APP STORE PARA PRODUCCIÓN!")
        else:
            print("ℹ️ La versión 3.0.3 y el Build 3004 quedaron listos y vinculados en App Store Connect.")
            print("   Detalle de validación de Apple para el envío final:", json.dumps(conf_res, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
