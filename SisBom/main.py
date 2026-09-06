import http.server
import socketserver
import threading
import mimetypes
import socket
import sys
import os
import urllib.parse
import json
import webview

# Import bundled assets. If not generated, show warning
try:
    from bundled_assets import ASSETS
except ImportError:
    print("Error: bundled_assets.py not found. Please run build.py first.")
    sys.exit(1)

# ── Embedded client logos ────────────────────────────────────────────────────
# Logo de Cuerpo de Bomberos de Placilla (embedded at build time)
# If the file Logo.png is missing and the client matches, this is extracted to disk.
try:
    from embedded_logos import LOGO_PLACILLA_PNG
except ImportError:
    LOGO_PLACILLA_PNG = None

PLACILLA_CLIENT_KEYWORDS = ['placilla']

def extract_embedded_logo_if_needed(client_name):
    """If the client is Placilla and Logo.png is not on disk, write the embedded logo."""
    if LOGO_PLACILLA_PNG is None:
        return
    name_lower = (client_name or '').lower()
    if not any(kw in name_lower for kw in PLACILLA_CLIENT_KEYWORDS):
        return
    logo_path = os.path.join(get_exe_dir(), 'Logo.png')
    if not os.path.isfile(logo_path):
        try:
            with open(logo_path, 'wb') as f:
                f.write(LOGO_PLACILLA_PNG)
            print('Logotipo de Bomberos de Placilla extraído desde el ejecutable.', flush=True)
        except Exception as e:
            print(f'Error extrayendo logotipo embebido: {e}', flush=True)

class InMemoryHTTPRequestHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Suppress console logging to keep execution clean
        pass

    def do_GET(self):
        # Normalize the path
        path = self.path.lstrip('/')
        if not path or path == '/':
            path = 'index.html'
        
        # Strip query parameters or hashes
        path = path.split('?')[0].split('#')[0]
        
        # Convert path encoding (e.g. %20 -> spaces)
        path = urllib.parse.unquote(path)

        # Intercept Logo.png requests to serve the custom logo from root directory if it exists
        if path.endswith('Logo.png'):
            root_logo_path = os.path.join(get_exe_dir(), 'Logo.png')
            if os.path.isfile(root_logo_path):
                self.send_response(200)
                self.send_header('Content-Type', 'image/png')
                self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
                self.send_header('Pragma', 'no-cache')
                self.send_header('Expires', '0')
                with open(root_logo_path, 'rb') as f:
                    content = f.read()
                self.send_header('Content-Length', len(content))
                self.end_headers()
                self.wfile.write(content)
                return

        # Intercept sentinel_logo.png requests to serve the official SENTINEL brand logo
        if path.endswith('sentinel_logo.png'):
            root_sentinel_path = os.path.join(get_exe_dir(), 'sentinel_logo.png')
            if not os.path.isfile(root_sentinel_path):
                root_sentinel_path = os.path.join(get_exe_dir(), 'central', 'sentinel_logo.png')
            if os.path.isfile(root_sentinel_path):
                self.send_response(200)
                self.send_header('Content-Type', 'image/png')
                self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
                self.send_header('Pragma', 'no-cache')
                self.send_header('Expires', '0')
                with open(root_sentinel_path, 'rb') as f:
                    content = f.read()
                self.send_header('Content-Length', len(content))
                self.end_headers()
                self.wfile.write(content)
                return
            if 'central/sentinel_logo.png' in ASSETS:
                self.send_response(200)
                self.send_header('Content-Type', 'image/png')
                self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
                self.send_header('Pragma', 'no-cache')
                self.send_header('Expires', '0')
                content = ASSETS['central/sentinel_logo.png']
                self.send_header('Content-Length', len(content))
                self.end_headers()
                self.wfile.write(content)
                return

        # Check if the file exists physically in the executable directory to allow SaaS branding override
        local_file_path = os.path.join(get_exe_dir(), path)
        if os.path.isfile(local_file_path):
            self.send_response(200)
            
            # Guess MIME type
            if path.endswith('.html'): mime_type = 'text/html'
            elif path.endswith('.css'): mime_type = 'text/css'
            elif path.endswith('.js'): mime_type = 'application/javascript'
            elif path.endswith('.png'): mime_type = 'image/png'
            elif path.endswith('.jpg') or path.endswith('.jpeg'): mime_type = 'image/jpeg'
            elif path.endswith('.svg'): mime_type = 'image/svg+xml'
            elif path.endswith('.ico'): mime_type = 'image/x-icon'
            else:
                mime_type, _ = mimetypes.guess_type(local_file_path)
                if not mime_type:
                    mime_type = 'application/octet-stream'

            self.send_header('Content-Type', mime_type)
            self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
            self.send_header('Pragma', 'no-cache')
            self.send_header('Expires', '0')
            
            with open(local_file_path, 'rb') as f:
                content = f.read()
            self.send_header('Content-Length', len(content))
            self.end_headers()
            self.wfile.write(content)
            return

        if path in ASSETS:
            self.send_response(200)
            
            # Force extension-based MIME type matching to prevent Windows registry misconfigurations
            if path.endswith('.html'):
                mime_type = 'text/html'
            elif path.endswith('.css'):
                mime_type = 'text/css'
            elif path.endswith('.js'):
                mime_type = 'application/javascript'
            elif path.endswith('.png'):
                mime_type = 'image/png'
            elif path.endswith('.jpg') or path.endswith('.jpeg'):
                mime_type = 'image/jpeg'
            elif path.endswith('.svg'):
                mime_type = 'image/svg+xml'
            elif path.endswith('.ico'):
                mime_type = 'image/x-icon'
            else:
                mime_type, _ = mimetypes.guess_type(path)
                if not mime_type:
                    mime_type = 'application/octet-stream'

            self.send_header('Content-Type', mime_type)
            
            # Disable caching to prevent files from being written to browser cache directories on disk
            self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
            self.send_header('Pragma', 'no-cache')
            self.send_header('Expires', '0')
            
            content = ASSETS[path]
            self.send_header('Content-Length', len(content))
            self.end_headers()
            self.wfile.write(content)
        else:
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b"Not Found")

