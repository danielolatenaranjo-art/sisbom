import os

txt_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\scratch\step_188_content.txt"

if os.path.exists(txt_path):
    with open(txt_path, "r", encoding="utf-8") as f:
        lines = f.readlines()
        in_alertas = False
        for line in lines:
            if "fun AlertasTab" in line or "fun AlertaTab" in line:
                in_alertas = True
            if in_alertas:
                print(line, end="")
                # We stop printing if we see another function or tab declaration
                if "fun AsistenciaTab" in line:
                    in_alertas = False
else:
    print("step 188 content not found!")
