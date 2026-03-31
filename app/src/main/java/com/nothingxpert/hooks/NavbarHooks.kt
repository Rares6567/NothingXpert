package com.nothingxpert.hooks

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.inputmethodservice.InputMethodService
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.nothingxpert.HookEntry
import com.nothingxpert.XPrefs
import java.util.concurrent.atomic.AtomicBoolean

class NavbarHooks : BaseHook() {
    override val tag = "Navbar"
    
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val imeDumpOnce = AtomicBoolean(false)
    private val imePrefsInitOnce = AtomicBoolean(false)
    
    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            SYSTEMUI_PKG -> installHideNavbarHook(lpparam)
            HookEntry.GBOARD_PKG -> installHideImeBarHook(lpparam)
        }
    }

    private fun installHideNavbarHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ENABLE_HIDE_NAVBAR) return

        // Try to find NavigationBarView class across different Android versions
        val navBarViewClass = NAV_BAR_VIEW_CLASSES.firstNotNullOfOrNull { className ->
            try { XposedHelpers.findClass(className, lpparam.classLoader) }
            catch (_: Throwable) { null }
        } ?: run {
            log("Could not find NavigationBarView class")
            return
        }
        
        safeHook("NavigationBarView.updateNavButtonIcons") {
            XposedHelpers.findAndHookMethod(
                navBarViewClass,
                "updateNavButtonIcons",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ENABLE_HIDE_NAVBAR) return
                        val view = param.thisObject as? ViewGroup ?: return
                        hideHomeHandle(view, "updateNavButtonIcons")
                    }
                }
            )
        }
        
        safeHook("NavigationBarView.updateStates") {
            XposedHelpers.findAndHookMethod(
                navBarViewClass,
                "updateStates",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ENABLE_HIDE_NAVBAR) return
                        val view = param.thisObject as? ViewGroup ?: return
                        hideHomeHandle(view, "updateStates")
                    }
                }
            )
        }
        
        safeHook("NavigationBarView.onLayout") {
            XposedHelpers.findAndHookMethod(
                navBarViewClass,
                "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ENABLE_HIDE_NAVBAR) return
                        val view = param.thisObject as? ViewGroup ?: return
                        hideHomeHandle(view, "onLayout")
                    }
                }
            )
        }
    }
    
    private fun installHideImeBarHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        safeHook("InputMethodService universal IME bar hooks") {
            val imsClass = InputMethodService::class.java

            XposedHelpers.findAndHookMethod(
                imsClass,
                "onWindowShown",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ims = param.thisObject
                        maybeInitImePrefs(ims)
                        if (!isHideImeBarEnabled()) return
                        log("onWindowShown called, class: ${ims.javaClass.name}")
                        handler.post {
                            hideImeSwitcher(ims)
                            ensureImeLayoutWatcher(ims)
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                imsClass,
                "onStartInputView",
                EditorInfo::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ims = param.thisObject
                        maybeInitImePrefs(ims)
                        if (!isHideImeBarEnabled()) return
                        log("onStartInputView called, class: ${ims.javaClass.name}")
                        handler.post {
                            hideImeSwitcher(ims)
                            ensureImeLayoutWatcher(ims)
                        }
                    }
                }
            )

            try {
                XposedHelpers.findAndHookMethod(
                    imsClass,
                    "onComputeInsets",
                    InputMethodService.Insets::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            maybeInitImePrefs(param.thisObject)
                            if (!isHideImeBarEnabled()) return
                            try {
                                val insets = param.args[0]
                                XposedHelpers.setIntField(
                                    insets,
                                    "contentTopInsets",
                                    XposedHelpers.getIntField(insets, "visibleTopInsets")
                                )
                                XposedHelpers.setIntField(insets, "touchableInsets", 0)
                            } catch (t: Throwable) {
                                log("Failed to adjust IME insets: $t")
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                log("onComputeInsets hook failed: ${t.message}")
            }

            log("IME bar hider installed using InputMethodService base hooks")
        }

        safeHook("Gboard InputView padding override") {
            val inputViewClass = XposedHelpers.findClass(
                "com.google.android.libraries.inputmethod.inputview.InputView",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setPadding",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        maybeInitImePrefs(param.thisObject)
                        if (!isHideImeBarEnabled()) return
                        if (!inputViewClass.isInstance(param.thisObject)) return
                        val bottom = param.args[3] as? Int ?: return
                        if (bottom == 0) return
                        param.args[3] = 0
                        log("Forced Gboard InputView bottom padding from $bottom to 0")
                    }
                }
            )
            log("Gboard InputView padding override installed")
        }
    }

    private fun maybeInitImePrefs(ims: Any?) {
        if (BaseHook.useRemotePrefs) return
        val context = ims as? Context ?: return
        try {
            XPrefs.init(context.applicationContext)
            BaseHook.useRemotePrefs = true
            if (imePrefsInitOnce.compareAndSet(false, true)) {
                log("Initialized RemotePreferences in IME process ${ims.javaClass.name}")
            }
        } catch (t: Throwable) {
            if (imePrefsInitOnce.compareAndSet(false, true)) {
                log("Failed to initialize RemotePreferences in IME process: $t")
            }
        }
    }

    private fun ensureImeLayoutWatcher(ims: Any) {
        try {
            val dialog = XposedHelpers.callMethod(ims, "getWindow") as? android.app.Dialog ?: return
            val decor = dialog.window?.decorView ?: return
            val existing = XposedHelpers.getAdditionalInstanceField(decor, IME_LAYOUT_WATCHER_KEY)
            if (existing != null) return

            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                if (!isHideImeBarEnabled()) return@OnGlobalLayoutListener
                hideImeSwitcher(ims)
            }
            decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
            XposedHelpers.setAdditionalInstanceField(decor, IME_LAYOUT_WATCHER_KEY, listener)
            decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    val stored = XposedHelpers.getAdditionalInstanceField(v, IME_LAYOUT_WATCHER_KEY)
                        as? ViewTreeObserver.OnGlobalLayoutListener
                    if (stored != null && v.viewTreeObserver.isAlive) {
                        v.viewTreeObserver.removeOnGlobalLayoutListener(stored)
                    }
                    XposedHelpers.removeAdditionalInstanceField(v, IME_LAYOUT_WATCHER_KEY)
                    v.removeOnAttachStateChangeListener(this)
                }
            })
        } catch (t: Throwable) {
            log("Failed to install IME layout watcher: $t")
        }
    }

    private fun hideImeSwitcher(ims: Any) {
        try {
            val dialog = XposedHelpers.callMethod(ims, "getWindow") as? android.app.Dialog ?: run {
                return
            }
            val window = dialog.window ?: run {
                return
            }
            val decor = window.decorView
            val ids = listOf(
                "input_method_nav_bar",
                "input_method_nav_back",
                "input_method_nav_ime_switcher",
                "input_method_nav_home_handle",
                "input_method_nav_gesture"
            )
            var hiddenAny = false
            for (name in ids) {
                val id = decor.resources.getIdentifier(name, "id", "android")
                if (id == 0) continue
                val v = decor.findViewById<View>(id) ?: continue
                v.visibility = View.GONE
                v.alpha = 0f
                v.layoutParams?.let { lp -> lp.height = 0 }
                (v.parent as? ViewGroup)?.requestLayout()
                zeroBottomPaddingUp(v)
                hiddenAny = true
            }
            if (hiddenAny) {
                log("IME nav bar hidden for ${ims.javaClass.name}")
            }
            if (hideImeNavBarClasses(decor)) {
                hiddenAny = true
            }
            zeroBottomPaddingUp(decor, 6)
            adjustImeInputViewPadding(decor)
            decor.post {
                hideImeNavBarClasses(decor)
                adjustImeInputViewPadding(decor)
                stripImeBottomInset(decor)
            }
            if (imeDumpOnce.compareAndSet(false, true)) {
                decor.postDelayed({ dumpImeBottomViews(decor) }, 250)
            }
            decor.requestLayout()
        } catch (t: Throwable) {
            log("Failed to hide IME nav bar: $t")
        }
    }

    private fun hideImeNavBarClasses(root: View): Boolean {
        var changed = false
        fun visit(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    visit(v.getChildAt(i))
                }
            }
            val className = v.javaClass.name
            val isImeNavBar = className.contains("inputmethodservice.navigationbar.NavigationBarFrame") ||
                className.endsWith(".NavigationBarFrame") ||
                className.contains("inputmethodservice.navigationbar.NavigationBarView") ||
                className.endsWith(".NavigationBarView")
            if (!isImeNavBar) return

            if (v.visibility != View.GONE) {
                v.visibility = View.GONE
                changed = true
            }
            if (v.alpha != 0f) {
                v.alpha = 0f
                changed = true
            }
            val lp = v.layoutParams
            if (lp != null && lp.height != 0) {
                lp.height = 0
                v.layoutParams = lp
                changed = true
            }
            if (v.paddingBottom != 0) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                changed = true
            }
        }
        visit(root)
        if (changed) {
            root.requestLayout()
        }
        return changed
    }
    
    private fun hideHomeHandle(navBarView: ViewGroup, source: String) {
        val handle = navBarView.findViewById<View?>(navBarView.resources.getIdentifier("home_handle", "id", "com.android.systemui"))
            ?: navBarView.findViewById<View?>(navBarView.resources.getIdentifier("home_handle", "id", "com.android.systemui.res"))
        if (handle != null) {
            var changed = false
            if (handle.visibility != View.GONE) {
                handle.visibility = View.GONE
                changed = true
            }
            if (handle.alpha != 0f) {
                handle.alpha = 0f
                changed = true
            }
            val lp = handle.layoutParams
            if (lp != null && lp.height != 0) {
                lp.height = 0
                handle.layoutParams = lp
                changed = true
            }
            if (changed) {
                (handle.parent as? ViewGroup)?.requestLayout()
                log("Hiding home_handle from $source")
            }
        }
    }
    
    private fun zeroBottomPaddingUp(view: View?, depth: Int = 3) {
        var current: Any? = view
        repeat(depth) {
            val v = current as? View ?: return
            if (v.paddingBottom != 0) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
            }
            current = v.parent
        }
    }
    
    private fun stripImeBottomInset(root: View) {
        val navBarHeight = getNavBarHeight(root) ?: return
        if (root.height <= 0) return
        var changed = false
        fun visit(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    visit(v.getChildAt(i))
                }
            }
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()?.lowercase()
            val lp = v.layoutParams
            val heightMatches = v.height == navBarHeight || lp?.height == navBarHeight
            val nearBottom = v.bottom >= (root.height - navBarHeight - 4)
            val nameMatches = name?.contains("nav") == true ||
                name?.contains("gesture") == true ||
                name?.contains("ime") == true ||
                name?.contains("inset") == true ||
                name?.contains("bar") == true
            val isSpacer = v.javaClass.name.endsWith("Space")
            val isInputView = v.javaClass.name.contains("inputview.InputView")
            if (isInputView && v.paddingBottom != 0) {
                if (adjustImeInputViewPadding(v)) {
                    changed = true
                }
                return
            }
            if (nearBottom && (heightMatches || nameMatches || isSpacer)) {
                v.visibility = View.GONE
                v.alpha = 0f
                if (lp != null && lp.height != 0) {
                    lp.height = 0
                    v.layoutParams = lp
                }
                if (lp is ViewGroup.MarginLayoutParams && lp.bottomMargin != 0) {
                    lp.bottomMargin = 0
                    v.layoutParams = lp
                }
                if (v.paddingBottom != 0) {
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                }
                changed = true
            }
        }
        visit(root)
        if (root.paddingBottom != 0) {
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
            changed = true
        }
        if (changed) {
            root.requestLayout()
        }
    }
    
    private fun adjustImeInputViewPadding(root: View): Boolean {
        var changed = false
        fun visit(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    visit(v.getChildAt(i))
                }
            }
            val className = v.javaClass.name
            val isInputView = className.contains("inputview.InputView")
            if (isInputView && v.paddingBottom != 0) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                changed = true
            }
        }
        visit(root)
        if (changed) {
            root.requestLayout()
        }
        return changed
    }
    
    private fun dumpImeBottomViews(root: View) {
        val navBarHeight = getNavBarHeight(root) ?: return
        if (root.height <= 0) return
        val maxLogs = 30
        var logs = 0
        fun visit(v: View) {
            if (logs >= maxLogs) return
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    visit(v.getChildAt(i))
                    if (logs >= maxLogs) return
                }
            }
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
            val lp = v.layoutParams
            val bottomMargin = (lp as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            val nearBottom = v.bottom >= (root.height - navBarHeight - 4)
            val heightMatches = v.height == navBarHeight || lp?.height == navBarHeight
            val paddingBottom = v.paddingBottom
            if (nearBottom && (heightMatches || bottomMargin > 0 || paddingBottom > 0)) {
                log("IME bottom view name=$name class=${v.javaClass.name} h=${v.height} bottom=${v.bottom} padB=$paddingBottom marginB=$bottomMargin")
                logs++
            }
        }
        visit(root)
        log("IME bottom view dump done (count=$logs)")
    }
    
    private fun getNavBarHeight(view: View): Int? {
        val res = view.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        if (id == 0) return null
        return runCatching { res.getDimensionPixelSize(id) }.getOrNull()
    }
    
    private fun isHideImeBarEnabled() = getPreferenceBoolean(PREF_HIDE_IME_BAR, false)
    
    companion object {
        // Class names for finding NavigationBarView across different Android versions
        private val NAV_BAR_VIEW_CLASSES = listOf(
            "com.android.systemui.navigationbar.views.NavigationBarView",
            "com.android.systemui.navigationbar.NavigationBarView",
            "com.android.systemui.statusbar.phone.NavigationBarView"
        )

        // Class names for GBoard InputMethodService hierarchy
        private val GBOARD_INPUT_CLASSES = listOf(
            "com.android.inputmethod.latin.LatinIME",
            "dyh",  // Obfuscated parent
            "moa",  // Obfuscated parent that extends InputMethodService
            "android.inputmethodservice.InputMethodService"
        )

        private const val PREF_HIDE_IME_BAR = "pref_hide_ime_bar"
        const val ENABLE_HIDE_NAVBAR = true
        private const val IME_LAYOUT_WATCHER_KEY = "nothingxpert_ime_layout_watcher"
    }
}
