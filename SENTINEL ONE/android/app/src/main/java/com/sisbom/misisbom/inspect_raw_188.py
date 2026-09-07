import os

txt_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\scratch\step_188_content.txt"

if os.path.exists(txt_path):
    with open(txt_path, "r", encoding="utf-8") as f:
        for line in f:
            line_str = line.strip()
            if not line_str:
                continue
            parts = line_str.split(":", 1)
            try:
                ln = int(parts[0])
                print(ln, end=" ")
            except ValueError:
                pass
        print()
else:
    print("step 188 content not found!")
