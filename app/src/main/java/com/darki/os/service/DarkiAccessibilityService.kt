package com.darki.os.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Servicio de Accesibilidad de DARKI: son sus "ojos y manos" sobre el
 * telefono. Se activa manualmente desde Ajustes > Accesibilidad porque
 * Android no permite pedir este permiso con un dialogo estandar.
 *
 * Limitacion real de Android: solo puede interactuar con lo que este
 * dibujado en pantalla y sea parte del arbol de accesibilidad. No puede
 * tocar por encima de apps que bloquean accesibilidad a proposito
 * (ej. pantallas de pago bancarias), ni cambiar ajustes protegidos sin
 * root. Para eso no hay alternativa desde una app normal.
 */
class DarkiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: DarkiAccessibilityService? = null

        fun isRunning(): Boolean = instance != null
        fun get(): DarkiAccessibilityService? = instance
    }

    @Volatile
    var currentPackageName: String = ""
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) currentPackageName = pkg
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    /** Descripcion en texto de lo que hay en pantalla, usada para que Claude "vea". */
    fun dumpScreen(maxNodes: Int = 160): String {
        val root = rootInActiveWindow
            ?: return "App activa: $currentPackageName\n(no se pudo leer el contenido de la pantalla)"

        val sb = StringBuilder("App activa: $currentPackageName\n")
        var count = intArrayOf(0)

        fun walk(node: AccessibilityNodeInfo) {
            if (count[0] >= maxNodes) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = text.ifBlank { desc }

            if (label.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val tags = buildList {
                    if (node.isClickable) add("clickeable")
                    if (node.isEditable) add("editable")
                    if (node.isScrollable) add("scrollable")
                    if (node.isCheckable) add("checkable:${node.isChecked}")
                }.joinToString(",")
                val shown = label.ifBlank { "(sin texto)" }
                sb.append("- \"$shown\" [$tags] x=${bounds.centerX()} y=${bounds.centerY()}\n")
                count[0]++
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)
                child.recycle()
            }
        }

        walk(root)
        root.recycle()
        return sb.toString()
    }

    fun clickByText(text: String): Boolean {
        if (text.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(text)
        val ok = matches.firstOrNull()?.let { clickNodeOrAncestor(it) } ?: false
        root.recycle()
        return ok
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    fun typeIntoFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findFocusedEditable(root)
        if (target == null) {
            root.recycle()
            return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        root.recycle()
        return ok
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditable(child)
            if (found != null) return found
        }
        return null
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findScrollable(root)
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val ok = target?.performAction(action) ?: false
        root.recycle()
        return ok
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollable(child)
            if (found != null) return found
        }
        return null
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun quickSettings(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        } else {
            false
        }

    fun takeScreenshot(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }

    /** Abre una app por nombre de paquete exacto o por nombre visible aproximado. */
    fun openApp(nameOrPackage: String): Boolean {
        if (nameOrPackage.isBlank()) return false
        val pm = packageManager

        pm.getLaunchIntentForPackage(nameOrPackage)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return true
        }

        val installed = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        val match = installed.firstOrNull { appInfo ->
            pm.getApplicationLabel(appInfo).toString().contains(nameOrPackage, ignoreCase = true)
        } ?: return false

        val launchIntent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        return true
    }
}