def find_free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(('127.0.0.1', 0))
    port = s.getsockname()[1]
    s.close()
    return port

def start_server(port):
    handler = InMemoryHTTPRequestHandler
    
    # ThreadingTCPServer allows handling concurrent requests
    class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
        allow_reuse_address = True
        
    server = ThreadedTCPServer(('127.0.0.1', port), handler)
    server_thread = threading.Thread(target=server.serve_forever)
    server_thread.daemon = True
    server_thread.start()
    return server

def get_exe_dir():
    if getattr(sys, 'frozen', False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))

is_logout_done = False

class Api:
    # Class-level properties to prevent Windows UI Automation / accessibility recursion loops
    AccessibilityObject = None
    Bounds = None
    Empty = None

    def __init__(self, port):
        self.port = port
        self._window = None

    def check_pdf_exists(self, cycle_year, sequence_num):
        path = os.path.join(get_exe_dir(), "asistencias", str(cycle_year), f"{sequence_num}.pdf")
        return os.path.exists(path)

    def save_list_pdf(self, doc_id, cycle_year, sequence_num, print_html_content):
        try:
            if self.check_pdf_exists(cycle_year, sequence_num):
                return True
            
            dest_dir = os.path.join(get_exe_dir(), "asistencias", str(cycle_year))
            os.makedirs(dest_dir, exist_ok=True)
            
            dest_pdf_path = os.path.normpath(os.path.join(dest_dir, f"{sequence_num}.pdf"))
            temp_html_path = os.path.normpath(os.path.join(get_exe_dir(), "asistencias", f"temp_print_{doc_id}.html"))
            
            # Adjust Logo path so it resolves from the subfolder to root
            print_html_content = print_html_content.replace("'./Logo.png'", "'../Logo.png'").replace('"./Logo.png"', '"../Logo.png"')
            
            with open(temp_html_path, "w", encoding="utf-8") as f:
                f.write(print_html_content)
                
            print(f"SISBOM PDF: Generando borrador temporal HTML en {temp_html_path}", flush=True)
            
            def on_loaded():
                try:
                    import time
                    time.sleep(0.5)
                    
                    webview_control = print_window.native.webview
                    core_webview2 = webview_control.CoreWebView2
                    
                    task = core_webview2.PrintToPdfAsync(dest_pdf_path, None)
                    
                    def poll_task_completion():
                        try:
                            while not task.IsCompleted:
                                time.sleep(0.1)
                            print(f"SISBOM PDF: PDF generado exitosamente en {dest_pdf_path}", flush=True)
                        except Exception as e:
                            print(f"SISBOM PDF ERROR: Error en hilo de impresión: {e}", flush=True)
                        finally:
                            print_window.destroy()
                            try:
                                if os.path.exists(temp_html_path):
                                    os.remove(temp_html_path)
                            except Exception as ex:
                                print(f"SISBOM PDF ERROR: No se pudo eliminar archivo temporal: {ex}", flush=True)
                                
                    t = threading.Thread(target=poll_task_completion)
                    t.daemon = True
                    t.start()
                except Exception as err:
                    print(f"SISBOM PDF ERROR: Error en callback de carga: {err}", flush=True)
                    print_window.destroy()
                    if os.path.exists(temp_html_path):
                        os.remove(temp_html_path)
            
            url = f"file:///{temp_html_path.replace(os.sep, '/')}"
            print_window = webview.create_window('Print Helper', url, hidden=True)
            print_window.events.loaded += on_loaded
            return True
        except Exception as e:
            print(f"SISBOM PDF ERROR: No se pudo iniciar la generación de PDF: {e}", flush=True)
            return False

    def check_should_backup(self):
        try:
            backup_dir = os.path.join(get_exe_dir(), "asistencias")
            os.makedirs(backup_dir, exist_ok=True)
            meta_path = os.path.join(backup_dir, ".backup_metadata")
            if not os.path.exists(meta_path):
                return True
            with open(meta_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            last_date = data.get("last_backup_date")
            import datetime
            today_str = datetime.date.today().isoformat()
            return last_date != today_str
        except Exception as e:
            print(f"SISBOM BACKUP ERROR: Error al comprobar fecha: {e}", flush=True)
            return True

    def save_database_backup(self, backup_data_json):
        try:
            exe_dir = get_exe_dir()
            backup_dir = os.path.join(exe_dir, "asistencias")
            os.makedirs(backup_dir, exist_ok=True)
            
            backup_path = os.path.join(backup_dir, "db_backup.json")
            data = json.loads(backup_data_json)
            with open(backup_path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=4)

            # Guardar archivos JSON individuales para contingencia y sincronización local
            central_dir = os.path.join(exe_dir, "central")
            if os.path.isdir(central_dir):
                if "prueba_radial" in data and data["prueba_radial"]:
                    with open(os.path.join(central_dir, "db_prueba_radial.json"), "w", encoding="utf-8") as f:
                        json.dump(data["prueba_radial"], f, ensure_ascii=False, indent=4)
                    with open(os.path.join(central_dir, "db_pruebaRadial.json"), "w", encoding="utf-8") as f:
                        json.dump(data["prueba_radial"], f, ensure_ascii=False, indent=4)
                if "personal" in data and data["personal"]:
                    with open(os.path.join(central_dir, "db_personal.json"), "w", encoding="utf-8") as f:
                        json.dump(data["personal"], f, ensure_ascii=False, indent=4)
                if "vehiculos" in data and data["vehiculos"]:
                    with open(os.path.join(central_dir, "db_vehiculos.json"), "w", encoding="utf-8") as f:
                        json.dump(data["vehiculos"], f, ensure_ascii=False, indent=4)
                if "bitacora" in data and data["bitacora"]:
                    with open(os.path.join(central_dir, "db_bitacora.json"), "w", encoding="utf-8") as f:
                        json.dump(data["bitacora"], f, ensure_ascii=False, indent=4)
                if "sirena" in data and data["sirena"]:
                    with open(os.path.join(central_dir, "db_sirena.json"), "w", encoding="utf-8") as f:
                        json.dump(data["sirena"], f, ensure_ascii=False, indent=4)
                
            meta_path = os.path.join(backup_dir, ".backup_metadata")
            import datetime
            today_str = datetime.date.today().isoformat()
            with open(meta_path, "w", encoding="utf-8") as f:
                json.dump({"last_backup_date": today_str}, f)
                
            print(f"SISBOM BACKUP: Copia de seguridad guardada con éxito en {backup_path}", flush=True)
            return True
        except Exception as e:
            print(f"SISBOM BACKUP ERROR: Error al guardar copia de seguridad: {e}", flush=True)
    def load_local_db(self, filename):
        try:
            exe_dir = get_exe_dir()
            clean_name = filename.replace('/', os.sep).replace('\\', os.sep)
            
            # Check direct path
            candidate_paths = [
                os.path.join(exe_dir, clean_name),
                os.path.join(exe_dir, "comandancia", clean_name),
                os.path.join(exe_dir, "central", clean_name),
                os.path.join(exe_dir, "asistencias", clean_name)
            ]
            for p in candidate_paths:
                if os.path.isfile(p):
                    with open(p, "r", encoding="utf-8") as f:
                        return f.read()
            return None
        except Exception as e:
            print(f"SISBOM DB ERROR: Error al cargar {filename} desde disco: {e}", flush=True)
            return None

    def save_local_db(self, filename, content_str):
        try:
            exe_dir = get_exe_dir()
            clean_name = filename.replace('/', os.sep).replace('\\', os.sep)
            file_path = os.path.join(exe_dir, clean_name)
            os.makedirs(os.path.dirname(file_path), exist_ok=True)
            with open(file_path, "w", encoding="utf-8") as f:
                f.write(content_str)
            return True
        except Exception as e:
            print(f"SISBOM DB ERROR: Error al guardar {filename} en disco: {e}", flush=True)
            return False

    def on_logout_completed(self):
        global is_logout_done
        is_logout_done = True
        if self._window:
            self._window.destroy()

    def closeApp(self):
        if self._window:
            self._window.destroy()
        else:
            os._exit(0)

    def send_to_whatsapp(self, text):
        try:
            import urllib.parse
            import subprocess
            import webbrowser
            import base64

            # 1. Copiar texto al portapapeles nativo de Windows mediante PowerShell
            try:
                encoded_bytes = text.encode('utf-8')
                b64_str = base64.b64encode(encoded_bytes).decode('ascii')
                ps_script = f"$bytes = [System.Convert]::FromBase64String('{b64_str}'); $txt = [System.Text.Encoding]::UTF8.GetString($bytes); Set-Clipboard -Value $txt"
                subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps_script],
                               creationflags=0x08000000, timeout=3)
            except Exception as ep:
                print(f"SISBOM WHATSAPP CLIPBOARD ERROR: {ep}", flush=True)

            # 2. Intentar lanzar la aplicación de escritorio de WhatsApp mediante protocolo nativo
            encoded_text = urllib.parse.quote(text)
            try:
                os.startfile(f"whatsapp://send?text={encoded_text}")
                print("SISBOM WHATSAPP: Aplicación de escritorio WhatsApp.exe iniciada con éxito.", flush=True)
                return True
            except Exception as ew:
                print(f"SISBOM WHATSAPP DESKTOP FALLBACK: No se pudo abrir protocolo whatsapp:// ({ew}). Abriendo navegador...", flush=True)
                webbrowser.open(f"https://web.whatsapp.com/send?text={encoded_text}")
                return True
        except Exception as e:
            print(f"SISBOM WHATSAPP GENERAL ERROR: {e}", flush=True)
            return False

    def trigger_ota_update(self, latest_version, download_url):
        print(f"SISBOM OTA: Iniciando descarga e instalación de actualización V.{latest_version}...", flush=True)
        try:
            exe_dir = get_exe_dir()
            current_exe_name = os.path.basename(sys.executable)
            current_exe_path = os.path.abspath(sys.executable)
            new_exe_name = f"{os.path.splitext(current_exe_name)[0]}_new.exe"
            new_exe_path = os.path.join(exe_dir, new_exe_name)
            
            def download_and_restart():
                try:
                    import urllib.request
                    import subprocess
                    print(f"SISBOM OTA: Descargando desde {download_url}...", flush=True)
                    urllib.request.urlretrieve(download_url, new_exe_path)
                    print(f"SISBOM OTA: Descarga completada en {new_exe_path}.", flush=True)
                    
                    # Generar update.bat para reemplazo dinámico usando rutas absolutas
                    bat_path = os.path.join(exe_dir, "update.bat")
                    with open(bat_path, "w", encoding="utf-8") as f:
                        f.write(f"""@echo off
timeout /t 3 /nobreak > nul
del /f /q "{current_exe_path}"
rename "{new_exe_path}" "{current_exe_name}"
start "" "{current_exe_path}"
del "%~f0"
exit
""")
                    print(f"SISBOM OTA: Script de actualización bat creado en {bat_path}.", flush=True)
                    
                    # Ejecutar bat y salir
                    subprocess.Popen(
                        [bat_path],
                        shell=True,
                        creationflags=subprocess.CREATE_NEW_CONSOLE | subprocess.DETACHED_PROCESS
                    )
                    print(f"SISBOM OTA: Reiniciando proceso...", flush=True)
                    os._exit(0)
                except Exception as ex:
                    print(f"SISBOM OTA ERROR: Falla durante la descarga/instalación: {ex}", flush=True)
            
            t = threading.Thread(target=download_and_restart)
            t.daemon = True
            t.start()
            return True
        except Exception as e:
            print(f"SISBOM OTA ERROR: No se pudo iniciar el proceso de actualización: {e}", flush=True)
            return False

    def get_hwid(self):
        try:
            fake_hwid_path = os.path.join(get_exe_dir(), "fake_hwid.txt")
            if os.path.exists(fake_hwid_path):
                with open(fake_hwid_path, "r", encoding="utf-8") as f:
                    return f.read().strip()
                    
            import subprocess
            output = subprocess.check_output('wmic csproduct get uuid', shell=True).decode().split('\n')[1].strip()
            return output
        except Exception:
            import uuid
            return f"MAC-{uuid.getnode()}"

    def check_license(self, license_key=None):
        import urllib.request
        import json
        import urllib.error
        try:

            hwid = self.get_hwid()
            cache_path = os.path.join(get_exe_dir(), ".licencia_cache")
            
            # If not provided, try to read it from cache
            if not license_key and os.path.exists(cache_path):
                try:
                    with open(cache_path, "r", encoding="utf-8") as cache_file:
                        cached_res = json.load(cache_file)
                        license_key = cached_res.get("licenseKey")
                except Exception:
                    pass

            urls = [
                "https://validatelicense-3kkeukidtq-uc.a.run.app",
                "https://us-central1-sisbom-central.cloudfunctions.net/validateLicense"
            ]
            
            payload = {
                "hwid": hwid
            }
            if license_key:
                payload["licenseKey"] = license_key

            data = json.dumps(payload).encode("utf-8")
            
            last_err = None
            for url in urls:
                try:
                    req = urllib.request.Request(
                        url,
                        data=data,
                        headers={
                            "Content-Type": "application/json",
                            "User-Agent": "SisBom-Unificada/1.1.7"
                        },
                        method="POST"
                    )
                    with urllib.request.urlopen(req, timeout=6) as response:
                        res_data = json.loads(response.read().decode("utf-8"))
                        if license_key:
                            res_data["licenseKey"] = license_key
                        
                        # Cache the successful license response locally
                        try:
                            with open(cache_path, "w", encoding="utf-8") as cache_file:
                                json.dump(res_data, cache_file)
                        except Exception as e:
                            print(f"Error saving license cache: {e}")
                            
                        # Dynamically download/update logo from logoUrl
                        if res_data.get("logoUrl"):
                            try:
                                logo_url = res_data["logoUrl"]
                                logo_path = os.path.join(get_exe_dir(), "Logo.png")
                                req_logo = urllib.request.Request(
                                    logo_url,
                                    headers={'User-Agent': 'Mozilla/5.0'}
                                )
                                with urllib.request.urlopen(req_logo, timeout=10) as logo_res:
                                    with open(logo_path, "wb") as logo_file:
                                        logo_file.write(logo_res.read())
                                print("Logotipo institucional descargado y guardado localmente.")
                            except Exception as e:
                                print(f"Error al descargar logotipo de la institución: {e}")

                        # Extract embedded logo for known clients (offline fallback)
                        extract_embedded_logo_if_needed(res_data.get('nombreMostrar', ''))

                        # Set window title if name is present
                        if res_data.get("nombreMostrar") and self._window:
                            try:
                                assigned_mod_label = "Central de Alarmas" if res_data.get("module") == "central" else "Comandancia"
                                self._window.set_title(f"{res_data['nombreMostrar']} - {assigned_mod_label}")
                            except Exception as e:
                                print(f"Error setting window title: {e}")

                        return res_data
                except urllib.error.HTTPError as e:
                    try:
                        err_body = json.loads(e.read().decode("utf-8"))
                        if license_key:
                            err_body["licenseKey"] = license_key
                            
                        # Cache the error body if it contains branding so offline fallback has it
                        try:
                            with open(cache_path, "w", encoding="utf-8") as cache_file:
                                json.dump(err_body, cache_file)
                        except Exception as ex:
                            print(f"Error saving error response to cache: {ex}")

                        # EVEN IF NOT AUTHORIZED, download logo and set title if returned
                        if err_body.get("logoUrl"):
                            try:
                                logo_url = err_body["logoUrl"]
                                logo_path = os.path.join(get_exe_dir(), "Logo.png")
                                req_logo = urllib.request.Request(
                                    logo_url,
                                    headers={'User-Agent': 'Mozilla/5.0'}
                                )
                                with urllib.request.urlopen(req_logo, timeout=10) as logo_res:
                                    with open(logo_path, "wb") as logo_file:
                                        logo_file.write(logo_res.read())
                                print("Logotipo institucional descargado y guardado localmente (no autorizado).")
                            except Exception as ex:
                                print(f"Error al descargar logotipo en error: {ex}")
                                
                        if err_body.get("nombreMostrar") and self._window:
                            try:
                                self._window.set_title(f"{err_body['nombreMostrar']} - Inactivo")
                            except Exception as ex:
                                print(f"Error setting window title in error: {ex}")
                                
                        return err_body
                    except Exception:
                        pass
                    last_err = f"Error HTTP {e.code}: {e.reason}"
                except Exception as e:
                    last_err = str(e)
            
            # Fallback to local cache if offline/network error
            if os.path.exists(cache_path):
                try:
                    with open(cache_path, "r", encoding="utf-8") as cache_file:
                        cached_res = json.load(cache_file)
                        cached_res["offline"] = True
                        print("Utilizando caché de licencia local offline.")
                        
                        # Apply cached branding offline
                        if cached_res.get("nombreMostrar") and self._window:
                            try:
                                assigned_mod_label = "Central de Alarmas" if cached_res.get("module") == "central" else "Comandancia"
                                self._window.set_title(f"{cached_res['nombreMostrar']} - {assigned_mod_label} (Offline)")
                            except Exception as ex:
                                print(f"Error setting window title from cache: {ex}")
                                
                        return cached_res
                except Exception as e:
                    print(f"Error leyendo caché de licencia: {e}")
            
            return {
                "authorized": False,
                "reason": f"No se pudo conectar al servidor de licencias SaaS. Detalle: {last_err}"
            }
        except Exception as e:
            print(f"Error checking license: {e}")
            return {"authorized": False, "reason": str(e)}

    def reverse_geocode(self, lat, lng):
        import urllib.request
        import json
        try:
            url = f"https://nominatim.openstreetmap.org/reverse?format=json&lat={lat}&lon={lng}&zoom=18&addressdetails=1"
            req = urllib.request.Request(
                url,
                headers={
                    'User-Agent': 'SisBom-Central/1.1.7 (contacto@sisbom.cl)'
                }
            )
            with urllib.request.urlopen(req, timeout=5) as response:
                data = json.loads(response.read().decode('utf-8'))
                if data and 'address' in data:
                    addr = data['address']
                    road = addr.get('road') or addr.get('pedestrian') or addr.get('neighbourhood') or addr.get('suburb') or ''
                    house = addr.get('house_number') or ''
                    city = addr.get('city') or addr.get('town') or addr.get('village') or addr.get('municipality') or ''
                    short_addr = road
                    if road and house:
                        short_addr = f"{road} {house}"
                    if short_addr and city:
                        short_addr += f", {city}"
                    if not short_addr:
                        short_addr = ', '.join(data.get('display_name', '').split(',')[:2])
                    return {'success': True, 'address': short_addr.upper()}
        except Exception as e:
            print(f"Error reverse geocode in Python: {e}")
        
        # Fallback BigDataCloud client API
        try:
            url2 = f"https://api.bigdatacloud.net/data/reverse-geocode-client?latitude={lat}&longitude={lng}&localityLanguage=es"
            req2 = urllib.request.Request(url2, headers={'User-Agent': 'SisBom-Central/1.1.7'})
            with urllib.request.urlopen(req2, timeout=5) as response2:
                data2 = json.loads(response2.read().decode('utf-8'))
                if data2:
                    locality = data2.get('locality') or data2.get('city') or ''
                    street = data2.get('localityInfo', {}).get('informative', [{}])[0].get('name', '')
                    res = f"{street}, {locality}".strip(', ')
                    return {'success': True, 'address': res.upper()}
        except Exception as e2:
            print(f"Error fallback reverse geocode: {e2}")

        return {'success': False, 'address': ''}

    def search_address(self, query):
        import urllib.request
        import urllib.parse
        import json
        try:
            encoded_q = urllib.parse.quote(query)
            url = f"https://nominatim.openstreetmap.org/search?format=json&countrycodes=cl&limit=1&q={encoded_q}"
            req = urllib.request.Request(
                url,
                headers={
                    'User-Agent': 'SisBom-Central/1.1.7 (contacto@sisbom.cl)'
                }
            )
            with urllib.request.urlopen(req, timeout=5) as response:
                data = json.loads(response.read().decode('utf-8'))
                if data and len(data) > 0:
                    return {
                        'success': True,
                        'lat': float(data[0]['lat']),
                        'lng': float(data[0]['lon']),
                        'display_name': data[0].get('display_name', '')
                    }
        except Exception as e:
            print(f"Error search address in Python: {e}")
        return {'success': False}

    def open_window(self, name):
        if name == 'personal':
            title = 'Personal - Central de Alarmas'
            url = f"http://127.0.0.1:{self.port}/central/personal.html"
        elif name == 'materialMayor':
            title = 'Material Mayor - Central de Alarmas'
            url = f"http://127.0.0.1:{self.port}/central/materialMayor.html"
        else:
            return
        
        # Create a new native window
        webview.create_window(
            title,
            url,
            width=1280,
            height=800,
            resizable=True,
            min_size=(1024, 768)
        )

    def save_backup(self, json_str, filename):
        if not self._window:
            return False
        try:
            file_types = ('Archivos JSON (*.json)', 'Todos los archivos (*.*)')
            save_path = self._window.create_file_dialog(
                webview.SAVE_DIALOG,
                directory=os.path.expanduser('~'),
                save_filename=filename,
                file_types=file_types
            )
            if save_path:
                if isinstance(save_path, (list, tuple)):
                    if len(save_path) > 0:
                        save_path = save_path[0]
                    else:
                        return False
                with open(save_path, 'w', encoding='utf-8') as f:
                    f.write(json_str)
                return True
        except Exception as e:
            print(f"Error al guardar respaldo: {e}")
        return False

    def save_local_db(self, name, content_str):
        try:
            db_path = os.path.join(get_exe_dir(), name)
            db_dir = os.path.dirname(db_path)
            if db_dir:
                os.makedirs(db_dir, exist_ok=True)
            
            tmp_path = db_path + ".tmp"
            bak_path = db_path + ".bak"

            with open(tmp_path, 'w', encoding='utf-8') as f:
                f.write(content_str)
                f.flush()
                os.fsync(f.fileno())

            if os.path.exists(db_path):
                try:
                    os.replace(db_path, bak_path)
                except Exception:
                    pass

            os.replace(tmp_path, db_path)
            return True
        except Exception as e:
            print(f"Error saving local DB {name}: {e}")
            return False

    def load_local_db(self, name):
        try:
            db_path = os.path.join(get_exe_dir(), name)
            bak_path = db_path + ".bak"
            if os.path.isfile(db_path):
                with open(db_path, 'r', encoding='utf-8') as f:
                    return f.read()
            elif os.path.isfile(bak_path):
                with open(bak_path, 'r', encoding='utf-8') as f:
                    return f.read()
            return None
        except Exception as e:
            print(f"Error loading local DB {name}: {e}")
            return None

    def save_last_session_id(self, session_id):
        try:
            path = os.path.join(get_exe_dir(), '.last_session_id')
            with open(path, 'w', encoding='utf-8') as f:
                f.write(str(session_id))
            return True
        except Exception as e:
            print(f"Error saving last session ID: {e}")
            return False

    def load_last_session_id(self):
        try:
            path = os.path.join(get_exe_dir(), '.last_session_id')
            if os.path.isfile(path):
                with open(path, 'r', encoding='utf-8') as f:
                    val = f.read().strip()
                    if val:
                        return int(val)
            return 0
        except Exception as e:
            print(f"Error loading last session ID: {e}")
            return 0

    def _generate_excel_xml(self, data):
        xml = []
        xml.append('<?xml version="1.0" encoding="utf-8"?>')
        xml.append('<?mso-application progid="Excel.Sheet"?>')
        xml.append('<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"')
        xml.append(' xmlns:o="urn:schemas-microsoft-com:office:office"')
        xml.append(' xmlns:x="urn:schemas-microsoft-com:office:excel"')
        xml.append(' xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet"')
        xml.append(' xmlns:html="http://www.w3.org/TR/REC-html40">')
        
        # Styles
        xml.append(' <Styles>')
        xml.append('  <Style ss:ID="Header">')
        xml.append('   <Font ss:Bold="1" ss:Color="#FFFFFF"/>')
        xml.append('   <Interior ss:Color="#B91C1C" ss:Pattern="Solid"/>')
        xml.append('  </Style>')
        xml.append('  <Style ss:ID="Title">')
        xml.append('   <Font ss:Bold="1" ss:Size="14"/>')
        xml.append('  </Style>')
        xml.append(' </Styles>')
        
        # Add metadata sheet
        xml.append(' <Worksheet ss:Name="Resumen">')
        xml.append('  <Table>')
        xml.append('   <Row><Cell><Data ss:Type="String">METADATO</Data></Cell><Cell><Data ss:Type="String">VALOR</Data></Cell></Row>')
        xml.append(f'   <Row><Cell><Data ss:Type="String">Fecha Respaldo</Data></Cell><Cell><Data ss:Type="String">{data.get("backup_date", "")}</Data></Cell></Row>')
        meta = data.get("metadata", {})
        for k, v in meta.items():
            xml.append(f'   <Row><Cell><Data ss:Type="String">{k}</Data></Cell><Cell><Data ss:Type="String">{v}</Data></Cell></Row>')
        xml.append('  </Table>')
        xml.append(' </Worksheet>')
        
        # Add a sheet for each collection
        collections = ['personal', 'vehiculos', 'bitacora', 'alertas', 'asistencia', 'despachos', 'sirena', 'prueba_radial']
        for col in collections:
            col_list = data.get(col, [])
            if not isinstance(col_list, list):
                continue
            
            # Sanitise sheet name (max 31 chars, no special chars)
            sheet_name = col.capitalize()[:30]
            xml.append(f' <Worksheet ss:Name="{sheet_name}">')
            xml.append('  <Table>')
            
            if len(col_list) > 0:
                # Get all unique keys in the collection objects
                keys = []
                for item in col_list:
                    if isinstance(item, dict):
                        for k in item.keys():
                            if k not in keys:
                                keys.append(k)
                
                # Header row
                xml.append('   <Row ss:StyleID="Header">')
                for k in keys:
                    xml.append(f'    <Cell><Data ss:Type="String">{k}</Data></Cell>')
                xml.append('   </Row>')
                
                # Data rows
                for item in col_list:
                    xml.append('   <Row>')
                    for k in keys:
                        val = item.get(k, "")
                        if val is None:
                            val = ""
                        
                        # Handle lists or dictionaries inside fields
                        if isinstance(val, (list, dict)):
                            val = json.dumps(val)
                        
                        val_str = str(val).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;').replace("'", '&apos;')
                        
                        # Check data type
                        if isinstance(val, (int, float)):
                            xml.append(f'    <Cell><Data ss:Type="Number">{val}</Data></Cell>')
                        else:
                            xml.append(f'    <Cell><Data ss:Type="String">{val_str}</Data></Cell>')
                    xml.append('   </Row>')
            else:
                xml.append('   <Row><Cell><Data ss:Type="String">Sin registros</Data></Cell></Row>')
                
            xml.append('  </Table>')
            xml.append(' </Worksheet>')
            
        xml.append('</Workbook>')
        return '\n'.join(xml)

    def save_backup_excel(self, json_str, filename):
        if not self._window:
            return False
        try:
            # Generate XML Spreadsheet content from json_str
            data = json.loads(json_str)
            xml_content = self._generate_excel_xml(data)
            
            file_types = ('Archivo de Excel (*.xls)', 'Todos los archivos (*.*)')
            save_path = self._window.create_file_dialog(
                webview.SAVE_DIALOG,
                directory=os.path.expanduser('~'),
                save_filename=filename,
                file_types=file_types
            )
            if save_path:
                if isinstance(save_path, (list, tuple)):
                    if len(save_path) > 0:
                        save_path = save_path[0]
                    else:
                        return False
                with open(save_path, 'w', encoding='utf-8') as f:
                    f.write(xml_content)
                return True
        except Exception as e:
            print(f"Error al guardar respaldo Excel: {e}")
        return False

    def log_js_error(self, message, source, lineno, colno, error_str):
        print(f"JS ERROR: {message} at {source}:{lineno}:{colno}\nStack: {error_str}", flush=True)
        return True

    def log_js_msg(self, msg):
        print(f"JS LOG: {msg}", flush=True)
        return True

