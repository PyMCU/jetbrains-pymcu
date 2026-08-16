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

    /**
     * The monochrome snake: tool window, run configurations, library nodes.
     * The platform draws these next to its own glyphs and expects one colour.
     */
    @JvmField
    val PyMcu: Icon = IconLoader.getIcon("/icons/pymcu.svg", PyMcuIcons::class.java)

    /**
     * The full-colour project logo, for the places that present a brand rather
     * than a glyph — currently the New Project wizard.
     *
     * These are deliberately two icons. Using the mono glyph as the wizard logo
     * put a small grey squiggle where every other entry shows a recognisable
     * product mark, and scaled it past the size it was drawn for.
     */
    @JvmField
    val Logo: Icon = IconLoader.getIcon("/icons/pymcuLogo.svg", PyMcuIcons::class.java)
}
