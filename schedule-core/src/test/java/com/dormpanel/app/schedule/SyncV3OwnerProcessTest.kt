package com.dormpanel.app.schedule

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.UUID

object V3OwnerWorker {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]); val j = V3Journal(root)
        val o = V3Object(1, UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val p = V3Publisher(j, object : V3PublicationStore { override fun prepare(scope: String) {}; override fun read(path: String): ByteArray? = null; override fun putOnce(path: String, raw: ByteArray) { error("must not PUT before marker") } })
        j.transaction { it.put("local", "saved-user-edit") }; p.stage(V3Paths.path(o), SyncV3Codec.encode(o), JSONObject().put("capture", "exact"))
        j.meta.fault = { phase -> if (phase == "after-commit" && j.meta.get("state", "frame")?.getJSONObject("pending")?.getString("phase") == "ATTEMPTED") { File(root, "boundary").writeText("ATTEMPTED"); Thread.sleep(Long.MAX_VALUE) } }
        p.resume()
    }
}

class SyncV3OwnerProcessTest {
    @Test fun secondJvmCannotWriteAndForceKillReleasesOwnerWithoutRepeatingPut() {
        val root = Files.createTempDirectory("v3-process-").toFile(); val entries = linkedSetOf<String>()
        entries.addAll(System.getProperty("java.class.path").split(File.pathSeparator))
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) { if (loader is URLClassLoader) entries.addAll(loader.urLs.map { File(it.toURI()).absolutePath }); loader = loader.parent }
        val java = File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        val child = ProcessBuilder(java.absolutePath, "-cp", entries.joinToString(File.pathSeparator), V3OwnerWorker::class.java.name, root.absolutePath)
            .redirectErrorStream(true).redirectOutput(File(root, "worker.log")).start()
        try {
            repeat(500) { if (!File(root, "boundary").exists() && child.isAlive) Thread.sleep(20) }
            assertTrue(File(root, "worker.log").readText(), File(root, "boundary").exists())
            val lock = File(root, "v3-owner.lock"); val length = lock.length(); val modified = lock.lastModified()
            assertThrows(Exception::class.java) { V3Journal(root) }; assertEquals(length, lock.length()); assertEquals(modified, lock.lastModified())
            child.destroyForcibly(); child.waitFor(); val j = V3Journal(root); assertEquals("saved-user-edit", j.snapshot().getString("local")); var puts = 0
            val publisher = V3Publisher(j, object : V3PublicationStore { override fun prepare(scope: String) {}; override fun read(path: String): ByteArray? = null; override fun putOnce(path: String, raw: ByteArray) { puts++ } })
            assertEquals("UNCERTAIN", publisher.resume()); assertEquals(0, puts); assertEquals("exact", j.snapshot().getJSONObject("pending").getJSONObject("capture").getString("capture")); j.close()
        } finally { if (child.isAlive) child.destroyForcibly().waitFor() }
    }
}
