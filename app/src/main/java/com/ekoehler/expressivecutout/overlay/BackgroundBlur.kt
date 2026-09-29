package com.ekoehler.expressivecutout.overlay

import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt

/**
 * Real blur of whatever sits behind the island, drawn with the framework's own
 * `BackgroundBlurDrawable` — the cross-window blur SystemUI uses for its rounded panels. The
 * compositor does the blurring, so the app never reads the pixels underneath.
 *
 * The drawable is a hidden API, reached through reflection (hidden-API checks are already lifted in
 * [com.ekoehler.expressivecutout.ExpressiveCutoutApp]). Where it is missing, or the user has turned
 * window blurs off, the modifier draws nothing and the island keeps its solid fill.
 *
 * @param strength 0 for no blur, 1 for [radiusDp] at full strength; animated by the caller.
 * @param cornerRadiusPx the island's current corner radius, so the blur follows its rounded shape.
 */
@Composable
internal fun Modifier.backgroundBlur(strength: Float, radiusDp: Int, cornerRadiusPx: Float): Modifier {
    val view = LocalView.current
    val blur = remember(view) { BlurHandle(view) }
    return drawBehind {
        val drawable = blur.drawable() ?: return@drawBehind
        val radiusPx = (radiusDp * density * strength).roundToInt()
        if (radiusPx <= 0) return@drawBehind
        blur.setRadius(radiusPx)
        blur.setCorner(cornerRadiusPx)
        drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}

/**
 * Lazily creates the blur drawable from the view's `ViewRootImpl` once the view is attached, and
 * caches the reflective setters. A failed lookup is remembered so it is not retried every frame.
 */
private class BlurHandle(private val view: View) {

    private var drawable: Drawable? = null
    private var failed = false
    private var lastRadius = -1
    private var lastCorner = -1f

    /** The drawable, created on first use; null when the platform can't provide one. */
    fun drawable(): Drawable? {
        if (drawable != null || failed) return drawable
        drawable = runCatching {
            val root = View::class.java.getMethod("getViewRootImpl").invoke(view)
                ?: return null
            root.javaClass.getMethod("createBackgroundBlurDrawable").invoke(root) as Drawable
        }.onFailure {
            Log.w(TAG, "Background blur unavailable", it)
            failed = true
        }.getOrNull()
        return drawable
    }

    /** Sets the blur radius in pixels, skipping the reflective call when it hasn't changed. */
    fun setRadius(px: Int) {
        if (px == lastRadius) return
        lastRadius = px
        call("setBlurRadius", Int::class.javaPrimitiveType!!, px)
    }

    /** Sets one corner radius for all four corners, skipping it when unchanged. */
    fun setCorner(px: Float) {
        if (px == lastCorner) return
        lastCorner = px
        call("setCornerRadius", Float::class.javaPrimitiveType!!, px)
    }

    /** Invokes a one-argument setter on the drawable; failures disable blur rather than crash. */
    private fun call(name: String, type: Class<*>, arg: Any) {
        val d = drawable ?: return
        runCatching { d.javaClass.getMethod(name, type).invoke(d, arg) }.onFailure {
            Log.w(TAG, "Background blur setter $name failed", it)
            failed = true
            drawable = null
        }
    }

    private companion object {
        const val TAG = "IslandBlur"
    }
}
