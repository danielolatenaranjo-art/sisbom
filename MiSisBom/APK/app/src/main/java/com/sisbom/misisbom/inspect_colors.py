from PIL import Image

image_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\media__1779798666406.jpg"
with Image.open(image_path) as img:
    pixels = img.load()
    print("Corners:")
    print("Top-Left (0,0):", pixels[0, 0])
    print("Top-Right (1023,0):", pixels[1023, 0])
    print("Bottom-Left (0,557):", pixels[0, 557])
    print("Bottom-Right (1023,557):", pixels[1023, 557])
    print("Center (512,279):", pixels[512, 279])
