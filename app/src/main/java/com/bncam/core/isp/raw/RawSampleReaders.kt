package com.bncam.core.isp.raw

object RawSampleReaders {
    fun packedRaw10RowBytes(widthPixels: Int): Int {
        require(widthPixels > 0) { "widthPixels must be positive" }
        return checkedSize(((widthPixels.toLong() + 3L) / 4L) * 5L, "RAW10 row")
    }

    fun packRaw10Rows(samples: IntArray, width: Int, height: Int, rowStrideBytes: Int = packedRaw10RowBytes(width)): ByteArray {
        require(width > 0 && height > 0) { "width and height must be positive" }
        require(samples.size >= checkedSize(width.toLong() * height, "sample count")) { "not enough samples for RAW10 frame" }
        require(rowStrideBytes >= packedRaw10RowBytes(width)) { "rowStrideBytes is smaller than packed RAW10 row" }
        val out = ByteArray(checkedSize(rowStrideBytes.toLong() * height, "packed byte count"))
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                val base = y * rowStrideBytes + (x / 4) * 5
                val p0 = samples[y * width + x].coerceIn(0, 1023)
                val p1 = if (x + 1 < width) samples[y * width + x + 1].coerceIn(0, 1023) else 0
                val p2 = if (x + 2 < width) samples[y * width + x + 2].coerceIn(0, 1023) else 0
                val p3 = if (x + 3 < width) samples[y * width + x + 3].coerceIn(0, 1023) else 0
                out[base] = ((p0 shr 2) and 0xFF).toByte()
                out[base + 1] = ((p1 shr 2) and 0xFF).toByte()
                out[base + 2] = ((p2 shr 2) and 0xFF).toByte()
                out[base + 3] = ((p3 shr 2) and 0xFF).toByte()
                out[base + 4] = ((p0 and 0x03) or ((p1 and 0x03) shl 2) or ((p2 and 0x03) shl 4) or ((p3 and 0x03) shl 6)).toByte()
                x += 4
            }
        }
        return out
    }

    fun unpackRaw10Rows(packed: ByteArray, width: Int, height: Int, rowStrideBytes: Int): IntArray {
        require(width > 0 && height > 0) { "width and height must be positive" }
        require(rowStrideBytes >= packedRaw10RowBytes(width)) { "rowStrideBytes is smaller than packed RAW10 row" }
        require(packed.size.toLong() >= (height - 1L) * rowStrideBytes + packedRaw10RowBytes(width)) { "packed buffer is smaller than declared stride/height" }
        val out = IntArray(checkedSize(width.toLong() * height, "sample count"))
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                val base = y * rowStrideBytes + (x / 4) * 5
                val b0 = packed[base].toInt() and 0xFF
                val b1 = packed[base + 1].toInt() and 0xFF
                val b2 = packed[base + 2].toInt() and 0xFF
                val b3 = packed[base + 3].toInt() and 0xFF
                val b4 = packed[base + 4].toInt() and 0xFF
                out[y * width + x] = (b0 shl 2) or (b4 and 0x03)
                if (x + 1 < width) out[y * width + x + 1] = (b1 shl 2) or ((b4 shr 2) and 0x03)
                if (x + 2 < width) out[y * width + x + 2] = (b2 shl 2) or ((b4 shr 4) and 0x03)
                if (x + 3 < width) out[y * width + x + 3] = (b3 shl 2) or ((b4 shr 6) and 0x03)
                x += 4
            }
        }
        return out
    }

    fun readRawSensor16LittleEndian(data: ByteArray, width: Int, height: Int, rowStrideBytes: Int, pixelStrideBytes: Int): IntArray {
        require(width > 0 && height > 0) { "width and height must be positive" }
        require(pixelStrideBytes >= 2) { "RAW_SENSOR pixelStrideBytes must be at least 2" }
        val minimumRowBytes = checkedSize((width - 1L) * pixelStrideBytes + 2L, "RAW_SENSOR row")
        require(rowStrideBytes >= minimumRowBytes) { "rowStrideBytes is smaller than RAW_SENSOR row payload" }
        require(data.size.toLong() >= (height - 1L) * rowStrideBytes + minimumRowBytes) { "RAW_SENSOR buffer is smaller than declared stride/height" }
        val out = IntArray(checkedSize(width.toLong() * height, "sample count"))
        for (y in 0 until height) {
            val rowBase = y * rowStrideBytes
            for (x in 0 until width) {
                val offset = rowBase + x * pixelStrideBytes
                val lo = data[offset].toInt() and 0xFF
                val hi = data[offset + 1].toInt() and 0xFF
                out[y * width + x] = lo or (hi shl 8)
            }
        }
        return out
    }

    private fun checkedSize(value: Long, label: String): Int {
        require(value in 1..Int.MAX_VALUE.toLong()) { "$label exceeds supported array size" }
        return value.toInt()
    }

    fun cfaOriginAfterCrop(originX: Int, originY: Int, cropLeft: Int, cropTop: Int): Pair<Int, Int> {
        return ((originX + cropLeft) and 1) to ((originY + cropTop) and 1)
    }

    fun normalizeSample(sample: Int, blackLevel: Int, whiteLevel: Int): Float {
        require(whiteLevel > blackLevel) { "whiteLevel must be greater than blackLevel in the same sample domain" }
        return ((sample - blackLevel).toFloat() / (whiteLevel - blackLevel).toFloat()).coerceIn(0f, 1f)
    }
}
