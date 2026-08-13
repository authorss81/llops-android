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

[ -f "${PROMPT_FILE}" ] || { echo "ERROR: no PROMPT.md for ${PHASE}"; exit 2; }
mkdir -p "${LOG_DIR}"

# --- Strict rate-limit detection ----------------------------------------------
# Only explicit HTTP/quota markers match. The bare word "retry" is NOT a match
# so normal agent output about retrying builds never triggers a false deferral.
is_rate_limited() {
  grep -qiE "HTTP[ /]?429|429[^0-9]|too many requests|rate[ _-]?limit(ed| exceeded)?|insufficient[ _-]?quota|quota exceeded|(per[ -]?minute|per[ -]?day).*(limit|exceeded)" \
    "${LOG_DIR}/${PHASE}.log" 2>/dev/null && return 0
  return 1
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
if run_phase; then
  echo "== [phase] SUCCESS: ${PHASE} =="
  touch "${DONE_FILE}"
  rm -f "${DEFERRED_FILE}" "${SESSION_FILE}"

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
  echo "== [phase] RATE-LIMITED: ${PHASE} marked deferred (cron will retry) =="
  touch "${DEFERRED_FILE}"
  # Persist what session we were on so the next tick resumes the same thread.
  # Uses --title search; opens our own session file with the last session id.
  LAST_SID="$(opencode session list --format json 2>/dev/null \
    | grep -o '"id":"[^"]*"' | head -n1 | cut -d'"' -f4 || true)"
  if [ -n "${LAST_SID}" ]; then
    printf '%s' "${LAST_SID}" > "${SESSION_FILE}"
  fi
  exit 42   # signals "deferred" to the workflow
fi

echo "== [phase] FAILED (non-rate-limit): ${PHASE} =="
echo "== [phase] See ${LOG_DIR}/${PHASE}.log =="
exit 1
