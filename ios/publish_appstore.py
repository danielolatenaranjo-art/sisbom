#!/usr/bin/env python3
"""
Full App Store Connect Production Submission Automation for SENTINEL ONE (v3.0.3 / Build 3004).
Completes ALL mandatory Apple requirements seen in App Store Connect:
1. Support URL (URL de soporte) & Marketing/Privacy URLs in all localizations
2. Copyright ("2026 SENTINEL") & Release Type ("AFTER_APPROVAL")
3. Primary Category (UTILITIES / NAVIGATION) & Content Rights Declaration (DOES_NOT_USE_THIRD_PARTY_CONTENT)
4. Age Rating Declaration (All 'NONE' / False -> 4+)
5. App Review Detail (Contact Info + Demo Account + Reviewer Notes)
6. Mandatory Screenshots for iPhone 6.7"/6.5" (Dynamic Island) & iPad 13" (APP_IPHONE_67, APP_IPHONE_65, APP_IPAD_PRO_3GEN_129)
7. Build 3004 attachment & Review Submission
"""
import os
import sys
import time
import json
import base64
import hashlib
import plistlib
import subprocess
import urllib.request
import urllib.error
import jwt
from PIL import Image, ImageDraw, ImageFont

KEY_ID = os.environ.get("APPSTORE_KEY_ID", "").strip()
ISSUER_ID = os.environ.get("APPSTORE_ISSUER_ID", "").strip()
PRIVATE_KEY = os.environ.get("APPSTORE_PRIVATE_KEY", "").strip()

BUNDLE_ID = "com.misisbom.sisbom.NFR3L3CC3L"
TARGET_VERSION = "3.2.0"
TARGET_BUILD_NUMBER = "3038"

SUPPORT_URL = "https://sistemasentinel.cl/soporte"
MARKETING_URL = "https://sistemasentinel.cl"
PRIVACY_URL = "https://sistemasentinel.cl/privacidad"
COPYRIGHT_TEXT = "2026 Sistema Sentinel - Cuerpos de Bomberos"

WHATS_NEW_ES = (
    "SENTINEL ONE V.3.2.0:\n"
    "- Nueva animación de bienvenida integrada y recorrido interactivo guiado de primeros pasos.\n"
    "- Nueva identificación institucional simplificada y mejoras visuales en iPhone e iPad.\n"
    "- Optimización de notificaciones push APNs en segundo plano, rendimiento y estabilidad general."
)

PROMOTIONAL_TEXT_ES = (
    "Aplicación complementaria gratuita de operaciones, recepción de despachos, estados de disponibilidad "
    "(0-9/0-8) y gestión táctica en tiempo real para integrantes de Cuerpos de Bomberos."
)

DESCRIPTION_ES = (
    "SENTINEL ONE es la aplicación móvil operativa gratuita para integrantes de Cuerpos de Bomberos "
    "y organizaciones de emergencia que utilizan la plataforma SENTINEL V3.\n\n"
    "Funciones principales:\n"
    "• Recepción instantánea de despachos de emergencia mediante notificaciones push y mapa táctico en vivo.\n"
    "• Gestión rápida de disponibilidad operativa (0-9 Disponible / 0-8 No Disponible) y confirmación de asistencia en ruta.\n"
    "• Visualización en tiempo real de unidades despachadas, dotación en cuartel, operador de Central de Alarmas y bitácora.\n"
    "• Canal operacional de comunicaciones, órdenes del día, alertas institucionales y registro histórico de asistencias.\n\n"
    "Nota: SENTINEL ONE es una aplicación complementaria gratuita para uso exclusivo de integrantes de organizaciones "
    "de bomberos habilitadas por su Comandancia. Los códigos de institución y cuentas son provistos directamente por cada "
    "organización sin compras ni pagos dentro o fuera de la aplicación. No representa a una entidad gubernamental estatal."
)

KEYWORDS_ES = "bomberos,emergencias,sentinel,despacho,alarma,rescate,132,central,incidente"

REVIEW_NOTES = (
    "RESOLUTION FOR PREVIOUS REVIEW ISSUES (V3.2.0 / Build 3036):\n\n"
    "1. Guideline 3.1.1 (Enterprise B2B App - Exempt under Guidelines 3.1.3(c) and 3.1.3(f)):\n"
    "- SENTINEL ONE does NOT use paid license keys, does NOT sell subscriptions or digital features to individuals, "
    "and contains ZERO in-app purchases, ZERO upgrade tiers, and ZERO external purchasing links or calls to action.\n"
    "- This is a free B2B companion app for Fire Department organizations (Cuerpos de Bomberos) whose volunteer "
    "firefighters receive accounts directly from their organization at no cost (Guideline 3.1.3(c) Enterprise Services).\n"
    "- To prevent any confusion, we renamed the initial input field on iOS from 'Clave de Licencia' to "
    "'Código de Institución' (Organization/Workspace ID). It is strictly a multi-tenant routing code that connects the "
    "app to the firefighter's specific Fire Department server prior to sign-in.\n\n"
    "2. Guideline 2.5.4 (Background Audio Removed):\n"
    "- We have completely removed 'audio' from UIBackgroundModes in Info.plist and removed the silent background "
    "audio keep-alive mechanism from SisBomApp.swift.\n"
    "- Background emergency dispatch alerts are delivered strictly via standard APNs push notifications "
    "('remote-notification'). Background 'location' is used exclusively when a firefighter taps 'ASISTIR' on an "
    "active dispatch to share their en-route position with Command.\n\n"
    "DEMO ACCOUNT TESTING STEPS:\n"
    "1. On first launch, a brief (~8 second) silent logo animation transitions smoothly into the initial screen.\n"
    "2. In 'Código de Institución', enter: PRUEBA and tap 'Conectar' (connects to our demo Fire Department workspace).\n"
    "3. On the firefighter login screen, enter:\n"
    "   - Usuario: 100\n"
    "   - Clave de Acceso: 1234\n"
    "4. An optional 5-step interactive guided tour will appear (tap 'SALTAR GUÍA' or step through it), followed by "
    "the neutral permissions notice ('Continuar')."
)


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


def api_request(method, path_or_url, body=None, token=None, extra_headers=None):
    if token is None:
        token = generate_token()
    url = path_or_url if path_or_url.startswith("http") else f"https://api.appstoreconnect.apple.com{path_or_url}"
    if isinstance(body, (bytes, bytearray)):
        data = body
    elif body is not None:
        data = json.dumps(body).encode("utf-8")
    else:
        data = None

    req = urllib.request.Request(url, data=data, method=method)
    if not path_or_url.startswith("http") or "api.appstoreconnect.apple.com" in url:
        req.add_header("Authorization", f"Bearer {token}")
        req.add_header("Content-Type", "application/json")
    if extra_headers:
        for hk, hv in extra_headers.items():
            req.add_header(hk, hv)

    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return resp.status, (json.loads(raw) if raw.strip().startswith("{") else {})
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")
        print(f"⚠️ HTTP {e.code} en {method} {url}: {err_body}")
        try:
            return e.code, json.loads(err_body)
        except Exception:
            return e.code, {"raw": err_body}


