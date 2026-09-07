import os
from PIL import Image

image_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\media__1779798666406.jpg"
if os.path.exists(image_path):
    with Image.open(image_path) as img:
        print(f"Format: {img.format}, Size: {img.size}, Mode: {img.mode}")
else:
    print("Image not found at path:", image_path)
