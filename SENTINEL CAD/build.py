import os
import sys
import urllib.request
import json
import re

def download_file(url, path):
    print(f"Downloading {url} to {path}...")
    dir_name = os.path.dirname(path)
    if dir_name:
        os.makedirs(dir_name, exist_ok=True)
    try:
        req = urllib.request.Request(
            url, 
            headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
        )
        with urllib.request.urlopen(req, timeout=15) as response:
            with open(path, 'wb') as f:
                f.write(response.read())
        print(f"Downloaded {path} successfully.")
        return True
    except Exception as e:
        print(f"Error downloading {url}: {e}")
        return False

def download_external_resources(module_name):
    libs = {
        f'{module_name}/libs/chart.js': 'https://cdn.jsdelivr.net/npm/chart.js',
        f'{module_name}/libs/font-awesome/css/all.min.css': 'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css',
        f'{module_name}/libs/font-awesome/webfonts/fa-solid-900.woff2': 'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/webfonts/fa-solid-900.woff2',
        f'{module_name}/libs/font-awesome/webfonts/fa-regular-400.woff2': 'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/webfonts/fa-regular-400.woff2',
        f'{module_name}/libs/font-awesome/webfonts/fa-brands-400.woff2': 'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/webfonts/fa-brands-400.woff2',
        f'{module_name}/libs/firebase-app.js': 'https://www.gstatic.com/firebasejs/11.6.1/firebase-app.js',
        f'{module_name}/libs/firebase-auth.js': 'https://www.gstatic.com/firebasejs/11.6.1/firebase-auth.js',
        f'{module_name}/libs/firebase-firestore.js': 'https://www.gstatic.com/firebasejs/11.6.1/firebase-firestore.js',
    }
    
    all_success = True
    for path, url in libs.items():
        # Only download if not already cached
        if not os.path.exists(path):
            if not download_file(url, path):
                all_success = False
        else:
            print(f"Using cached {path}")
            
    if all_success:
        print(f"All external CDNs for {module_name} downloaded successfully.")
    else:
        print(f"Some external CDNs for {module_name} failed to download. Check connection.")

def patch_firebase_files(module_name):
    files = [f'{module_name}/libs/firebase-auth.js', f'{module_name}/libs/firebase-firestore.js']
    for file in files:
        if os.path.exists(file):
            print(f"Patching imports in {file}...")
            with open(file, 'r', encoding='utf-8') as f:
                content = f.read()
            # Replace absolute imports with local ones
            patched = content.replace('https://www.gstatic.com/firebasejs/11.6.1/firebase-app.js', './firebase-app.js')
            with open(file, 'w', encoding='utf-8') as f:
                f.write(patched)

def compile_tailwind():
    print("\nCompiling Tailwind CSS using Tailwind CLI...")
    import subprocess
    os.makedirs('central/libs', exist_ok=True)
    os.makedirs('libs', exist_ok=True)
    
    if os.name == 'nt':
        tailwind_bin = os.path.join('node_modules', '.bin', 'tailwindcss.cmd')
        if not os.path.exists(tailwind_bin):
            tailwind_bin = os.path.join('..', 'SisBom', 'node_modules', '.bin', 'tailwindcss.cmd')
    else:
        tailwind_bin = os.path.join('node_modules', '.bin', 'tailwindcss')
        if not os.path.exists(tailwind_bin):
            tailwind_bin = os.path.join('..', 'SisBom', 'node_modules', '.bin', 'tailwindcss')
        
    if not os.path.exists(tailwind_bin):
        print(f"Error: Tailwind CSS CLI binary not found at {tailwind_bin}. Run npm install first.")
        sys.exit(1)
        
    try:
        print("Compiling central/libs/tailwind.css...")
        subprocess.run([tailwind_bin, '-i', 'tailwind-input.css', '-o', 'central/libs/tailwind.css', '--minify'], check=True)
        
        print("Compiling libs/tailwind-activation.css...")
        subprocess.run([tailwind_bin, '-i', 'tailwind-input.css', '-o', 'libs/tailwind-activation.css', '--minify'], check=True)
        
        print("Tailwind CSS compilation complete.\n")
    except Exception as e:
        print(f"Error compiling Tailwind CSS: {e}")
        sys.exit(1)

def build():
    # 0. Compile Tailwind CSS
    compile_tailwind()



    # 1. Download/setup resources for Central de Alarmas (SENTINEL CAD)
    print("\nSetting up Central de alarmas resources...")
    download_external_resources('central')
    patch_firebase_files('central')

    # Convert sentinel_icon.png (from LOGOS DE SENTINEL) to Logo.ico using pillow
    try:
        from PIL import Image
        if os.path.exists('sentinel_icon.png'):
            print("\nConverting sentinel_icon.png to Logo.ico...")
            img = Image.open('sentinel_icon.png')
            img.save('Logo.ico', format='ICO', sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
            print("Logo.ico generated successfully from sentinel_icon.png!")
        elif os.path.exists('Logo.png'):
            print("\nConverting Logo.png to Logo.ico...")
            img = Image.open('Logo.png')
            img.save('Logo.ico', format='ICO', sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
            print("Logo.ico generated successfully!")
    except Exception as e:
        print(f"\nWarning: Could not create Logo.ico using pillow: {e}")

    # Files to exclude during packaging
    exclude_files = {
        'build.py',
        'main.py',
        'bundled_assets.py',
        'embedded_logos.py',
        'generate_embedded_logos.py',
        'requirements.txt',
        'Logo.ico',
        'SisBomApp.spec',
        'SisBom.spec',
        '.licencia_cache',
        '.last_session_id',
        'package.json',
        'package-lock.json',
        'tailwind.config.js',
        'tailwind-input.css',
    }
    exclude_extensions = {
        '.py',
        '.pyc',
        '.exe',
        '.spec',
        '.bak',
        '.bak_final'
    }
    exclude_dirs = {
        'build',
        'dist',
        '__pycache__',
        '.git',
        '.venv',
        'venv',
        'Ejecutable',
        'node_modules'
    }

    assets = {}
    
    print("\nBundling assets...")
    for root, dirs, files in os.walk('.'):
        dirs[:] = [d for d in dirs if d not in exclude_dirs]
        
        for file in files:
            if file in exclude_files:
                continue
            _, ext = os.path.splitext(file)
            if ext.lower() in exclude_extensions:
                continue
            
            rel_path = os.path.relpath(os.path.join(root, file), '.')
            url_path = rel_path.replace(os.path.sep, '/')
            
            print(f"Bundling: {url_path}")
            try:
                with open(os.path.join(root, file), 'rb') as f:
                    assets[url_path] = f.read()
            except Exception as e:
                print(f"Error reading {url_path}: {e}")

    try:
        with open('bundled_assets.py', 'w', encoding='utf-8') as f:
            f.write("# This file is auto-generated by build.py. Do not edit.\n\n")
            f.write("ASSETS = {\n")
            for path, data in assets.items():
                f.write(f"    {repr(path)}: {repr(data)},\n")
            f.write("}\n")
        print(f"\nbundled_assets.py generated successfully! Packed {len(assets)} files.")
    except Exception as e:
        print(f"Error writing bundled_assets.py: {e}")
        sys.exit(1)

if __name__ == '__main__':
    build()
