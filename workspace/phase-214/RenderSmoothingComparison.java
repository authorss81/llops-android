import com.authorss81.noteflow.data.model.PointF;
import com.authorss81.noteflow.services.PressureCurve;
import com.authorss81.noteflow.services.PressureCurveHelper;
import com.authorss81.noteflow.services.RawInputSample;
import com.authorss81.noteflow.services.StabilizedSample;
import com.authorss81.noteflow.services.StabilizerFilter;
import com.authorss81.noteflow.services.StrokeInputBatcher;
import com.authorss81.noteflow.services.StrokeSmoothingPolicy;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Phase 214 visual + bench evidence (Paparazzi is env-broken on the runner —
 * phase-200/213 precedent). Renders BEFORE (pre-214 pipeline) vs AFTER
 * (phase-214 pipeline) crops for the two DoD cases using the REAL compiled
 * policy classes:
 *
 *   BEFORE: one sample per dispatched event, static-alpha EWMA on x/y only,
 *           pressure remapped raw (pre-214 AnnotationCanvas behaviour).
 *   AFTER:  coalesced history ingested (4 samples per event), velocity-adaptive
 *           alpha, pressure/tilt low-passed BEFORE the curve remap.
 *
 * Also prints a per-sample ingest microbench (JVM; a low-end proxy, not a
 * device measurement — stated honestly in REPORT.md).
 */
public class RenderSmoothingComparison {

    // ---- Synthetic digitizer input -------------------------------------------------

    /** One physical pen sample in window space. */
    record Phys(long tsMs, float wx, float wy, float pressure) {}

