# Generic agent wrapper — `run_wrapped.sh`

Not every agent exposes a hooks API like Claude Code does. This directory holds
the **adapter pattern** for those agents: instead of instrumenting the agent
from the inside, wrap its CLI invocation from the outside and record the run as
an AgentTrail session.

## Usage

```sh
./run_wrapped.sh <session-name> -- <command...> [args...]
```

Examples:

```sh
# Wrap any agent CLI
./run_wrapped.sh "refactor-auth" -- codex exec "refactor the auth module"

# Or a plain script / command — anything with an exit code is traceable
./run_wrapped.sh "nightly-tests" -- ./scripts/run_tests.sh --suite integration
```

The command's stdout/stderr stream to your terminal exactly as if you ran it
directly. The wrapper's own chatter goes to stderr and only at the end.

## What gets recorded

| AgentTrail call | Content |
|---|---|
| `POST /api/v1/sessions` | `agentType: "custom"`, summary = `<session-name>`, idempotent via `clientSessionId` |
| `message` (actor `agent`) | The exact command line that was run |
| `tool_call` (actor `agent`) | The wrapped command: summary like `` ran `codex` (exit 0, 42.5s) ``, detail with command line, exit code and output head (2 KB) |
| `error` (only on failure) | Failure excerpt (1 KB) when exit code ≠ 0 |
| `session_end` + `PATCH` | Session marked `completed` or `failed` |

The wrapper exits with the wrapped command's exit code, so it composes with
`&&`, `||`, CI pipelines and `set -e` scripts unchanged.

## Pointing an agent CLI at it

The pattern is deliberately agent-agnostic — there are no Codex-specific config
keys here, so it works with any CLI that runs a task and exits:

1. **Ad-hoc runs** (debugging, one-off tasks): prefix the invocation as above.
2. **Aliases**: `alias at-codex='./run_wrapped.sh "codex-task" -- codex exec'` —
   every run becomes a traced session named `codex-task`.
3. **CI / cron**: wrap the scheduled command; each run is one session, and the
   dashboard's error/cost rollups give you a history of the job for free.
4. **Finer granularity**: if the agent CLI can emit per-tool JSON logs
   (e.g. `--json` output), pipe that into a small translator that POSTs
   `tool_call` events to the session created by this wrapper — see
   `integrations/claude-code/` for the per-tool event shape to mimic.

## Requirements

- POSIX `sh`, `curl`, `mktemp`, `tail`
- `python3` or `uuidgen` preferred for UUID generation (falls back to a
  timestamp+pid id); `python3` also preferred for parsing the session id
  (falls back to `sed`)
- `AGENTTRAIL_URL` (default `http://localhost:8080`) and
  `AGENTTRAIL_API_KEY` (default `dev-key`) as usual

## Limitations (honest)

- One wrapped invocation = one coarse `tool_call`, not per-tool events. If you
  need per-tool fidelity, use the agent's native hooks/events API when it has
  one (Claude Code does — see `../claude-code/`), or parse the CLI's JSON logs.
- Output is tailed from a temp file, so there can be a sub-second delay before
  output appears, and extremely chatty output is truncated to 2 KB in the event
  detail (the terminal still shows everything).
