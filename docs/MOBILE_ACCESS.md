# Accessing the LLOPS Pipeline from Your Phone

Two distinct things you can access from mobile:

1. **Monitor/trigger the GitHub Actions pipeline** (the AI building your app).
2. **Drive a persistent opencode session interactively** (optional).

---

## Option A — Monitor & trigger from mobile (recommended, zero setup)

Your pipeline runs on **GitHub's cloud**, so your phone just needs to talk to GitHub.

### Install the GitHub mobile app
- **Android**: GitHub app from Play Store.
- **iOS**: GitHub app from App Store.

### What you can do
- **Watch pipeline status**: Repo → **Actions** tab → tap the running "llops" workflow.
- **See logs**: Tap any job → view the live output (which phase, rate-limit waits, review results).
- **Trigger a run**: Actions → llops → "Run workflow" → pick the phase. Great for "retry" or "start next phase" from bed.
- **Get notifications**: Enable notifications for repo workflows so you're pinged when a phase passes/fails/deferred.

This requires **no extra setup** — it's all GitHub's existing mobile experience.

---

## Option B — Interactive opencode session on your own machine + tunnel

If you want to *chat with opencode / approve permission prompts from your phone*
(like a remote dev loop separate from CI), run opencode in **web/serve mode** on
a persistent machine and reach it via a tunnel. Works on Codespaces, a VPS, or
even your PC left running.

### B1. Start the headless server + web UI
```bash
# on the machine that owns the project
opencode serve --port 4096 --hostname 0.0.0.0
# or
opencode web   --port 4096 --hostname 0.0.0.0   # opens browser UI
```

### B2. Expose it securely (choose ONE)

**With Cloudflare Tunnel (free, no account-churn):**
```bash
# install cloudflared, then:
cloudflared tunnel --url http://localhost:4096
# prints a public https://<random>.trycloudflare.com URL — open on your phone
```

**With Tailscale (more private, recommended):**
```bash
# install tailscale on the machine AND on your phone, same account
# then phone just opens http://<machine-tailscale-ip>:4096
```

**With basic auth for safety:**
```bash
export OPENCODE_SERVER_PASSWORD="your-strong-password"
opencode serve --port 4096 --hostname 0.0.0.0
```

### B3. Use it on Android
- **OpenCode Mobile app** (`getopencode.app`, community Android beta): enter the
  server URL + credentials → start/inspect/stop sessions, terminal, voice input.
- **Or simply a browser** pointing at your tunnel URL (works on any phone).

---

## Which to use?

| Your goal | Use |
|-----------|-----|
| Just watch the AI build your app & retry phases | **Option A** (GitHub app) — nothing to install |
| Approve permission prompts / interactive chat from phone | **Option B** + Conduit/OpenCode-Mobile |
| Get push notifications for permission approvals | **B + Conduit** (`npx conduit-code`) |
| Private, no public ports | **B + Tailscale** |

---

## Recommended combo for mobile
1. **GitHub app** → monitor the pipeline (Option A).
2. When you want interactive control, open a **Codespace** on your phone browser
   or use the **OpenCode Mobile** app pointed at a `cloudflared` tunnel.

That gives you: full visibility into every phase, plus hands-on control, all from
your phone.