    /**
     * A slow diagonal stroke drawn over ~2.5 s at 60 Hz dispatch with 4x batch
     * coalescing (240 Hz digitizer). Position jitter +-2.2 px; pressure wobbles
     * around 0.32 (+-0.14) — light stylus contact.
     */
    static List<Phys> slowDiagonal(int n, long seed, float pressMid, float pressNoise) {
        Random r = new Random(seed);
        List<Phys> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double t = i / 240.0;
            float x = 40 + (float) (t * 90) + (float)((r.nextDouble() - 0.5) * 4.4);
            float y = 40 + (float) (t * 60) + (float)((r.nextDouble() - 0.5) * 4.4);
            float p = (float) (pressMid + (r.nextDouble() - 0.5) * 2 * pressNoise);
            out.add(new Phys(1_000L + i * 4L, x, y, Math.max(0.02f, Math.min(1f, p))));
        }
        return out;
    }

    /** Groups physical samples into dispatched MotionEvents: `batching` per event. */
    static List<List<Phys>> coalesce(List<Phys> phys, int batching) {
        List<List<Phys>> events = new ArrayList<>();
        for (int i = 0; i < phys.size(); i += batching) {
            events.add(phys.subList(i, Math.min(i + batching, phys.size())));
        }
        return events;
    }

    // ---- The two pipelines (mirroring AnnotationCanvas capture order) ----------------

    interface Pipeline { List<PointF> run(List<List<Phys>> events); }

    /** Pre-214: newest sample per event; static EWMA on x/y; raw pressure remap. */
    static List<PointF> legacyRun(List<List<Phys>> events) {
        StabilizerFilter filter = new StabilizerFilter(
                StrokeStabilizerDefaults.window(), StrokeStabilizerDefaults.prediction());
        List<PointF> pts = new ArrayList<>();
        for (List<Phys> ev : events) {
            Phys cur = ev.get(ev.size() - 1);          // <-- coalesced points LOST
            PointF p = new PointF(cur.wx(), cur.wy(),
                    PressureCurveHelper.INSTANCE.remapPressure(cur.pressure(), PressureCurve.SMOOTH), null, null);
            if (!pts.isEmpty()) {
                var s = filter.next(p.getX(), p.getY());   // static alpha, x/y only
                p = new PointF(s.getX(), s.getY(), p.getPressure(), null, null);
            }
            pts.add(p);
        }
        return pts;
    }

    /** Phase 214: full history ingestion -> velocity-adaptive alpha + smooth-then-remap. */
    static List<PointF> v2Run(List<List<Phys>> events) {
        StrokeInputBatcher batcher = new StrokeInputBatcher();
        StabilizerFilter filter = new StabilizerFilter(
                StrokeStabilizerDefaults.window(), StrokeStabilizerDefaults.prediction());
        List<PointF> pts = new ArrayList<>();
        Long lastTs = null;
        for (List<Phys> ev : events) {
            for (Phys s : ev) batcher.offer(new RawInputSample(s.wx(), s.wy(), s.pressure(), 0f, s.tsMs()));
            List<RawInputSample> drained = new ArrayList<>();
            batcher.drainInto(drained);
            for (RawInputSample s : drained) {
                Float vel = null;
                if (!pts.isEmpty() && lastTs != null && s.getTimestampMs() > lastTs) {
                    PointF prev = pts.get(pts.size() - 1);
                    vel = BrushStrokeMathBridge.segmentVelocity(prev, s);
                }
                StabilizedSample out = filter.next(s.getX(), s.getY(), s.getPressure(), null, vel, s.getTimestampMs());
                lastTs = s.getTimestampMs();
                pts.add(new PointF(out.getX(), out.getY(),
                        PressureCurveHelper.INSTANCE.remapPressure(out.getPressure(), PressureCurve.SMOOTH), null, null));
            }
        }
        return pts;
    }

    /** Mirrors BrushStrokeMath.segmentVelocity without dragging in StrokeTool deps. */
    static final class BrushStrokeMathBridge {
        static float segmentVelocity(PointF a, RawInputSample b) {
            float dx = b.getX() - a.getX();
            float dy = b.getY() - a.getY();
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist <= 0f) return 0f;
            long dt = b.getTimestampMs() - (a.getTimestampMs() == null ? 0L : a.getTimestampMs());
            return (dt > 0 && dist > 0.001) ? (float) (dist / dt) : (float) (dist / 16.0);
        }
    }

    static final class StrokeStabilizerDefaults {
        static int window() { return 8; } // StrokeStabilizer.DEFAULT_WINDOW_SIZE
        static float prediction() { return com.authorss81.noteflow.services.StrokeStabilizer.DEFAULT_PREDICTION; }
    }

    // ---- Rendering ---------------------------------------------------------------------

    static void drawPolyline(Graphics2D g, List<PointF> pts, float baseWidthPx) {
        for (int i = 1; i < pts.size(); i++) {
            PointF a = pts.get(i - 1);
            PointF b = pts.get(i);
            float p = b.getPressure() == null ? 0.6f : b.getPressure();
            // width follows remapped pressure like the pressure-sensitive brush path
            float w = baseWidthPx * (0.5f + 0.5f * p);
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine((int)(a.getX() * SCALE), (int)(a.getY() * SCALE), (int)(b.getX() * SCALE), (int)(b.getY() * SCALE));
        }
    }

    static final double SCALE = 1.5; // stroke bbox ~222x148 world -> ~333x222 px, fits a 360x320 crop

    static BufferedImage panel(String labelA, List<PointF> before, String labelB, List<PointF> after, float w) {
        int cw = 360, ch = 320;
        BufferedImage img = new BufferedImage(cw * 2 + 30, ch + 46, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0xFAF7F0)); g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(new Color(0x334155));
        g.drawString("Phase 214 — " + labelA, 12, 18);
        g.drawString(labelB, cw + 42, 18);
        // BEFORE crop (clipped so overflow cannot bleed across panels)
        g.setColor(Color.WHITE); g.fillRect(10, 30, cw, ch);
        g.setColor(new Color(0xE2E8F0)); g.drawRect(10, 30, cw, ch);
        g.setClip(10, 30, cw, ch);
        g.translate(10, 30);
        g.setColor(new Color(0x1B365D));
        drawPolyline(g, before, w);
        g.translate(-10, -30);
        g.setClip(null);
        g.setColor(new Color(0x9A3412)); g.drawString("BEFORE", 22, 48);
        // AFTER crop (clipped)
        g.setColor(Color.WHITE); g.fillRect(cw + 40, 30, cw, ch);
        g.setColor(new Color(0xE2E8F0)); g.drawRect(cw + 40, 30, cw, ch);
        g.setClip(cw + 40, 30, cw, ch);
        g.translate(cw + 40, 30);
        g.setColor(new Color(0x1B365D));
        drawPolyline(g, after, w);
        g.translate(-(cw + 40), -30);
        g.setClip(null);
        g.setColor(new Color(0x166534)); g.drawString("AFTER", cw + 52, 48);
        g.dispose();
        return img;
    }

    static void overlay(BufferedImage top, BufferedImage bottom, File out) throws Exception {
        BufferedImage img = new BufferedImage(
                Math.max(top.getWidth(), bottom.getWidth()),
                top.getHeight() + bottom.getHeight() + 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.drawImage(top, 0, 0, null);
        g.drawImage(bottom, 0, top.getHeight() + 8, null);
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    // ---- Bench ---------------------------------------------------------------------------

    static void bench(List<List<Phys>> events) {
        int repeats = 200;
        // legacy
        long t0 = System.nanoTime();
        for (int r = 0; r < repeats; r++) legacyRun(events);
        long legacyNs = System.nanoTime() - t0;
        // v2
        t0 = System.nanoTime();
        for (int r = 0; r < repeats; r++) v2Run(events);
        long v2Ns = System.nanoTime() - t0;
        long nLegacy = repeats * events.size();
        long nV2 = repeats * events.stream().mapToInt(List::size).sum();
        System.out.printf("bench legacy: %.1f ns/dispatched-event (%d events)%n", legacyNs / (double) nLegacy, nLegacy / repeats);
        System.out.printf("bench v2    : %.1f ns/physical-sample  (%d samples)%n", v2Ns / (double) nV2, nV2 / repeats);
    }

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : ".");
        // Panel 1: slow diagonal — positional smoothing parity of the PATH.
        List<List<Phys>> diagEvents = coalesce(slowDiagonal(600, 99L, 0.55f, 0.02f), 4);
        BufferedImage p1 = panel("slow diagonal (path)", legacyRun(diagEvents), "slow diagonal (path)", v2Run(diagEvents), 2.2f);

        // Panel 2: light pressure — WIDTH jitter is the target.
        List<List<Phys>> lightEvents = coalesce(slowDiagonal(600, 7L, 0.30f, 0.15f), 4);
        BufferedImage p2 = panel("light pressure (width)", legacyRun(lightEvents), "light pressure (width)", v2Run(lightEvents), 2.6f);

        overlay(p1, p2, new File(outDir, "before-after-smoothing.png"));
        System.out.println("wrote " + new File(outDir, "before-after-smoothing.png").getAbsolutePath());

        System.out.println("-- ingest microbench (desktop JVM proxy for low-end; not a device number) --");
        bench(diagEvents);

        // Policy goldens for the REPORT.
        System.out.printf("adaptiveAlpha(w=8, v=0)=%.4f  v=3 => %.4f  v>=6 => %.4f%n",
                StrokeSmoothingPolicy.INSTANCE.adaptiveAlpha(8, 0f),
                StrokeSmoothingPolicy.INSTANCE.adaptiveAlpha(8, 3f),
                StrokeSmoothingPolicy.INSTANCE.adaptiveAlpha(8, 9f));
        System.out.printf("pressureWindow(w=8)=%d%n", StrokeSmoothingPolicy.INSTANCE.pressureWindowSize(8));
    }
}
