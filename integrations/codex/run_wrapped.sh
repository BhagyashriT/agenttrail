#!/bin/sh
# run_wrapped.sh - run ANY agent CLI (or any command) under AgentTrail tracing.
#
# Usage: run_wrapped.sh <session-name> -- <command...> [args...]
#
# Streams the command's stdout/stderr to this terminal, records start/end,
# duration and exit code, and POSTs to AgentTrail:
#   - session_start (via POST /api/v1/sessions)
#   - message       (the exact command line, actor=agent)
#   - tool_call     (the wrapped command, with exit code + duration)
#   - session_end   (+ PATCH session status completed/failed)
#
# Env:
#   AGENTTRAIL_URL      default http://localhost:8080
#   AGENTTRAIL_API_KEY  default dev-key
#
# POSIX sh. Needs: curl, and python3 or uuidgen for a client session id
# (falls back to a timestamp+pid id). No jq required.
set -eu

BASE_URL="${AGENTTRAIL_URL:-http://localhost:8080}"
API_KEY="${AGENTTRAIL_API_KEY:-dev-key}"

die() { echo "run_wrapped: $*" >&2; exit 2; }

[ $# -ge 3 ] || die "usage: $0 <session-name> -- <command...> [args...]"
SESSION_NAME="$1"; shift
[ "$1" = "--" ] || die "expected '--' after session name"
shift
[ $# -ge 1 ] || die "no command given"

command -v curl >/dev/null 2>&1 || die "curl is required"

new_uuid() {
    if command -v python3 >/dev/null 2>&1; then
        python3 -c 'import uuid,sys; sys.stdout.write(str(uuid.uuid4()))'
    elif command -v uuidgen >/dev/null 2>&1; then
        uuidgen | tr 'A-Z' 'a-z'
    else
        # last-resort id; still unique enough for idempotent creation
        echo "$(date +%s%N 2>/dev/null || date +%s)$$-runwrapped"
    fi
}

# POST JSON, print response body. $1=method $2=path $3=json-body
api() {
    curl -sS --max-time 10 -X "$1" "$BASE_URL$2" \
        -H "Content-Type: application/json" \
        -H "X-API-Key: $API_KEY" \
        -d "$3"
}

# Extract "id" from a flat JSON response without jq.
json_id() {
    if command -v python3 >/dev/null 2>&1; then
        printf '%s' "$1" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))'
    else
        printf '%s' "$1" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
    fi
}

utcnow() { date -u +%Y-%m-%dT%H:%M:%SZ; }
now_ms() {
    ms="$(date +%s%3N 2>/dev/null || true)"
    case "$ms" in
        *%*|"") echo $(( $(date +%s) * 1000 )) ;;
        *) echo "$ms" ;;
    esac
}

# --- session start -----------------------------------------------------------
CLIENT_SID="$(new_uuid)"
ESCAPED_NAME="$(printf '%s' "$SESSION_NAME" | sed 's/\\/\\\\/g; s/"/\\"/g')"
RESP="$(api POST /api/v1/sessions \
    "{\"agentType\":\"custom\",\"summary\":\"$ESCAPED_NAME\",\"clientSessionId\":\"$CLIENT_SID\",\"metadata\":{\"wrappedBy\":\"run_wrapped.sh\"}}")"
TRAIL_ID="$(json_id "$RESP")"
[ -n "$TRAIL_ID" ] || die "could not create session (server at $BASE_URL reachable?)"

post_event() { # $1 = event JSON (without ts/sessionId)
    api POST "/api/v1/sessions/$TRAIL_ID/events" \
        "{\"ts\":\"$(utcnow)\",$1}" >/dev/null
}

# Record the exact command line as a message event.
CMD_STR=""
for a in "$@"; do
    case "$a" in
        *[\ \"\'\\]*) a="'$(printf '%s' "$a" | sed "s/'/'\\\\''/g")'" ;;
    esac
    CMD_STR="$CMD_STR $a"
done
CMD_STR="$(printf '%s' "$CMD_STR" | sed 's/^ //; s/\\/\\\\/g; s/"/\\"/g')"
post_event "\"type\":\"message\",\"actor\":\"agent\",\"name\":\"wrapped command\",\"summary\":\"ran: $(printf '%s' "$CMD_STR" | cut -c1-200)\",\"detail\":{\"commandLine\":\"$CMD_STR\"}"

# --- run the command, streaming output ---------------------------------------
TMPOUT="$(mktemp)"; TMPCODE="$(mktemp)"
cleanup() { rm -f "$TMPOUT" "$TMPCODE"; }
trap cleanup EXIT INT TERM

START_MS="$(now_ms)"
# Run in background writing to a file while tail -f streams it to the terminal.
"$@" >"$TMPOUT" 2>&1 &
CMDPID=$!
tail -n +1 -f "$TMPOUT" 2>/dev/null &
TAILPID=$!
set +e
wait "$CMDPID"; CODE=$?
set -e
kill "$TAILPID" 2>/dev/null || true
wait "$TAILPID" 2>/dev/null || true
END_MS="$(now_ms)"
DUR_MS=$((END_MS - START_MS))
[ "$DUR_MS" -ge 0 ] 2>/dev/null || DUR_MS=0

# --- report ------------------------------------------------------------------
if [ "$CODE" -eq 0 ]; then STATUS="completed"; else STATUS="failed"; fi
OUT_HEAD="$(head -c 2000 "$TMPOUT" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n' ' ' | cut -c1-2000)"
SUMMARY="$(printf 'ran `%s` (exit %s, %.1fs)' "$1" "$CODE" "$(awk "BEGIN{printf \"%.1f\", $DUR_MS/1000}")" | cut -c1-280)"
post_event "\"type\":\"tool_call\",\"actor\":\"agent\",\"name\":\"wrapped-command\",\"summary\":\"$SUMMARY\",\"detail\":{\"commandLine\":\"$CMD_STR\",\"exitCode\":$CODE,\"outputHead\":\"$OUT_HEAD\"},\"durationMs\":$DUR_MS"
if [ "$CODE" -ne 0 ]; then
    ERR_HEAD="$(head -c 1000 "$TMPOUT" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n' ' ' | cut -c1-1000)"
    post_event "\"type\":\"error\",\"actor\":\"agent\",\"name\":\"wrapped-command\",\"summary\":\"command failed (exit $CODE)\",\"detail\":{\"excerpt\":\"$ERR_HEAD\",\"exitCode\":$CODE}"
fi
post_event "\"type\":\"session_end\",\"actor\":\"system\",\"name\":\"session end\",\"summary\":\"wrapped command finished ($STATUS)\""
api PATCH "/api/v1/sessions/$TRAIL_ID" "{\"status\":\"$STATUS\"}" >/dev/null

echo "run_wrapped: session $TRAIL_ID ended ($STATUS, exit $CODE, ${DUR_MS}ms)" >&2
exit "$CODE"
