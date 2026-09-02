package com.example.areadtext.model

import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts model archives (.tar.bz2 or .zip) into [outputDir], then flattens a
 * single top-level directory so the engine finds encoder/decoder/tokens at the root.
 */
object ArchiveExtractor {

    private const val TAG = "ArchiveExtractor"

    fun extract(archive: File, outputDir: File) {
        outputDir.mkdirs()
        when {
            archive.name.endsWith(".tar.bz2", true) ||
                archive.name.endsWith(".tbz2", true) -> extractTarBz2(archive, outputDir)
            archive.name.endsWith(".zip", true) -> extractZip(archive, outputDir)
            archive.name.endsWith(".tar.gz", true) ||
                archive.name.endsWith(".tgz", true) -> extractTarGz(archive, outputDir)
            else -> throw IllegalArgumentException("Unsupported archive: ${archive.name}")
        }
        flattenSingleTopDir(outputDir)
    }

    private fun extractTarBz2(archive: File, out: File) {
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        ).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isDirectory) continue
                writeEntry(out, e.name, tar)
            }
        }
    }

    private fun extractTarGz(archive: File, out: File) {
        TarArchiveInputStream(
            java.util.zip.GZIPInputStream(BufferedInputStream(FileInputStream(archive)))
        ).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isDirectory) continue
                writeEntry(out, e.name, tar)
            }
        }
    }

    private fun extractZip(archive: File, out: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                writeEntry(out, e.name, zip)
                zip.closeEntry()
            }
        }
    }

    private fun writeEntry(out: File, entryName: String, input: java.io.InputStream) {
        val safe = entryName.replace('\\', '/').replace(Regex("^/+"), "")
        if (safe.contains("..")) return // guard against path traversal
        val dest = File(out, safe)
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { os ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } >= 0) os.write(buf, 0, n)
        }
    }

    /** If the archive wrapped everything in one folder, move its contents up a level. */
    private fun flattenSingleTopDir(dir: File) {
        val children = dir.listFiles() ?: return
        if (children.size != 1 || !children[0].isDirectory) return
        val top = children[0]
        top.listFiles()?.forEach { f ->
            val target = File(dir, f.name)
            if (!f.renameTo(target)) {
                Log.w(TAG, "failed to flatten ${f.name}")
            }
        }
        top.delete()
    }
}
