# LLOPS Android — Autonomous Android Dev Pipeline

Build an Android app automatically using **DeepSeek free models** running through
**opencode**, executing in **GitHub Actions**, one phase at a time, with subagent
review and automatic continuation after rate limits.

---

## Architecture

```
GitHub repository
   │
   ├─ .github/workflows/llops.yml      ← the orchestrator
   ├─ scripts/phase_runner.sh          ← phase execution + 1-hour retry logic
   ├─ opencode.json                    ← DeepSeek free model + agents
   ├─ .opencode/agent/reviewer.md      ← review subagent
   └─ workspace/phase-NN/PROMPT.md     ← the work instructions per phase
```

**Flow per phase:**
1. On every tick (manual run or cron, every 30 min), a cheap `select-phase`
   job scans `workspace/` and picks the next phase.
2. `phase_runner.sh` runs `opencode run "<PROMPT.md>"` on DeepSeek V4 Flash Free
   via **OpenCode Zen**.
3. Success → phase marked `.done` → `reviewer` subagent reviews → fixes applied →
   commit + push.
4. Rate-limit hit → phase marked `.deferred`, exits fast (exit 42) → the **next
   cron tick** (≤30 min later) retries the same deferred phase, resuming the
   saved session via `--session`.
5. No pending/deferred phases → workflow exits idle using ~0 minutes.

---

## How "auto-continue after rate limit" works (fixed)

Old design had the script `sleep 3600` 8 times in-job — that (a) hit the job's
2-hour timeout mid-sleep, and (b) burned paid/free Actions minutes while doing
nothing. Now retries are **cron-driven**, not sleeps:

```
cron every 30 min
  → select-phase: is any phase marked .deferred?
      yes → run it (resume via saved .session id) → if still limited → re-defer
      no  → pick lowest phase without .done → run it → mark done/advance
      none at all → idle job, ~0 minutes
```

- `phase_runner.sh` NEVER sleeps. It marks `.deferred` and exits 42 fast.
- Every 30 minutes the schedule wakes, checks the marker, and retries.
- This works **indefinitely** (until free tier itself ends) without burning an
  hour of minutes per stall — the waiting happens between runs, not in one.
- Resuming: the script saves the last session id to `workspace/<phase>/.session`
  and passes `--session <id>` on the retry so context is preserved.

---

## Getting it running — full step-by-step

### 1. Create the repo
```bash
# In GitHub, create a new empty repository named e.g. "llops-android"
cd C:\Users\USER\Documents\Default Project\llops-android
git init
git add -A
git commit -m "llops: initial pipeline scaffold"
git branch -M main
git remote add origin https://github.com/<YOU>/llops-android.git
git push -u origin main
```

### 2. Get an OpenCode Zen key
1. This pipeline uses **OpenCode Zen** (opencode's own model gateway) with the
   **DeepSeek V4 Flash Free** model (`opencode/deepseek-v4-flash-free`) —
   a current, promo free tier. Do **not** use OpenRouter for this; the old
   `openrouter/...:free` DeepSeek listings are dead (paid-only now).
2. In opencode, run `/connect` → select **opencode** → open https://opencode.ai/auth
   → sign in → copy the API key.

### 3. Add the secret to GitHub
1. Repo → **Settings → Secrets and variables → Actions**.
2. Add repository secret **`OPENCODE_API_KEY`** = the key from step 2.

### 4. Run phase 1 (and it keeps going)
1. Repo → **Actions** → **llops** → **Run workflow** (manual `workflow_dispatch`).
2. It runs phase-01 with DeepSeek V4 Flash Free, reviews, fixes, and pushes.
3. The **`*/30 * * * *` cron schedule** then re-fires automatically: it advances
   to the next phase on success, or retries a deferred phase on rate limit. You
   don't need to touch it.
4. To force a specific phase: Run workflow → set the `phase` input.

### 5. Add future phases
1. Create `workspace/phase-02/PROMPT.md` (see `workspace/PHASES.md` for the
   phase plan and prompt style).
2. Push it. The cron picks it up automatically.

---

## Skipping / retrying

| Situation | What happens | What to do |
|-----------|-------------|------------|
| Rate limit mid-phase | Phase marked deferred, exits fast | Nothing — cron retries ≤30 min later, indefinitely |
| Limit still exhausted | Stays deferred, retried each cron tick | Nothing, or check your Zen key limits |
| Real code bug | Stops with log, no deferral | Read `logs/phase-NN.log`, fix PROMPT.md, re-run |
| Reviewer found issues | Auto-fix pass runs before commit | Nothing |
| Want to force a phase | `workflow_dispatch` with `phase: phase-03` input | Set the input |

---

## Requirements summary
- GitHub repo (free tier: unlimited public Actions; private ~500-2000 min/mo).
- **OpenCode Zen** API key (secret `OPENCODE_API_KEY`) — DeepSeek V4 Flash Free.
- Java 21 + Android SDK auto-installed on the runner (workflow already handles it).
- No local Android SDK needed — everything runs on GitHub's Ubuntu runner.

---

## Files
```
llops-android/
├─ opencode.json                 ← DeepSeek V4 Flash Free via OpenCode Zen
├─ .opencode/agent/reviewer.md   ← review subagent prompt
├─ scripts/phase_runner.sh       ← phase runner (.done/.deferred markers, no sleep)
├─ .github/workflows/llops.yml   ← orchestrator + cron retry engine
├─ workspace/
│  ├─ PHASES.md                  ← the whole app build plan
│  └─ phase-01/PROMPT.md         ← phase 1 instructions
└─ docs/MOBILE_ACCESS.md         ← how to watch/control from your phone
```