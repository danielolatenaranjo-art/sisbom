import os

reconstructed_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\scratch\reconstructed_TabScreens.kt"

if os.path.exists(reconstructed_path):
    with open(reconstructed_path, "r", encoding="utf-8") as f:
        lines = f.readlines()
        for line in lines:
            line_str = line.strip()
            if not line_str:
                continue
            ln = int(line_str.split(":")[0])
            if ln >= 1250:
                print(line_str)
else:
    print("Reconstructed file not found!")
