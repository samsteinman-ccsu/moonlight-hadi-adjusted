package com.limelight;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * ThorPad — a JSON-configurable control surface on the Thor's bottom screen.
 *
 * Layouts live as JSON files under  <externalFilesDir>/thorpad/  and are resolved
 * PER STREAMED APP: when streaming "Vault Hunters" we look for vault_hunters.json,
 * falling back to default.json, then a built-in default. Edit the JSON over USB /
 * a file manager — no rebuild needed.
 *
 * An element is positioned with normalized coordinates (0..1 of the screen):
 *   { "type":"trackpad", "x":0, "y":0, "w":0.78, "h":1 }
 *   { "type":"button", "x":0.8, "y":0.02, "w":0.18, "h":0.22,
 *     "label":"A", "action":{ "type":"key", "key":"A" } }
 *
 * Buttons take optional styling (all default to the previous look):
 *   "color":"#1F2335"   fill color (hex or named, e.g. "red")
 *   "textColor":"#C0CAF5"   label color
 *   "fontSize":20       label text size in sp
 *   "radius":0|1|2|3    corner roundness (0 = square, 3 = most round)
 *
 * Actions: key (+modifiers) | text | mouse | macro (sequence with delays).
 */
public class ThorPadActivity extends AppCompatActivity {

    public static final String EXTRA_APP_NAME = "thorpad_app_name";

    private TextView statusText;
    private String layoutSource = "built-in";
    private float trackpadSensitivity = 1.4f;
    private String appName;

    private FrameLayout root;
    private LayoutCanvas canvas;
    private View keyboardPanel;
    private boolean shiftActive;

    /** Lets the settings screen hot-reload the live pad after a JSON edit. */
    @SuppressLint("StaticFieldLeak")
    public static ThorPadActivity instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statusPoll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        appName = getIntent() != null ? getIntent().getStringExtra(EXTRA_APP_NAME) : null;

        // Tiny status overlay (does not intercept touch)
        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#7AA2F7"));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        statusText.setPadding(dp(8), dp(2), dp(8), dp(2));
        statusText.setVisibility(View.GONE);

        root = new FrameLayout(this);
        setContentView(root);
        applyLayout();

        statusPoll = new Runnable() {
            @Override public void run() {
                if (Game.instance == null) { finish(); return; }
                boolean up = conn() != null;
                statusText.setText((up ? "● " : "○ ")
                        + (appName == null ? "—" : appName) + "  ·  layout: " + layoutSource);
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(statusPoll);
    }

    /** Builds (or rebuilds) the pad from the resolved JSON. Safe to call repeatedly = hot reload. */
    public void applyLayout() {
        JSONObject layout = loadLayout(appName);
        root.removeAllViews();
        keyboardPanel = null;
        shiftActive = false;

        canvas = new LayoutCanvas(this);
        canvas.setBackgroundColor(Color.parseColor("#0B0E14"));
        buildElements(canvas, layout);
        root.addView(canvas, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (statusText.getParent() != null) {
            ((ViewGroup) statusText.getParent()).removeView(statusText);
        }
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.gravity = Gravity.TOP | Gravity.START;
        root.addView(statusText, statusLp);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
        handler.removeCallbacksAndMessages(null);
    }

    private NvConnection conn() {
        return Game.instance != null ? Game.instance.conn : null;
    }

    // ----------------------------------------------------------------------
    // Physical controller pass-through
    //
    // ThorPad lives on the Thor's BOTTOM screen. As soon as you touch it, this
    // window takes input focus away from the streaming Game activity on the TOP
    // screen — so Android starts routing physical gamepad/joystick events here
    // and the game stops receiving them ("top screen loses game controls").
    //
    // Touch is for the pad; a physical controller is always meant for the game.
    // So we intercept controller events here and forward them straight into the
    // Game activity's existing input handlers (it stays input-grabbed the whole
    // time), keeping the stream fully controllable without bouncing window focus.
    // ----------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {

        // Back should ever close the ThorPad activity.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP
                && keyboardPanel != null
                && keyboardPanel.getVisibility() == View.VISIBLE) {
            keyboardPanel.setVisibility(View.GONE);
            }
        return true;
        }

