import os
from PIL import Image

src_path = r"C:\Users\danie\Delta_Temp_Logo.png" # Let's write to a temp path first or directly to the target drawable
dest_path = r"c:\Users\danie\Desktop\SisBom - Bomberos Placilla OH\APK\app\src\main\res\drawable\logo_sisbom.png"

# Source logo path
logo_src = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\media__1779798666406.jpg"

if not os.path.exists(logo_src):
    print("Error: Source image not found!")
    exit(1)

with Image.open(logo_src) as img:
    img = img.convert("RGBA")
    datas = img.getdata()
    
    new_data = []
    for item in datas:
        r, g, b, a = item
        # Calculate brightness/max channel or check distance
        # The background is very dark grey (below 50 in R, G, B)
        # We can use a combination of max channel and a threshold.
        val = max(r, g, b)
        
        # Smooth alpha interpolation
        # Below 55: fully transparent
        # Above 80: fully opaque
        # In between: interpolate
        low = 55
        high = 80
        if val <= low:
            alpha = 0
        elif val >= high:
            alpha = 255
        else:
            alpha = int((val - low) / (high - low) * 255)
            
        new_data.append((r, g, b, alpha))
        
    img.putdata(new_data)
    
    # Let's crop the image automatically to remove empty transparent space around the logo
    bbox = img.getbbox()
    if bbox:
        img = img.crop(bbox)
        
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    img.save(dest_path, "PNG")
    print(f"Processed and saved to {dest_path}. Size after crop: {img.size}")
