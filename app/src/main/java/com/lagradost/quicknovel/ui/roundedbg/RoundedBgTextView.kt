/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lagradost.quicknovel.ui.roundedbg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.Spanned
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withTranslation
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
import com.lagradost.quicknovel.util.toPx

/**
 * A TextView that can draw rounded background to the portions of the text. See
 * [TextRoundedBgHelper] for more information.
 *
 * See [TextRoundedBgAttributeReader] for supported attributes.
 */
class RoundedBgTextView// PREVENT OVERLAP, SUE ME
@JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val textRoundedBgHelper: TextRoundedBgHelper

    init {
        val attributeReader = TextRoundedBgAttributeReader(context, attrs)
        textRoundedBgHelper = TextRoundedBgHelper(
            horizontalPadding = attributeReader.horizontalPadding,
            verticalPadding = attributeReader.verticalPadding - (75.toPx / 100), // PREVENT OVERLAP, SUE ME
            drawable = attributeReader.drawable,
            drawableLeft = attributeReader.drawableLeft,
            drawableMid = attributeReader.drawableMid,
            drawableRight = attributeReader.drawableRight
        )
    }

    /** Alpha (0..255) of the highlight background — pulsed to flicker while TTS audio is generating. */
    var roundedBgAlpha: Int = 255
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    // ---- thin pulsating underline for sentences whose TTS is being generated in the background ----
    private val generatingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.colorFromAttribute(R.attr.colorPrimary)
    }
    private val genThicknessPx = 1.5f * resources.displayMetrics.density
    private val genGapPx = 1f * resources.displayMetrics.density

    /** Alpha (0..255) of the "generating" underline — pulsed independently of [roundedBgAlpha]. */
    var generatingAlpha: Int = 255
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    override fun onDraw(canvas: Canvas) {
        // need to draw bg first so that text can be on top during super.onDraw()
        if (text is Spanned && layout != null) {
            canvas.withTranslation(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat()) {
                drawGeneratingUnderlines(canvas, text as Spanned, layout)
                textRoundedBgHelper.draw(canvas, text as Spanned, layout, roundedBgAlpha)
            }
        }
        super.onDraw(canvas)
    }

    /** Thin pulsating rule under every "generating" annotation span (audio being prepared ahead).
     *  Different Y band + color + independent alpha => never collides with the "rounded" highlight. */
    private fun drawGeneratingUnderlines(canvas: Canvas, text: Spanned, layout: Layout) {
        val spans = text.getSpans(0, text.length, android.text.Annotation::class.java)
        generatingPaint.alpha = generatingAlpha
        for (span in spans) {
            if (span.value != "generating") continue
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val startLine = layout.getLineForOffset(start)
            val endLine = layout.getLineForOffset(end)
            for (line in startLine..endLine) {
                val l = if (line == startLine) layout.getPrimaryHorizontal(start) else layout.getLineLeft(line)
                val r = if (line == endLine) layout.getPrimaryHorizontal(end) else layout.getLineRight(line)
                val y = layout.getLineBottomWithoutSpacing(line).toFloat() - genGapPx
                canvas.drawRect(minOf(l, r), y, maxOf(l, r), y + genThicknessPx, generatingPaint)
            }
        }
    }
}