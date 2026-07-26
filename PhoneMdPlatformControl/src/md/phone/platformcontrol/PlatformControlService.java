package md.phone.platformcontrol;

import android.app.ActivityTaskManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.ScreenCapture;

import md.phone.control.IPhoneMdControl;
import md.phone.control.IPhoneMdControlCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds only the platform authority needed for visual phone control.
 *
 * HOME sends one fixed action at a time. The broker checks its caller, current
 * foreground package, request bounds, and the post-action package before it
 * returns a receipt. It exposes no shell, arbitrary Binder, file, or settings
 * primitive. Financial apps are rejected before capture or input and a
 * transition into one is immediately sent home.
 */
public final class PlatformControlService extends Service {
    private static final String TAG = "PhoneMdPlatformControl";
    private static final String LAUNCHER_PACKAGE = "md.phone.launcher";
    private static final String LAUNCHER_CERT_SHA256 =
            "261ae1251b95af2d5af84e0c3831d261e8c0f716d18887bb23ffdbfd309215dc";
    private static final int PROTOCOL_VERSION = 6;
    private static final int MAX_TEXT_LENGTH = 20_000;
    private static final int MAX_PATH_POINTS = 128;
    private static final int GESTURE_DURATION_MILLIS = 300;
    private static final int GESTURE_EVENT_HZ = 120;
    private static final int GENERATED_MOTION_EDGE_FLAG = 0x40000000;
    private static final long OVERLAY_MAX_LIFETIME_MILLIS = 120_000;
    private static final long FRAME_TOKEN_MAX_AGE_MILLIS = 45_000;
    private static final int MAX_FRAME_CONTEXTS = 8;
    // A non-touchable overlay above Android's maximum obscuring opacity still
    // makes InputDispatcher reject injected touches beneath it as untrusted.
    // Stay below the 0.80 platform threshold instead of relying on WindowManager
    // to clamp the visual surface after its input window has been published.
    private static final float PROGRESS_OVERLAY_WINDOW_ALPHA = 0.79f;

    private static final String STATUS_OK = "ok";
    private static final String STATUS_INVALID = "invalid";
    private static final String STATUS_DENIED = "denied";
    private static final String STATUS_ERROR = "error";
    private static final String STATUS_FINANCIAL = "financial_package_blocked";
    private static final String STATUS_FINANCIAL_TRANSITION = "financial_transition_blocked";

    private final android.os.Handler mMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Object mOverlayLock = new Object();
    private final Object mFrameLock = new Object();
    private final LinkedHashMap<String, FrameContext> mFrameContexts = new LinkedHashMap<>();

    private WindowManager mWindowManager;
    private View mOverlay;
    private String mOverlayOperationId;
    private IPhoneMdControlCallback mOverlayCallback;
    private IBinder.DeathRecipient mOverlayDeathRecipient;
    private Runnable mOverlayExpiry;
    private boolean mOverlayConfirmation;
    private boolean mOverlaySuppressedForCapture;
    private InputMonitor mOverlayInputMonitor;
    private InputEventReceiver mOverlayInputReceiver;
    private final Rect mOverlayStopBounds = new Rect();

    private final IPhoneMdControl.Stub mBinder = new IPhoneMdControl.Stub() {
        @Override
        public int getProtocolVersion() {
            enforceTrustedCaller();
            return PROTOCOL_VERSION;
        }

        @Override
        public Bundle getCapabilities() {
            enforceTrustedCaller();
            Bundle result = baseResult(null, STATUS_OK, "Platform control is ready.");
            result.putInt("protocol_version", PROTOCOL_VERSION);
            result.putBoolean("capture", true);
            result.putBoolean("input", true);
            result.putBoolean("ui_semantics", true);
            result.putBoolean("frame_locked_input", true);
            result.putBoolean("system_surface_input", true);
            result.putBoolean("unicode_text", true);
            result.putBoolean("copilot_overlay", true);
            result.putBoolean("financial_package_boundary", true);
            result.putBoolean("system_operations", true);
            result.putBoolean("system_data", true);
            result.putBoolean("package_management", true);
            result.putBoolean("connectivity_management", true);
            result.putBoolean("power_control", true);
            result.putString("foreground_package", foregroundPackage());
            return result;
        }

        @Override
        public Bundle captureDisplay(String requestId) {
            enforceTrustedCaller();
            final long started = SystemClock.elapsedRealtime();
            if (!validRequestId(requestId)) {
                return timed(baseResult(requestId, STATUS_INVALID, "Invalid request id."), started);
            }
            final String foreground = foregroundPackage();
            if (foreground == null) {
                return timed(baseResult(requestId, STATUS_DENIED,
                        "The foreground app could not be verified."), started);
            }
            if (isFinancialPackage(foreground)) {
                returnHome();
                Bundle denied = baseResult(requestId, STATUS_FINANCIAL,
                        "Screen capture is blocked for financial apps; returned Home.");
                denied.putString("foreground_package", foreground);
                return timed(denied, started);
            }

            final long identity = Binder.clearCallingIdentity();
            String suppressedOverlayOperation = null;
            try {
                final boolean overlayWasActive = hasOverlay();
                suppressedOverlayOperation = setOverlayCaptureSuppressed(true, null);
                if (overlayWasActive && suppressedOverlayOperation == null) {
                    return timed(baseResult(requestId, STATUS_ERROR,
                            "The progress overlay could not be excluded from capture."), started);
                }
                ScreenCapture.SynchronousScreenCaptureListener listener =
                        ScreenCapture.createSyncCaptureListener();
                WindowManagerGlobal.getWindowManagerService().captureDisplay(
                        Display.DEFAULT_DISPLAY, null, listener);
                ScreenCapture.ScreenshotHardwareBuffer buffer = listener.getBuffer();
                if (buffer == null || buffer.getHardwareBuffer() == null) {
                    return timed(baseResult(requestId, STATUS_ERROR,
                            "The display returned no capture buffer."), started);
                }
                if (buffer.containsSecureLayers()) {
                    buffer.getHardwareBuffer().close();
                    return timed(baseResult(requestId, STATUS_DENIED,
                            "A secure surface cannot be captured."), started);
                }

                Bitmap hardwareBitmap = buffer.asBitmap();
                Bitmap bitmap = hardwareBitmap == null ? null
                        : hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (hardwareBitmap != null) hardwareBitmap.recycle();
                buffer.getHardwareBuffer().close();
                if (bitmap == null) {
                    return timed(baseResult(requestId, STATUS_ERROR,
                            "The capture buffer could not be converted."), started);
                }

                final int width = bitmap.getWidth();
                final int height = bitmap.getHeight();

                File capture = File.createTempFile("phone-md-screen-", ".png", getCacheDir());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (FileOutputStream output = new FileOutputStream(capture);
                     java.security.DigestOutputStream checked =
                             new java.security.DigestOutputStream(output, digest)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, checked)) {
                        throw new IllegalStateException("PNG encoder rejected the frame");
                    }
                } finally {
                    bitmap.recycle();
                }
                ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                        capture, ParcelFileDescriptor.MODE_READ_ONLY);
                if (!capture.delete()) capture.deleteOnExit();

