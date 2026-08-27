import com.authorss81.noteflow.data.model.PointF;
import com.authorss81.noteflow.data.model.Stroke;
import com.authorss81.noteflow.data.model.StrokeTool;
import com.authorss81.noteflow.services.SelectionTransformPolicy;
import com.authorss81.noteflow.services.StrokeSelectionActionPolicy;
import com.authorss81.noteflow.services.LassoTrailPolicy;
import com.authorss81.noteflow.services.StrokeHitPolicy;
import com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy;
import androidx.compose.ui.geometry.Rect;
import androidx.compose.ui.graphics.Color;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 226 DoD artifact: renders the SELECT-tool selection-transform overlay —
 * three ink strokes scaled 1.5x + rotated 45° about their selection centre —
 * by driving the REAL compiled policy classes ([SelectionTransformPolicy],
 * [LassoTrailPolicy], [StrokeHitPolicy], [ResizeHandleVisibilityPolicy]) the
 * exact way the canvas overlay does. No emulator on this runner, so this is the
 * geometry-level ground truth of the phase-226 handles + bounds.
 */
public class RenderSelectionTransform {
    static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    static int overA(int bg, float alpha, int fgRgb) {
        int br = (bg >> 16) & 0xFF, bgc = (bg >> 8) & 0xFF, bb = bg & 0xFF;
        int sr = (fgRgb >> 16) & 0xFF, sg = (fgRgb >> 8) & 0xFF, sb = fgRgb & 0xFF;
        return argb(255,
            (int) (sr * alpha + br * (1 - alpha)),
            (int) (sg * alpha + bgc * (1 - alpha)),
            (int) (sb * alpha + bb * (1 - alpha)));
    }

    static Stroke freehand(String id, StrokeTool tool, List<PointF> pts) {
        return new Stroke(id, tool, 0xFF1B365D, 8f, false, "", pts, null, null, 0, null, false, null,
            com.authorss81.noteflow.data.model.StrokeColorMode.SOLID, 0, null);
    }

    static List<PointF> poly(double[][] p) {
        List<PointF> out = new ArrayList<>();
        for (double[] xy : p) out.add(new PointF((float) xy[0], (float) xy[1], null, null, null));
        return out;
    }

    public static void main(String[] args) throws Exception {
        // World coordinates of the selection geometry.
        List<Stroke> originals = new ArrayList<>();
        List<PointF> s1 = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            double t = i / 20.0;
            double x = 120 + t * 240, y = 180 + Math.sin(t * 4) * 12;
            s1.add(new PointF((float) x, (float) y, null, null, null));
        }
        originals.add(freehand("s1", StrokeTool.PEN, s1));
        List<PointF> s2 = new ArrayList<>();
        for (int i = 0; i <= 24; i++) {
            double a = Math.PI * i / 24;
            s2.add(new PointF((float) (180 + 70 * Math.cos(a)), (float) (180 + 70 * Math.sin(a)), null, null, null));
        }
        originals.add(freehand("s2", StrokeTool.MARKER, s2));
        List<PointF> s3 = new ArrayList<>();
        for (int i = 0; i <= 12; i++) {
            s3.add(new PointF(160 + (i % 2) * 40, 100 + i * 14, null, null, null));
        }
        originals.add(freehand("s3", StrokeTool.PENCIL, s3));

        // Bake scale 1.5x + rotate 45 deg about the selection centre (real math).
        List<String> ids = new ArrayList<>();
        for (Stroke s : originals) ids.add(s.getId());
        java.util.Set<String> idSet = new java.util.HashSet<>(ids);
        Rect origBounds = StrokeSelectionActionPolicy.INSTANCE.recomputeBounds(originals, idSet);
        PairF centre = centerOf(origBounds);
        List<Stroke> transformed = SelectionTransformPolicy.INSTANCE.transformSelected(
            originals, idSet, centre.x, centre.y, 1.5f, 1.5f, 45f, 1592f);
        Rect bounds = StrokeSelectionActionPolicy.INSTANCE.recomputeBounds(transformed, idSet);

