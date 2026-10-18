package dev.codex.classicshade;

import android.animation.Animator;
import android.util.Log;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import io.github.libxposed.api.XposedInterface.HookHandle;

/** Adapts Xiaomi's existing child views to the height driven by ExpandHelper. */
final class ClassicGroupExpansion {
    private static final String TAG = "ClassicShadeGroup";
    private final ClassicShadeModule module;
    private final BooleanSupplier classic;
    private final Class<?> containerType, rowType, contentType, stateType;
    private final List<HookHandle> hooks = new ArrayList<>();
    // Entries exist only for an active native drag, including its native settling animation.
    private final Map<Object, Session> active = new IdentityHashMap<>();
    private final Map<Object, Boolean> settled = new WeakHashMap<>();
    private final Map<String, Field> fields = new HashMap<>();
    private final Map<String, Method> methods = new HashMap<>();
    private boolean failed;

    ClassicGroupExpansion(ClassicShadeModule module, ClassLoader loader,
            BooleanSupplier classic) throws Exception {
        this.module = module;
        this.classic = classic;
        containerType = Class.forName("com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer", false, loader);
        rowType = Class.forName("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false, loader);
        contentType = Class.forName("com.android.systemui.statusbar.notification.row.NotificationContentView", false, loader);
        stateType = Class.forName("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow$NotificationViewState", false, loader);
        Class<?> expandableState = Class.forName("com.android.systemui.statusbar.notification.stack.ExpandableViewState", false, loader);
        Class<?> properties = Class.forName("com.android.systemui.statusbar.notification.stack.AnimationProperties", false, loader);
        try {
            hooks.add(module.hook(method(containerType, "setUserSwipingToExpandRow", boolean.class)).intercept(chain -> {
                Object container = chain.getThisObject();
                boolean expanding = (Boolean) chain.getArg(0);
                if (expanding && !active.containsKey(container) && classic.getAsBoolean() && !failed) {
                    try {
                        if (!(Boolean) call(container, "isBundle") &&
                                !(Boolean) call(container, "showingAsLowPriority")) {
                            Object parent = get(container, "mContainingNotification");
                            if (!(Boolean) call(parent, "shouldShowPublic") && !bool(parent, "mOnKeyguard")) {
                                Session session = new Session(container);
                                active.put(container, session);
                                ((View) container).addOnAttachStateChangeListener(session);
                                settled.remove(container);
                                session.cancelBackgroundAnimations();
                                Log.i(TAG, "Group drag started: children=" + session.rows.size());
                            }
                        }
                    } catch (UnsupportedOperationException ignored) {
                        // A single unmeasured child or a nested bundle keeps OEM behavior.
                    } catch (Throwable error) { fail(error); }
                }
                Object result = chain.proceed();
                if (!expanding) {
                    Session session = active.remove(container);
                    if (session != null) {
                        ((View) container).removeOnAttachStateChangeListener(session);
                        try {
                            call(get(container, "mContainingNotification"), "setGroupExpansionChanging", false);
                            settled.put(container, Boolean.TRUE);
                        } catch (Throwable error) { fail(error); }
                    }
                }
                return result;
            }));
            hooks.add(module.hook(method(containerType, "getGroupExpandFraction")).intercept(chain -> {
                Session session = active.get(chain.getThisObject());
                if (session != null && classic.getAsBoolean() && !failed) {
                    try { return session.fraction(chain.getThisObject()); }
                    catch (Throwable error) { fail(error); }
                }
                return chain.proceed();
            }));
            hooks.add(module.hook(method(containerType, "getMaxContentHeight")).intercept(chain -> {
                Session session = active.get(chain.getThisObject());
                if (session != null && classic.getAsBoolean() && !failed) return session.expandedHeight;
                return chain.proceed();
            }));
            hooks.add(module.hook(method(containerType, "updateState", expandableState)).intercept(chain -> {
                Object result = chain.proceed();
                Session session = active.get(chain.getThisObject());
                if (session != null && classic.getAsBoolean() && !failed) {
                    try { session.updateState(chain.getThisObject()); }
                    catch (Throwable error) { fail(error); }
                }
                return result;
            }));
            hooks.add(module.hook(method(containerType, "updateChildrenClipping")).intercept(chain -> {
                Object result = chain.proceed();
                Session session = active.get(chain.getThisObject());
                if (session != null && classic.getAsBoolean() && !failed) {
                    try { session.clip(chain.getThisObject()); }
                    catch (Throwable error) { fail(error); }
                }
                return result;
            }));
            Method apply = method(stateType, "applyToView", View.class);
            hooks.add(module.hook(apply).intercept(chain -> {
                Object result = chain.proceed();
                Object row = chain.getArg(0);
                if (rowType.isInstance(row)) {
                    try {
                        Object container = get(row, "mChildrenContainer");
                        Session session = active.get(container);
                        if (session != null && classic.getAsBoolean() && !failed) {
                            session.applyAppearance(container);
                        }
                    } catch (Throwable error) { fail(error); }
                }
                return result;
            }));
            hooks.add(module.hook(method(stateType, "animateTo", View.class, properties)).intercept(chain -> {
                Object row = chain.getArg(0);
                if (rowType.isInstance(row) && classic.getAsBoolean() && !failed) {
                    Object container = get(row, "mChildrenContainer");
                    if (active.containsKey(container) || settled.remove(container) != null) {
                        // ExpandHelper already animates the height. Do not start a second
                        // independent Xiaomi group animation while applying that height.
                        apply.invoke(chain.getThisObject(), row);
                        return null;
                    }
                }
                return chain.proceed();
            }));
            hooks.add(module.hook(method(contentType, "selectLayout", boolean.class, boolean.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (!classic.getAsBoolean() || failed || active.isEmpty()) return result;
                try {
                    Object content = chain.getThisObject();
                    Object row = get(content, "mContainingNotification");
                    Object parent = row instanceof View ? ((View) row).getParent() : null;
                    Session session = active.get(parent);
                    if (session != null) session.transformContent(content, row);
                } catch (Throwable error) { fail(error); }
                return result;
            }));
            // These methods can be inlined into the per-frame state update and row setters.
            for (Method m : containerType.getDeclaredMethods()) {
                if (m.getName().equals("setActualHeight") || m.getName().equals("getIntrinsicHeightAosp")) module.deoptimize(m);
            }
            for (Method m : rowType.getDeclaredMethods()) {
                if (m.getName().equals("setActualHeight") || m.getName().equals("setUserSwipingToExpandRow") ||
                        m.getName().equals("getMaxContentHeight")) module.deoptimize(m);
            }
            Log.i(TAG, "Installed classic group geometry and native content transforms");
        } catch (Throwable error) {
            for (HookHandle hook : hooks) hook.unhook();
            throw error;
        }
    }

    private final class Session implements View.OnAttachStateChangeListener {
        final List<Object> rows;
        final int count, visible, collapsedHeight, expandedHeight;
        final int[] compact, expanded;
        final float compactTop, expandedTop, compactGap, expandedGap, visibleExpandedHeight;

        Session(Object container) throws Exception {
            rows = new ArrayList<>((List<?>) get(container, "mAttachedChildren"));
            int maximum = field(containerType, "NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED").getInt(null);
            count = Math.min(rows.size(), maximum);
            if (count < 2) throw new UnsupportedOperationException("Group needs two children");
            visible = Math.min(count, (Integer) call(container, "getMaxAllowedVisibleChildren", true));
            collapsedHeight = (Integer) call(container, "getCollapsedHeight");
            compact = new int[count]; expanded = new int[count];
            for (int i = 0; i < count; i++) {
                Object row = rows.get(i);
                if (bool(row, "mIsSummaryWithChildren")) throw new UnsupportedOperationException("Nested group is not supported");
                Object content = call(row, "getShowingLayout");
                compact[i] = (Integer) call(content, "getViewHeight", 3, false);
                expanded[i] = (Boolean) call(row, "isExpanded$1") ?
                        (Integer) call(row, "getMaxExpandHeight") : (Integer) call(content, "getMinHeight", true);
                if (compact[i] <= 0 || expanded[i] < compact[i]) throw new UnsupportedOperationException("Child is not measured");
            }
            compactGap = integer(container, "mChildPadding");
            expandedGap = integer(container, "mDividerHeight");
            float margin = (Integer) call(container, "getCollapsedHeaderMargin") + integer(container, "mCurrentHeaderTranslation");
            compactTop = margin + integer(container, "mAdditionalExpandedHeaderMargin");
            boolean wasExpanded = bool(container, "mChildrenExpanded");
            int header;
            try {
                set(container, "mChildrenExpanded", true);
                header = (Integer) call(get(container, "mInjector"), "getGroupHeaderHeight");
            } finally { set(container, "mChildrenExpanded", wasExpanded); }
            expandedTop = margin + header + expandedGap;
            float full = expandedTop, first = expandedTop;
            for (int i = 0; i < count; i++) {
                full += expanded[i] + (i == 0 ? 0 : expandedGap);
                if (i < visible) first += expanded[i] + (i == 0 ? 0 : expandedGap);
            }
            expandedHeight = Math.round(full);
            visibleExpandedHeight = Math.max(collapsedHeight + 1, first);
        }

        float fraction(Object container) throws Exception {
            return clamp((integer(container, "mActualHeight") - collapsedHeight) /
                    (visibleExpandedHeight - collapsedHeight));
        }

        void updateState(Object container) throws Exception {
            float progress = fraction(container);
            float y = mix(compactTop, expandedTop, progress);
            float gap = mix(compactGap, expandedGap, progress);
            float bottom = integer(container, "mActualHeight");
            for (int i = 0; i < rows.size(); i++) {
                Object state = call(rows.get(i), "getViewState");
                if (i >= count) { alpha(state, 0); continue; }
                int height = i < visible ? Math.round(mix(compact[i], expanded[i], progress)) : expanded[i];
                set(state, "height", height);
                call(state, "setYTranslation", TAG, y);
                call(state, "setScaleX", 1.0f); call(state, "setScaleY", 1.0f);
                float shown = i < visible ? 1 : clamp((bottom - y) / Math.max(1, height)) * Math.min(1, progress * 4);
                alpha(state, shown);
                set(state, "hidden", shown == 0);
                y += height + gap;
            }
            Object injector = get(container, "mInjector");
            Object header = get(injector, "mGroupHeaderViewState");
            View headerView = (View) get(injector, "mMiuiGroupHeader");
            if (header != null && headerView != null) {
                int topId = ((View) container).getResources().getIdentifier("notification_group_header_top_padding_miui", "dimen", "com.android.systemui");
                float top = topId == 0 ? 0 : ((View) container).getResources().getDimensionPixelSize(topId);
                call(header, "setYTranslation", TAG, mix(integer(container, "mAdditionalExpandedHeaderMargin") + headerView.getMeasuredHeight(), top, progress));
                alpha(header, progress);
            }
            for (String name : new String[]{"mAppIconViewState", "mGroupNumberFlViewState"}) {
                Object state = get(injector, name);
                if (state != null) {
                    alpha(state, 1 - progress);
                    call(state, "setYTranslation", TAG, expandedTop * progress);
                }
            }
        }

        void clip(Object container) throws Exception {
            int bottom = (Integer) call(get(container, "mContainingNotification"), "getActualHeight") - integer(container, "mClipBottomAmount");
            for (int i = 0; i < count; i++) {
                View row = (View) rows.get(i);
                int height = (Integer) call(row, "getActualHeight");
                int clipped = Math.min(height, Math.max(0, Math.round(row.getTranslationY() + height - bottom)));
                call(row, "setClipBottomAmount", clipped);
            }
        }

        void applyAppearance(Object container) throws Exception {
            float progress = fraction(container);
            Object parent = get(container, "mContainingNotification");
            View summaryBackground = background(parent);
            summaryBackground.setVisibility(progress >= 1 ? View.INVISIBLE : View.VISIBLE);
            summaryBackground.setAlpha(1 - progress);
            for (int i = 0; i < count; i++) {
                Object row = rows.get(i);
                View bg = background(row);
                bg.setVisibility(View.VISIBLE);
                bg.setAlpha(i < visible ? progress : 1);
                bg.setScaleX(1); bg.setScaleY(1);
                call(bg, "setActualHeight", (Integer) call(row, "getActualHeight"));
                transformContent(call(row, "getPrivateLayout"), row);
            }
            clip(container);
        }

        void transformContent(Object content, Object row) throws Exception {
            int index = rows.indexOf(row);
            if (index < 0 || index >= count || content != call(row, "getPrivateLayout")) return;
            int targetType = (Boolean) call(row, "isExpanded$1") ? 1 : 0;
            View single = (View) call(content, "getViewForVisibleType", 3);
            View target = (View) call(content, "getViewForVisibleType", targetType);
            if (single == null || target == null) return;
            float p = index < visible ? clamp(((Integer) call(row, "getActualHeight") - compact[index]) /
                    (float) Math.max(1, expanded[index] - compact[index])) : 1;
            Object from = call(content, "getTransformableViewForVisibleType", 3);
            Object to = call(content, "getTransformableViewForVisibleType", targetType);
            if (from == null || to == null) return;
            call(to, "transformFrom", p, from);
            call(from, "transformTo", p, to);
            single.setVisibility(p < 1 ? View.VISIBLE : View.INVISIBLE);
            target.setVisibility(p > 0 ? View.VISIBLE : View.INVISIBLE);
        }

        void cancelBackgroundAnimations() throws Exception {
            for (Object row : rows) {
                View bg = background(row);
                for (View view : new View[]{bg, (View) call(call(row, "getPrivateLayout"), "getContractedChild")}) {
                    if (view == null) continue;
                    for (String name : new String[]{"alpha_animator_tag", "scale_x_animator_tag", "scale_y_animator_tag"}) {
                        int id = view.getResources().getIdentifier(name, "id", "com.android.systemui");
                        if (id != 0 && view.getTag(id) instanceof Animator) ((Animator) view.getTag(id)).cancel();
                    }
                }
            }
        }

        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) {
            active.remove(view); settled.remove(view); view.removeOnAttachStateChangeListener(this);
        }
    }

