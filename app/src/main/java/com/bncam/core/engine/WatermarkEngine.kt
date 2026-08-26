package com.bncam.core.engine

import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import com.bncam.R
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WatermarkConfig(
    val enabled: Boolean,
    val style: String,
    val signature: String,
    val addAuthorTopRight: Boolean,
    // Data specifiek voor de EXIF templates
    val deviceModel: String,
    val sensorName: String,
    val fov: String,
    val aperture: String,
    val shutterSpeed: String,
    val iso: String,
    val captureMode: String,
    val frameCountInfo: String
)

object WatermarkEngine {
    private const val TAG = "WatermarkEngine"

    // Cachen van zware lettertypen
    private var signatureTypeface: Typeface? = null
    private var digitalTypeface: Typeface? = null
    private var nintendoTypeface: Typeface? = null

    fun applyWatermark(context: Context, jpegBytes: ByteArray, config: WatermarkConfig): ByteArray {
        // Master check
        if (!config.enabled || (config.style == "Off" && !config.addAuthorTopRight)) {
            return jpegBytes
        }

        try {
            // Lettertypen inladen (cached)
            if (signatureTypeface == null && config.signature.isNotBlank()) {
                signatureTypeface = try {
                    ResourcesCompat.getFont(context, R.font.street)
                } catch (e: Exception) {
                    throw IllegalStateException("Configured watermark font could not be loaded.", e)
                }
            }

            // 1. Decodeer bytes naar een bitmap
            val options = BitmapFactory.Options().apply { inMutable = true }
            val originalBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                ?: throw IllegalStateException("Configured watermark could not decode the rendered JPEG.")

            // 2. Bepaal of we de foto moeten vergroten (voor "Device + EXIF" balk onderaan)
            val barHeight = (originalBitmap.width * 0.12f).toInt()
            val requiresExtendedCanvas = (config.style == "Device + EXIF")

            var workingBitmap = originalBitmap
            var isExtended = false

            if (requiresExtendedCanvas) {
                try {
                    // Creëer een nieuwe, hogere bitmap voor de balk eronder
                    workingBitmap = Bitmap.createBitmap(
                        originalBitmap.width,
                        originalBitmap.height + barHeight,
                        originalBitmap.config ?: Bitmap.Config.ARGB_8888
                    )
                    val bgCanvas = Canvas(workingBitmap)
                    // Teken de originele foto bovenaan
                    bgCanvas.drawBitmap(originalBitmap, 0f, 0f, null)
                    isExtended = true
                    // Original is niet meer nodig, gooi direct weg om RAM te sparen
                    originalBitmap.recycle()
                } catch (e: OutOfMemoryError) {
                    originalBitmap.recycle()
                    throw IllegalStateException("Configured extended watermark canvas could not be allocated.", e)
                }
            }

            val canvas = Canvas(workingBitmap)

            // 3. Teken de gekozen template stijl
            when (config.style) {
                "BnCam original" -> drawBnCamOriginal(context, canvas, workingBitmap)
                "80s Film Date" -> draw80sFilmDateTime(context, canvas, workingBitmap)
                "Signature stamp" -> drawSignatureStamp(canvas, workingBitmap, config.signature)
                "Creative frame" -> drawCreativeFrame(canvas, workingBitmap, config.signature)
                "Device + EXIF" -> drawDeviceExifBar(canvas, workingBitmap, config, isExtended, barHeight)
            }

            // 4. Teken de universele Author Signature rechtsboven (als deze aan staat)
            if (config.addAuthorTopRight && config.signature.isNotBlank()) {
                drawAuthorTopRight(context, canvas, workingBitmap, config.signature)
            }

            // 5. Comprimeer terug naar ByteArray
            val outputStream = ByteArrayOutputStream()
            workingBitmap.compress(Bitmap.CompressFormat.JPEG, 98, outputStream)

            // Geheugen vrijmaken!
            workingBitmap.recycle()

            return outputStream.toByteArray()

        } catch (e: OutOfMemoryError) {
            throw IllegalStateException("Configured watermark ran out of memory.", e)
        } catch (e: Exception) {
            throw IllegalStateException("Configured watermark failed.", e)
        }
    }

