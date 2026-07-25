import urllib.request
import urllib.error
import json
import subprocess
import uuid
import sys
import os

def get_hwid():
    try:
        output = subprocess.check_output('wmic csproduct get uuid', shell=True).decode().split('\n')[1].strip()
        return output
    except Exception:
        return f"MAC-{uuid.getnode()}"

def check_license(license_key):
    hwid = get_hwid()
    urls = [
        "https://validatelicense-3kkeukidtq-uc.a.run.app",
        "https://us-central1-sisbom-central.cloudfunctions.net/validateLicense"
    ]
    
    payload = {
        "hwid": hwid,
        "licenseKey": license_key
    }
    
    data = json.dumps(payload).encode("utf-8")
    
    for url in urls:
        try:
            req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=10) as response:
                res_data = json.loads(response.read().decode())
                return res_data
        except Exception as e:
            print(f"Intento fallido en {url}: {e}")
            continue
            
    return {"error": "No se pudo contactar al servidor."}

if __name__ == "__main__":
    print("========================================")
    print("   GESTOR DE LICENCIAS SISBOM (SaaS)    ")
    print("========================================")
    print("Tu HWID actual es:", get_hwid())
    print("\nIngresa la NUEVA clave de licencia que deseas asociar a este computador.")
    
    nueva_clave = input("\nClave de Licencia: ").strip()
    
    if not nueva_clave:
        print("Cancelado.")
        sys.exit(0)
        
    print("\nConectando a la nube de SisBom...")
    resultado = check_license(nueva_clave)
    
    if resultado.get("authorized"):
        print("\n[ÉXITO] La licencia ha sido actualizada en la nube.")
        print(f"Módulo autorizado: {resultado.get('module')}")
        print(f"Cliente: {resultado.get('clientName')}")
        
        # Intentar borrar el caché local si existe
        cache_paths = [
            os.path.join(os.getcwd(), 'SisBom', '.licencia_cache'),
            os.path.join(os.getcwd(), '.licencia_cache'),
            os.path.join(os.path.dirname(os.path.abspath(__file__)), '.licencia_cache')
        ]
        
        for cp in cache_paths:
            if os.path.exists(cp):
                try:
                    os.remove(cp)
                    print(f"Caché local eliminado ({cp}).")
                except:
                    pass
    else:
        print("\n[ERROR] El servidor rechazó la licencia:")
        print(resultado)
    
    input("\nPresiona Enter para salir...")
