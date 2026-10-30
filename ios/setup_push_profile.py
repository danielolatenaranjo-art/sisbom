#!/usr/bin/env python3
"""
Habilita PUSH_NOTIFICATIONS en App Store Connect API para com.misisbom.sisbom.NFR3L3CC3L,
regenera/descarga un perfil IOS_APP_STORE actualizado con aps-environment=production,
y si el perfil instalado contiene aps-environment, activa SisBom.entitlements en project.yml.
"""
import os
import sys
import time
import json
import base64
import plistlib
import subprocess
import urllib.request
import urllib.error
import jwt

KEY_ID = os.environ.get("APPSTORE_KEY_ID", "").strip()
ISSUER_ID = os.environ.get("APPSTORE_ISSUER_ID", "").strip()
PRIVATE_KEY = os.environ.get("APPSTORE_PRIVATE_KEY", "").strip()
BUNDLE_ID = "com.misisbom.sisbom.NFR3L3CC3L"
PROFILE_NAME = "SENTINEL_ONE_AppStore"


def generate_token():
    if not KEY_ID or not ISSUER_ID or not PRIVATE_KEY:
        return None
    now = int(time.time())
    payload = {
        "iss": ISSUER_ID,
        "iat": now - 30,
        "exp": now + 1100,
        "aud": "appstoreconnect-v1",
    }
    headers = {"alg": "ES256", "kid": KEY_ID, "typ": "JWT"}
    return jwt.encode(payload, PRIVATE_KEY, algorithm="ES256", headers=headers)


def api_request(method, path, body=None, token=None):
    url = f"https://api.appstoreconnect.apple.com{path}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        err_raw = e.read().decode("utf-8", errors="ignore")
        print(f"  [API {method} {path}] HTTP {e.code}: {err_raw[:300]}")
        try:
            return e.code, json.loads(err_raw)
        except Exception:
            return e.code, {}
    except Exception as ex:
        print(f"  [API {method} {path}] Error: {ex}")
        return 500, {}


def inspect_mobileprovision_entitlements(pp_path):
    try:
        out = subprocess.check_output(["security", "cms", "-D", "-i", pp_path])
        plist = plistlib.loads(out)
        ent = plist.get("Entitlements", {})
        uuid = plist.get("UUID", "")
        name = plist.get("Name", "")
        return uuid, name, ent
    except Exception as e:
        print(f"  No se pudo inspeccionar {pp_path}: {e}")
        return "", "", {}


