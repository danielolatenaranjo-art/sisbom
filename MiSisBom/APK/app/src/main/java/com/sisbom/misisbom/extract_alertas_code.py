import os

txt_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\scratch\reconstructed_TabScreens.kt"

if os.path.exists(txt_path):
    with open(txt_path, "r", encoding="utf-8") as f:
        lines = f.readlines()
        for line in lines:
            line_str = line.strip()
            if not line_str:
                continue
            parts = line_str.split(":", 1)
            if len(parts) < 2:
                continue
            try:
                ln = int(parts[0])
                if 1150 <= ln <= 1662:
                    content = parts[1]
                    print(f"{ln}:{content}")
            except ValueError:
                pass
else:
    print("Reconstructed file not found!")