        System.out.printf("ORIGINAL bounds = [%.1f,%.1f .. %.1f,%.1f] (w=%.1f h=%.1f)%n",
            origBounds.getLeft(), origBounds.getTop(), origBounds.getRight(), origBounds.getBottom(),
            origBounds.getWidth(), origBounds.getHeight());
        System.out.printf("TRANSFORMED (1.5x, 45deg) bounds = [%.1f,%.1f .. %.1f,%.1f] (w=%.1f h=%.1f)%n",
            bounds.getLeft(), bounds.getTop(), bounds.getRight(), bounds.getBottom(),
            bounds.getWidth(), bounds.getHeight());
        System.out.printf("centre = (%.1f, %.1f), stroke count = %d%n", centre.x, centre.y, transformed.size());

        // ---- rasterize into a fixed-size image ----
        int W = 1080, H = 1600;
        int paper = 0xFFFAFAFA;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(paper)); g.fillRect(0, 0, W, H);
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

        // Map world -> screen: fit the selection bounds with margin and centre it.
        float pad = 90f;
        float scale = (float) Math.min((W - 2 * pad) / Math.max(bounds.getWidth(), 1f),
                                       (H - 2 * pad) / Math.max(bounds.getHeight(), 1f));
        PairF c = centerOf(bounds);
        float ox = W / 2f - c.x * scale;
        float oy = H / 2f - c.y * scale;
        java.awt.geom.AffineTransform tf = new java.awt.geom.AffineTransform();
        tf.concatenate(java.awt.geom.AffineTransform.getTranslateInstance(ox, oy));
        tf.concatenate(java.awt.geom.AffineTransform.getScaleInstance(scale, scale));

        // Paper dot-grid texture (visual only).
        g.setColor(new java.awt.Color(0xFFD8D8E0));
        for (int tx = 0; tx < W; tx += 22) for (int ty = 0; ty < H; ty += 22) g.fillOval(tx, ty, 3, 3);

        // Ink strokes (real geometry).
        g.setStroke(new BasicStroke(8f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new java.awt.Color(0xFF1B365D));
        for (Stroke s : transformed) {
            if (s.getPoints().size() > 1) {
                java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
                PointF first = s.getPoints().get(0);
                java.awt.geom.Point2D p0 = tf.transform(new java.awt.geom.Point2D.Float(first.getX(), first.getY()), null);
                path.moveTo(p0.getX(), p0.getY());
                for (int i = 1; i < s.getPoints().size(); i++) {
                    PointF p = s.getPoints().get(i);
                    java.awt.geom.Point2D pt = tf.transform(new java.awt.geom.Point2D.Float(p.getX(), p.getY()), null);
                    path.lineTo(pt.getX(), pt.getY());
                }
                g.draw(path);
            }
        }

        // Per-stroke selection highlight (LassoTrailPolicy.HIGHLIGHT_ALPHA, width+10).
        g.setStroke(new BasicStroke((8f + 10f) * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new java.awt.Color(0, 0, 0, 0)); // placeholder; replaced below
        int accent = 0xFF3366E8;
        float hlAlpha = LassoTrailPolicy.HIGHLIGHT_ALPHA;
        for (Stroke s : transformed) {
            if (s.getPoints().size() > 1) {
                java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
                PointF first = s.getPoints().get(0);
                java.awt.geom.Point2D p0 = tf.transform(new java.awt.geom.Point2D.Float(first.getX(), first.getY()), null);
                path.moveTo(p0.getX(), p0.getY());
                for (int i = 1; i < s.getPoints().size(); i++) {
                    PointF p = s.getPoints().get(i);
                    java.awt.geom.Point2D pt = tf.transform(new java.awt.geom.Point2D.Float(p.getX(), p.getY()), null);
                    path.lineTo(pt.getX(), pt.getY());
                }
                g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, hlAlpha));
                g.setColor(new java.awt.Color(accent));
                g.draw(path);
                g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1f));
            }
        }

        // Dashed bounds box (LassoTrailPolicy bounds alpha/width/dash pattern,
        // inflated by StrokeHitPolicy.SELECTION_BOUNDS_PADDING_PX).
        float pb = StrokeHitPolicy.SELECTION_BOUNDS_PADDING_PX;
        Rect padBounds = new Rect(bounds.getLeft() - pb, bounds.getTop() - pb,
                                  bounds.getRight() + pb, bounds.getBottom() + pb);
        java.awt.geom.Point2D tl = tf.transform(new java.awt.geom.Point2D.Float(padBounds.getLeft(), padBounds.getTop()), null);
        java.awt.geom.Point2D br = tf.transform(new java.awt.geom.Point2D.Float(padBounds.getRight(), padBounds.getBottom()), null);
        float[] dashPat = LassoTrailPolicy.INSTANCE.boundsDashPattern(1f);
        g.setStroke(new BasicStroke(LassoTrailPolicy.BOUNDS_STROKE_WIDTH_PX * scale, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
            10f, new float[] { dashPat[0] * scale, dashPat[1] * scale }, 0f));
        g.setColor(new java.awt.Color(accent));
        g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, LassoTrailPolicy.BOUNDS_OUTLINE_ALPHA));
        g.draw(new java.awt.geom.RoundRectangle2D.Float((float) tl.getX(), (float) tl.getY(),
            (float) (br.getX() - tl.getX()), (float) (br.getY() - tl.getY()), 12f, 12f));
        g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 1f));

        // Handles — visible at rest (alpha 1). 4 corner scale handles + 1 top
        // rotation handle (sizes from ResizeHandleVisibilityPolicy, dp -> px *3).
        double cornerPx = ResizeHandleVisibilityPolicy.HANDLE_SIZE_DP * 3;
        double rotPx = ResizeHandleVisibilityPolicy.ROTATION_HANDLE_SIZE_DP * 3;
        Corner[] corners = { Corner.TOP_LEFT, Corner.TOP_RIGHT, Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT };
        for (Corner cn : corners) {
            PairF pos = cornerPos(padBounds, cn);
            java.awt.geom.Point2D hp = tf.transform(new java.awt.geom.Point2D.Float(pos.x, pos.y), null);
            g.setColor(new java.awt.Color(accent));
            g.fillOval((int) (hp.getX() - cornerPx / 2), (int) (hp.getY() - cornerPx / 2), (int) cornerPx, (int) cornerPx);
            g.setColor(new java.awt.Color(0xFFFFFFFF));
            g.drawOval((int) (hp.getX() - cornerPx / 2), (int) (hp.getY() - cornerPx / 2), (int) cornerPx, (int) cornerPx);
        }
        // Rotation handle above the top centre of the PADDED bounds.
        double rotTop = padBounds.getTop() - 8 - rotPx;
        PairF rc = centerOf(padBounds);
        java.awt.geom.Point2D rhp = tf.transform(new java.awt.geom.Point2D.Float(rc.x, (float) rotTop), null);
        g.setColor(new java.awt.Color(accent));
        g.fillOval((int) (rhp.getX() - rotPx / 2), (int) (rhp.getY() - rotPx / 2), (int) rotPx, (int) rotPx);
        g.setColor(new java.awt.Color(0xFFFFFFFF));
        g.drawOval((int) (rhp.getX() - rotPx / 2), (int) (rhp.getY() - rotPx / 2), (int) rotPx, (int) rotPx);
        // Rotation "stem" line
        g.setStroke(new BasicStroke(2f));
        g.setColor(new java.awt.Color(accent));
        g.drawLine((int) rhp.getX(), (int) (rhp.getY() + rotPx / 2), (int) rhp.getX(), (int) (tl.getY()));

        g.setFont(g.getFont().deriveFont(20f));
        g.setColor(new java.awt.Color(0xFF333333));
        g.drawString("Phase 226 — 3 selected strokes: scale 1.5x + rotate 45 deg", 30, 60);

        File out = new File(args.length > 0 ? args[0] : "phase226-selection-transform.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out.getPath() + " " + img.getWidth() + "x" + img.getHeight());
    }

    static PairF centerOf(Rect r) {
        return new PairF((r.getLeft() + r.getRight()) / 2f, (r.getTop() + r.getBottom()) / 2f);
    }
    static PairF cornerPos(Rect b, Corner c) {
        switch (c) {
            case TOP_LEFT: return new PairF(b.getLeft(), b.getTop());
            case TOP_RIGHT: return new PairF(b.getRight(), b.getTop());
            case BOTTOM_LEFT: return new PairF(b.getLeft(), b.getBottom());
            default: return new PairF(b.getRight(), b.getBottom());
        }
    }
    enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    static class PairF { float x, y; PairF(float x, float y) { this.x = x; this.y = y; } }
}