def is_newer_version(latest, current):
    try:
        l_parts = [int(x) for x in latest.split('.')]
        c_parts = [int(x) for x in current.split('.')]
        return l_parts > c_parts
    except Exception:
        return False

def check_for_updates(window):
    import urllib.request
    import json
    import time
    
    # Wait for webview window to initialize
    time.sleep(3)
    
    current_version = "1.3.0"
    if getattr(sys, 'frozen', False):
        print(f"Modo Producción: Iniciando verificación de actualizaciones (V.{current_version})...")
        try:
            req = urllib.request.Request(
                "https://sisbom-de5f8.web.app/descargas/version.json",
                headers={'User-Agent': 'Mozilla/5.0'}
            )
            with urllib.request.urlopen(req, timeout=5) as r:
                data = json.loads(r.read().decode('utf-8'))
                
            # Detect target config key based on running executable filename
            exe_name = os.path.basename(sys.executable).lower()
            target_key = "windows_unificada"
            if "central" in exe_name:
                target_key = "windows_central"
            elif "comandancia" in exe_name:
                target_key = "windows_comandancia"
                
            windows_info = data.get(target_key, data.get("windows_unificada", {}))
            latest_version = windows_info.get("versionName", current_version)
            download_url = windows_info.get("url")
            
            if is_newer_version(latest_version, current_version) and download_url:
                # Esperar a que el frontend esté cargado y enviar notificación de versión
                time.sleep(5)
                for _ in range(15):
                    try:
                        res = window.evaluate_js("typeof window.showUpdateNotification !== 'undefined'")
                        if res:
                            window.evaluate_js(f"window.showUpdateNotification('{latest_version}', '{download_url}')")
                            print(f"SISBOM OTA: Notificación enviada al frontend (V.{latest_version})", flush=True)
                            break
                    except Exception as ej:
                        print(f"SISBOM OTA: Esperando carga para enviar actualización: {ej}", flush=True)
                    time.sleep(2)
        except Exception as e:
            print(f"Error en verificación de actualización: {e}")
    else:
        print("Modo Desarrollo: Omitiendo auto-actualización de ejecutable.")

