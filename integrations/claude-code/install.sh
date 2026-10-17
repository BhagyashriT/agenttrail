#!/bin/sh
# Install AgentTrail hooks into ~/.claude/settings.json.
# - Creates a timestamped backup of the existing settings file first.
# - Uses python3 for JSON merging (no jq dependency).
# - Idempotent: re-running replaces only AgentTrail-owned hook entries.
set -eu

HOOKS_DIR="$(cd "$(dirname "$0")/hooks" && pwd)"
SETTINGS="$HOME/.claude/settings.json"

mkdir -p "$HOME/.claude"
mkdir -p "$HOME/.agenttrail"

if [ -f "$SETTINGS" ]; then
    BACKUP="$HOME/.claude/settings.json.bak.$(date +%Y%m%d%H%M%S)"
    cp "$SETTINGS" "$BACKUP"
    echo "Backup written to: $BACKUP"
fi

python3 - "$HOOKS_DIR" "$SETTINGS" <<'PYEOF'
import json
import os
import sys

hooks_dir, settings_path = sys.argv[1], sys.argv[2]

try:
    with open(settings_path, encoding="utf-8") as f:
        settings = json.load(f)
except (FileNotFoundError, json.JSONDecodeError):
    settings = {}
if not isinstance(settings, dict):
    raise SystemExit("settings.json is not a JSON object; refusing to modify it")

# event -> hook script
mapping = {
    "SessionStart": "session_start.py",
    "UserPromptSubmit": "user_prompt_submit.py",
    "PreToolUse": "pre_tool_use.py",
    "PostToolUse": "post_tool_use.py",
    "SubagentStop": "subagent_stop.py",
    "Stop": "stop.py",
    "Notification": "notification.py",
}

hooks = settings.get("hooks")
if not isinstance(hooks, dict):
    hooks = {}
    settings["hooks"] = hooks

for event, script in mapping.items():
    command = os.path.join(hooks_dir, script)
    entry = {"matcher": "", "hooks": [{"type": "command", "command": command}]}
    existing = hooks.get(event)
    if isinstance(existing, list):
        # Drop previous AgentTrail entries so reinstalls don't duplicate.
        existing = [
            e for e in existing
            if "agenttrail" not in json.dumps(e).lower()
        ]
        hooks[event] = existing + [entry]
    else:
        hooks[event] = [entry]

with open(settings_path, "w", encoding="utf-8") as f:
    json.dump(settings, f, indent=2)
    f.write("\n")

print("AgentTrail hooks installed in %s" % settings_path)
PYEOF

echo "Done. Set AGENTTRAIL_URL (default http://localhost:8080) and"
echo "AGENTTRAIL_API_KEY (default dev-key) in your shell profile if needed."
