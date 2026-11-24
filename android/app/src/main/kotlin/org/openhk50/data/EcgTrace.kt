package org.openhk50.data

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.Reader

internal data class EcgPoint(val milliseconds: Float, val millivolts: Float)

internal data class EcgTrace(val points: List<EcgPoint>) {
    val durationMs get() = points.last().milliseconds

    companion object {
        private const val MAX_CHARS = 2_000_000
        internal const val MAX_POINTS = 120_000
        private val number = Regex("^[+-]?\\d+(?:[.,]\\d+)?$")

        fun readCsv(reader: Reader): EcgTrace {
            val content = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(content.length + count <= MAX_CHARS)
                content.append(buffer, 0, count)
            }
            val lines = content.toString().removePrefix("\uFEFF").lineSequence().map(String::trim)
                .filter(String::isNotEmpty).iterator()
            require(lines.hasNext())
            val first = lines.next()
            val points = if (first.equals("time_ms,mv", ignoreCase = true)) timestamped(lines)
                else appleWatch(sequenceOf(first).plus(lines.asSequence()).iterator())
            return checked(points)
        }

        internal fun checked(points: List<EcgPoint>): EcgTrace {
            require(points.size in 2..MAX_POINTS && points.first().milliseconds == 0f)
            require(points.last().milliseconds <= 600_000 && points.zipWithNext().all { (a, b) ->
                b.milliseconds.isFinite() && b.milliseconds > a.milliseconds
            })
            require(points.all { it.millivolts.isFinite() && it.millivolts in -100f..100f })
            return EcgTrace(points)
        }

        private fun timestamped(lines: Iterator<String>) = buildList {
            var start: Float? = null
            for (line in lines) {
                require(size < MAX_POINTS)
                val fields = line.split(',')
                require(fields.size == 2)
                val time = fields[0].trim().toFloat()
                if (start == null) start = time
                add(EcgPoint(time - start, fields[1].trim().toFloat()))
            }
        }

        private fun appleWatch(lines: Iterator<String>) = buildList {
            var sampleRate: Float? = null
            var unit: Float? = null
            for (line in lines) {
                if (line.startsWith("Sample Rate,", ignoreCase = true)) {
                    sampleRate = line.substringAfter(',').trim().substringBefore(' ').replace(',', '.').toFloat()
                    require(sampleRate in 50f..2_000f)
                } else if (line.startsWith("Unit,", ignoreCase = true)) {
                    unit = when (line.substringAfter(',').trim()) {
                        "µV", "μV", "uV" -> .001f
                        "mV" -> 1f
                        else -> throw IllegalArgumentException()
                    }
                } else if (unit != null && sampleRate != null) {
                    val value = line.trim('"')
                    require(size < MAX_POINTS && number.matches(value))
                    add(EcgPoint(size * 1_000f / sampleRate, value.replace(',', '.').toFloat() * unit))
                }
            }
        }
    }
}

internal class EcgStore(files: File) {
    private val file = AtomicFile(File(files, "ecg_waveform"))

    fun read(): EcgTrace? {
        val stream = try { file.openRead() } catch (_: FileNotFoundException) { return null }
        return DataInputStream(stream).use { input ->
            val count = input.readInt()
            require(count in 2..EcgTrace.MAX_POINTS)
            EcgTrace.checked(List(count) { EcgPoint(input.readFloat(), input.readFloat()) })
        }
    }

    fun save(trace: EcgTrace) {
        val stream = file.startWrite()
        try {
            val output = DataOutputStream(stream)
            output.writeInt(trace.points.size)
            for (point in trace.points) {
                output.writeFloat(point.milliseconds)
                output.writeFloat(point.millivolts)
            }
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun clear() = file.delete()
}
