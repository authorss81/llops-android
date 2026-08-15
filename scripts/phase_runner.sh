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
#   phase_runner.sh PHASE_NAME [--review]       # run phase then review (legacy)
#   phase_runner.sh PHASE_NAME --review-only    # review an already-done phase
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
NOWORK_FILE="${PHASE_DIR}/.no_work"
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

# Build the injected context header so every phase starts oriented (no cold start):
# AGENTS.md rules + docs/ARCHITECTURE.md map + docs/phase-status.md truth table.
build_context_header() {
  {
    echo "# PIPELINE CONTEXT (injected by phase_runner.sh — do not delete)"
    echo ""
    echo "## Hard rules (AGENTS.md)"
    if [ -f AGENTS.md ]; then
      sed -n '/^## Hard rules/,/^## /p' AGENTS.md | head -n 120
    fi
    echo ""
    echo "## Architecture map (docs/ARCHITECTURE.md — living doc, read + update)"
    if [ -f docs/ARCHITECTURE.md ]; then cat docs/ARCHITECTURE.md; fi
    echo ""
    echo "## Phase status truth table (docs/phase-status.md — read + update your row)"
    if [ -f docs/phase-status.md ]; then cat docs/phase-status.md; fi
    echo ""
    echo "## Current phase PROMPT"
    echo ""
  } > "${LOG_DIR}/${PHASE}.ctx" 2>/dev/null
  echo "== [phase] context header built: ${LOG_DIR}/${PHASE}.ctx =="
}

# --- Evidence gate (review finding fix): opencode exits 0 even when a session
#     never does anything (phase-32 self-certified .done with zero work at
#     commit 6b17422). A phase is only marked DONE when the run left real
#     working-tree changes — pipeline artifacts (logs/<phase>.*, the phase's own
#     hidden markers like .deferred/.session/.attempts) are not evidence.
tree_work() {
  git status --porcelain 2>/dev/null \
    | sed -E 's/^(.{2})[[:space:]]+//' \
    | grep -vE "^logs/|^workspace/${PHASE}/\.|^workspace/\.[^/]*($|/)" || true
}

# True when this run introduced changes (vs. the before-run snapshot).
has_new_work() {
  local before="$1"
  local after
  after="$(tree_work)"
  local new
  new="$(comm -13 <(printf '%s\n' "${before}" | sort -u) <(printf '%s\n' "${after}" | sort -u))"
  [ -n "${new}" ]
}

git_available() {
  git rev-parse --git-dir >/dev/null 2>&1
}

run_phase() {
  echo "== [phase] Running: ${PHASE} =="
  build_context_header
  set +e
  # opencode CLI >=1.15: the prompt is a POSITIONAL argument, not --prompt.
  {
    cat "${LOG_DIR}/${PHASE}.ctx"
    cat "${PROMPT_FILE}"
  } > "${LOG_DIR}/${PHASE}.prompt"
  opencode run \
    --model "${MODEL}" \
    --agent build \
    "${SESSION_ARGS[@]}" \
    --title "llops-${PHASE}" \
    "$(cat "${LOG_DIR}/${PHASE}.prompt")" \
    > "${LOG_DIR}/${PHASE}.log" 2>&1
  local code=$?
  set -e
  return "${code}"
}

# Review + fix the phase's changes. Used by --review (after phase success) and
# by --review-only (on a later tick, after the phase work was already pushed).
run_review() {
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

# --- Review-only mode (Fix 9): phase work is committed BEFORE review, so a job
#     timeout during review can never destroy the phase. Review runs on a later
#     invocation (same tick after commit, or a future tick) via --review-only.
if [ "${DO_REVIEW}" = "--review-only" ]; then
  if [ -f "${DONE_FILE}" ]; then
    echo "== [phase] ${PHASE} already done — running review only =="
    run_review
    exit 0
  fi
  echo "== [phase] ${PHASE} not done yet — nothing to review =="
  exit 0
fi

# --- Already-done guard (review finding fix): a completed phase must never be
#     re-run in normal mode, even if a stale/deferred re-selection picked it
#     (phase-32 was re-run after .done at commit 27b93fd, adding contradictory
#     .no_work/.deferred/.session/.deferred_attempts alongside .done). Exit
#     clean and clear those stale markers so select-phase stops re-selecting it.
if [ -f "${DONE_FILE}" ]; then
  echo "== [phase] ${PHASE} already DONE — skipping re-run, clearing stale failure markers =="
  rm -f "${DEFERRED_FILE}" "${SESSION_FILE}" "${BLOCKED_FILE}" "${ATTEMPTS_FILE}" "${DEFERRED_ATTEMPTS_FILE}" "${NOWORK_FILE}"
  exit 0
fi

# Snapshot the tree BEFORE the run so the gate only counts this run's delta.
WORK_BEFORE="$(tree_work)"

if run_phase; then
  if ! git_available; then
    # No git repo to diff against (should not happen in CI) — don't trust the
    # gate, but flag that evidence could not be verified.
    echo "== [phase] WARNING: git unavailable — evidence gate skipped (verify ${PHASE} manually) =="
    touch "${DONE_FILE}"
    rm -f "${DEFERRED_FILE}" "${SESSION_FILE}" "${BLOCKED_FILE}" "${ATTEMPTS_FILE}" "${DEFERRED_ATTEMPTS_FILE}" "${NOWORK_FILE}"
    exit 0
  fi

  if has_new_work "${WORK_BEFORE}"; then
    echo "== [phase] SUCCESS + evidence gate passed: ${PHASE} left working-tree changes =="
    touch "${DONE_FILE}"
    rm -f "${DEFERRED_FILE}" "${SESSION_FILE}" "${BLOCKED_FILE}" "${ATTEMPTS_FILE}" "${DEFERRED_ATTEMPTS_FILE}" "${NOWORK_FILE}"

    # Fix 9: do NOT run review inline here — the workflow commits the phase work
    # first, then invokes this script again with --review-only. That way the phase
    # is safely pushed even if the review step (or the whole job) times out.
    exit 0
  fi

  # opencode exited 0 but left no evidence of work. Refuse to mark DONE; fall
  # through to the failure classification so the attempt cap applies and the
  # pipeline re-selects the phase on a later tick instead of moving on.
  echo "== [phase] NO-WORK FAILURE: opencode exited 0 but ${PHASE} left no changes" \
       "outside logs/ + phase markers =="
  echo "== [phase] NOT marking done; counting as a failed attempt (cap ${MAX_ATTEMPTS}) =="
  touch "${NOWORK_FILE}"
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
