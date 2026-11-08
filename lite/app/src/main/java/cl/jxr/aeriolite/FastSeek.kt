package cl.jxr.aeriolite

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.ts.TsExtractor

/**
 * Movies come as plain .ts files with no index. To open one at minute 32 the player normally
 * "searches" for the exact spot, one request per guess, and this provider takes several seconds
 * to answer each request (over a minute to resume). Here the spot is estimated from the file
 * size in a single jump: it may land a few seconds off, but it is one request.
 */
class FastSeekExtractors(private val base: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = base.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        base.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(e: Extractor): Extractor = if (e is TsExtractor) FastSeekTs(e) else e
}

private class FastSeekTs(private val inner: TsExtractor) : Extractor {
    private var length = C.LENGTH_UNSET.toLong()
    private var dropSearch = false

    override fun sniff(input: ExtractorInput) = inner.sniff(input)

    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput by output {
            override fun seekMap(seekMap: SeekMap) {
                val duration = seekMap.durationUs
                if (seekMap.isSeekable && duration != C.TIME_UNSET && duration > 0 && length > 0 && canDrop) {
                    dropSearch = true
                    output.seekMap(Estimate(duration, length))
                } else output.seekMap(seekMap)
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        length = input.length
        val r = inner.read(input, seekPosition)
        if (dropSearch) {
            // without its searcher the extractor simply reads from where the estimate put it
            dropSearch = false
            runCatching { searcher?.set(inner, null) }
        }
        return r
    }

    override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)

    override fun release() = inner.release()

    private class Estimate(private val durationUs: Long, private val length: Long) : SeekMap {
        override fun isSeekable() = true
        override fun getDurationUs() = durationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (timeUs <= 0) return SeekMap.SeekPoints(SeekPoint(0, 0))
            val packets = length / TS_PACKET
            val at = (packets * (timeUs.toDouble() / durationUs)).toLong().coerceIn(0, maxOf(0, packets - 1))
            return SeekMap.SeekPoints(SeekPoint(timeUs, at * TS_PACKET))
        }
    }

    companion object {
        private const val TS_PACKET = 188L
        private val searcher = runCatching {
            TsExtractor::class.java.getDeclaredField("tsBinarySearchSeeker").apply { isAccessible = true }
        }.getOrNull()
        // only take over the seek map when the searcher can really be switched off
        private val canDrop = searcher != null
    }
}
