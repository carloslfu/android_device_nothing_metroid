package md.phone.platformcontrol;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.util.Slog;

import java.security.MessageDigest;

/**
 * Carries phone.md's notification ownership across preserve-data ROM upgrades.
 *
 * Android's framework default is applied only when a user or its notification
 * policy is first created. This platform-signed component requests the grant
 * again after an OTA-style upgrade and at boot. Passing userSet=false makes
 * NotificationManager keep an explicit user disable intact.
 */
public final class NotificationAccessBootReceiver extends BroadcastReceiver {
    private static final String TAG = "PhoneMdNotificationAccess";
    private static final String PHONE_MD_PACKAGE = "md.phone.launcher";
    private static final String PHONE_MD_CERT_SHA256 =
            "261ae1251b95af2d5af84e0c3831d261e8c0f716d18887bb23ffdbfd309215dc";
    private static final ComponentName PHONE_MD_LISTENER = new ComponentName(
            PHONE_MD_PACKAGE,
            "md.phone.launcher.control.PhoneNotificationListener");
    private static final int USER_DECISION_FLAGS = PackageManager.FLAG_PERMISSION_USER_SET
            | PackageManager.FLAG_PERMISSION_USER_FIXED
            | PackageManager.FLAG_PERMISSION_POLICY_FIXED
            | PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
    private static final int MATCH_ALL_BOOT_STATES = PackageManager.MATCH_DIRECT_BOOT_AWARE
            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
    private static final String[] PHONE_MD_RUNTIME_PERMISSIONS = {
            "android.permission.RECORD_AUDIO",
            "android.permission.CALL_PHONE",
            "android.permission.SEND_SMS",
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.ANSWER_PHONE_CALLS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.CAMERA",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.NEARBY_WIFI_DEVICES",
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        int userId = context.getUserId();
        if (!isTrustedPhoneMdHome(context, userId)) {
            Slog.e(TAG, "Refusing to reconcile an untrusted phone.md HOME after " + action);
            return;
        }

        reconcileRuntimePermissions(context, UserHandle.of(userId), action);

        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null) {
            Slog.e(TAG, "NotificationManager is unavailable during " + action);
            return;
        }

        try {
            notifications.setNotificationListenerAccessGranted(
                    PHONE_MD_LISTENER, true, false /* userSet */);
            Slog.i(TAG, "Reconciled phone.md notification access after " + action);
        } catch (RuntimeException error) {
            Slog.e(TAG, "Could not reconcile phone.md notification access after " + action,
                    error);
        }
    }

    private static void reconcileRuntimePermissions(
            Context context, UserHandle user, String action) {
        PackageManager packageManager = context.getPackageManager();
        for (String permission : PHONE_MD_RUNTIME_PERMISSIONS) {
            try {
                if (packageManager.checkPermission(permission, PHONE_MD_PACKAGE)
                        == PackageManager.PERMISSION_GRANTED) {
                    continue;
                }
                int flags = packageManager.getPermissionFlags(permission, PHONE_MD_PACKAGE, user);
                if ((flags & USER_DECISION_FLAGS) != 0) {
                    Slog.i(TAG, "Preserved the user's phone.md permission choice for "
                            + permission);
                    continue;
                }
                packageManager.grantRuntimePermission(PHONE_MD_PACKAGE, permission, user);
                Slog.i(TAG, "Granted new phone.md runtime permission " + permission
                        + " after " + action);
            } catch (RuntimeException error) {
                Slog.e(TAG, "Could not reconcile phone.md runtime permission " + permission
                        + " after " + action, error);
            }
        }
    }

    private static boolean isTrustedPhoneMdHome(Context context, int userId) {
        PackageManager packageManager = context.getPackageManager();
        try {
            ApplicationInfo launcher = packageManager.getApplicationInfo(
                    PHONE_MD_PACKAGE, MATCH_ALL_BOOT_STATES);
            if ((launcher.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    || (launcher.privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) == 0) {
                return false;
            }
            PackageInfo packageInfo = packageManager.getPackageInfo(
                    PHONE_MD_PACKAGE,
                    PackageManager.GET_SIGNING_CERTIFICATES | MATCH_ALL_BOOT_STATES);
            if (packageInfo.signingInfo == null
                    || packageInfo.signingInfo.getApkContentsSigners().length != 1
                    || !PHONE_MD_CERT_SHA256.equals(hex(MessageDigest.getInstance("SHA-256")
                    .digest(packageInfo.signingInfo.getApkContentsSigners()[0].toByteArray())))) {
                return false;
            }

            Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo home = packageManager.resolveActivityAsUser(
                    homeIntent, PackageManager.MATCH_DEFAULT_ONLY | MATCH_ALL_BOOT_STATES, userId);
            return home != null && home.activityInfo != null
                    && PHONE_MD_PACKAGE.equals(home.activityInfo.packageName);
        } catch (Throwable error) {
            Slog.e(TAG, "Could not validate phone.md HOME for permission reconciliation", error);
            return false;
        }
    }

    private static String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte item : value) {
            builder.append(String.format("%02x", item & 0xff));
        }
        return builder.toString();
    }
}