    private fun drawBnCamOriginal(context: Context, canvas: Canvas, bitmap: Bitmap) {
        try {
            val logoDrawable = ResourcesCompat.getDrawable(context.resources, R.drawable.watermark_logo, null)
                ?: return

            val desiredHeight = (bitmap.width * 0.25f).toInt()
            val aspectRatio = logoDrawable.intrinsicWidth.toFloat() / logoDrawable.intrinsicHeight.toFloat()
            val desiredWidth = (desiredHeight * aspectRatio).toInt()

            val margin = (bitmap.width * 0.015f).toInt()
            val left = margin
            val right = left + desiredWidth
            val bottom = bitmap.height - margin
            val top = bottom - desiredHeight

            // --- NIEUW: Radiale 'pizzapunt' schaduw ---
            // Bepaal hoe ver de schaduw de foto in mag stralen (bijv. 50% van de foto breedte)
            val shadowRadius = bitmap.width * 0.75f

            val shadowPaint = Paint().apply {
                shader = RadialGradient(
                    0f, bitmap.height.toFloat(), // Start exact in het hoekpunt linksonder
                    shadowRadius,                // Hoe ver de straal reikt
                    intArrayOf(Color.argb(170, 0, 0, 0), Color.TRANSPARENT), // Van zwart naar 0
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }

            // We tekenen een vierkant in de hoek linksonder, exact zo groot als de radius.
            // De gradient vult dit als een waaier in.
            val rectTop = bitmap.height - shadowRadius
            val rectRight = shadowRadius
            canvas.drawRect(0f, rectTop, rectRight, bitmap.height.toFloat(), shadowPaint)
            // ------------------------------------------

            logoDrawable.setBounds(left, top, right, bottom)
            logoDrawable.draw(canvas)
        } catch (e: Exception) {
            throw IllegalStateException("BnCam watermark logo could not be rendered.", e)
        }
    }

    private fun drawDeviceExifBar(
        canvas: Canvas,
        bitmap: Bitmap,
        config: WatermarkConfig,
        isExtended: Boolean,
        barHeight: Int
    ) {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        // Bepaal de Y startpositie van de balk
        val barStartY = if (isExtended) height - barHeight else height - barHeight
        val margin = width * 0.03f

        // 1. Zwarte achtergrondbalk tekenen
        val barPaint = Paint().apply {
            color = Color.parseColor("#151515")
            alpha = 255
        }
        val barRect = RectF(0f, barStartY, width, height)
        canvas.drawRect(barRect, barPaint)

        // 2. Tekst styling
        val titleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textSize = width * 0.025f
            alpha = 240
        }
        val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT
            textSize = width * 0.018f
            alpha = 180
        }

