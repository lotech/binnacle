package app.binnacle;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Live lights drawn over the car render: indicators (amber, following the car's own on/off flag,
 * which already flashes), tail/position lights (red), headlights and high beam (white). Own drawn
 * glows, no vendor art; lamp positions measured from the stock 800x434 renders (rear lamps found
 * from their red pixels, rear view = turn frame 12 = car_bg/middlecar). Front fog lights glow low
 * in the front bumper on the side views; the rear fog light glows red in the lower rear bumper on
 * the rear views. No brake lights: the feed has no brake field.
 */
final class CarLights {

    // lamp centres in the 800x434 frame
    private static final float[] BEHIND_LEFT = {300, 250}, BEHIND_RIGHT = {510, 250};
    private static final float[] LEFTSIDE_REAR = {716, 152}, LEFTSIDE_FRONT = {112, 172};
    private static final float[] RIGHTSIDE_REAR = {92, 152}, RIGHTSIDE_FRONT = {688, 172};
    private static final float[] LEFTSIDE_FOG = {92, 212}, RIGHTSIDE_FOG = {708, 212};
    /** Where the hidden nose meets the road in the rear view, for the headlight pool. */
    private static final float[] NOSE = {400, 120};
    private static final int BEAM_LAYERS = 16;
    private static final float[] BEHIND_FOG_LEFT = {310, 325}, BEHIND_FOG_RIGHT = {500, 325};
    /** Turn frames this close to a canonical view still get that view's lights (keeps hazards visible mid-turn). */
    private static final int VIEW_SLACK = 2;

    private static final int AMBER = 0xffffa21f, RED = 0xffff2a1a, WHITE = 0xfff4f8ff;

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RadialGradient amber = unit(AMBER), red = unit(RED), white = unit(WHITE);
    private LinearGradient beamShader;
    private float beamKeyLen = Float.NaN;
    private boolean beamKeyHigh;
    private LinearGradient sideLow, sideHigh;

