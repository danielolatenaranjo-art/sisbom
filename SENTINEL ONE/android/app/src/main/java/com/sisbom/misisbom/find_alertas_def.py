import json

log_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\.system_generated\logs\transcript.jsonl"

with open(log_path, 'r', encoding='utf-8') as f:
    for i, line in enumerate(f):
        try:
            data = json.loads(line)
            content = data.get('content') or ""
            if "AlertasTab" in content:
                print(f"Step {data.get('step_index')}:")
                # Search for AlertasTab line in content
                for l in content.split("\n"):
                    if "AlertasTab" in l:
                        print("  ", l)
        except Exception as e:
            pass
