package md.phone.platformcontrol;

import android.app.PendingIntent;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.role.RoleManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.ext.PackageId;
import android.net.TetheringManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.euicc.EuiccManager;
import android.util.Slog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
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
    private static final int MAX_OPERATION_CHARS = 64;
    private static final int MAX_PACKAGE_CHARS = 255;
    private static final int MAX_PERMISSION_CHARS = 255;
    private static final int MAX_VALUE_CHARS = 1_024;
    private static final int MAX_DETAIL_CHARS = 500;
    private static final int MAX_MODEMS = 8;
    private static final int MAX_SUBSCRIPTIONS = 16;
    private static final int MAX_CLIPBOARD_TEXT_CHARS = 128 * 1024;
    private static final Object PACKAGE_INSTALL_LOCK = new Object();

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
        final String rawOperation = request.getString("operation");
        final String operation = rawOperation != null
                && rawOperation.length() <= MAX_OPERATION_CHARS ? lower(rawOperation) : "";
        if (TextUtils.isEmpty(requestId) || requestId.length() > 128 || operation.isEmpty()) {
            return timed(result(requestId, operation, STATUS_INVALID, "Invalid system operation request."), started);
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            Bundle outcome;
            switch (operation) {
                case "package_install": outcome = install(requestId, operation, request); break;
                case "package_uninstall": outcome = uninstall(requestId, operation, request); break;
                case "clipboard_read": outcome = clipboardRead(requestId, operation); break;
                case "clipboard_write": outcome = clipboardWrite(requestId, operation, request); break;
                case "clipboard_clear": outcome = clipboardClear(requestId, operation); break;
                case "google_play_health": outcome = googlePlayHealth(requestId, operation); break;
                case "google_play_reset": outcome = googlePlayReset(requestId, operation); break;
                case "grant_runtime_permission": outcome = permission(requestId, operation, request, true); break;
                case "revoke_runtime_permission": outcome = permission(requestId, operation, request, false); break;
                case "set_default_role": outcome = setDefaultRole(requestId, operation, request); break;
                case "get_default_role": outcome = getDefaultRole(requestId, operation, request); break;
                case "telephony_snapshot": outcome = telephonySnapshot(requestId, operation); break;
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

    /**
     * One read-only platform snapshot for facts that ordinary app sandboxes
     * cannot see. Values are namespaced so HOME can split telephony state from
     * persistent identifiers without adding another privileged API.
     */
    private Bundle telephonySnapshot(String requestId, String operation) {
        TelephonyManager phones = mContext.getSystemService(TelephonyManager.class);
        SubscriptionManager subscriptions = mContext.getSystemService(SubscriptionManager.class);
        Bundle values = new Bundle();

        int modemCount = Math.min(
                MAX_MODEMS,
                Math.max(0, Math.max(phones.getActiveModemCount(), phones.getPhoneCount())));
        values.putInt("telephony.active_modem_count", phones.getActiveModemCount());
        values.putInt("telephony.phone_count", phones.getPhoneCount());
        values.putInt("telephony.default_subscription_id",
                SubscriptionManager.getDefaultSubscriptionId());
        values.putInt("telephony.default_voice_subscription_id",
                SubscriptionManager.getDefaultVoiceSubscriptionId());
        values.putInt("telephony.default_sms_subscription_id",
                SubscriptionManager.getDefaultSmsSubscriptionId());
        values.putInt("telephony.default_data_subscription_id",
                SubscriptionManager.getDefaultDataSubscriptionId());

        putString(values, "identifiers.device_serial", readString(Build::getSerial));
        for (int slot = 0; slot < modemCount; slot++) {
            final int currentSlot = slot;
            String slotKey = "slot_" + slot;
            values.putString("telephony." + slotKey + ".sim_state",
                    simStateName(phones.getSimState(slot)));
            putString(values, "identifiers." + slotKey + ".imei",
                    readString(() -> phones.getImei(currentSlot)));
            putString(values, "identifiers." + slotKey + ".meid",
                    readString(() -> phones.getMeid(currentSlot)));
        }

        List<SubscriptionInfo> active = subscriptions.getActiveSubscriptionInfoList();
        if (active == null) active = new ArrayList<>();
        values.putInt("telephony.active_subscription_count", active.size());
        for (int index = 0; index < Math.min(active.size(), MAX_SUBSCRIPTIONS); index++) {
            SubscriptionInfo info = active.get(index);
            int subId = info.getSubscriptionId();
            TelephonyManager phone = phones.createForSubscriptionId(subId);
            String key = "subscription_" + index;
            String telephonyPrefix = "telephony." + key + ".";
            String identifierPrefix = "identifiers." + key + ".";

            values.putInt(telephonyPrefix + "subscription_id", subId);
            values.putInt(telephonyPrefix + "sim_slot_index", info.getSimSlotIndex());
            values.putInt(telephonyPrefix + "carrier_id", info.getCarrierId());
            putString(values, telephonyPrefix + "display_name",
                    info.getDisplayName() == null ? "" : info.getDisplayName().toString());
            putString(values, telephonyPrefix + "carrier_name",
                    info.getCarrierName() == null ? "" : info.getCarrierName().toString());
            putString(values, telephonyPrefix + "country_iso", info.getCountryIso());
            putString(values, telephonyPrefix + "mcc", info.getMccString());
            putString(values, telephonyPrefix + "mnc", info.getMncString());
            values.putBoolean(telephonyPrefix + "embedded_esim", info.isEmbedded());
            values.putBoolean(telephonyPrefix + "opportunistic", info.isOpportunistic());
            values.putBoolean(telephonyPrefix + "network_roaming", phone.isNetworkRoaming());
            putString(values, telephonyPrefix + "network_operator_name",
                    phone.getNetworkOperatorName());
            putString(values, telephonyPrefix + "network_operator", phone.getNetworkOperator());
            putString(values, telephonyPrefix + "network_country_iso",
                    phone.getNetworkCountryIso());
            putString(values, telephonyPrefix + "sim_operator_name", phone.getSimOperatorName());
            putString(values, telephonyPrefix + "sim_operator", phone.getSimOperator());
            putString(values, telephonyPrefix + "sim_country_iso", phone.getSimCountryIso());
            putString(values, telephonyPrefix + "data_network_type",
                    TelephonyManager.getNetworkTypeName(phone.getDataNetworkType()));
            putString(values, telephonyPrefix + "voice_network_type",
                    TelephonyManager.getNetworkTypeName(phone.getVoiceNetworkType()));
            putString(values, telephonyPrefix + "data_state", dataStateName(phone.getDataState()));
            putString(values, telephonyPrefix + "call_state", callStateName(phone.getCallState()));
            ServiceState service = phone.getServiceState();
            if (service != null) {
                putString(values, telephonyPrefix + "service_state",
                        serviceStateName(service.getState()));
                values.putBoolean(telephonyPrefix + "service_roaming", service.getRoaming());
                putString(values, telephonyPrefix + "operator_alpha_long",
                        service.getOperatorAlphaLong());
                putString(values, telephonyPrefix + "operator_alpha_short",
                        service.getOperatorAlphaShort());
                putString(values, telephonyPrefix + "operator_numeric",
                        service.getOperatorNumeric());
            }

            putString(values, telephonyPrefix + "phone_number",
                    readString(() -> subscriptions.getPhoneNumber(subId)));
            putString(values, telephonyPrefix + "phone_number_carrier",
                    readString(() -> subscriptions.getPhoneNumber(
                            subId, SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER)));
            putString(values, telephonyPrefix + "phone_number_uicc",
                    readString(() -> subscriptions.getPhoneNumber(
                            subId, SubscriptionManager.PHONE_NUMBER_SOURCE_UICC)));
            putString(values, telephonyPrefix + "phone_number_ims",
                    readString(() -> subscriptions.getPhoneNumber(
                            subId, SubscriptionManager.PHONE_NUMBER_SOURCE_IMS)));
            putString(values, telephonyPrefix + "phone_number_source_priority",
                    "carrier>uicc>ims");

            putString(values, identifierPrefix + "iccid", info.getIccId());
            putString(values, identifierPrefix + "group_uuid",
                    info.getGroupUuid() == null ? "" : info.getGroupUuid().toString());
            putString(values, identifierPrefix + "imsi",
                    readString(phone::getSubscriberId));
            putString(values, identifierPrefix + "sim_serial_number",
                    readString(phone::getSimSerialNumber));
        }

        EuiccManager euicc = mContext.getSystemService(EuiccManager.class);
        if (euicc != null) {
            values.putBoolean("telephony.esim_enabled", euicc.isEnabled());
            putString(values, "identifiers.esim_eid", readString(euicc::getEid));
        }

        Bundle answer = result(requestId, operation, STATUS_OK,
                "Read protected telephony and subscription state.");
        answer.putString("target", "phone_system");
        answer.putBundle("values", values);
        return answer;
    }

    private static String readString(StringReader reader) {
        try {
            String value = reader.read();
            return bounded(value, MAX_VALUE_CHARS);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void putString(Bundle values, String key, String value) {
        values.putString(key, bounded(value, MAX_VALUE_CHARS));
    }

    private static String simStateName(int state) {
        switch (state) {
            case TelephonyManager.SIM_STATE_ABSENT: return "absent";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED: return "pin_required";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED: return "puk_required";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED: return "network_locked";
            case TelephonyManager.SIM_STATE_READY: return "ready";
            case TelephonyManager.SIM_STATE_NOT_READY: return "not_ready";
            case TelephonyManager.SIM_STATE_PERM_DISABLED: return "permanently_disabled";
            case TelephonyManager.SIM_STATE_CARD_IO_ERROR: return "card_io_error";
            case TelephonyManager.SIM_STATE_CARD_RESTRICTED: return "card_restricted";
            case TelephonyManager.SIM_STATE_LOADED: return "loaded";
            case TelephonyManager.SIM_STATE_PRESENT: return "present";
            default: return "unknown";
        }
    }

    private static String dataStateName(int state) {
        switch (state) {
            case TelephonyManager.DATA_DISCONNECTED: return "disconnected";
            case TelephonyManager.DATA_CONNECTING: return "connecting";
            case TelephonyManager.DATA_CONNECTED: return "connected";
            case TelephonyManager.DATA_SUSPENDED: return "suspended";
            case TelephonyManager.DATA_DISCONNECTING: return "disconnecting";
            case TelephonyManager.DATA_HANDOVER_IN_PROGRESS: return "handover";
            default: return "unknown";
        }
    }

    private static String callStateName(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE: return "idle";
            case TelephonyManager.CALL_STATE_RINGING: return "ringing";
            case TelephonyManager.CALL_STATE_OFFHOOK: return "off_hook";
            default: return "unknown";
        }
    }

    private static String serviceStateName(int state) {
        switch (state) {
            case ServiceState.STATE_IN_SERVICE: return "in_service";
            case ServiceState.STATE_OUT_OF_SERVICE: return "out_of_service";
            case ServiceState.STATE_EMERGENCY_ONLY: return "emergency_only";
            case ServiceState.STATE_POWER_OFF: return "power_off";
            default: return "unknown";
        }
    }

    @FunctionalInterface
    private interface StringReader {
        String read();
    }

    private Bundle install(String requestId, String operation, Bundle request) throws Exception {
        synchronized (PACKAGE_INSTALL_LOCK) {
            return installLocked(requestId, operation, request);
        }
    }

    private Bundle installLocked(String requestId, String operation, Bundle request)
            throws Exception {
        ParcelFileDescriptor descriptor = request.getParcelable("apk_fd", ParcelFileDescriptor.class);
        long size = request.getLong("apk_size", -1);
        String expectedHash = lower(request.getString("apk_sha256"));
        if (descriptor == null || size <= 0 || size > MAX_APK_BYTES
                || !expectedHash.matches("[0-9a-f]{64}")) {
            if (descriptor != null) descriptor.close();
            return result(requestId, operation, STATUS_INVALID, "Install needs a checked APK descriptor, size, and SHA-256.");
        }
        PackageInstaller installer = mPackages.getPackageInstaller();
        PackageInstaller.Session session = null;
        File checkedApk = null;
        int sessionId = -1;
        AtomicBoolean submitted = new AtomicBoolean(false);
        try {
            pruneCheckedApks(mContext.getCacheDir());
            checkedApk = File.createTempFile(
                    "phone-md-install-", ".apk", mContext.getCacheDir());
            MessageDigest receivedDigest = MessageDigest.getInstance("SHA-256");
            long received = 0;
            try (FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                         ParcelFileDescriptor.dup(descriptor.getFileDescriptor()));
                 FileOutputStream output = new FileOutputStream(checkedApk)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    received += read;
                    if (received > size || received > MAX_APK_BYTES) {
                        throw new IllegalArgumentException("APK exceeded its declared size.");
                    }
                    receivedDigest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            if (received != size || !expectedHash.equals(hex(receivedDigest.digest()))) {
                return result(requestId, operation, STATUS_INVALID,
                        "APK size or SHA-256 changed before validation.");
            }

            PackageInfo archive = mPackages.getPackageArchiveInfo(
                    checkedApk.getAbsolutePath(), 0);
            String archivePackage = archive == null ? null : checkedPackage(archive.packageName);
            if (archivePackage == null) {
                return result(requestId, operation, STATUS_INVALID,
                        "The checked file is not a readable Android package.");
            }
            if (LAUNCHER_PACKAGE.equals(archivePackage) || BROKER_PACKAGE.equals(archivePackage)) {
                return result(requestId, operation, STATUS_DENIED,
                        "The phone agent and its broker can only be updated by a checked ROM build.");
            }
            if (isInstalled(archivePackage) && !isOrdinaryApp(archivePackage)) {
                return result(requestId, operation, STATUS_DENIED,
                        "ROM system apps can only be updated by a checked ROM build.");
            }
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setSize(size);
            params.setAppPackageName(archivePackage);
            params.setInstallReason(PackageManager.INSTALL_REASON_USER);
            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (FileInputStream input = new FileInputStream(checkedApk);
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
                return result(requestId, operation, STATUS_INVALID, "APK size or SHA-256 changed before install.");
            }
            InstallOutcome outcome = commitSession(session, "install", submitted);
            boolean installed = outcome.success
                    && archivePackage.equals(outcome.packageName)
                    && isInstalled(archivePackage);
            Bundle answer = result(requestId, operation,
                    installed ? STATUS_OK : STATUS_ERROR,
                    installed ? "Installed " + archivePackage + "."
                            : "Android did not verify the installed package.");
            answer.putString("target", archivePackage);
            answer.putString("after", installed ? "installed" : "not_installed");
            return answer;
        } finally {
            try {
                descriptor.close();
            } finally {
                try {
                    if (!submitted.get() && sessionId >= 0) {
                        try {
                            if (session != null) {
                                session.abandon();
                            } else {
                                installer.abandonSession(sessionId);
                            }
                        } catch (RuntimeException ignored) {
                            // Cleanup must not replace the checked install
                            // result or the original copy/verification error.
                        }
                    }
                } finally {
                    if (session != null) session.close();
                    if (checkedApk != null && checkedApk.exists()
                            && !checkedApk.delete()) {
                        checkedApk.deleteOnExit();
                    }
                }
            }
        }
    }

    private static void pruneCheckedApks(File cacheDir) {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("phone-md-install-") && name.endsWith(".apk")
                    && file.isFile() && !file.delete()) {
                Slog.w(TAG, "Could not remove abandoned checked APK " + name);
            }
        }
    }

    private Bundle clipboardRead(String requestId, String operation) {
        ClipboardManager clipboard = mContext.getSystemService(ClipboardManager.class);
        ClipData clip = clipboard.getPrimaryClip();
        ClipData.Item item = clip != null && clip.getItemCount() > 0
                ? clip.getItemAt(0) : null;
        String raw = clipboardItemText(item);
        int totalCharacters = raw.length();
        boolean truncated = totalCharacters > MAX_CLIPBOARD_TEXT_CHARS;
        String value = bounded(raw, MAX_CLIPBOARD_TEXT_CHARS);

        Bundle values = new Bundle();
        values.putString("clipboard_text", value);
        values.putBoolean("empty", value.isBlank());
        values.putBoolean("truncated", truncated);
        values.putInt("total_characters", totalCharacters);
        Bundle answer = result(requestId, operation, STATUS_OK,
                value.isBlank() ? "The clipboard is empty."
                        : truncated
                                ? "Read the first " + MAX_CLIPBOARD_TEXT_CHARS
                                        + " characters of the clipboard."
                                : "Read the clipboard.");
        answer.putString("target", "clipboard");
        answer.putBundle("values", values);
        return answer;
    }

    private Bundle clipboardWrite(String requestId, String operation, Bundle request) {
        if (!request.containsKey("clipboard_text")) {
            return result(requestId, operation, STATUS_INVALID,
                    "Writing the clipboard needs text.");
        }
        String value = request.getString("clipboard_text");
        if (value == null || value.length() > MAX_CLIPBOARD_TEXT_CHARS) {
            return result(requestId, operation, STATUS_INVALID,
                    "Clipboard text exceeds its supported size.");
        }
        ClipboardManager clipboard = mContext.getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("phone.md", value));
        ClipData checkedClip = clipboard.getPrimaryClip();
        String checked = checkedClip != null && checkedClip.getItemCount() == 1
                && checkedClip.getItemAt(0).getText() != null
                        ? checkedClip.getItemAt(0).getText().toString() : null;
        boolean stored = value.equals(checked);
        Bundle answer = result(requestId, operation, stored ? STATUS_OK : STATUS_ERROR,
                stored ? "Copied the requested text."
                        : "Android did not preserve the requested clipboard text exactly.");
        answer.putString("target", "clipboard");
        answer.putString("after", stored ? value.length() + " characters" : "not_verified");
        return answer;
    }

    private Bundle clipboardClear(String requestId, String operation) {
        ClipboardManager clipboard = mContext.getSystemService(ClipboardManager.class);
        clipboard.clearPrimaryClip();
        boolean cleared = !clipboard.hasPrimaryClip();
        Bundle answer = result(requestId, operation, cleared ? STATUS_OK : STATUS_ERROR,
                cleared ? "Cleared the clipboard." : "Android did not clear the clipboard.");
        answer.putString("target", "clipboard");
        answer.putString("after", cleared ? "empty" : "not_verified");
        return answer;
    }

    private static String clipboardItemText(ClipData.Item item) {
        if (item == null) return "";
        if (item.getText() != null) return item.getText().toString();
        if (item.getUri() != null) return item.getUri().toString();
        Intent intent = item.getIntent();
        if (intent == null) return "";
        StringBuilder value = new StringBuilder();
        appendClipboardField(value, intent.getAction());
        appendClipboardField(value, intent.getDataString());
        appendClipboardField(value, intent.getComponent() == null
                ? null : intent.getComponent().flattenToShortString());
        return value.toString();
    }

    private static void appendClipboardField(StringBuilder target, String value) {
        if (TextUtils.isEmpty(value) || target.length() >= MAX_CLIPBOARD_TEXT_CHARS) return;
        if (target.length() > 0) target.append(' ');
        int remaining = MAX_CLIPBOARD_TEXT_CHARS - target.length();
        target.append(value, 0, Math.min(value.length(), remaining));
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
        if (!isOrdinaryApp(packageName)) {
            return result(requestId, operation, STATUS_DENIED,
                    "ROM system apps cannot be removed through app control.");
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
        if (!isEnabled(GOOGLE_PLAY_SERVICES_PACKAGE)
                || !isEnabled(GOOGLE_PLAY_STORE_PACKAGE)) {
            return result(requestId, operation, STATUS_ERROR,
                    "Google Play services and Google Play Store are not both enabled.");
        }

        ActivityManager activity = mContext.getSystemService(ActivityManager.class);
        int servicesCrashes = currentBootCrashes(activity, GOOGLE_PLAY_SERVICES_PACKAGE);
        int storeCrashes = currentBootCrashes(activity, GOOGLE_PLAY_STORE_PACKAGE);
        int servicesPackageId =
                mPackages.getApplicationInfo(GOOGLE_PLAY_SERVICES_PACKAGE, 0)
                        .ext().getPackageId();
        int storePackageId =
                mPackages.getApplicationInfo(GOOGLE_PLAY_STORE_PACKAGE, 0)
                        .ext().getPackageId();
        boolean compatibilityActive = servicesPackageId == PackageId.GMS_CORE
                && storePackageId == PackageId.PLAY_STORE;
        boolean healthy =
                compatibilityActive && servicesCrashes == 0 && storeCrashes == 0;
        Bundle values = new Bundle();
        values.putBoolean("healthy", healthy);
        values.putBoolean("compatibility_active", compatibilityActive);
        values.putInt("play_services_package_id", servicesPackageId);
        values.putInt("play_store_package_id", storePackageId);
        values.putInt("play_services_crashes_this_boot", servicesCrashes);
        values.putInt("play_store_crashes_this_boot", storeCrashes);
        Bundle answer = result(requestId, operation, STATUS_OK,
                healthy ? "Google Play compatibility is active with no current-boot crash."
                        : !compatibilityActive
                                ? "Google Play compatibility identity is inactive."
                                : "A Google Play process crashed during this boot.");
        answer.putString("target", "sandboxed_google_play");
        answer.putString("after", healthy ? "healthy"
                : compatibilityActive ? "crashed_this_boot" : "compatibility_inactive");
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
        int servicesPackageId =
                mPackages.getApplicationInfo(GOOGLE_PLAY_SERVICES_PACKAGE, 0)
                        .ext().getPackageId();
        int storePackageId =
                mPackages.getApplicationInfo(GOOGLE_PLAY_STORE_PACKAGE, 0)
                        .ext().getPackageId();
        if (servicesPackageId != PackageId.GMS_CORE
                || storePackageId != PackageId.PLAY_STORE) {
            return result(requestId, operation, STATUS_DENIED,
                    "Google Play compatibility identity is inactive. Install an OS update; "
                            + "clearing app data cannot repair this.");
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
        if (packageName == null || TextUtils.isEmpty(permission)
                || permission.length() > MAX_PERMISSION_CHARS || !isInstalled(packageName)) {
            return result(requestId, operation, STATUS_INVALID, "Permission change needs an installed package and exact permission.");
        }
        if (LAUNCHER_PACKAGE.equals(packageName) || BROKER_PACKAGE.equals(packageName)) {
            return result(requestId, operation, STATUS_DENIED,
                    "The phone agent cannot rewrite its own baked authority.");
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
        if ("lock".equals(operation)) {
            boolean before = power.isInteractive();
            power.goToSleep(SystemClock.uptimeMillis());
            long deadline = SystemClock.elapsedRealtime() + 5_000;
            while (power.isInteractive() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50);
            }
            boolean locked = !power.isInteractive();
            Bundle answer = result(requestId, operation, locked ? STATUS_OK : STATUS_ERROR,
                    locked ? "Locked the phone." : "Android did not lock the phone.");
            answer.putString("target", "device");
            answer.putString("before", before ? "interactive" : "not_interactive");
            answer.putString("after", locked ? "locked" : "interactive");
            return answer;
        }
        Bundle answer = result(requestId, operation, STATUS_OK,
                operation.substring(0, 1).toUpperCase(Locale.ROOT) + operation.substring(1) + " requested.");
        answer.putString("target", "device");
        answer.putString("after", operation + "_requested");
        if ("reboot".equals(operation)) {
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
        values.putBoolean("wifi_enabled", wifi.isWifiEnabled());
        values.putBoolean("hotspot_enabled", wifi.isWifiApEnabled());
        values.putBoolean("nfc_available", nfc != null);
        values.putBoolean("nfc_enabled", nfc != null && nfc.isEnabled());
        values.putBoolean("bluetooth_available", bluetooth != null);
        values.putBoolean("bluetooth_enabled", bluetooth != null && bluetooth.isEnabled());
        Bundle answer = result(requestId, operation, STATUS_OK, "Read platform connectivity state.");
        answer.putString("target", "connectivity");
        answer.putBundle("values", values);
        return answer;
    }

    private Bundle wifiConnect(String requestId, String operation, Bundle request) {
        String ssid = checkedSsid(request.getString("ssid"));
        String password = request.getString("password");
        if (ssid == null) return result(requestId, operation, STATUS_INVALID, "Wi-Fi connect needs an SSID.");
        if (!validWpaPassword(password)) {
            return result(requestId, operation, STATUS_INVALID,
                    "A WPA password needs 8 through 63 UTF-8 bytes, or 64 hexadecimal characters.");
        }
        WifiManager wifi = mContext.getSystemService(WifiManager.class);
        boolean wifiWasEnabled = wifi.isWifiEnabled();
        if (!wifiWasEnabled) {
            boolean enabled = wifi.setWifiEnabled(true) && waitForWifiEnabled(wifi, true);
            if (!enabled) {
                return result(requestId, operation, STATUS_ERROR,
                        "Android did not turn on Wi-Fi for the connection.");
            }
        }
        WifiConfiguration existing = configuredNetwork(wifi, ssid);
        WifiInfo priorConnection = wifi.getConnectionInfo();
        int priorNetworkId = priorConnection == null ? -1 : priorConnection.getNetworkId();
        WifiConfiguration priorConfiguration =
                existing == null ? null : new WifiConfiguration(existing);
        boolean created = false;
        boolean updated = false;
        int networkId;
        if (existing != null && TextUtils.isEmpty(password)) {
            networkId = existing.networkId;
        } else {
            WifiConfiguration config =
                    existing == null ? new WifiConfiguration() : new WifiConfiguration(existing);
            config.SSID = quote(ssid);
            if (TextUtils.isEmpty(password)) {
                config.allowedKeyManagement.clear();
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                config.preSharedKey = null;
            } else {
                boolean rawPsk = password.matches("[0-9A-Fa-f]{64}");
                config.allowedKeyManagement.clear();
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                config.preSharedKey = rawPsk ? password : quote(password);
            }
            if (existing == null) {
                networkId = wifi.addNetwork(config);
                created = networkId >= 0;
            } else {
                config.networkId = existing.networkId;
                networkId = wifi.updateNetwork(config);
                updated = networkId >= 0;
            }
        }
        if (networkId < 0 || !wifi.enableNetwork(networkId, true)) {
            rollbackWifiChange(
                    wifi,
                    networkId,
                    created,
                    updated,
                    priorConfiguration,
                    priorNetworkId,
                    wifiWasEnabled);
            return result(requestId, operation, STATUS_ERROR, "Android rejected the Wi-Fi connection.");
        }
        wifi.reconnect();
        boolean connected = waitForWifi(wifi, ssid);
        if (connected) {
            wifi.saveConfiguration();
        } else {
            rollbackWifiChange(
                    wifi,
                    networkId,
                    created,
                    updated,
                    priorConfiguration,
                    priorNetworkId,
                    wifiWasEnabled);
        }
        Bundle answer = result(requestId, operation, connected ? STATUS_OK : STATUS_ERROR,
                connected ? "Connected to " + ssid + "." : "The phone did not connect to " + ssid + ".");
        answer.putString("target", ssid);
        answer.putString("after", connected ? "connected" : "not_connected");
        return answer;
    }

    private Bundle wifiForget(String requestId, String operation, Bundle request) {
        String ssid = checkedSsid(request.getString("ssid"));
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
        int expected = pair ? BluetoothDevice.BOND_BONDED : BluetoothDevice.BOND_NONE;
        boolean accepted = before == expected
                || (pair ? device.createBond() : device.removeBond());
        boolean applied = accepted && (before == expected || waitForBond(device, expected));
        if (!applied && pair && before == BluetoothDevice.BOND_NONE) {
            device.cancelBondProcess();
            waitForBond(device, BluetoothDevice.BOND_NONE);
        }
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
        WifiManager wifi = mContext.getSystemService(WifiManager.class);
        boolean before = wifi.isWifiApEnabled();
        if (before == enabled) {
            Bundle unchanged = result(requestId, operation, STATUS_OK,
                    "Wi-Fi hotspot is already " + (enabled ? "on." : "off."));
            unchanged.putString("target", "wifi_hotspot");
            unchanged.putString("before", enabled ? "on" : "off");
            unchanged.putString("after", enabled ? "on" : "off");
            return unchanged;
        }
        if (!enabled) {
            tethering.stopTethering(TetheringManager.TETHERING_WIFI);
            boolean stopped = waitForHotspot(wifi, false);
            Bundle answer = result(requestId, operation,
                    stopped ? STATUS_OK : STATUS_ERROR,
                    stopped ? "Wi-Fi hotspot turned off."
                            : "Android did not turn the Wi-Fi hotspot off.");
            answer.putString("target", "wifi_hotspot");
            answer.putString("before", "on");
            answer.putString("after", stopped ? "off" : "on");
            return answer;
        }
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger errorCode = new AtomicInteger(-1);
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean acceptingStart = new AtomicBoolean(true);
        TetheringManager.TetheringRequest tetheringRequest =
                new TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).build();
        tethering.startTethering(tetheringRequest, mContext.getMainExecutor(),
                new TetheringManager.StartTetheringCallback() {
                    @Override public void onTetheringStarted() {
                        if (!acceptingStart.get()) {
                            tethering.stopTethering(TetheringManager.TETHERING_WIFI);
                            return;
                        }
                        started.set(true);
                        finished.countDown();
                    }
                    @Override public void onTetheringFailed(int error) {
                        errorCode.set(error);
                        finished.countDown();
                    }
                });
        boolean returned = finished.await(30, TimeUnit.SECONDS);
        boolean active = returned && started.get() && waitForHotspot(wifi, true);
        if (!active) {
            acceptingStart.set(false);
            tethering.stopTethering(TetheringManager.TETHERING_WIFI);
            waitForHotspot(wifi, false);
        }
        boolean after = wifi.isWifiApEnabled();
        Bundle answer = result(requestId, operation, active ? STATUS_OK : STATUS_ERROR,
                active ? "Wi-Fi hotspot turned on."
                        : "Hotspot start failed with code " + errorCode.get() + ".");
        answer.putString("target", "wifi_hotspot");
        answer.putString("before", "off");
        answer.putString("after", after ? "on" : "off");
        return answer;
    }

    private Bundle nfc(String requestId, String operation, boolean enabled) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        if (adapter == null) return result(requestId, operation, STATUS_NOT_FOUND, "NFC is unavailable.");
        boolean before = adapter.isEnabled();
        boolean accepted = before == enabled || (enabled ? adapter.enable() : adapter.disable());
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

    private InstallOutcome commitSession(PackageInstaller.Session session, String suffix,
            AtomicBoolean submitted)
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
            submitted.set(true);
            if (!finished.await(PACKAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return new InstallOutcome(false, "", "Package installation timed out.");
            }
            return outcome.get();
        } finally {
            pending.cancel();
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
            pending.cancel();
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

    private boolean isEnabled(String packageName) {
        try {
            return mPackages.getApplicationInfo(packageName, 0).enabled;
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
        if (value == null || value.length() > MAX_PACKAGE_CHARS
                || !value.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) return null;
        return value;
    }

    private WifiConfiguration configuredNetwork(WifiManager wifi, String ssid) {
        List<WifiConfiguration> configured = wifi.getConfiguredNetworks();
        if (configured == null) return null;
        for (WifiConfiguration candidate : configured) {
            if (ssid.equals(observedSsid(candidate.SSID))) return candidate;
        }
        return null;
    }

    private boolean waitForWifi(WifiManager wifi, String ssid) {
        long deadline = SystemClock.elapsedRealtime() + RADIO_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            WifiInfo info = wifi.getConnectionInfo();
            if (info != null && ssid.equals(observedSsid(info.getSSID()))
                    && info.getNetworkId() != -1) return true;
            SystemClock.sleep(250);
        }
        return false;
    }

    private void rollbackWifiChange(
            WifiManager wifi,
            int changedNetworkId,
            boolean created,
            boolean updated,
            WifiConfiguration priorConfiguration,
            int priorNetworkId,
            boolean wifiWasEnabled) {
        if (created && changedNetworkId >= 0) {
            wifi.removeNetwork(changedNetworkId);
        } else if (updated && priorConfiguration != null) {
            wifi.updateNetwork(priorConfiguration);
        }
        wifi.saveConfiguration();
        if (priorNetworkId >= 0) {
            wifi.enableNetwork(priorNetworkId, true);
            wifi.reconnect();
        }
        if (!wifiWasEnabled) {
            wifi.setWifiEnabled(false);
            waitForWifiEnabled(wifi, false);
        }
    }

    private boolean waitForWifiEnabled(WifiManager wifi, boolean enabled) {
        long deadline = SystemClock.elapsedRealtime() + RADIO_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (wifi.isWifiEnabled() == enabled) return true;
            SystemClock.sleep(250);
        }
        return wifi.isWifiEnabled() == enabled;
    }

    private boolean waitForHotspot(WifiManager wifi, boolean enabled) {
        long deadline = SystemClock.elapsedRealtime() + RADIO_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (wifi.isWifiApEnabled() == enabled) return true;
            SystemClock.sleep(250);
        }
        return wifi.isWifiApEnabled() == enabled;
    }

    private BluetoothDevice findBluetoothDevice(BluetoothAdapter adapter, String query) {
        if (TextUtils.isEmpty(query) || query.length() > 240) return null;
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

    private static String checkedSsid(String value) {
        if (value == null) return null;
        return value.isEmpty()
                || value.getBytes(StandardCharsets.UTF_8).length > 32
                || value.chars().anyMatch(codePoint -> Character.isISOControl(codePoint))
                ? null : value;
    }

    private static String observedSsid(String value) {
        if (value == null) return null;
        String clean = value;
        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return checkedSsid(clean);
    }

    private static boolean validWpaPassword(String password) {
        if (TextUtils.isEmpty(password)) return true;
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        boolean rawPsk = password.matches("[0-9A-Fa-f]{64}");
        return !password.chars().anyMatch(codePoint -> Character.isISOControl(codePoint))
                && (rawPsk || (passwordBytes >= 8 && passwordBytes <= 63));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String safeDeviceName(BluetoothDevice device) {
        String name = device.getName();
        return bounded(TextUtils.isEmpty(name) ? device.getAddress() : name, 240);
    }

    private static String bondName(int state) {
        if (state == BluetoothDevice.BOND_BONDED) return "paired";
        if (state == BluetoothDevice.BOND_BONDING) return "pairing";
        return "not_paired";
    }

    private static Bundle result(String requestId, String operation, String status, String detail) {
        Bundle result = new Bundle();
        result.putString("request_id", bounded(requestId, 128));
        result.putString("operation", bounded(operation, MAX_OPERATION_CHARS));
        result.putString("status", bounded(status, 64));
        result.putString("detail", bounded(detail, MAX_DETAIL_CHARS));
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
        return bounded(
                TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message,
                MAX_DETAIL_CHARS);
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
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