        // Physical controller events still go to the streamed game.
        if (Game.instance != null
            && ControllerHandler.isGameControllerDevice(event.GetDevice())) {
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                if (Game.instance.handleKeyDown(event)) return true;
                break;

            case KeyEvent.ACTION_UP:
                if (Game.instance.handleKeyUp(event)) return true;
                break;

            case KeyEvent.ACTION_MULTIPLE:
                if (Game.instance.handleKeyMultiple(event)) return true;
                break;
        }
    }

    return super.dispatchKeyEvent(event);
}

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        // Joystick sticks / triggers / D-pad hat arrive here. Forward them to the
        // game; touch events (SOURCE_TOUCHSCREEN) never hit this path, so the pad
        // keeps working normally.
        if (Game.instance != null
                && (event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0
                && Game.instance.handleMotionEvent(null, event)) {
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    // ----------------------------------------------------------------------
    // Client-side actions: settings + on-screen keyboard
    // ----------------------------------------------------------------------

    private void openSettings() {
        Intent i = new Intent(this, ThorPadSettingsActivity.class);
        i.putExtra(EXTRA_APP_NAME, appName);
        startActivity(i);
    }

    private void toggleKeyboard() {
        if (keyboardPanel == null) {
            keyboardPanel = buildKeyboardPanel();
            root.addView(keyboardPanel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        boolean show = keyboardPanel.getVisibility() != View.VISIBLE;
        keyboardPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            keyboardPanel.bringToFront();
            statusText.bringToFront();
        }
    }

    // Each token is "ab" (a = unshifted, b = shifted) for character keys,
    // or an UPPERCASE word for a special key.
    private static final String[][] KB_ROWS = {
            {"1!", "2@", "3#", "4$", "5%", "6^", "7&", "8*", "9(", "0)", "-_", "=+", "BKSP"},
            {"qQ", "wW", "eE", "rR", "tT", "yY", "uU", "iI", "oO", "pP", "[{", "]}", "\\|"},
            {"aA", "sS", "dD", "fF", "gG", "hH", "jJ", "kK", "lL", ";:", "'\"", "ENTER"},
            {"SHIFT", "zZ", "xX", "cC", "vV", "bB", "nN", "mM", ",<", ".>", "/?", "SHIFT"},
            {"ESC", "TAB", "SPACE", "LEFT", "RIGHT", "CLOSE"}
    };

    private View buildKeyboardPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#0D1017"));
        panel.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (String[] row : KB_ROWS) {
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : row) {
                rowView.addView(makeKbKey(key), keyParams(key));
            }
            panel.addView(rowView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        return panel;
    }

    private LinearLayout.LayoutParams keyParams(String key) {
        float weight = 1f;
        if ("SPACE".equals(key)) {
            weight = 6f;
        } else if ("BKSP".equals(key) || "ENTER".equals(key) || "SHIFT".equals(key)
                || "TAB".equals(key) || "CLOSE".equals(key) || "ESC".equals(key)) {
            weight = 1.6f;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        return lp;
    }

    private Button makeKbKey(final String key) {
        final Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor("#C0CAF5"));
        b.setBackgroundColor(Color.parseColor("#1F2335"));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(0, 0, 0, 0);
        b.setText(kbLabel(key));
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            onKbKey(key, b);
        });
        return b;
    }

    private String kbLabel(String key) {
        switch (key) {
            case "BKSP": return "⌫";   // ⌫
            case "ENTER": return "⏎";  // ⏎
            case "SHIFT": return "⇧";  // ⇧
            case "SPACE": return "space";
            case "ESC": return "esc";
            case "TAB": return "tab";
            case "LEFT": return "◀";   // ◀
            case "RIGHT": return "▶";  // ▶
            case "CLOSE": return "▼";  // ▼
            default: return key.substring(0, 1);
        }
    }

    private void onKbKey(String key, Button b) {
        switch (key) {
            case "SHIFT":
                shiftActive = !shiftActive;
                b.setBackgroundColor(Color.parseColor(shiftActive ? "#3D59A1" : "#1F2335"));
                return;
            case "CLOSE":
                if (keyboardPanel != null) {
                    keyboardPanel.setVisibility(View.GONE);
                }
                return;
            case "BKSP": sendKeyBg((short) 0x08); return;
            case "ENTER": sendKeyBg((short) 0x0D); return;
            case "TAB": sendKeyBg((short) 0x09); return;
            case "ESC": sendKeyBg((short) 0x1B); return;
            case "SPACE": sendKeyBg((short) 0x20); return;
            case "LEFT": sendKeyBg((short) 0x25); return;
            case "RIGHT": sendKeyBg((short) 0x27); return;
            default:
                final char ch = shiftActive ? key.charAt(1) : key.charAt(0);
                new Thread(() -> typeChar(ch)).start();
        }
    }

    private void sendKeyBg(short vk) {
        new Thread(() -> sendKey(vk, (byte) 0)).start();
    }

    private void typeChar(char ch) {
        int[] k = keyStrokeFor(ch);
        if (k == null) {
            return;
        }
        sendKey((short) k[0], k[1] == 1 ? KeyboardPacket.MODIFIER_SHIFT : (byte) 0);
    }

    // ----------------------------------------------------------------------
    // Layout building
    // ----------------------------------------------------------------------

    private void buildElements(LayoutCanvas canvas, JSONObject layout) {
        trackpadSensitivity = (float) layout.optDouble("trackpadSensitivity", 1.4);
        JSONArray elements = layout.optJSONArray("elements");
        if (elements == null) {
            return;
        }
        int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.optJSONObject(i);
            if (el == null) {
                continue;
            }
            String type = el.optString("type", "");
            float x = (float) el.optDouble("x", 0);
            float y = (float) el.optDouble("y", 0);
            float w = (float) el.optDouble("w", 0.1);
            float h = (float) el.optDouble("h", 0.1);

            View view = null;
            if ("trackpad".equals(type)) {
                View pad = new View(this);
                pad.setBackgroundColor(Color.parseColor("#11151F"));
                pad.setOnTouchListener(new TrackpadListener(touchSlop, trackpadSensitivity));
                view = pad;
            } else if ("button".equals(type)) {
                view = makeButton(el);
            }

            if (view != null) {
                canvas.addView(view, new LayoutCanvas.LP(x, y, w, h));
            }
        }
    }

    private Button makeButton(final JSONObject el) {
        String label = el.optString("label", "");
        String icon = el.optString("icon", "");
        final JSONObject action = el.optJSONObject("action");

        Button b = new Button(this);
        b.setText(label);                       // emoji works here directly, e.g. "🔊"
        b.setAllCaps(false);

        // Optional styling (all have sensible defaults so old layouts are unchanged):
        //   "fontSize": 20            text size in sp
        //   "textColor": "#C0CAF5"    label color (hex or named, e.g. "red")
        //   "color": "#1F2335"        button fill color
        //   "radius": 0|1|2|3         corner roundness (0 = square, 3 = most round)
        //   "alpha": 0..1             fill opacity (lower = more see-through; label stays crisp)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                (float) el.optDouble("fontSize", el.optDouble("textSize", 20)));
        b.setTextColor(parseColorOr(el.optString("textColor", ""), Color.parseColor("#C0CAF5")));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        int fill = parseColorOr(el.optString("color", ""), Color.parseColor("#1F2335"));
        bg.setColor(applyAlpha(fill, el.optDouble("alpha", 1.0)));
        bg.setCornerRadius(radiusPx(el.optInt("radius", 0)));
        b.setBackground(bg);

        // Optional image icon: a file in the thorpad/ folder (e.g. "icon":"steam.png").
        if (icon != null && !icon.isEmpty()) {
            File f = new File(new File(getExternalFilesDir(null), "thorpad"), icon);
            if (f.exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bmp != null) {
                    b.setBackground(new BitmapDrawable(getResources(), bmp));
                }
            }
        }
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (action != null) {
                // Run off the UI thread so macros/delays don't jank the surface.
                new Thread(() -> executeAction(action)).start();
            }
        });
        return b;
    }

    // ----------------------------------------------------------------------
    // Action execution
    // ----------------------------------------------------------------------

    private void executeAction(JSONObject action) {
        if (action == null) {
            return;
        }
        String type = action.optString("type", "");

        // Client-side actions — handled on the device, no host connection required.
        switch (type) {
            case "keyboard": runOnUiThread(this::toggleKeyboard); return;
            case "settings": runOnUiThread(this::openSettings); return;
            case "reload":   runOnUiThread(this::applyLayout); return;
            case "zoom": {
                final float scale = (float) action.optDouble("scale", 2.0);
                runOnUiThread(() -> { if (Game.instance != null) Game.instance.thorZoomToggle(scale); });
                return;
            }
            default: break;
        }

        NvConnection c = conn();
        if (c == null) {
            return;
        }
        switch (type) {
            case "key":
                sendKey((short) vkForKey(action.optString("key", "")),
                        parseModifiers(action.optJSONArray("modifiers")));
                break;
            case "text":
                // Type as real key events — sendUtf8Text is not honored by all hosts
                // (e.g. Sunshine/Lumen on macOS), whereas keyboard events are.
                typeString(action.optString("text", ""));
                break;
            case "mouse":
                byte btn = mouseButton(action.optString("button", "left"));
                c.sendMouseButtonDown(btn);
                c.sendMouseButtonUp(btn);
                break;
            case "delay":
                sleep(action.optLong("ms", 50));
                break;
            case "macro":
                JSONArray steps = action.optJSONArray("steps");
                if (steps != null) {
                    for (int i = 0; i < steps.length(); i++) {
                        JSONObject step = steps.optJSONObject(i);
                        if (step != null) {
                            executeAction(step);
                        }
                    }
                }
                break;
            default:
                break;
        }
    }

    /** Sends a key with optional modifiers held around it (robust for combos like Ctrl+C). */
    private void sendKey(short vk, byte mod) {
        NvConnection c = conn();
        if (c == null || vk == 0) {
            return;
        }
        if ((mod & KeyboardPacket.MODIFIER_CTRL) != 0)  c.sendKeyboardInput((short) 0x11, KeyboardPacket.KEY_DOWN, mod, (byte) 0);
        if ((mod & KeyboardPacket.MODIFIER_SHIFT) != 0) c.sendKeyboardInput((short) 0x10, KeyboardPacket.KEY_DOWN, mod, (byte) 0);
        if ((mod & KeyboardPacket.MODIFIER_ALT) != 0)   c.sendKeyboardInput((short) 0x12, KeyboardPacket.KEY_DOWN, mod, (byte) 0);
        if ((mod & KeyboardPacket.MODIFIER_META) != 0)  c.sendKeyboardInput((short) 0x5B, KeyboardPacket.KEY_DOWN, mod, (byte) 0);

        c.sendKeyboardInput(vk, KeyboardPacket.KEY_DOWN, mod, (byte) 0);
        c.sendKeyboardInput(vk, KeyboardPacket.KEY_UP, mod, (byte) 0);

        if ((mod & KeyboardPacket.MODIFIER_META) != 0)  c.sendKeyboardInput((short) 0x5B, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        if ((mod & KeyboardPacket.MODIFIER_ALT) != 0)   c.sendKeyboardInput((short) 0x12, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        if ((mod & KeyboardPacket.MODIFIER_SHIFT) != 0) c.sendKeyboardInput((short) 0x10, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
        if ((mod & KeyboardPacket.MODIFIER_CTRL) != 0)  c.sendKeyboardInput((short) 0x11, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
    }

    /** Types a string by sending one keyboard event per character (US layout). */
    private void typeString(String s) {
        NvConnection c = conn();
        if (c == null || s == null) {
            return;
        }
        for (int i = 0; i < s.length(); i++) {
            int[] stroke = keyStrokeFor(s.charAt(i));
            if (stroke == null) {
                continue;
            }
            byte mod = stroke[1] == 1 ? KeyboardPacket.MODIFIER_SHIFT : (byte) 0;
            sendKey((short) stroke[0], mod);
            sleep(10); // small gap so the host doesn't drop fast bursts
        }
    }

    /** Maps a character to {Win32 VK code, needsShift} for a US keyboard, or null if unknown. */
    private static int[] keyStrokeFor(char ch) {
        if (ch >= 'a' && ch <= 'z') return new int[] {Character.toUpperCase(ch), 0};
        if (ch >= 'A' && ch <= 'Z') return new int[] {ch, 1};
        if (ch >= '0' && ch <= '9') return new int[] {ch, 0};
        switch (ch) {
            case ' ':  return new int[] {0x20, 0};
            case '\n': return new int[] {0x0D, 0};
            case '\t': return new int[] {0x09, 0};
            case '`':  return new int[] {0xC0, 0};
            case '~':  return new int[] {0xC0, 1};
            case '-':  return new int[] {0xBD, 0};
            case '_':  return new int[] {0xBD, 1};
            case '=':  return new int[] {0xBB, 0};
            case '+':  return new int[] {0xBB, 1};
            case '[':  return new int[] {0xDB, 0};
            case '{':  return new int[] {0xDB, 1};
            case ']':  return new int[] {0xDD, 0};
            case '}':  return new int[] {0xDD, 1};
            case '\\': return new int[] {0xDC, 0};
            case '|':  return new int[] {0xDC, 1};
            case ';':  return new int[] {0xBA, 0};
            case ':':  return new int[] {0xBA, 1};
            case '\'': return new int[] {0xDE, 0};
            case '"':  return new int[] {0xDE, 1};
            case ',':  return new int[] {0xBC, 0};
            case '<':  return new int[] {0xBC, 1};
            case '.':  return new int[] {0xBE, 0};
            case '>':  return new int[] {0xBE, 1};
            case '/':  return new int[] {0xBF, 0};
            case '?':  return new int[] {0xBF, 1};
            case '!':  return new int[] {'1', 1};
            case '@':  return new int[] {'2', 1};
            case '#':  return new int[] {'3', 1};
            case '$':  return new int[] {'4', 1};
            case '%':  return new int[] {'5', 1};
            case '^':  return new int[] {'6', 1};
            case '&':  return new int[] {'7', 1};
            case '*':  return new int[] {'8', 1};
            case '(':  return new int[] {'9', 1};
            case ')':  return new int[] {'0', 1};
            default:   return null;
        }
    }

    private static byte parseModifiers(JSONArray mods) {
        byte m = 0;
        if (mods == null) {
            return 0;
        }
        for (int i = 0; i < mods.length(); i++) {
            switch (mods.optString(i, "").toLowerCase()) {
                case "shift": m |= KeyboardPacket.MODIFIER_SHIFT; break;
                case "ctrl": case "control": m |= KeyboardPacket.MODIFIER_CTRL; break;
                case "alt": m |= KeyboardPacket.MODIFIER_ALT; break;
                case "meta": case "win": case "cmd": m |= KeyboardPacket.MODIFIER_META; break;
                default: break;
            }
        }
        return m;
    }

    private static byte mouseButton(String name) {
        switch (name.toLowerCase()) {
            case "right": return MouseButtonPacket.BUTTON_RIGHT;
            case "middle": return MouseButtonPacket.BUTTON_MIDDLE;
            default: return MouseButtonPacket.BUTTON_LEFT;
        }
    }

    /** Win32 virtual-key code for a key name. Single chars (letters/digits) == ASCII upper. */
    private static int vkForKey(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }
        if (key.length() == 1) {
            return Character.toUpperCase(key.charAt(0));
        }
        switch (key.toLowerCase()) {
            case "enter": case "return": return 0x0D;
            case "esc": case "escape": return 0x1B;
            case "space": return 0x20;
            case "tab": return 0x09;
            case "backspace": return 0x08;
            case "delete": case "del": return 0x2E;
            case "up": return 0x26;
            case "down": return 0x28;
            case "left": return 0x25;
            case "right": return 0x27;
            case "home": return 0x24;
            case "end": return 0x23;
            case "pageup": return 0x21;
            case "pagedown": return 0x22;
            case "shift": return 0x10;
            case "ctrl": case "control": return 0x11;
            case "alt": return 0x12;
            case "win": case "meta": case "cmd": return 0x5B;
            // Media / consumer keys (Win32 VK codes)
            case "volup": case "volumeup": return 0xAF;
            case "voldown": case "volumedown": return 0xAE;
            case "mute": case "volmute": return 0xAD;
            case "playpause": case "play": return 0xB3;
            case "next": case "nexttrack": return 0xB0;
            case "prev": case "previous": case "prevtrack": return 0xB1;
            case "mediastop": return 0xB2;
            case "printscreen": case "prtsc": return 0x2C;
            case "insert": case "ins": return 0x2D;
            case "capslock": return 0x14;
            default:
                if (key.matches("(?i)f(1[0-2]|[1-9])")) {
                    return 0x70 + Integer.parseInt(key.substring(1)) - 1;
                }
                return 0;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // ----------------------------------------------------------------------
    // Layout file resolution (per-app JSON)
    // ----------------------------------------------------------------------

    private JSONObject loadLayout(String appName) {
        File dir = new File(getExternalFilesDir(null), "thorpad");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        // Seed default.json + README on first run so there's something to edit.
        File defaultFile = new File(dir, "default.json");
        if (!defaultFile.exists()) {
            writeFile(defaultFile, DEFAULT_LAYOUT_JSON);
        }
        File readme = new File(dir, "README.txt");
        if (!readme.exists()) {
            writeFile(readme, README_TEXT);
        }

        // Per-app file wins, else default.json, else the built-in constant.
        if (appName != null && !appName.isEmpty()) {
            File appFile = new File(dir, sanitize(appName) + ".json");
            JSONObject fromApp = tryRead(appFile);
            if (fromApp != null) {
                layoutSource = appFile.getName();
                return fromApp;
            }
        }
        JSONObject fromDefault = tryRead(defaultFile);
        if (fromDefault != null) {
            layoutSource = "default.json";
            return fromDefault;
        }
        layoutSource = "built-in";
        try {
            return new JSONObject(DEFAULT_LAYOUT_JSON);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject tryRead(File f) {
        if (f == null || !f.exists()) {
            return null;
        }
        try {
            return new JSONObject(readFile(f));
        } catch (Exception e) {
            LimeLog.warning("ThorPad: bad JSON in " + f.getName() + ": " + e);
            runOnUiThread(() -> Toast.makeText(this,
                    "ThorPad: invalid JSON in " + f.getName() + ", using default",
                    Toast.LENGTH_LONG).show());
            return null;
        }
    }

    private static String sanitize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9-_]+", "_");
    }

    private static String readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(buf, 0, read, "UTF-8");
        }
    }

    private static void writeFile(File f, String content) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes("UTF-8"));
        } catch (IOException e) {
            LimeLog.warning("ThorPad: failed to write " + f.getName() + ": " + e);
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /** Corner radius "level" → pixels. 0 = square; 1/2/3 (and up) get progressively rounder. */
    private float radiusPx(int level) {
        return level <= 0 ? 0f : dp(8 * level);
    }

    /** Override a color's alpha from a 0..1 fraction (1 = opaque). Applied to the fill only. */
    private static int applyAlpha(int color, double alpha) {
        if (alpha >= 1.0) {
            return color;
        }
        int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** Parse "#RRGGBB", "RRGGBB", or a named color ("red"); fall back if blank/invalid. */
    private static int parseColorOr(String s, int fallback) {
        if (s == null || s.isEmpty()) {
            return fallback;
        }
        try {
            return Color.parseColor(s.charAt(0) == '#' ? s : "#" + s);
        } catch (IllegalArgumentException e) {
            // Not a hex string — try a named color (e.g. "red", "cyan").
            try {
                return Color.parseColor(s.toLowerCase());
            } catch (IllegalArgumentException e2) {
                return fallback;
            }
        }
    }

    // ----------------------------------------------------------------------
    // A ViewGroup that positions children by a normalized (0..1) rect.
    // ----------------------------------------------------------------------

    static final class LayoutCanvas extends ViewGroup {
        LayoutCanvas(Context c) { super(c); }

        static final class LP extends ViewGroup.LayoutParams {
            final float x, y, w, h;
            LP(float x, float y, float w, float h) {
                super(WRAP_CONTENT, WRAP_CONTENT);
                this.x = x; this.y = y; this.w = w; this.h = h;
            }
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = MeasureSpec.getSize(heightSpec);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                LP lp = (LP) child.getLayoutParams();
                child.measure(
                        MeasureSpec.makeMeasureSpec(Math.round(lp.w * width), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round(lp.h * height), MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int height = b - t;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                LP lp = (LP) child.getLayoutParams();
                int left = Math.round(lp.x * width);
                int top = Math.round(lp.y * height);
                child.layout(left, top,
                        left + Math.round(lp.w * width),
                        top + Math.round(lp.h * height));
            }
        }
    }

    private final class TrackpadListener implements View.OnTouchListener {
        private final int touchSlop;
        private final float sensitivity;
        private static final long TAP_TIMEOUT_MS = 200;
        private static final float SCROLL_STEP_PX = 40f;

        private float lastX, lastY, downX, downY;
        private long downTime;
        private boolean moved;
        private int maxPointers;
        private float scrollAccum;

        TrackpadListener(int touchSlop, float sensitivity) {
            this.touchSlop = touchSlop;
            this.sensitivity = sensitivity;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            NvConnection c = conn();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = downX = e.getX();
                    lastY = downY = e.getY();
                    downTime = e.getEventTime();
                    moved = false;
                    maxPointers = 1;
                    scrollAccum = 0;
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    maxPointers = Math.max(maxPointers, e.getPointerCount());
                    lastX = e.getX();
                    lastY = e.getY();
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    if (c == null) return true;
                    float x = e.getX(), y = e.getY();
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x;
                    lastY = y;
                    if (Math.abs(x - downX) > touchSlop || Math.abs(y - downY) > touchSlop) {
                        moved = true;
                    }
                    if (e.getPointerCount() >= 2) {
                        scrollAccum += dy;
                        while (Math.abs(scrollAccum) >= SCROLL_STEP_PX) {
                            c.sendMouseScroll((byte) (scrollAccum > 0 ? -1 : 1));
                            scrollAccum += (scrollAccum > 0 ? -SCROLL_STEP_PX : SCROLL_STEP_PX);
                        }
                    } else {
                        short mdx = (short) (dx * sensitivity);
                        short mdy = (short) (dy * sensitivity);
                        if (mdx != 0 || mdy != 0) {
                            c.sendMouseMove(mdx, mdy);
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    if (c != null && !moved && (e.getEventTime() - downTime) < TAP_TIMEOUT_MS) {
                        byte button = maxPointers >= 2
                                ? MouseButtonPacket.BUTTON_RIGHT : MouseButtonPacket.BUTTON_LEFT;
                        c.sendMouseButtonDown(button);
                        c.sendMouseButtonUp(button);
                    }
                    return true;

                default:
                    return true;
            }
        }
    }

    // ----------------------------------------------------------------------
    // Seed content
    // ----------------------------------------------------------------------

    public static final String DEFAULT_LAYOUT_JSON =
            "{\n" +
            "  \"name\": \"default\",\n" +
            "  \"trackpadSensitivity\": 1.4,\n" +
            "  \"elements\": [\n" +
            "    { \"type\": \"trackpad\", \"x\": 0.0,  \"y\": 0.0,  \"w\": 0.78, \"h\": 1.0 },\n" +
            "    { \"type\": \"button\", \"x\": 0.80, \"y\": 0.02, \"w\": 0.18, \"h\": 0.225, \"label\": \"A\", \"action\": { \"type\": \"key\", \"key\": \"A\" } },\n" +
            "    { \"type\": \"button\", \"x\": 0.80, \"y\": 0.265,\"w\": 0.18, \"h\": 0.225, \"label\": \"B\", \"action\": { \"type\": \"key\", \"key\": \"B\" } },\n" +
            "    { \"type\": \"button\", \"x\": 0.80, \"y\": 0.51, \"w\": 0.18, \"h\": 0.225, \"label\": \"C\", \"action\": { \"type\": \"key\", \"key\": \"C\" } },\n" +
            "    { \"type\": \"button\", \"x\": 0.80, \"y\": 0.755,\"w\": 0.18, \"h\": 0.225, \"label\": \"D\", \"action\": { \"type\": \"key\", \"key\": \"D\" } }\n" +
            "  ]\n" +
            "}\n";

    private static final String README_TEXT =
            "ThorPad layouts\n" +
            "===============\n\n" +
            "Drop JSON files in this folder to define the bottom-screen control pad.\n\n" +
            "Resolution order while streaming app \"My Game\":\n" +
            "  1. my_game.json   (per-app; name lowercased, non-alphanumerics -> _)\n" +
            "  2. default.json\n" +
            "  3. built-in default\n\n" +
            "Coordinates are fractions of the screen (0..1): x,y = top-left, w,h = size.\n\n" +
            "Element types:\n" +
            "  { \"type\":\"trackpad\", \"x\":0,\"y\":0,\"w\":0.78,\"h\":1 }\n" +
            "  { \"type\":\"button\", \"x\":..,\"y\":..,\"w\":..,\"h\":..,\n" +
            "    \"label\":\"A\", \"action\": <action> }\n\n" +
            "Top-level \"trackpadSensitivity\" (default 1.4) scales mouse movement.\n\n" +
            "Actions that talk to the host:\n" +
            "  { \"type\":\"key\", \"key\":\"A\", \"modifiers\":[\"ctrl\"] }\n" +
            "      key: a letter/digit, or a name: enter esc space tab backspace delete\n" +
            "           up down left right home end pageup pagedown f1..f12\n" +
            "           volup voldown mute playpause next prev mediastop printscreen insert capslock\n" +
            "      modifiers (optional): shift ctrl alt meta   (on macOS meta = Cmd)\n" +
            "  { \"type\":\"text\", \"text\":\"gg wp\" }            types a string\n" +
            "  { \"type\":\"mouse\", \"button\":\"left|right|middle\" }\n" +
            "  { \"type\":\"macro\", \"steps\":[ <action>, { \"type\":\"delay\",\"ms\":100 }, <action> ] }\n\n" +
            "Actions handled on the device (no host needed):\n" +
            "  { \"type\":\"keyboard\" }   toggles a full on-screen keyboard on the bottom screen\n" +
            "  { \"type\":\"settings\" }   opens the ThorPad settings / layout manager\n" +
            "  { \"type\":\"zoom\" }       toggles pan/zoom mode on the top screen (then pinch to zoom)\n" +
            "  { \"type\":\"reload\" }     re-reads this layout file (hot reload, no stream restart)\n";
}
