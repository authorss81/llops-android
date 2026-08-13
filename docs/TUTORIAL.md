# InkFlow: Complete User Guide, Feature Reference & Aesthetic Manual

Welcome to **InkFlow**! InkFlow is a premier, offline-first, private-by-design canvas and document annotation notebook built native for Android with Kotlin and Jetpack Compose.

This manual serves as your comprehensive tutorial, feature catalog, and design reference. It details all user-facing interactions, advanced drawing systems, security protocols, and visual aesthetic concepts of the app.

---

## 📖 Table of Contents
1. [🎨 Canvas & Drawing Engines](#1-canvas--drawing-engines)
2. [🖌️ Advanced Brushes & GPU Shaders](#2-advanced-brushes--gpu-shaders)
3. [🥞 Layers & Compositing System](#3-layers--compositing-system)
4. [🎙️ Time-Synced Voice Notes](#4-time-synced-voice-notes)
5. [🔗 Wikilinks & Interactive Knowledge Graph](#5-wikilinks--interactive-knowledge-graph)
6. [🛡️ Encrypted Vault, Biometrics & Security](#6-encrypted-vault-biometrics--security)
7. [📂 Document Annotation & Media Imports](#7-document-annotation--media-imports)
8. [💾 Backups & Export Pipeline](#8-backups--export-pipeline)
9. [🌈 Theme, Personalization & UI Philosophy](#9-theme-personalization--ui-philosophy)

---

## 🎨 1. Canvas & Drawing Engines

InkFlow features a high-performance rendering engine that allows for seamless handwriting, sketching, and annotation. You can choose between two main canvas layouts designed for different workflows:

### A. Infinite Canvas (Continuous Mode)
An open, boundary-free space that expands dynamically in all directions as you draw.
*   **Best for**: Sprawling mind-maps, rapid brainstorming, wireframing, and unconstrained sketching.
*   **Gestures**: Use a standard two-finger pinch to zoom in/out (from **0.5x to 4.0x**) and a two-finger drag to pan in any direction.
*   **Technical Detail**: The rendering loop runs on a highly optimized vector pipeline. To prevent state recomposition lag during rapid pan/zoom gestures, the transform states are offloaded from standard Compose state triggers and applied directly inside the GPU rendering phase (`graphicsLayer` lambda transforms).

### B. Page-by-Page Mode
Locks drawing boundaries to standard sheet dimensions (e.g., A4, Letter, or custom PDF page bounds).
*   **Best for**: Structured journaling, lecture notes, textbook annotations, and print-ready exporting.
*   **Navigation**: Swipe left or right to move between pages sequentially, or use the visual page grid at the bottom of the screen.

```
+--------------------------------------------------------+
|  [Pen]  [Highlighter]  [Eraser]  [Layers]  [More]  💧  |
+--------------------------------------------------------+
|                                                        |
|        Page-by-Page Canvas View (Standard Bounds)       |
|                                                        |
|        [ [Page 1] ]  ----> Swipe ----> [ [Page 2] ]    |
|                                                        |
+--------------------------------------------------------+
|  [ < Prev Page ]                   Page 1 of 5 [ Next > ] |
+--------------------------------------------------------+
```

---

## 🖌️ 2. Advanced Brushes & GPU Shaders

Every stroke in InkFlow is treated as a mathematically defined vector path, capturing precise pressure, speed, and timing info. Our engine dynamically selects your brush performance tier:

### The Brush Catalog
1.  **Classic Pen**: A crisp, pressure-responsive solid line perfect for general handwriting.
2.  **Highlighter**: Semi-transparent, wide stroke with square cap parameters. Ideal for calling attention to text without obscuring underlying handwriting or PDF elements.
3.  **Calligraphy Brush**: Angle-sensitive stroke that mimics a traditional fountain pen nib, varying thickness based on stroke direction.
4.  **Airbrush**: Uses a randomized spray pattern to create soft, textured gradients. To prevent CPU lag from drawing tens of thousands of circles per second, the stamp pattern is pre-compiled onto an optimized bitmap cache.
5.  **GPU Wet Watercolors & Oils**: Our flagship brushes! They utilize custom Android Graphics Shading Language (**AGSL**) shaders to simulate wet fluid paint blending, bleeding, and paper-grain textures in real time.

### Canvas Wetness Simulation
When drawing with Watercolor or Oil paint, the digital canvas sheet is marked as "Wet". Paint deposited onto wet areas dynamically blends and diffuses into adjacent colors.
*   **The Wet Indicator**: A dynamic indicator in the toolbar shows your canvas status (e.g., `💧 Wet 80%`).
*   **Drying**: Tap the status indicator or the Sun icon (`☀️`) to dry the canvas sheet immediately, locking the current paint layer.
*   **Hardware Fallback**: Since AGSL requires API 33+ (Android 13) and a mid-to-high-end graphics processor, the app automatically falls back to beautiful 2D vector blend simulations on older/lower-end hardware, keeping your drawing experience completely lag-free.

---

## 🥞 3. Layers & Compositing System

Organize complex artwork, sketches, and notes into distinct horizontal planes using the multi-layer manager.

```
  [Front Layer]    =============================  Ink Canvas, Annotations
  [Middle Layer]   =============================  Images, Media Embeds
  [Background]     =============================  PDF Template, Lined Paper
```

*   **Layer Ordering**: Move layers up or down to adjust which elements draw on top.
*   **Visibility & Lock**: Hide layers to focus on specific annotations, or lock layers to prevent accidental edits or eraser marks.
*   **Rendering Optimization**: Layers utilize a background LRU cache capped at 4 full-screen high-resolution bitmaps. A layer's cached bitmap is only invalidated and re-rendered when you actively modify strokes on that specific layer. This ensures that multi-layered documents pan and zoom at a consistent **60fps to 120fps**.

---

## 🎙️ 4. Time-Synced Voice Notes

Record audio lectures or meeting proceedings while taking notes, and watch your note-taking experience come alive during playback.

*   **How to Record**: Tap the microphone icon in the drawing toolbar. The app will record high-quality mono audio while tracking the exact millisecond offset of every stroke you write.
*   **The Synced Playback**: When playing back an embedded voice note, the canvas goes into playback mode. Your drawn strokes will fade in or highlight in real time, matching the exact moment they were written relative to the audio playback track.
*   **Scrubbing**: Drag the audio progress slider or tap anywhere on the voice card to jump to a specific time. The canvas will immediately update, replaying and flattening vector strokes up to that exact timestamp.

---

## 🔗 5. Wikilinks & Interactive Knowledge Graph

Create a personal knowledge network by connecting individual note pages together using a simple, intuitive wiki syntax.

### Wikilinking Notes
In any markdown or text-based note page, enclose a page title in double brackets to create an immediate hyperlink:
*   *Example*: `Make sure to review [[Project Roadmap]] before meeting.`
*   **Dynamic Creation**: If the page `Project Roadmap` already exists, clicking the link opens it. If it does not exist, InkFlow will automatically create a new, blank note with that title and link them!

### Interactive Knowledge Graph
A stunning, fully interactive 2D node map visualizes how your notes are connected.
*   **Nodes**: Represent note pages. Larger nodes represent highly linked pages.
*   **Edges (Lines)**: Represent active wikilinks or common tag categories between pages.
*   **Interactivity**: Drag nodes to rearrange the map. Tap on any node to jump directly to that page's editor screen.

> *Aesthetic Tip*: The graph view uses beautiful physics simulations with subtle, spring-based animations and a balanced neutral palette that feels tactile and responsive.

---

## 🛡️ 6. Encrypted Vault, Biometrics & Security

Privacy is a non-negotiable core value of InkFlow. We keep your data 100% private with a highly secure local encryption architecture.

### The Cryptography Stack
*   **Key Derivation**: When you set up a Master Password, we derive your master key using **PBKDF2WithHmacSHA256** with a highly demanding work factor of **600,000 iterations** combined with a secure local salt.
*   **Symmetric Encryption**: Individual notes, Markdown documents, and extracted text blocks are fully encrypted using **AES-256-GCM** with a secure random 12-byte IV and a 128-bit integrity tag.
*   **Secure Zeroization**: Decrypted keys and plaintext payloads live exclusively in secure transient memory blocks. The moment you lock the app, background it, or leave the editor, all in-memory keys are instantly zeroed out.

### Authentication & Fail-Safes
*   **Biometric Unlock**: Access your encrypted vault instantly using Android BiometricPrompt (fingerprint, face recognition) as a secure fallback.
*   **5-Fail Lockout**: To prevent brute-force attacks, entering an incorrect master password 5 consecutive times triggers an automatic lockout timer (starting at 30 seconds and scaling exponentially up to 15 minutes). This lockout state is stored securely in persistent preferences and survives device reboots.

---

## 📂 7. Document Annotation & Media Imports

Bring external research, documents, and visual media into your notebook space:

### A. PDF Imports
*   Import complex textbooks, slides, and contracts.
*   Our render pipeline opens and scales PDF textures dynamically, bounding high-resolution renders to max **1.5x of your screen's width** to prevent Out Of Memory (OOM) errors.
*   Draw directly on top of the PDF pages as a background layer. All vector highlights, ink notes, and text additions are saved relative to the PDF's coordinates.

### B. Image & Photo Embeds
*   Insert photos, diagrams, or screenshots onto your infinite canvas.
*   Resize, drag, and position images freely. InkFlow dynamically decodes imported images off the main thread, utilizing `inSampleSize` calculations to scale visual assets efficiently and prevent system lag.

---

## 💾 8. Backups & Export Pipeline

Since InkFlow operates entirely offline with no cloud dependencies, your backups are fully in your control.

### Encrypted Backup Packages
Generate a standard `.zip` archive containing your entire local SQLite database and raw voice recordings.
1.  Navigate to **Settings & More -> Backup to File**.
2.  Choose a destination directory (such as your Downloads folder, a secure USB drive, or your preferred third-party cloud drive).
3.  The backup is encrypted using your Master Password, ensuring it remains fully secure even when stored on public cloud drives.
4.  To recover your notes, choose **Restore from Backup**, select your `.zip` archive, and enter your password.

---

## 🌈 9. Theme, Personalization & UI Philosophy

InkFlow prioritizes an elegant, visual, and distractions-free workspace. Our user interface leverages Material Design 3 guidelines:

### Premium Theme Collections
*   **Sepia Theme**: An ultra-warm, low-contrast vintage paper theme designed to ease eye strain during long nocturnal note-taking sessions.
*   **Light & Dark Themes**: High-contrast, clean modern canvases utilizing neutral colors, extensive negative space, and elegant visual typography pairings.
*   **Amoled Black Theme**: A pitch-black layout tailored for modern OLED displays, reducing power consumption to an absolute minimum.
*   **Dynamic Material You**: Dynamically reads your Android system color palette (on Android 12+) and applies harmonized color variants across the app.

### Paper Guidelines
Personalize your page canvas backdrops with classic structural overlays:
*   **Plain Blank**: Crisp, negative space for open-ended drawing.
*   **Lined / Ruled**: Perfect for standard handwriting and cursive practice.
*   **Dot Grid**: Excellent for technical drafting, math notes, and bullet journaling.
*   **Cornell Notes Layout**: Structural divider lines tailored for the popular Cornell academic study method.
