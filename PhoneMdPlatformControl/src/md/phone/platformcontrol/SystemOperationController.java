package md.phone.platformcontrol;

import android.app.PendingIntent;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.role.RoleManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.net.TetheringManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import java.io.FileInputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Fixed, checked platform operations that must not be expressed as shell commands. */
final class SystemOperationController {
    private static final String TAG = "PhoneMdSystemControl";
    private static final String LAUNCHER_PACKAGE = "md.phone.launcher";
    private static final String BROKER_PACKAGE = "md.phone.platformcontrol";
    private static final String GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms";
    private static final String GOOGLE_PLAY_STORE_PACKAGE = "com.android.vending";
    private static final String STATUS_OK = "ok";
    private static final String STATUS_INVALID = "invalid";
    private static final String STATUS_DENIED = "denied";
    private static final String STATUS_NOT_FOUND = "not_found";
    private static final String STATUS_ERROR = "error";
    private static final long PACKAGE_TIMEOUT_MS = 120_000L;
    private static final long CLEAR_DATA_TIMEOUT_MS = 60_000L;
    private static final long BOOT_START_TOLERANCE_MS = 10_000L;
    private static final long RADIO_TIMEOUT_MS = 30_000L;
    private static final long MAX_APK_BYTES = 2L * 1024 * 1024 * 1024;

    private final Context mContext;
    private final PackageManager mPackages;

    SystemOperationController(Context context) {
        mContext = context;
        mPackages = context.getPackageManager();
    }

