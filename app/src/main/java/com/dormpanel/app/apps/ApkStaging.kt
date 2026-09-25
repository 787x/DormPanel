package com.dormpanel.app.apps

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

data class ApkStagedFile(val file: File, val filename: String, val source: String, val size: Long, val sha256: String)
data class ApkMetadata(val staged: ApkStagedFile, val label: String, val packageName: String,
    val versionName: String, val versionCode: Long, val signingSha256: String,
    val installedVersion: String?, val versionRelation: String?, val signatureMatches: Boolean?)

class ApkRejected(message: String) : Exception(message)

/** Pure streaming primitive so the byte limit and cleanup can be tested without Android. */
object ApkFileSink {
    fun write(input: InputStream, file: File, limit: Long): Pair<Long, String> {
        var size = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            file.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > limit) throw ApkRejected("APK exceeds the size limit.")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            if (size == 0L) throw ApkRejected("APK is empty.")
            return size to digest.digest().hex()
        } catch (error: Exception) { file.delete(); throw error }
    }
}

/** All transports use this bounded disk sink. Neither Content-Length nor filenames are trusted. */
class ApkStaging(private val context: Context) {
    companion object { const val LIMIT = 256L * 1024 * 1024 }
    private val directory = File(context.cacheDir, "apk-staging").apply { mkdirs() }

    init { cleanupStale() }

    fun cleanupStale(now: Long = System.currentTimeMillis()) {
        directory.listFiles()?.filter { it.isFile && now - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
    }

    fun stage(input: InputStream, filename: String, source: String, lengthHint: Long = -1): ApkStagedFile {
        if (lengthHint > LIMIT) throw ApkRejected("APK exceeds the 256 MiB limit.")
        val safeName = filename.substringAfterLast('/').substringAfterLast('\\').take(120)
            .filter { it.isLetterOrDigit() || it in " ._-()" }.ifBlank { "package.apk" }
        if (!safeName.endsWith(".apk", true)) throw ApkRejected("Choose one .apk file.")
        val file = File(directory, "${UUID.randomUUID()}.apk")
        val (size, hash) = ApkFileSink.write(input, file, LIMIT)
        return ApkStagedFile(file, safeName, source, size, hash)
    }

    fun validate(staged: ApkStagedFile): ApkMetadata {
        try {
            ZipFile(staged.file).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") == null) throw ApkRejected("File is not an Android APK.")
            }
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(staged.file.absolutePath, PackageManager.GET_SIGNATURES)
                ?: throw ApkRejected("Android cannot read this APK.")
            if (info.packageName.isNullOrBlank() || info.applicationInfo == null) throw ApkRejected("APK package metadata is incomplete.")
            val signatures = @Suppress("DEPRECATION") (info.signatures?.toList().orEmpty())
            if (signatures.isEmpty()) throw ApkRejected("APK has no signing certificate.")
            val installed = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(info.packageName, PackageManager.GET_SIGNATURES)
            } catch (_: PackageManager.NameNotFoundException) { null }
            val matches = installed?.let { current ->
                @Suppress("DEPRECATION")
                fingerprints(current.signatures?.toList().orEmpty()) == fingerprints(signatures)
            }
            @Suppress("DEPRECATION") val candidateCode = info.longVersionCode
            @Suppress("DEPRECATION") val currentCode = installed?.longVersionCode
            val relation = currentCode?.let { when {
                candidateCode > it -> "Update"
                candidateCode == it -> "Same version"
                else -> "Older version"
            } }
            val applicationInfo = info.applicationInfo ?: throw ApkRejected("APK has no application metadata.")
            applicationInfo.sourceDir = staged.file.absolutePath
            applicationInfo.publicSourceDir = staged.file.absolutePath
            val label = runCatching { pm.getApplicationLabel(applicationInfo).toString() }.getOrDefault(info.packageName)
            return ApkMetadata(staged, label, info.packageName, info.versionName ?: "Unknown", candidateCode,
                fingerprints(signatures).joinToString(", "), installed?.let { "${it.versionName ?: "Unknown"} (${it.longVersionCode})" }, relation, matches)
        } catch (error: Exception) {
            staged.file.delete()
            if (error is java.util.zip.ZipException || error is IllegalArgumentException)
                throw ApkRejected("File is not a valid Android APK.")
            throw error
        }
    }

    private fun fingerprints(signatures: List<Signature>) = signatures.map {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
    }.sorted()
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
