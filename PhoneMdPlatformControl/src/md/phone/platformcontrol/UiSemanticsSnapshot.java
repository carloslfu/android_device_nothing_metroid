package md.phone.platformcontrol;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.app.UiAutomationConnection;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.InputType;
import android.util.Slog;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.text.Normalizer;

/**
 * One bounded accessibility snapshot captured alongside a display frame.
 *
 * Pixels stay the visual source of truth. This snapshot gives the acting model
 * exact Android control bounds and lets the input broker bind a generated
 * action to the package that owned the visible target in that same frame.
 */
final class UiSemanticsSnapshot {
    private static final String TAG = "PhoneMdUiSemantics";
    private static final String BROKER_PACKAGE = "md.phone.platformcontrol";
    private static final int MAX_NODES = 480;
    private static final int MAX_WINDOWS = 32;
    private static final int MAX_TEXT_CHARS = 240;
    private static final int MAX_JSON_CHARS = 48_000;
    private static final long CONNECT_SETTLE_MILLIS = 80;

    static final class Region {
        final Rect bounds;
        final String packageName;
        final int layer;
        final int depth;
        final boolean interactive;
        final boolean scrollable;
        final boolean focused;
        final boolean privateInput;
        final String identity;

        Region(Rect bounds, String packageName, int layer, int depth, boolean interactive,
                boolean scrollable, boolean focused, boolean privateInput, String identity) {
            this.bounds = new Rect(bounds);
            this.packageName = packageName;
            this.layer = layer;
            this.depth = depth;
            this.interactive = interactive;
            this.scrollable = scrollable;
            this.focused = focused;
            this.privateInput = privateInput;
            this.identity = identity;
        }

        long area() {
            return Math.max(1L, (long) bounds.width() * bounds.height());
        }
    }

    private static final class PendingNode {
        final AccessibilityNodeInfo node;
        final int depth;
        final boolean privateAncestor;

        PendingNode(AccessibilityNodeInfo node, int depth, boolean privateAncestor) {
            this.node = node;
            this.depth = depth;
            this.privateAncestor = privateAncestor;
        }
    }

    final boolean available;
    final String json;
    final List<Region> regions;
    final String focusedPackage;

    private UiSemanticsSnapshot(boolean available, String json, List<Region> regions,
            String focusedPackage) {
        this.available = available;
        this.json = json;
        this.regions = List.copyOf(regions);
        this.focusedPackage = focusedPackage;
    }

    static UiSemanticsSnapshot unavailable(int width, int height, String detail) {
        JSONObject root = new JSONObject();
        try {
            root.put("available", false);
            root.put("coordinate_space", coordinateSpace(width, height));
            root.put("detail", bounded(detail));
        } catch (Throwable ignored) {
        }
        return new UiSemanticsSnapshot(false, root.toString(), List.of(), null);
    }

