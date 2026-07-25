import json

log_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\.system_generated\logs\transcript.jsonl"

with open(log_path, 'r', encoding='utf-8') as f:
    for line in f:
        try:
            data = json.loads(line)
            if data.get('step_index') == 188:
                print("Step 188 found!")
                content = data.get('content') or ""
                print("Content length:", len(content))
                # Write content to a text file in scratch
                out_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\scratch\step_188_content.txt"
                with open(out_path, "w", encoding="utf-8") as out_f:
                    out_f.write(content)
                print(f"Saved step 188 content to {out_path}")
                # print first 500 chars and last 500 chars
                print("Start:\n", content[:500])
                print("\nEnd:\n", content[-500:])
                break
        except Exception as e:
            pass
