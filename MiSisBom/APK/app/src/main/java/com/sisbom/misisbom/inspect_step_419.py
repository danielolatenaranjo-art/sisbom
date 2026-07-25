import json

log_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\.system_generated\logs\transcript.jsonl"

with open(log_path, 'r', encoding='utf-8') as f:
    for line in f:
        try:
            data = json.loads(line)
            if data.get('step_index') == 419:
                print("Step 419 found!")
                content = data.get('content') or ""
                # Write to scratch
                out_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\scratch\step_419_content.txt"
                with open(out_path, "w", encoding="utf-8") as out_f:
                    out_f.write(content)
                # Print from line 1050 onwards
                for l in content.split("\n"):
                    parts = l.strip().split(":")
                    try:
                        ln = int(parts[0])
                        if 1050 <= ln <= 1110:
                            print(l)
                    except ValueError:
                        pass
                break
        except Exception as e:
            pass
