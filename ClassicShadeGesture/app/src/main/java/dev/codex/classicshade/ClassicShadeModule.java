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
    private boolean reportedScrolledGesture;
    private boolean reportedFailure;
    private volatile boolean classicStyle;
    private ClassicGroupExpansion groupExpansion;

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
        HookHandle scrolledPullHook = null;
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
            scrolledPullHook = installScrolledPullRecognition(loader);
            try {
                groupExpansion = new ClassicGroupExpansion(this, loader, () -> classicStyle);
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Group adaptation unavailable; basic gestures retained", error);
            }
            installed = true;

            // Prevent ART from bypassing this small OEM method through inlining.
            deoptimizeCallers(loader);
            report("Installed OEM enable + native touch threshold + scrolled pull hooks (API " + getApiVersion() + ")");
        } catch (Throwable error) {
            if (scrolledPullHook != null) scrolledPullHook.unhook();
            if (thresholdHook != null) thresholdHook.unhook();
            if (enableHook != null) enableHook.unhook();
            log(Log.ERROR, TAG, "Unsupported SystemUI; no modification applied", error);
        }
    }

    private HookHandle installScrolledPullRecognition(ClassLoader loader) throws Exception {
        Class<?> helper = Class.forName("com.android.systemui.ExpandHelper", false, loader);
        Field enabled = field(helper, "mEnabled");
        Field expanding = field(helper, "mExpanding");
        Field watching = field(helper, "mWatchingForPull");
        Field resized = field(helper, "mResizedView");
        Field detector = field(helper, "mSGD");
        Field adapter = field(helper, "mScrollAdapter");
        Field host = field(adapter.getType(), "this$0");
        Method scrollY = host.getType().getDeclaredMethod("getOwnScrollY");
        Method inside = helper.getDeclaredMethod("isInside", android.view.View.class,
                float.class, float.class);
        scrollY.setAccessible(true);
        inside.setAccessible(true);
        Method intercept = helper.getDeclaredMethod("onInterceptTouchEvent", android.view.MotionEvent.class);

        HookHandle handle = hook(intercept).intercept(chain -> {
            Object result = chain.proceed();
            android.view.MotionEvent event = (android.view.MotionEvent) chain.getArg(0);
            if (!classicStyle || event.getActionMasked() != android.view.MotionEvent.ACTION_DOWN ||
                    event.getPointerCount() != 1) return result;
            try {
                Object self = chain.getThisObject();
                // Let the native DOWN handling pick and validate the row first.
                // Its remaining false condition is getOwnScrollY() != 0. A scroll
                // offset must not prevent a new drag on that valid notification.
                if (enabled.getBoolean(self) && !expanding.getBoolean(self) &&
                        !watching.getBoolean(self) && resized.get(self) != null) {
                    Object scrollAdapter = adapter.get(self);
                    if (scrollAdapter != null) {
                        Object scrollHost = host.get(scrollAdapter);
                        android.view.ScaleGestureDetector scaleDetector =
                                (android.view.ScaleGestureDetector) detector.get(self);
                        if ((Integer) scrollY.invoke(scrollHost) > 0 &&
                                (Boolean) inside.invoke(self, scrollHost,
                                        (float) (int) scaleDetector.getFocusX(),
                                        (float) (int) scaleDetector.getFocusY())) {
                            watching.setBoolean(self, true);
                            if (!reportedScrolledGesture) {
                                reportedScrolledGesture = true;
                                report("Scrolled classic list: native pull recognition enabled");
                            }
                        }
                    }
                }
            } catch (Throwable error) {
                if (!reportedFailure) {
                    reportedFailure = true;
                    log(Log.ERROR, TAG, "Scrolled pull unavailable; using OEM behavior", error);
                }
            }
            return result;
        });
        try {
            Class<?> handler = Class.forName(STACK +
                    "NotificationStackScrollLayoutController$TouchHandler", false, loader);
            for (Method method : handler.getDeclaredMethods()) {
                if (method.getName().equals("onInterceptTouchEvent")) deoptimize(method);
            }
        } catch (Throwable ignored) {
            // Optional optimization hint; the hook still applies without it.
        }
        return handle;
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
