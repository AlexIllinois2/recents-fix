package dev.n8p.recentsfix;

import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Makes the second tap on the (3-button navigation) recents button immediately interrupt the
 * overview transition and jump to the next task, instead of waiting for the overview animation
 * to finish (Launcher3 serialises overview commands in OverviewCommandHelper).
 *
 * First tap: untouched, dispatches KEYCODE_APP_SWITCH (overview opens instantly because
 * lineagesettings key_app_switch_double_tap_action = 0).
 * Second tap within the window: directly startActivityFromRecents(nextTaskView) from the
 * launcher's own overview data, which is a window-manager level switch that overrides the
 * in-flight overview transition.
 */
public class Module implements IXposedHookLoadPackage {

    private static final String TAG = "RecentsFix: ";
    private static final String LAUNCHER = "com.android.launcher3";
    /** system_server runs in the "android" package. */
    private static final String SYSTEM = "android";

    /** Max gap between the two taps. */
    private static final long DOUBLE_TAP_MS = 450L;
    /**
     * Lower bound between the two taps. Taps closer than this arrive before the overview
     * transition has initialised, and launching a task then leaves the launcher's recents
     * animation state half-initialised (the next recents press would be swallowed).
     */
    private static final long MIN_TAP_MS = 80L;

    private static volatile long sLastTapTime;
    private static volatile Object sRecentsView;
    private static volatile boolean sLoggedPwm;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (SYSTEM.equals(lpparam.packageName)) {
            hookPhoneWindowManager(lpparam);
            return;
        }
        if (!LAUNCHER.equals(lpparam.packageName)) {
            return;
        }
        try {
            hookRecentsButton(lpparam);
            hookRecentsViewCapture(lpparam);
            XposedBridge.log(TAG + "hooks installed in " + lpparam.processName);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /**
     * Lineage's PhoneWindowManager waits ~300ms before dispatching APP_SWITCH so it can tell a
     * single press from a double press. The double-press action being NOTHING disables that wait,
     * which is the same effect as writing key_app_switch_double_tap_action = 0 into
     * lineagesettings. That setting lives in a provider that only system_server (or a package
     * holding lineageos.permission.WRITE_SETTINGS) may write, so it is forced here instead.
     */
    private void hookPhoneWindowManager(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> pwm = XposedHelpers.findClass(
                    "com.android.server.policy.PhoneWindowManager", lpparam.classLoader);
            Class<?> actionCls = XposedHelpers.findClass(
                    "org.lineageos.internal.util.DeviceKeysConstants$Action", lpparam.classLoader);
            Object nothing = XposedHelpers.getStaticObjectField(actionCls, "NOTHING");
            XposedBridge.hookAllMethods(pwm, "updateKeyAssignments", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        XposedHelpers.setObjectField(
                                param.thisObject, "mAppSwitchDoubleTapAction", nothing);
                        if (!sLoggedPwm) {
                            sLoggedPwm = true;
                            XposedBridge.log(TAG + "system_server: app switch double tap forced to NOTHING");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });
            XposedBridge.log(TAG + "system_server hooks installed in " + lpparam.processName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "system_server hook failed: " + t);
        }
    }

    private void hookRecentsButton(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(
                    "com.android.launcher3.taskbar.TaskbarNavButtonController", lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "TaskbarNavButtonController not found: " + t);
            return;
        }
        XposedBridge.hookAllMethods(cls, "onButtonClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object first = param.args[0];
                    if (!(first instanceof Integer) || ((Integer) first).intValue() != 4) {
                        return; // 4 == recents button
                    }
                    long now = SystemClock.uptimeMillis();
                    long last = sLastTapTime;
                    Object recentsView = sRecentsView;
                    long delta = now - last;
                    XposedBridge.log(TAG + "recents tap: delta=" + (last == 0 ? -1 : delta)
                            + " view=" + (recentsView != null));
                    if (last != 0 && delta >= MIN_TAP_MS && delta <= DOUBLE_TAP_MS
                            && recentsView != null) {
                        if (switchToNextTask(recentsView)) {
                            sLastTapTime = 0L;
                            sRecentsView = null;
                            param.setResult(null);
                            XposedBridge.log(TAG + "second tap: switched to next task");
                            return;
                        }
                        XposedBridge.log(TAG + "second tap: switch failed, dispatching original");
                    }
                    sLastTapTime = now;
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    private void hookRecentsViewCapture(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> helper;
        try {
            helper = XposedHelpers.findClass(
                    "com.android.quickstep.OverviewCommandHelper", lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "OverviewCommandHelper not found: " + t);
            return;
        }
        XC_MethodHook captureOnStart = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object view = findRecentsView(param.args);
                if (view != null) {
                    sRecentsView = view;
                    XposedBridge.log(TAG + "overview started, view=" + view.getClass().getSimpleName());
                }
            }
        };
        XC_MethodHook captureView = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (result != null && result.getClass().getName().endsWith("RecentsView")) {
                    sRecentsView = result;
                } else if (result == null) {
                    // Overview is no longer visible; forget the (now detached) view.
                    sRecentsView = null;
                }
            }
        };
        XposedBridge.hookAllMethods(helper, "startRecentsTransitionWithEndTarget", captureOnStart);
        XposedBridge.hookAllMethods(helper, "getVisibleRecentsView", captureView);
    }

    private boolean switchToNextTask(Object recentsView) {
        try {
            Object running = invoke(recentsView, "getRunningTaskView");
            Object target;
            if (running != null) {
                Object next = invoke(recentsView, "getNextTaskView");
                target = next != null ? next : running;
            } else {
                Object last = invoke(recentsView, "getLastLargeTaskView");
                target = last != null ? last : invoke(recentsView, "getFirstTaskView");
            }
            if (target == null) {
                XposedBridge.log(TAG + "no target task view");
                return false;
            }
            Object ids = invoke(target, "getTaskIdSet");
            if (!(ids instanceof Set) || ((Set<?>) ids).isEmpty()) {
                XposedBridge.log(TAG + "no task ids for target");
                return false;
            }
            int taskId = ((Number) ((Set<?>) ids).iterator().next()).intValue();
            XposedBridge.log(TAG + "switch taskId=" + taskId
                    + " running=" + (running != null)
                    + " target=" + target.getClass().getSimpleName());
            // Use the launcher's own launch path so it builds the right ActivityOptions and
            // receives the launch callbacks (keeps its recents animation state consistent).
            Object runnableList = invoke(target, "launchWithAnimation");
            XposedBridge.log(TAG + "launchWithAnimation -> " + runnableList);
            return runnableList != null;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return false;
        }
    }

    private static Object findRecentsView(Object[] args) {
        for (Object arg : args) {
            if (arg != null && arg.getClass().getName().endsWith("RecentsView")) {
                return arg;
            }
        }
        return null;
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }
}
