#!/usr/bin/env bash
# =============================================================================
# phase_runner.sh — runs ONE invocation of a phase and records its state.
#
# DESIGN: This script NEVER sleeps inside a job. Retries are handled by the
# GitHub Actions cron schedule (see llops.yml) — each scheduled run invokes
# this script for the current phase. If the model is rate-limited, the script
# marks the phase as DEFERRED and exits fast (near-zero Actions minutes). The
# next cron tick retries it. If the phase succeeds, it's marked DONE and the
# pipeline moves to the next phase on the following tick.
#
# State markers (in workspace/<phase>/):
#   .done      - phase completed successfully
#   .deferred  - phase hit a rate limit, needs retry on a later tick
#   .session   - session id to resume (for --continue continuity)
#
# Usage:
#   phase_runner.sh PHASE_NAME [--review]
# =============================================================================

set -euo pipefail

PHASE="${1:-}"
DO_REVIEW="${2:-}"

if [ -z "${PHASE}" ]; then
  echo "ERROR: no phase given."
  exit 2
fi

# --- Config (override with env) ----------------------------------------------
MODEL="${OPENCODE_MODEL:-opencode/deepseek-v4-flash-free}"
REVIEWER_AGENT="${REVIEWER_AGENT:-reviewer}"
LOG_DIR="logs"
PHASE_DIR="workspace/${PHASE}"
PROMPT_FILE="${PHASE_DIR}/PROMPT.md"
DONE_FILE="${PHASE_DIR}/.done"
DEFERRED_FILE="${PHASE_DIR}/.deferred"
SESSION_FILE="${PHASE_DIR}/.session"
BLOCKED_FILE="${PHASE_DIR}/.blocked"
ATTEMPTS_FILE="${PHASE_DIR}/.attempts"
DEFERRED_ATTEMPTS_FILE="${PHASE_DIR}/.deferred_attempts"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-3}"
MAX_DEFERRALS="${MAX_DEFERRALS:-5}"
STOP_FILE="workspace/.stop"

[ -f "${PROMPT_FILE}" ] || { echo "ERROR: no PROMPT.md for ${PHASE}"; exit 2; }
mkdir -p "${LOG_DIR}"

# --- Halt marker: manual stop switch. Remove workspace/.stop to resume. ------
if [ -f "${STOP_FILE}" ]; then
  echo "== [phase] STOP marker present — pipeline halted. Remove workspace/.stop to resume. =="
  exit 0
fi

# --- Strict rate-limit detection ----------------------------------------------
# Only explicit HTTP/quota markers match. The bare word "retry" is NOT a match
# so normal agent output about retrying builds never triggers a false deferral.
is_rate_limited() {
  grep -qiE "HTTP[ /]?429|429[^0-9]|too many requests|rate[ _-]?limit(ed| exceeded)?|insufficient[ _-]?quota|quota exceeded|(per[ -]?minute|per[ -]?day).*(limit|exceeded)" \
    "${LOG_DIR}/${PHASE}.log" 2>/dev/null && return 0
  return 1
}

# --- Env pre-flight (Fix 7): don't burn an attempt on install flakes/bad keys ---
env_check() {
  if ! command -v opencode >/dev/null 2>&1; then
    echo "== [env] opencode binary missing — env error, will retry (not counted) =="
    return 1
  fi
  if [ -z "${OPENCODE_API_KEY:-}" ]; then
    echo "== [env] OPENCODE_API_KEY not set — BLOCKED (manual intervention) =="
    touch "${BLOCKED_FILE}"
    rm -f "${DEFERRED_FILE}" "${SESSION_FILE}" "${ATTEMPTS_FILE}" "${DEFERRED_ATTEMPTS_FILE}"
    return 2
  fi
  if ! opencode models >/dev/null 2>&1; then
    echo "== [env] auth probe failed (bad key?) — BLOCKED (manual intervention) =="
    touch "${BLOCKED_FILE}"
    rm -f "${DEFERRED_FILE}" "${SESSION_FILE}" "${ATTEMPTS_FILE}" "${DEFERRED_ATTEMPTS_FILE}"
    return 2
  fi
  return 0
}

# --- Session resume by phase title (Fix 5): pick the newest matching session ---
find_session() {
  if command -v python3 >/dev/null 2>&1; then
    opencode session list --format json 2>/dev/null \
      | python3 -c '
import sys, json
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
prefix = "llops-'${PHASE}'"
best = None
best_updated = -1
for s in data:
    t = s.get("title", "") or ""
    if t.startswith(prefix) and int(s.get("updated", 0) or 0) > best_updated:
        best = s.get("id", ""); best_updated = int(s.get("updated", 0))
if best:
    sys.stdout.write(best)
' 2>/dev/null || true
  else
    # fallback: no python3 — newest session id overall
    opencode session list --format json 2>/dev/null \
      | grep -o '"id":"[^"]*"' | head -n1 | cut -d'"' -f4 || true
  fi
}

# --- Build the resume args -----------------------------------------------------
SESSION_ARGS=()
if [ -f "${SESSION_FILE}" ]; then
  SID="$(cat "${SESSION_FILE}")"
  if [ -n "${SID}" ]; then
    SESSION_ARGS=(--session "${SID}")
  fi
fi

