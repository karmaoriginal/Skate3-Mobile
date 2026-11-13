package chat.buku.skate3;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLControllerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * A lightweight multitouch Xbox-style controller layered over the SDL surface.
 *
 * Customisation (persisted in SharedPreferences):
 *   - Layout presets: Default, Compact, Big buttons, Mirrored (left-handed)
 *   - Button size: S / M / L
 *   - Opacity: Low / Medium / High
 *   - Haptic feedback: On / Off
 *   - Free layout editing: drag any control where you want it
 *
 * The gear pill next to the HIDE/TOUCH pill opens the setup panel. While the
 * panel is open the game never receives stray input; in edit mode controls
 * are dragged instead of pressed.
 */
public final class TouchControllerView extends View {
    private static final int DPAD_UP = 0x0001;
    private static final int DPAD_DOWN = 0x0002;
    private static final int DPAD_LEFT = 0x0004;
    private static final int DPAD_RIGHT = 0x0008;
    private static final int START = 0x0010;
    private static final int BACK = 0x0020;
    private static final int L3 = 0x0040;
    private static final int R3 = 0x0080;
    private static final int LB = 0x0100;
    private static final int RB = 0x0200;
    private static final int A = 0x1000;
    private static final int B = 0x2000;
    private static final int X = 0x4000;
    private static final int Y = 0x8000;
    private static final String PREFS = "skate3_touch_controller";
    private static final String PREF_VISIBLE = "visible";
    private static final String PREF_LAYOUT = "layout";
    private static final String PREF_SCALE = "scale";
    private static final String PREF_ALPHA = "alpha";
    private static final String PREF_HAPTICS = "haptics";
    private static final String PREF_POS = "pos_";

    private static final String LAYOUT_DEFAULT = "default";
    private static final String LAYOUT_COMPACT = "compact";
    private static final String LAYOUT_BIG = "big";
    private static final String LAYOUT_MIRROR = "mirror";
    private static final String[] LAYOUT_ORDER = {
        LAYOUT_DEFAULT, LAYOUT_COMPACT, LAYOUT_BIG, LAYOUT_MIRROR
    };
    private static final String[] LAYOUT_LABELS = {
        "Default", "Compact", "Big buttons", "Mirrored"
    };
    private static final float[] SIZE_STEPS = {0.80f, 1.0f, 1.25f};
    private static final String[] SIZE_LABELS = {"S", "M", "L"};
    private static final float[] ALPHA_STEPS = {0.55f, 0.80f, 1.0f};
    private static final String[] ALPHA_LABELS = {"Low", "Medium", "High"};

    private static final int ACTION_LAYOUT = 1;
    private static final int ACTION_SIZE = 2;
    private static final int ACTION_OPACITY = 3;
    private static final int ACTION_HAPTICS = 4;
    private static final int ACTION_EDIT = 5;
    private static final int ACTION_RESET = 6;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint editRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Control> controls = new ArrayList<>();
    private final SparseArray<Control> pointers = new SparseArray<>();
    private final SharedPreferences preferences;
    private boolean controlsVisible;
    private boolean settingsOpen;
    private boolean editMode;
    private Control dragTarget;
    private int dragPointerId = -1;
    private RectF toggle = new RectF();
    private RectF gear = new RectF();
    private float lastWidth;
    private float lastHeight;

    private Stick leftStick;
    private Stick rightStick;
    private Trigger leftTrigger;
    private Trigger rightTrigger;

