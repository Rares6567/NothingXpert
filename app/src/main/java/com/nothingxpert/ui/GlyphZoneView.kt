package com.nothingxpert.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.graphics.PathParser
import com.nothingxpert.R
import org.xmlpull.v1.XmlPullParser
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive glyph zone picker view.
 *
 * Phone 2 geometry uses official vector paths and placement extracted from NothingSettings.
 */
class GlyphZoneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class DeviceType {
        PHONE_1,
        PHONE_2
    }

    var deviceType: DeviceType = DeviceType.PHONE_2
        set(value) {
            if (field == value) return
            field = value
            val validZoneIds = currentZoneSpecs().map { it.id }.toSet()
            if (selectedZoneIds.retainAll(validZoneIds)) {
                onZoneSelectionChanged?.invoke(selectedZoneIds.toSet())
            }
            rebuildGeometry()
            invalidate()
        }

    var onZoneSelectionChanged: ((Set<String>) -> Unit)? = null

    private data class SegmentSpec(
        val id: String,
        @DrawableRes val vectorRes: Int,
        val width: Float,
        val height: Float,
        val left: Float? = null,
        val top: Float? = null,
        val right: Float? = null,
        val bottom: Float? = null,
        val centerHorizontal: Boolean = false
    ) {
        fun x(designWidth: Float): Float {
            return when {
                centerHorizontal -> (designWidth - width) / 2f
                left != null -> left
                right != null -> designWidth - right - width
                else -> 0f
            }
        }

        fun y(designHeight: Float): Float {
            return when {
                top != null -> top
                bottom != null -> designHeight - bottom - height
                else -> 0f
            }
        }
    }

    private data class BitmapSegmentSpec(
        val id: String,
        @DrawableRes val defaultRes: Int,
        @DrawableRes val selectedRes: Int,
        val width: Float,
        val height: Float,
        val left: Float? = null,
        val top: Float? = null,
        val right: Float? = null,
        val bottom: Float? = null,
        val centerHorizontal: Boolean = false
    ) {
        fun x(designWidth: Float): Float {
            return when {
                centerHorizontal -> (designWidth - width) / 2f
                left != null -> left
                right != null -> designWidth - right - width
                else -> 0f
            }
        }

        fun y(designHeight: Float): Float {
            return when {
                top != null -> top
                bottom != null -> designHeight - bottom - height
                else -> 0f
            }
        }
    }

    private data class ZoneSpec(
        val id: String,
        val segmentIds: List<String>
    )

    private data class VectorPathData(
        val viewportWidth: Float,
        val viewportHeight: Float,
        val pathData: String
    )

    private data class SegmentRenderData(
        val spec: SegmentSpec,
        val path: Path,
        val bounds: RectF
    )

    private data class BitmapSegmentRenderData(
        val spec: BitmapSegmentSpec,
        val defaultBitmap: Bitmap,
        val selectedBitmap: Bitmap,
        val drawRect: RectF
    )

    private data class ZoneRenderData(
        val id: String,
        val region: Region
    )

    private val selectedZoneIds = linkedSetOf<String>()
    private val renderSegments = mutableListOf<SegmentRenderData>()
    private val renderBitmapSegments = mutableListOf<BitmapSegmentRenderData>()
    private val renderZones = mutableListOf<ZoneRenderData>()
    private val renderTouchZones = mutableListOf<ZoneRenderData>()
    private val segmentToZone = mutableMapOf<String, String>()
    private val vectorCache = mutableMapOf<Int, VectorPathData>()
    private val bitmapCache = mutableMapOf<Int, Bitmap?>()
    private var bodyPath = Path()

    private val contentRect = RectF()
    private val drawMatrix = Matrix()
    private val pathMatrix = Matrix()
    private val tempRegion = Region()
    private val clipRegion = Region()

    private val bodyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#15181E")
    }

    private val bodyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.3f)
        color = Color.parseColor("#2C313A")
    }

    private val sideRailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.parseColor("#2C313A")
    }

    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = resolveColor(R.color.glyph_light_effect_preview_default_color, Color.parseColor("#CFD3DC"))
    }

    private val ledGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 54
    }

    private val touchPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        isClickable = true
        isFocusable = true
    }

    fun setSelectedZoneIds(zoneIds: Set<String>) {
        val validZoneIds = currentZoneSpecs().map { it.id }.toSet()
        val filtered = zoneIds.filterTo(linkedSetOf()) { it in validZoneIds }
        if (selectedZoneIds == filtered) return

        selectedZoneIds.clear()
        selectedZoneIds.addAll(filtered)
        onZoneSelectionChanged?.invoke(selectedZoneIds.toSet())
        invalidate()
    }

    fun getSelectedZoneIds(): Set<String> = selectedZoneIds.toSet()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (contentRect.isEmpty) return

        val designScale = contentRect.width() / currentDesignWidth()
        if (!bodyPath.isEmpty) {
            canvas.drawPath(bodyPath, bodyFillPaint)
            canvas.drawPath(bodyPath, bodyStrokePaint)
        } else {
            val cornerRadius = 24f * designScale
            canvas.drawRoundRect(contentRect, cornerRadius, cornerRadius, bodyFillPaint)
            canvas.drawRoundRect(contentRect, cornerRadius, cornerRadius, bodyStrokePaint)
        }

        if (deviceType == DeviceType.PHONE_2) {
            val railX = contentRect.right - 0.9f * designScale
            canvas.drawLine(
                railX,
                contentRect.top + 16f * designScale,
                railX,
                contentRect.top + 178f * designScale,
                sideRailPaint
            )
            canvas.drawLine(
                railX,
                contentRect.top + 250f * designScale,
                railX,
                contentRect.bottom - 16f * designScale,
                sideRailPaint
            )
        }

        if (renderBitmapSegments.isNotEmpty()) {
            for (segment in renderBitmapSegments) {
                val zoneId = segmentToZone[segment.spec.id] ?: continue
                val selected = selectedZoneIds.contains(zoneId)
                val bitmap = if (selected) segment.selectedBitmap else segment.defaultBitmap
                canvas.drawBitmap(bitmap, null, segment.drawRect, null)
            }
            return
        }

        for (segment in renderSegments) {
            val zoneId = segmentToZone[segment.spec.id] ?: continue
            val selected = selectedZoneIds.contains(zoneId)

            ledPaint.color = if (selected) {
                resolveColor(R.color.glyph_light_effect_preview_select_color, Color.WHITE)
            } else {
                resolveColor(R.color.glyph_light_effect_preview_default_color, Color.parseColor("#CFD3DC"))
            }
            ledPaint.alpha = if (selected) 255 else 170

            canvas.drawPath(segment.path, ledPaint)
            if (selected) {
                canvas.drawPath(segment.path, ledGlowPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val tapped = renderZones.lastOrNull { zone ->
                    zone.region.contains(event.x.toInt(), event.y.toInt())
                } ?: renderTouchZones.lastOrNull { zone ->
                    zone.region.contains(event.x.toInt(), event.y.toInt())
                } ?: return super.onTouchEvent(event)

                if (!selectedZoneIds.remove(tapped.id)) {
                    selectedZoneIds.add(tapped.id)
                }
                onZoneSelectionChanged?.invoke(selectedZoneIds.toSet())
                invalidate()
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun rebuildGeometry() {
        if (width <= 0 || height <= 0) return

        val designWidth = currentDesignWidth()
        val designHeight = currentDesignHeight()

        val availableWidth = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        val availableHeight = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        val scale = min(availableWidth / designWidth, availableHeight / designHeight)

        val contentWidth = designWidth * scale
        val contentHeight = designHeight * scale
        val left = paddingLeft + (availableWidth - contentWidth) / 2f
        val top = paddingTop + (availableHeight - contentHeight) / 2f

        contentRect.set(left, top, left + contentWidth, top + contentHeight)

        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(contentRect.left, contentRect.top)

        val bodyVector = vectorCache.getOrPut(R.drawable.bg_glyphs_led) { loadVectorPathData(R.drawable.bg_glyphs_led) }
        if (bodyVector.pathData.isNotEmpty()) {
            val sourceBodyPath = PathParser.createPathFromPathData(bodyVector.pathData)
            if (sourceBodyPath != null) {
                pathMatrix.reset()
                pathMatrix.postScale(
                    designWidth / bodyVector.viewportWidth.coerceAtLeast(1f),
                    designHeight / bodyVector.viewportHeight.coerceAtLeast(1f)
                )
                sourceBodyPath.transform(pathMatrix)

                bodyPath = Path(sourceBodyPath)
                bodyPath.transform(drawMatrix)
            }
        } else {
            bodyPath = Path()
        }

        val segmentSpecs = currentVectorSegmentSpecs()
        val bitmapSegmentSpecs = currentBitmapSegmentSpecs()
        val zoneSpecs = currentZoneSpecs()

        renderSegments.clear()
        renderBitmapSegments.clear()
        renderZones.clear()
        renderTouchZones.clear()
        segmentToZone.clear()
        clipRegion.set(0, 0, width, height)

        val segmentRegionById = LinkedHashMap<String, Region>(segmentSpecs.size + bitmapSegmentSpecs.size)
        val segmentTouchRegionById = LinkedHashMap<String, Region>(segmentSpecs.size + bitmapSegmentSpecs.size)
        val touchStrokeWidth = max(dp(16f), 18f * scale)

        for (spec in segmentSpecs) {
            val vector = vectorCache.getOrPut(spec.vectorRes) { loadVectorPathData(spec.vectorRes) }
            if (vector.pathData.isEmpty()) continue

            val sourcePath = PathParser.createPathFromPathData(vector.pathData) ?: continue

            pathMatrix.reset()
            pathMatrix.postScale(
                spec.width / vector.viewportWidth.coerceAtLeast(1f),
                spec.height / vector.viewportHeight.coerceAtLeast(1f)
            )
            pathMatrix.postTranslate(spec.x(designWidth), spec.y(designHeight))
            sourcePath.transform(pathMatrix)

            val drawPath = Path(sourcePath)
            drawPath.transform(drawMatrix)

            val bounds = RectF()
            drawPath.computeBounds(bounds, true)

            val renderData = SegmentRenderData(spec, drawPath, bounds)
            renderSegments.add(renderData)

            tempRegion.setPath(drawPath, clipRegion)
            segmentRegionById[spec.id] = Region(tempRegion)

            val touchPath = Path()
            touchPathPaint.strokeWidth = touchStrokeWidth
            touchPathPaint.getFillPath(drawPath, touchPath)
            tempRegion.setPath(touchPath, clipRegion)
            segmentTouchRegionById[spec.id] = Region(tempRegion)
        }

        for (spec in bitmapSegmentSpecs) {
            val defaultBitmap = loadBitmap(spec.defaultRes) ?: continue
            val selectedBitmap = loadBitmap(spec.selectedRes) ?: defaultBitmap

            val baseRect = RectF(
                spec.x(designWidth),
                spec.y(designHeight),
                spec.x(designWidth) + spec.width,
                spec.y(designHeight) + spec.height
            )
            val drawRect = RectF(baseRect)
            drawMatrix.mapRect(drawRect)

            renderBitmapSegments.add(
                BitmapSegmentRenderData(
                    spec = spec,
                    defaultBitmap = defaultBitmap,
                    selectedBitmap = selectedBitmap,
                    drawRect = drawRect
                )
            )

            val segmentRegion = buildBitmapRegion(defaultBitmap, drawRect)
            segmentRegionById[spec.id] = segmentRegion

            val expandedRect = RectF(drawRect)
            val expandBy = max(dp(12f), 14f * scale)
            expandedRect.inset(-expandBy, -expandBy)
            val touchRegion = rectToRegion(expandedRect)
            touchRegion.op(segmentRegion, Region.Op.UNION)
            segmentTouchRegionById[spec.id] = touchRegion
        }

        for (zone in zoneSpecs) {
            for (segmentId in zone.segmentIds) {
                segmentToZone[segmentId] = zone.id
            }

            val zoneRegion = Region()
            val zoneTouchRegion = Region()
            var initialized = false
            var touchInitialized = false

            for (segmentId in zone.segmentIds) {
                val segmentRegion = segmentRegionById[segmentId]
                if (segmentRegion != null) {
                    if (!initialized) {
                        zoneRegion.set(segmentRegion)
                        initialized = true
                    } else {
                        zoneRegion.op(segmentRegion, Region.Op.UNION)
                    }
                }

                val touchRegion = segmentTouchRegionById[segmentId]
                if (touchRegion != null) {
                    if (!touchInitialized) {
                        zoneTouchRegion.set(touchRegion)
                        touchInitialized = true
                    } else {
                        zoneTouchRegion.op(touchRegion, Region.Op.UNION)
                    }
                }
            }

            if (initialized) {
                renderZones.add(ZoneRenderData(zone.id, zoneRegion))
            }
            if (touchInitialized) {
                renderTouchZones.add(ZoneRenderData(zone.id, zoneTouchRegion))
            }
        }

        invalidate()
    }

    private fun currentVectorSegmentSpecs(): List<SegmentSpec> {
        return when (deviceType) {
            DeviceType.PHONE_1 -> emptyList()
            DeviceType.PHONE_2 -> PHONE_2_SEGMENTS
        }
    }

    private fun currentBitmapSegmentSpecs(): List<BitmapSegmentSpec> {
        return when (deviceType) {
            DeviceType.PHONE_1 -> PHONE_1_BITMAP_SEGMENTS
            DeviceType.PHONE_2 -> emptyList()
        }
    }

    private fun currentZoneSpecs(): List<ZoneSpec> {
        return when (deviceType) {
            DeviceType.PHONE_1 -> PHONE_1_ZONES
            DeviceType.PHONE_2 -> PHONE_2_ZONES
        }
    }

    private fun loadVectorPathData(@DrawableRes resId: Int): VectorPathData {
        val parser = resources.getXml(resId)
        var viewportWidth = 1f
        var viewportHeight = 1f
        var pathData = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "vector" -> {
                        viewportWidth = parser.getAttributeFloatValue(
                            ANDROID_NS,
                            "viewportWidth",
                            parser.getAttributeFloatValue(null, "viewportWidth", 1f)
                        )
                        viewportHeight = parser.getAttributeFloatValue(
                            ANDROID_NS,
                            "viewportHeight",
                            parser.getAttributeFloatValue(null, "viewportHeight", 1f)
                        )
                    }

                    "path" -> {
                        pathData = parser.getAttributeValue(ANDROID_NS, "pathData")
                            ?: parser.getAttributeValue(null, "pathData")
                            ?: ""
                        if (pathData.isNotEmpty()) {
                            break
                        }
                    }
                }
            }
            event = parser.next()
        }

        return VectorPathData(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            pathData = pathData
        )
    }

    private fun loadBitmap(@DrawableRes resId: Int): Bitmap? {
        return bitmapCache.getOrPut(resId) {
            try {
                BitmapFactory.decodeResource(resources, resId)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun rectToRegion(rect: RectF): Region {
        val left = rect.left.toInt()
        val top = rect.top.toInt()
        val right = max(left + 1, ceil(rect.right.toDouble()).toInt())
        val bottom = max(top + 1, ceil(rect.bottom.toDouble()).toInt())
        return Region(left, top, right, bottom)
    }

    private fun buildBitmapRegion(bitmap: Bitmap, drawRect: RectF): Region {
        val widthPx = max(1, drawRect.width().toInt())
        val heightPx = max(1, drawRect.height().toInt())
        val scaled = if (bitmap.width == widthPx && bitmap.height == heightPx) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, widthPx, heightPx, true)
        }

        val left = drawRect.left.toInt()
        val top = drawRect.top.toInt()
        val alphaThreshold = 12
        val rowPixels = IntArray(widthPx)
        val region = Region()
        var initialized = false

        for (y in 0 until heightPx) {
            scaled.getPixels(rowPixels, 0, widthPx, 0, y, widthPx, 1)
            var x = 0
            while (x < widthPx) {
                while (x < widthPx && Color.alpha(rowPixels[x]) <= alphaThreshold) {
                    x++
                }
                if (x >= widthPx) break

                val runStart = x
                while (x < widthPx && Color.alpha(rowPixels[x]) > alphaThreshold) {
                    x++
                }
                val runEnd = x

                val runLeft = left + runStart
                val runTop = top + y
                val runRight = max(runLeft + 1, left + runEnd)
                val runBottom = runTop + 1
                if (!initialized) {
                    region.set(runLeft, runTop, runRight, runBottom)
                    initialized = true
                } else {
                    region.op(runLeft, runTop, runRight, runBottom, Region.Op.UNION)
                }
            }
        }

        if (scaled !== bitmap) {
            scaled.recycle()
        }

        return if (initialized) region else rectToRegion(drawRect)
    }

    private fun currentDesignWidth(): Float {
        return when (deviceType) {
            DeviceType.PHONE_1 -> PHONE_1_DESIGN_WIDTH
            DeviceType.PHONE_2 -> PHONE_2_DESIGN_WIDTH
        }
    }

    private fun currentDesignHeight(): Float {
        return when (deviceType) {
            DeviceType.PHONE_1 -> PHONE_1_DESIGN_HEIGHT
            DeviceType.PHONE_2 -> PHONE_2_DESIGN_HEIGHT
        }
    }

    private fun resolveColor(resId: Int, fallback: Int): Int {
        return try {
            context.getColor(resId)
        } catch (_: Exception) {
            fallback
        }
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        )
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        private const val PHONE_1_DESIGN_WIDTH = 179f
        private const val PHONE_1_DESIGN_HEIGHT = 375f

        private const val PHONE_2_DESIGN_WIDTH = 179f
        private const val PHONE_2_DESIGN_HEIGHT = 382f

        private val PHONE_1_BITMAP_SEGMENTS = listOf(
            BitmapSegmentSpec("P1_LED1", R.drawable.ic_default_led1, R.drawable.ic_glyphs_led1, width = 43f, height = 78f, left = 9.77f, top = 8f),
            BitmapSegmentSpec("P1_LED2", R.drawable.ic_default_led2, R.drawable.ic_glyphs_led2, width = 39f, height = 45f, right = 21.91f, top = 19.33f),
            BitmapSegmentSpec("P1_LED3", R.drawable.ic_default_led3, R.drawable.ic_glyphs_led3, width = 161f, height = 199.33334f, top = 85.14f, centerHorizontal = true),
            BitmapSegmentSpec("P1_LED4", R.drawable.ic_default_led4, R.drawable.ic_glyphs_led4, width = 6f, height = 50.33334f, top = 301.0703f, centerHorizontal = true),
            BitmapSegmentSpec("P1_LED5", R.drawable.ic_default_led5, R.drawable.ic_glyphs_led5, width = 6f, height = 6f, top = 358.4141f, centerHorizontal = true)
        )

        private val PHONE_2_SEGMENTS = listOf(
            SegmentSpec("A1", R.drawable.glyph_led_default_a1, width = 45f, height = 49f, left = 10.919983f, top = 9f),
            SegmentSpec("A2", R.drawable.glyph_led_default_a2, width = 37f, height = 42f, left = 19.709991f, top = 50.049988f),
            SegmentSpec("B1", R.drawable.glyph_led_default_b1, width = 40f, height = 45f, left = 117.81f, top = 18.949982f),
            SegmentSpec("C1", R.drawable.glyph_led_default_c1, width = 95f, height = 43f, right = 11.269989f, top = 90.5f),
            SegmentSpec("C2", R.drawable.glyph_led_default_c2, width = 42f, height = 34f, left = 10.75f, top = 99.16f),
            SegmentSpec("C3", R.drawable.glyph_led_default_c3, width = 7f, height = 47f, left = 9f, top = 143.63f),
            SegmentSpec("C4", R.drawable.glyph_led_default_c4, width = 95f, height = 43f, left = 10.75f, top = 246.6f),
            SegmentSpec("C5", R.drawable.glyph_led_default_c5, width = 42f, height = 35f, right = 11.279999f, top = 246.57f),
            SegmentSpec("C6", R.drawable.glyph_led_default_c6, width = 7f, height = 37f, right = 9.529999f, top = 189.72f),
            SegmentSpec("D1", R.drawable.glyph_led_default_d1, width = 7f, height = 45f, bottom = 22.629974f, centerHorizontal = true),
            SegmentSpec("E1", R.drawable.glyph_led_default_e1, width = 6f, height = 10f, bottom = 8.439972f, centerHorizontal = true)
        )

        private val PHONE_2_ZONES = listOf(
            ZoneSpec("TOP_LEFT", listOf("A1")),
            ZoneSpec("TOP_RIGHT", listOf("A2")),
            ZoneSpec("CAMERA", listOf("B1")),
            ZoneSpec("STRIP_LEFT", listOf("C2")),
            ZoneSpec("STRIP_RIGHT", listOf("C1")),
            ZoneSpec("CURVE_LEFT", listOf("C3")),
            ZoneSpec("CURVE_BOTTOM_LEFT", listOf("C4")),
            ZoneSpec("CURVE_BOTTOM_RIGHT", listOf("C5")),
            ZoneSpec("CURVE_RIGHT", listOf("C6")),
            ZoneSpec("USB", listOf("D1")),
            ZoneSpec("BOTTOM", listOf("E1"))
        )

        // Phone 1 regions from the official glyphs_item_led_pager_spacewar layout.
        private val PHONE_1_ZONES = listOf(
            ZoneSpec("CAMERA", listOf("P1_LED1")),    // A1 -> 0
            ZoneSpec("DIAGONAL", listOf("P1_LED2")),  // B1 -> 1
            ZoneSpec("BATTERY", listOf("P1_LED3")),   // C1-C4 -> 2..5
            ZoneSpec("CENTER", listOf("P1_LED4")),    // D1_1-D1_8 -> 7..14
            ZoneSpec("BOTTOM", listOf("P1_LED5"))     // E1 -> 6
        )
    }
}
