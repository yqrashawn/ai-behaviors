#!/bin/bash
set -euo pipefail

# PreToolUse hook: enforce mode boundaries by denying mutation tools
# when the active operating mode forbids them.

INPUT=$(cat)
SESSION_ID=$(jq -r '.session_id // empty' <<< "$INPUT")
TOOL_NAME=$(jq -r '.tool_name // empty' <<< "$INPUT")

if [ -z "$SESSION_ID" ] || [ -z "$TOOL_NAME" ]; then
  exit 0
fi

# Read active state
STATE_FILE="$HOME/.claude/behaviors-state/$SESSION_ID"
if [ ! -f "$STATE_FILE" ] || [ ! -s "$STATE_FILE" ]; then
  exit 0
fi

ACTIVE=$(cat "$STATE_FILE")

# Extract operating mode (the #= prefixed tag)
MODE=""
for TAG in $ACTIVE; do
  case "$TAG" in
    \#=*) MODE="${TAG#\#=}" ;;
    =*)   MODE="${TAG#=}" ;;
  esac
done

if [ -z "$MODE" ]; then
  exit 0
fi

# Resolve symlink to find the repo
SCRIPT_PATH="${BASH_SOURCE[0]}"
while [ -L "$SCRIPT_PATH" ]; do
  SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_PATH")" && pwd)"
  SCRIPT_PATH="$(readlink "$SCRIPT_PATH")"
  [[ "$SCRIPT_PATH" != /* ]] && SCRIPT_PATH="$SCRIPT_DIR/$SCRIPT_PATH"
done
REPO_DIR="$(cd "$(dirname "$SCRIPT_PATH")/.." && pwd)"

# Derive project root for local overrides
CWD=$(jq -r '.cwd // empty' <<< "$INPUT")
PROJECT_ROOT=""
if [ -n "$CWD" ]; then
  PROJECT_ROOT=$(git -C "$CWD" rev-parse --show-toplevel 2>/dev/null || true)
fi
LOCAL_BEHAVIORS_DIR=${PROJECT_ROOT:+$PROJECT_ROOT/.ai-behaviors}
USER_BEHAVIORS_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/ai-behaviors/behaviors"

# Find the blocked-tools file: project-local → user-local → repo
BLOCKED_FILE=""
for DIR in "$LOCAL_BEHAVIORS_DIR/=$MODE" "$USER_BEHAVIORS_DIR/=$MODE" "$REPO_DIR/behaviors/=$MODE"; do
  if [ -n "$DIR" ] && [ -f "$DIR/blocked-tools" ]; then
    BLOCKED_FILE="$DIR/blocked-tools"
    break
  fi
done

if [ -z "$BLOCKED_FILE" ]; then
  exit 0
fi

# Check if the current tool is blocked
# blocked-tools format: one tool name/pattern per line
# Supports exact match and prefix match (ending with *)
while IFS= read -r PATTERN; do
  PATTERN=$(echo "$PATTERN" | sed 's/#.*//' | xargs)  # strip comments and whitespace
  [ -z "$PATTERN" ] && continue

  if [[ "$PATTERN" == *\* ]]; then
    # Prefix match
    PREFIX="${PATTERN%\*}"
    if [[ "$TOOL_NAME" == "$PREFIX"* ]]; then
      jq -n --arg mode "=$MODE" --arg tool "$TOOL_NAME" '{
        decision: "deny",
        reason: ("Mode #" + $mode + " does not allow " + $tool + ". This mode has hard boundaries — switch to an appropriate mode (e.g. #=code) to use this tool.")
      }'
      exit 0
    fi
  else
    # Exact match
    if [ "$TOOL_NAME" = "$PATTERN" ]; then
      jq -n --arg mode "=$MODE" --arg tool "$TOOL_NAME" '{
        decision: "deny",
        reason: ("Mode #" + $mode + " does not allow " + $tool + ". This mode has hard boundaries — switch to an appropriate mode (e.g. #=code) to use this tool.")
      }'
      exit 0
    fi
  fi
done < "$BLOCKED_FILE"

exit 0