        // 3. Zelf-getekende Minimalistische Icoon Styling
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = width * 0.002f
            alpha = 240
        }

        // Posities
        var xLeft = margin
        val yCenterTop = barStartY + (barHeight * 0.40f)
        val yCenterBottom = barStartY + (barHeight * 0.80f)
        val iconSize = width * 0.035f

        val titleYOffset = (titleTextPaint.descent() + titleTextPaint.ascent()) / 2
        val subYOffset = (subTextPaint.descent() + subTextPaint.ascent()) / 2

        // --- LINKERKANT ---

        // Regel 1: Telefoon Icoontje + Model
        val phoneW = iconSize * 0.55f
        val phoneH = iconSize * 0.9f
        val pTop = yCenterTop - (phoneH / 2)

        canvas.drawRoundRect(RectF(xLeft, pTop, xLeft + phoneW, pTop + phoneH), 8f, 8f, iconPaint)
        canvas.drawLine(xLeft + phoneW * 0.35f, pTop + phoneH * 0.15f, xLeft + phoneW * 0.65f, pTop + phoneH * 0.15f, iconPaint)

        xLeft += phoneW + (margin * 0.5f)
        canvas.drawText(config.deviceModel.ifBlank { "BnCam Device" }, xLeft, yCenterTop - titleYOffset, titleTextPaint)

        // Regel 2: Camera Icoontje + Sensor/Lens info
        var xLeft2 = margin
        val camW = iconSize * 0.9f
        val camH = iconSize * 0.6f
        val cTop = yCenterBottom - (camH / 2)

        canvas.drawRoundRect(RectF(xLeft2, cTop, xLeft2 + camW, cTop + camH), 6f, 6f, iconPaint)
        canvas.drawCircle(xLeft2 + (camW / 2), yCenterBottom, camH * 0.35f, iconPaint)
        canvas.drawCircle(xLeft2 + (camW * 0.2f), cTop + (camH * 0.25f), width * 0.001f, Paint(iconPaint).apply { style = Paint.Style.FILL })

        xLeft2 += camW + (margin * 0.5f)
        val sensorInfo = "${config.sensorName}   ${config.fov}   ${config.aperture}"
        canvas.drawText(sensorInfo, xLeft2, yCenterBottom - subYOffset, subTextPaint)

        // --- RECHTERKANT ---
        val xRight = width - margin
        titleTextPaint.textAlign = Paint.Align.RIGHT
        subTextPaint.textAlign = Paint.Align.RIGHT

        val exifParams = "${config.shutterSpeed}   ${config.iso}"
        canvas.drawText(exifParams, xRight, yCenterTop - titleYOffset, titleTextPaint)

        val captureParams = "${config.captureMode}  |  ${config.frameCountInfo}"
        canvas.drawText(captureParams, xRight, yCenterBottom - subYOffset, subTextPaint)
    }

    private fun drawCreativeFrame(canvas: Canvas, bitmap: Bitmap, signature: String) {
        val cx = bitmap.width / 2f
        val cy = bitmap.height * 0.85f
        val radius = bitmap.width * 0.135f

        // --- NIEUW: Subtiele Radial Gradient achtergrond ---
        val bgRadius = radius * 1.75f // Iets groter dan de tekst ring
        val bgPaint = Paint().apply {
            shader = RadialGradient(
                cx, cy, bgRadius,
                intArrayOf(Color.argb(120, 0, 0, 0), Color.TRANSPARENT), // Van ~47% zwart naar transparant
                floatArrayOf(0.4f, 1f), // Blijf stabiel tot 40% van de radius, fade daarna zacht uit
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, bgRadius, bgPaint)

        // ---------------------------------------------------
        // INITIALS
        // ---------------------------------------------------

        val initials = getInitials(signature)

        val initialsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

            color = Color.WHITE

            typeface =
                signatureTypeface
                    ?: Typeface.create(
                        Typeface.SERIF,
                        Typeface.ITALIC
                    )

            textSize = radius * 0.82f

            textAlign = Paint.Align.CENTER

            alpha = 240

            setShadowLayer(
                6f,
                2f,
                2f,
                Color.parseColor("#99000000")
            )
        }

        val initialsOffset =
            (initialsPaint.descent() + initialsPaint.ascent()) / 2f

        canvas.drawText(
            initials,
            cx,
            cy - initialsOffset,
            initialsPaint
        )

        // ---------------------------------------------------
        // TEXT PAINT
        // ---------------------------------------------------

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

            color = Color.WHITE

            typeface = Typeface.create(
                Typeface.SANS_SERIF,
                Typeface.NORMAL
            )

            textSize = radius * 0.18f

            textAlign = Paint.Align.LEFT

            alpha = 210

            setShadowLayer(
                4f,
                2f,
                2f,
                Color.parseColor("#99000000")
            )
        }

        val authorText =
            if (signature.isNotBlank())
                signature.uppercase()
            else
                "AUTHOR NAME"

        val photoText = "PHOTOGRAPHY"

        val halfArcLength =
            (Math.PI * radius).toFloat()

        // ---------------------------------------------------
// TOP ARC
// ---------------------------------------------------

        val topPath = Path().apply {

            addArc(
                RectF(
                    cx - radius,
                    cy - radius,
                    cx + radius,
                    cy + radius
                ),
                180f,
                180f
            )
        }

// Reset
        circlePaint.textSize = radius * 0.18f
        circlePaint.letterSpacing = 0f

// ---------------------------------------------------
// LONG NAME PROTECTION
// ---------------------------------------------------

// Bovenste helft hoeft niet volledig gevuld,
// maar moet mooi breed uitlopen
        val targetTopWidth =
            halfArcLength * 0.72f

        var topWidth =
            circlePaint.measureText(authorText)

// Eerst verkleinen indien nodig
        if (topWidth > targetTopWidth) {

            val scale =
                targetTopWidth / topWidth

            circlePaint.textSize *= scale

            topWidth =
                circlePaint.measureText(authorText)
        }

// ---------------------------------------------------
// FLEXIBLE LETTER SPACING
// ---------------------------------------------------

        val missingTopWidth =
            targetTopWidth - topWidth

        val topGaps =
            (authorText.length - 1).coerceAtLeast(1)

        val topSpacingPx =
            missingTopWidth / topGaps

        val topRelativeSpacing =
            topSpacingPx / circlePaint.textSize

// Veel subtieler dan onderkant
        circlePaint.letterSpacing =
            topRelativeSpacing.coerceIn(0f, 0.35f)