def main():
    ios_dir = os.path.dirname(os.path.abspath(__file__))
    runner_temp = os.environ.get("RUNNER_TEMP", "/tmp")
    pp_path = os.path.join(runner_temp, "build_pp.mobileprovision")
    profiles_dir = os.path.expanduser("~/Library/MobileDevice/Provisioning Profiles")
    os.makedirs(profiles_dir, exist_ok=True)

    token = generate_token()
    if token:
        print("1. Consultando Bundle ID en App Store Connect API...")
        st, res = api_request("GET", f"/v1/bundleIds?filter[identifier]={BUNDLE_ID}", token=token)
        items = res.get("data", [])
        bundle_res_id = None
        for it in items:
            if it.get("attributes", {}).get("identifier") == BUNDLE_ID:
                bundle_res_id = it.get("id")
                break

        if bundle_res_id:
            print(f"   Bundle Resource ID encontrado: {bundle_res_id}")
            # Habilitar PUSH_NOTIFICATIONS
            cap_body = {
                "data": {
                    "type": "bundleIdCapabilities",
                    "attributes": {
                        "capabilityType": "PUSH_NOTIFICATIONS",
                        "settings": []
                    },
                    "relationships": {
                        "bundleId": {
                            "data": {"type": "bundleIds", "id": bundle_res_id}
                        }
                    }
                }
            }
            st_cap, _ = api_request("POST", "/v1/bundleIdCapabilities", body=cap_body, token=token)
            if st_cap in (200, 201, 409):
                print("   Capacidad PUSH_NOTIFICATIONS activa en el App ID.")

            # Buscar certificados de distribución activos
            st_cert, res_cert = api_request("GET", "/v1/certificates?filter[certificateType]=DISTRIBUTION,IOS_DISTRIBUTION", token=token)
            cert_ids = [c["id"] for c in res_cert.get("data", []) if c.get("id")]
            if not cert_ids:
                st_cert2, res_cert2 = api_request("GET", "/v1/certificates", token=token)
                cert_ids = [
                    c["id"] for c in res_cert2.get("data", [])
                    if "DISTRIBUTION" in c.get("attributes", {}).get("certificateType", "")
                ]

            if cert_ids:
                print(f"   Certificados Distribution encontrados: {cert_ids}")
                # Buscar perfiles existentes con nombre SENTINEL_ONE_AppStore y eliminarlos para recrearlo con Push
                st_prof, res_prof = api_request("GET", f"/v1/profiles?filter[name]={PROFILE_NAME}", token=token)
                for old_p in res_prof.get("data", []):
                    old_id = old_p.get("id")
                    if old_id:
                        api_request("DELETE", f"/v1/profiles/{old_id}", token=token)

                create_prof_body = {
                    "data": {
                        "type": "profiles",
                        "attributes": {
                            "name": PROFILE_NAME,
                            "profileType": "IOS_APP_STORE"
                        },
                        "relationships": {
                            "bundleId": {
                                "data": {"type": "bundleIds", "id": bundle_res_id}
                            },
                            "certificates": {
                                "data": [{"type": "certificates", "id": cid} for cid in cert_ids]
                            }
                        }
                    }
                }
                st_new, res_new = api_request("POST", "/v1/profiles", body=create_prof_body, token=token)
                content_b64 = res_new.get("data", {}).get("attributes", {}).get("profileContent", "")
                if st_new in (200, 201) and content_b64:
                    raw_pp = base64.b64decode(content_b64)
                    with open(pp_path, "wb") as f:
                        f.write(raw_pp)
                    print("   Nuevo perfil IOS_APP_STORE con Push Notifications descargado exitosamente.")

    if not os.path.exists(pp_path):
        print("No se encontró build_pp.mobileprovision.")
        return

    uuid, name, entitlements = inspect_mobileprovision_entitlements(pp_path)
    print(f"Perfil activo: Name='{name}', UUID='{uuid}', Entitlements={list(entitlements.keys())}")
    if uuid:
        dest = os.path.join(profiles_dir, f"{uuid}.mobileprovision")
        with open(pp_path, "rb") as src_f, open(dest, "wb") as dst_f:
            dst_f.write(src_f.read())

    # Solo activar SisBom.entitlements si el perfil de aprovisionamiento realmente incluye aps-environment
    if "aps-environment" in entitlements:
        print("✅ El perfil incluye 'aps-environment'. Configurando SisBom.entitlements...")
        ent_dict = {
            "aps-environment": entitlements.get("aps-environment", "production")
        }
        if "com.apple.developer.usernotifications.time-sensitive" in entitlements:
            ent_dict["com.apple.developer.usernotifications.time-sensitive"] = True

        ent_path = os.path.join(ios_dir, "SisBom.entitlements")
        with open(ent_path, "wb") as f:
            plistlib.dump(ent_dict, f)

        proj_path = os.path.join(ios_dir, "project.yml")
        with open(proj_path, "r", encoding="utf-8") as f:
            proj_txt = f.read()
        if "CODE_SIGN_ENTITLEMENTS" not in proj_txt:
            proj_txt = proj_txt.replace(
                'PROVISIONING_PROFILE_SPECIFIER: "SENTINEL_ONE_AppStore"',
                'PROVISIONING_PROFILE_SPECIFIER: "SENTINEL_ONE_AppStore"\n        CODE_SIGN_ENTITLEMENTS: SisBom.entitlements'
            )
            with open(proj_path, "w", encoding="utf-8") as f:
                f.write(proj_txt)
    else:
        print("ℹ️ El perfil actual no incluye 'aps-environment'; compilando sin CODE_SIGN_ENTITLEMENTS para evitar error de firma.")


if __name__ == "__main__":
    main()
