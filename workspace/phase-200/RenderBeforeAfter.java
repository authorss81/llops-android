import com.authorss81.noteflow.services.EraserGeometryPolicy;
import com.authorss81.noteflow.services.PaperGrainPolicy;
import com.authorss81.noteflow.services.WetMixingMath;
import androidx.compose.ui.graphics.colorspace.ColorSpace;
import androidx.compose.ui.graphics.colorspace.ColorSpaces;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Phase 200 DoD artifact: before/after visualization rendered by driving the
 * REAL compiled policy classes (the exact functions mirrored by the AGSL
 * shader / canvas draw path). No emulator on this runner, so this is the
 * math-level ground truth of what changed on screen.
 */
public class RenderBeforeAfter {
    static int argb(int a, int r, int g, int b) {
        a = Math.max(0, Math.min(255, a));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    static int enc(float v) { return (int) (Math.max(0f, Math.min(1f, v)) * 255f + 0.5f); }

    /** alpha-over composite of a speckle pixel onto an opaque bg, returns opaque argb */
    static int over(int bg, float a, int speckleRgb) {
        int br = (bg >> 16) & 0xFF, bgc = (bg >> 8) & 0xFF, bb = bg & 0xFF;
        int sr = (speckleRgb >> 16) & 0xFF, sg = (speckleRgb >> 8) & 0xFF, sb = speckleRgb & 0xFF;
        return argb(255,
            (int) (sr * a + br * (1 - a)),
            (int) (sg * a + bgc * (1 - a)),
            (int) (sb * a + bb * (1 - a)));
    }

    /**
     * Review-fix honesty change: evaluates the alpha of what the app ACTUALLY
     * ships — the piecewise-linear interpolation over the exact radial-gradient
     * stop list built by AnnotationCanvas.LiveStrokePreview (opaque plateau out
     * to cursorBandStartNd, then CURSOR_FEATHER_STOP_COUNT samples across
     * [bandStart, 1]) — not the raw hermite curve. Uses the real policy
     * functions for both the band start and the sampled falloff.
     */
    static float shippedCursorAlpha(float dist, float radius) {
        int n = EraserGeometryPolicy.CURSOR_FEATHER_STOP_COUNT;
        float bandStart = EraserGeometryPolicy.INSTANCE.cursorBandStartNd(radius);
        int count = n + 2;
        float[] pos = new float[count], alp = new float[count];
        pos[0] = 0f;
        alp[0] = EraserGeometryPolicy.INSTANCE.cursorFillAlphaAt(0f, radius);
        for (int i = 0; i <= n; i++) {
            float nd = bandStart + (1f - bandStart) * i / (float) n;
            pos[i + 1] = Math.min(nd, 1f);
            alp[i + 1] = EraserGeometryPolicy.INSTANCE.cursorFillAlphaAt(Math.min(nd, 1f), radius);
        }
        if (dist <= pos[0]) return alp[0];
        for (int i = 1; i < count; i++) {
            if (dist <= pos[i]) {
                float t = (pos[i] == pos[i - 1]) ? 0f : (dist - pos[i - 1]) / (pos[i] - pos[i - 1]);
                return alp[i - 1] + (alp[i] - alp[i - 1]) * t;
            }
        }
        return alp[count - 1];
    }

    public static void main(String[] args) throws Exception {
        int panelW = 420, panelH = 240, pad = 16;
        BufferedImage img = new BufferedImage((panelW + pad) * 2 + pad, (panelH + 34) * 3 + pad, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0xFF20242B)); g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setFont(g.getFont().deriveFont(13f));
        int y = pad;

        // ---- Panel 1: cyan wash + overlapping red deposit on the TRANSPARENT ink layer ----
        for (int col = 0; col < 2; col++) {
            boolean after = col == 1;
            ColorSpace space = after ? ColorSpaces.INSTANCE.getLinearSrgb() : ColorSpaces.INSTANCE.getSrgb();
            int x0 = pad + col * (panelW + pad);
            int paper = 0xFFFFFFFF;
            float[] cyan = {0f, 0.55f, 0.55f};
            float[] red = {0.9f, 0.25f, 0.25f};
            for (int py = 0; py < panelH; py++) {
                for (int px = 0; px < panelW; px++) {
                    // wet ink layer state
                    float[] ink = {0f, 0f, 0f};
                    float alpha = 0f;
                    // wash 1: full-page cyan @ a=.65, pigmentFactor .8
                    alpha = WetMixingMath.INSTANCE.sourceOverAlpha(alpha, 0.65f);
                    ink = WetMixingMath.INSTANCE.pigmentMixRgb(ink[0], ink[1], ink[2], cyan[0], cyan[1], cyan[2], 0.8f, space);
                    // wash 2: soft round red deposit @ a=.5, pigmentFactor .8
                    float dx = px - panelW * 0.38f, dy = py - panelH * 0.5f;
                    float rEdge = panelH * 0.34f;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy) / rEdge;
                    float fall = Math.max(0f, Math.min(1f, (1f - dist) * 2.2f));
                    if (fall > 0.001f) {
                        alpha = WetMixingMath.INSTANCE.sourceOverAlpha(alpha, 0.5f * fall);
                        ink = WetMixingMath.INSTANCE.pigmentMixRgb(ink[0], ink[1], ink[2], red[0], red[1], red[2], 0.8f, space);
                    }
                    // composite ink layer over white paper for display
                    int pr = (paper >> 16) & 0xFF, pg = (paper >> 8) & 0xFF, pb = paper & 0xFF;
                    img.setRGB(x0 + px, y + py, argb(255,
                        (int) (enc(ink[0]) * alpha + pr * (1 - alpha)),
                        (int) (enc(ink[1]) * alpha + pg * (1 - alpha)),
                        (int) (enc(ink[2]) * alpha + pb * (1 - alpha))));
                }
            }
            g.setColor(col == 1 ? new java.awt.Color(0xFF7CE38B) : new java.awt.Color(0xFFF28B82));
            g.drawString((after ? "AFTER phase-200: mix in LINEAR light" : "BEFORE: mix in gamma sRGB (muddy)"), x0 + 4, y + panelH + 20);
        }
        y += panelH + 34;

