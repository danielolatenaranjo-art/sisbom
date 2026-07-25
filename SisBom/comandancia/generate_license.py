import hashlib
import sys
import os

SECRET_SALT = "SisBomPlacillaOHFirefighterSecurityKey"

def generate_key(hwid):
    raw = (hwid + SECRET_SALT).encode('utf-8')
    return hashlib.sha256(raw).hexdigest()

def main():
    print("========================================")
    print("Generador de Licencias - SisBom Comandancia")
    print("========================================")
    
    if len(sys.argv) > 1:
        hwid = sys.argv[1].strip()
    else:
        hwid = input("Ingrese el ID de Hardware (HWID) del equipo: ").strip()
        
    if not hwid:
        print("Error: El ID de Hardware no puede estar vacío.")
        sys.exit(1)
        
    key = generate_key(hwid)
    script_dir = os.path.dirname(os.path.abspath(__file__))
    filename = os.path.join(script_dir, "licencia.key")
    
    try:
        with open(filename, "w", encoding="utf-8") as f:
            f.write(key)
        print(f"\nLicencia generada con éxito para el HWID: {hwid}")
        print(f"Archivo guardado en: {filename}")
        print("Coloque este archivo en la misma carpeta del ejecutable de Comandancia.")
    except Exception as e:
        print(f"Error al escribir el archivo: {e}")
        print(f"Clave generada (copie manualmente si es necesario):\n{key}")
        
    print("========================================")
    input("\nPresione Enter para salir...")

if __name__ == '__main__':
    main()
