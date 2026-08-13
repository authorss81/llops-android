# InkFlow: Device Compatibility & Fallbacks Matrix

InkFlow is designed with high performance, security, and portability in mind. Because we target Android API level 26+ (Android 8.0), our features gracefully degrade and fall back to maintain accessibility and stability across various hardware tiers and operating system versions.

---

## 📱 1. Device Hardware Tiers

We classify devices into 3 distinct performance tiers. Heavily compute- or render-intensive operations are gated dynamically based on these tiers, but the user may manually override these detections under **Settings & More -> Device Compatibility**.

| Performance Tier | Hardware Profiles / Heuristics | Default Capabilities & Actions |
| :--- | :--- | :--- |
| **Low-End** | <ul><li>≤ 2 CPU Cores</li><li>≤ 3.0 GB Total System RAM</li><li>`ActivityManager.isLowRamDevice` = true</li></ul> | <ul><li>AGSL Wet Mixing disabled by default</li><li>Standard 2D vector fallback rendering for watercolor and oil brushes</li><li>Heavy animations reduced</li></ul> |
| **Mid-Range** | <ul><li>Between Low-End and Flagship specifications</li></ul> | <ul><li>All standard features enabled</li><li>Standard layout configuration</li></ul> |
| **Flagship** | <ul><li>> 6 CPU Cores</li><li>> 6.0 GB Total System RAM</li></ul> | <ul><li>Max performance profiles</li><li>High-density rendering enabled</li><li>Advanced shading enabled</li></ul> |

---

## ⚙️ 2. Feature Capability & OS Fallbacks

The following table lists major hardware-dependent or OS-dependent features and how they degrade gracefully on older platforms or restricted hardware.

| Feature Name | Minimum Requirements | Low-End / Older OS Fallback Behavior |
| :--- | :--- | :--- |
| **AGSL Wet-Mix Shaders** | Android 13+ (API 33) AND Mid/Flagship Tier | Falls back to high-fidelity **2D Vector drawing** and basic composting, avoiding expensive pixel-blending shaders. |
| **Dynamic Color (Material You)** | Android 12+ (API 31) | Falls back automatically to beautifully tuned static palettes (**Sepia**, **Light**, **Dark**, **Amoled**). |
| **Hardware Bitmaps** | Android 8.0+ (API 26) | Uses standard software-backed `Bitmap` instances with automatic garbage collection when out of scope. |
| **Adaptive 3-Pane Navigation** | Screen Width ≥ 600dp | Automatically collapses to a clean **Single-Pane navigation flow** tailored for compact mobile viewports. |
| **Edge-to-Edge Drawing** | Android 10+ (API 29+) | Standard status and navigation bar paddings are added on API 26-29 platforms to prevent overlap. |
| **System Splash Screen API** | Android 12+ (API 31+) | Falls back to classic `values` XML styles and a high-performance Pre-Splash placeholder theme. |

---

## 🧪 3. SDK Test Matrix

We test and verify our fallbacks and core operations across major Android API levels using Robolectric JVM testing, Android Lint auditing, and visual screenshot validations:

*   **API 26 (Android 8.0 - Oreo)**: Verified standard database, cryptography layer, 2D vector pathing, and backup/restore paths.
*   **API 29 (Android 10 - Q)**: Verified edge-to-edge drawing, basic clipboard security, and light/dark theme switching.
*   **API 31 (Android 12 - S)**: Verified Dynamic Color palettes, splash screen rendering, and Biometric prompt capabilities.
*   **API 33 (Android 13 - Tiramisu)**: Verified AGSL Wet-Mix shader loading and high-fidelity brush rendering.
*   **API 34/35 (Android 14/15)**: Full flagship validation of parallel file handling, hardware rendering loops, and scoped storage access.
