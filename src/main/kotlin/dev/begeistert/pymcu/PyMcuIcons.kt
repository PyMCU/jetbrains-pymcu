package dev.begeistert.pymcu

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Plugin icons.
 *
 * [IconLoader.getIcon] resolves `_dark` variants automatically, so only the
 * light SVG needs naming here.
 */
object PyMcuIcons {
    /** 13×13 tool-window and run-configuration icon. */
    @JvmField
    val PyMcu: Icon = IconLoader.getIcon("/icons/pymcu.svg", PyMcuIcons::class.java)
}
