import json
import re

log_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\.system_generated\logs\transcript.jsonl"

views = []

with open(log_path, 'r', encoding='utf-8') as f:
    for line in f:
        try:
            data = json.loads(line)
            # We are looking for SYSTEM or MODEL step with VIEW_FILE or tool execution result
            # Or content of steps that contains the output of view_file
            content = data.get('content') or ""
            step_idx = data.get('step_index')
            
            # Let's search if this step is a view_file result for TabScreens.kt
            # The view_file tool output typically has: "File Path: `file:///c:/Users/danie/Desktop/SisBom%20-%20Bomberos%20Placilla%20OH/APK/app/src/main/res`" or similar
            if "TabScreens.kt" in content and "Showing lines" in content:
                # Extract line numbers shown
                match = re.search(r"Showing lines (\d+) to (\d+)", content)
                if match:
                    start_ln = int(match.group(1))
                    end_ln = int(match.group(2))
                    # Extract the lines content. Each line typically starts with "<line_number>: "
                    lines = []
                    for raw_line in content.split('\n'):
                        line_match = re.match(r"^\s*(\d+):\s*(.*)$", raw_line)
                        if line_match:
                            ln = int(line_match.group(1))
                            text = line_match.group(2)
                            lines.append((ln, text))
                    views.append({
                        'step_idx': step_idx,
                        'start_ln': start_ln,
                        'end_ln': end_ln,
                        'lines': lines
                    })
                    print(f"Step {step_idx}: Extracted {len(lines)} lines from {start_ln} to {end_ln}")
        except Exception as e:
            pass

# Now reconstruct the file lines by merging all views
# We map line number to line text
reconstructed = {}
for v in views:
    for ln, text in v['lines']:
        reconstructed[ln] = text

print(f"Reconstructed {len(reconstructed)} unique lines.")
# Let's see if there are missing gaps in the lines from 1200 onwards
missing = []
all_keys = sorted(reconstructed.keys())
if all_keys:
    for ln in range(min(all_keys), max(all_keys) + 1):
        if ln not in reconstructed:
            missing.append(ln)
print(f"Missing lines: {missing}")

# Save the reconstructed lines to a file
out_path = r"C:\Users\danie\.gemini\antigravity-ide\brain\3307f9fb-2c94-4909-a56c-b55a696c1c5b\scratch\reconstructed_TabScreens.kt"
with open(out_path, 'w', encoding='utf-8') as out_f:
    for ln in sorted(reconstructed.keys()):
        out_f.write(f"{ln}: {reconstructed[ln]}\n")
print(f"Saved reconstructed lines to {out_path}")