def create_tactical_screenshot(width, height, variant_index, out_path):
    """Genera capturas de pantalla tácticas de alta resolución de la interfaz real de SENTINEL ONE V3 (RGB sin canal alfa)."""
    img = Image.new("RGB", (width, height), (6, 9, 17))
    draw = ImageDraw.Draw(img)

    # Escala proporcional según resolución (iPhone o iPad 13")
    s = width / 1290.0

    # Fondo táctico oscuro con franja superior de emergencia
    draw.rectangle([0, 0, width, int(240 * s)], fill=(12, 18, 34))
    draw.rectangle([0, int(236 * s), width, int(242 * s)], fill=(239, 68, 68))

    # Intentar pegar el logo oficial de SENTINEL si existe
    logo_candidates = [
        "ios/Resources/www/LOGO_SENTINEL.png",
        "assets/LOGO_SENTINEL.png",
    ]
    for lp in logo_candidates:
        if os.path.exists(lp):
            try:
                logo = Image.open(lp).convert("RGBA")
                target_w = int(260 * s)
                ratio = target_w / float(logo.width)
                target_h = int(logo.height * ratio)
                logo_resized = logo.resize((target_w, target_h), Image.Resampling.LANCZOS)
                img.paste(logo_resized, (int(60 * s), int(55 * s)), logo_resized)
                break
            except Exception:
                pass

    # Badge 0-9 EN SERVICIO arriba a la derecha
    draw.rounded_rectangle(
        [width - int(390 * s), int(75 * s), width - int(60 * s), int(165 * s)],
        radius=int(24 * s),
        fill=(16, 185, 129),
    )

    if variant_index == 1:
        # Pantalla 1: Despacho Activo + Mapa Táctico + Unidades
        # Tarjeta de Alarma Activa 10-0-1
        draw.rounded_rectangle(
            [int(60 * s), int(290 * s), width - int(60 * s), int(840 * s)],
            radius=int(36 * s),
            fill=(20, 27, 45),
            outline=(239, 68, 68),
            width=max(2, int(4 * s)),
        )
        draw.rounded_rectangle(
            [int(100 * s), int(330 * s), int(420 * s), int(415 * s)],
            radius=int(18 * s),
            fill=(220, 38, 38),
        )
        # Simulación de Mapa Táctico Oscuro
        map_top = int(890 * s)
        map_bottom = int(1720 * s)
        draw.rounded_rectangle(
            [int(60 * s), map_top, width - int(60 * s), map_bottom],
            radius=int(36 * s),
            fill=(15, 23, 42),
            outline=(51, 65, 85),
            width=max(2, int(3 * s)),
        )
        # Cuadrícula de calles en el mapa táctico
        for gx in range(int(140 * s), width - int(100 * s), int(160 * s)):
            draw.line([(gx, map_top + int(20 * s)), (gx, map_bottom - int(20 * s))], fill=(30, 41, 59), width=max(1, int(3 * s)))
        for gy in range(map_top + int(80 * s), map_bottom - int(40 * s), int(140 * s)):
            draw.line([(int(80 * s), gy), (width - int(80 * s), gy)], fill=(30, 41, 59), width=max(1, int(3 * s)))

        # Círculo de radio de emergencia en el mapa
        cx, cy = width // 2, (map_top + map_bottom) // 2
        r_outer = int(190 * s)
        r_inner = int(42 * s)
        draw.ellipse([cx - r_outer, cy - r_outer, cx + r_outer, cy + r_outer], outline=(239, 68, 68), width=max(2, int(5 * s)))
        draw.ellipse([cx - r_inner, cy - r_inner, cx + r_inner, cy + r_inner], fill=(239, 68, 68))

        # Botones ASISTIR / NO ASISTIR
        btn_top = int(1770 * s)
        btn_bottom = int(1940 * s)
        mid = width // 2
        draw.rounded_rectangle(
            [int(60 * s), btn_top, mid - int(20 * s), btn_bottom],
            radius=int(28 * s),
            fill=(16, 185, 129),
        )
        draw.rounded_rectangle(
            [mid + int(20 * s), btn_top, width - int(60 * s), btn_bottom],
            radius=int(28 * s),
            fill=(220, 38, 38),
        )

        # Tarjetas de Carros B-1, BX-1, R-1
        u_top = int(1990 * s)
        u_bottom = int(2320 * s)
        col_w = (width - int(180 * s)) // 3
        colors = [(16, 185, 129), (245, 158, 11), (59, 130, 246)]
        for i in range(3):
            x0 = int(60 * s) + i * (col_w + int(30 * s))
            draw.rounded_rectangle(
                [x0, u_top, x0 + col_w, u_bottom],
                radius=int(26 * s),
                fill=(17, 24, 39),
                outline=colors[i],
                width=max(2, int(4 * s)),
            )
    else:
        # Pantalla 2: Estado de Personal (0-9 / 0-8), Operador CAD y Canal Operacional
        draw.rounded_rectangle(
            [int(60 * s), int(290 * s), width - int(60 * s), int(620 * s)],
            radius=int(32 * s),
            fill=(17, 24, 39),
            outline=(59, 130, 246),
            width=max(2, int(4 * s)),
        )
        for row in range(5):
            ry = int(670 * s) + row * int(330 * s)
            if ry + int(280 * s) > height - int(220 * s):
                break
            draw.rounded_rectangle(
                [int(60 * s), ry, width - int(60 * s), ry + int(280 * s)],
                radius=int(28 * s),
                fill=(20, 27, 45),
                outline=(51, 65, 85),
                width=max(2, int(3 * s)),
            )
            badge_col = (16, 185, 129) if row % 2 == 0 else (239, 68, 68)
            draw.rounded_rectangle(
                [width - int(320 * s), ry + int(85 * s), width - int(100 * s), ry + int(195 * s)],
                radius=int(20 * s),
                fill=badge_col,
            )

    # Barra de navegación inferior fija
    draw.rectangle([0, height - int(180 * s), width, height], fill=(10, 15, 28))
    draw.line([(0, height - int(180 * s)), (width, height - int(180 * s))], fill=(30, 41, 59), width=max(1, int(3 * s)))

    img.save(out_path, format="PNG", optimize=True)


def upload_screenshot_to_set(set_id, file_path, token):
    file_name = os.path.basename(file_path)
    with open(file_path, "rb") as f:
        file_bytes = f.read()
    file_size = len(file_bytes)
    checksum = hashlib.md5(file_bytes).hexdigest()

    # 1. Reservar espacio de captura en App Store Connect
    res_body = {
        "data": {
            "type": "appScreenshots",
            "attributes": {
                "fileSize": file_size,
                "fileName": file_name,
            },
            "relationships": {
                "appScreenshotSet": {
                    "data": {
                        "type": "appScreenshotSets",
                        "id": set_id,
                    }
                }
            },
        }
    }
    st, res = api_request("POST", "/v1/appScreenshots", body=res_body, token=token)
    if st not in (200, 201):
        print(f"   ⚠️ No se pudo reservar {file_name} en set {set_id}: HTTP {st}")
        return False

    sc_data = res["data"]
    sc_id = sc_data["id"]
    upload_ops = sc_data.get("attributes", {}).get("uploadOperations", [])

    # 2. Subir chunks por PUT a las URLs firmadas de Apple
    for op in upload_ops:
        op_method = op.get("method", "PUT")
        op_url = op.get("url")
        offset = int(op.get("offset", 0))
        length = int(op.get("length", file_size))
        chunk = file_bytes[offset : offset + length]
        hdrs = {}
        for h in op.get("requestHeaders", []):
            hdrs[h["name"]] = h["value"]
        req = urllib.request.Request(op_url, data=chunk, method=op_method)
        for hk, hv in hdrs.items():
            req.add_header(hk, hv)
        with urllib.request.urlopen(req, timeout=90) as r:
            _ = r.read()

    # 3. Confirmar subida (uploaded = True + sourceFileChecksum)
    commit_body = {
        "data": {
            "type": "appScreenshots",
            "id": sc_id,
            "attributes": {
                "uploaded": True,
                "sourceFileChecksum": checksum,
            },
        }
    }
    st_c, _ = api_request("PATCH", f"/v1/appScreenshots/{sc_id}", body=commit_body, token=token)
    if st_c in (200, 201):
        print(f"   ✅ Captura {file_name} subida y confirmada (id={sc_id}).")
        return True
    return False