def disable_close_button(window):
    try:
        import ctypes
        hwnd = window.native.Handle.ToInt64()
        user32 = ctypes.windll.user32
        hMenu = user32.GetSystemMenu(hwnd, False)
        if hMenu:
            # SC_CLOSE = 0xF060, MF_BYCOMMAND = 0x00000000, MF_GRAYED = 0x00000001, MF_DISABLED = 0x00000002
            user32.EnableMenuItem(hMenu, 0xF060, 0x00000000 | 0x00000001 | 0x00000002)
    except Exception:
        pass

def main():
    port = find_free_port()
    server = start_server(port)
    url = f"http://127.0.0.1:{port}/index.html"
    
    api = Api(port)
    logo_file_path = os.path.join(get_exe_dir(), "Logo.png")
    window_icon = logo_file_path if os.path.isfile(logo_file_path) else None

    # Determine window title from cache if exists
    window_title = 'SisBom'
    cache_path = os.path.join(get_exe_dir(), ".licencia_cache")
    if os.path.exists(cache_path):
        try:
            with open(cache_path, "r", encoding="utf-8") as cache_file:
                cached_res = json.load(cache_file)
                if cached_res.get("nombreMostrar"):
                    assigned_mod_label = "Central de Alarmas" if cached_res.get("module") == "central" else "Comandancia"
                    window_title = f"{cached_res['nombreMostrar']} - {assigned_mod_label}"
        except Exception:
            pass

    # Create pywebview window
    try:
        win = webview.create_window(
            window_title,
            url,
            width=1280,
            height=800,
            resizable=True,
            min_size=(1024, 768),
            js_api=api,
            icon=window_icon
        )
    except TypeError:
        win = webview.create_window(
            window_title,
            url,
            width=1280,
            height=800,
            resizable=True,
            min_size=(1024, 768),
            js_api=api
        )
        
    api._window = win
    
    # Registrar controladores de eventos WinForms para mantener el boton "X" desactivado de forma limpia
    def setup_event_handlers():
        try:
            win.native.Resize += lambda sender, event: disable_close_button(win)
            win.native.Activated += lambda sender, event: disable_close_button(win)
            disable_close_button(win)
        except Exception:
            pass

    win.events.shown += setup_event_handlers
    
    # Start OTA update thread
    t = threading.Thread(target=check_for_updates, args=(win,))
    t.daemon = True
    t.start()
    
    def on_closing():
        global is_logout_done
        if is_logout_done:
            return True
            
        def logout_and_close():
            global is_logout_done
            try:
                # Retrieve active module to properly run evaluate_js in the correct window context
                user_str = win.evaluate_js('localStorage.getItem("adminUser")')
                if user_str:
                    win.evaluate_js('window.performLogoutOnClose()')
                else:
                    is_logout_done = True
                    win.destroy()
            except Exception as e:
                print("Error during background logout:", e)
                is_logout_done = True
                win.destroy()
                
        import threading
        t = threading.Thread(target=logout_and_close)
        t.daemon = True
        t.start()
        return False
        
    win.events.closing += on_closing
    
    # Start the webview GUI loop
    webview.start(debug=True, private_mode=False)

if __name__ == '__main__':
    main()
