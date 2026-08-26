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
# OPENCODE_MODEL accepts a SINGLE model id or a COMMA-SEPARATED FALLBACK CHAIN
# (e.g. "provider/a,provider/b:free"). Entries are tried one at a time via
# --model — the raw list is NEVER passed to opencode verbatim (that caused the
# phase-175/176 "Model not found" incident). On model-level failures (unknown
# id, rate limit, provider outage) the runner advances to the next entry.
MODEL_LIST="${OPENCODE_MODEL:-openrouter/nvidia/nemotron-3-super-120b-a12b:free,opencode/nemotron-3-ultra-free}"
MODELS=()
while IFS=',' read -r -a _RAW_MODELS; do
  for _m in "${_RAW_MODELS[@]}"; do
    _m="${_m#"${_m%%[![:space:]]*}"}"   # ltrim
    _m="${_m%"${_m##*[![:space:]]}"}"   # rtrim
    if [ -n "${_m}" ]; then MODELS+=("${_m}"); fi
  done
done <<< "${MODEL_LIST}"
if [ "${#MODELS[@]}" -eq 0 ]; then
  MODELS=("opencode/nemotron-3-ultra-free")
fi
MODEL="${MODELS[0]}"   # first entry = default for anything still referencing $MODEL
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
log_is_rate_limited() {
  grep -qiE "HTTP[ /]?429|429[^0-9]|too many requests|rate[ _-]?limit(ed| exceeded)?|insufficient[ _-]?quota|quota exceeded|(per[ -]?minute|per[ -]?day).*(limit|exceeded)" \
    "$1" 2>/dev/null && return 0
  return 1
}

is_rate_limited() {
  log_is_rate_limited "${LOG_DIR}/${PHASE}.log"
}

# True when the log shows opencode could not resolve/use THIS model at all
# (unknown id, provider-side outage) — safe signals for advancing the chain.
log_is_model_error() {
  grep -qiE "model not found|unknown model|invalid model|no such model|unexpected server error|upstream error|service temporarily overloaded|temporarily overloaded|streaming response failed|502|503|504|overloaded" \
    "$1" 2>/dev/null && return 0
  return 1
}

ACTIVE_MODEL=""
# Run `opencode run` once per entry of MODELS until one succeeds. $1 = log file
# (output APPENDED with a per-model banner so classification still sees earlier
# models' rate-limit markers); remaining args are passed to opencode verbatim.
# Advances the chain ONLY on model-level failures (unknown id / rate limit /
# provider outage / zero output). A real work failure (agent ran but exited
# non-zero) returns immediately — re-running the whole task with another model
# inside one tick would double-spend minutes; the attempt cap handles retries.
run_models() {
  local logfile="$1"; shift
  local m code pre post growth
  : > "${logfile}"
  for m in "${MODELS[@]}"; do
    echo "== [models] trying ${m} ==" >> "${logfile}"
    pre="$(wc -c < "${logfile}" 2>/dev/null || echo 0)"
    # Fix stdin-consumption bug: ctx was piped via outer `< ctx` which is exhausted after first model.
    # Re-open the phase ctx file per-model attempt so fallback chain can actually retry.
    if [ -n "${PHASE:-}" ] && [ -f "${LOG_DIR}/${PHASE}.ctx" ]; then
      opencode run --model "${m}" "$@" < "${LOG_DIR}/${PHASE}.ctx" >> "${logfile}" 2>&1
    else
      opencode run --model "${m}" "$@" >> "${logfile}" 2>&1
    fi
    code=$?
    post="$(wc -c < "${logfile}" 2>/dev/null || echo 0)"
    growth=$((post - pre))
    if [ "${code}" -eq 0 ]; then
      ACTIVE_MODEL="${m}"
      echo "== [models] ${m} succeeded =="
      return 0
    fi
    if log_is_rate_limited "${logfile}" || log_is_model_error "${logfile}" || [ "${growth}" -lt 200 ]; then
      echo "== [models] ${m} unusable (exit ${code}) — advancing fallback chain =="
      continue
    fi
    echo "== [models] ${m} failed with a real work error (exit ${code}) — keeping result =="
    ACTIVE_MODEL="${m}"
    return "${code}"
  done
  echo "== [models] every model in the fallback chain was skipped/unusable =="
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

# Build the injected context header so every phase starts oriented (no cold start).
# KEPT COMPACT on purpose: the agent has file tools and must read the big docs
# itself. Dumping the full ARCHITECTURE.md (1181 lines) + phase-status.md into
# every ctx made logs grow ~10 lines per phase and burned tokens. This header is
# a short orientation + pointers; the agent reads the docs on demand.
build_context_header() {
  {
    echo "# PIPELINE CONTEXT (injected by phase_runner.sh — do not delete)"
    echo ""
    echo "## Hard rules (AGENTS.md)"
    if [ -f AGENTS.md ]; then
      sed -n '/^## Hard rules/,/^## /p' AGENTS.md | head -n 120
    fi
    echo ""
    echo "## Orientation (read these files yourself — do NOT rely on memory)"
    echo "- docs/ARCHITECTURE.md = living architecture map (package layout, core"
    echo "  subsystem file:line anchors, build/CI essentials, gotchas). READ IT."
    echo "- docs/phase-status.md = phase status truth table. READ IT, then UPDATE"
    echo "  your phase's row when you finish."
    echo "- workspace/PHASES.md = full phase table."
    echo "- workspace/SECURITY_FIX_PLAN.md + docs/security-report*.md = security"
    echo "  findings status (only if your phase touches security)."
    echo ""
    if [ -f "${PHASE_DIR}/.checkpoint" ]; then
      echo "## CONTINUATION MODE"
      echo "A previous run started this phase and its partial work was already"
      echo "committed and pushed (see the working tree + prior commits). DO NOT"
      echo "start over: inspect what is already done, CONTINUE from the current"
      echo "state, refine it, and finish the phase. If the phase is already"
      echo "complete in the tree, verify it and mark it done."
      echo ""
    fi
    echo "## Your task"
    echo "Execute the phase described in ${PROMPT_FILE}. Read that file now and"
    echo "complete it fully. The context above is orientation only."
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

# True when the agent committed its own work during the run (it pushes phase
# work itself, leaving the tree clean — that is evidence, not a no-op). Only
# count commits that touched real files; empty or log/marker-only commits do
# not count, so the gate cannot be gamed with a trivial commit.
has_new_commits() {
  local before="$1"
  [ -n "${before}" ] || return 1
  [ "${before}" = "$(git rev-parse HEAD 2>/dev/null)" ] && return 1
  git diff --name-only "${before}"..HEAD 2>/dev/null \
    | grep -vE "^logs/|^workspace/${PHASE}/\.|^workspace/\.[^/]*($|/)" | grep -q .
}

git_available() {
  git rev-parse --git-dir >/dev/null 2>&1
}

# --- Survive-cancellation checkpoint machinery (Fix 12) ------------------------
# A job timeout/cancel kills the VM mid-run and erases everything done since the
# last push. To make partial work survive, a background loop snapshots the
# working tree every CHECKPOINT_INTERVAL_SECONDS into a commit on a dedicated
# WIP branch and force-pushes it. The next tick merges that branch back and the
# agent CONTINUES refining (it does not restart). The WIP branch is deleted once
# the phase reaches DONE.
CHECKPOINT_INTERVAL="${CHECKPOINT_INTERVAL_SECONDS:-300}"
WIP_BRANCH="llops-wip/${PHASE}"

# Snapshot real working-tree changes into a commit on the WIP branch and
# force-push it. The main branch is never touched. Empty/log-only states are
# skipped so we don't spam commits.
checkpoint_wip() {
  if ! git status --porcelain 2>/dev/null \
       | grep -vE '^\?\? (logs/|workspace/\.)' | grep -q .; then
    return 0
  fi
  git add -A 2>/dev/null || true
  local tree commit base
  tree="$(git write-tree 2>/dev/null)" || return 0
  base="$(git rev-parse HEAD 2>/dev/null || echo HEAD)"
  commit="$(git commit-tree "${tree}" -p "${base}" -m "llops: ${PHASE} checkpoint $(date -u +%s)" 2>/dev/null)" || return 0
  if git push origin "${commit}:refs/heads/${WIP_BRANCH}" --force 2>/dev/null; then
    echo "== [checkpoint] WIP pushed: ${WIP_BRANCH} @ ${commit:0:8} =="
  fi
  git reset -q 2>/dev/null || true   # unstage; keep files in the working tree
}

# Loop while an opencode run is active. Killed by the caller on exit.
checkpoint_loop() {
  while true; do
    sleep "${CHECKPOINT_INTERVAL}"
    checkpoint_wip
  done
}

# Merge prior partial work back into the tree so this run CONTINUES the phase
# instead of starting from scratch. Marks the phase as continued via .checkpoint.
resume_wip() {
  if git ls-remote --exit-code origin "refs/heads/${WIP_BRANCH}" >/dev/null 2>&1; then
    echo "== [phase] continuing from prior partial work (${WIP_BRANCH}) =="
    git fetch origin "${WIP_BRANCH}" 2>/dev/null || true
    git merge --no-edit FETCH_HEAD 2>/dev/null || true
    git push origin main 2>/dev/null || true
    touch "${PHASE_DIR}/.checkpoint"
  fi
}

# Drop the WIP branch once the phase is finished.
clear_wip() {
  git push origin --delete "${WIP_BRANCH}" 2>/dev/null || true
  rm -f "${PHASE_DIR}/.checkpoint"
}

run_phase() {
  echo "== [phase] Running: ${PHASE} =="
  resume_wip
  build_context_header
  set +e
  # Keep the full ctx+PROMPT as a committed audit record (logs/<phase>.prompt),
  # but send ONLY the compact context header over stdin. The header's "Your task"
  # line points the agent at workspace/<phase>/PROMPT.md, which the agent reads
  # itself. This keeps the arg/context footprint small as docs grow.
  {
    cat "${LOG_DIR}/${PHASE}.ctx"
    cat "${PROMPT_FILE}"
  } > "${LOG_DIR}/${PHASE}.prompt"
  checkpoint_loop &
  local CHECK_PID=$!
  run_models "${LOG_DIR}/${PHASE}.log" \
    --agent build \
    "${SESSION_ARGS[@]}" \
    --title "llops-${PHASE}"
  local code=$?
  kill "${CHECK_PID}" 2>/dev/null || true
  set -e
  if [ -n "${ACTIVE_MODEL}" ]; then
    echo "== [phase] model used: ${ACTIVE_MODEL} =="
  fi
  # Stale-session recovery: opencode stores sessions on the CI VM's local disk,
  # which is wiped between runs. A `.session` marker committed by an earlier tick
  # points at an ID that no longer exists -> "Session not found". Clear the stale
  # marker and return a retryable-env code (5, not counted as a phase failure) so
  # the next tick runs the phase fresh instead of burning attempts on a dead ID.
  if [ "${code}" -ne 0 ] && grep -qi "Session not found" "${LOG_DIR}/${PHASE}.log"; then
    echo "== [phase] STALE SESSION (${SESSION_FILE}): 'Session not found' - clearing marker, will retry fresh =="
    rm -f "${SESSION_FILE}"
    return 5
  fi
  return "${code}"
}

# Review + fix the phase's changes. Used by --review (after phase success) and
# by --review-only (on a later tick, after the phase work was already pushed).
run_review() {
  echo "== [review] Running reviewer subagent =="
  set +e
  run_models "${LOG_DIR}/${PHASE}.review.log" --agent "${REVIEWER_AGENT}" \
    "Review all changes made in phase '${PHASE}'. Output numbered FINDINGS."
  code=$?
  set -e
  echo "== [review] exit: ${code} =="

  if grep -qiE "FINDINGS:[[:space:]]*[0-9]+|^[[:space:]]*[0-9]+\." "${LOG_DIR}/${PHASE}.review.log"; then
    echo "== [fix] Applying fixes for review findings =="
    set +e
    checkpoint_loop &
    local CHECK_PID=$!
    run_models "${LOG_DIR}/${PHASE}.fix.log" --agent build \
      --continue \
      "Apply fixes for the review FINDINGS above. Do not break other code. After applying every fix, commit and push them yourself: git add -A; git commit -m 'llops: ${PHASE} review fixes'; git push (pull --rebase on rejection). If the working tree is clean, push nothing."
    code=$?
    kill "${CHECK_PID}" 2>/dev/null || true
    set -e
    echo "== [fix] exit: ${code} =="
  fi

  # Safety net: if the fix agent committed but could not push (or left
  # uncommitted fixes behind), commit and push them here so a job timeout
  # after this point can never lose the review work.
  if [ "$(git status --porcelain 2>/dev/null | grep -vE '^\?\? (logs/|workspace/\.)' | wc -l)" -gt 0 ]; then
    echo "== [review] committing + pushing review fixes (safety net) =="
    git config user.name "llops-bot"
    git config user.email "llops-bot@users.noreply.github.com"
    git add -A
    git commit -m "llops: ${PHASE} review fixes" 2>/dev/null || true
    PUSHED=0
    for i in 1 2 3 4 5; do
      if git push origin main 2>/dev/null; then
        echo "== [review] review fixes pushed (attempt $i) =="
        PUSHED=1
        break
      fi
      git pull --rebase origin main 2>/dev/null || true
      sleep 3
    done
    if [ "${PUSHED}" = "0" ]; then
      echo "== [review] WARNING: review fixes could not be pushed after 5 attempts =="
      git branch -f "llops-recovery/${PHASE}-review" HEAD 2>/dev/null || true
    fi
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
HEAD_BEFORE="$(git rev-parse HEAD 2>/dev/null || true)"

if run_phase; then
  if ! git_available; then
    # No git repo to diff against (should not happen in CI) — don't trust the
    # gate, but flag that evidence could not be verified.
    echo "== [phase] WARNING: git unavailable — evidence gate skipped (verify ${PHASE} manually) =="
    touch "${DONE_FILE}"
    rm -f "${DEFERRED_FILE}" "${SESSION_FILE}" "${BLOCKED_FILE}" "${ATTEMPTS_FILE}" "${DEFERRED_ATTEMPTS_FILE}" "${NOWORK_FILE}"
    exit 0
  fi

  if has_new_work "${WORK_BEFORE}" || has_new_commits "${HEAD_BEFORE}"; then
    echo "== [phase] SUCCESS + evidence gate passed: ${PHASE} left working-tree changes =="
    touch "${DONE_FILE}"
    rm -f "${DEFERRED_FILE}" "${SESSION_FILE}" "${BLOCKED_FILE}" "${ATTEMPTS_FILE}" "${DEFERRED_ATTEMPTS_FILE}" "${NOWORK_FILE}"
    clear_wip

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
else
  # run_phase failed. Retryable infra errors (exit 5) must NOT count against the
  # attempt cap and must not touch failure markers — the next tick just retries.
  RUN_CODE=$?
  if [ "${RUN_CODE}" = "5" ]; then
    echo "== [phase] RETRYABLE INFRA ERROR (exit 5): ${PHASE} — not counted, will retry =="
    exit 5
  fi
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
