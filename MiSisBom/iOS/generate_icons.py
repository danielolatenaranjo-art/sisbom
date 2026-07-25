import os
import json
from PIL import Image

def generate_ios_icons():
    # Paths
    script_dir = os.path.dirname(os.path.abspath(__file__))
    source_logo = os.path.abspath(os.path.join(script_dir, "..", "logo.png"))
    assets_path = os.path.join(script_dir, "Assets.xcassets")
    appicon_path = os.path.join(assets_path, "AppIcon.appiconset")

    if not os.path.exists(source_logo):
        print(f"Error: Source logo not found at {source_logo}")
        return

    os.makedirs(appicon_path, exist_ok=True)

    # Standard iOS AppIcon sizes configuration
    icons_config = [
        {"size": "20x20", "idiom": "iphone", "scale": "2x", "filename": "icon-20@2x.png"},
        {"size": "20x20", "idiom": "iphone", "scale": "3x", "filename": "icon-20@3x.png"},
        {"size": "29x29", "idiom": "iphone", "scale": "2x", "filename": "icon-29@2x.png"},
        {"size": "29x29", "idiom": "iphone", "scale": "3x", "filename": "icon-29@3x.png"},
        {"size": "40x40", "idiom": "iphone", "scale": "2x", "filename": "icon-40@2x.png"},
        {"size": "40x40", "idiom": "iphone", "scale": "3x", "filename": "icon-40@3x.png"},
        {"size": "60x60", "idiom": "iphone", "scale": "2x", "filename": "icon-60@2x.png"},
        {"size": "60x60", "idiom": "iphone", "scale": "3x", "filename": "icon-60@3x.png"},
        {"size": "20x20", "idiom": "ipad", "scale": "1x", "filename": "icon-20-ipad@1x.png"},
        {"size": "20x20", "idiom": "ipad", "scale": "2x", "filename": "icon-20-ipad@2x.png"},
        {"size": "29x29", "idiom": "ipad", "scale": "1x", "filename": "icon-29-ipad@1x.png"},
        {"size": "29x29", "idiom": "ipad", "scale": "2x", "filename": "icon-29-ipad@2x.png"},
        {"size": "40x40", "idiom": "ipad", "scale": "1x", "filename": "icon-40-ipad@1x.png"},
        {"size": "40x40", "idiom": "ipad", "scale": "2x", "filename": "icon-40-ipad@2x.png"},
        {"size": "76x76", "idiom": "ipad", "scale": "1x", "filename": "icon-76@1x.png"},
        {"size": "76x76", "idiom": "ipad", "scale": "2x", "filename": "icon-76@2x.png"},
        {"size": "83.5x83.5", "idiom": "ipad", "scale": "2x", "filename": "icon-83.5@2x.png"},
        {"size": "1024x1024", "idiom": "ios-marketing", "scale": "1x", "filename": "icon-1024.png"}
    ]

    print(f"Reading source logo: {source_logo}")
    with Image.open(source_logo) as img:
        # Standardize format to RGBA for processing
        if img.mode != "RGBA":
            img = img.convert("RGBA")

        for cfg in icons_config:
            size_str = cfg["size"]
            scale_str = cfg["scale"]
            filename = cfg["filename"]

            # Compute actual pixel dimensions
            if "x" in size_str:
                w_str, h_str = size_str.split("x")
                w, h = float(w_str), float(h_str)
            else:
                w = h = float(size_str)

            scale = float(scale_str.replace("x", ""))
            px_w = int(w * scale)
            px_h = int(h * scale)

            # Resize the image using LANCZOS interpolation
            resized = img.resize((px_w, px_h), Image.Resampling.LANCZOS)

            # Create a white solid background image (iOS icons must not have alpha channel)
            background = Image.new("RGBA", (px_w, px_h), (255, 255, 255, 255))
            background.paste(resized, (0, 0), resized)
            
            # Convert to RGB (removes alpha)
            final_img = background.convert("RGB")
            
            # Save
            output_filepath = os.path.join(appicon_path, filename)
            final_img.save(output_filepath, "PNG")
            print(f"Generated: {filename} ({px_w}x{px_h}px)")

    # Generate Contents.json
    contents = {
        "images": [
            {
                "size": cfg["size"],
                "idiom": cfg["idiom"],
                "filename": cfg["filename"],
                "scale": cfg["scale"]
            } for cfg in icons_config
        ],
        "info": {
            "version": 1,
            "author": "xcode"
        }
    }

    contents_filepath = os.path.join(appicon_path, "Contents.json")
    with open(contents_filepath, "w") as f:
        json.dump(contents, f, indent=4)
    print(f"Generated asset Contents.json at: {contents_filepath}")
    print("All iOS AppIcon assets generated successfully!")

if __name__ == "__main__":
    generate_ios_icons()