    CarLights() {
        glow.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));   // lights add, never darken
    }

    /** A glow in unit space (radius 1 at the origin); spot() positions it with the canvas matrix. */
    private static RadialGradient unit(int c) {
        return new RadialGradient(0, 0, 1, new int[]{c, c & 0x99ffffff, c & 0x00ffffff},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP);
    }

    /** Lights for a parked/turned car. view = the turn frame on screen. */
    void drawParked(Canvas c, int view, RectF frame, CarState car) {
        float k = frame.width() / DoorScene.FRAME_W;
        if (Math.abs(view - DoorScene.VIEW_BEHIND) <= VIEW_SLACK) {
            drawRear(c, frame, k, car);
        } else if (view <= DoorScene.VIEW_LEFT + VIEW_SLACK || view >= DoorScene.VIEW_RIGHT - VIEW_SLACK) {
            boolean leftSide = view <= DoorScene.VIEW_LEFT + VIEW_SLACK;
            float[] rear = leftSide ? LEFTSIDE_REAR : RIGHTSIDE_REAR, front = leftSide ? LEFTSIDE_FRONT : RIGHTSIDE_FRONT;
            boolean indicator = leftSide ? car.turnLeft : car.turnRight;
            if (car.position || car.lowBeam) spot(c, frame, k, rear, 34, red, 0.55f);
            if (indicator) {
                spot(c, frame, k, rear, 46, amber, 0.95f);
                spot(c, frame, k, front, 34, amber, 0.9f);
            }
            if (car.highBeam || car.lowBeam) sideBeam(c, frame, k, front, leftSide, car.highBeam);
            if (car.highBeam) spot(c, frame, k, front, 70, white, 0.9f);
            else if (car.lowBeam) spot(c, frame, k, front, 44, white, 0.7f);
            if (car.frontFog) spot(c, frame, k, leftSide ? LEFTSIDE_FOG : RIGHTSIDE_FOG, 40, white, 0.75f);
        }
    }

    /**
     * Headlight light on the road in the driving view (car seen from behind); draw before the car.
     * In-car feedback: a beam starting at lamp height hid behind the car, so this is a wash that
     * starts at the car's nose, spills out either side of it, and fans up the road. The road beam
     * is several stacked trapezoids, each narrower and adding a little light, so its sides fade
     * out softly (like the Autopilot lane). High beam: brighter and reaching the horizon.
     */
    void drawBeam(Canvas c, RectF frame, CarState car, float horizonY) {
        if (!car.lowBeam && !car.highBeam) return;
        boolean high = car.highBeam;
        float w = frame.width(), cx = frame.centerX(), near = frame.top + frame.height() * 0.30f;
        float top = high ? horizonY + (near - horizonY) * 0.04f : horizonY + (near - horizonY) * 0.42f;
        float len = Math.round(near - top);
        if (len <= 0) return;
        if (beamShader == null || len != beamKeyLen || high != beamKeyHigh) {
            int a = high ? 0x12 : 0x0c;     // per layer; BEAM_LAYERS stack up in the middle
            beamShader = new LinearGradient(0, 0, 0, -len, new int[]{(a << 24) | 0xe8f0ff, ((a * 2 / 3) << 24) | 0xe8f0ff, 0x00e8f0ff},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            beamKeyLen = len;
            beamKeyHigh = high;
        }
        // base hidden behind the car body, widening past the car's sides, then narrowing with the road
        float halfBase = w * 0.15f, halfWide = w * 0.36f, halfFar = w * (high ? 0.10f : 0.20f), wideY = -len * 0.22f;
        beam.setShader(beamShader);
        c.save();
        c.translate(cx, near);
        for (int i = 0; i < BEAM_LAYERS; i++) {
            float f = 1f - i * 0.045f;
            path.reset();
            path.moveTo(-halfBase, 0);
            path.lineTo(halfBase, 0);
            path.quadTo(halfWide * f, wideY * 0.3f, halfWide * f, wideY);
            path.lineTo(halfFar * f, -len);
            path.lineTo(-halfFar * f, -len);
            path.lineTo(-halfWide * f, wideY);
            path.quadTo(-halfWide * f, wideY * 0.3f, -halfBase, 0);
            path.close();
            c.drawPath(path, beam);
        }
        c.restore();
        // light pooling on the road around the nose, visible either side of the car
        spot(c, frame, w / DoorScene.FRAME_W, NOSE, high ? 200 : 170, white, high ? 0.40f : 0.30f);
    }

    /**
     * Headlight beam on the parked side views: a soft cone from the front lamp out ahead of the
     * car (left side view: the car faces left), longer and brighter for high beam. Built once in
     * unit coordinates and placed with the canvas matrix.
     */
    private void sideBeam(Canvas c, RectF frame, float k, float[] lamp, boolean facingLeft, boolean high) {
        float len = high ? 380 : 260, spread = high ? 46 : 70;
        LinearGradient g = high ? sideHigh : sideLow;
        if (g == null) {
            g = new LinearGradient(0, 0, 1, 0, new int[]{high ? 0xccf4f8ff : 0x99f4f8ff, high ? 0x44f4f8ff : 0x30f4f8ff, 0x00f4f8ff},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            if (high) sideHigh = g; else sideLow = g;
        }
        path.reset();                         // unit cone: lamp at (0,0), opening toward +x
        path.moveTo(0, -0.06f);
        path.lineTo(1, -spread / len);
        path.lineTo(1, spread / len * 1.4f);  // light falls slightly toward the ground
        path.lineTo(0, 0.06f);
        path.close();
        beam.setShader(g);
        c.save();
        c.translate(frame.left + lamp[0] * k, frame.top + lamp[1] * k);
        c.scale(facingLeft ? -len * k : len * k, len * k);
        c.drawPath(path, beam);
        c.restore();
    }

    /** Rear lamps in the driving (rear) view; draw after the car. */
    void drawRearView(Canvas c, RectF frame, CarState car) {
        drawRear(c, frame, frame.width() / DoorScene.FRAME_W, car);
    }

    private void drawRear(Canvas c, RectF frame, float k, CarState car) {
        if (car.position || car.lowBeam) {
            spot(c, frame, k, BEHIND_LEFT, 44, red, 0.5f);
            spot(c, frame, k, BEHIND_RIGHT, 44, red, 0.5f);
        }
        if (car.rearFog) {
            spot(c, frame, k, BEHIND_FOG_LEFT, 40, red, 0.9f);
            spot(c, frame, k, BEHIND_FOG_RIGHT, 40, red, 0.9f);
        }
        if (car.turnLeft) spot(c, frame, k, BEHIND_LEFT, 58, amber, 1f);
        if (car.turnRight) spot(c, frame, k, BEHIND_RIGHT, 58, amber, 1f);
    }

    /** A soft elliptical glow of radius r (frame px) at lamp position p. No per-frame shader changes. */
    private void spot(Canvas c, RectF frame, float k, float[] p, float r, RadialGradient g, float alpha) {
        float rr = r * k;
        glow.setShader(g);
        glow.setAlpha(Math.round(alpha * 255));
        c.save();
        c.translate(frame.left + p[0] * k, frame.top + p[1] * k);
        c.scale(rr, rr * 0.7f);
        c.drawCircle(0, 0, 1, glow);
        c.restore();
    }
}