    Bundle execute(Bundle request, int protocolVersion) {
        final long started = SystemClock.elapsedRealtime();
        if (request == null || request.getInt("protocol_version", -1) != protocolVersion) {
            return timed(result(null, null, STATUS_INVALID, "Protocol version mismatch."), started);
        }
        final String requestId = request.getString("request_id");
        final String operation = lower(request.getString("operation"));
        if (TextUtils.isEmpty(requestId) || requestId.length() > 128 || operation.isEmpty()) {
            return timed(result(requestId, operation, STATUS_INVALID, "Invalid system operation request."), started);
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            Bundle outcome;
            switch (operation) {
                case "package_install": outcome = install(requestId, operation, request); break;
                case "package_uninstall": outcome = uninstall(requestId, operation, request); break;
                case "google_play_health": outcome = googlePlayHealth(requestId, operation); break;
                case "google_play_reset": outcome = googlePlayReset(requestId, operation); break;
                case "grant_runtime_permission": outcome = permission(requestId, operation, request, true); break;
                case "revoke_runtime_permission": outcome = permission(requestId, operation, request, false); break;
                case "set_default_role": outcome = setDefaultRole(requestId, operation, request); break;
                case "get_default_role": outcome = getDefaultRole(requestId, operation, request); break;
                case "lock": outcome = power(requestId, operation); break;
                case "reboot": outcome = power(requestId, operation); break;
                case "shutdown": outcome = power(requestId, operation); break;
                case "connectivity_status": outcome = connectivityStatus(requestId, operation); break;
                case "wifi_connect": outcome = wifiConnect(requestId, operation, request); break;
                case "wifi_forget": outcome = wifiForget(requestId, operation, request); break;
                case "bluetooth_pair": outcome = bluetoothBond(requestId, operation, request, true); break;
                case "bluetooth_unpair": outcome = bluetoothBond(requestId, operation, request, false); break;
                case "hotspot_on": outcome = hotspot(requestId, operation, true); break;
                case "hotspot_off": outcome = hotspot(requestId, operation, false); break;
                case "nfc_on": outcome = nfc(requestId, operation, true); break;
                case "nfc_off": outcome = nfc(requestId, operation, false); break;
                default: outcome = result(requestId, operation, STATUS_INVALID,
                        "Unknown platform operation: " + operation);
            }
            return timed(outcome, started);
        } catch (SecurityException error) {
            Slog.w(TAG, operation + " denied", error);
            return timed(result(requestId, operation, STATUS_DENIED,
                    "Android denied " + operation + ": " + safeMessage(error)), started);
        } catch (Throwable error) {
            Slog.e(TAG, operation + " failed", error);
            return timed(result(requestId, operation, STATUS_ERROR,
                    operation + " failed: " + error.getClass().getSimpleName()), started);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Bundle install(String requestId, String operation, Bundle request) throws Exception {
        ParcelFileDescriptor descriptor = request.getParcelable("apk_fd", ParcelFileDescriptor.class);
        long size = request.getLong("apk_size", -1);
        String expectedHash = lower(request.getString("apk_sha256"));
        if (descriptor == null || size <= 0 || size > MAX_APK_BYTES || expectedHash.length() != 64) {
            return result(requestId, operation, STATUS_INVALID, "Install needs a checked APK descriptor, size, and SHA-256.");
        }
        PackageInstaller installer = mPackages.getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(size);
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = null;
        try {
            session = installer.openSession(sessionId);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
                 OutputStream output = session.openWrite("base.apk", 0, size)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    copied += read;
                    if (copied > size || copied > MAX_APK_BYTES) {
                        throw new IllegalArgumentException("APK exceeded its declared size.");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                session.fsync(output);
            }
            if (copied != size || !expectedHash.equals(hex(digest.digest()))) {
                session.abandon();
                return result(requestId, operation, STATUS_INVALID, "APK size or SHA-256 changed before install.");
            }
            InstallOutcome outcome = commitSession(session, "install");
            Bundle answer = result(requestId, operation,
                    outcome.success ? STATUS_OK : STATUS_ERROR,
                    outcome.success ? "Installed " + outcome.packageName + "." : outcome.message);
            answer.putString("target", outcome.packageName);
            answer.putString("after", outcome.success ? "installed" : "not_installed");
            return answer;
        } finally {
            descriptor.close();
            if (session != null) session.close();
        }
    }

    private Bundle uninstall(String requestId, String operation, Bundle request) throws Exception {
        String packageName = checkedPackage(request.getString("package"));
        if (packageName == null) {
            return result(requestId, operation, STATUS_INVALID, "Uninstall needs an installed package name.");
        }
        if (LAUNCHER_PACKAGE.equals(packageName) || BROKER_PACKAGE.equals(packageName)) {
            return result(requestId, operation, STATUS_DENIED, "The phone agent cannot remove itself or its broker.");
        }
        if (!isInstalled(packageName)) {
            return result(requestId, operation, STATUS_NOT_FOUND, packageName + " is not installed.");
        }
        InstallOutcome outcome = commitUninstall(packageName);
        boolean absent = !isInstalled(packageName);
        boolean success = outcome.success && absent;
        Bundle answer = result(requestId, operation, success ? STATUS_OK : STATUS_ERROR,
                success ? "Uninstalled " + packageName + "." : outcome.message);
        answer.putString("target", packageName);
        answer.putString("before", "installed");
        answer.putString("after", absent ? "absent" : "installed");
        return answer;
    }

    private Bundle googlePlayHealth(String requestId, String operation) throws Exception {
        if (!isInstalled(GOOGLE_PLAY_SERVICES_PACKAGE)
                || !isInstalled(GOOGLE_PLAY_STORE_PACKAGE)) {
            return result(requestId, operation, STATUS_NOT_FOUND,
                    "Google Play services and Google Play Store are not both installed.");
        }
        if (!isOrdinaryApp(GOOGLE_PLAY_SERVICES_PACKAGE)
                || !isOrdinaryApp(GOOGLE_PLAY_STORE_PACKAGE)) {
            return result(requestId, operation, STATUS_DENIED,
                    "The installed Google packages are not both ordinary sandboxed apps.");
        }

        ActivityManager activity = mContext.getSystemService(ActivityManager.class);
        int servicesCrashes = currentBootCrashes(activity, GOOGLE_PLAY_SERVICES_PACKAGE);
        int storeCrashes = currentBootCrashes(activity, GOOGLE_PLAY_STORE_PACKAGE);
        boolean healthy = servicesCrashes == 0 && storeCrashes == 0;
        Bundle values = new Bundle();
        values.putBoolean("healthy", healthy);
        values.putInt("play_services_crashes_this_boot", servicesCrashes);
        values.putInt("play_store_crashes_this_boot", storeCrashes);
        Bundle answer = result(requestId, operation, STATUS_OK,
                healthy ? "Google Play has no current-boot crash."
                        : "A Google Play process crashed during this boot.");
        answer.putString("target", "sandboxed_google_play");
        answer.putString("after", healthy ? "healthy" : "crashed_this_boot");
        answer.putBundle("values", values);
        return answer;
    }

    private Bundle googlePlayReset(String requestId, String operation) throws Exception {
        if (!isInstalled(GOOGLE_PLAY_SERVICES_PACKAGE)
                || !isInstalled(GOOGLE_PLAY_STORE_PACKAGE)) {
            return result(requestId, operation, STATUS_NOT_FOUND,
                    "Google Play services and Google Play Store are not both installed.");
        }
        if (!isOrdinaryApp(GOOGLE_PLAY_SERVICES_PACKAGE)
                || !isOrdinaryApp(GOOGLE_PLAY_STORE_PACKAGE)) {
            return result(requestId, operation, STATUS_DENIED,
                    "The installed Google packages are not both ordinary sandboxed apps.");
        }

        ClearDataOutcome store = clearUserData(GOOGLE_PLAY_STORE_PACKAGE);
        ClearDataOutcome services = clearUserData(GOOGLE_PLAY_SERVICES_PACKAGE);
        boolean cleared = store.success && services.success;
        Bundle values = new Bundle();
        values.putBoolean("play_store_cleared", store.success);
        values.putBoolean("play_services_cleared", services.success);
        Bundle answer = result(requestId, operation, cleared ? STATUS_OK : STATUS_ERROR,
                cleared
                        ? "Reset sandboxed Google Play. Google accounts must sign in again."
                        : "Google Play reset did not clear both app states.");
        answer.putString("target", "sandboxed_google_play");
        answer.putString("before", "installed_state");
        answer.putString("after", cleared ? "signed_out_clean_state" : "partially_cleared");
        answer.putBundle("values", values);
        return answer;
    }

    private Bundle permission(String requestId, String operation, Bundle request, boolean grant)
            throws Exception {
        String packageName = checkedPackage(request.getString("package"));
        String permission = request.getString("permission");
        if (packageName == null || TextUtils.isEmpty(permission) || !isInstalled(packageName)) {
            return result(requestId, operation, STATUS_INVALID, "Permission change needs an installed package and exact permission.");
        }
        PackageInfo packageInfo = mPackages.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
        if (packageInfo.requestedPermissions == null
                || !Arrays.asList(packageInfo.requestedPermissions).contains(permission)) {
            return result(requestId, operation, STATUS_INVALID, packageName + " did not request " + permission + ".");
        }
        PermissionInfo permissionInfo = mPackages.getPermissionInfo(permission, 0);
        int baseProtection = permissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
        if (baseProtection != PermissionInfo.PROTECTION_DANGEROUS) {
            return result(requestId, operation, STATUS_DENIED, "Only user runtime permissions can be changed.");
        }
        boolean before = mPackages.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED;
        if (grant) {
            mPackages.grantRuntimePermission(packageName, permission, UserHandle.SYSTEM);
        } else {
            mPackages.revokeRuntimePermission(packageName, permission, UserHandle.SYSTEM);
        }
        boolean after = mPackages.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED;
        boolean expected = grant;
        Bundle answer = result(requestId, operation, after == expected ? STATUS_OK : STATUS_ERROR,
                after == expected ? (grant ? "Granted " : "Revoked ") + permission + "."
                        : "Android did not apply the permission change.");
        answer.putString("target", packageName + ":" + permission);
        answer.putString("before", Boolean.toString(before));
        answer.putString("after", Boolean.toString(after));
        return answer;
    }

    private Bundle setDefaultRole(String requestId, String operation, Bundle request)
            throws Exception {
        String role = request.getString("role");
        String packageName = checkedPackage(request.getString("package"));
        List<String> supported = Arrays.asList(
                RoleManager.ROLE_BROWSER, RoleManager.ROLE_SMS, RoleManager.ROLE_ASSISTANT);
        if (!supported.contains(role) || packageName == null || !isInstalled(packageName)) {
            return result(requestId, operation, STATUS_INVALID, "Unsupported role or package.");
        }
        RoleManager roles = mContext.getSystemService(RoleManager.class);
        if (!roles.isRoleAvailable(role)) {
            return result(requestId, operation, STATUS_NOT_FOUND, "That role is unavailable on this phone.");
        }
        List<String> before = roles.getRoleHoldersAsUser(role, UserHandle.SYSTEM);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(false);
        roles.addRoleHolderAsUser(role, packageName, 0, UserHandle.SYSTEM,
                mContext.getMainExecutor(), value -> {
                    accepted.set(Boolean.TRUE.equals(value));
                    finished.countDown();
                });
        if (!finished.await(30, TimeUnit.SECONDS)) {
            return result(requestId, operation, STATUS_ERROR, "Timed out changing the default app.");
        }
        List<String> after = roles.getRoleHoldersAsUser(role, UserHandle.SYSTEM);
        boolean applied = accepted.get() && after.contains(packageName);
        Bundle answer = result(requestId, operation, applied ? STATUS_OK : STATUS_ERROR,
                applied ? "Changed the default app." : "Android did not change the default app.");
        answer.putString("target", role);
        answer.putString("before", String.join(",", before));
        answer.putString("after", String.join(",", after));
        return answer;
    }

    private Bundle getDefaultRole(String requestId, String operation, Bundle request) {
        String role = request.getString("role");
        List<String> supported = Arrays.asList(
                RoleManager.ROLE_BROWSER, RoleManager.ROLE_SMS, RoleManager.ROLE_ASSISTANT);
        if (!supported.contains(role)) {
            return result(requestId, operation, STATUS_INVALID, "Unsupported default-app role.");
        }
        RoleManager roles = mContext.getSystemService(RoleManager.class);
        if (!roles.isRoleAvailable(role)) {
            return result(requestId, operation, STATUS_NOT_FOUND, "That role is unavailable on this phone.");
        }
        List<String> holders = roles.getRoleHoldersAsUser(role, UserHandle.SYSTEM);
        Bundle values = new Bundle();
        values.putString("packages", String.join("\n", holders));
        Bundle answer = result(requestId, operation, STATUS_OK, "Read the default app role.");
        answer.putString("target", role);
        answer.putString("after", String.join(",", holders));
        answer.putBundle("values", values);
        return answer;
    }

    private Bundle power(String requestId, String operation) {
        PowerManager power = mContext.getSystemService(PowerManager.class);
        Bundle answer = result(requestId, operation, STATUS_OK,
                operation.substring(0, 1).toUpperCase(Locale.ROOT) + operation.substring(1) + " requested.");
        answer.putString("target", "device");
        answer.putString("after", operation + "_requested");
        if ("lock".equals(operation)) {
            power.goToSleep(SystemClock.uptimeMillis());
        } else if ("reboot".equals(operation)) {
            power.reboot(null);
        } else {
            power.shutdown(false, "phone.md user request", false);
        }
        return answer;
    }

    private Bundle connectivityStatus(String requestId, String operation) {
        WifiManager wifi = mContext.getSystemService(WifiManager.class);
        BluetoothAdapter bluetooth = mContext.getSystemService(BluetoothManager.class).getAdapter();
        NfcAdapter nfc = NfcAdapter.getDefaultAdapter(mContext);
        Bundle values = new Bundle();
        values.putBoolean("hotspot_enabled", wifi.isWifiApEnabled());
        values.putBoolean("nfc_available", nfc != null);
        values.putBoolean("nfc_enabled", nfc != null && nfc.isEnabled());
        values.putBoolean("bluetooth_available", bluetooth != null);
        Bundle answer = result(requestId, operation, STATUS_OK, "Read platform connectivity state.");
        answer.putString("target", "connectivity");
        answer.putBundle("values", values);
        return answer;
    }

    private Bundle wifiConnect(String requestId, String operation, Bundle request) {
        String ssid = cleanSsid(request.getString("ssid"));
        String password = request.getString("password");
        if (ssid == null) return result(requestId, operation, STATUS_INVALID, "Wi-Fi connect needs an SSID.");
        WifiManager wifi = mContext.getSystemService(WifiManager.class);
        WifiConfiguration existing = configuredNetwork(wifi, ssid);
        int networkId;
        if (existing != null && TextUtils.isEmpty(password)) {
            networkId = existing.networkId;
        } else {
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = quote(ssid);
            if (TextUtils.isEmpty(password)) {
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else {
                if (password.length() < 8 || password.length() > 63) {
                    return result(requestId, operation, STATUS_INVALID, "A WPA password needs 8 through 63 characters.");
                }
                config.preSharedKey = quote(password);
            }
            networkId = wifi.addNetwork(config);
        }
        if (networkId < 0 || !wifi.enableNetwork(networkId, true)) {
            return result(requestId, operation, STATUS_ERROR, "Android rejected the Wi-Fi connection.");
        }
        wifi.reconnect();
        boolean connected = waitForWifi(wifi, ssid);
        Bundle answer = result(requestId, operation, connected ? STATUS_OK : STATUS_ERROR,
                connected ? "Connected to " + ssid + "." : "The phone did not connect to " + ssid + ".");
        answer.putString("target", ssid);
        answer.putString("after", connected ? "connected" : "not_connected");
        return answer;
    }

    private Bundle wifiForget(String requestId, String operation, Bundle request) {
        String ssid = cleanSsid(request.getString("ssid"));
        if (ssid == null) return result(requestId, operation, STATUS_INVALID, "Wi-Fi forget needs an SSID.");
        WifiManager wifi = mContext.getSystemService(WifiManager.class);
        WifiConfiguration config = configuredNetwork(wifi, ssid);
        if (config == null) return result(requestId, operation, STATUS_NOT_FOUND, ssid + " is not saved.");
        boolean removed = wifi.removeNetwork(config.networkId);
        wifi.saveConfiguration();
        boolean absent = configuredNetwork(wifi, ssid) == null;
        Bundle answer = result(requestId, operation, removed && absent ? STATUS_OK : STATUS_ERROR,
                removed && absent ? "Forgot " + ssid + "." : "Android did not forget " + ssid + ".");
        answer.putString("target", ssid);
        answer.putString("before", "saved");
        answer.putString("after", absent ? "absent" : "saved");
        return answer;
    }

    private Bundle bluetoothBond(String requestId, String operation, Bundle request, boolean pair) {
        BluetoothAdapter adapter = mContext.getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null) return result(requestId, operation, STATUS_NOT_FOUND, "Bluetooth is unavailable.");
        String query = request.getString("device");
        BluetoothDevice device = findBluetoothDevice(adapter, query);
        if (device == null) return result(requestId, operation, STATUS_NOT_FOUND,
                "Use the exact Bluetooth address returned by scan.");
        int before = device.getBondState();
        boolean accepted = pair ? device.createBond() : device.removeBond();
        int expected = pair ? BluetoothDevice.BOND_BONDED : BluetoothDevice.BOND_NONE;
        boolean applied = accepted && waitForBond(device, expected);
        Bundle answer = result(requestId, operation, applied ? STATUS_OK : STATUS_ERROR,
                applied ? (pair ? "Paired " : "Unpaired ") + safeDeviceName(device) + "."
                        : "Android did not complete the Bluetooth change.");
        answer.putString("target", device.getAddress());
        answer.putString("before", bondName(before));
        answer.putString("after", bondName(device.getBondState()));
        return answer;
    }

    private Bundle hotspot(String requestId, String operation, boolean enabled) throws Exception {
        TetheringManager tethering = mContext.getSystemService(TetheringManager.class);
        if (!enabled) {
            tethering.stopTethering(TetheringManager.TETHERING_WIFI);
            SystemClock.sleep(500);
            Bundle answer = result(requestId, operation, STATUS_OK, "Wi-Fi hotspot turned off.");
            answer.putString("target", "wifi_hotspot");
            answer.putString("after", "off");
            return answer;
        }
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger errorCode = new AtomicInteger(-1);
        AtomicBoolean started = new AtomicBoolean(false);
        TetheringManager.TetheringRequest tetheringRequest =
                new TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).build();
        tethering.startTethering(tetheringRequest, mContext.getMainExecutor(),
                new TetheringManager.StartTetheringCallback() {
                    @Override public void onTetheringStarted() {
                        started.set(true);
                        finished.countDown();
                    }
                    @Override public void onTetheringFailed(int error) {
                        errorCode.set(error);
                        finished.countDown();
                    }
                });
        boolean returned = finished.await(30, TimeUnit.SECONDS);
        Bundle answer = result(requestId, operation, returned && started.get() ? STATUS_OK : STATUS_ERROR,
                returned && started.get() ? "Wi-Fi hotspot turned on."
                        : "Hotspot start failed with code " + errorCode.get() + ".");
        answer.putString("target", "wifi_hotspot");
        answer.putString("after", returned && started.get() ? "on" : "off");
        return answer;
    }

    private Bundle nfc(String requestId, String operation, boolean enabled) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        if (adapter == null) return result(requestId, operation, STATUS_NOT_FOUND, "NFC is unavailable.");
        boolean before = adapter.isEnabled();
        boolean accepted = enabled ? adapter.enable() : adapter.disable();
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (adapter.isEnabled() != enabled && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100);
        }
        boolean after = adapter.isEnabled();
        Bundle answer = result(requestId, operation, accepted && after == enabled ? STATUS_OK : STATUS_ERROR,
                accepted && after == enabled ? "NFC turned " + (enabled ? "on." : "off.")
                        : "Android did not change NFC.");
        answer.putString("target", "nfc");
        answer.putString("before", Boolean.toString(before));
        answer.putString("after", Boolean.toString(after));
        return answer;
    }

    private InstallOutcome commitSession(PackageInstaller.Session session, String suffix)
            throws Exception {
        String action = BROKER_PACKAGE + ".PACKAGE_RESULT." + suffix + "." + UUID.randomUUID();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<InstallOutcome> outcome = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                outcome.set(new InstallOutcome(status == PackageInstaller.STATUS_SUCCESS,
                        packageName == null ? "" : packageName,
                        message == null ? "Package operation failed." : message));
                finished.countDown();
            }
        };
        mContext.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        PendingIntent pending = PendingIntent.getBroadcast(mContext, action.hashCode(),
                new Intent(action).setPackage(BROKER_PACKAGE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        try {
            session.commit(pending.getIntentSender());
            if (!finished.await(PACKAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return new InstallOutcome(false, "", "Package installation timed out.");
            }
            return outcome.get();
        } finally {
            mContext.unregisterReceiver(receiver);
        }
    }

    private InstallOutcome commitUninstall(String packageName) throws Exception {
        String action = BROKER_PACKAGE + ".PACKAGE_RESULT.uninstall." + UUID.randomUUID();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<InstallOutcome> outcome = new AtomicReference<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                outcome.set(new InstallOutcome(status == PackageInstaller.STATUS_SUCCESS,
                        packageName, message == null ? "Package removal failed." : message));
                finished.countDown();
            }
        };
        mContext.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        PendingIntent pending = PendingIntent.getBroadcast(mContext, action.hashCode(),
                new Intent(action).setPackage(BROKER_PACKAGE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        try {
            mPackages.getPackageInstaller().uninstall(packageName, pending.getIntentSender());
            if (!finished.await(PACKAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return new InstallOutcome(false, packageName, "Package removal timed out.");
            }
            return outcome.get();
        } finally {
            mContext.unregisterReceiver(receiver);
        }
    }

    private boolean isInstalled(String packageName) {
        try {
            mPackages.getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private boolean isOrdinaryApp(String packageName) throws PackageManager.NameNotFoundException {
        ApplicationInfo info = mPackages.getApplicationInfo(packageName, 0);
        return (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
    }

    private int currentBootCrashes(ActivityManager activity, String packageName) {
        long bootStartedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                - BOOT_START_TOLERANCE_MS;
        int crashes = 0;
        List<ApplicationExitInfo> exits =
                activity.getHistoricalProcessExitReasons(packageName, 0, 32);
        for (ApplicationExitInfo exit : exits) {
            if (exit.getTimestamp() < bootStartedAt) continue;
            int reason = exit.getReason();
            if (reason == ApplicationExitInfo.REASON_CRASH
                    || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                    || reason == ApplicationExitInfo.REASON_ANR) {
                crashes++;
            }
        }
        return crashes;
    }

    private ClearDataOutcome clearUserData(String packageName) throws InterruptedException {
        ActivityManager activity = mContext.getSystemService(ActivityManager.class);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean callbackSuccess = new AtomicBoolean(false);
        IPackageDataObserver observer = new IPackageDataObserver.Stub() {
            @Override public void onRemoveCompleted(String observedPackage, boolean succeeded) {
                callbackSuccess.set(packageName.equals(observedPackage) && succeeded);
                finished.countDown();
            }
        };
        boolean started = activity.clearApplicationUserData(packageName, observer);
        boolean returned = started && finished.await(CLEAR_DATA_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return new ClearDataOutcome(returned && callbackSuccess.get());
    }

    private String checkedPackage(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) return null;
        return value;
    }

    private WifiConfiguration configuredNetwork(WifiManager wifi, String ssid) {
        List<WifiConfiguration> configured = wifi.getConfiguredNetworks();
        if (configured == null) return null;
        for (WifiConfiguration candidate : configured) {
            if (ssid.equals(cleanSsid(candidate.SSID))) return candidate;
        }
        return null;
    }

    private boolean waitForWifi(WifiManager wifi, String ssid) {
        long deadline = SystemClock.elapsedRealtime() + RADIO_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            WifiInfo info = wifi.getConnectionInfo();
            if (info != null && ssid.equals(cleanSsid(info.getSSID()))
                    && info.getNetworkId() != -1) return true;
            SystemClock.sleep(250);
        }
        return false;
    }

    private BluetoothDevice findBluetoothDevice(BluetoothAdapter adapter, String query) {
        if (TextUtils.isEmpty(query)) return null;
        try {
            if (BluetoothAdapter.checkBluetoothAddress(query)) return adapter.getRemoteDevice(query);
        } catch (IllegalArgumentException ignored) {
        }
        List<BluetoothDevice> matches = new ArrayList<>();
        for (BluetoothDevice candidate : adapter.getBondedDevices()) {
            if (query.equalsIgnoreCase(candidate.getName())) matches.add(candidate);
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private boolean waitForBond(BluetoothDevice device, int expected) {
        long deadline = SystemClock.elapsedRealtime() + RADIO_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (device.getBondState() == expected) return true;
            SystemClock.sleep(250);
        }
        return false;
    }

    private static String cleanSsid(String value) {
        if (value == null) return null;
        String clean = value.trim();
        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return clean.isEmpty() || clean.length() > 32 ? null : clean;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String safeDeviceName(BluetoothDevice device) {
        String name = device.getName();
        return TextUtils.isEmpty(name) ? device.getAddress() : name;
    }

    private static String bondName(int state) {
        if (state == BluetoothDevice.BOND_BONDED) return "paired";
        if (state == BluetoothDevice.BOND_BONDING) return "pairing";
        return "not_paired";
    }

    private static Bundle result(String requestId, String operation, String status, String detail) {
        Bundle result = new Bundle();
        result.putString("request_id", requestId);
        result.putString("operation", operation);
        result.putString("status", status);
        result.putString("detail", detail);
        return result;
    }

    private static Bundle timed(Bundle result, long started) {
        result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started);
        return result;
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message;
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(Locale.ROOT, "%02x", value));
        return output.toString();
    }

    private static final class InstallOutcome {
        final boolean success;
        final String packageName;
        final String message;

        InstallOutcome(boolean success, String packageName, String message) {
            this.success = success;
            this.packageName = packageName;
            this.message = message;
        }
    }

    private static final class ClearDataOutcome {
        final boolean success;

        ClearDataOutcome(boolean success) {
            this.success = success;
        }
    }
}
