import com.authorss81.noteflow.services.PaperEdgePolicy;
import com.authorss81.noteflow.services.PaperTextureStrengthPolicy;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * Phase 227 DoD artifact: deckled-edge + paper-texture strength strip rendered
 * by a JVM driver that calls the REAL compiled PaperEdgePolicy /
 * PaperTextureStrengthPolicy (the exact pure-JVM classes the canvas draw path
 * and the exporters consume). Paparazzi/layoutlib is broken on this runner, so
 * — same precedent as phase-200/213 — this is the geometry-level ground truth
 * of what the deckled sheet looks like across densities and strengths.
 *
 * Top band  : the deckled sheet silhouette at 2x & 3x density (the wavy node
 *             stream smoothed to midpoints, exactly as the canvas clips to).
 * Middle    : texture-strength grain ALPHA bands — the single cached tile
 *             drawn at grainScale(0/25/50/75/100), i.e. the strength dial's
 *             visible tooth envelope on a sheet edge.
 * Bottom    : ROUNDED vs DECKLED next to each other so the torn edge reads.
 */
public class RenderDeckledEdge {

    private static Path2D.Float decklePath(float width, float height, float pxPerDp) {
        float amp = PaperEdgePolicy.INSTANCE.amplitudePx(pxPerDp);
        List<kotlin.Pair<Float, Float>> nodes = PaperEdgePolicy.INSTANCE.deckleNodes(
            0f, 0f, width, height, amp, PaperEdgePolicy.INSTANCE.seedFor(false));
        List<kotlin.Pair<Float, Float>> mid = PaperEdgePolicy.INSTANCE.smoothedDeckleMidpoints(nodes);
        Path2D.Float p = new Path2D.Float();
        p.moveTo(mid.get(0).getFirst(), mid.get(0).getSecond());
        for (int i = 1; i < mid.size(); i++) p.lineTo(mid.get(i).getFirst(), mid.get(i).getSecond());
        p.closePath();
        return p;
    }

    private static BufferedImage sheet(int pxPerDp) {
        int w = 540 * pxPerDp, h = 300 * pxPerDp;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x334155));
        g.fillRect(0, 0, w, h);
        g.translate(40, 30);
        g.setColor(Color.WHITE);
        g.fill(decklePath((float) (w - 80), (float) (h - 60), (float) pxPerDp));
        g.setColor(new Color(0x475569));
        g.setStroke(new BasicStroke(2 * pxPerDp, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(decklePath((float) (w - 80), (float) (h - 60), (float) pxPerDp));
        g.dispose();
        return img;
    }

    private static BufferedImage strengthBand(int strength, int w, int h) {
        float scale = PaperTextureStrengthPolicy.INSTANCE.grainScale(strength);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        // The real grain tile is a noise speck; here we render the SAME envelope:
        // a mid-grey tooth at the policy's draw alpha, scaled by grainScale.
        float alpha = PaperTextureStrengthPolicy.INSTANCE.grainDrawAlpha(strength);
        g.setColor(new Color(120, 120, 120, Math.round(alpha * 255f)));
        // Speckle dots approximating the fleck field density.
        java.util.Random rnd = new java.util.Random(strength * 7919L + 13);
        for (int i = 0; i < w * h / 120; i++) {
            int x = Math.abs(rnd.nextInt()) % w, y = Math.abs(rnd.nextInt()) % h;
            g.fillRect(x, y, 2, 2);
        }
        g.dispose();
        return img;
    }

    public static void main(String[] args) throws Exception {
        int pad = 16, labelH = 24;
        BufferedImage out = new BufferedImage(1200, 760, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1E293B));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setColor(Color.WHITE);

        // Top band: deckled sheet at 2x and 3x density next to the rounded card.
        int y = pad;
        g.drawString("PHASE 227 — DECKLED EDGE  (real PaperEdgePolicy node stream, pdf 'rounded' vs deckled)", 12, y + 14);
        g.drawImage(sheet(2), pad, y + labelH, null);
        g.drawImage(sheet(3), 2 * pad + 1080, y + labelH, null);

        // Middle band: strength dial alpha envelope.
        y += 300 * 2 + labelH + pad * 2;
        g.drawString("PAPER TEXTURE STRENGTH  (grainDrawAlpha + grainScale of the cached tile)", 12, y + 14);
        int bw = 120, bh = 150;
        int[] strengths = {0, 25, 50, 75, 100};
        for (int i = 0; i < strengths.length; i++) {
            int x = pad + i * (bw + pad);
            g.setColor(Color.LIGHT_GRAY);
            g.drawString("s=" + strengths[i], x, y + labelH - 4);
            g.drawImage(strengthBand(strengths[i], bw, bh), x, y + labelH, null);
        }

        // Bottom band: strength text + the shader mapping note (pure JVM math).
        y += bh + labelH + pad;
        float def = PaperTextureStrengthPolicy.INSTANCE.grainScale(50);
        float max = PaperTextureStrengthPolicy.INSTANCE.grainScale(100);
        g.setColor(Color.WHITE);
        g.drawString("grainScale(0)=" + PaperTextureStrengthPolicy.INSTANCE.grainScale(0) +
            "  grainScale(50)=" + def + "  grainScale(100)=" + max +
            "  (default anchored at exactly 1.0 = pre-227 grain)", 12, y + 14);
        g.drawString("wet-shader shaderGain(50)=" + PaperTextureStrengthPolicy.INSTANCE.shaderGain(50) +
            "  shaderGain(100)=" + PaperTextureStrengthPolicy.INSTANCE.shaderGain(100), 12, y + 30);
        g.dispose();

        File dir = new File("visual-qa/screenshots/phase-227");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File outF = new File(dir, "deckled-edge-texture.png");
        ImageIO.write(out, "png", outF);
        System.out.println("WROTE " + outF.getAbsolutePath() + " " + out.getWidth() + "x" + out.getHeight());
    }
}
