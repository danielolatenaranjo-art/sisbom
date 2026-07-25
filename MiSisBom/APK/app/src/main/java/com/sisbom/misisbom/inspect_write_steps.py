import json

log_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\.system_generated\logs\transcript.jsonl"

with open(log_path, 'r', encoding='utf-8') as f:
    for line in f:
        try:
            data = json.loads(line)
            tool_calls = data.get('tool_calls') or []
            for tc in tool_calls:
                if tc.get('name') == 'write_to_file':
                    args = tc.get('args') or {}
                    target = args.get('TargetFile') or ""
                    if 'TabScreens.kt' in target:
                        print(f"Step {data.get('step_index')} wrote TabScreens.kt")
                        code = args.get('CodeContent') or ""
                        print(f"  Length: {len(code)}")
                        # Save it
                        out_path = f"C:\\Users\\danie\\.gemini\\antigravity-ide\\brain\\3307f9fb-2c94-4909-a56c-b55a696c1c5b\\scratch\\step_{data.get('step_index')}_TabScreens.kt"
                        with open(out_path, "w", encoding="utf-8") as out_f:
                            out_f.write(code)
                        print(f"  Saved to {out_path}")
        except Exception as e:
            pass