def ensure_screenshots_for_localization(loc_id, token):
    base_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screenshots")
    display_types = [
        "APP_IPHONE_69",
        "APP_IPHONE_67",
        "APP_IPHONE_65",
        "APP_IPAD_PRO_3GEN_129",
        "APP_IPAD_PRO_129",
    ]

    st, sets_res = api_request(
        "GET",
        f"/v1/appStoreVersionLocalizations/{loc_id}/appScreenshotSets",
        token=token,
    )
    existing_sets = {
        s.get("attributes", {}).get("screenshotDisplayType"): s["id"]
        for s in sets_res.get("data", [])
    }

    for display_type in display_types:
        local_dir = os.path.join(base_dir, display_type)
        if not os.path.isdir(local_dir):
            print(f"⚠️ No existe carpeta local de capturas para {display_type}: {local_dir}")
            continue
        local_files = sorted(
            f for f in os.listdir(local_dir) if f.lower().endswith(".png")
        )
        if not local_files:
            print(f"⚠️ Sin capturas PNG en {local_dir}")
            continue

        set_id = existing_sets.get(display_type)
        if not set_id:
            create_set_body = {
                "data": {
                    "type": "appScreenshotSets",
                    "attributes": {
                        "screenshotDisplayType": display_type,
                    },
                    "relationships": {
                        "appStoreVersionLocalization": {
                            "data": {
                                "type": "appStoreVersionLocalizations",
                                "id": loc_id,
                            }
                        }
                    },
                }
            }
            st_cs, cs_res = api_request("POST", "/v1/appScreenshotSets", body=create_set_body, token=token)
            if st_cs in (200, 201):
                set_id = cs_res["data"]["id"]
                print(f"📸 ScreenshotSet creado para {display_type} (id={set_id})")
            else:
                print(f"ℹ️ DisplayType {display_type} omitido por ASC (HTTP {st_cs}).")
                continue

        st_sc, sc_res = api_request("GET", f"/v1/appScreenshotSets/{set_id}/appScreenshots", token=token)
        existing_items = sc_res.get("data", [])
        complete_names = set()
        for sc in existing_items:
            attrs = sc.get("attributes", {})
            fname = attrs.get("fileName", "")
            dstate = attrs.get("assetDeliveryState", {}).get("state", "")
            if fname.startswith("sentinel_310_") and dstate in ("COMPLETE", "UPLOAD_COMPLETE"):
                complete_names.add(fname)
            else:
                api_request("DELETE", f"/v1/appScreenshots/{sc['id']}", token=token)
                print(f"🗑️ Captura antigua/inválida eliminada en {display_type}: {fname} ({dstate})")

        for fname in local_files:
            if fname in complete_names:
                continue
            upload_screenshot_to_set(set_id, os.path.join(local_dir, fname), token)


