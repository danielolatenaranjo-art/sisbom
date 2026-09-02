with open(r'c:\Users\danie\Desktop\SisBom\DEV\SisBom\comandancia\index.html', 'r', encoding='utf-8') as f:
    text = f.read()

import re
for m in re.finditer(r'screensaver|togglePassword|onkeydown|keypress|keyup|click|focus', text, re.IGNORECASE):
    idx = m.start()
    line_no = text[:idx].count('\n') + 1
    print(f"Line {line_no} [{m.group(0)}]: {text[idx:idx+60].replace(chr(10), ' ')}")
