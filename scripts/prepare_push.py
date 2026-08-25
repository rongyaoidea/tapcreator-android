import json, os, sys

project = sys.argv[1]
files = [
    'app/src/main/java/com/tapcreator/app/MainActivity.kt',
    'app/src/main/java/com/tapcreator/app/backend/auth/AuthService.kt',
    'app/src/main/java/com/tapcreator/app/backend/service/RunService.kt',
    'app/src/main/java/com/tapcreator/app/backend/agent/AgentBrain.kt',
    'app/src/main/java/com/tapcreator/app/ui/chat/ChatViewModel.kt',
    'app/src/main/java/com/tapcreator/app/ui/chat/ChatScreen.kt',
    'app/src/main/java/com/tapcreator/app/ui/chat/CanvasWorkspace.kt',
    'app/src/main/java/com/tapcreator/app/ui/home/ConversationListScreen.kt',
    'app/src/main/java/com/tapcreator/app/ui/home/ConversationListViewModel.kt',
    'app/src/main/java/com/tapcreator/app/ui/settings/SettingsViewModel.kt',
    'app/src/main/java/com/tapcreator/app/ui/nav/TapcreatorNav.kt',
    'app/src/main/java/com/tapcreator/app/ui/library/LibraryScreen.kt',
    'app/src/main/java/com/tapcreator/app/ui/settings/SettingsScreen.kt',
    'app/src/test/java/com/tapcreator/app/backend/service/RunServicePureFunctionsTest.kt',
]

batch_size = 5
for i in range(0, len(files), batch_size):
    batch = files[i:i+batch_size]
    result = []
    for f in batch:
        full = os.path.join(project, f)
        with open(full, 'r', encoding='utf-8') as fh:
            content = fh.read()
        result.append({'path': f, 'content': content})
    out_path = os.path.join('/tmp', f'push_batch_{i//batch_size}.json')
    with open(out_path, 'w', encoding='utf-8') as out:
        json.dump(result, out, ensure_ascii=False)
    print(f'BATCH {i//batch_size} ({len(batch)} files, {len(json.dumps(result))} chars) -> {out_path}')
print('Done')