    public TouchControllerView(Context context) {
        super(context);
        setWillNotDraw(false);
        setFocusable(false);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2));
        label.setColor(Color.WHITE);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panelFill.setStyle(Paint.Style.FILL);
        panelStroke.setStyle(Paint.Style.STROKE);
        panelStroke.setStrokeWidth(dp(1.5f));
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        controlsVisible = preferences.contains(PREF_VISIBLE)
            ? preferences.getBoolean(PREF_VISIBLE, true)
            : !hasPhysicalController();
    }

    public void clearInput() {
        for (Control control : controls) control.release();
        pointers.clear();
        dragTarget = null;
        dragPointerId = -1;
        sendState();
        invalidate();
    }

    public void disconnect() {
        for (Control control : controls) control.release();
        pointers.clear();
        dragTarget = null;
        dragPointerId = -1;
        TouchControllerBridge.trySetState(-1, 0, 0, 0, 0, 0, 0);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        sendState();
    }

    @Override
    protected void onDetachedFromWindow() {
        disconnect();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        buildControls(width, height);
    }

    // ---- Settings state -----------------------------------------------------

    private String currentLayout() {
        String value = preferences.getString(PREF_LAYOUT, LAYOUT_DEFAULT);
        for (String candidate : LAYOUT_ORDER) {
            if (candidate.equals(value)) return candidate;
        }
        return LAYOUT_DEFAULT;
    }

    private int layoutIndex() {
        String layout = currentLayout();
        for (int i = 0; i < LAYOUT_ORDER.length; ++i) {
            if (LAYOUT_ORDER[i].equals(layout)) return i;
        }
        return 0;
    }

    private float sizeMultiplier() {
        return nearestStep(preferences.getFloat(PREF_SCALE, 1.0f), SIZE_STEPS);
    }

    private float alphaMultiplier() {
        return nearestStep(preferences.getFloat(PREF_ALPHA, 1.0f), ALPHA_STEPS);
    }

    private float nearestStep(float value, float[] steps) {
        float best = steps[0];
        float bestDistance = Math.abs(value - best);
        for (float step : steps) {
            float distance = Math.abs(value - step);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = step;
            }
        }
        return best;
    }

    private static int stepIndex(float value, float[] steps) {
        int best = 0;
        float bestDistance = Math.abs(value - steps[0]);
        for (int i = 1; i < steps.length; ++i) {
            float distance = Math.abs(value - steps[i]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private boolean hapticsEnabled() {
        return preferences.getBoolean(PREF_HAPTICS, true);
    }

    /** Scale a base 0-255 alpha by the configured opacity. */
    private int a(int base) {
        return Math.max(0, Math.min(255, Math.round(base * alphaMultiplier())));
    }

    private void cycleLayout() {
        int next = (layoutIndex() + 1) % LAYOUT_ORDER.length;
        preferences.edit().putString(PREF_LAYOUT, LAYOUT_ORDER[next]).apply();
        rebuild();
    }

    private void cycleSize() {
        int next = (stepIndex(sizeMultiplier(), SIZE_STEPS) + 1) % SIZE_STEPS.length;
        preferences.edit().putFloat(PREF_SCALE, SIZE_STEPS[next]).apply();
        rebuild();
    }

    private void cycleOpacity() {
        int next = (stepIndex(alphaMultiplier(), ALPHA_STEPS) + 1) % ALPHA_STEPS.length;
        preferences.edit().putFloat(PREF_ALPHA, ALPHA_STEPS[next]).apply();
        invalidate();
    }

    private void toggleHaptics() {
        preferences.edit().putBoolean(PREF_HAPTICS, !hapticsEnabled()).apply();
        invalidate();
    }

    private void toggleEditMode() {
        editMode = !editMode;
        if (!editMode && dragTarget != null) {
            persistPosition(dragTarget);
            dragTarget = null;
            dragPointerId = -1;
        }
        invalidate();
    }

    private void resetPositions() {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(PREF_POS)) editor.remove(key);
        }
        editor.apply();
        rebuild();
    }

    private void rebuild() {
        if (lastWidth > 0 && lastHeight > 0) buildControls(lastWidth, lastHeight);
        invalidate();
    }

    private void applySavedPosition(Control control, float width, float height) {
        if (!preferences.contains(PREF_POS + control.id + "_x")) return;
        if (!preferences.contains(PREF_POS + control.id + "_y")) return;
        float x = clamp(preferences.getFloat(PREF_POS + control.id + "_x", 0.5f), 0.02f, 0.98f);
        float y = clamp(preferences.getFloat(PREF_POS + control.id + "_y", 0.5f), 0.05f, 0.95f);
        control.recenter(x * width, y * height);
    }

    private void persistPosition(Control control) {
        if (lastWidth <= 0 || lastHeight <= 0) return;
        preferences.edit()
            .putFloat(PREF_POS + control.id + "_x", control.cx() / lastWidth)
            .putFloat(PREF_POS + control.id + "_y", control.cy() / lastHeight)
            .apply();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- Layout -------------------------------------------------------------

    private void buildControls(float width, float height) {
        lastWidth = width;
        lastHeight = height;
        controls.clear();

        String layout = currentLayout();
        boolean mirror = LAYOUT_MIRROR.equals(layout);
        boolean compact = LAYOUT_COMPACT.equals(layout);
        boolean big = LAYOUT_BIG.equals(layout);

        float unitW = big ? 15.0f : (compact ? 17.0f : 16.0f);
        float unitH = big ? 8.6f : (compact ? 9.6f : 9.0f);
        float unit = Math.min(width / unitW, height / unitH);
        float sizeScale = (big ? 1.22f : (compact ? 0.90f : 1.0f)) * sizeMultiplier();
        float stickRadius = unit * 0.78f * sizeScale;
        float buttonRadius = unit * 0.38f * sizeScale;
        float shoulderW = unit * 1.25f * sizeScale;
        float shoulderH = unit * 0.55f * sizeScale;

        // Preset-relative anchor points (before the mirror flip).
        float stickY = 0.73f;
        float rStickY = 0.77f;
        float dpadY = 0.72f;
        float faceY = 0.68f;
        float faceSpacing = 0.68f;
        float shoulderY = 0.10f;
        float menuY = 0.79f;
        float lStickX = 0.16f, dpadX = 0.32f, rStickX = 0.67f;
        float faceX = 0.85f, triggerL = 0.07f, triggerR = 0.93f;
        float bumperL = 0.20f, bumperR = 0.80f;
        float backX = 0.44f, startX = 0.55f;
        float l3X = 0.07f, l3Y = 0.48f, r3X = 0.73f, r3Y = 0.48f;
        if (compact) {
            stickY = 0.76f;
            rStickY = 0.79f;
            dpadY = 0.745f;
            faceY = 0.70f;
            faceSpacing = 0.60f;
            shoulderY = 0.105f;
            menuY = 0.80f;
            lStickX = 0.20f;
            dpadX = 0.345f;
            rStickX = 0.63f;
            faceX = 0.83f;
            triggerL = 0.085f;
            triggerR = 0.915f;
            bumperL = 0.215f;
            bumperR = 0.785f;
            backX = 0.45f;
            startX = 0.55f;
            l3X = 0.095f;
            l3Y = 0.50f;
            r3X = 0.705f;
            r3Y = 0.50f;
        } else if (big) {
            stickY = 0.725f;
            rStickY = 0.77f;
            dpadY = 0.715f;
            faceY = 0.675f;
            faceSpacing = 0.70f;
            shoulderY = 0.10f;
            menuY = 0.80f;
            lStickX = 0.155f;
            dpadX = 0.315f;
            rStickX = 0.665f;
            faceX = 0.855f;
            triggerL = 0.075f;
            triggerR = 0.925f;
            bumperL = 0.20f;
            bumperR = 0.80f;
            backX = 0.44f;
            startX = 0.56f;
            l3X = 0.07f;
            l3Y = 0.475f;
            r3X = 0.73f;
            r3Y = 0.475f;
        }
        // Mirror horizontally for left-handed play: face buttons and the D-pad
        // move to the left hand, the sticks move to the right.
        float lStickPX = mirror ? 1f - lStickX : lStickX;
        float rStickPX = mirror ? 1f - rStickX : rStickX;
        float dpadPX = mirror ? 1f - dpadX : dpadX;
        float facePX = mirror ? 1f - faceX : faceX;
        float triggerLXP = mirror ? 1f - triggerL : triggerL;
        float triggerRXP = mirror ? 1f - triggerR : triggerR;
        float bumperLXP = mirror ? 1f - bumperL : bumperL;
        float bumperRXP = mirror ? 1f - bumperR : bumperR;
        float backPX = mirror ? 1f - backX : backX;
        float startPX = mirror ? 1f - startX : startX;
        float l3PX = mirror ? 1f - l3X : l3X;
        float r3PX = mirror ? 1f - r3X : r3X;

        leftStick = new Stick("ls", "L", width * lStickPX, height * stickY,
                              stickRadius, true);
        rightStick = new Stick("rs", "R", width * rStickPX, height * rStickY,
                               stickRadius, false);
        controls.add(leftStick);
        controls.add(rightStick);

        controls.add(new Dpad("dpad", width * dpadPX, height * dpadY, unit * 0.98f * sizeScale));

        float facePY = faceY * height;
        float faceSpread = faceSpacing * unit;
        controls.add(new ButtonControl("a", "A", facePX * width, facePY + faceSpread,
                                       buttonRadius, A));
        controls.add(new ButtonControl("b", "B", facePX * width + faceSpread, facePY,
                                       buttonRadius, B));
        controls.add(new ButtonControl("x", "X", facePX * width - faceSpread, facePY,
                                       buttonRadius, X));
        controls.add(new ButtonControl("y", "Y", facePX * width, facePY - faceSpread,
                                       buttonRadius, Y));

        leftTrigger = new Trigger("lt", "LT", width * triggerLXP, height * shoulderY,
                                  shoulderW, shoulderH, true);
        rightTrigger = new Trigger("rt", "RT", width * triggerRXP, height * shoulderY,
                                   shoulderW, shoulderH, false);
        controls.add(leftTrigger);
        controls.add(new RectButton("lb", "LB", width * bumperLXP, height * shoulderY,
                                    shoulderW, shoulderH, LB));
        controls.add(new RectButton("rb", "RB", width * bumperRXP, height * shoulderY,
                                    shoulderW, shoulderH, RB));
        controls.add(rightTrigger);

        controls.add(new RectButton("back", "BACK", width * backPX, height * menuY,
                                    unit * 0.82f * sizeScale, unit * 0.45f * sizeScale, BACK));
        controls.add(new RectButton("start", "START", width * startPX, height * menuY,
                                    unit * 0.92f * sizeScale, unit * 0.45f * sizeScale, START));
        controls.add(new ButtonControl("l3", "L3", width * l3PX, height * l3Y,
                                       buttonRadius * 0.82f, L3));
        controls.add(new ButtonControl("r3", "R3", width * r3PX, height * r3Y,
                                       buttonRadius * 0.82f, R3));

        for (Control control : controls) applySavedPosition(control, width, height);

        float toggleWidth = controlsVisible ? unit * 1.05f : unit * 1.35f;
        float toggleTop = Math.max(dp(18), height * 0.035f);
        toggle.set(width * 0.5f - toggleWidth * 0.5f, toggleTop,
                   width * 0.5f + toggleWidth * 0.5f, toggleTop + unit * 0.46f);
        float gearSize = unit * 0.46f;
        gear.set(toggle.right + dp(10), toggleTop,
                 toggle.right + dp(10) + gearSize, toggleTop + gearSize);
        sendState();
        invalidate();
    }

    // ---- Drawing --------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawToggle(canvas);
        drawGear(canvas);
        if (controlsVisible) {
            for (Control control : controls) control.draw(canvas);
            if (editMode) {
                for (Control control : controls) drawEditRing(canvas, control);
                drawHint(canvas);
            }
        }
        if (settingsOpen) drawPanel(canvas);
    }

    private void drawToggle(Canvas canvas) {
        fill.setColor(Color.argb(a(175), 15, 15, 18));
        stroke.setColor(Color.argb(a(220), 255, 112, 28));
        canvas.drawRoundRect(toggle, dp(9), dp(9), fill);
        canvas.drawRoundRect(toggle, dp(9), dp(9), stroke);
        drawLabel(canvas, controlsVisible ? "HIDE" : "TOUCH", toggle.centerX(),
                  toggle.centerY(), dp(11));
    }

    private void drawGear(Canvas canvas) {
        fill.setColor(settingsOpen
            ? Color.argb(a(220), 255, 112, 28)
            : Color.argb(a(175), 15, 15, 18));
        stroke.setColor(Color.argb(a(220), 255, 112, 28));
        canvas.drawRoundRect(gear, dp(9), dp(9), fill);
        canvas.drawRoundRect(gear, dp(9), dp(9), stroke);
        drawLabel(canvas, "⚙", gear.centerX(), gear.centerY(), dp(13));
    }

    private void drawEditRing(Canvas canvas, Control control) {
        RectF bounds = control.editBounds();
        editRing.setStyle(Paint.Style.STROKE);
        editRing.setStrokeWidth(dp(2.5f));
        editRing.setColor(Color.argb(235, 255, 112, 28));
        canvas.drawRoundRect(bounds, dp(10), dp(10), editRing);
        editRing.setStyle(Paint.Style.FILL);
        editRing.setColor(Color.argb(45, 255, 112, 28));
        canvas.drawRoundRect(bounds, dp(10), dp(10), editRing);
    }

    private void drawHint(Canvas canvas) {
        String text = "DRAG ANY CONTROL TO MOVE IT";
        label.setTextSize(dp(11));
        float textWidth = label.measureText(text);
        float pad = dp(14);
        RectF bar = new RectF(lastWidth * 0.5f - textWidth * 0.5f - pad,
                              lastHeight - dp(34),
                              lastWidth * 0.5f + textWidth * 0.5f + pad,
                              lastHeight - dp(8));
        fill.setColor(Color.argb(220, 15, 15, 18));
        stroke.setColor(Color.argb(235, 255, 112, 28));
        canvas.drawRoundRect(bar, dp(9), dp(9), fill);
        canvas.drawRoundRect(bar, dp(9), dp(9), stroke);
        drawLabel(canvas, text, bar.centerX(), bar.centerY(), dp(11));
    }

    private float panelWidth() {
        float unit = Math.min(lastWidth / 16f, lastHeight / 9f);
        return Math.min(lastWidth - dp(24), unit * 5.1f);
    }

    private float panelRowHeight() {
        float unit = Math.min(lastWidth / 16f, lastHeight / 9f);
        return unit * 0.46f;
    }

    private RectF panelBounds() {
        float unit = Math.min(lastWidth / 16f, lastHeight / 9f);
        float width = panelWidth();
        float pad = unit * 0.16f;
        float title = unit * 0.44f;
        float height = pad * 2f + title + panelRowHeight() * 6f;
        float left = (lastWidth - width) * 0.5f;
        float top = toggle.bottom + unit * 0.14f;
        return new RectF(left, top, left + width, top + height);
    }

    private List<PanelRow> buildPanelRows() {
        RectF panel = panelBounds();
        float unit = Math.min(lastWidth / 16f, lastHeight / 9f);
        float pad = unit * 0.16f;
        float title = unit * 0.44f;
        float rowHeight = panelRowHeight();
        float top = panel.top + pad + title;
        List<PanelRow> rows = new ArrayList<>(6);
        String layoutLabel = LAYOUT_LABELS[layoutIndex()];
        String sizeLabel = SIZE_LABELS[stepIndex(sizeMultiplier(), SIZE_STEPS)];
        String alphaLabel = ALPHA_LABELS[stepIndex(alphaMultiplier(), ALPHA_STEPS)];
        String[] labels = {
            "Layout", "Button size", "Opacity", "Haptics", "Edit layout", "Reset positions"
        };
        String[] values = {
            layoutLabel, sizeLabel, alphaLabel,
            hapticsEnabled() ? "On" : "Off",
            editMode ? "Done" : "Edit",
            "Clear"
        };
        int[] actions = {
            ACTION_LAYOUT, ACTION_SIZE, ACTION_OPACITY, ACTION_HAPTICS, ACTION_EDIT, ACTION_RESET
        };
        for (int i = 0; i < labels.length; ++i) {
            RectF bounds = new RectF(panel.left + dp(6), top + i * rowHeight,
                                     panel.right - dp(6), top + (i + 1) * rowHeight);
            rows.add(new PanelRow(bounds, labels[i], values[i], actions[i]));
        }
        return rows;
    }

    private void drawPanel(Canvas canvas) {
        RectF panel = panelBounds();
        float unit = Math.min(lastWidth / 16f, lastHeight / 9f);
        float round = dp(12);
        panelFill.setColor(Color.argb(238, 16, 16, 20));
        panelStroke.setColor(Color.argb(235, 255, 112, 28));
        canvas.drawRoundRect(panel, round, round, panelFill);
        canvas.drawRoundRect(panel, round, round, panelStroke);

        label.setTextAlign(Paint.Align.LEFT);
        label.setTextSize(dp(12.5f));
        label.setColor(Color.argb(255, 255, 112, 28));
        float unitText = Math.min(lastWidth / 16f, lastHeight / 9f);
        float pad = unitText * 0.16f;
        Paint.FontMetrics metrics = label.getFontMetrics();
        float titleCenter = panel.top + pad + (unitText * 0.44f) * 0.5f;
        canvas.drawText("TOUCH SETUP", panel.left + pad,
                        titleCenter - (metrics.ascent + metrics.descent) / 2, label);
        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(Color.WHITE);

        for (PanelRow row : buildPanelRows()) {
            float rowCenter = row.bounds.centerY();
            label.setTextAlign(Paint.Align.LEFT);
            label.setTextSize(dp(11.5f));
            label.setColor(Color.argb(255, 240, 240, 244));
            canvas.drawText(row.label, row.bounds.left + dp(8),
                            rowCenter - (label.getFontMetrics().ascent
                                + label.getFontMetrics().descent) / 2, label);
            label.setTextAlign(Paint.Align.RIGHT);
            boolean accent = row.action == ACTION_EDIT && editMode;
            label.setColor(accent
                ? Color.argb(255, 255, 112, 28)
                : Color.argb(255, 255, 170, 90));
            canvas.drawText(row.value, row.bounds.right - dp(8),
                            rowCenter - (label.getFontMetrics().ascent
                                + label.getFontMetrics().descent) / 2, label);
            label.setTextAlign(Paint.Align.CENTER);
            label.setColor(Color.WHITE);
            stroke.setColor(Color.argb(28, 255, 255, 255));
            stroke.setStrokeWidth(1f);
            canvas.drawLine(row.bounds.left, row.bounds.bottom,
                            row.bounds.right, row.bounds.bottom, stroke);
            stroke.setStrokeWidth(dp(2));
        }
    }

    // ---- Touch handling ---------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int pointerId = event.getPointerId(index);
        float x = event.getX(index);
        float y = event.getY(index);

        if (action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (toggle.contains(x, y)) {
                setControlsVisible(!controlsVisible);
                return true;
            }
            if (gear.contains(x, y)) {
                settingsOpen = !settingsOpen;
                if (settingsOpen) {
                    clearInput();
                }
                if (!settingsOpen) {
                    editMode = false;
                    if (dragTarget != null) {
                        persistPosition(dragTarget);
                        dragTarget = null;
                        dragPointerId = -1;
                    }
                }
                invalidate();
                return true;
            }
            if (settingsOpen) {
                for (PanelRow row : buildPanelRows()) {
                    if (row.bounds.contains(x, y)) {
                        activatePanelRow(row.action);
                        return true;
                    }
                }
                if (editMode) {
                    Control hit = hitTest(x, y);
                    if (hit != null) {
                        dragTarget = hit;
                        dragPointerId = pointerId;
                        return true;
                    }
                }
                // Tap outside the panel: swallow it instead of moving the skater.
                return true;
            }
            if (!controlsVisible) return false;
            for (int i = controls.size() - 1; i >= 0; --i) {
                Control control = controls.get(i);
                if (!control.inUse && control.contains(x, y)) {
                    pointers.put(pointerId, control);
                    control.press(x, y);
                    if (hapticsEnabled()) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                    sendState();
                    invalidate();
                    return true;
                }
            }
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (settingsOpen && dragTarget == null) return true;
            if (dragTarget != null) {
                for (int i = 0; i < event.getPointerCount(); ++i) {
                    if (event.getPointerId(i) == dragPointerId) {
                        float margin = dp(18);
                        dragTarget.recenter(
                            clamp(event.getX(i), margin, lastWidth - margin),
                            clamp(event.getY(i), margin, lastHeight - margin));
                        invalidate();
                        return true;
                    }
                }
                return true;
            }
            boolean handled = false;
            for (int i = 0; i < event.getPointerCount(); ++i) {
                Control control = pointers.get(event.getPointerId(i));
                if (control != null) {
                    control.move(event.getX(i), event.getY(i));
                    handled = true;
                }
            }
            if (handled) {
                sendState();
                invalidate();
            }
            return handled;
        }

        if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_POINTER_UP) {
            if (dragTarget != null && pointerId == dragPointerId) {
                persistPosition(dragTarget);
                dragTarget = null;
                dragPointerId = -1;
                invalidate();
                return true;
            }
            if (settingsOpen) return true;
            Control control = pointers.get(pointerId);
            if (control == null) return false;
            control.release();
            pointers.remove(pointerId);
            sendState();
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            if (dragTarget != null) {
                persistPosition(dragTarget);
                dragTarget = null;
                dragPointerId = -1;
            }
            clearInput();
            return true;
        }
        return false;
    }

    private Control hitTest(float x, float y) {
        for (int i = controls.size() - 1; i >= 0; --i) {
            Control control = controls.get(i);
            if (control.contains(x, y)) return control;
        }
        return null;
    }

    private void activatePanelRow(int action) {
        switch (action) {
            case ACTION_LAYOUT: cycleLayout(); break;
            case ACTION_SIZE: cycleSize(); break;
            case ACTION_OPACITY: cycleOpacity(); break;
            case ACTION_HAPTICS: toggleHaptics(); break;
            case ACTION_EDIT: toggleEditMode(); break;
            case ACTION_RESET: resetPositions(); break;
            default: break;
        }
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        preferences.edit().putBoolean(PREF_VISIBLE, visible).apply();
        for (Control control : controls) control.release();
        pointers.clear();
        if (lastWidth > 0 && lastHeight > 0) buildControls(lastWidth, lastHeight);
        sendState();
        invalidate();
    }

    private void sendState() {
        if (!controlsVisible || settingsOpen) {
            TouchControllerBridge.trySetState(-1, 0, 0, 0, 0, 0, 0);
            return;
        }
        int buttons = 0;
        for (Control control : controls) buttons |= control.buttons();
        TouchControllerBridge.trySetState(
            buttons,
            leftStick == null ? 0 : leftStick.xValue,
            leftStick == null ? 0 : leftStick.yValue,
            rightStick == null ? 0 : rightStick.xValue,
            rightStick == null ? 0 : rightStick.yValue,
            leftTrigger != null && leftTrigger.inUse ? 1 : 0,
            rightTrigger != null && rightTrigger.inUse ? 1 : 0);
    }

    private boolean hasPhysicalController() {
        for (int deviceId : InputDevice.getDeviceIds()) {
            if (SDLControllerManager.isDeviceSDLJoystick(deviceId)) return true;
        }
        return false;
    }

    private abstract class Control {
        final String id;
        boolean inUse;
        Control(String id) { this.id = id; }
        abstract boolean contains(float x, float y);
        void press(float x, float y) { inUse = true; move(x, y); }
        void move(float x, float y) {}
        void release() { inUse = false; }
        int buttons() { return 0; }
        /** Current center, used for position persistence. */
        abstract float cx();
        abstract float cy();
        /** Move the control so its center sits at (x, y). */
        abstract void recenter(float x, float y);
        /** Bounds used by the edit-mode highlight ring. */
        abstract RectF editBounds();
        abstract void draw(Canvas canvas);
    }

    private final class ButtonControl extends Control {
        final String text;
        float x;
        float y;
        final float radius;
        final int mask;
        ButtonControl(String id, String text, float x, float y, float radius, int mask) {
            super(id);
            this.text = text; this.x = x; this.y = y; this.radius = radius; this.mask = mask;
        }
        boolean contains(float px, float py) {
            float dx = px - x, dy = py - y;
            return dx * dx + dy * dy <= radius * radius * 1.35f;
        }
        int buttons() { return inUse ? mask : 0; }
        float cx() { return x; }
        float cy() { return y; }
        void recenter(float nx, float ny) { x = nx; y = ny; }
        RectF editBounds() {
            return new RectF(x - radius * 1.6f, y - radius * 1.6f,
                             x + radius * 1.6f, y + radius * 1.6f);
        }
        void draw(Canvas canvas) {
            drawCircle(canvas, x, y, radius, inUse);
            drawLabel(canvas, text, x, y, radius * 0.72f);
        }
    }

    private class RectButton extends Control {
        final String text;
        final RectF bounds;
        final int mask;
        RectButton(String id, String text, float x, float y, float width, float height,
                   int mask) {
            super(id);
            this.text = text;
            this.bounds = new RectF(x - width / 2, y - height / 2,
                                    x + width / 2, y + height / 2);
            this.mask = mask;
        }
        boolean contains(float x, float y) { return bounds.contains(x, y); }
        int buttons() { return inUse ? mask : 0; }
        float cx() { return bounds.centerX(); }
        float cy() { return bounds.centerY(); }
        void recenter(float x, float y) {
            float halfW = bounds.width() / 2f, halfH = bounds.height() / 2f;
            bounds.set(x - halfW, y - halfH, x + halfW, y + halfH);
        }
        RectF editBounds() {
            return new RectF(bounds.left - dp(4), bounds.top - dp(4),
                             bounds.right + dp(4), bounds.bottom + dp(4));
        }
        void draw(Canvas canvas) {
            fill.setColor(inUse ? Color.argb(a(190), 255, 112, 28)
                                : Color.argb(a(105), 12, 12, 15));
            stroke.setColor(Color.argb(a(205), 255, 255, 255));
            canvas.drawRoundRect(bounds, dp(10), dp(10), fill);
            canvas.drawRoundRect(bounds, dp(10), dp(10), stroke);
            drawLabel(canvas, text, bounds.centerX(), bounds.centerY(), dp(11));
        }
    }

    private final class Trigger extends RectButton {
        Trigger(String id, String text, float x, float y, float width, float height,
                boolean left) {
            super(id, text, x, y, width, height, 0);
        }
    }

    private final class Stick extends Control {
        final String text;
        float centerX;
        float centerY;
        final float radius;
        final boolean left;
        float xValue;
        float yValue;
        Stick(String id, String text, float x, float y, float radius, boolean left) {
            super(id);
            this.text = text; this.centerX = x; this.centerY = y;
            this.radius = radius; this.left = left;
        }
        boolean contains(float x, float y) {
            float dx = x - centerX, dy = y - centerY;
            return dx * dx + dy * dy <= radius * radius * 1.55f;
        }
        void move(float x, float y) {
            float dx = (x - centerX) / radius;
            float dy = (y - centerY) / radius;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length > 1) { dx /= length; dy /= length; }
            float deadzone = 0.08f;
            xValue = Math.abs(dx) < deadzone ? 0 : dx;
            yValue = Math.abs(dy) < deadzone ? 0 : -dy;
        }
        void release() { super.release(); xValue = 0; yValue = 0; }
        float cx() { return centerX; }
        float cy() { return centerY; }
        void recenter(float x, float y) { centerX = x; centerY = y; }
        RectF editBounds() {
            return new RectF(centerX - radius * 1.6f, centerY - radius * 1.6f,
                             centerX + radius * 1.6f, centerY + radius * 1.6f);
        }
        void draw(Canvas canvas) {
            drawCircle(canvas, centerX, centerY, radius, false);
            float knobX = centerX + xValue * radius * 0.58f;
            float knobY = centerY - yValue * radius * 0.58f;
            drawCircle(canvas, knobX, knobY, radius * 0.43f, inUse);
            drawLabel(canvas, text, knobX, knobY, radius * 0.34f);
        }
    }

    private final class Dpad extends Control {
        float centerX;
        float centerY;
        final float radius;
        int mask;
        Dpad(String id, float x, float y, float radius) {
            super(id);
            centerX = x; centerY = y; this.radius = radius;
        }
        boolean contains(float x, float y) {
            return Math.abs(x - centerX) <= radius && Math.abs(y - centerY) <= radius;
        }
        void move(float x, float y) {
            float dx = x - centerX, dy = y - centerY;
            float threshold = radius * 0.20f;
            mask = 0;
            if (dx < -threshold) mask |= DPAD_LEFT;
            if (dx > threshold) mask |= DPAD_RIGHT;
            if (dy < -threshold) mask |= DPAD_UP;
            if (dy > threshold) mask |= DPAD_DOWN;
        }
        void release() { super.release(); mask = 0; }
        int buttons() { return inUse ? mask : 0; }
        float cx() { return centerX; }
        float cy() { return centerY; }
        void recenter(float x, float y) { centerX = x; centerY = y; }
        RectF editBounds() {
            return new RectF(centerX - radius, centerY - radius,
                             centerX + radius, centerY + radius);
        }
        void draw(Canvas canvas) {
            float arm = radius * 0.36f;
            fill.setColor(inUse ? Color.argb(a(180), 255, 112, 28)
                                : Color.argb(a(105), 12, 12, 15));
            stroke.setColor(Color.argb(a(205), 255, 255, 255));
            RectF vertical = new RectF(centerX - arm, centerY - radius,
                                       centerX + arm, centerY + radius);
            RectF horizontal = new RectF(centerX - radius, centerY - arm,
                                         centerX + radius, centerY + arm);
            canvas.drawRoundRect(vertical, dp(6), dp(6), fill);
            canvas.drawRoundRect(horizontal, dp(6), dp(6), fill);
            canvas.drawRoundRect(vertical, dp(6), dp(6), stroke);
            canvas.drawRoundRect(horizontal, dp(6), dp(6), stroke);
        }
    }

    private final class PanelRow {
        final RectF bounds;
        final String label;
        final String value;
        final int action;

        PanelRow(RectF bounds, String label, String value, int action) {
            this.bounds = bounds;
            this.label = label;
            this.value = value;
            this.action = action;
        }
    }

    private void drawCircle(Canvas canvas, float x, float y, float radius, boolean pressed) {
        fill.setColor(pressed ? Color.argb(a(190), 255, 112, 28)
                              : Color.argb(a(105), 12, 12, 15));
        stroke.setColor(Color.argb(a(205), 255, 255, 255));
        canvas.drawCircle(x, y, radius, fill);
        canvas.drawCircle(x, y, radius, stroke);
    }

    private void drawLabel(Canvas canvas, String text, float x, float y, float size) {
        label.setTextSize(size);
        Paint.FontMetrics metrics = label.getFontMetrics();
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2, label);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