        // ---- Panel 2: paper grain tiles ----
        int[] bgs = {0xFFFFFFFF, 0xFF1E293B};
        for (int col = 0; col < 2; col++) {
            boolean dark = col == 1;
            int x0 = pad + col * (panelW + pad);
            int bg = bgs[col];
            g.setColor(new java.awt.Color(bg, false)); g.fillRect(x0, y, panelW, panelH);
            int speckle = PaperGrainPolicy.INSTANCE.speckleRgb(dark) & 0xFFFFFF;
            int t = PaperGrainPolicy.TILE_SIZE_PX;
            for (int ty = 0; ty < panelH; ty++) {
                for (int tx = 0; tx < panelW; tx++) {
                    float n = PaperGrainPolicy.INSTANCE.noiseAt(tx % t, ty % t, dark); // REPEAT wrap
                    float a = PaperGrainPolicy.INSTANCE.pixelAlphaAt(n, dark) * 3.5f;  // gain for print visibility
                    img.setRGB(x0 + tx, y + ty, over(bg, Math.min(1f, a), speckle));
                }
            }
            g.setColor(new java.awt.Color(0xFFAAAAAA));
            g.drawString("Paper grain tile (" + (dark ? "dark" : "light") + " paper family, alpha x3.5 for print)", x0 + 4, y + panelH + 20);
        }
        y += panelH + 34;

        // ---- Panel 3: eraser aim-cursor edge, hard rim vs ink-parity feather ----
        for (int col = 0; col < 2; col++) {
            boolean after = col == 1;
            int x0 = pad + col * (panelW + pad);
            int bg = 0xFFFFFFFF;
            g.setColor(new java.awt.Color(bg, false)); g.fillRect(x0, y, panelH, panelH);
            float radius = 88f;
            int cx = panelH / 2, cy = panelH / 2;
            int er = 0x33, eg = 0x66, eb = 0xE8;
            for (int py = 0; py < panelH; py++) {
                for (int px = 0; px < panelH; px++) {
                    float dist = (float) Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy)) / radius;
                    float fill;
                    if (after) {
                        fill = (dist >= 1f) ? 0f
                            : shippedCursorAlpha(Math.min(dist, 1f), radius) * EraserGeometryPolicy.CURSOR_FILL_ALPHA;
                    } else {
                        fill = dist <= 1f ? EraserGeometryPolicy.CURSOR_FILL_ALPHA : 0f;
                    }
                    img.setRGB(x0 + px, y + py, over(bg, Math.min(1f, fill), (er << 16) | (eg << 8) | eb));
                }
            }
            // 1D edge profile strip (x across the rim, y = alpha) under the circle
            int stripY = y + panelH - 14, stripX = x0 + 12, stripW = panelH - 24;
            g.setColor(new java.awt.Color(0xFFCCCCCC));
            g.drawRect(stripX, stripY, stripW, 10);
            for (int px = 0; px < stripW; px++) {
                float nd = 1f - (float) px / stripW + 0.02f; // rim -> center
                float a = after
                    ? shippedCursorAlpha(Math.min(nd, 1f), radius) * EraserGeometryPolicy.CURSOR_FILL_ALPHA
                    : (nd <= 1f ? EraserGeometryPolicy.CURSOR_FILL_ALPHA : 0f);
                int barH = (int) (a * 10f);
                g.setColor(new java.awt.Color(0xFF3366E8));
                g.fillRect(stripX + px, stripY + 10 - barH, 1, barH);
            }
            g.setColor(col == 1 ? new java.awt.Color(0xFF7CE38B) : new java.awt.Color(0xFFF28B82));
            g.drawString(after ? "AFTER: shipped gradient — plateau + band-sampled edgeFeather" : "BEFORE: hard-edged flat fill (aliased rim)", x0 + 4, y + panelH + 20);
        }

        File out = new File(args.length > 0 ? args[0] : ".");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out.getPath() + " " + img.getWidth() + "x" + img.getHeight());
    }
}
