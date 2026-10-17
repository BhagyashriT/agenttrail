# Claude Code setup

Record every Claude Code session as an AgentTrail session: prompts, tool calls
(with durations and exit codes), errors, subagent handoffs, and session end.

## 1. Start the server

```sh
docker compose up        # API at http://localhost:8080, dashboard at :5173
```

Verify it's up (no auth needed):

```sh
curl localhost:8080/api/v1/health
# {"status":"UP","version":"0.1.0"}
```

## 2. Install the hooks

```sh
./integrations/claude-code/install.sh
```

This merges seven hooks into `~/.claude/settings.json`:

| Hook | AgentTrail event |
|---|---|
| `SessionStart` | creates the session (`agentType: "claude-code"`), caches the id mapping |
| `UserPromptSubmit` | `message` (actor `user`) |
| `PreToolUse` | stashes the tool start time |
| `PostToolUse` | one `tool_call` event (+ an `error` event when the tool fails) |
| `SubagentStop` | `handoff` (actor `subagent:<name>`) |
| `Stop` | `session_end` + session marked `completed` |
| `Notification` | `message` (actor `system`) |

A timestamped backup of your previous `settings.json` is written next to it
first, and re-running the installer replaces only AgentTrail-owned entries —
your other hooks are untouched. See `settings.example.json` for the resulting
snippet.

## 3. Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `AGENTTRAIL_URL` | `http://localhost:8080` | where the hooks POST |
| `AGENTTRAIL_API_KEY` | `dev-key` | `X-API-Key` header value |

Export them in your shell profile if your server uses non-defaults:

```sh
export AGENTTRAIL_URL="http://myserver:8080"
export AGENTTRAIL_API_KEY="..."
```

## 4. Verify events land

1. Open a new Claude Code session and run any command (e.g. ask it to run `ls`).
2. Check the session exists:

```sh
curl -H "X-API-Key: dev-key" localhost:8080/api/v1/sessions | python3 -m json.tool
```

3. Open the dashboard and watch the timeline fill in as tools run.
4. Or query events directly:

```sh
SID=<session-id>
curl -H "X-API-Key: dev-key" "localhost:8080/api/v1/sessions/$SID/events" | python3 -m json.tool
```

You should see `session_start`, a `message` for your prompt, and a `tool_call`
for the command with a human summary like `` ran `ls` (exit 0, 0.1s) ``.

## 5. Troubleshooting

**No sessions appear.**
- Is the server up? `curl localhost:8080/api/v1/health`.
- Are the env vars right in the shell that launched Claude Code? Hooks inherit
  the environment of the Claude Code process — exporting the vars in a new
  terminal after launching it won't help.
- Check `~/.agenttrail/hooks.log` — every hook failure appends exactly one
  line there (the API key is never logged).

**Sessions appear but no tool events.**
- The hooks only fire for tools run inside the session after install; restart
  Claude Code so it picks up the new `settings.json`.
- Confirm the entries exist: `python3 -c "import json; print(list(json.load(open('$HOME/.claude/settings.json'))['hooks']))"`.

**Wrong API key (401s in the log).**
- `AGENTTRAIL_API_KEY` must match the server's key. Default on both sides is
  `dev-key`.

**A hook broke my session?**
- It can't — by design. Every hook wraps its work in try/except, uses a 3s
  HTTP timeout, and exits 0 no matter what, logging one line to
  `~/.agenttrail/hooks.log`. If you suspect otherwise, run the hook manually:
  `echo '{"session_id":"x"}' | ./integrations/claude-code/hooks/session_start.py; echo "exit: $?"`.

**State file.** `~/.agenttrail/state.json` maps Claude session ids to
AgentTrail session ids plus in-flight tool timers. Deleting it is safe — the
next hook recreates the session (idempotently, via `clientSessionId`).

**Uninstall.** Restore the backup: `cp ~/.claude/settings.json.bak.<timestamp> ~/.claude/settings.json`.
