// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.content.Context;
import android.annotation.UserIdInt;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.rosan.dhizuku.api.Dhizuku;
import com.rosan.dhizuku.api.DhizukuRemoteProcess;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import io.github.muntashirakon.AppManager.compat.ApplicationInfoCompat;
import io.github.muntashirakon.AppManager.compat.ManifestCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.db.AppsDb;
import io.github.muntashirakon.AppManager.db.entity.FreezeType;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.settings.Prefs;

public final class FreezeUtils {
    @IntDef({FREEZE_DISABLE, FREEZE_SUSPEND, FREEZE_HIDE, FREEZE_ADV_SUSPEND, FREEZE_DHIZUKU})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FreezeMethod {
    }

    public static final int FREEZE_DISABLE = 1;
    public static final int FREEZE_SUSPEND = 1 << 1;
    public static final int FREEZE_HIDE = 1 << 2;
    public static final int FREEZE_ADV_SUSPEND = 1 << 3;
    public static final int FREEZE_DHIZUKU = 1 << 4;

    @WorkerThread
    public static void storeFreezeMethod(@NonNull String packageName, @FreezeMethod int freezeType) {
        AppsDb.getInstance().freezeTypeDao().insert(new FreezeType(packageName, freezeType));
    }

    @WorkerThread
    public static void deleteFreezeMethod(@NonNull String packageName) {
        AppsDb.getInstance().freezeTypeDao().delete(packageName);
    }

    @WorkerThread
    @FreezeMethod
    @Nullable
    public static Integer loadFreezeMethod(@Nullable String packageName) {
        if (packageName != null) {
            FreezeType freezeType;
            freezeType = AppsDb.getInstance().freezeTypeDao().get(packageName);
            if (freezeType != null) {
                return freezeType.type;
            }
        }
        // No package-specific freezing method exists
        return null;
    }

    public static boolean isFrozen(@NonNull ApplicationInfo applicationInfo) {
        // An app is frozen if one of the following operations return true: suspend, disable or hide
        if (!applicationInfo.enabled) {
            return true;
        }
        if (ApplicationInfoCompat.isSuspended(applicationInfo)) {
            return true;
        }
        return ApplicationInfoCompat.isHidden(applicationInfo);
    }

    @Deprecated
    public static boolean freeze(@NonNull Context context, @NonNull String packageName, @UserIdInt int userId) throws RemoteException {
        return freeze(context, packageName, userId, Prefs.Blocking.getDefaultFreezingMethod());
    }

    public static boolean freeze(@NonNull Context context, @NonNull String packageName, @UserIdInt int userId, @FreezeMethod int freezeType)
            throws RemoteException {
        if (freezeType == FREEZE_DHIZUKU) {
            if (Dhizuku.init(context)) {
                try {
                    DhizukuRemoteProcess process = Dhizuku.newProcess(new String[]{"pm", "disable", "user", "--user", String.valueOf(userId), packageName}, null, null);
                    return process.waitFor() == 0;
                } catch (Exception e) {
                    Log.e("AppManager", "Failed to freeze with Dhizuku", e);
                    return false;
                }
            }
            return false;
        } else if (freezeType == FREEZE_HIDE) {
            if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_USERS)) {
                PackageManagerCompat.hidePackage(packageName, userId, true);
                return true;
            }
            // No permission, fall-through
        } else if ((freezeType == FREEZE_SUSPEND || freezeType == FREEZE_ADV_SUSPEND) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (freezeType == FREEZE_ADV_SUSPEND) {
                // Force-stop app
                if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.FORCE_STOP_PACKAGES)) {
                    PackageManagerCompat.forceStopPackage(packageName, userId);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.SUSPEND_APPS)) {
                    PackageManagerCompat.suspendPackages(new String[]{packageName}, userId, true);
                    return true;
                }
                // No permission, fall-through
            } else {
                if (SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.MANAGE_USERS)) {
                    PackageManagerCompat.suspendPackages(new String[]{packageName}, userId, true);
                    return true;
                }
                // No permission, fall-through
            }
        }
        PackageManagerCompat.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0, userId);
        return true;
    }

    public static boolean unfreeze(@NonNull Context context, @NonNull String packageName, @UserIdInt int userId) throws RemoteException {
        Integer freezeType = loadFreezeMethod(packageName);
        if (freezeType != null && freezeType == FREEZE_DHIZUKU) {
            if (Dhizuku.init(context)) {
                try {
                    DhizukuRemoteProcess process = Dhizuku.newProcess(new String[]{"pm", "enable", packageName}, null, null);
                    return process.waitFor() == 0;
                } catch (Exception e) {
                    Log.e("AppManager", "Failed to unfreeze with Dhizuku", e);
                    return false;
                }
            }
            return false;
        }
        // Unfreeze using other methods as a fallback
        if (PackageManagerCompat.getApplicationEnabledSetting(packageName, userId) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            PackageManagerCompat.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0, userId);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && PackageManagerCompat.isPackageSuspended(packageName, userId)) {
            PackageManagerCompat.suspendPackages(new String[]{packageName}, userId, false);
        }
        if (PackageManagerCompat.isPackageHidden(packageName, userId)) {
            PackageManagerCompat.hidePackage(packageName, userId, false);
        }
        return true;
    }
}