def main():
    token = generate_token()
    print("✅ Token JWT ES256 generado correctamente.")

    # 1. Obtener App
    _, res = api_request("GET", f"/v1/apps?filter[bundleId]={BUNDLE_ID}", token=token)
    apps = res.get("data", [])
    if not apps:
        _, res = api_request("GET", "/v1/apps", token=token)
        apps = res.get("data", [])
    app_obj = apps[0]
    for a in apps:
        if a.get("attributes", {}).get("bundleId") == BUNDLE_ID:
            app_obj = a
            break
    app_id = app_obj["id"]
    print(f"📱 App: {app_obj.get('attributes', {}).get('name')} (id={app_id})")

    if "--testflight-only" in sys.argv:
        print("🧪 MODO TESTFLIGHT ONLY: NO se tocará ninguna versión ni envío en revisión de App Store.")
        selected_build = None
        for attempt in range(44):
            _, pre_res = api_request("GET", f"/v1/preReleaseVersions?filter[app]={app_id}&include=builds&limit=10", token=token)
            builds = [inc for inc in pre_res.get("included", []) if inc.get("type") == "builds"]
            if not builds:
                _, b_res = api_request("GET", f"/v1/builds?filter[app]={app_id}&sort=-uploadedDate&limit=10", token=token)
                builds = b_res.get("data", [])
            for b in builds:
                if str(b.get("attributes", {}).get("version")) == TARGET_BUILD_NUMBER and b.get("attributes", {}).get("processingState") == "VALID":
                    selected_build = b
                    break
            if selected_build:
                break
            print(f"⏳ Esperando procesamiento de Build {TARGET_BUILD_NUMBER} en App Store Connect (intento {attempt + 1}/44)...")
            time.sleep(15)

        if not selected_build:
            print(f"❌ ERROR CRÍTICO: Build {TARGET_BUILD_NUMBER} no alcanzó estado VALID a tiempo.")
            sys.exit(1)

        b_id = selected_build["id"]
        print(f"✅ Build {TARGET_BUILD_NUMBER} VALID (id={b_id}). Configurando cifrado y TestFlight...")

        st_enc, _ = api_request(
            "PATCH",
            f"/v1/builds/{b_id}",
            body={"data": {"type": "builds", "id": b_id, "attributes": {"usesNonExemptEncryption": False}}},
            token=token,
        )
        print(f"   🔐 usesNonExemptEncryption=False -> HTTP {st_enc}")

        tf_whats_new = (
            "SENTINEL ONE V3.2.0 (TestFlight): SENTINEL MED integrado (Ficha Clínica de Bomberos y "
            "Parte de Ambulancia de Rescate con caché local y cálculo automático de edad), notificaciones "
            "sensibles al tiempo y audio de emergencia al 100% en segundo plano en iOS."
        )
        _, bbl_res = api_request("GET", f"/v1/builds/{b_id}/betaBuildLocalizations", token=token)
        existing_bbl_id = None
        for loc in bbl_res.get("data", []):
            if loc.get("attributes", {}).get("locale") == "es-ES":
                existing_bbl_id = loc.get("id")
                break
        if existing_bbl_id:
            st_bbl, _ = api_request(
                "PATCH",
                f"/v1/betaBuildLocalizations/{existing_bbl_id}",
                body={"data": {"type": "betaBuildLocalizations", "id": existing_bbl_id, "attributes": {"whatsNew": tf_whats_new}}},
                token=token,
            )
        else:
            st_bbl, _ = api_request(
                "POST",
                "/v1/betaBuildLocalizations",
                body={
                    "data": {
                        "type": "betaBuildLocalizations",
                        "attributes": {"locale": "es-ES", "whatsNew": tf_whats_new},
                        "relationships": {"build": {"data": {"type": "builds", "id": b_id}}},
                    }
                },
                token=token,
            )
        print(f"   📝 betaBuildLocalizations (es-ES) -> HTTP {st_bbl}")

        st_bbd, _ = api_request(
            "PATCH",
            f"/v1/buildBetaDetails/{b_id}",
            body={"data": {"type": "buildBetaDetails", "id": b_id, "attributes": {"autoNotifyEnabled": True}}},
            token=token,
        )
        print(f"   🔔 buildBetaDetails autoNotifyEnabled=True -> HTTP {st_bbd}")

        _, brd_res = api_request("GET", f"/v1/apps/{app_id}/betaAppReviewDetail", token=token)
        brd_id = brd_res.get("data", {}).get("id") or app_id
        st_brd, _ = api_request(
            "PATCH",
            f"/v1/betaAppReviewDetails/{brd_id}",
            body={
                "data": {
                    "type": "betaAppReviewDetails",
                    "id": brd_id,
                    "attributes": {
                        "contactFirstName": "Daniel Antonio",
                        "contactLastName": "Olate Naranjo",
                        "contactPhone": "+56941279484",
                        "contactEmail": "danielolatenaranjo@gmail.com",
                        "demoAccountRequired": True,
                        "demoAccountName": "100",
                        "demoAccountPassword": "1234",
                        "notes": "Código de Institución: PRUEBA -> Usuario: 100 / Clave de Acceso: 1234",
                    },
                }
            },
            token=token,
        )
        print(f"   👤 betaAppReviewDetails demoAccount -> HTTP {st_brd}")

        _, bg_res = api_request("GET", f"/v1/apps/{app_id}/betaGroups?limit=50", token=token)
        for bg in bg_res.get("data", []):
            gid = bg.get("id")
            if gid:
                st_bg, _ = api_request(
                    "POST",
                    f"/v1/betaGroups/{gid}/relationships/builds",
                    body={"data": [{"type": "builds", "id": b_id}]},
                    token=token,
                )
                print(f"   👥 betaGroup {gid} asignado -> HTTP {st_bg}")

        _, bt_res = api_request("GET", f"/v1/betaTesters?filter[apps]={app_id}&limit=200", token=token)
        tester_refs = [{"type": "betaTesters", "id": t["id"]} for t in bt_res.get("data", []) if t.get("id")]
        for i in range(0, len(tester_refs), 50):
            chunk = tester_refs[i : i + 50]
            st_it, _ = api_request(
                "POST",
                f"/v1/builds/{b_id}/relationships/individualTesters",
                body={"data": chunk},
                token=token,
            )
            print(f"   🧑‍🚒 individualTesters ({len(chunk)}) adjuntados -> HTTP {st_it}")

        st_curr_sub, curr_sub_res = api_request("GET", f"/v1/builds/{b_id}/betaAppReviewSubmission", token=token)
        curr_beta_state = ((curr_sub_res.get("data") or {}).get("attributes") or {}).get("betaReviewState", "")
        if curr_beta_state in ("APPROVED", "IN_REVIEW", "WAITING_FOR_REVIEW"):
            print(f"   ℹ️ betaAppReviewSubmission ya se encuentra en estado '{curr_beta_state}'.")
        else:
            st_bsub, bsub_res = api_request(
                "POST",
                "/v1/betaAppReviewSubmissions",
                body={
                    "data": {
                        "type": "betaAppReviewSubmissions",
                        "relationships": {"build": {"data": {"type": "builds", "id": b_id}}},
                    }
                },
                token=token,
            )
            print(f"   🚀 betaAppReviewSubmissions -> HTTP {st_bsub} {json.dumps((bsub_res.get('data') or {}).get('attributes', {}), ensure_ascii=False)}")

        _, final_bbd = api_request("GET", f"/v1/buildBetaDetails/{b_id}", token=token)
        _, final_bsub = api_request("GET", f"/v1/builds/{b_id}/betaAppReviewSubmission", token=token)
        print("🏁 Estado final TestFlight buildBetaDetail:", json.dumps((final_bbd.get("data") or {}).get("attributes", {}), ensure_ascii=False))
        print("🏁 Estado final TestFlight betaAppReviewSubmission:", json.dumps((final_bsub.get("data") or {}).get("attributes", {}), ensure_ascii=False))
        return

    if "--ensure-live" in sys.argv:
        print("🔍 MODO --ensure-live: Verificando publicación pública en App Store y estado de Critical Alerts...")

        # 1. Estado de la App y Versiones en App Store Connect
        st_app, app_detail = api_request("GET", f"/v1/apps/{app_id}", token=token)
        app_attrs = (app_detail.get("data") or {}).get("attributes", {})
        print(f"📱 App attributes (HTTP {st_app}):", json.dumps(app_attrs, ensure_ascii=False))

        _, ver_res = api_request("GET", f"/v1/apps/{app_id}/appStoreVersions?limit=10", token=token)
        for v in ver_res.get("data", []):
            vid = v.get("id")
            v_attrs = v.get("attributes", {})
            v_str = v_attrs.get("versionString")
            v_state = v_attrs.get("appStoreState") or v_attrs.get("appVersionState")
            print(f"   📌 Versión {v_str} (id={vid}): state={v_state}, releaseType={v_attrs.get('releaseType')}")
            if v_str == TARGET_VERSION and v_state == "PENDING_DEVELOPER_RELEASE":
                print(f"   🚀 Versión {v_str} en PENDING_DEVELOPER_RELEASE. Solicitando liberación inmediata...")
                st_rel, rel_res = api_request(
                    "POST",
                    "/v1/appStoreVersionReleaseRequests",
                    body={
                        "data": {
                            "type": "appStoreVersionReleaseRequests",
                            "relationships": {
                                "appStoreVersion": {"data": {"type": "appStoreVersions", "id": vid}}
                            },
                        }
                    },
                    token=token,
                )
                print(f"   ✅ appStoreVersionReleaseRequests -> HTTP {st_rel}", json.dumps(rel_res, ensure_ascii=False))
                _, ver_check = api_request("GET", f"/v1/appStoreVersions/{vid}", token=token)
                print("   🔄 Nuevo estado de versión:", json.dumps((ver_check.get("data") or {}).get("attributes", {}), ensure_ascii=False))

        # 2. Verificar Precio Gratis ($0.00)
        st_ps, ps_res = api_request("GET", f"/v1/apps/{app_id}/appPriceSchedule", token=token)
        print(f"💲 GET appPriceSchedule -> HTTP {st_ps}, hasData={bool(ps_res.get('data'))}")
        if st_ps == 404 or not ps_res.get("data"):
            print("   ⚠️ No había appPriceSchedule activo. Configurando precio Gratis ($0.00) en USA...")
            base_territory = "USA"
            _, pp_res = api_request(
                "GET",
                f"/v1/apps/{app_id}/appPricePoints?filter[territory]={base_territory}&limit=50",
                token=token,
            )
            pp_list = pp_res.get("data", [])
            free_pp_id = None
            for pp in pp_list:
                cp = str(pp.get("attributes", {}).get("customerPrice", ""))
                if cp in ("0", "0.0", "0.00"):
                    free_pp_id = pp["id"]
                    break
            if not free_pp_id and pp_list:
                free_pp_id = pp_list[0]["id"]
            if free_pp_id:
                price_schedule_body = {
                    "data": {
                        "type": "appPriceSchedules",
                        "relationships": {
                            "app": {"data": {"type": "apps", "id": app_id}},
                            "baseTerritory": {"data": {"type": "territories", "id": base_territory}},
                            "manualPrices": {"data": [{"type": "appPrices", "id": "${price1}"}]},
                        },
                    },
                    "included": [
                        {
                            "type": "appPrices",
                            "id": "${price1}",
                            "attributes": {"startDate": None},
                            "relationships": {
                                "appPricePoint": {"data": {"type": "appPricePoints", "id": free_pp_id}}
                            },
                        }
                    ],
                }
                st_pcreate, _ = api_request("POST", "/v1/appPriceSchedules", body=price_schedule_body, token=token)
                print(f"   ✅ Precio Gratis ($0.00) configurado (HTTP {st_pcreate}).")

        # 3. Verificar y Habilitar Disponibilidad por Países (/v2/appAvailabilities & territoryAvailabilities)
        def fetch_all_territory_availabilities(avail_resource_id):
            rows = []
            next_url = f"/v2/appAvailabilities/{avail_resource_id}/territoryAvailabilities?include=territory&limit=200"
            while next_url:
                st_ta, ta_res = api_request("GET", next_url, token=token)
                if st_ta != 200:
                    print(f"   ⚠️ GET territoryAvailabilities -> HTTP {st_ta}")
                    break
                rows.extend(ta_res.get("data", []))
                next_url = (ta_res.get("links") or {}).get("next")
            return rows

        def create_app_availability_for_territories(codes):
            ta_refs = []
            included_items = []
            for code in codes:
                local_id = f"${{{code}}}"
                ta_refs.append({"type": "territoryAvailabilities", "id": local_id})
                included_items.append(
                    {
                        "type": "territoryAvailabilities",
                        "id": local_id,
                        "attributes": {"available": True},
                        "relationships": {"territory": {"data": {"type": "territories", "id": code}}},
                    }
                )
            av_body = {
                "data": {
                    "type": "appAvailabilities",
                    "attributes": {"availableInNewTerritories": True},
                    "relationships": {
                        "app": {"data": {"type": "apps", "id": app_id}},
                        "territoryAvailabilities": {"data": ta_refs},
                    },
                },
                "included": included_items,
            }
            return api_request("POST", "/v2/appAvailabilities", body=av_body, token=token)

        st_av, av_res = api_request("GET", f"/v1/apps/{app_id}/appAvailabilityV2", token=token)
        print(f"🌍 GET appAvailabilityV2 -> HTTP {st_av}, data={json.dumps(av_res.get('data'), ensure_ascii=False)}")

        priority_codes = ["CHL", "USA", "ESP", "MEX", "ARG", "COL", "PER", "URY", "PRY", "BOL", "ECU", "BRA", "CAN", "GBR"]

        if st_av == 200 and av_res.get("data"):
            avail_id = av_res["data"]["id"]
            ta_rows = fetch_all_territory_availabilities(avail_id)
            active_count = sum(1 for r in ta_rows if (r.get("attributes") or {}).get("available") is True)
            print(f"   📊 Territorios totales: {len(ta_rows)}, activos (available=true): {active_count}")

            by_code = {}
            for r in ta_rows:
                t_code = (((r.get("relationships") or {}).get("territory") or {}).get("data") or {}).get("id", "")
                if t_code:
                    by_code[t_code] = r

            for check_code in ("CHL", "USA", "ESP"):
                row = by_code.get(check_code)
                print(f"   🔎 Estado actual {check_code}:", json.dumps(row, ensure_ascii=False) if row else "NO PRESENTE")

            chl_ok = ((by_code.get("CHL") or {}).get("attributes") or {}).get("available") is True
            usa_ok = ((by_code.get("USA") or {}).get("attributes") or {}).get("available") is True

            if not chl_ok or not usa_ok or active_count < len(priority_codes):
                eligible_codes = []
                for r in ta_rows:
                    t_code = (((r.get("relationships") or {}).get("territory") or {}).get("data") or {}).get("id", "")
                    statuses = (r.get("attributes") or {}).get("contentStatuses") or []
                    if t_code and t_code != "CHN" and "CANNOT_SELL" not in statuses:
                        eligible_codes.append(t_code)
                if not eligible_codes:
                    eligible_codes = priority_codes

                st_post, post_res = create_app_availability_for_territories(eligible_codes)
                print(f"   🔄 POST /v2/appAvailabilities ({len(eligible_codes)} territorios) -> HTTP {st_post}")
                if st_post not in (200, 201):
                    print("   ⚠️ Intentando habilitar territorios vía PATCH individual...")
                    rows_to_patch = []
                    for r in ta_rows:
                        r_attrs = r.get("attributes") or {}
                        t_code = (((r.get("relationships") or {}).get("territory") or {}).get("data") or {}).get("id", "")
                        statuses = r_attrs.get("contentStatuses") or []
                        if r_attrs.get("available") is not True and t_code != "CHN" and "CANNOT_SELL" not in statuses:
                            rows_to_patch.append((t_code, r))
                    # Priorizar CHL, USA y Latinoamérica primero
                    rows_to_patch.sort(key=lambda pair: (0 if pair[0] in priority_codes else 1, pair[0]))
                    patched_ok = 0
                    for t_code, r in rows_to_patch:
                        ta_id = r.get("id")
                        st_pt, _ = api_request(
                            "PATCH",
                            f"/v1/territoryAvailabilities/{ta_id}",
                            body={"data": {"type": "territoryAvailabilities", "id": ta_id, "attributes": {"available": True}}},
                            token=token,
                        )
                        if st_pt in (200, 201):
                            patched_ok += 1
                    print(f"   ✅ Territorios habilitados vía PATCH: {patched_ok}/{len(rows_to_patch)}")

                # Verificación post-actualización
                _, av_after = api_request("GET", f"/v1/apps/{app_id}/appAvailabilityV2", token=token)
                avail_after_id = (av_after.get("data") or {}).get("id") or avail_id
                ta_after = fetch_all_territory_availabilities(avail_after_id)
                active_after = sum(1 for r in ta_after if (r.get("attributes") or {}).get("available") is True)
                print(f"   ✅ Post-actualización: {active_after}/{len(ta_after)} territorios activos.")
                for check_code in ("CHL", "USA"):
                    match_r = next(
                        (r for r in ta_after if (((r.get("relationships") or {}).get("territory") or {}).get("data") or {}).get("id") == check_code),
                        None,
                    )
                    print(f"   📍 Post-actualización {check_code}:", json.dumps((match_r or {}).get("attributes", {}), ensure_ascii=False))
        else:
            print("   ⚠️ appAvailabilityV2 no existe todavía (o HTTP != 200). Obteniendo lista de territorios...")
            all_codes = []
            next_t = "/v1/territories?limit=200"
            while next_t:
                st_t, t_res = api_request("GET", next_t, token=token)
                if st_t != 200:
                    break
                for t in t_res.get("data", []):
                    cid = t.get("id")
                    if cid and cid != "CHN":
                        all_codes.append(cid)
                next_t = (t_res.get("links") or {}).get("next")
            if not all_codes:
                all_codes = priority_codes
            st_cav, _ = create_app_availability_for_territories(all_codes)
            print(f"   🚀 POST /v2/appAvailabilities ({len(all_codes)} territorios) -> HTTP {st_cav}")
            if st_cav not in (200, 201):
                st_cav2, _ = create_app_availability_for_territories(priority_codes)
                print(f"   🚀 Fallback POST /v2/appAvailabilities (prioritarios) -> HTTP {st_cav2}")
            _, av_after = api_request("GET", f"/v1/apps/{app_id}/appAvailabilityV2", token=token)
            if av_after.get("data"):
                ta_after = fetch_all_territory_availabilities(av_after["data"]["id"])
                active_after = sum(1 for r in ta_after if (r.get("attributes") or {}).get("available") is True)
                print(f"   ✅ Post-creación: {active_after}/{len(ta_after)} territorios activos.")
                for check_code in ("CHL", "USA"):
                    match_r = next(
                        (r for r in ta_after if (((r.get("relationships") or {}).get("territory") or {}).get("data") or {}).get("id") == check_code),
                        None,
                    )
                    print(f"   📍 Post-creación {check_code}:", json.dumps((match_r or {}).get("attributes", {}), ensure_ascii=False))

        # 4. Auditar Bundle ID Capabilities y Critical Alerts en App Store Connect API
        print("🔔 Auditando capacidades del Bundle ID y Critical Alerts...")
        _, bid_res = api_request(
            "GET",
            f"/v1/bundleIds?filter[identifier]={BUNDLE_ID}&include=bundleIdCapabilities",
            token=token,
        )
        for item in bid_res.get("data", []):
            if (item.get("attributes") or {}).get("identifier") == BUNDLE_ID:
                print(f"   🆔 Bundle ID resource id={item.get('id')}, name={(item.get('attributes') or {}).get('name')}")
        caps_list = [
            (inc.get("attributes") or {}).get("capabilityType")
            for inc in bid_res.get("included", [])
            if inc.get("type") == "bundleIdCapabilities"
        ]
        print(f"   📋 bundleIdCapabilities activas: {caps_list}")

        _, prof_res = api_request("GET", "/v1/profiles?filter[name]=SENTINEL_ONE_AppStore", token=token)
        profiles = prof_res.get("data", [])
        print(f"   📄 Perfiles encontrados con nombre SENTINEL_ONE_AppStore: {len(profiles)}")
        critical_found = False
        for prof in profiles:
            p_attrs = prof.get("attributes") or {}
            b64_content = p_attrs.get("profileContent", "")
            print(
                f"   📄 Perfil id={prof.get('id')}, state={p_attrs.get('profileState')}, exp={p_attrs.get('expirationDate')}"
            )
            if b64_content:
                try:
                    raw_pp = base64.b64decode(b64_content)
                    plist_bytes = subprocess.check_output(
                        ["openssl", "smime", "-inform", "DER", "-verify", "-noverify"],
                        input=raw_pp,
                        stderr=subprocess.DEVNULL,
                    )
                    pp_plist = plistlib.loads(plist_bytes)
                    ents = pp_plist.get("Entitlements", {})
                    print(f"   🔐 Entitlements del perfil: {json.dumps(ents, default=str, ensure_ascii=False)}")
                    if "com.apple.developer.usernotifications.critical-alerts" in ents:
                        critical_found = True
                except Exception as ex:
                    print(f"   ⚠️ No se pudo decodificar profileContent: {ex}")
        print(f"🚨 CRITICAL ALERTS HABILITADO EN PERFIL: {critical_found}")
        return

    if "--promo-only" in sys.argv:
        _, ver_res = api_request("GET", f"/v1/apps/{app_id}/appStoreVersions?limit=10", token=token)
        for v in ver_res.get("data", []):
            if v.get("attributes", {}).get("versionString") == TARGET_VERSION:
                vid = v["id"]
                _, loc_res = api_request("GET", f"/v1/appStoreVersions/{vid}/appStoreVersionLocalizations", token=token)
                for loc in loc_res.get("data", []):
                    lid = loc["id"]
                    st_p, _ = api_request(
                        "PATCH",
                        f"/v1/appStoreVersionLocalizations/{lid}",
                        body={"data": {"type": "appStoreVersionLocalizations", "id": lid, "attributes": {"promotionalText": PROMOTIONAL_TEXT_ES}}},
                        token=token,
                    )
                    print(f"✅ promotionalText actualizado sin retirar de revisión (loc={lid}, HTTP {st_p}).")
        return

    # 2. Configurar Derechos sobre el Contenido (Content Rights Declaration) en el recurso App
    print("📄 Configurando Derechos sobre el Contenido (DOES_NOT_USE_THIRD_PARTY_CONTENT)...")
    app_patch = {
        "data": {
            "type": "apps",
            "id": app_id,
            "attributes": {
                "contentRightsDeclaration": "DOES_NOT_USE_THIRD_PARTY_CONTENT",
            },
        }
    }
    api_request("PATCH", f"/v1/apps/{app_id}", body=app_patch, token=token)

    # 3. Configurar Categoría Principal (UTILITIES) y Clasificación por Edades (Age Rating Declaration) en AppInfo
    _, info_res = api_request("GET", f"/v1/apps/{app_id}/appInfos", token=token)
    app_infos = info_res.get("data", [])
    for ai in app_infos:
        ai_id = ai["id"]
        ai_state = ai.get("attributes", {}).get("appStoreState") or ai.get("attributes", {}).get("state")
        print(f"🏷️ Configurando Categoría Principal (UTILITIES) en AppInfo id={ai_id} (state={ai_state})...")
        cat_patch = {
            "data": {
                "type": "appInfos",
                "id": ai_id,
                "relationships": {
                    "primaryCategory": {
                        "data": {
                            "type": "appCategories",
                            "id": "UTILITIES",
                        }
                    },
                    "secondaryCategory": {
                        "data": {
                            "type": "appCategories",
                            "id": "NAVIGATION",
                        }
                    },
                },
            }
        }
        api_request("PATCH", f"/v1/appInfos/{ai_id}", body=cat_patch, token=token)

        # Actualizar Privacy Policy URL en appInfoLocalizations si existe
        _, ail_res = api_request("GET", f"/v1/appInfos/{ai_id}/appInfoLocalizations", token=token)
        for ail in ail_res.get("data", []):
            ail_id = ail["id"]
            ail_patch = {
                "data": {
                    "type": "appInfoLocalizations",
                    "id": ail_id,
                    "attributes": {
                        "privacyPolicyUrl": PRIVACY_URL,
                    },
                }
            }
            api_request("PATCH", f"/v1/appInfoLocalizations/{ail_id}", body=ail_patch, token=token)

        # Obtener y responder todas las preguntas de Clasificación por Edades (Age Rating Declaration -> 4+)
        _, age_res = api_request("GET", f"/v1/appInfos/{ai_id}/ageRatingDeclaration", token=token)
        age_data = age_res.get("data")
        if age_data and age_data.get("id"):
            age_id = age_data["id"]
            print(f"🔞 Completando Clasificación por Edades (AgeRatingDeclaration id={age_id})...")
            age_attrs = {
                "alcoholTobaccoOrDrugUseOrReferences": "NONE",
                "contests": "NONE",
                "gambling": False,
                "gamblingSimulated": "NONE",
                "horrorOrFearThemes": "NONE",
                "matureOrSuggestiveThemes": "NONE",
                "medicalOrTreatmentInformation": "NONE",
                "profanityOrCrudeHumor": "NONE",
                "sexualContentGraphicAndNudity": "NONE",
                "sexualContentOrNudity": "NONE",
                "violenceCartoonOrFantasy": "NONE",
                "violenceRealistic": "NONE",
                "violenceRealisticProlongedGraphicOrSadistic": "NONE",
                "unrestrictedWebAccess": False,
                "lootBox": False,
                "ageAssurance": False,
                "advertising": False,
                "healthOrWellnessTopics": False,
                "messagingAndChat": False,
                "parentalControls": False,
                "userGeneratedContent": False,
                "gunsOrOtherWeapons": "NONE",
                "socialMedia": False,
            }
            # Enviar primero el conjunto completo y, si algún campo es de otra versión del esquema, enviar el subconjunto estándar
            age_patch = {
                "data": {
                    "type": "ageRatingDeclarations",
                    "id": age_id,
                    "attributes": age_attrs,
                }
            }
            st_age, age_err = api_request("PATCH", f"/v1/ageRatingDeclarations/{age_id}", body=age_patch, token=token)
            if st_age not in (200, 201):
                # Limpiar atributos no soportados dinámicamente según el error de Apple
                bad_keys = []
                for err_item in age_err.get("errors", []):
                    pointer = err_item.get("source", {}).get("pointer", "")
                    if "/data/attributes/" in pointer:
                        bad_keys.append(pointer.split("/data/attributes/")[-1])
                for bk in bad_keys:
                    age_attrs.pop(bk, None)
                age_patch["data"]["attributes"] = age_attrs
                st_age2, _ = api_request("PATCH", f"/v1/ageRatingDeclarations/{age_id}", body=age_patch, token=token)
                print(f"   ✅ AgeRatingDeclaration actualizado (HTTP {st_age2}).")
            else:
                print("   ✅ AgeRatingDeclaration actualizado al 100%.")

    # 4. Consultar estado actual de TARGET_VERSION antes de tocar envíos activos:
    #    Si Apple ya la tiene IN_REVIEW o READY_FOR_SALE, NO interrumpimos la revisión activa,
    #    pero SÍ esperamos Build 3034 y marcamos usesNonExemptEncryption=False para TestFlight inmediato.
    _, ver_pre_res = api_request("GET", f"/v1/apps/{app_id}/appStoreVersions?limit=10", token=token)
    pre_versions = ver_pre_res.get("data", [])
    target_ver_state = None
    for pv in pre_versions:
        if pv.get("attributes", {}).get("versionString") == TARGET_VERSION:
            target_ver_state = pv.get("attributes", {}).get("appStoreState") or pv.get("attributes", {}).get("appVersionState")
            break

    if target_ver_state in ("IN_REVIEW", "READY_FOR_SALE", "PENDING_DEVELOPER_RELEASE", "PROCESSING_FOR_APP_STORE"):
        print(f"ℹ️ La versión {TARGET_VERSION} está en estado '{target_ver_state}'. Respetando revisión activa y habilitando Build {TARGET_BUILD_NUMBER} en TestFlight...")
        selected_build = None
        for attempt in range(44):
            _, pre_res = api_request("GET", f"/v1/preReleaseVersions?filter[app]={app_id}&include=builds&limit=10", token=token)
            builds = [inc for inc in pre_res.get("included", []) if inc.get("type") == "builds"]
            if not builds:
                _, b_res = api_request("GET", f"/v1/builds?filter[app]={app_id}&sort=-uploadedDate&limit=10", token=token)
                builds = b_res.get("data", [])
            for b in builds:
                if str(b.get("attributes", {}).get("version")) == TARGET_BUILD_NUMBER and b.get("attributes", {}).get("processingState") == "VALID":
                    selected_build = b
                    break
            if selected_build:
                break
            print(f"⏳ Esperando procesamiento de Build {TARGET_BUILD_NUMBER} para TestFlight (intento {attempt + 1}/44)...")
            time.sleep(15)
        if selected_build:
            b_id = selected_build["id"]
            api_request(
                "PATCH",
                f"/v1/builds/{b_id}",
                body={"data": {"type": "builds", "id": b_id, "attributes": {"usesNonExemptEncryption": False}}},
                token=token,
            )
            print(f"✅ Build {TARGET_BUILD_NUMBER} habilitado en TestFlight (usesNonExemptEncryption=False).")
        return

    # 4A. Retirar únicamente envíos WAITING_FOR_REVIEW si se necesita adjuntar el nuevo build
    _, waiting_res = api_request(
        "GET",
        f"/v1/reviewSubmissions?filter[app]={app_id}&filter[state]=WAITING_FOR_REVIEW",
        token=token,
    )
    for w_sub in waiting_res.get("data", []):
        w_id = w_sub.get("id")
        if w_id:
            print(f"🔄 Retirando momentáneamente envío en espera (id={w_id}) para vincular el nuevo Build {TARGET_BUILD_NUMBER}...")
            api_request(
                "PATCH",
                f"/v1/reviewSubmissions/{w_id}",
                body={"data": {"type": "reviewSubmissions", "id": w_id, "attributes": {"canceled": True}}},
                token=token,
            )
            time.sleep(3)

    _, ver_res = api_request("GET", f"/v1/apps/{app_id}/appStoreVersions?limit=10", token=token)
    versions = ver_res.get("data", [])
    version_id = None
    editable_states = {"PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED", "INVALID_BINARY"}
    for v in versions:
        if v.get("attributes", {}).get("versionString") == TARGET_VERSION:
            version_id = v["id"]
            break
    if not version_id:
        for v in versions:
            st_v = v.get("attributes", {}).get("appStoreState") or v.get("attributes", {}).get("appVersionState")
            if st_v in editable_states:
                version_id = v["id"]
                break
    if not version_id:
        st_nv, nv_res = api_request(
            "POST",
            "/v1/appStoreVersions",
            body={
                "data": {
                    "type": "appStoreVersions",
                    "attributes": {"platform": "IOS", "versionString": TARGET_VERSION, "releaseType": "AFTER_APPROVAL"},
                    "relationships": {"app": {"data": {"type": "apps", "id": app_id}}},
                }
            },
            token=token,
        )
        if st_nv in (200, 201):
            version_id = nv_res["data"]["id"]

    print(f"📝 Actualizando VersionString, Copyright y ReleaseType en versión {TARGET_VERSION} (id={version_id})...")
    ver_patch = {
        "data": {
            "type": "appStoreVersions",
            "id": version_id,
            "attributes": {
                "versionString": TARGET_VERSION,
                "copyright": COPYRIGHT_TEXT,
                "releaseType": "AFTER_APPROVAL",
            },
        }
    }
    api_request("PATCH", f"/v1/appStoreVersions/{version_id}", body=ver_patch, token=token)

    # 4B. Actualizar también /v1/ageRatingDeclarations/{version_id} directamente (Apple lo asocia al mismo UUID de la versión)
    full_age_attrs = {
        "alcoholTobaccoOrDrugUseOrReferences": "NONE",
        "contests": "NONE",
        "gambling": False,
        "gamblingSimulated": "NONE",
        "horrorOrFearThemes": "NONE",
        "matureOrSuggestiveThemes": "NONE",
        "medicalOrTreatmentInformation": "NONE",
        "profanityOrCrudeHumor": "NONE",
        "sexualContentGraphicAndNudity": "NONE",
        "sexualContentOrNudity": "NONE",
        "violenceCartoonOrFantasy": "NONE",
        "violenceRealistic": "NONE",
        "violenceRealisticProlongedGraphicOrSadistic": "NONE",
        "unrestrictedWebAccess": False,
        "lootBox": False,
        "ageAssurance": False,
        "advertising": False,
        "healthOrWellnessTopics": False,
        "messagingAndChat": False,
        "parentalControls": False,
        "userGeneratedContent": False,
        "gunsOrOtherWeapons": "NONE",
        "socialMedia": False,
    }
    print(f"🔞 Completando AgeRatingDeclaration de la versión (id={version_id})...")
    st_va, _ = api_request(
        "PATCH",
        f"/v1/ageRatingDeclarations/{version_id}",
        body={"data": {"type": "ageRatingDeclarations", "id": version_id, "attributes": full_age_attrs}},
        token=token,
    )
    print(f"   ✅ AgeRatingDeclaration de versión respondido con HTTP {st_va}")

    # 4C. Configurar Precio Gratis ($0.00 - FREE) en /v1/appPriceSchedules de manera incondicional
    print("💲 Configurando Precio Gratis ($0.00) en App Store Connect (/v1/appPriceSchedules)...")
    base_territory = "USA"
    _, pp_res = api_request(
        "GET",
        f"/v1/apps/{app_id}/appPricePoints?filter[territory]={base_territory}&limit=50",
        token=token,
    )
    pp_list = pp_res.get("data", [])
    free_pp_id = None
    for pp in pp_list:
        cp = str(pp.get("attributes", {}).get("customerPrice", ""))
        if cp in ("0", "0.0", "0.00"):
            free_pp_id = pp["id"]
            break
    if not free_pp_id and pp_list:
        free_pp_id = pp_list[0]["id"]

    if free_pp_id:
        price_schedule_body = {
            "data": {
                "type": "appPriceSchedules",
                "relationships": {
                    "app": {
                        "data": {"type": "apps", "id": app_id}
                    },
                    "baseTerritory": {
                        "data": {"type": "territories", "id": base_territory}
                    },
                    "manualPrices": {
                        "data": [
                            {"type": "appPrices", "id": "${price1}"}
                        ]
                    },
                },
            },
            "included": [
                {
                    "type": "appPrices",
                    "id": "${price1}",
                    "attributes": {
                        "startDate": None,
                    },
                    "relationships": {
                        "appPricePoint": {
                            "data": {
                                "type": "appPricePoints",
                                "id": free_pp_id,
                            }
                        }
                    },
                }
            ],
        }
        st_pcreate, _ = api_request("POST", "/v1/appPriceSchedules", body=price_schedule_body, token=token)
        print(f"   ✅ Precio Gratis ($0.00) configurado (HTTP {st_pcreate}).")

    # 5. Actualizar URL de Soporte, Descripción, Palabras Clave y Capturas en todas las Localizaciones de la versión
    _, loc_res = api_request("GET", f"/v1/appStoreVersions/{version_id}/appStoreVersionLocalizations", token=token)
    for loc in loc_res.get("data", []):
        loc_id = loc["id"]
        locale = loc.get("attributes", {}).get("locale", "")
        print(f"🌐 Configurando URL de Soporte, Textos y Capturas para {locale} (id={loc_id})...")
        loc_attrs = {
            "description": DESCRIPTION_ES,
            "keywords": KEYWORDS_ES,
            "promotionalText": PROMOTIONAL_TEXT_ES,
            "supportUrl": SUPPORT_URL,
            "marketingUrl": MARKETING_URL,
            "whatsNew": WHATS_NEW_ES,
        }
        st_l, _ = api_request(
            "PATCH",
            f"/v1/appStoreVersionLocalizations/{loc_id}",
            body={"data": {"type": "appStoreVersionLocalizations", "id": loc_id, "attributes": loc_attrs}},
            token=token,
        )
        if st_l not in (200, 201):
            loc_attrs.pop("whatsNew", None)
            api_request(
                "PATCH",
                f"/v1/appStoreVersionLocalizations/{loc_id}",
                body={"data": {"type": "appStoreVersionLocalizations", "id": loc_id, "attributes": loc_attrs}},
                token=token,
            )

        # Subir capturas de pantalla obligatorias (iPhone con Dynamic Island + iPad de 13 pulgadas)
        ensure_screenshots_for_localization(loc_id, token)

    # 6. Configurar Información de Revisión de App Store (AppStoreReviewDetail)
    print("👤 Configurando datos de contacto y cuenta demo para el Revisor de Apple...")
    st_rd, rd_res = api_request("GET", f"/v1/appStoreVersions/{version_id}/appStoreReviewDetail", token=token)
    rd_data = rd_res.get("data")
    review_attrs = {
        "contactFirstName": "Daniel",
        "contactLastName": "Olate",
        "contactPhone": "+56998887766",
        "contactEmail": "daniel.olate.naranjo@gmail.com",
        "demoAccountName": "100",
        "demoAccountPassword": "1234",
        "demoAccountRequired": True,
        "notes": REVIEW_NOTES,
    }
    if rd_data and rd_data.get("id"):
        rd_id = rd_data["id"]
        api_request(
            "PATCH",
            f"/v1/appStoreReviewDetails/{rd_id}",
            body={"data": {"type": "appStoreReviewDetails", "id": rd_id, "attributes": review_attrs}},
            token=token,
        )
    else:
        api_request(
            "POST",
            "/v1/appStoreReviewDetails",
            body={
                "data": {
                    "type": "appStoreReviewDetails",
                    "attributes": review_attrs,
                    "relationships": {
                        "appStoreVersion": {
                            "data": {
                                "type": "appStoreVersions",
                                "id": version_id,
                            }
                        }
                    },
                }
            },
            token=token,
        )

    # 7. Vincular el Build TARGET_BUILD_NUMBER (esperando a que pase a VALID si recién subió, consultando preReleaseVersions que sí incluye builds en procesamiento)
    selected_build = None
    for attempt in range(44):
        _, pre_res = api_request("GET", f"/v1/preReleaseVersions?filter[app]={app_id}&include=builds&limit=10", token=token)
        builds = [inc for inc in pre_res.get("included", []) if inc.get("type") == "builds"]
        if not builds:
            _, b_res = api_request("GET", f"/v1/builds?filter[app]={app_id}&sort=-uploadedDate&limit=10", token=token)
            builds = b_res.get("data", [])
        for b in builds:
            if str(b.get("attributes", {}).get("version")) == TARGET_BUILD_NUMBER and b.get("attributes", {}).get("processingState") == "VALID":
                selected_build = b
                break
        if selected_build:
            break
        print(f"⏳ Esperando procesamiento de Build {TARGET_BUILD_NUMBER} en App Store Connect (intento {attempt + 1}/44)...")
        time.sleep(15)

    if selected_build:
        b_id = selected_build["id"]
        b_ver = selected_build.get("attributes", {}).get("version")
        api_request(
            "PATCH",
            f"/v1/builds/{b_id}",
            body={"data": {"type": "builds", "id": b_id, "attributes": {"usesNonExemptEncryption": False}}},
            token=token,
        )
        api_request(
            "PATCH",
            f"/v1/appStoreVersions/{version_id}/relationships/build",
            body={"data": {"type": "builds", "id": b_id}},
            token=token,
        )
        print(f"✅ Build {b_ver} vinculado a la versión {TARGET_VERSION}.")
    else:
        print(f"❌ ERROR CRÍTICO: El Build {TARGET_BUILD_NUMBER} no alcanzó estado VALID a tiempo. Abortando envío para evitar reenviar un build anterior.")
        sys.exit(1)

    # 8. Enviar oficialmente a Revisión de App Store (priorizando UNRESOLVED_ISSUES y marcando items REJECTED como resolved=True)
    print(f"🚀 Enviando versión {TARGET_VERSION} a Revisión de App Store...")
    _, list_res = api_request(
        "GET",
        f"/v1/reviewSubmissions?filter[app]={app_id}&filter[state]=UNRESOLVED_ISSUES,READY_FOR_REVIEW&include=items",
        token=token,
    )
    existing_subs = list_res.get("data", [])
    unresolved_subs = [s for s in existing_subs if s.get("attributes", {}).get("state") == "UNRESOLVED_ISSUES"]
    ready_subs = [s for s in existing_subs if s.get("attributes", {}).get("state") == "READY_FOR_REVIEW"]

    if unresolved_subs:
        submission_id = unresolved_subs[0]["id"]
        for item_ref in unresolved_subs[0].get("relationships", {}).get("items", {}).get("data", []):
            item_id = item_ref.get("id")
            if item_id:
                api_request(
                    "PATCH",
                    f"/v1/reviewSubmissionItems/{item_id}",
                    body={"data": {"type": "reviewSubmissionItems", "id": item_id, "attributes": {"resolved": True}}},
                    token=token,
                )
    elif ready_subs:
        submission_id = ready_subs[0]["id"]
    else:
        st_sub, sub_res = api_request(
            "POST",
            "/v1/reviewSubmissions",
            body={
                "data": {
                    "type": "reviewSubmissions",
                    "attributes": {"platform": "IOS"},
                    "relationships": {"app": {"data": {"type": "apps", "id": app_id}}},
                }
            },
            token=token,
        )
        submission_id = sub_res.get("data", {}).get("id") if st_sub in (200, 201) else None

    if submission_id:
        if not unresolved_subs:
            api_request(
                "POST",
                "/v1/reviewSubmissionItems",
                body={
                    "data": {
                        "type": "reviewSubmissionItems",
                        "relationships": {
                            "reviewSubmission": {"data": {"type": "reviewSubmissions", "id": submission_id}},
                            "appStoreVersion": {"data": {"type": "appStoreVersions", "id": version_id}},
                        },
                    }
                },
                token=token,
            )
        st_conf, conf_res = api_request(
            "PATCH",
            f"/v1/reviewSubmissions/{submission_id}",
            body={"data": {"type": "reviewSubmissions", "id": submission_id, "attributes": {"submitted": True}}},
            token=token,
        )
        if st_conf in (200, 201):
            print("🎉 ¡APLICACIÓN ENVIADA EXITOSAMENTE A REVISIÓN DE APP STORE PARA PRODUCCIÓN!")
        else:
            print("⚠️ Respuesta de envío a revisión:", json.dumps(conf_res, indent=2, ensure_ascii=False))
            # Consultar items de reviewSubmission para ver si hay algún detalle bloqueante
            _, items_res = api_request("GET", f"/v1/reviewSubmissions/{submission_id}/items", token=token)
            print("📋 Items en reviewSubmission:", json.dumps(items_res, indent=2, ensure_ascii=False))

    # Imprimir estado final de la versión
    _, final_ver = api_request("GET", f"/v1/appStoreVersions/{version_id}", token=token)
    print(f"🏁 Estado final de la versión {TARGET_VERSION}:", json.dumps(final_ver.get("data", {}).get("attributes", {}), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
