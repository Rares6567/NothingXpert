package com.nothingxpert.hooks

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

class NavbarHooks : BaseHook() {
    override val tag = "Navbar"
    
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val imeDumpOnce = AtomicBoolean(false)
    
    override fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            SYSTEMUI_PKG -> installHideNavbarHook(lpparam)
            GBOARD_PKG -> installHideImeBarHook(lpparam)
        }
    }
    
    fun installImeToggleReceiver() {
        if (imeReceiverRegistered) return
        val ctx = getSystemContext() ?: return
        try {
            val filter = android.content.IntentFilter(ACTION_IME_BAR_TOGGLED)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_IME_BAR_TOGGLED) return
                    forceStopPackage(ctx, GBOARD_PKG)
                }
            }
            ContextCompat.registerReceiver(
                ctx,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            imeReceiverRegistered = true
            log("IME toggle receiver registered")
        } catch (t: Throwable) {
            log("Failed to register IME toggle receiver: $t")
        }
    }
    
    private fun forceStopPackage(context: Context, pkg: String) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return
            val method = am.javaClass.getMethod("forceStopPackage", String::class.java)
            method.invoke(am, pkg)
            log("force-stopped $pkg")
        } catch (t: Throwable) {
            log("force-stop failed for $pkg: $t")
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
        safeHook("InputMethodService.onWindowShown") {
            // Try to hook the actual GBoard implementation classes

            var hooked = false
            for (className in GBOARD_INPUT_CLASSES) {
                try {
                    val imsClass = XposedHelpers.findClass(className, lpparam.classLoader)

                    // Hook onWindowShown
                    XposedHelpers.findAndHookMethod(
                        imsClass,
                        "onWindowShown",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!isHideImeBarEnabled()) return
                                log("onWindowShown called, class: ${param.thisObject.javaClass.name}")
                                val ims = param.thisObject
                                handler.post { hideImeSwitcher(ims) }
                            }
                        }
                    )

                    // Hook onStartInputView
                    XposedHelpers.findAndHookMethod(
                        imsClass,
                        "onStartInputView",
                        EditorInfo::class.java,
                        Boolean::class.javaPrimitiveType,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!isHideImeBarEnabled()) return
                                log("onStartInputView called, class: ${param.thisObject.javaClass.name}")
                                val ims = param.thisObject
                                handler.post { hideImeSwitcher(ims) }
                            }
                        }
                    )

                    // Hook onComputeInsets to zero out the bottom insets
                    try {
                        XposedHelpers.findAndHookMethod(
                            imsClass,
                            "onComputeInsets",
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (!isHideImeBarEnabled()) return
                                    try {
                                        val insets = param.args[0]
                                        // Set contentTopInsets to visibleTopInsets to remove the bottom gap
                                        XposedHelpers.setIntField(insets, "contentTopInsets",
                                            XposedHelpers.getIntField(insets, "visibleTopInsets"))
                                        XposedHelpers.setIntField(insets, "touchableInsets", 0) // INSETS_TOUCHABLE_INNER
                                        log("Adjusted onComputeInsets")
                                    } catch (t: Throwable) {
                                        log("Failed to adjust insets: $t")
                                    }
                                }
                            }
                        )
                    } catch (t: Throwable) {
                        log("onComputeInsets hook failed (may not exist in this class): ${t.message}")
                    }

                    log("IME bar hider hooked into $className")
                    hooked = true
                    break  // Successfully hooked, no need to try other classes
                } catch (t: Throwable) {
                    log("Failed to hook $className: ${t.message}")
                }
            }

            if (!hooked) {
                log("Failed to hook any InputMethodService class!")
            } else {
                log("IME bar hider installed successfully")
            }
        }
    }
    
    private fun hideImeSwitcher(ims: Any) {
        try {
            log("hideImeSwitcher called for: ${ims.javaClass.name}")
            val dialog = XposedHelpers.callMethod(ims, "getWindow") as? android.app.Dialog ?: run {
                log("getWindow returned null or not a Dialog")
                return
            }
            val window = dialog.window ?: run {
                log("dialog.window returned null")
                return
            }
            val decor = window.decorView
            log("decorView: ${decor.javaClass.name}")
            val ids = listOf("input_method_nav_bar", "input_method_nav_back", "input_method_nav_ime_switcher")
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
                log("IME nav bar hidden")
            }
            zeroBottomPaddingUp(decor, 6)
            adjustImeInputViewPadding(decor)
            decor.post {
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
        val navBarHeight = getNavBarHeight(root) ?: 0
        val minPad = dpToPx(root, 8)
        val target = if (navBarHeight > 0) {
            kotlin.math.max(minPad, navBarHeight / 4)
        } else {
            minPad
        }
        fun visit(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    visit(v.getChildAt(i))
                }
            }
            val className = v.javaClass.name
            val isInputView = className.contains("inputview.InputView")
            if (isInputView && v.paddingBottom > target) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, target)
                changed = true
            }
        }
        visit(root)
        if (changed) {
            root.requestLayout()
        }
        return changed
    }
    
    private fun dpToPx(view: View, dp: Int): Int {
        val density = view.resources.displayMetrics.density
        return (dp * density).toInt().coerceAtLeast(1)
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

        private const val GBOARD_PKG = "com.google.android.inputmethod.latin"
        private const val PREF_HIDE_IME_BAR = "pref_hide_ime_bar"
        private const val ACTION_IME_BAR_TOGGLED = "com.nothingxpert.action.IME_BAR_TOGGLED"
        const val ENABLE_HIDE_NAVBAR = true

        @Volatile var imeReceiverRegistered = false
    }
}
