import http.server
import socketserver
import threading
import mimetypes
import socket
import sys
import os
import urllib.parse
import webview

class SaaSHTTPRequestHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_GET(self):
        path = self.path.lstrip('/')
        if not path or path == '/':
            path = 'index.html'
        
        path = path.split('?')[0].split('#')[0]
        path = urllib.parse.unquote(path)

        # Get root directory of resources
        if getattr(sys, 'frozen', False):
            base_dir = sys._MEIPASS
        else:
            base_dir = os.path.dirname(os.path.abspath(__file__))

        local_file_path = os.path.join(base_dir, path)
        if os.path.isfile(local_file_path):
            self.send_response(200)
            
            if path.endswith('.html'): mime_type = 'text/html'
            elif path.endswith('.css'): mime_type = 'text/css'
            elif path.endswith('.js'): mime_type = 'application/javascript'
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
    handler = SaaSHTTPRequestHandler
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

class Api:
    def get_clients(self):
        import json
        file_path = os.path.join(get_exe_dir(), 'clientes.json')
        if os.path.exists(file_path):
            try:
                with open(file_path, 'r', encoding='utf-8') as f:
                    return json.load(f)
            except Exception as e:
                print("Error reading clientes.json:", e)
        return {}

    def save_clients(self, clients_dict):
        import json
        file_path = os.path.join(get_exe_dir(), 'clientes.json')
        try:
            with open(file_path, 'w', encoding='utf-8') as f:
                json.dump(clients_dict, f, ensure_ascii=False, indent=4)
            return {"success": True}
        except Exception as e:
            return {"success": False, "reason": str(e)}

def main():
    port = find_free_port()
    server = start_server(port)
    url = f"http://127.0.0.1:{port}/index.html"
    
    # Try using logo icon if exists in AppUnificada
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    icon_path = os.path.join(root_dir, "AppUnificada", "Logo.ico")
    if not os.path.exists(icon_path):
        icon_path = None

    api = Api()

    try:
        win = webview.create_window(
            'SisBom SaaS - Panel de Control',
            url,
            width=1400,
            height=900,
            resizable=True,
            min_size=(1024, 768),
            icon=icon_path,
            js_api=api
        )
    except Exception:
        win = webview.create_window(
            'SisBom SaaS - Panel de Control',
            url,
            width=1400,
            height=900,
            resizable=True,
            min_size=(1024, 768),
            js_api=api
        )
        
    webview.start(debug=False, private_mode=False)

if __name__ == '__main__':
    main()