                final String frameSha256 = hex(digest.digest());
                final UiSemanticsSnapshot semantics =
                        UiSemanticsSnapshot.capture(PlatformControlService.this, width, height);
                final String frameToken = UUID.randomUUID().toString();
                rememberFrame(frameToken, foreground, frameSha256, semantics);

                Bundle result = baseResult(requestId, STATUS_OK, "Display captured.");
                result.putParcelable("image_fd", descriptor);
                result.putInt("width", width);
                result.putInt("height", height);
                result.putString("sha256", frameSha256);
                result.putString("foreground_package", foreground);
                result.putString("frame_token", frameToken);
                result.putBoolean("ui_semantics_available", semantics.available);
                result.putString("ui_semantics", semantics.json);
                return timed(result, started);
            } catch (Throwable error) {
                Slog.e(TAG, "Display capture failed", error);
                return timed(baseResult(requestId, STATUS_ERROR,
                        "Display capture failed: " + error.getClass().getSimpleName()), started);
            } finally {
                if (suppressedOverlayOperation != null) {
                    setOverlayCaptureSuppressed(false, suppressedOverlayOperation);
                }
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public Bundle execute(Bundle request) {
            enforceTrustedCaller();
            final long started = SystemClock.elapsedRealtime();
            if (request == null || request.getInt("protocol_version", -1) != PROTOCOL_VERSION) {
                return timed(baseResult(null, STATUS_INVALID, "Protocol version mismatch."), started);
            }
            final String requestId = request.getString("request_id");
            final String action = safeLower(request.getString("action"));
            if (!validRequestId(requestId) || action.isEmpty()) {
                return timed(baseResult(requestId, STATUS_INVALID, "Invalid action request."), started);
            }

            final String before = foregroundPackage();
            if (before == null) {
                return timed(baseResult(requestId, STATUS_DENIED,
                        "The foreground app could not be verified."), started);
            }
            if (isFinancialPackage(before)) {
                returnHome();
                Bundle denied = baseResult(requestId, STATUS_FINANCIAL,
                        "Input is blocked for financial apps; returned Home.");
                denied.putString("action", action);
                denied.putString("foreground_before", before);
                return timed(denied, started);
            }
            final String frameToken = request.getString("frame_token");
            if (frameToken == null || frameToken.isBlank()) {
                return timed(baseResult(requestId, STATUS_DENIED,
                        "Input requires the exact captured visual frame."), started);
            }
            final FrameContext frame = consumeFrameContext(frameToken);
            if (frame == null) {
                return timed(baseResult(requestId, STATUS_DENIED,
                        "The visual frame expired; capture the screen again."), started);
            }
            if (!before.equals(frame.foregroundPackage)) {
                Bundle denied = baseResult(requestId, STATUS_DENIED,
                        "The foreground changed after the visual frame; capture again.");
                denied.putString("foreground_before", before);
                denied.putString("frame_foreground", frame.foregroundPackage);
                return timed(denied, started);
            }
            final String targetPackage = targetPackage(action, request, before, frame);
            if (isFinancialPackage(targetPackage)) {
                returnHome();
                Bundle denied = baseResult(requestId, STATUS_FINANCIAL,
                        "Input is blocked for financial apps; returned Home.");
                denied.putString("action", action);
                denied.putString("foreground_before", before);
                denied.putString("target_package", targetPackage);
                return timed(denied, started);
            }
            final int targetUid;
            try {
                targetUid = getPackageManager().getApplicationInfo(targetPackage, 0).uid;
            } catch (PackageManager.NameNotFoundException error) {
                return timed(baseResult(requestId, STATUS_DENIED,
                        "The visible input target identity could not be resolved."), started);
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                // InputDispatcher must deliver the action only to the exact app
                // whose foreground identity was checked above. The co-pilot Stop
                // is a non-touchable visual surface. A privileged gesture monitor
                // handles real human Stop taps, while tagged generated touches
                // continue only to the verified app UID.
                final boolean applied = executeChecked(action, request, targetUid);
                if (applied) SystemClock.sleep(160);
                final String after = foregroundPackage();
                if (after == null) {
                    injectKeyCode(KeyEvent.KEYCODE_HOME, 0);
                    Bundle denied = baseResult(requestId, STATUS_DENIED,
                            "The post-action foreground app could not be verified; returned Home.");
                    denied.putString("action", action);
                    denied.putBoolean("applied", false);
                    denied.putString("foreground_before", before);
                    return timed(denied, started);
                }
                if (isFinancialPackage(after)) {
                    injectKeyCode(KeyEvent.KEYCODE_HOME, 0);
                    Bundle denied = baseResult(requestId, STATUS_FINANCIAL_TRANSITION,
                            "A transition into a financial app was blocked.");
                    denied.putString("action", action);
                    denied.putBoolean("applied", false);
                    denied.putString("foreground_before", before);
                    denied.putString("foreground_after", after);
                    return timed(denied, started);
                }
                Bundle result = baseResult(requestId, applied ? STATUS_OK : STATUS_ERROR,
                        applied ? "Action injected." : "Android rejected the input event.");
                result.putString("action", action);
                result.putBoolean("applied", applied);
                result.putString("foreground_before", before);
                result.putString("foreground_after", after);
                result.putString("target_package", targetPackage);
                result.putBoolean("frame_token_checked", frame != null);
                return timed(result, started);
            } catch (IllegalArgumentException error) {
                return timed(baseResult(requestId, STATUS_INVALID, error.getMessage()), started);
            } catch (Throwable error) {
                Slog.e(TAG, "Input action failed", error);
                return timed(baseResult(requestId, STATUS_ERROR,
                        "Input failed: " + error.getClass().getSimpleName()), started);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public Bundle performSystemOperation(Bundle request) {
            enforceTrustedCaller();
            return new SystemOperationController(PlatformControlService.this)
                    .execute(request, PROTOCOL_VERSION);
        }

        @Override
        public boolean showTask(Bundle state, IPhoneMdControlCallback callback) {
            enforceTrustedCaller();
            if (state == null || callback == null) return false;
            final String operationId = state.getString("operation_id");
            if (!validRequestId(operationId)) return false;
            final String title = bounded(state.getString("title"), 120, "Phone is working");
            final String detail = bounded(state.getString("detail"), 240, "");
            final String stopLabel = bounded(state.getString("stop_label"), 40, "Stop");
            final boolean confirmation = state.getBoolean("confirmation", false);
            final String approveLabel = bounded(state.getString("approve_label"), 40, "Allow");
            final String denyLabel = bounded(state.getString("deny_label"), 40, "Cancel");
            final AtomicBoolean shown = new AtomicBoolean(false);
            final CountDownLatch finished = new CountDownLatch(1);
            final Runnable task = () -> {
                try {
                    shown.set(showOverlay(operationId, title, detail, stopLabel, confirmation,
                            approveLabel, denyLabel, callback));
                } finally {
                    finished.countDown();
                }
            };
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                task.run();
            } else if (!mMainHandler.post(task)) {
                return false;
            }
            try {
                return finished.await(2, TimeUnit.SECONDS) && shown.get();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public void hideTask(String operationId) {
            enforceTrustedCaller();
            mMainHandler.post(() -> hideOverlay(operationId));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mWindowManager = getSystemService(WindowManager.class);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        hideOverlay(null);
        super.onDestroy();
    }

    private void enforceTrustedCaller() {
        final int uid = Binder.getCallingUid();
        PackageManager packageManager = getPackageManager();
        String[] packages = packageManager.getPackagesForUid(uid);
        if (packages == null || !Arrays.asList(packages).contains(LAUNCHER_PACKAGE)) {
            throw new SecurityException("Caller is not phone.md HOME");
        }
        try {
            ApplicationInfo launcher = packageManager.getApplicationInfo(LAUNCHER_PACKAGE, 0);
            if (launcher.uid != uid
                    || (launcher.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    || (launcher.privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) == 0) {
                throw new SecurityException("phone.md HOME is not a privileged system app");
            }
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    LAUNCHER_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
            if (packageInfo.signingInfo == null
                    || packageInfo.signingInfo.getApkContentsSigners().length != 1
                    || !LAUNCHER_CERT_SHA256.equals(hex(MessageDigest.getInstance("SHA-256")
                    .digest(packageInfo.signingInfo.getApkContentsSigners()[0].toByteArray())))) {
                throw new SecurityException("phone.md HOME certificate mismatch");
            }
        } catch (SecurityException error) {
            throw error;
        } catch (Throwable error) {
            throw new SecurityException("Could not validate phone.md HOME", error);
        }

        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo home = packageManager.resolveActivityAsUser(
                homeIntent, PackageManager.MATCH_DEFAULT_ONLY, UserHandle.getUserId(uid));
        if (home == null || home.activityInfo == null
                || !LAUNCHER_PACKAGE.equals(home.activityInfo.packageName)) {
            throw new SecurityException("phone.md is not the selected HOME");
        }
    }

    private void returnHome() {
        final long identity = Binder.clearCallingIdentity();
        try {
            injectKeyCode(KeyEvent.KEYCODE_HOME, 0);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean executeChecked(String action, Bundle request, int targetUid) {
        switch (action) {
            case "tap":
                return tap(coordinate(request, "x", true), coordinate(request, "y", false),
                        targetUid);
            case "double_tap": {
                int x = coordinate(request, "x", true);
                int y = coordinate(request, "y", false);
                boolean first = tap(x, y, targetUid);
                SystemClock.sleep(120);
                return first && tap(x, y, targetUid);
            }
            case "drag":
                return drag(request.getIntArray("path_x"), request.getIntArray("path_y"),
                        targetUid);
            case "scroll": {
                DisplayMetrics metrics = displayMetrics();
                int x = clamp(request.getInt("x", metrics.widthPixels / 2), 0,
                        metrics.widthPixels - 1);
                int y = clamp(request.getInt("y", metrics.heightPixels / 2), 0,
                        metrics.heightPixels - 1);
                int dx = request.getInt("dx", 0);
                int dy = request.getInt("dy", 0);
                return drag(new int[]{
                                x,
                                clamp(x - dx / 2, 0, metrics.widthPixels - 1),
                                clamp(x - dx, 0, metrics.widthPixels - 1),
                            },
                        new int[]{
                                y,
                                clamp(y - dy / 2, 0, metrics.heightPixels - 1),
                                clamp(y - dy, 0, metrics.heightPixels - 1),
                            },
                        targetUid);
            }
            case "type_text":
                return typeText(request.getString("text", ""), targetUid);
            case "key_press":
                return keyPress(request.getStringArrayList("keys"), targetUid);
            case "move":
            case "screenshot":
                return true;
            default:
                throw new IllegalArgumentException("Unsupported action: " + action);
        }
    }

    private String targetPackage(String action, Bundle request, String fallback,
            FrameContext frame) {
        if (frame == null || frame.semantics == null) return fallback;
        switch (action) {
            case "tap":
            case "double_tap":
                return frame.semantics.packageAt(
                        coordinate(request, "x", true),
                        coordinate(request, "y", false),
                        fallback,
                        false);
            case "scroll":
                DisplayMetrics metrics = displayMetrics();
                return frame.semantics.packageAt(
                        clamp(request.getInt("x", metrics.widthPixels / 2),
                                0, metrics.widthPixels - 1),
                        clamp(request.getInt("y", metrics.heightPixels / 2),
                                0, metrics.heightPixels - 1),
                        fallback,
                        true);
            case "drag":
                int[] xs = request.getIntArray("path_x");
                int[] ys = request.getIntArray("path_y");
                if (xs != null && ys != null && xs.length > 0 && ys.length > 0) {
                    return frame.semantics.packageAt(xs[0], ys[0], fallback, false);
                }
                return fallback;
            case "type_text":
            case "key_press":
                return frame.semantics.inputPackage(fallback);
            default:
                return fallback;
        }
    }

    private void rememberFrame(String token, String foreground, String sha256,
            UiSemanticsSnapshot semantics) {
        synchronized (mFrameLock) {
            pruneFramesLocked();
            mFrameContexts.put(token, new FrameContext(
                    foreground, sha256, semantics, SystemClock.elapsedRealtime()));
            while (mFrameContexts.size() > MAX_FRAME_CONTEXTS) {
                String oldest = mFrameContexts.keySet().iterator().next();
                mFrameContexts.remove(oldest);
            }
        }
    }

    private FrameContext consumeFrameContext(String token) {
        synchronized (mFrameLock) {
            pruneFramesLocked();
            return mFrameContexts.remove(token);
        }
    }

    private void pruneFramesLocked() {
        final long cutoff = SystemClock.elapsedRealtime() - FRAME_TOKEN_MAX_AGE_MILLIS;
        mFrameContexts.entrySet().removeIf(entry -> entry.getValue().capturedAt < cutoff);
    }

    private static final class FrameContext {
        final String foregroundPackage;
        final String sha256;
        final UiSemanticsSnapshot semantics;
        final long capturedAt;

        FrameContext(String foregroundPackage, String sha256,
                UiSemanticsSnapshot semantics, long capturedAt) {
            this.foregroundPackage = foregroundPackage;
            this.sha256 = sha256;
            this.semantics = semantics;
            this.capturedAt = capturedAt;
        }
    }

    private boolean tap(int x, int y, int targetUid) {
        long down = SystemClock.uptimeMillis();
        boolean first = injectMotion(MotionEvent.ACTION_DOWN, down, down, x, y, 1.0f,
                targetUid);
        return injectMotion(MotionEvent.ACTION_UP, down, SystemClock.uptimeMillis(),
                x, y, 0.0f, targetUid) && first;
    }

    private boolean drag(int[] xs, int[] ys, int targetUid) {
        if (xs == null || ys == null || xs.length != ys.length || xs.length < 2
                || xs.length > MAX_PATH_POINTS) {
            throw new IllegalArgumentException("A drag needs 2 to 128 matched points.");
        }
        DisplayMetrics metrics = displayMetrics();
        for (int index = 0; index < xs.length; index++) {
            requireCoordinate(xs[index], metrics.widthPixels, "x");
            requireCoordinate(ys[index], metrics.heightPixels, "y");
        }

        double[] distanceAt = new double[xs.length];
        for (int index = 1; index < xs.length; index++) {
            distanceAt[index] = distanceAt[index - 1] + Math.hypot(
                    xs[index] - xs[index - 1], ys[index] - ys[index - 1]);
        }
        final double totalDistance = distanceAt[distanceAt.length - 1];
        if (totalDistance < 1.0) {
            throw new IllegalArgumentException("A drag needs distinct points.");
        }

        long down = SystemClock.uptimeMillis();
        boolean applied = injectMotion(MotionEvent.ACTION_DOWN, down, down,
                xs[0], ys[0], 1.0f, targetUid);
        final long end = down + GESTURE_DURATION_MILLIS;
        final float eventPeriodMillis = 1_000.0f / GESTURE_EVENT_HZ;
        int injected = 1;
        long now = SystemClock.uptimeMillis();
        while (now < end) {
            final long elapsed = now - down;
            final long wait = (long) Math.floor(injected * eventPeriodMillis - elapsed);
            if (wait > 0) {
                SystemClock.sleep(Math.min(wait, end - now));
            }
            now = SystemClock.uptimeMillis();
            if (now >= end) break;

            final float alpha = Math.min(1.0f,
                    (float) (now - down) / GESTURE_DURATION_MILLIS);
            final float[] point = interpolatePath(xs, ys, distanceAt, totalDistance, alpha);
            applied = injectMotion(MotionEvent.ACTION_MOVE, down, now,
                    point[0], point[1], 1.0f, targetUid) && applied;
            injected++;
            now = SystemClock.uptimeMillis();
        }
        return injectMotion(MotionEvent.ACTION_UP, down, SystemClock.uptimeMillis(),
                xs[xs.length - 1], ys[ys.length - 1], 0.0f, targetUid) && applied;
    }

    private float[] interpolatePath(int[] xs, int[] ys, double[] distanceAt,
            double totalDistance, float alpha) {
        final double target = totalDistance * alpha;
        int segment = 1;
        while (segment < distanceAt.length - 1 && distanceAt[segment] < target) {
            segment++;
        }
        final double segmentDistance = distanceAt[segment] - distanceAt[segment - 1];
        final float segmentAlpha = segmentDistance == 0.0 ? 1.0f
                : (float) ((target - distanceAt[segment - 1]) / segmentDistance);
        return new float[]{
                xs[segment - 1] + (xs[segment] - xs[segment - 1]) * segmentAlpha,
                ys[segment - 1] + (ys[segment] - ys[segment - 1]) * segmentAlpha,
        };
    }

    private boolean typeText(String text, int targetUid) {
        if (text == null || text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Text input is too long.");
        }
        if (text.isEmpty()) return true;
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData previous = clipboard.hasPrimaryClip() ? clipboard.getPrimaryClip() : null;
        String previousSource = previous == null ? null : clipboard.getPrimaryClipSource();
        try {
            String foreground = foregroundPackage();
            ClipData input = ClipData.newPlainText("phone.md input", text);
            if (foreground == null) {
                clipboard.setPrimaryClip(input);
            } else {
                clipboard.setPrimaryClipAsPackage(input, foreground);
            }
            SystemClock.sleep(40);
            return injectKeyCode(KeyEvent.KEYCODE_PASTE, 0, targetUid);
        } finally {
            SystemClock.sleep(80);
            if (previous == null) {
                clipboard.clearPrimaryClip();
            } else if (previousSource == null) {
                clipboard.setPrimaryClip(previous);
            } else {
                clipboard.setPrimaryClipAsPackage(previous, previousSource);
            }
        }
    }

    private boolean keyPress(ArrayList<String> keys, int targetUid) {
        if (keys == null || keys.isEmpty() || keys.size() > 8) {
            throw new IllegalArgumentException("A key press needs 1 to 8 keys.");
        }
        int metaState = 0;
        List<Integer> modifiers = new ArrayList<>();
        Integer main = null;
        for (String raw : keys) {
            String key = safeUpper(raw);
            switch (key) {
                case "CTRL":
                case "CONTROL":
                    metaState |= KeyEvent.META_CTRL_ON;
                    modifiers.add(KeyEvent.KEYCODE_CTRL_LEFT);
                    break;
                case "ALT":
                    metaState |= KeyEvent.META_ALT_ON;
                    modifiers.add(KeyEvent.KEYCODE_ALT_LEFT);
                    break;
                case "SHIFT":
                    metaState |= KeyEvent.META_SHIFT_ON;
                    modifiers.add(KeyEvent.KEYCODE_SHIFT_LEFT);
                    break;
                case "CMD":
                case "META":
                    metaState |= KeyEvent.META_META_ON;
                    modifiers.add(KeyEvent.KEYCODE_META_LEFT);
                    break;
                default:
                    int mapped = keyCodeFor(key);
                    if (mapped == KeyEvent.KEYCODE_UNKNOWN || main != null) {
                        throw new IllegalArgumentException("Unsupported key chord.");
                    }
                    main = mapped;
            }
        }
        if (main == null) throw new IllegalArgumentException("A key chord needs a non-modifier key.");
        if (main == KeyEvent.KEYCODE_HOME && modifiers.isEmpty()) {
            return injectKeyCode(KeyEvent.KEYCODE_HOME, 0);
        }
        boolean applied = true;
        for (int modifier : modifiers) {
            applied = injectKeyEvent(KeyEvent.ACTION_DOWN, modifier, metaState, targetUid)
                    && applied;
        }
        applied = injectKeyEvent(KeyEvent.ACTION_DOWN, main, metaState, targetUid) && applied;
        applied = injectKeyEvent(KeyEvent.ACTION_UP, main, metaState, targetUid) && applied;
        for (int index = modifiers.size() - 1; index >= 0; index--) {
            applied = injectKeyEvent(KeyEvent.ACTION_UP, modifiers.get(index), metaState,
                    targetUid) && applied;
        }
        return applied;
    }

    private int keyCodeFor(String key) {
        switch (key) {
            case "ENTER":
            case "RETURN": return KeyEvent.KEYCODE_ENTER;
            case "BACKSPACE":
            case "DELETE":
            case "DEL": return KeyEvent.KEYCODE_DEL;
            case "TAB": return KeyEvent.KEYCODE_TAB;
            case "SPACE": return KeyEvent.KEYCODE_SPACE;
            case "BACK":
            case "ESCAPE":
            case "ESC": return KeyEvent.KEYCODE_BACK;
            case "HOME": return KeyEvent.KEYCODE_HOME;
            case "UP": return KeyEvent.KEYCODE_DPAD_UP;
            case "DOWN": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "LEFT": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "RIGHT": return KeyEvent.KEYCODE_DPAD_RIGHT;
            default: return KeyEvent.keyCodeFromString("KEYCODE_" + key);
        }
    }

    private boolean injectMotion(int action, long downTime, long eventTime,
            float x, float y, float pressure, int targetUid) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = x;
        coordinates.y = y;
        coordinates.pressure = pressure;
        coordinates.size = 1.0f;
        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                1,
                new MotionEvent.PointerProperties[]{properties},
                new MotionEvent.PointerCoords[]{coordinates},
                0,
                0,
                1.0f,
                1.0f,
                touchscreenDeviceId(),
                GENERATED_MOTION_EDGE_FLAG,
                InputDevice.SOURCE_TOUCHSCREEN,
                Display.DEFAULT_DISPLAY,
                0);
        try {
            return inject(event, targetUid);
        } finally {
            event.recycle();
        }
    }

    private int touchscreenDeviceId() {
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device != null && device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                return deviceId;
            }
        }
        return 0;
    }

    private boolean injectKeyCode(int keyCode, int metaState) {
        return injectKeyEvent(KeyEvent.ACTION_DOWN, keyCode, metaState)
                && injectKeyEvent(KeyEvent.ACTION_UP, keyCode, metaState);
    }

    private boolean injectKeyCode(int keyCode, int metaState, int targetUid) {
        return injectKeyEvent(KeyEvent.ACTION_DOWN, keyCode, metaState, targetUid)
                && injectKeyEvent(KeyEvent.ACTION_UP, keyCode, metaState, targetUid);
    }

    private boolean injectKeyEvent(int action, int keyCode, int metaState) {
        long now = SystemClock.uptimeMillis();
        return inject(new KeyEvent(now, now, action, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    private boolean injectKeyEvent(int action, int keyCode, int metaState, int targetUid) {
        long now = SystemClock.uptimeMillis();
        return inject(new KeyEvent(now, now, action, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD),
                targetUid);
    }

    private boolean inject(InputEvent event) {
        return InputManager.getInstance().injectInputEvent(
                event, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    private boolean inject(InputEvent event, int targetUid) {
        return InputManager.getInstance().injectInputEvent(
                event, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH, targetUid);
    }

    private int coordinate(Bundle request, String key, boolean horizontal) {
        DisplayMetrics metrics = displayMetrics();
        int value = request.getInt(key, -1);
        requireCoordinate(value, horizontal ? metrics.widthPixels : metrics.heightPixels, key);
        return value;
    }

    private void requireCoordinate(int value, int limit, String name) {
        if (value < 0 || value >= limit) {
            throw new IllegalArgumentException(name + " coordinate is outside the display.");
        }
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics displayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getRealMetrics(metrics);
        return metrics;
    }

    private String foregroundPackage() {
        final long identity = Binder.clearCallingIdentity();
        try {
            ActivityTaskManager.RootTaskInfo task =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            ComponentName top = task == null ? null : task.topActivity;
            return top == null ? null : top.getPackageName();
        } catch (Throwable error) {
            Slog.w(TAG, "Could not read foreground package", error);
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean isFinancialPackage(String packageName) {
        if (packageName == null || packageName.isBlank()) return false;
        String normalizedPackage = packageName.toLowerCase(Locale.ROOT);
        for (String token : FINANCIAL_PACKAGE_TOKENS) {
            if (normalizedPackage.contains(token)) return true;
        }
        try {
            ApplicationInfo app = getPackageManager().getApplicationInfo(packageName, 0);
            String label = String.valueOf(getPackageManager().getApplicationLabel(app));
            String normalizedLabel = normalize(label);
            for (String token : FINANCIAL_LABEL_TOKENS) {
                if (containsWord(normalizedLabel, token)) return true;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    private boolean showOverlay(String operationId, String title, String detail, String stopLabel,
            boolean confirmation, String approveLabel, String denyLabel,
            IPhoneMdControlCallback callback) {
        synchronized (mOverlayLock) {
            hideOverlayLocked(null);
            final IBinder callbackBinder = callback.asBinder();
            final IBinder.DeathRecipient deathRecipient =
                    () -> mMainHandler.post(() -> hideOverlay(operationId));
            try {
                callbackBinder.linkToDeath(deathRecipient, 0);
            } catch (RemoteException error) {
                Slog.w(TAG, "Control callback died before overlay display", error);
                return false;
            }
            try {
                LinearLayout panel = new LinearLayout(this);
                panel.setOrientation(LinearLayout.HORIZONTAL);
                panel.setGravity(android.view.Gravity.CENTER_VERTICAL);
                panel.setPadding(dp(16), dp(8), dp(10), dp(8));
                panel.setBackgroundColor(Color.argb(242, 18, 18, 18));

                LinearLayout copy = new LinearLayout(this);
                copy.setOrientation(LinearLayout.VERTICAL);
                TextView heading = new TextView(this);
                heading.setText(title);
                heading.setTextColor(Color.WHITE);
                heading.setTextSize(15);
                heading.setTypeface(null, android.graphics.Typeface.BOLD);
                TextView status = new TextView(this);
                status.setText(detail);
                status.setTextColor(Color.rgb(210, 210, 210));
                status.setTextSize(12);
                copy.addView(heading);
                if (!detail.isEmpty()) copy.addView(status);
                panel.addView(copy, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                Button progressStop = null;

                if (confirmation) {
                    Button deny = new Button(this);
                    deny.setText(denyLabel);
                    deny.setAllCaps(false);
                    deny.setOnClickListener(view -> deliverConfirmation(
                            callback, operationId, false));
                    panel.addView(deny, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));
                    Button approve = new Button(this);
                    approve.setText(approveLabel);
                    approve.setAllCaps(false);
                    approve.setOnClickListener(view -> deliverConfirmation(
                            callback, operationId, true));
                    panel.addView(approve, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));
                } else {
                    Button stop = new Button(this);
                    stop.setText(stopLabel);
                    stop.setAllCaps(false);
                    panel.addView(stop, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));
                    progressStop = stop;
                }

                int windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                if (!confirmation) {
                    // The visible progress surface never participates in normal
                    // input dispatch. A gesture monitor below owns only a real
                    // human tap on the Stop bounds.
                    windowFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                }
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                        windowFlags,
                        PixelFormat.TRANSLUCENT);
                params.gravity = android.view.Gravity.TOP;
                params.setTitle("phone.md co-pilot");
                if (!confirmation) {
                    params.alpha = PROGRESS_OVERLAY_WINDOW_ALPHA;
                }
                mWindowManager.addView(panel, params);
                mOverlay = panel;
                mOverlayOperationId = operationId;
                mOverlayCallback = callback;
                mOverlayDeathRecipient = deathRecipient;
                mOverlayConfirmation = confirmation;
                mOverlaySuppressedForCapture = false;
                if (!confirmation) {
                    startOverlayStopMonitor(operationId, callback, progressStop);
                }
                mOverlayExpiry = () -> {
                    Slog.w(TAG, "Expiring stale control overlay: " + operationId);
                    hideOverlay(operationId);
                };
                mMainHandler.postDelayed(mOverlayExpiry, OVERLAY_MAX_LIFETIME_MILLIS);
                return true;
            } catch (Throwable error) {
                callbackBinder.unlinkToDeath(deathRecipient, 0);
                Slog.e(TAG, "Could not display control overlay", error);
                return false;
            }
        }
    }

    private void startOverlayStopMonitor(String operationId,
            IPhoneMdControlCallback callback, View stop) {
        if (stop == null) {
            throw new IllegalStateException("A progress overlay needs a Stop control.");
        }
        mOverlayStopBounds.setEmpty();
        stop.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) ->
                updateOverlayStopBounds(operationId, view));
        stop.post(() -> updateOverlayStopBounds(operationId, stop));

        InputMonitor monitor = getSystemService(InputManager.class)
                .monitorGestureInput("phone.md co-pilot Stop", Display.DEFAULT_DISPLAY);
        mOverlayInputMonitor = monitor;
        mOverlayInputReceiver = new InputEventReceiver(
                monitor.getInputChannel(), Looper.getMainLooper()) {
            @Override
            public void onInputEvent(InputEvent event) {
                try {
                    if (!(event instanceof MotionEvent)) return;
                    MotionEvent motion = (MotionEvent) event;
                    if (motion.getActionMasked() != MotionEvent.ACTION_DOWN
                            || (motion.getEdgeFlags() & GENERATED_MOTION_EDGE_FLAG) != 0) {
                        return;
                    }
                    final boolean stopHit;
                    synchronized (mOverlayLock) {
                        stopHit = operationId.equals(mOverlayOperationId)
                                && mOverlayStopBounds.contains(
                                        Math.round(motion.getRawX()),
                                        Math.round(motion.getRawY()));
                    }
                    if (!stopHit) return;
                    monitor.pilferPointers();
                    try {
                        Slog.i(TAG, "User requested control stop: " + operationId);
                        callback.onCancelRequested(operationId);
                    } catch (RemoteException error) {
                        Slog.w(TAG, "Control callback died", error);
                    }
                    mMainHandler.post(() -> hideOverlay(operationId));
                } finally {
                    finishInputEvent(event, true);
                }
            }
        };
    }

    private void updateOverlayStopBounds(String operationId, View stop) {
        int[] location = new int[2];
        stop.getLocationOnScreen(location);
        synchronized (mOverlayLock) {
            if (!operationId.equals(mOverlayOperationId)) return;
            mOverlayStopBounds.set(
                    location[0],
                    location[1],
                    location[0] + stop.getWidth(),
                    location[1] + stop.getHeight());
        }
    }

    private void deliverConfirmation(IPhoneMdControlCallback callback,
            String operationId, boolean approved) {
        try {
            callback.onConfirmation(operationId, approved);
        } catch (RemoteException error) {
            Slog.w(TAG, "Control callback died", error);
        }
        hideOverlay(operationId);
    }

    /**
     * Suppresses an active progress banner for one capture. The model sees only
     * the app pixels the user asked it to control.
     */
    private String setOverlayCaptureSuppressed(boolean suppressed,
            String expectedOperationId) {
        AtomicReference<String> affectedOperation = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch windowTraversalFinished = new CountDownLatch(1);
        Runnable task = () -> {
            try {
                synchronized (mOverlayLock) {
                    if (mOverlay == null) return;
                    if (expectedOperationId != null
                            && !expectedOperationId.equals(mOverlayOperationId)) return;
                    if (mOverlayConfirmation) return;
                    WindowManager.LayoutParams params =
                            (WindowManager.LayoutParams) mOverlay.getLayoutParams();
                    if (suppressed) {
                        if (!mOverlaySuppressedForCapture) {
                            awaitNextOverlayLayout(windowTraversalFinished);
                            params.alpha = 0.0f;
                            mWindowManager.updateViewLayout(mOverlay, params);
                            mOverlay.requestLayout();
                            mOverlaySuppressedForCapture = true;
                        } else {
                            windowTraversalFinished.countDown();
                        }
                    } else if (mOverlaySuppressedForCapture) {
                        awaitNextOverlayLayout(windowTraversalFinished);
                        params.alpha = PROGRESS_OVERLAY_WINDOW_ALPHA;
                        mWindowManager.updateViewLayout(mOverlay, params);
                        mOverlay.requestLayout();
                        mOverlaySuppressedForCapture = false;
                    } else {
                        windowTraversalFinished.countDown();
                    }
                    affectedOperation.set(mOverlayOperationId);
                }
            } catch (Throwable error) {
                Slog.e(TAG, "Could not change control overlay capture mode", error);
            } finally {
                finished.countDown();
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            task.run();
        } else if (!mMainHandler.post(task)) {
            return null;
        }
        try {
            if (!finished.await(2, TimeUnit.SECONDS)) {
                Slog.e(TAG, "Timed out changing control overlay capture mode");
                return null;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        }
        final String operationId = affectedOperation.get();
        if (operationId == null) return null;
        try {
            if (!windowTraversalFinished.await(1, TimeUnit.SECONDS)) {
                Slog.e(TAG, "Timed out publishing control overlay capture mode");
                return null;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            // Force the touchability update through InputDispatcher before
            // injecting or accepting a human Stop tap.
            WindowManagerGlobal.getWindowManagerService().syncInputTransactions(false);
        } catch (Throwable error) {
            Slog.e(TAG, "Could not synchronize control overlay capture mode", error);
            return null;
        }
        return operationId;
    }

    private void awaitNextOverlayLayout(CountDownLatch finished) {
        final ViewTreeObserver observer = mOverlay.getViewTreeObserver();
        final ViewTreeObserver.OnGlobalLayoutListener[] listener =
                new ViewTreeObserver.OnGlobalLayoutListener[1];
        listener[0] = () -> {
            if (observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(listener[0]);
            }
            finished.countDown();
        };
        observer.addOnGlobalLayoutListener(listener[0]);
    }

    private boolean hasOverlay() {
        synchronized (mOverlayLock) {
            return mOverlay != null;
        }
    }

    private void hideOverlay(String operationId) {
        synchronized (mOverlayLock) {
            hideOverlayLocked(operationId);
        }
    }

    private void hideOverlayLocked(String operationId) {
        if (mOverlay == null) return;
        if (operationId != null && !operationId.equals(mOverlayOperationId)) return;
        try {
            mWindowManager.removeViewImmediate(mOverlay);
        } catch (Throwable error) {
            Slog.w(TAG, "Could not remove control overlay", error);
        }
        if (mOverlayCallback != null && mOverlayDeathRecipient != null) {
            mOverlayCallback.asBinder().unlinkToDeath(mOverlayDeathRecipient, 0);
        }
        if (mOverlayExpiry != null) {
            mMainHandler.removeCallbacks(mOverlayExpiry);
        }
        if (mOverlayInputReceiver != null) {
            mOverlayInputReceiver.dispose();
        }
        if (mOverlayInputMonitor != null) {
            mOverlayInputMonitor.dispose();
        }
        mOverlay = null;
        mOverlayOperationId = null;
        mOverlayCallback = null;
        mOverlayDeathRecipient = null;
        mOverlayConfirmation = false;
        mOverlaySuppressedForCapture = false;
        mOverlayInputReceiver = null;
        mOverlayInputMonitor = null;
        mOverlayStopBounds.setEmpty();
        mOverlayExpiry = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static Bundle baseResult(String requestId, String status, String detail) {
        Bundle result = new Bundle();
        result.putInt("protocol_version", PROTOCOL_VERSION);
        result.putString("request_id", requestId);
        result.putString("status", status);
        result.putString("detail", detail);
        return result;
    }

    private static Bundle timed(Bundle result, long started) {
        result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started);
        return result;
    }

    private static boolean validRequestId(String value) {
        return value != null && !value.isBlank() && value.length() <= 128;
    }

    private static String bounded(String value, int max, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static String normalize(String value) {
        String decomposed = java.text.Normalizer.normalize(
                value, java.text.Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
        return decomposed.replaceAll("\\p{M}+", "");
    }

    private static boolean containsWord(String value, String token) {
        return (" " + value.replaceAll("[^a-z0-9]+", " ") + " ")
                .contains(" " + token + " ");
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(Locale.ROOT, "%02x", value));
        return output.toString();
    }

    private static final Set<String> FINANCIAL_PACKAGE_TOKENS = new HashSet<>(Arrays.asList(
            "bank", "banco", "banca", "bancolombia", "nequi", "daviplata", "transfiya",
            "paypal", "venmo", "cashapp", "cash.app", "wallet", "revolut", "wise",
            "wellsfargo", "bankofamerica", "chase.sig", "capitalone", "citibank", "coinbase",
            "binance", "robinhood", "etrade", "fidelity", "pnc", "navyfederal", "ally",
            "discover", "chime", "sofi", "amex", "schwab", "vanguard", "zelle", "stripe",
            "squareup", "mercadopago", "rappipay", "lulo", "com.nu."
    ));

    private static final Set<String> FINANCIAL_LABEL_TOKENS = new HashSet<>(Arrays.asList(
            "bank", "banking", "banco", "banca", "bancolombia", "nequi", "daviplata",
            "paypal", "venmo", "cash app", "wallet", "billetera", "revolut", "wise",
            "coinbase", "binance", "robinhood", "fidelity", "pnc", "ally", "discover",
            "chime", "sofi", "american express", "amex", "charles schwab", "vanguard",
            "navy federal", "zelle", "stripe", "mercado pago", "rappi pay", "lulo", "nu"
    ));
}
