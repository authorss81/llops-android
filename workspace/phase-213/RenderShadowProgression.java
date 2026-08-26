import com.authorss81.noteflow.data.model.StrokeTool;
import com.authorss81.noteflow.services.BrushShadowPolicy;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Phase 213 DoD artifact: before/after shadow-progression strip rendered by a
 * JVM driver that calls the REAL compiled BrushShadowPolicy (the exact class
 * the canvas draw path consumes). Paparazzi/layoutlib is broken on this runner
 * (pre-existing, see phase-208/212 reports), so — same precedent as phase-200 —
 * this is the math-level ground truth of what the renderer draws:
 *
 *   shadow layer = stroke path offset by plan.offset*, stroked at the stroke
 *   width, tinted black (light paper) / white (dark paper) at plan.alpha,
 *   blurred with a true separable Gaussian of radius plan.blurRadiusPx
 *   (BlurMaskFilter.Blur.NORMAL equivalent), composited UNDER the un-blurred ink.
 *
 * Rows: light paper / dark paper. Cells: widths 3/8/16 px, BEFORE (setting off)
 * vs AFTER (setting on). Bottom band: alpha ramp 0 → 1 (shadow strength
 * progression) at width 10.
 */
public class RenderShadowProgression {

    /** The hand-drawn test curve every cell draws. */
    private static Path2D.Float curve(float ox, float oy) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(ox + 18, oy + 52);
        p.quadTo(ox + 45, oy + 8, ox + 72, oy + 44);
        p.quadTo(ox + 95, oy + 74, ox + 122, oy + 34);
        return p;
    }

    private static void paintStroke(Graphics2D g, float ox, float oy, float w, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(curve(ox, oy));
    }

    /** Separable Gaussian blur of an ARGB image's alpha+color (premultiplied-safe enough for this artifact). */
    private static BufferedImage gaussianBlur(BufferedImage src, float radius) {
        int r = Math.max(1, Math.round(radius));
        float sigma = r / 2f;
        int size = 2 * r + 1;
        float[] k = new float[size];
        float sum = 0;
        for (int i = 0; i < size; i++) {
            float d = i - r;
            k[i] = (float) Math.exp(-(d * d) / (2 * sigma * sigma));
            sum += k[i];
        }
        for (int i = 0; i < size; i++) k[i] /= sum;

        int w = src.getWidth(), h = src.getHeight();
        float[] a = new float[w * h];
        float[] rr = new float[w * h], gg = new float[w * h], bb = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int al = (argb >>> 24) & 0xFF;
                a[y * w + x] = al;
                float f = al / 255f;
                rr[y * w + x] = ((argb >> 16) & 0xFF) * f;
                gg[y * w + x] = ((argb >> 8) & 0xFF) * f;
                bb[y * w + x] = (argb & 0xFF) * f;
            }
        }
        float[] tmpA = new float[w * h], tmpR = new float[w * h], tmpG = new float[w * h], tmpB = new float[w * h];
        // horizontal then vertical
        float[][] planes = {a, rr, gg, bb};
        for (int pi = 0; pi < 4; pi++) {
            float[] in = planes[pi];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    float acc = 0;
                    for (int i = 0; i < size; i++) {
                        int xx = Math.min(w - 1, Math.max(0, x + i - r));
                        acc += in[y * w + xx] * k[i];
                    }
                    tmpA[y * w + x] = acc;
                }
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    float acc = 0;
                    for (int i = 0; i < size; i++) {
                        int yy = Math.min(h - 1, Math.max(0, y + i - r));
                        acc += tmpA[yy * w + x] * k[i];
                    }
                    in[y * w + x] = acc;
                }
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int al = Math.round(a[i]);
                if (al <= 0) { out.setRGB(x, y, 0); continue; }
                int ri = Math.min(255, Math.round(rr[i] / (a[i] / 255f)));
                int gi = Math.min(255, Math.round(gg[i] / (a[i] / 255f)));
                int bi = Math.min(255, Math.round(bb[i] / (a[i] / 255f)));
                out.setRGB(x, y, (al << 24) | (ri << 16) | (gi << 8) | bi);
            }
        }
        return out;
    }

    /** One rendered cell: paper + shadow (strength-scaled policy plan) + ink. */
    private static BufferedImage cell(boolean darkPaper, float widthPx, float strengthMul) {
        int cw = 150, ch = 95;
        BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(darkPaper ? new Color(0x1E293B) : Color.WHITE);
        g.fillRect(0, 0, cw, ch);

        BrushShadowPolicy.ShadowPlan plan = BrushShadowPolicy.INSTANCE.plan(
            StrokeTool.PEN, widthPx, darkPaper, true, 3f /* pxPerDp */);
        if (plan != null && strengthMul > 0f) {
            // Shadow layer: offset copy of the stroke, blurred, UNDER the ink.
            BufferedImage shadowLayer = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            Graphics2D sg = shadowLayer.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color tint = darkPaper ? Color.WHITE : Color.BLACK;
            sg.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(),
                Math.round(plan.getAlpha() * strengthMul * 255f)));
            sg.setStroke(new BasicStroke(widthPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            sg.draw(curve(plan.getOffsetX() * strengthMul, plan.getOffsetY() * strengthMul));
            sg.dispose();
            g.drawImage(gaussianBlur(shadowLayer, plan.getBlurRadiusPx() * strengthMul), 0, 0, null);
        }
        Color ink = darkPaper ? new Color(0xF8FAFC) : new Color(0x1B365D);
        paintStroke(g, 0, 0, widthPx, ink);
        g.dispose();
        return img;
    }

    public static void main(String[] args) throws Exception {
        float[] widths = {3f, 8f, 16f};
        int cw = 150, ch = 95, pad = 10, labelH = 22;
        int rowW = widths.length * 2 * (cw + pad) + pad + 90;
        int rampCols = 5;
        int totalW = Math.max(rowW, rampCols * (cw + pad) + pad + 90);
        int totalH = 2 * (ch + labelH + pad) + (ch + labelH + pad) + pad;

        BufferedImage out = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x334155));
        g.fillRect(0, 0, totalW, totalH);

        String[] rowNames = {"LIGHT PAPER", "DARK PAPER"};
        boolean[] darks = {false, true};
        for (int row = 0; row < 2; row++) {
            int y = pad + row * (ch + labelH + pad);
            g.setColor(Color.WHITE);
            g.drawString(rowNames[row] + "  (BrushShadowPolicy.plan: alpha=" +
                BrushShadowPolicy.INSTANCE.shadowAlpha(darks[row]) + ")", 12, y + 14);
            for (int i = 0; i < widths.length; i++) {
                int xBefore = 100 + i * 2 * (cw + pad);
                g.setColor(Color.LIGHT_GRAY);
                g.drawString("w=" + (int) widths[i] + "px BEFORE", xBefore, y + labelH - 4);
                g.drawImage(cell(darks[row], widths[i], 0f), xBefore, y + labelH, null);
                int xAfter = xBefore + cw + pad;
                g.setColor(Color.LIGHT_GRAY);
                g.drawString("AFTER", xAfter, y + labelH - 4);
                g.drawImage(cell(darks[row], widths[i], 1f), xAfter, y + labelH, null);
            }
        }

        // Bottom band: 0 -> 1 strength progression (light paper, width 10).
        int yRamp = pad + 2 * (ch + labelH + pad);
        g.setColor(Color.WHITE);
        g.drawString("SHADOW STRENGTH PROGRESSION 0 -> 1 (light paper, pen w=10)", 12, yRamp + 14);
        float[] muls = {0f, 0.25f, 0.5f, 0.75f, 1f};
        for (int i = 0; i < muls.length; i++) {
            int x = 100 + i * (cw + pad);
            g.setColor(Color.LIGHT_GRAY);
            g.drawString("strength=" + muls[i], x, yRamp + labelH - 4);
            g.drawImage(cell(false, 10f, muls[i]), x, yRamp + labelH, null);
        }
        g.dispose();

        File dir = new File("visual-qa/screenshots/phase-213");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File outF = new File(dir, "before-after-light-dark.png");
        ImageIO.write(out, "png", outF);
        System.out.println("WROTE " + outF.getAbsolutePath() + " " + out.getWidth() + "x" + out.getHeight());
    }
}