    private View background(Object row) throws Exception { return (View) call(call(row, "getInjector"), "getAnimatedBackground"); }
    private void alpha(Object state, float value) throws Exception { call(state, "setAlpha", TAG, value); }
    private static float mix(float a, float b, float p) { return a + (b - a) * p; }
    private static float clamp(float p) { return Math.max(0, Math.min(1, p)); }

    private void fail(Throwable error) {
        if (!failed) Log.e(TAG, "Group adaptation disabled; native behavior retained", error);
        failed = true;
    }

    private boolean bool(Object object, String name) throws Exception { return (Boolean) get(object, name); }
    private int integer(Object object, String name) throws Exception { return ((Number) get(object, name)).intValue(); }
    private Object get(Object object, String name) throws Exception { return field(object.getClass(), name).get(object); }
    private void set(Object object, String name, Object value) throws Exception { field(object.getClass(), name).set(object, value); }
    private Field field(Class<?> type, String name) throws Exception {
        String key = type.getName() + '#' + name;
        Field found = fields.get(key);
        if (found != null) return found;
        for (Class<?> cls = type; cls != null; cls = cls.getSuperclass()) {
            try {
                found = cls.getDeclaredField(name); found.setAccessible(true); fields.put(key, found); return found;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(key);
    }
    private Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method;
    }
    private Object call(Object object, String name, Object... args) throws Exception {
        StringBuilder key = new StringBuilder(object.getClass().getName()).append('#').append(name);
        for (Object arg : args) key.append(':').append(arg == null ? "null" : arg.getClass().getName());
        Method cached = methods.get(key.toString());
        if (cached == null) {
            outer: for (Class<?> cls = object.getClass(); cls != null; cls = cls.getSuperclass()) {
                candidates: for (Method candidate : cls.getDeclaredMethods()) {
                    if (!candidate.getName().equals(name) || candidate.getParameterCount() != args.length) continue;
                    Class<?>[] types = candidate.getParameterTypes();
                    for (int i = 0; i < types.length; i++) {
                        Class<?> t = types[i];
                        if (t == int.class) t = Integer.class;
                        else if (t == boolean.class) t = Boolean.class;
                        else if (t == float.class) t = Float.class;
                        if (args[i] != null && !t.isInstance(args[i])) continue candidates;
                    }
                    cached = candidate; cached.setAccessible(true); methods.put(key.toString(), cached); break outer;
                }
            }
            if (cached == null) throw new NoSuchMethodException(key.toString());
        }
        return cached.invoke(object, args);
    }
}
