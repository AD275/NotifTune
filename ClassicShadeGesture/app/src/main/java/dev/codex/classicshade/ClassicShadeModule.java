package dev.codex.classicshade;

import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedModule;

/** Enables the existing ExpandHelper without changing notification rendering. */
public final class ClassicShadeModule extends XposedModule {
    private static final String TAG = "ClassicShadeGesture";
    private static final String STACK =
            "com.android.systemui.statusbar.notification.stack.";
    private boolean systemUiProcess;
    private boolean installed;
    private boolean reportedEnabled;
    private boolean reportedFailure;
    private volatile boolean classicStyle;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        systemUiProcess = "com.android.systemui".equals(param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!systemUiProcess || installed ||
                !"com.android.systemui".equals(param.getPackageName())) return;
        HookHandle enableHook = null;
        HookHandle thresholdHook = null;
        try {
            ClassLoader loader = param.getClassLoader();
            Class<?> injector = Class.forName(STACK +
                    "NotificationStackScrollLayoutControllerInjectorImpl", false, loader);
            Class<?> layoutClass = Class.forName(STACK +
                    "NotificationStackScrollLayout", false, loader);
            Field style = field(injector, "notificationStyle");
            Field state = field(injector, "statusBarState");
            Field layout = field(injector, "layout");
            Method update = injector.getDeclaredMethod("updateViewExpandingEnabled");
            Method enable = layoutClass.getDeclaredMethod("setExpandingEnabled", boolean.class);
            enable.setAccessible(true);

            enableHook = hook(update).intercept(chain -> {
                // Keep Xiaomi's state transitions, including its keyguard restriction.
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    classicStyle = style.getInt(self) == 0;
                    if (classicStyle && state.getInt(self) != 1) {
                        // The OEM condition is: state != KEYGUARD && style == NATIVE.
                        // Only remove the style requirement. ExpandHelper does the rest.
                        enable.invoke(layout.get(self), true);
                        if (!reportedEnabled) {
                            reportedEnabled = true;
                            report("Classic style: system ExpandHelper enabled");
                        }
                    }
                } catch (Throwable error) {
                    if (!reportedFailure) {
                        reportedFailure = true;
                        log(Log.ERROR, TAG, "Could not enable gesture; OEM behavior retained", error);
                    }
                }
                return result;
            });
            thresholdHook = installNativeStretchThreshold(loader);
            installed = true;

            // Prevent ART from bypassing this small OEM method through inlining.
            deoptimizeCallers(loader);
            report("Installed OEM enable + native touch threshold hooks (API " + getApiVersion() + ")");
        } catch (Throwable error) {
            if (thresholdHook != null) thresholdHook.unhook();
            if (enableHook != null) enableHook.unhook();
            log(Log.ERROR, TAG, "Unsupported SystemUI; no modification applied", error);
        }
    }

    private void deoptimizeCallers(ClassLoader loader) {
        String[] classes = {
                "NotificationStackScrollLayoutController",
                "NotificationStackScrollLayoutController$7",
                "NotificationStackScrollLayoutControllerInjectorImpl$setUpView$2"
        };
        for (String name : classes) {
            try {
                Class<?> type = Class.forName(STACK + name, false, loader);
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    deoptimize(constructor);
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().equals("onStatePostChange") ||
                            method.getName().equals("onChanged")) deoptimize(method);
                }
            } catch (Throwable error) {
                // Optimization hints are optional; never block SystemUI startup.
                log(Log.WARN, TAG, "Optional caller deoptimization unavailable: " + name);
            }
        }
    }

    private HookHandle installNativeStretchThreshold(ClassLoader loader) throws Exception {
        String prefix = "com.android.systemui.shade.";
        Class<?> injector = Class.forName(prefix + "NotificationPanelViewControllerInjector", false, loader);
        Class<?> controller = Class.forName(prefix + "NotificationPanelViewController", false, loader);
        Field fullyExpanded = field(injector, "isFullyExpandedOnDown");
        Field barState = field(injector, "barState");
        Method getController = injector.getDeclaredMethod("getPanelViewController");
        Method getSlop = controller.getDeclaredMethod("getTouchSlop$2", android.view.MotionEvent.class);
        getController.setAccessible(true);
        getSlop.setAccessible(true);
        Method stretch = injector.getDeclaredMethod("handleStretchState", float.class,
                float.class, android.view.MotionEvent.class);
        HookHandle handle = hook(stretch).intercept(chain -> {
            Object self = chain.getThisObject();
            // Xiaomi already gives native notifications 1.5x slop so the child can
            // claim the drag first. Apply that same threshold to classic style.
            try {
                if (classicStyle && fullyExpanded.getBoolean(self) && barState.getInt(self) == 0) {
                    float dy = (Float) chain.getArg(0);
                    if (dy > 0) {
                        Object panel = getController.invoke(self);
                        float slop = (Float) getSlop.invoke(panel, chain.getArg(2));
                        if (dy <= slop * 1.5f) return false;
                    }
                }
            } catch (Throwable error) {
                if (!reportedFailure) {
                    reportedFailure = true;
                    log(Log.ERROR, TAG, "Touch threshold unavailable; using OEM behavior", error);
                }
            }
            return chain.proceed();
        });
        try {
            Class<?> handler = Class.forName(prefix + "NotificationPanelViewController$TouchHandler", false, loader);
            for (Method method : handler.getDeclaredMethods()) {
                if (method.getName().equals("onInterceptTouchEvent") ||
                        method.getName().equals("onTouchEvent")) deoptimize(method);
            }
        } catch (Throwable ignored) {
            // Optional optimization hint, as above.
        }
        return handle;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void report(String message) {
        log(Log.INFO, TAG, message);
        Log.i(TAG, message);
    }

}
