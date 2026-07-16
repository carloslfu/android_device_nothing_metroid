package md.phone.localecontroller;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.app.LocalePicker;

import java.util.Locale;
import java.util.Set;

/**
 * Owns the one platform authority needed for language changes.
 *
 * The launcher keeps its normal system-app signature. This service accepts
 * only the installed system HOME package, validates a small locale allowlist,
 * changes Android's persistent locale, and checks the resulting configuration.
 */
public final class LocaleControllerService extends Service {
    private static final String TAG = "PhoneMdLocale";
    private static final String DESCRIPTOR = "md.phone.localecontroller.ILocaleController";
    private static final String LAUNCHER_PACKAGE = "md.phone.launcher";
    private static final int TRANSACTION_SET_LANGUAGE = IBinder.FIRST_CALL_TRANSACTION;
    private static final Set<String> SUPPORTED_TAGS = Set.of("en-US", "es-CO");

    private final Binder mBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code != TRANSACTION_SET_LANGUAGE) {
                return super.onTransact(code, data, reply, flags);
            }

            data.enforceInterface(DESCRIPTOR);
            final String languageTag = data.readString();
            data.enforceNoDataAvail();
            final boolean changed = setLanguageForCaller(languageTag);
            reply.writeNoException();
            reply.writeInt(changed ? 1 : 0);
            return true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private boolean setLanguageForCaller(String languageTag) {
        final int callingUid = Binder.getCallingUid();
        if (!SUPPORTED_TAGS.contains(languageTag)) {
            Slog.w(TAG, "Rejected unsupported locale from uid " + callingUid);
            return false;
        }
        if (!isCurrentSystemHome(callingUid)) {
            Slog.w(TAG, "Rejected non-HOME locale caller uid " + callingUid);
            return false;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            LocalePicker.updateLocales(
                    new LocaleList(Locale.forLanguageTag(languageTag)));
            final Configuration checked = ActivityManager.getService().getConfiguration();
            final Locale active = checked.getLocales().isEmpty()
                    ? null : checked.getLocales().get(0);
            final boolean applied = active != null
                    && active.toLanguageTag().equalsIgnoreCase(languageTag);
            Slog.i(TAG, "Locale " + languageTag + " applied=" + applied);
            return applied;
        } catch (RuntimeException | RemoteException error) {
            Slog.e(TAG, "Could not apply locale " + languageTag, error);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean isCurrentSystemHome(int uid) {
        final PackageManager packageManager = getPackageManager();
        final String[] packages = packageManager.getPackagesForUid(uid);
        boolean ownsLauncherPackage = false;
        if (packages != null) {
            for (String packageName : packages) {
                if (LAUNCHER_PACKAGE.equals(packageName)) {
                    ownsLauncherPackage = true;
                    break;
                }
            }
        }
        if (!ownsLauncherPackage) {
            return false;
        }

        try {
            final ApplicationInfo launcher =
                    packageManager.getApplicationInfo(LAUNCHER_PACKAGE, 0);
            if ((launcher.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                return false;
            }
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }

        final Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME);
        final ResolveInfo home = packageManager.resolveActivityAsUser(
                homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY,
                UserHandle.getUserId(uid));
        return home != null
                && home.activityInfo != null
                && LAUNCHER_PACKAGE.equals(home.activityInfo.packageName);
    }
}