run_phase() {
  echo "== [phase] Running: ${PHASE} =="
  set +e
  # opencode CLI >=1.15: the prompt is a POSITIONAL argument, not --prompt.
  opencode run \
    --model "${MODEL}" \
    --agent build \
    "${SESSION_ARGS[@]}" \
    --title "llops-${PHASE}" \
    "$(cat "${PROMPT_FILE}")" \
    > "${LOG_DIR}/${PHASE}.log" 2>&1
  local code=$?
  set -e
  return "${code}"
}

# --- Run the phase --------------------------------------------------------------
# Env pre-flight: missing binary = retry (exit 5, not counted); bad key = blocked (exit 3)
env_check
ENV_CODE=$?
if [ "${ENV_CODE}" = "2" ]; then
  echo "== [phase] BLOCKED during env pre-flight: ${PHASE} =="
  exit 3   # signals "blocked" to the workflow
fi
if [ "${ENV_CODE}" = "1" ]; then
  echo "== [phase] ENV ERROR (opencode missing): ${PHASE} — will retry, not counted =="
  exit 5   # signals "env error" to the workflow
fi

if run_phase; then
  echo "== [phase] SUCCESS: ${PHASE} =="
  touch "${DONE_FILE}"
  rm -f "${DEFERRED_FILE}" "${SESSION_FILE}" "${BLOCKED_FILE}" "${ATTEMPTS_FILE}" "${DEFERRED_ATTEMPTS_FILE}"

  if [ "${DO_REVIEW}" = "--review" ]; then
    echo "== [review] Running reviewer subagent =="
    set +e
    opencode run --model "${MODEL}" --agent "${REVIEWER_AGENT}" \
      "Review all changes made in phase '${PHASE}'. Output numbered FINDINGS." \
      > "${LOG_DIR}/${PHASE}.review.log" 2>&1
    code=$?
    set -e
    echo "== [review] exit: ${code} =="

    if grep -qiE "FINDINGS:[[:space:]]*[0-9]+|^[[:space:]]*[0-9]+\." "${LOG_DIR}/${PHASE}.review.log"; then
      echo "== [fix] Applying fixes for review findings =="
      set +e
      opencode run --model "${MODEL}" --agent build \
        --continue \
        "Apply fixes for the review FINDINGS above. Do not break other code." \
        > "${LOG_DIR}/${PHASE}.fix.log" 2>&1
      code=$?
      set -e
      echo "== [fix] exit: ${code} =="
    fi
  fi

  exit 0
fi

# --- Phase failed: classify -------------------------------------------------------
if is_rate_limited; then
  # Deferred attempt cap (Fix 1): persistent rate-limit -> block instead of infinite retry
  DEFERRED_ATTEMPT=0
  if [ -f "${DEFERRED_ATTEMPTS_FILE}" ]; then
    DEFERRED_ATTEMPT="$(cat "${DEFERRED_ATTEMPTS_FILE}" 2>/dev/null || echo 0)"
  fi
  DEFERRED_ATTEMPT=$((DEFERRED_ATTEMPT + 1))
  printf '%s' "${DEFERRED_ATTEMPT}" > "${DEFERRED_ATTEMPTS_FILE}"

  if [ "${DEFERRED_ATTEMPT}" -ge "${MAX_DEFERRALS}" ]; then
    echo "== [phase] RATE-LIMITED ${DEFERRED_ATTEMPT}/${MAX_DEFERRALS} times: ${PHASE} BLOCKED (manual intervention) =="
    touch "${BLOCKED_FILE}"
    rm -f "${DEFERRED_FILE}" "${SESSION_FILE}" "${ATTEMPTS_FILE}" "${DEFERRED_ATTEMPTS_FILE}"
    exit 3   # signals "blocked" to the workflow
  fi

  echo "== [phase] RATE-LIMITED attempt ${DEFERRED_ATTEMPT}/${MAX_DEFERRALS}: ${PHASE} marked deferred (cron will retry) =="
  touch "${DEFERRED_FILE}"
  # Persist what session we were on so the next tick resumes the same thread
  # (Fix 5: match the phase title, not the newest session).
  LAST_SID="$(find_session)"
  if [ -n "${LAST_SID}" ]; then
    printf '%s' "${LAST_SID}" > "${SESSION_FILE}"
  fi
  exit 42   # signals "deferred" to the workflow
fi

# --- Non-rate-limit failure: attempt cap. After MAX_ATTEMPTS, BLOCK the phase
#     so select-phase skips it instead of retrying forever (quota drain).
ATTEMPT=0
if [ -f "${ATTEMPTS_FILE}" ]; then
  ATTEMPT="$(cat "${ATTEMPTS_FILE}" 2>/dev/null || echo 0)"
fi
ATTEMPT=$((ATTEMPT + 1))
printf '%s' "${ATTEMPT}" > "${ATTEMPTS_FILE}"

if [ "${ATTEMPT}" -ge "${MAX_ATTEMPTS}" ]; then
  echo "== [phase] FAILED ${ATTEMPT}/${MAX_ATTEMPTS}: ${PHASE} BLOCKED (manual intervention needed) =="
  echo "== [phase] Remove ${BLOCKED_FILE} + ${ATTEMPTS_FILE} and push to retry. =="
  touch "${BLOCKED_FILE}"
  rm -f "${DEFERRED_FILE}" "${SESSION_FILE}" "${DEFERRED_ATTEMPTS_FILE}"
  exit 3   # signals "blocked" to the workflow
fi

echo "== [phase] FAILED attempt ${ATTEMPT}/${MAX_ATTEMPTS} (non-rate-limit): ${PHASE} =="
echo "== [phase] See ${LOG_DIR}/${PHASE}.log =="
exit 1