// Finale breedte opnieuw meten
        topWidth =
            circlePaint.measureText(authorText)

        val topOffset =
            (halfArcLength - topWidth) / 2f

        canvas.drawTextOnPath(
            authorText,
            topPath,
            topOffset,
            0f,
            circlePaint
        )

        // ---------------------------------------------------
        // BOTTOM ARC
        // ---------------------------------------------------

        val bottomPath = Path().apply {

            addArc(
                RectF(
                    cx - radius,
                    cy - radius,
                    cx + radius,
                    cy + radius
                ),
                0f,
                180f
            )
        }

        // Reset
        circlePaint.textSize = radius * 0.18f
        circlePaint.letterSpacing = 0f

        val targetWidth =
            halfArcLength * 0.96f

        val baseWidth =
            circlePaint.measureText(photoText)

        // Hoeveel ruimte moeten we opvullen?
        val missingWidth =
            targetWidth - baseWidth

        val gaps =
            (photoText.length - 1).coerceAtLeast(1)

        // Extra spacing per gap
        val spacingPx =
            missingWidth / gaps

        // Android gebruikt em-relative spacing
        val relativeSpacing =
            spacingPx / circlePaint.textSize

        // Veel ruimer toegestaan
        circlePaint.letterSpacing =
            relativeSpacing.coerceIn(0f, 1.2f)

        val finalWidth =
            circlePaint.measureText(photoText)

        val bottomOffset =
            (halfArcLength - finalWidth) / 2f

        canvas.drawTextOnPath(
            photoText,
            bottomPath,
            bottomOffset,
            0f,
            circlePaint
        )
    }

    private fun drawSignatureStamp(canvas: Canvas, bitmap: Bitmap, signature: String) {
        val displaySig = signature.ifBlank { "BnCam" }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = signatureTypeface ?: Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
            // --- AANGEPAST: Stuk kleiner gemaakt (was 0.25f) ---
            textSize = bitmap.width * 0.11f
            color = Color.parseColor("#EAEAEA")
            alpha = 150
            setShadowLayer(4f, 2f, 2f, Color.parseColor("#66000000"))
        }

        val xPos = (bitmap.width / 2).toFloat()
        val margin = bitmap.width * 0.075f
        val yPos = bitmap.height - margin - paint.descent()

        canvas.drawText(displaySig, xPos, yPos, paint)
    }

    private fun draw80sFilmDateTime(context: Context, canvas: Canvas, bitmap: Bitmap) {
        val dateFormat = SimpleDateFormat("dd MM ''yy  HH:mm", Locale.US)
        val dateText = dateFormat.format(Date())

        if (digitalTypeface == null) {
            digitalTypeface = try {
                ResourcesCompat.getFont(context, R.font.digital)
            } catch (e: Exception) {
                throw IllegalStateException("80s watermark font could not be loaded.", e)
            }
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = digitalTypeface
            textAlign = Paint.Align.RIGHT
            color = Color.parseColor("#F28500")
            alpha = 240
            textSize = bitmap.width * 0.05f
            setShadowLayer(10f, 4f, 4f, Color.parseColor("#CC000000"))
        }

        val margin = bitmap.width * 0.04f
        val xPos = bitmap.width - margin
        val yPos = bitmap.height - margin

        canvas.drawText(dateText, xPos, yPos, paint)
    }

    private fun drawAuthorTopRight(context: Context, canvas: Canvas, bitmap: Bitmap, signature: String) {
        // Laad het digital font ook hier in
        if (nintendoTypeface == null) {
            nintendoTypeface = try {
                ResourcesCompat.getFont(context, R.font.nintendo)
            } catch (e: Exception) {
                throw IllegalStateException("Author watermark font could not be loaded.", e)
            }
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 180
            textSize = bitmap.width * 0.038f // Vergoot (was 0.025f)
            typeface = nintendoTypeface // Aangepast naar het nintendo font!
            textAlign = Paint.Align.RIGHT
            setShadowLayer(5f, 2f, 2f, Color.parseColor("#99000000"))
        }

        val margin = bitmap.width * 0.05f
        val xPos = bitmap.width - margin
        val yPos = margin + paint.textSize

        canvas.drawText(signature, xPos, yPos, paint)
    }

    private fun getInitials(name: String): String {
        if (name.isBlank()) return "B"
        val words = name.trim().split(Regex("\\s+"))
        return if (words.size == 1) {
            words[0].take(1).uppercase()
        } else {
            (words.first().take(1) + words.last().take(1)).uppercase()
        }
    }
}
