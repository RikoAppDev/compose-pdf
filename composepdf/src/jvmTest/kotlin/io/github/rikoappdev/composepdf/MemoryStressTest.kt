package io.github.rikoappdev.composepdf

import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Memory stress harness for photo-heavy documents — **not part of `jvmTest`/`check`/CI**. It takes
 * minutes and writes hundreds of MB, so `composepdf/build.gradle.kts` excludes this class from the
 * `jvmTest` task and runs it only from the opt-in `:composepdf:jvmStressTest` task, which forks a
 * JVM with a constrained heap (`-PstressHeap`, default 460m).
 *
 * What it proves, with measurements rather than reasoning:
 *  - [PdfDocumentSpec.renderTo] streams ~50/100/200 MB photo reports to disk inside a heap far
 *    smaller than the file, producing a structurally valid PDF (verified with a `RandomAccessFile`
 *    xref walk — the file is never loaded into memory).
 *  - [PdfDocumentSpec.render] on the same document peaks roughly one whole file-sized array higher,
 *    and at the largest size dies with [OutOfMemoryError] in the same JVM.
 *  - The streamed bytes stay byte-identical to `render()` at scale (SHA-256 of a digest sink vs the
 *    returned array, at the 50 MB size where the array still fits).
 *  - **[PdfImageSource] removes the last term that scaled with the document**: with photos supplied
 *    as file-backed sources, ~200 MB and ~400 MB reports stream out of a heap a *fraction* of their
 *    size, and the live set measured mid-write is a small constant that does not grow with the photo
 *    count ([c_lazySourceLadderStreamsFarBeyondTheHeap]).
 *
 * Memory model under test (P = caller's photo bytes, F = final file size; JPEGs pass through as
 * `/DCTDecode` so F ≈ P + a few % of text/font/xref overhead):
 *  - `renderTo`, photos as `ByteArray` ≈ P — the caller's own photos; the object list references
 *    them rather than copying (`addStreamRef`, `pdf/Pdf.kt`).
 *  - `render`, photos as `ByteArray`   ≈ P + F — the above plus the exact-size whole-file array.
 *  - `renderTo`, photos as [PdfImageSource] ≈ **one photo** — there is no P: each payload is pulled
 *    when its object is written and released immediately (`addStreamLazy`).
 *
 * Synthetic data only: photos are procedurally generated value-noise, all text is placeholder.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MemoryStressTest {

    // ---------------------------------------------------------------------------------------------
    // Fixture: synthetic "capture-time resized" photos
    // ---------------------------------------------------------------------------------------------

    private fun regular() = File("src/jvmTest/resources/font/NotoSans-Regular.ttf").readBytes()
    private fun bold() = File("src/jvmTest/resources/font/NotoSans-Bold.ttf").readBytes()

    companion object {
        /** Matches a typical capture-time resize on the phone (4:3, ~1.7 MPix). */
        private const val PHOTO_W = 1500
        private const val PHOTO_H = 1125
        private const val JPEG_QUALITY = 0.85f

        /** Lattice cell / per-pixel jitter tuned so a photo lands in the 300–600 KB band. */
        private const val NOISE_CELL = 8
        private const val NOISE_JITTER = 10

        /** Photos per "day" section: two rows of three. */
        private const val PHOTOS_PER_DAY = 6

        /**
         * Distinct encodes. Every photo in a document gets its own byte array (so P is honest — the
         * app holds one array per photo), but the *content* is drawn from this many distinct seeds:
         * a full encode costs ~0.5 s, and 400+ of them would dominate the run time.
         */
        private const val DISTINCT_SEEDS = 12

        /** Encoded once per JVM; documents get [ByteArray.copyOf] clones of these. */
        private val basePhotos: List<ByteArray> by lazy {
            val t0 = System.nanoTime()
            val photos = (0 until DISTINCT_SEEDS).map { noisePhotoJpeg(seed = 1000L + it) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            println(
                "[fixture] $DISTINCT_SEEDS distinct ${PHOTO_W}x${PHOTO_H} q$JPEG_QUALITY JPEGs in $ms ms, " +
                    "avg ${photos.sumOf { it.size } / photos.size / 1024} KB " +
                    "(min ${photos.minOf { it.size } / 1024} KB, max ${photos.maxOf { it.size } / 1024} KB)"
            )
            photos
        }

        private val avgPhotoBytes: Int get() = basePhotos.sumOf { it.size } / basePhotos.size

        /**
         * A photo-shaped noise image: a random colour lattice bilinearly interpolated (smooth, like
         * real photo content) plus per-pixel jitter (grain). Both defeat entropy coding enough that
         * the JPEG stays a realistic few hundred KB instead of compressing away to nothing.
         */
        private fun noisePhotoJpeg(seed: Long): ByteArray {
            val rnd = Random(seed)
            val lw = PHOTO_W / NOISE_CELL + 2
            val lh = PHOTO_H / NOISE_CELL + 2
            val r = lattice(rnd, lw, lh); val g = lattice(rnd, lw, lh); val b = lattice(rnd, lw, lh)
            val img = BufferedImage(PHOTO_W, PHOTO_H, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until PHOTO_H) {
                val gy = y / NOISE_CELL
                val fy = (y % NOISE_CELL) / NOISE_CELL.toFloat()
                for (x in 0 until PHOTO_W) {
                    val gx = x / NOISE_CELL
                    val fx = (x % NOISE_CELL) / NOISE_CELL.toFloat()
                    val rr = clamp8(bilinear(r, gx, gy, fx, fy) + rnd.nextInt(NOISE_JITTER * 2 + 1) - NOISE_JITTER)
                    val gg = clamp8(bilinear(g, gx, gy, fx, fy) + rnd.nextInt(NOISE_JITTER * 2 + 1) - NOISE_JITTER)
                    val bb = clamp8(bilinear(b, gx, gy, fx, fy) + rnd.nextInt(NOISE_JITTER * 2 + 1) - NOISE_JITTER)
                    img.setRGB(x, y, (rr shl 16) or (gg shl 8) or bb)
                }
            }
            return encodeJpeg(img, JPEG_QUALITY)
        }

        private fun lattice(rnd: Random, w: Int, h: Int) =
            Array(h) { FloatArray(w) { rnd.nextInt(256).toFloat() } }

        private fun bilinear(a: Array<FloatArray>, gx: Int, gy: Int, fx: Float, fy: Float): Int {
            val top = a[gy][gx] * (1 - fx) + a[gy][gx + 1] * fx
            val bot = a[gy + 1][gx] * (1 - fx) + a[gy + 1][gx + 1] * fx
            return Math.round(top * (1 - fy) + bot * fy)
        }

        private fun clamp8(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

        private fun encodeJpeg(img: BufferedImage, quality: Float): ByteArray {
            val writer = ImageIO.getImageWritersByFormatName("jpg").next()
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            val bos = ByteArrayOutputStream(1 shl 20)
            ImageIO.createImageOutputStream(bos).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(img, null, null), param)
            }
            writer.dispose()
            return bos.toByteArray()
        }
    }

    /** [count] photos, each its **own** byte array (content cycles through [basePhotos]). */
    private fun photoSet(count: Int): List<ByteArray> =
        List(count) { basePhotos[it % basePhotos.size].copyOf() }

    /** Photo count that should land the finished PDF near [targetBytes]. */
    private fun photoCountFor(targetBytes: Long): Int {
        // JPEG payloads pass through untouched, so the file is the photos plus ~1% of text/xref/font.
        val n = targetBytes / (avgPhotoBytes * 1.012)
        // Round to a whole day so the last grid row is never half-empty.
        return (Math.round(n / PHOTOS_PER_DAY).toInt()).coerceAtLeast(1) * PHOTOS_PER_DAY
    }

    /**
     * The same day-by-day report as [photoReport], with photos supplied as [PdfImageSource]s. The two
     * overloads produce byte-identical documents (`LazyImageSourceTest` pins that), so the peaks below
     * differ only in how the photos reached the engine.
     */
    private fun photoReport(photos: List<ByteArray>): PdfDocumentSpec =
        photoReportOf(photos.map { bytes -> PdfImageSource { bytes } })

    /** A day-by-day photo report: title + a few text rows + a photo grid per day. */
    private fun photoReportOf(photos: List<PdfImageSource>): PdfDocumentSpec {
        val ink = Color(0xFF212529)
        val muted = Color(0xFF6C757D)
        return pdfDocument(PageConfig(margin = 36.dp)) {
            header {
                row(gap = 12.dp) {
                    cell(3f) { text("Site photo log", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ink)) }
                    cell(2f) { text("Synthetic stress fixture", TextStyle(fontSize = 8.sp, color = muted, align = TextAlign.End)) }
                }
                spacer(4.dp)
                divider(muted)
            }
            photos.chunked(PHOTOS_PER_DAY).forEachIndexed { day, chunk ->
                text("Day ${day + 1}", TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ink))
                text("Sector ${day % 9 + 1} · crew 4 · shift 07:00–15:30", TextStyle(fontSize = 8.sp, color = muted))
                text(
                    "Routine inspection walk-through; ${chunk.size} frames captured, no findings recorded.",
                    TextStyle(fontSize = 8.sp, color = ink),
                )
                spacer(4.dp)
                photoGrid(chunk, perRow = 3, cellHeight = 96.dp, gap = 6.dp, fit = PhotoFit.Smart)
                spacer(10.dp)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Instrumentation
    // ---------------------------------------------------------------------------------------------

    /** Daemon poller recording the maximum used heap (total − free) seen while a run is in flight. */
    private class HeapSampler(private val intervalMs: Long = 20) {
        @Volatile private var running = true
        @Volatile private var peak = 0L
        private val thread = Thread {
            val rt = Runtime.getRuntime()
            while (running) {
                val used = rt.totalMemory() - rt.freeMemory()
                if (used > peak) peak = used
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "heap-sampler" }

        fun start(): HeapSampler { thread.start(); return this }
        fun stop(): Long { running = false; thread.join(1_000); return peak }
    }

    /** Full GC + settle, then the used heap with only the fixture live. */
    private fun settle(): Long {
        repeat(3) { System.gc(); Thread.sleep(120) }
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    private class FileSink(file: File) : PdfSink, Closeable {
        private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 20)
        var written = 0L; private set
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            out.write(bytes, offset, length); written += length
        }
        override fun close() { out.flush(); out.close() }
    }

    /**
     * A [FileSink] that stops at a few points *inside* the write, forces a full GC and records the
     * used heap. That is the honest instrument for the lazy path: in a tiny heap the sampled peak is
     * dominated by how long the collector lets dead photo buffers pile up, whereas the post-GC used
     * heap is the live set — the number that must stay constant as the photo count grows.
     */
    private class ProbingFileSink(file: File, private val probeAt: LongArray) : PdfSink, Closeable {
        private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 20)
        var written = 0L; private set
        var liveSetPeak = 0L; private set
        private var next = 0

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            out.write(bytes, offset, length)
            written += length
            while (next < probeAt.size && written >= probeAt[next]) {
                next++
                val rt = Runtime.getRuntime()
                repeat(2) { System.gc(); try { Thread.sleep(80) } catch (_: InterruptedException) {} }
                val live = rt.totalMemory() - rt.freeMemory()
                if (live > liveSetPeak) liveSetPeak = live
            }
        }

        override fun close() { out.flush(); out.close() }
    }

    private class DigestSink : PdfSink {
        private val md = MessageDigest.getInstance("SHA-256")
        var written = 0L; private set
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            md.update(bytes, offset, length); written += length
        }
        fun hex(): String = md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Prints at most a handful of progress lines so a multi-minute run shows liveness. */
    private fun progressLogger(label: String): (Float) -> Unit {
        var next = 0.25f
        return { p -> if (p >= next) { println("  [$label] ${(p * 100).toInt()}%"); next += 0.25f } }
    }

    // ---------------------------------------------------------------------------------------------
    // Structural validation — never loads the file into memory
    // ---------------------------------------------------------------------------------------------

    /**
     * Verifies the on-disk PDF with random access only: header, `startxref` in the tail, the whole
     * xref table walked so every recorded offset really lands on `N 0 obj`, and a `%%EOF` terminator.
     * Returns the object count.
     */
    private fun validatePdfOnDisk(file: File): Int {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(9)
            raf.readFully(header)
            assertEquals("%PDF-1.7\n", header.decodeToString(), "PDF header")

            val tailLen = minOf(2048L, raf.length()).toInt()
            raf.seek(raf.length() - tailLen)
            val tail = ByteArray(tailLen)
            raf.readFully(tail)
            val tailText = tail.decodeToString()
            assertTrue(tailText.trimEnd().endsWith("%%EOF"), "file must end with %%EOF")

            val sxIdx = tailText.lastIndexOf("startxref")
            assertTrue(sxIdx >= 0, "startxref must be present in the last $tailLen bytes")
            val xrefPos = tailText.substring(sxIdx).lineSequence().drop(1).first().trim().toLong()
            assertTrue(xrefPos in 0 until raf.length(), "startxref offset $xrefPos out of file")

            raf.seek(xrefPos)
            assertEquals("xref", raf.readLine(), "startxref must point at the xref keyword")
            val sizeLine = raf.readLine()!!.trim().split(" ")
            assertEquals("0", sizeLine[0], "xref subsection must start at object 0")
            val entries = sizeLine[1].toInt()
            assertTrue(entries > 1, "xref must contain objects, had $entries")
            assertTrue(raf.readLine()!!.startsWith("0000000000 65535 f"), "object 0 must be the free head")

            val offsets = LongArray(entries)
            for (i in 1 until entries) {
                val line = raf.readLine()!!
                assertTrue(line.endsWith("00000 n "), "malformed xref entry $i: '$line'")
                offsets[i] = line.substring(0, 10).toLong()
            }
            val trailer = raf.readLine()
            assertEquals("trailer", trailer, "xref table must be followed by the trailer")
            assertTrue(raf.readLine()!!.contains("/Root"), "trailer must reference /Root")

            // Every recorded offset must land exactly on its own object header.
            val probe = ByteArray(24)
            for (i in 1 until entries) {
                assertTrue(offsets[i] in 9 until raf.length(), "object $i offset ${offsets[i]} out of file")
                raf.seek(offsets[i])
                val n = raf.read(probe)
                val text = String(probe, 0, n, Charsets.ISO_8859_1)
                assertTrue(text.startsWith("$i 0 obj\n"), "xref offset for object $i lands on '${text.take(16)}'")
            }
            return entries - 1
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Runs
    // ---------------------------------------------------------------------------------------------

    private data class Run(
        val label: String,
        val photoCount: Int,
        val photoBytes: Long,
        val pdfBytes: Long,
        val baseline: Long,
        val peak: Long,
        val millis: Long,
        val outcome: String,
        /** Used heap measured after a forced GC *during* the write — the live set, free of the
         *  allocation-rate noise that inflates [peak] in a small heap. 0 when not probed. */
        val liveSet: Long = 0,
    ) {
        val delta get() = peak - baseline
        val liveDelta get() = liveSet - baseline
    }

    /** Static so the table survives JUnit's per-method instance and both tests report into it. */
    private object Results {
        val all = ArrayList<Run>()
    }

    private val results get() = Results.all

    /** Heap figures in MiB (2^20) — the unit the JVM reports heap in. */
    private fun mb(bytes: Long) = String.format(Locale.ROOT, "%.1f MiB", bytes / 1_048_576.0)

    /** File sizes in decimal MB (10^6) — the unit the size targets are stated in. */
    private fun decMb(bytes: Long) = String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)

    /**
     * One small render through both transports, discarded: loads the serializer/deflate/ImageIO
     * classes and lets the JIT settle, so the first *measured* run isn't charged for startup garbage.
     */
    private fun warmUp(reg: ByteArray, bold: ByteArray) {
        val photos = photoSet(PHOTOS_PER_DAY)
        photoReport(photos).renderTo({ _, _, _ -> }, reg, bold)
        photoReport(photos).render(reg, bold)
        settle()
    }

    private fun stressDir(): File = File("build/stress").apply { mkdirs() }

    /** Streams [photos] to a temp file, validates it on disk, deletes it, records the peak. */
    private fun streamRun(label: String, photos: List<ByteArray>, reg: ByteArray, bold: ByteArray): Run {
        val photoBytes = photos.sumOf { it.size.toLong() }
        val file = File(stressDir(), "stress-$label.pdf")
        file.delete()
        val baseline = settle()
        val sampler = HeapSampler().start()
        val t0 = System.nanoTime()
        var outcome = "OK"
        var pdfBytes = 0L
        try {
            FileSink(file).use { sink ->
                photoReport(photos).renderTo(sink, reg, bold, onProgress = progressLogger(label))
                pdfBytes = sink.written
            }
        } catch (e: OutOfMemoryError) {
            outcome = "OOM(renderTo)"
        }
        val millis = (System.nanoTime() - t0) / 1_000_000
        val peak = sampler.stop()

        if (outcome == "OK") {
            assertEquals(pdfBytes, file.length(), "sink byte count must match the file on disk")
            val objects = validatePdfOnDisk(file)
            assertTrue(pdfBytes > photoBytes, "PDF must be larger than its photo payload")
            assertTrue(
                pdfBytes < photoBytes * 1.10 + 4_000_000,
                "PDF ${mb(pdfBytes)} unexpectedly larger than photos ${mb(photoBytes)} + overhead",
            )
            println("  [$label] $objects objects, file ${mb(pdfBytes)}, xref walk OK")
        }
        file.delete()
        val run = Run(label, photos.size, photoBytes, pdfBytes, baseline, peak, millis, outcome)
        results += run
        println(
            "[$label] renderTo photos=${run.photoCount} P=${mb(photoBytes)} F=${mb(pdfBytes)} " +
                "(${decMb(pdfBytes)}) baseline=${mb(baseline)} peak=${mb(peak)} " +
                "delta=${mb(run.delta)} ${millis}ms $outcome"
        )
        return run
    }

    /** `render()` on the same document — the ByteArray path, which is expected to need P + 2F. */
    private fun arrayRun(label: String, photos: List<ByteArray>, reg: ByteArray, bold: ByteArray): Run {
        val photoBytes = photos.sumOf { it.size.toLong() }
        val baseline = settle()
        val sampler = HeapSampler().start()
        val t0 = System.nanoTime()
        var outcome = "OK"
        var pdfBytes = 0L
        var bytes: ByteArray? = null
        try {
            bytes = photoReport(photos).render(reg, bold, onProgress = progressLogger(label))
            pdfBytes = bytes.size.toLong()
        } catch (e: OutOfMemoryError) {
            outcome = "OOM(render)"
        } finally {
            @Suppress("UNUSED_VALUE")
            bytes = null
        }
        val millis = (System.nanoTime() - t0) / 1_000_000
        val peak = sampler.stop()
        settle()
        val run = Run(label, photos.size, photoBytes, pdfBytes, baseline, peak, millis, outcome)
        results += run
        println(
            "[$label] render() photos=${run.photoCount} P=${mb(photoBytes)} F=${mb(pdfBytes)} " +
                "baseline=${mb(baseline)} peak=${mb(peak)} delta=${mb(run.delta)} ${millis}ms $outcome"
        )
        return run
    }

    // ---------------------------------------------------------------------------------------------
    // Tests (name-ordered: they share one JVM and one heap)
    // ---------------------------------------------------------------------------------------------

    /**
     * Byte-identity at scale: a ~50 MB document streamed through a SHA-256 sink (nothing buffered)
     * must hash identically to the array `render()` returns. Also the head-to-head peak comparison —
     * both paths fit at this size, so the extra whole-file array of `render()` is directly visible.
     */
    @Test
    fun a_byteIdentityAndPeakContrastAtFiftyMegabytes() {
        val reg = regular(); val bd = bold()
        warmUp(reg, bd)
        val targetMb = (System.getProperty("stress.identityMb") ?: "50").toLong()
        val photos = photoSet(photoCountFor(targetMb * 1_000_000))
        val photoBytes = photos.sumOf { it.size.toLong() }
        println("[identity] target=${targetMb}MB photos=${photos.size} P=${mb(photoBytes)}")

        val baselineStream = settle()
        val streamSampler = HeapSampler().start()
        val digest = DigestSink()
        photoReport(photos).renderTo(digest, reg, bd)
        val streamPeak = streamSampler.stop()
        val streamedHash = digest.hex()

        val baselineArray = settle()
        val arraySampler = HeapSampler().start()
        var rendered: ByteArray? = photoReport(photos).render(reg, bd)
        val renderedSize = rendered!!.size.toLong()
        val renderedHash = MessageDigest.getInstance("SHA-256").digest(rendered)
            .joinToString("") { "%02x".format(it) }
        @Suppress("UNUSED_VALUE")
        rendered = null
        val arrayPeak = arraySampler.stop()

        println(
            "[identity] F=${mb(renderedSize)} streamed=${mb(digest.written)} " +
                "streamPeak=${mb(streamPeak)} (delta ${mb(streamPeak - baselineStream)}) " +
                "arrayPeak=${mb(arrayPeak)} (delta ${mb(arrayPeak - baselineArray)})"
        )
        println("[identity] sha256 streamed=$streamedHash")
        println("[identity] sha256 render()=$renderedHash")

        assertEquals(renderedSize, digest.written, "streamed byte count must equal render() size")
        assertEquals(renderedHash, streamedHash, "renderTo must stream byte-identical output at scale")

        // The whole-file array is the only structural difference between the two paths, so it must
        // show up as (at least) half a file of extra peak. Skipped for tiny smoke sizes where GC
        // timing noise is the same order as F.
        results += Run("identity-stream", photos.size, photoBytes, renderedSize, baselineStream, streamPeak, 0, "OK")
        results += Run("identity-render", photos.size, photoBytes, renderedSize, baselineArray, arrayPeak, 0, "OK")
        if (renderedSize > 20_000_000) {
            assertTrue(
                arrayPeak - streamPeak > renderedSize / 2,
                "render() must peak at least F/2 above renderTo (stream ${mb(streamPeak)}, " +
                    "array ${mb(arrayPeak)}, F ${mb(renderedSize)})",
            )
        }
        settle()
    }

    /**
     * The ladder: ~50 / ~100 / ~200 MB streamed to disk, then the `render()` contrast at the largest
     * size in the same constrained JVM, then a probe for the streaming ceiling.
     */
    @Test
    fun b_streamingLadderOomContrastAndCeiling() {
        val reg = regular(); val bd = bold()
        warmUp(reg, bd)
        val heapMax = Runtime.getRuntime().maxMemory()
        println("[stress] maxHeap=${mb(heapMax)} avgPhoto=${avgPhotoBytes / 1024} KB")

        val targetsMb = (System.getProperty("stress.targetsMb") ?: "50,100,200")
            .split(",").map { it.trim().toLong() }.filter { it > 0 }

        var lastOkPhotos = 0
        for (targetMb in targetsMb) {
            val label = "${targetMb}MB"
            var photos: List<ByteArray>? = photoSet(photoCountFor(targetMb * 1_000_000))
            val run = streamRun(label, photos!!, reg, bd)
            if (run.outcome == "OK") {
                lastOkPhotos = run.photoCount
                val achievedMb = run.pdfBytes / 1_000_000.0
                assertTrue(
                    achievedMb > targetMb * 0.85 && achievedMb < targetMb * 1.20,
                    "achieved ${String.format(Locale.ROOT, "%.1f", achievedMb)} MB should be near " +
                        "the $targetMb MB target",
                )
            }
            @Suppress("UNUSED_VALUE")
            photos = null
            settle()
        }

        // ---- OOM contrast: the same largest document through the ByteArray path ----
        val biggest = targetsMb.max()
        var contrastPhotos: List<ByteArray>? = photoSet(photoCountFor(biggest * 1_000_000))
        val contrast = arrayRun("${biggest}MB-render", contrastPhotos!!, reg, bd)
        @Suppress("UNUSED_VALUE")
        contrastPhotos = null
        settle()
        if (contrast.outcome == "OK") {
            println(
                "[finding] render() did NOT OOM at $biggest MB in a ${mb(heapMax)} heap — " +
                    "peak ${mb(contrast.peak)} (delta ${mb(contrast.delta)})"
            )
        } else {
            println(
                "[finding] render() OOM'd at $biggest MB in a ${mb(heapMax)} heap while renderTo " +
                    "of the same document succeeded"
            )
        }

        // ---- Ceiling probe: grow the streamed document until renderTo runs out of heap ----
        // Coarse +15% steps to bracket the wall, then a short bisection to pin it down.
        if (System.getProperty("stress.ceiling", "true").toBoolean() && lastOkPhotos > 0) {
            fun probe(count: Int): Run {
                var photos: List<ByteArray>? = try {
                    photoSet(count)
                } catch (e: OutOfMemoryError) {
                    println("[ceiling] OOM allocating $count photos — the photo payload alone is the wall")
                    val r = Run("ceiling-$count", count, 0, 0, 0, 0, 0, "OOM(photos)")
                    results += r
                    settle()
                    return r
                }
                val run = streamRun("ceiling-$count", photos!!, reg, bd)
                @Suppress("UNUSED_VALUE")
                photos = null
                settle()
                return run
            }

            var ok = lastOkPhotos
            var okBytes = results.last { it.photoCount == lastOkPhotos && it.outcome == "OK" }.pdfBytes
            var bad = 0
            var steps = 0
            while (steps < 6) {
                steps++
                val next = (ok * 1.15).toInt() / PHOTOS_PER_DAY * PHOTOS_PER_DAY
                val run = probe(next)
                if (run.outcome == "OK") { ok = next; okBytes = run.pdfBytes } else { bad = next; break }
            }
            var iters = 0
            while (bad > 0 && iters < 3) {
                iters++
                val mid = (ok + bad) / 2 / PHOTOS_PER_DAY * PHOTOS_PER_DAY
                if (mid <= ok || mid >= bad) break
                val run = probe(mid)
                if (run.outcome == "OK") { ok = mid; okBytes = run.pdfBytes } else bad = mid
            }
            println(
                "[ceiling] largest streamed PDF in a ${mb(heapMax)} heap: ${decMb(okBytes)} " +
                    "(${mb(okBytes)}, $ok photos)" +
                    if (bad > 0) "; first failure at $bad photos" else "; no failure found in $steps steps"
            )
        }

        // ---- Report ----
        println()
        println("== memory stress matrix (maxHeap ${mb(heapMax)}) ==")
        println("(heap columns in MiB; F also shown in decimal MB, the unit the targets are stated in)")
        println(String.format(Locale.ROOT, "%-20s %7s %11s %11s %10s %11s %11s %11s %6s  %s",
            "run", "photos", "P", "F(pdf)", "F dec", "baseline", "peak", "peak-base", "sec", "outcome"))
        for (r in results) {
            println(String.format(Locale.ROOT, "%-20s %7d %11s %11s %10s %11s %11s %11s %6.1f  %s",
                r.label, r.photoCount, mb(r.photoBytes), mb(r.pdfBytes), decMb(r.pdfBytes),
                mb(r.baseline), mb(r.peak), mb(r.delta), r.millis / 1000.0, r.outcome))
        }
        println()
        for (r in results.filter { it.outcome == "OK" && it.photoBytes > 0 && it.pdfBytes > 0 }) {
            println(String.format(Locale.ROOT,
                "[ratio] %-20s peak/P = %.2f, peak/F = %.2f, (peak-baseline)/F = %.2f",
                r.label, r.peak.toDouble() / r.photoBytes, r.peak.toDouble() / r.pdfBytes,
                r.delta.toDouble() / r.pdfBytes))
        }

        val streamed = results.filter { it.outcome == "OK" && it.pdfBytes > 0 }
        assertTrue(streamed.isNotEmpty(), "at least one streaming run must succeed")
        val largest = streamed.maxByOrNull { it.pdfBytes }!!
        println(
            "[stress] largest streamed PDF: ${decMb(largest.pdfBytes)} (${mb(largest.pdfBytes)}) " +
                "in a ${mb(heapMax)} heap — peak ${mb(largest.peak)} = " +
                String.format(Locale.ROOT, "%.2f", largest.peak.toDouble() / largest.photoBytes) + " x P"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Lazy sources: the ladder with no P term at all
    // ---------------------------------------------------------------------------------------------

    /** Live-set budget above the settled baseline: a couple of photos, the object list, the fonts. */
    private val liveSetBudget = 32L * 1024 * 1024

    /**
     * Writes the distinct fixture photos to disk once. The document then addresses them by file, so
     * the JVM never holds the photo set — which is the whole point. Content cycles through these the
     * same way the eager fixture cycles [basePhotos]; each `readBytes()` is still an independent read
     * of a real file into a fresh array.
     */
    private fun lazyPhotoFiles(dir: File): List<File> = basePhotos.mapIndexed { i, bytes ->
        File(dir, "photo-$i.jpg").also { if (!it.exists() || it.length() != bytes.size.toLong()) it.writeBytes(bytes) }
    }

    /** [count] file-backed sources — no photo bytes are resident until the engine asks for one. */
    private fun lazySources(files: List<File>, count: Int): List<PdfImageSource> =
        List(count) { i -> files[i % files.size] }.map { f -> PdfImageSource { f.readBytes() } }

    /**
     * **The headline.** ~200 MB and ~400 MB photo reports streamed to disk out of a heap that could
     * not hold a tenth of their photos, with the photos supplied as file-backed [PdfImageSource]s.
     *
     * Asserts, per rung: the write completes, the file is a structurally valid PDF (full xref walk on
     * disk), the photo payload dwarfs the heap ceiling — and, the measurement that matters, the live
     * set sampled mid-write stays inside [liveSetBudget] and does **not** grow with the photo count.
     * A per-photo term would show up immediately: 400 MB is twice the photos of 200 MB.
     *
     * Run it alone (the eager ladder cannot survive this heap):
     * `./gradlew :composepdf:jvmStressTest -PstressHeap=128m -PstressTests="*MemoryStressTest.c_*"`
     */
    @Test
    fun c_lazySourceLadderStreamsFarBeyondTheHeap() {
        val reg = regular(); val bd = bold()
        val heapMax = Runtime.getRuntime().maxMemory()
        val dir = File(stressDir(), "lazy").apply { mkdirs() }
        val files = lazyPhotoFiles(dir)
        val targetsMb = (System.getProperty("stress.lazyTargetsMb") ?: "200,400")
            .split(",").map { it.trim().toLong() }.filter { it > 0 }
        println("[lazy] maxHeap=${mb(heapMax)} avgPhoto=${avgPhotoBytes / 1024} KB, ${files.size} files on disk")

        // Warm-up through the lazy path so class loading / JIT is not charged to the first rung.
        photoReportOf(lazySources(files, PHOTOS_PER_DAY)).renderTo({ _, _, _ -> }, reg, bd)
        settle()

        val runs = ArrayList<Run>()
        try {
            for (targetMb in targetsMb) {
                val label = "lazy-${targetMb}MB"
                val count = photoCountFor(targetMb * 1_000_000)
                val sourceBytes = count.toLong() * avgPhotoBytes
                val file = File(dir, "stress-$label.pdf")
                file.delete()

                val baseline = settle()
                val sampler = HeapSampler().start()
                val t0 = System.nanoTime()
                var outcome = "OK"
                var pdfBytes = 0L
                var liveSet = 0L
                try {
                    // Probe the live set at ~25/50/75% of the expected output — mid photo-pulling.
                    val probes = longArrayOf(sourceBytes / 4, sourceBytes / 2, sourceBytes * 3 / 4)
                    ProbingFileSink(file, probes).use { sink ->
                        photoReportOf(lazySources(files, count))
                            .renderTo(sink, reg, bd, onProgress = progressLogger(label))
                        pdfBytes = sink.written
                        liveSet = sink.liveSetPeak
                    }
                } catch (e: OutOfMemoryError) {
                    outcome = "OOM(renderTo)"
                }
                val millis = (System.nanoTime() - t0) / 1_000_000
                val peak = sampler.stop()

                var objects = 0
                if (outcome == "OK") {
                    assertEquals(pdfBytes, file.length(), "sink byte count must match the file on disk")
                    objects = validatePdfOnDisk(file)
                }
                file.delete()

                val run = Run(label, count, sourceBytes, pdfBytes, baseline, peak, millis, outcome, liveSet)
                runs += run
                results += run
                println(
                    "[$label] renderTo(lazy) photos=$count P(on disk)=${mb(sourceBytes)} " +
                        "F=${mb(pdfBytes)} (${decMb(pdfBytes)}) objects=$objects " +
                        "baseline=${mb(baseline)} peak=${mb(peak)} liveSet=${mb(liveSet)} " +
                        "(live-baseline ${mb(run.liveDelta)}) ${millis}ms $outcome"
                )

                assertEquals("OK", outcome, "lazy $targetMb MB must stream inside a ${mb(heapMax)} heap")
                val achievedMb = pdfBytes / 1_000_000.0
                assertTrue(
                    achievedMb > targetMb * 0.85 && achievedMb < targetMb * 1.20,
                    "achieved ${String.format(Locale.ROOT, "%.1f", achievedMb)} MB should be near the $targetMb MB target",
                )
                assertTrue(
                    run.liveDelta in 0..liveSetBudget,
                    "live set during the write must be a small constant, was ${mb(run.liveDelta)} " +
                        "above baseline (budget ${mb(liveSetBudget)}) for $count photos",
                )
                // Holds at any heap size, unlike a "P > heap" check, which only says how the harness
                // was invoked. This is the engine claim: what is resident is a photo or two, not P.
                assertTrue(
                    run.liveDelta * 20 < sourceBytes,
                    "the live set must be a small fraction of the photo payload: ${mb(run.liveDelta)} " +
                        "resident for ${mb(sourceBytes)} of photos",
                )
                settle()
            }
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }

        // ---- The claim: the live set does not track the photo count ----
        println()
        println("== lazy-source ladder (maxHeap ${mb(heapMax)}) ==")
        println(String.format(Locale.ROOT, "%-16s %7s %11s %11s %10s %11s %11s %11s %6s  %s",
            "run", "photos", "P(disk)", "F(pdf)", "F dec", "baseline", "peak", "live-base", "sec", "outcome"))
        for (r in runs) {
            println(String.format(Locale.ROOT, "%-16s %7d %11s %11s %10s %11s %11s %11s %6.1f  %s",
                r.label, r.photoCount, mb(r.photoBytes), mb(r.pdfBytes), decMb(r.pdfBytes),
                mb(r.baseline), mb(r.peak), mb(r.liveDelta), r.millis / 1000.0, r.outcome))
        }
        for (r in runs) {
            println(String.format(Locale.ROOT,
                "[ratio] %-16s peak/P = %.3f, (live-baseline)/P = %.4f, (live-baseline)/photo = %s",
                r.label, r.peak.toDouble() / r.photoBytes, r.liveDelta.toDouble() / r.photoBytes,
                mb(r.liveDelta / r.photoCount)))
        }

        if (runs.size >= 2) {
            val small = runs.first()
            val big = runs.last()
            assertTrue(
                big.photoCount > small.photoCount * 1.5,
                "the ladder must actually double the photo count to be a proof",
            )
            assertTrue(
                big.liveDelta < small.liveDelta * 1.5 + 8L * 1024 * 1024,
                "the live set must not scale with the photo count: ${small.photoCount} photos → " +
                    "${mb(small.liveDelta)}, ${big.photoCount} photos → ${mb(big.liveDelta)}",
            )
            println(
                "[lazy] ${big.photoCount} photos (${decMb(big.pdfBytes)}) streamed in a ${mb(heapMax)} " +
                    "heap with a ${mb(big.liveDelta)} live set — " +
                    String.format(Locale.ROOT, "%.1fx", big.photoCount.toDouble() / small.photoCount) +
                    " the photos of the ${small.photoCount}-photo rung, " +
                    String.format(Locale.ROOT, "%.2fx", big.liveDelta.toDouble() / small.liveDelta.coerceAtLeast(1)) +
                    " the live set"
            )
        }
    }
}
