#!/usr/bin/env python3
import base64, json, subprocess, sys

result = subprocess.run(['git', 'diff', '--name-only', 'HEAD~1', 'HEAD'], capture_output=True, text=True, cwd=sys.argv[1])
files = [f.strip() for f in result.stdout.split('\n') if f.strip() and not f.startswith('.codegraph') and not f.startswith('app/src/main/assets')]

files_data = []
for f in files:
    with open(f'{sys.argv[1]}/{f}', 'rb') as fp:
        content = fp.read()
    files_data.append({'path': f, 'content': base64.b64encode(content).decode('ascii')})

print(json.dumps({'files': files_data, 'count': len(files_data)}))