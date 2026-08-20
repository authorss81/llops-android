# Dynamic Testing on Kali — Fastbot + Emulator (free, open-source)

Free, no-paid-tier runtime bug hunting for **InkFlow/Noteflow** from Kali Linux.
Goal: find crashes / ANRs / functional bugs that only appear at runtime.

- Package (applicationId): `com.aistudio.inkflow.app.bkxjrz`
- Debug APK artifact (GitHub Actions `release.yml`): name `noteflow-apk`, path
  `app/build/outputs/apk/debug/app-debug.apk`
- Release APK artifact: `noteflow-release-apk` (do NOT fuzz this — FLAG_SECURE +
  fail-closed vault make dynamic analysis painful; debug build is the right target).

> Always fuzz the **debug** build. Release builds set FLAG_SECURE (screenshots go
> black, Fastbot still works but evidence is weaker) and the vault's fail-closed
> lock fights the fuzzer on password vaults.

---

## 1. One-time Kali setup

```bash
# JDK + Android SDK command-line tools
sudo apt update && sudo apt install -y openjdk-17-jre-headless unzip wget

# cmdline-tools (accept licenses non-interactively)
mkdir -p ~/android-sdk/cmdline-tools
wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-11076708_latest.zip -d ~/android-sdk/cmdline-tools
mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest
export ANDROID_HOME=~/android-sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
yes | sdkmanager --licenses >/dev/null 2>&1

# SDK + emulator + system image (API 33 x86_64, google_apis for screenshots)
sdkmanager "platform-tools" "emulator" "platforms;android-33" \
  "build-tools;33.0.2" "system-images;android-33;google_apis;x86_64"

# Create AVD
avdmanager create avd -n inkflow -k "system-images;android-33;google_apis;x86_64" \
  -d "pixel_6" --force
```

## 2. Get the debug APK (from CI, no local Gradle needed)

```bash
# From GitHub CLI (Kali) — download the debug artifact
gh auth login                      # or pre-set GH_TOKEN
gh run download --repo authorss81/llops-android --name noteflow-apk -D ./apk
# => ./apk/app-debug.apk
```

## 3. Boot the emulator (headless — fine for Athlon 200GE / 7.5 GB RAM)

```bash
$ANDROID_HOME/emulator/emulator -avd inkflow -no-window -no-audio \
  -gpu swiftshader_indirect -no-snapshot -no-boot-anim \
  -memory 2048 -cores 2 &
adb wait-for-device
adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 2; done'
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
```

## 4. Install + prime the vault

```bash
adb install -r ./apk/app-debug.apk
# Launch once, create a passwordless vault (or a vault you can unlock manually),
# open the editor so the fuzzer starts inside the app:
adb shell am start -n com.aistudio.inkflow.app.bkxjrz/.MainActivity
```

> Fastbot cannot unlock a password vault. For fuzzing, use a passwordless vault or
> unlock manually before starting, then do NOT let the app lock mid-run.

## 5. Fastbot (recommended) — model-based fuzzer

```bash
# Clone (MIT license, free)
git clone https://github.com/bytedance/fastbot_android.git
cd fastbot_android

# Push Fastbot to the emulator
adb push monkey/build/libs/monkeyq.jar /sdcard/monkeyq.jar
adb push fastbot-thirdpart.jar /sdcard/fastbot-thirdpart.jar
adb push framework.jar /sdcard/framework.jar
adb push libs/x86_64/* /data/local/tmp/

# Optional: improve the model with your UI strings
$ANDROID_HOME/build-tools/33.0.2/aapt2 dump --values strings ./apk/app-debug.apk > /sdcard/max.valid.strings

# Run 30 minutes (throttle 300ms). Crash/ANR/oom logs go to /sdcard/crash-dump.log
adb shell CLASSPATH=/sdcard/monkeyq.jar:/sdcard/framework.jar:/sdcard/fastbot-thirdpart.jar \
  exec app_process /system/bin com.android.commands.monkey.Monkey \
  -p com.aistudio.inkflow.app.bkxjrz \
  --agent reuseq --running-minutes 30 --throttle 300 -v -v \
  --bugreport --output-directory /sdcard/fastbot-out

# Pull results
adb pull /sdcard/crash-dump.log ./fastbot-crash-dump.log
adb pull /sdcard/fastbot-out ./fastbot-out
```

### Reading results

- `crash-dump.log` — Java crashes, native crashes, ANRs, OOM. Search for your
  package's frames (`com.aistudio.inkflow` / `com.authorss81`) to filter real
  bugs from fuzzer-induced framework noise.
- Each crash line: timestamp + exception class + stack. Turn the top candidate
  into a new `workspace/phase-NN/` prompt (reproducer + expected fix).

## 6. Quick alternative — Android Monkey (zero setup)

```bash
# 50k events, reproducible seed, keep going after crashes to collect all of them
adb shell monkey -p com.aistudio.inkflow.app.bkxjrz \
  -s 42 --throttle 300 --ignore-crashes --ignore-timeouts -v 50000 2>&1 | tee monkey.log
grep -iE "CRASH|ANR|FATAL" monkey.log
```

## 7. Logcat monitoring while fuzzing

```bash
adb logcat -c
# ...start fuzzing in another terminal...
adb logcat -v threadtime 2>&1 | tee logcat-run.log
# After the run: uncaught exceptions / ANR blocks mentioning your package
grep -E "FATAL EXCEPTION|ANR in com.aistudio" logcat-run.log
```

Note: this app's `FailureLogPolicy` logs class names only (never throwables), so
use logcat to find *uncaught* exceptions — caught-and-logged failures are handled
deliberately and are not bugs.

## 8. Tier-2 free options (only if needed)

- **DroidBot** (`github.com/honeynet/droidbot`, free) — GUI-model explorer, no
  root/instrumentation, good when Fastbot's ML model fights the custom Compose
  canvas. `pip install droidbot` then `droidbot -a apk/app-debug.apk -o out`.
- **MobSF** (`github.com/MobSF/Mobile-Security-Framework-MobSF`, GPL, Kali docs) —
  static + dynamic (Frida) for security checks: exported components, WebView,
  crypto monitoring, clipboard leaks.
- **Frida + Medusa / apkAnalyzer** (free) — runtime hooks. Only pursue for the
  crypto/lock threat model: verify `EncryptionService`/`lock()` zeroize the DEK
  and never write plaintext. Requires a rooted emulator/device.

## Security-policy guardrails

- Fuzz the **debug** APK only. Never use Frida to defeat the app's own security
  gates in a way that violates `AGENTS.md` (never log keys/decrypted content).
- Findings go into the standard flow: write to `docs/pentest-findings-<date>.md`
  incrementally, then create `workspace/phase-NN/` with file:line verification.

## Fastest feedback loop

1. Monkey on every CI debug build (runs in the emulator job) — 50k events, fixed
   seed = build number.
2. Fastbot overnight on release candidates — model-based deep exploration.
3. Triage: crash-dump.log / monkey.log → `phase-NN` prompt → pipeline fixes it.