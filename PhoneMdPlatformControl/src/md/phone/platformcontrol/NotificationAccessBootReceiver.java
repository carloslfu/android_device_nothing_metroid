package md.phone.platformcontrol;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Slog;

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
    private static final ComponentName PHONE_MD_LISTENER = new ComponentName(
            "md.phone.launcher",
            "md.phone.launcher.control.PhoneNotificationListener");

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

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
}
