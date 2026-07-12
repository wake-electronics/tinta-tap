package com.wakeelectronics.tintatap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * 62×62 pixel drawing canvas for NFC image transfer.
 * Touch/drag to draw. Use [drawMode] to switch between draw (black) and erase (white).
 * Use [brushRadius] to control brush size (1 = single cell, 2 = ~5 cells, 3 = ~21 cells).
 * Call [pack] to get the 481-byte 1-bit payload, MSB-first, row-major.
 */
class DrawingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val COLS = 62
        const val ROWS = 62
        const val PIXEL_BYTES = (COLS * ROWS + 7) / 8  // 481
        private const val UNDO_LEVELS = 10
    }

    private val pixels = BooleanArray(COLS * ROWS)  // false = white, true = black

    private val cellPaint = Paint().apply { style = Paint.Style.FILL }
    private val gridPaint = Paint().apply {
        color = Color.LTGRAY
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
    }
    private val borderPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    /** true = draw black, false = erase to white */
    var drawMode: Boolean = true

    /** Brush radius in grid cells. 1 = single cell, 2 = ~5 cells, 3 = ~21 cells. */
    var brushRadius: Int = 2

    /** Invoked whenever the pixels change — lets the detail screen re-arm the NFC request. */
    var onChange: (() -> Unit)? = null

    private val undoStack = ArrayDeque<BooleanArray>(UNDO_LEVELS)
    private var lastTouchedIdx = -1

    // Track last painted cell for historical-point interpolation
    private var lastPaintCol = -1
    private var lastPaintRow = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.getSize(widthMeasureSpec)
            else -> resources.displayMetrics.widthPixels
        }
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.getSize(heightMeasureSpec)
            else -> resources.displayMetrics.heightPixels
        }
        val size = minOf(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cellW = width.toFloat() / COLS
        val cellH = height.toFloat() / ROWS

        // Fill cells
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                cellPaint.color = if (pixels[row * COLS + col]) Color.BLACK else Color.WHITE
                canvas.drawRect(
                    col * cellW, row * cellH,
                    (col + 1) * cellW, (row + 1) * cellH,
                    cellPaint
                )
            }
        }

        // Grid lines
        for (col in 0..COLS) {
            val x = col * cellW
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
        }
        for (row in 0..ROWS) {
            val y = row * cellH
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }

        // Border
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cellW = width.toFloat() / COLS
        val cellH = height.toFloat() / ROWS

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)  // stop the scroll view stealing the stroke
                pushUndo()
                lastPaintCol = -1
                lastPaintRow = -1
                paintAtEvent(event, cellW, cellH)
            }
            MotionEvent.ACTION_MOVE -> {
                paintAtEvent(event, cellW, cellH)
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                lastPaintCol = -1
                lastPaintRow = -1
                lastTouchedIdx = -1
            }
        }
        return true
    }

    private fun paintAtEvent(event: MotionEvent, cellW: Float, cellH: Float) {
        var changed = false
        for (p in 0 until event.pointerCount) {
            // Replay historical positions to fill gaps from fast swipes
            val histCount = event.getHistorySize()
            for (h in 0..histCount) {
                val x = if (h < histCount) event.getHistoricalX(p, h) else event.getX(p)
                val y = if (h < histCount) event.getHistoricalY(p, h) else event.getY(p)
                val col = floor(x / cellW).toInt().coerceIn(0, COLS - 1)
                val row = floor(y / cellH).toInt().coerceIn(0, ROWS - 1)

                // Interpolate between last painted cell and current to avoid gaps
                if (lastPaintCol >= 0) {
                    changed = changed or interpolateLine(lastPaintCol, lastPaintRow, col, row)
                }
                changed = changed or paintBrush(col, row)
                lastPaintCol = col
                lastPaintRow = row
            }
        }
        if (changed) { invalidate(); onChange?.invoke() }
        lastTouchedIdx = -1
    }

    /** Bresenham line fill between two grid cells to avoid gaps on fast moves. */
    private fun interpolateLine(c0: Int, r0: Int, c1: Int, r1: Int): Boolean {
        var changed = false
        val dc = abs(c1 - c0)
        val dr = abs(r1 - r0)
        val sc = if (c0 < c1) 1 else -1
        val sr = if (r0 < r1) 1 else -1
        var err = dc - dr
        var c = c0; var r = r0
        while (true) {
            changed = changed or paintBrush(c, r)
            if (c == c1 && r == r1) break
            val e2 = 2 * err
            if (e2 > -dr) { err -= dr; c += sc }
            if (e2 < dc) { err += dc; r += sr }
        }
        return changed
    }

    /** Paint a circular brush of [brushRadius] centred on (col, row). */
    private fun paintBrush(col: Int, row: Int): Boolean {
        var changed = false
        val r = brushRadius - 1
        for (dr in -r..r) {
            for (dc in -r..r) {
                if (dr * dr + dc * dc > r * r) continue  // circular clip
                val nr = (row + dr).coerceIn(0, ROWS - 1)
                val nc = (col + dc).coerceIn(0, COLS - 1)
                val idx = nr * COLS + nc
                if (pixels[idx] != drawMode) {
                    pixels[idx] = drawMode
                    changed = true
                }
            }
        }
        return changed
    }

    /** Reset all pixels to white. Pushes undo snapshot first. */
    fun clear() {
        pushUndo()
        pixels.fill(false)
        invalidate()
        onChange?.invoke()
    }

    /** Invert all pixels. Pushes undo snapshot first. */
    fun invert() {
        pushUndo()
        for (i in pixels.indices) pixels[i] = !pixels[i]
        invalidate()
        onChange?.invoke()
    }

    /** Undo the last draw/erase/clear/invert action. */
    fun undo() {
        if (undoStack.isEmpty()) return
        val snapshot = undoStack.removeLast()
        snapshot.copyInto(pixels)
        invalidate()
        onChange?.invoke()
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    private fun pushUndo() {
        if (undoStack.size >= UNDO_LEVELS) undoStack.removeFirst()
        undoStack.addLast(pixels.copyOf())
    }

    /**
     * Pack pixels to [PIXEL_BYTES] bytes, MSB-first, row-major.
     * Bit 7 of byte 0 = pixel (0,0), bit 6 = pixel (0,1), etc.
     */
    fun pack(): ByteArray {
        val out = ByteArray(PIXEL_BYTES)
        for (i in pixels.indices) {
            if (pixels[i]) {
                out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
            }
        }
        return out
    }
}
