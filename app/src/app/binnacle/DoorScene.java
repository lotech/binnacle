package app.binnacle;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Process;
import android.util.LruCache;

import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Parked car with animated doors, using the stock ZIQI renders the unit already has (same logic as
 * the stock launcher's CarDoor3DLayout, re-implemented):
 * - turn/0..24 is a 25-frame turn: 0 = left side, 12 = from behind, 24 = right side.
 * - Doors open on the left only -> turn to 0; right only -> 24; both sides -> 12; none -> 0.
 * - Each door is a 7-frame opening animation drawn over the car at a fixed offset in the 800x434
 *   frame: door_xxS sets for the side views, door_xxM ("face") for the view from behind.
 * - The turn frames have every door closed, so while a door is open the car is drawn like the
 *   stock launcher does: the door-less body body/{left,middle,right} plus each
 *   door of that view at its own frame (0 = closed). Drawing open doors over a turn frame showed
 *   the closed door underneath (in-car, 29 Oct).
 * Frames are decoded lazily on a background thread the first time a door opens and kept in a
 * bounded cache; a frame that fails to load is skipped (never retried in a loop).
 * Boot / frunk open: the door-less body is swapped for one rendered with the lid(s) open
 * (lids/<view>_{boot,frunk,both}.png); the boot alone turns the car to the view from behind. Older
 * packs without lids/ fall back to tinting the lid with the stock-style silhouette (drawCovers).
 */
final class DoorScene {

    /** doorval bits (stock launcher order): RF, LF, RB, LB. 0x80 = driver's door (RHD) verified in the car. */
    static final int[] DOOR_BITS = {0x80, 0x40, 0x20, 0x10};
    private static final String[] DOOR_NAMES = {"right_front", "left_front", "right_back", "left_back"};
    /** Door layer offsets inside the 800x434 car frame (stock style0_zoom_door_layout). */
    private static final float[][] DOOR_XY = {{291, 28}, {43, 28}, {138, 69}, {138, 67}};
    static final float FRAME_W = 800, FRAME_H = 434;
    static final int VIEW_LEFT = 0, VIEW_BEHIND = 12, VIEW_RIGHT = 24;
    /** Doors seen in each resting view (indexes into DOOR_BITS): left side, behind, right side. */
    private static final int[] LEFT_DOORS = {1, 3}, ALL_DOORS = {0, 1, 2, 3}, RIGHT_DOORS = {0, 2};
    private static final int TURN_FRAMES = 25, DOOR_FRAMES = 7;
    private static final long FRAME_MS = 40;   // stock animation speed

    /** Decoded frames kept, by bytes. Frames are stored at drawn size (~0.7x), so all 81 fit (~35 MB). */
    private static final int CACHE_BYTES = 64 * 1024 * 1024;

    private final Runnable onLoaded;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService loader;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final HashSet<String> pending = new HashSet<>();
    private final HashSet<String> failed = new HashSet<>();   // missing / undecodable: never retried
    private final HashSet<String> wanted = new HashSet<>();   // requested by draw(): redraw when loaded
    private boolean prefetched;
    private float scale = 1f;                                   // stored frame size vs the 800x434 render
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private CarArt.Source src;
    private int turn = VIEW_LEFT;                         // current turn frame (animated)
    private int drawnView = VIEW_LEFT;                    // the frame actually drawn last
    private int forcedView = -1;
    private final float[] open = new float[4];            // per-door opening frame 0..6 (animated)
    private long lastMs;

    DoorScene(Runnable onLoaded) { this.onLoaded = onLoaded; }

    /** Which turn frame the open doors call for. */
    static int targetView(int doors) {
        if (doors <= 0) return VIEW_LEFT;
        boolean right = (doors & (0x80 | 0x20)) != 0, left = (doors & (0x40 | 0x10)) != 0;
        if (left && right) return VIEW_BEHIND;
        // the boot alone: from behind, where the open boot shows best (the side views open it only halfway)
        if (!left && !right && (doors & BOOT_BIT) != 0 && (doors & FRUNK_BIT) == 0) return VIEW_BEHIND;
        if (right) return VIEW_RIGHT;
        return VIEW_LEFT;
    }

    /**
     * Force the car to face a view regardless of doors (doors close first), e.g. VIEW_BEHIND while
     * handing over to the driving view; -1 returns control to the doors.
     */
    void force(int view) { forcedView = view; }

    /** Jump straight to a view without animating (e.g. the dash starts while driving). */
    void snapTo(int view) {
        turn = view;
        drawnView = view;
        java.util.Arrays.fill(open, 0);
    }

    /**
     * True once the car has logically reached view, or can't get there because a frame on the way
     * failed to load (so callers waiting on the turn never wait for ever).
     */
    boolean reached(int view) {
        if (turn == view) return true;
        int next = turn + (view > turn ? 1 : -1);
        return failed.contains(turnPath(next));
    }

    /** The turn frame actually on screen (0 left side, 12 behind, 24 right side, others mid-turn). */
    int view() { return drawnView; }

    /** Call when the parked view stops being drawn, so the next draw doesn't jump ahead. */
    void pause() { lastMs = 0; }

    /** Free the decoded frames and the loader thread (view detached or memory pressure). */
    void release() {
        cache.evictAll();
        pending.clear();
        wanted.clear();
        prefetched = false;
        if (loader != null) {
            loader.shutdownNow();
            loader = null;
        }
        lastMs = 0;
    }

    /**
     * Get the frames ready for drawing into frame without drawing: picks up a colour or size
     * change and starts the background preload. Called by draw(), and while driving so the first
     * hand-back to Park already has the turn frames (it starts from frame 12). Returns false if
     * there are no renders to use.
     */
    boolean warm(CarArt art, RectF frame) {
        if (art == null || art.src == null || art.side == null) return false;
        if (!art.src.equals(src)) {           // colour changed: drop frames of the old colour
            release();
            failed.clear();
            src = art.src;
        }
        // frames are kept at the size they are drawn (decoding full size and scaling every frame
        // costs memory and GPU); a big size change drops and reloads them
        float wantScale = Math.min(1f, Math.round(frame.width() / FRAME_W * 20f) / 20f);
        if (wantScale > 0 && Math.abs(wantScale - scale) > 0.049f) {
            release();
            scale = wantScale;
        }
        if (!prefetched) prefetch();
        return true;
    }

    /**
     * Draws the parked car into frame (the 800x434 render scaled to fit). Returns true while it is
     * animating and needs the next vsync; loads in progress call onLoaded instead.
     */
    boolean draw(Canvas c, CarArt art, int doors, RectF frame) {
        if (!warm(art, frame)) return false;
        long now = SystemClock.uptimeMillis();
        int steps = lastMs == 0 ? 0 : (int) Math.min(DOOR_FRAMES, (now - lastMs) / FRAME_MS);
        if (lastMs == 0 || steps > 0) lastMs = lastMs == 0 ? now : lastMs + steps * FRAME_MS;
        boolean animating = false;

        // 1. turn toward the view the doors need; doors close first, then the car turns back
        int target = forcedView >= 0 ? forcedView : targetView(Math.max(0, doors));
        boolean anyOpen = false;
        for (float o : open) anyOpen |= o > 0;
        int wantTurn = (anyOpen && turn != target) ? turn : target;
        if (turn != wantTurn) {
            animating = true;
            for (int i = 0; i < Math.max(steps, 0) && turn != wantTurn; i++) {
                int next = turn + (wantTurn > turn ? 1 : -1);
                String path = turnPath(next);
                if (failed.contains(path)) { turn = wantTurn; break; }   // frame missing: jump to the end
                if (frame(path) == null) { animating = false; break; }   // loading: onLoaded redraws, no vsync spin
                turn = next;
            }
        }
        // 2. doors: open once the car faces the right way, close otherwise
        boolean facing = turn == target;
        boolean anyShown = false;
        for (int d = 0; d < 4; d++) {
            boolean want = forcedView < 0 && doors > 0 && (doors & DOOR_BITS[d]) != 0 && facing;
            float goal = want ? DOOR_FRAMES - 1 : 0;
            if (open[d] != goal) {
                open[d] = approach(open[d], goal, steps);
                animating = true;
            }
            anyShown |= Math.round(open[d]) > 0 || want;
        }

        // 3. with a door, boot or frunk open at a resting view: the door-less body (with the lids
        //    open, when the pack has them) plus every door of that view
        int[] viewDoors = turn == VIEW_LEFT ? LEFT_DOORS : turn == VIEW_RIGHT ? RIGHT_DOORS : turn == VIEW_BEHIND ? ALL_DOORS : null;
        String lids = forcedView < 0 && facing ? lidPath(turn, doors) : null;
        Bitmap lidBody = lids == null ? null : loaded(lids);
        boolean lidsShown = lidBody != null;            // not loaded yet / old pack: tinted covers instead
        if ((anyShown || lidBody != null) && viewDoors != null) {
            Bitmap body = lidBody != null ? lidBody : loaded(bodyPath(turn));
            Bitmap[] layers = new Bitmap[4];
            boolean ready = body != null;
            for (int d : viewDoors) {
                layers[d] = loaded(doorPath(d, turn == VIEW_BEHIND, Math.round(open[d])));
                ready &= layers[d] != null;
            }
            if (ready) {
                drawnView = turn;
                c.drawBitmap(body, null, frame, bmp);
                for (int d : viewDoors) drawDoor(c, d, layers[d], frame);
                if (!lidsShown) drawCovers(c, doors, frame);
                if (!animating) lastMs = 0;
                return animating;
            }
            // a frame is still loading (or missing): the old way below, closed doors baked in
        }

        Bitmap turnBmp = turn == VIEW_LEFT ? art.side : frame(turnPath(turn));
        drawnView = turn;
        if (turnBmp == null) {                                         // fall back to the side view
            turnBmp = art.side;
            drawnView = VIEW_LEFT;
        }
        c.drawBitmap(turnBmp, null, frame, bmp);
        for (int d = 0; d < 4; d++) {
            int f = Math.round(open[d]);
            if (f <= 0) continue;
            String path = doorPath(d, turn == VIEW_BEHIND, f);
            Bitmap door = failed.contains(path) ? null : frame(path);
            if (door != null) drawDoor(c, d, door, frame);
        }
        drawCovers(c, doors, frame);
        if (!animating) lastMs = 0;
        return animating;
    }

    /** doorval bits for the boot and the frunk (0x08 verified in the car; 0x04 from the stock code). */
    static final int BOOT_BIT = 0x08, FRUNK_BIT = 0x04;
    /** Where the stock launcher puts its boot / frunk overlays in the 800x434 frame (style0_zoom_door_layout). */
    private static final float[] BOOT_XY = {75, 37}, FRUNK_XY = {82, 7};

    /**
     * Boot / frunk open: there are no opening frames for them, so, like the stock launcher, the
     * boot or bonnet is tinted with the stock silhouette for the current resting view
     * (covers/{back,front}cover_<tint>_{mid,left,right}.png beside the car renders; red, or white
     * on a white car and yellow on a red one, as stock). Missing files: nothing drawn.
     */
    private void drawCovers(Canvas c, int doors, RectF frame) {
        if (doors <= 0 || (doors & (BOOT_BIT | FRUNK_BIT)) == 0 || forcedView >= 0) return;
        String side = turn == VIEW_LEFT ? "left" : turn == VIEW_RIGHT ? "right" : turn == VIEW_BEHIND ? "mid" : null;
        if (side == null) return;                                       // mid-turn: wait for a resting view
        String name = src == null ? "" : src.name;
        String tint = name.contains("white") ? "white" : name.contains("red") ? "yellow" : "red";
        if ((doors & BOOT_BIT) != 0) drawCover(c, "covers/backcover_" + tint + "_" + side + ".png", BOOT_XY, frame);
        if ((doors & FRUNK_BIT) != 0) drawCover(c, "covers/frontcover_" + tint + "_" + side + ".png", FRUNK_XY, frame);
    }

    private void drawCover(Canvas c, String rel, float[] xy, RectF frame) {
        Bitmap b = loaded(rel);
        if (b == null) return;
        float k = frame.width() / FRAME_W, x = frame.left + xy[0] * k, y = frame.top + xy[1] * k, dk = k / scale;
        r.set(x, y, x + b.getWidth() * dk, y + b.getHeight() * dk);
        cover.setAlpha(215);
        c.drawBitmap(b, null, r, cover);
    }

    private final Paint cover = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    private void drawDoor(Canvas c, int d, Bitmap door, RectF frame) {
        float k = frame.width() / FRAME_W;
        float x = frame.left + DOOR_XY[d][0] * k, y = frame.top + DOOR_XY[d][1] * k;
        float dk = k / scale;              // door bitmaps are stored at `scale`
        r.set(x, y, x + door.getWidth() * dk, y + door.getHeight() * dk);
        c.drawBitmap(door, null, r, bmp);
    }

    /** frame(), but null for one that already failed (so it isn't requested again). */
    private Bitmap loaded(String rel) { return failed.contains(rel) ? null : frame(rel); }

    /** Height/width of the render, for callers laying out the frame. */
    static float aspect() { return FRAME_H / FRAME_W; }

    private static float approach(float v, float goal, int steps) {
        return v < goal ? Math.min(goal, v + steps) : Math.max(goal, v - steps);
    }

    private String turnPath(int i) { return "turn/" + i + ".png"; }

    private static String bodyPath(int view) {
        return "body/" + (view == VIEW_LEFT ? "left" : view == VIEW_RIGHT ? "right" : "middle") + ".png";
    }

    /** The body with the open lid(s) for this resting view, or null with neither open / mid-turn. */
    private static String lidPath(int view, int doors) {
        if (doors <= 0) return null;
        boolean boot = (doors & BOOT_BIT) != 0, frunk = (doors & FRUNK_BIT) != 0;
        if (!boot && !frunk) return null;
        String v = view == VIEW_LEFT ? "left" : view == VIEW_RIGHT ? "right" : view == VIEW_BEHIND ? "middle" : null;
        if (v == null) return null;
        return "lids/" + v + "_" + (boot && frunk ? "both" : boot ? "boot" : "frunk") + ".png";
    }

    private String doorPath(int d, boolean behind, int f) {
        return "doors/" + (behind ? "face_" : "side_") + DOOR_NAMES[d] + "/" + f + ".png";
    }

    /**
     * Decode every turn and door frame in the background once, so the first turn or door opening
     * is as smooth as later ones (the first run used to stutter while frames decoded). Low priority
     * and paced, because the unit's CPU is already busy; turn frames first, then the driver's side.
     */
    private void prefetch() {
        prefetched = true;
        for (int i = 1; i < TURN_FRAMES; i++) load(turnPath(i), false);
        for (int v : new int[]{VIEW_RIGHT, VIEW_LEFT, VIEW_BEHIND}) load(bodyPath(v), false);
        int[] order = {0, 1, 2, 3};             // RF (driver), LF, RB, LB
        for (boolean behind : new boolean[]{false, true})
            for (int d : order)
                for (int f = 0; f < DOOR_FRAMES; f++) load(doorPath(d, behind, f), false);
    }

    /** Cached bitmap, or null and a background load (onLoaded runs when a frame draw() wanted lands). */
    private Bitmap frame(String rel) {
        Bitmap b = cache.get(rel);
        if (b == null && !failed.contains(rel)) {
            wanted.add(rel);
            load(rel, true);
        }
        return b;
    }

    private void load(final String rel, final boolean urgent) {
        if (src == null || cache.get(rel) != null || pending.contains(rel) || failed.contains(rel)) return;
        pending.add(rel);
        final CarArt.Source d = src;
        final float sc = scale;
        if (loader == null) {
            loader = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override public Thread newThread(final Runnable r) {
                    Thread t = new Thread(new Runnable() {
                        @Override public void run() {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                            r.run();
                        }
                    }, "car-frames");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        loader.execute(new Runnable() {
            @Override public void run() {
                Bitmap b = d.decode(rel);
                if (b != null && sc < 0.99f) {
                    try {
                        Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * sc)),
                                Math.max(1, Math.round(b.getHeight() * sc)), true);
                        if (s != b) b.recycle();
                        b = s;
                    } catch (Throwable t) {
                        b = null;
                    }
                }
                final Bitmap loaded = b;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!d.equals(src) || sc != scale) return;   // stale: colour or size changed
                        pending.remove(rel);
                        if (loaded != null) cache.put(rel, loaded); else failed.add(rel);
                        if (wanted.remove(rel)) onLoaded.run();
                    }
                });
                if (!urgent) SystemClock.sleep(15);   // pace the prefetch on the busy CPU
            }
        });
    }
}