    static UiSemanticsSnapshot capture(Context context, int width, int height) {
        UiAutomationConnection connection = null;
        UiAutomation automation = null;
        try {
            connection = new UiAutomationConnection();
            automation = new UiAutomation(context, connection);
            automation.connect(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
            serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(serviceInfo);
            SystemClock.sleep(CONNECT_SETTLE_MILLIS);

            List<AccessibilityWindowInfo> windows = new ArrayList<>(automation.getWindows());
            if (windows.isEmpty()) {
                AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                if (root == null) {
                    return unavailable(width, height, "Android returned no accessibility window.");
                }
                return fromSingleRoot(root, width, height);
            }
            // Start with the top/focused surface. A large background hierarchy
            // must never consume the node budget before a visible password,
            // SystemUI control, or foreground target can be classified.
            windows.sort(Comparator.comparingInt(AccessibilityWindowInfo::getLayer).reversed());
            return fromWindows(windows, width, height);
        } catch (Throwable error) {
            Slog.w(TAG, "Could not capture UI semantics", error);
            return unavailable(width, height,
                    "Android UI semantics unavailable: " + error.getClass().getSimpleName());
        } finally {
            if (automation != null) {
                try {
                    automation.disconnect();
                } catch (Throwable ignored) {
                }
            }
            if (connection != null) {
                try {
                    connection.shutdown();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    String packageAt(int x, int y, String fallbackPackage, boolean preferScrollable) {
        Region target = regionAt(x, y, preferScrollable);
        return target == null || target.packageName == null
                ? fallbackPackage : target.packageName;
    }

    String identityAt(int x, int y, String fallbackPackage, boolean preferScrollable) {
        Region target = regionAt(x, y, preferScrollable);
        return target == null ? "window|" + fallbackPackage : target.identity;
    }

    String inputPackage(String fallbackPackage) {
        return regions.stream()
                .filter(region -> region.focused && region.interactive
                        && region.packageName != null)
                .sorted((left, right) -> {
                    int layer = Integer.compare(right.layer, left.layer);
                    if (layer != 0) return layer;
                    int depth = Integer.compare(right.depth, left.depth);
                    if (depth != 0) return depth;
                    return Long.compare(left.area(), right.area());
                })
                .map(region -> region.packageName)
                .findFirst()
                .orElse(focusedPackage == null ? fallbackPackage : focusedPackage);
    }

    String focusedIdentity(String fallbackPackage) {
        return regions.stream()
                .filter(region -> region.focused && region.identity != null)
                .sorted((left, right) -> {
                    if (left.interactive != right.interactive) {
                        return left.interactive ? -1 : 1;
                    }
                    int layer = Integer.compare(right.layer, left.layer);
                    if (layer != 0) return layer;
                    int depth = Integer.compare(right.depth, left.depth);
                    if (depth != 0) return depth;
                    return Long.compare(left.area(), right.area());
                })
                .map(region -> region.identity)
                .findFirst()
                .orElse("window|" + fallbackPackage);
    }

    boolean hasFocusedPrivateInput() {
        return regions.stream().anyMatch(region -> region.focused && region.privateInput);
    }

    void redactPrivateInputs(Bitmap bitmap) {
        if (bitmap == null || !bitmap.isMutable()) return;
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        Canvas canvas = new Canvas(bitmap);
        regions.stream()
                .filter(region -> region.privateInput)
                .forEach(region -> canvas.drawRect(region.bounds, paint));
    }

    private Region regionAt(int x, int y, boolean preferScrollable) {
        return regions.stream()
                .filter(region -> region.packageName != null && region.bounds.contains(x, y))
                .sorted((left, right) -> {
                    if (preferScrollable && left.scrollable != right.scrollable) {
                        return left.scrollable ? -1 : 1;
                    }
                    if (left.interactive != right.interactive) {
                        return left.interactive ? -1 : 1;
                    }
                    int layer = Integer.compare(right.layer, left.layer);
                    if (layer != 0) return layer;
                    if (left.focused != right.focused) return left.focused ? -1 : 1;
                    int depth = Integer.compare(right.depth, left.depth);
                    if (depth != 0) return depth;
                    return Long.compare(left.area(), right.area());
                })
                .findFirst()
                .orElse(null);
    }

    private static UiSemanticsSnapshot fromWindows(List<AccessibilityWindowInfo> windows,
            int width, int height) throws Exception {
        JSONArray windowRows = new JSONArray();
        List<Region> regions = new ArrayList<>();
        String focusedPackage = null;
        int remaining = MAX_NODES;
        int inspectedWindows = 0;
        for (AccessibilityWindowInfo window : windows) {
            if (inspectedWindows++ >= MAX_WINDOWS) break;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            Rect windowBounds = new Rect();
            window.getBoundsInScreen(windowBounds);
            String windowPackage = chars(root.getPackageName());
            // The co-pilot banner is intentionally non-touchable. It must stay
            // visible to the user without becoming a model target or masking
            // the real Android control underneath it.
            if (BROKER_PACKAGE.equals(windowPackage)) continue;
            // Accessibility can retain a collapsed, full-display System UI
            // window above the foreground app. It has no visible target, but
            // its layer would otherwise steal every coordinate from the app.
            // Keep real bars with bounded geometry and any active/focused
            // full-display surface, such as the expanded notification shade.
            if (!window.isActive() && !window.isFocused()
                    && coversDisplay(windowBounds, width, height)) {
                continue;
            }
            if (focusedPackage == null && window.isFocused() && windowPackage != null) {
                focusedPackage = windowPackage;
            }
            if (windowPackage != null && validBounds(windowBounds, width, height)) {
                regions.add(new Region(windowBounds, windowPackage, window.getLayer(), 0,
                        false, false, window.isFocused(), false,
                        windowIdentity(windowPackage, windowBounds, window.getLayer())));
            }

            JSONArray controls = new JSONArray();
            int consumed = remaining > 0
                    ? appendNodes(root, window.getLayer(), width, height, remaining,
                            controls, regions)
                    : 0;
            remaining -= consumed;

            JSONObject row = new JSONObject();
            row.put("package", windowPackage == null ? JSONObject.NULL : windowPackage);
            row.put("type", windowType(window.getType()));
            row.put("layer", window.getLayer());
            row.put("active", window.isActive());
            row.put("focused", window.isFocused());
            row.put("bounds", bounds(windowBounds));
            row.put("controls", controls);
            windowRows.put(row);
        }
        JSONObject result = new JSONObject();
        result.put("available", true);
        result.put("coordinate_space", coordinateSpace(width, height));
        result.put("windows", windowRows);
        String json = boundedJson(result, windowRows);
        return new UiSemanticsSnapshot(true, json, regions, focusedPackage);
    }

    private static UiSemanticsSnapshot fromSingleRoot(AccessibilityNodeInfo root,
            int width, int height) throws Exception {
        JSONArray controls = new JSONArray();
        List<Region> regions = new ArrayList<>();
        appendNodes(root, 0, width, height, MAX_NODES, controls, regions);
        JSONObject window = new JSONObject();
        window.put("package", chars(root.getPackageName()));
        window.put("type", "active_application");
        window.put("layer", 0);
        window.put("active", true);
        window.put("focused", true);
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        window.put("bounds", bounds(rootBounds));
        window.put("controls", controls);
        JSONObject result = new JSONObject();
        result.put("available", true);
        result.put("coordinate_space", coordinateSpace(width, height));
        String packageName = chars(root.getPackageName());
        JSONArray windows = new JSONArray().put(window);
        result.put("windows", windows);
        return new UiSemanticsSnapshot(
                true,
                boundedJson(result, windows),
                regions,
                packageName);
    }

    private static int appendNodes(AccessibilityNodeInfo root, int layer, int width, int height,
            int limit, JSONArray output, List<Region> regions) throws Exception {
        ArrayDeque<PendingNode> queue = new ArrayDeque<>();
        queue.add(new PendingNode(root, 0, false));
        int visited = 0;
        while (!queue.isEmpty() && visited < limit) {
            PendingNode pending = queue.removeFirst();
            AccessibilityNodeInfo node = pending.node;
            visited++;

            Rect nodeBounds = new Rect();
            node.getBoundsInScreen(nodeBounds);
            String packageName = chars(node.getPackageName());
            boolean editable = node.isEditable();
            boolean interactive = node.isClickable() || node.isLongClickable()
                    || node.isScrollable() || node.isCheckable() || editable
                    || node.isDismissable();
            boolean meaningful = interactive || node.isFocused()
                    || !TextUtils.isEmpty(node.getText())
                    || !TextUtils.isEmpty(node.getContentDescription())
                    || !TextUtils.isEmpty(node.getHintText());
            if (node.isVisibleToUser() && validBounds(nodeBounds, width, height)
                    && packageName != null) {
                boolean privateInput = pending.privateAncestor || isPrivateInput(node);
                regions.add(new Region(nodeBounds, packageName, layer, pending.depth,
                        interactive, node.isScrollable(), node.isFocused(), privateInput,
                        nodeIdentity(node, nodeBounds, packageName, privateInput)));
                if (meaningful) {
                    output.put(nodeJson(node, nodeBounds, packageName, privateInput));
                }
            }

            boolean privateSubtree = pending.privateAncestor || isPrivateInput(node);
            for (int index = 0; index < node.getChildCount() && visited + queue.size() < limit;
                    index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) {
                    queue.addLast(new PendingNode(child, pending.depth + 1, privateSubtree));
                }
            }
        }
        return visited;
    }

    private static boolean coversDisplay(Rect bounds, int width, int height) {
        return bounds.left <= 0 && bounds.top <= 0
                && bounds.right >= width && bounds.bottom >= height;
    }

    private static JSONObject nodeJson(AccessibilityNodeInfo node, Rect nodeBounds,
            String packageName, boolean privateInput) throws Exception {
        JSONObject row = new JSONObject();
        row.put("package", packageName);
        row.put("role", bounded(chars(node.getClassName())));
        if (privateInput) {
            row.put("private_input", true);
        } else {
            putIfPresent(row, "text", chars(node.getText()));
            putIfPresent(row, "description", chars(node.getContentDescription()));
            putIfPresent(row, "hint", chars(node.getHintText()));
        }
        String viewId = node.getViewIdResourceName();
        if (!TextUtils.isEmpty(viewId) && !viewId.contains("0_resource_name_obfuscated")) {
            row.put("view_id", bounded(viewId));
        }
        row.put("clickable", node.isClickable());
        row.put("enabled", node.isEnabled());
        row.put("editable", node.isEditable());
        row.put("focused", node.isFocused());
        row.put("scrollable", node.isScrollable());
        if (node.isCheckable()) {
            row.put("checkable", true);
            row.put("checked", node.isChecked());
        }
        row.put("bounds", bounds(nodeBounds));
        row.put("center", new JSONObject()
                .put("x", nodeBounds.centerX())
                .put("y", nodeBounds.centerY()));
        return row;
    }

    private static JSONObject coordinateSpace(int width, int height) throws Exception {
        return new JSONObject().put("width", width).put("height", height)
                .put("origin", "top_left").put("units", "physical_pixels");
    }

    private static JSONObject bounds(Rect rect) throws Exception {
        return new JSONObject()
                .put("left", rect.left)
                .put("top", rect.top)
                .put("right", rect.right)
                .put("bottom", rect.bottom);
    }

    private static boolean validBounds(Rect rect, int width, int height) {
        return !rect.isEmpty() && rect.right > 0 && rect.bottom > 0
                && rect.left < width && rect.top < height;
    }

    private static String windowType(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "application";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "input_method";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY:
                return "accessibility_overlay";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "split_screen_divider";
            default: return "unknown_" + type;
        }
    }

    private static void putIfPresent(JSONObject row, String key, String value) throws Exception {
        if (!TextUtils.isEmpty(value)) row.put(key, bounded(value));
    }

    private static String boundedJson(JSONObject result, JSONArray windows) throws Exception {
        String json = result.toString();
        boolean truncated = false;
        while (json.length() > MAX_JSON_CHARS) {
            boolean removed = false;
            // Windows are sorted from the highest layer to the lowest. Drop
            // background controls and windows first so the focused surface
            // remains useful instead of being the first data cut.
            for (int index = windows.length() - 1; index >= 0; index--) {
                JSONArray controls = windows.getJSONObject(index).getJSONArray("controls");
                if (controls.length() > 0) {
                    controls.remove(controls.length() - 1);
                    removed = true;
                    truncated = true;
                    break;
                }
            }
            if (!removed && windows.length() > 1) {
                windows.remove(windows.length() - 1);
                removed = true;
                truncated = true;
            }
            if (!removed) break;
            if (truncated) result.put("truncated", true);
            json = result.toString();
        }
        if (json.length() > MAX_JSON_CHARS) {
            result.put("windows", new JSONArray());
            result.put("truncated", true);
            json = result.toString();
        }
        return json;
    }

    private static boolean isPrivateInput(AccessibilityNodeInfo node) {
        if (node.isPassword()) return true;
        int variation = node.getInputType()
                & (InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION);
        if (variation == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                || variation == (InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                || variation == (InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || variation == (InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            return true;
        }
        if (!node.isEditable()) return false;
        String metadata = Normalizer.normalize((
                chars(node.getViewIdResourceName()) + " "
                        + chars(node.getHintText()) + " "
                        + chars(node.getContentDescription()))
                .toLowerCase(java.util.Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9]+", " ");
        String padded = " " + metadata + " ";
        return padded.contains(" password ")
                || padded.contains(" passcode ")
                || padded.contains(" one time ")
                || padded.contains(" otp ")
                || padded.contains(" verification code ")
                || padded.contains(" security code ")
                || padded.contains(" card number ")
                || padded.contains(" cvv ")
                || padded.contains(" cvc ")
                || padded.contains(" pin ")
                || padded.contains(" contrasena ")
                || padded.contains(" clave ")
                || padded.contains(" codigo de verificacion ")
                || padded.contains(" codigo de seguridad ")
                || padded.contains(" numero de tarjeta ")
                || padded.contains(" nip ");
    }

    private static String windowIdentity(String packageName, Rect bounds, int layer) {
        return "window|" + packageName + "|" + layer + "|" + bounds.flattenToString();
    }

    private static String nodeIdentity(
            AccessibilityNodeInfo node,
            Rect bounds,
            String packageName,
            boolean privateInput) {
        StringBuilder identity = new StringBuilder("node|")
                .append(packageName).append('|')
                .append(bounded(chars(node.getClassName()))).append('|')
                .append(bounded(node.getViewIdResourceName())).append('|')
                .append(bounds.flattenToString()).append('|')
                .append(node.isClickable()).append('|')
                .append(node.isEditable()).append('|')
                .append(node.isScrollable()).append('|')
                .append(privateInput);
        if (privateInput) {
            identity.append("|private");
        } else {
            identity.append('|').append(bounded(chars(node.getText())))
                    .append('|').append(bounded(chars(node.getContentDescription())))
                    .append('|').append(bounded(chars(node.getHintText())))
                    .append('|').append(descendantLabel(node));
        }
        return identity.toString();
    }

    private static String descendantLabel(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        for (int index = 0; index < root.getChildCount(); index++) {
            AccessibilityNodeInfo child = root.getChild(index);
            if (child != null) queue.addLast(child);
        }
        StringBuilder label = new StringBuilder();
        int visited = 0;
        while (!queue.isEmpty() && visited < 24 && label.length() < 960) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            boolean privateNode = isPrivateInput(node);
            if (!privateNode) {
                appendIdentityText(label, chars(node.getText()));
                appendIdentityText(label, chars(node.getContentDescription()));
                appendIdentityText(label, chars(node.getHintText()));
                for (int index = 0; index < node.getChildCount()
                        && visited + queue.size() < 24; index++) {
                    AccessibilityNodeInfo child = node.getChild(index);
                    if (child != null) queue.addLast(child);
                }
            }
        }
        return bounded(label.toString());
    }

    private static void appendIdentityText(StringBuilder target, String value) {
        String checked = bounded(value);
        if (checked.isEmpty()) return;
        if (target.length() > 0) target.append(' ');
        target.append(checked);
    }

    private static String chars(CharSequence value) {
        return value == null ? null : value.toString();
    }

    private static String bounded(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_TEXT_CHARS
                ? normalized : normalized.substring(0, MAX_TEXT_CHARS);
    }
}
