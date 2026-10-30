package com.tasirin.httpdownloadmanager.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipCreatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `recursive zip skips symlink outside allowed root`() {
        val allowedRoot = temp.newFolder("allowed").toPath()
        val secret = temp.newFolder("secret").toPath()
        val safeFile = allowedRoot.resolve("safe.txt")
        safeFile.writeText("safe")
        val allowedPath = allowedRoot.toFile().absolutePath
        val link = allowedRoot.resolve("link")
        Files.createSymbolicLink(link, secret)

        val bytes = ByteArrayOutputStream().use { raw ->
            ZipOutputStream(raw).use { zip ->
                ZipCreator.zipFile(zip, allowedRoot.toFile(), "") { path ->
                    path == allowedPath || path.startsWith("$allowedPath/")
                }
            }
            raw.toByteArray()
        }

        val names = mutableListOf<String>()
        ByteArrayInputStream(bytes).use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry: ZipEntry = zip.nextEntry ?: break
                    names.add(entry.name)
                    if (entry.name == "allowed/safe.txt") {
                        val content = zip.readBytes().toString(StandardCharsets.UTF_8)
                        assertTrue(content.contains("safe"))
                    }
                    zip.closeEntry()
                }
            }
        }

        assertTrue(names.contains("allowed/safe.txt"))
        assertFalse(names.any { it.startsWith("link") })
    }

    @Test
    fun `recursive zip terminates on symlink cycle`() {
        val root = temp.newFolder("root")
        java.io.File(root, "a.txt").writeText("a")
        Files.createSymbolicLink(root.toPath().resolve("loop"), root.toPath())
        val allowedPath = root.absolutePath
        val bytes = ByteArrayOutputStream().use { raw ->
            ZipOutputStream(raw).use { zip ->
                ZipCreator.zipFile(zip, root, "") { path ->
                    path == allowedPath || path.startsWith("$allowedPath/")
                }
            }
            raw.toByteArray()
        }
        val names = mutableListOf<String>()
        ByteArrayInputStream(bytes).use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry: ZipEntry = zip.nextEntry ?: break
                    names.add(entry.name)
                    zip.closeEntry()
                }
            }
        }
        assertTrue(names.contains("root/a.txt"))
    }

    @Test
    fun `zip entry names are deduplicated`() {
        val used = mutableMapOf<String, Int>()
        assertEquals("a.mp4", ZipCreator.uniqueZipName("a.mp4", used))
        assertEquals("a (1).mp4", ZipCreator.uniqueZipName("a.mp4", used))
        assertEquals("a (2).mp4", ZipCreator.uniqueZipName("a.mp4", used))
        assertEquals("file", ZipCreator.uniqueZipName("file", used))
        assertEquals("file (1)", ZipCreator.uniqueZipName("file", used))
    }

    @Test
    fun `zip entry names never collide with literal suffixed names`() {
        // Nilai harapan dibuktikan port Python 1:1 (issue #912): nama
        // hasil dedupe ikut terdaftar sehingga berkas literal bernama
        // "movie (1).mp4" tidak menghasilkan entri duplikat.
        val used = mutableMapOf<String, Int>()
        assertEquals("movie.mp4", ZipCreator.uniqueZipName("movie.mp4", used))
        assertEquals("movie (1).mp4", ZipCreator.uniqueZipName("movie.mp4", used))
        assertEquals("movie (1) (1).mp4", ZipCreator.uniqueZipName("movie (1).mp4", used))

        val used2 = mutableMapOf<String, Int>()
        assertEquals("movie (1).mp4", ZipCreator.uniqueZipName("movie (1).mp4", used2))
        assertEquals("movie.mp4", ZipCreator.uniqueZipName("movie.mp4", used2))
        assertEquals("movie (2).mp4", ZipCreator.uniqueZipName("movie.mp4", used2))
    }

    @Test
    fun `recursive zip deduplicates normalized entry names`() {
        val root = temp.newFolder("dup")
        // Dua nama berbeda yang ternormalisasi sama (kontrol C0 -> '_'):
        // tanpa dedupe, ZipException duplikat menggugurkan seluruh arsip.
        java.io.File(root, "bad\u0001name.txt").writeText("one")
        java.io.File(root, "bad\u0002name.txt").writeText("two")
        val allowedPath = root.absolutePath
        val bytes = ByteArrayOutputStream().use { raw ->
            ZipOutputStream(raw).use { zip ->
                ZipCreator.zipFile(zip, root, "") { path ->
                    path == allowedPath || path.startsWith("$allowedPath/")
                }
            }
            raw.toByteArray()
        }
        val names = mutableListOf<String>()
        ByteArrayInputStream(bytes).use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry: ZipEntry = zip.nextEntry ?: break
                    names.add(entry.name)
                    zip.closeEntry()
                }
            }
        }
        assertTrue(names.contains("dup/bad_name.txt"))
        assertTrue(names.contains("dup/bad_name (1).txt"))
    }

    @Test
    fun `token dir root name is sanitized like other entries`() {
        val used = mutableMapOf<String, Int>()
        assertEquals("folder", ZipCreator.tokenDirRootName("..", used))
        assertEquals("file.txt", ZipCreator.tokenDirRootName("..\\file.txt", used))
        assertEquals("bad_name", ZipCreator.tokenDirRootName("bad\u0001name", used))
        assertEquals("folder (1)", ZipCreator.tokenDirRootName("..", used))
    }

    @Test
    fun `zip budget caps entries at max`() {
        val budget = ZipCreator.ZipBudget()
        var taken = 0
        while (budget.tryTake()) taken++
        assertEquals(ZipCreator.MAX_ZIP_ENTRIES, taken)
        assertFalse(budget.tryTake())
    }

    @Test
    fun `entry path blocks traversal separators and control chars`() {
        assertEquals("folder/file.txt", ZipCreator.safeEntryPath("../folder/..\\file.txt"))
        assertEquals("file.txt", ZipCreator.safeEntryPath("/../../file.txt"))
        assertEquals("bad_name.txt", ZipCreator.safeEntryPath("bad\u0000name.txt"))
        assertEquals("", ZipCreator.safeEntryPath(".."))
    }

    @Test
    fun `stamp fold matches fnv1a reference vectors`() {
        // Basis FNV-1a 64 yang dipakai zipFsSourceStamp; nilai harapan
        // dibuktikan port Python 1:1 dari implementasi Kotlin (issue #973).
        val basis = -3750763034362895579L
        assertEquals(basis, ZipCreator.zipStampFold(basis, ""))
        assertEquals(-5808556873153909620L, ZipCreator.zipStampFold(basis, "a"))
        assertEquals(-1792535898324117685L, ZipCreator.zipStampFold(basis, "abc"))
        assertEquals(
            -1676209753061295567L,
            ZipCreator.zipStampFold(basis, "/storage/emulated/0/Download")
        )
        assertEquals(-1912366794928059912L, ZipCreator.zipStampFold(basis, "12345"))
    }

    @Test
    fun `fs stamp of single file is length and mtime`() {
        val f = temp.newFile("one.bin")
        f.writeBytes(ByteArray(7))
        assertTrue(f.setLastModified(1700000000000L))
        assertEquals("f7:1700000000000", ZipCreator.zipFsSourceStamp(f))
        assertEquals(
            "none",
            ZipCreator.zipFsSourceStamp(java.io.File(f.parentFile, "missing.bin"))
        )
    }

    @Test
    fun `fs stamp of empty folder has zero count and complete flag`() {
        val dir = temp.newFolder("empty")
        assertTrue(dir.setLastModified(1700000000000L))
        val stamp = ZipCreator.zipFsSourceStamp(dir)
        assertTrue(stamp.startsWith("d0:"))
        assertTrue(stamp.endsWith(":c:1700000000000"))
    }

    @Test
    fun `fs stamp is deterministic and detects content add and rename changes`() {
        val dir = temp.newFolder("tree")
        val a = java.io.File(dir, "a.txt")
        a.writeText("alpha")
        val sub = java.io.File(dir, "sub")
        assertTrue(sub.mkdir())
        java.io.File(sub, "b.txt").writeText("beta")
        val first = ZipCreator.zipFsSourceStamp(dir)
        assertEquals(first, ZipCreator.zipFsSourceStamp(dir))
        // Perubahan isi (panjang berubah) menggerakkan cap.
        a.writeText("alpha-longer")
        val afterEdit = ZipCreator.zipFsSourceStamp(dir)
        assertTrue(first != afterEdit)
        // Penambahan berkas menggerakkan cap.
        java.io.File(dir, "c.txt").writeText("gamma")
        val afterAdd = ZipCreator.zipFsSourceStamp(dir)
        assertTrue(afterEdit != afterAdd)
        // Ganti nama menggerakkan cap (path ikut dilipat).
        assertTrue(a.renameTo(java.io.File(dir, "a-renamed.txt")))
        assertTrue(afterAdd != ZipCreator.zipFsSourceStamp(dir))
    }

    // Implementasi acuan yang DIBEKUKAN di sisi uji (issue #991):
    // salinan algoritma komposisi cap saat ini (basis, urutan lipat,
    // plafon 8192, lipatan dangkal, sufiks). Uji paritas di bawah
    // membandingkannya dengan produksi atas pohon yang sama, jadi
    // perubahan komposisi produksi yang tak disengaja membuat CI
    // merah walau nilai emas mentah tak bisa dipakai (path absolut
    // folder sementara berbeda tiap mesin).
    private fun referenceFsStamp(root: java.io.File): String {
        if (root.isFile) return "f" + root.length() + ":" + root.lastModified()
        if (!root.isDirectory) return "none"
        var hash = -3750763034362895579L
        var count = 0
        var truncated = false
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque<java.io.File>()
        stack.add(root)
        while (stack.isNotEmpty() && !truncated) {
            val dir = stack.removeLast()
            val canon = runCatching { dir.canonicalPath }.getOrNull() ?: dir.absolutePath
            if (!seen.add(canon)) continue
            val children = dir.listFiles()?.sortedWith(
                Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
            ) ?: continue
            for (child in children) {
                if (count >= 8192) {
                    truncated = true
                    break
                }
                count++
                hash = ZipCreator.zipStampFold(hash, child.absolutePath)
                hash = ZipCreator.zipStampFold(hash, child.length().toString())
                hash = ZipCreator.zipStampFold(hash, child.lastModified().toString())
                if (child.isDirectory) stack.add(child)
            }
        }
        if (truncated) {
            var shallowBudget = 8192
            while (stack.isNotEmpty()) {
                val queued = stack.removeLast()
                hash = ZipCreator.zipStampFold(hash, queued.absolutePath)
                hash = ZipCreator.zipStampFold(hash, queued.length().toString())
                hash = ZipCreator.zipStampFold(hash, queued.lastModified().toString())
                if (shallowBudget > 0) {
                    val kids = queued.listFiles()?.sortedWith(
                        Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
                    )
                    if (kids != null) {
                        for (kid in kids) {
                            if (shallowBudget <= 0) break
                            shallowBudget--
                            hash = ZipCreator.zipStampFold(hash, kid.absolutePath)
                            hash = ZipCreator.zipStampFold(hash, kid.length().toString())
                            hash = ZipCreator.zipStampFold(hash, kid.lastModified().toString())
                        }
                    }
                }
            }
        }
        return "d" + count + ":" + hash + ":" + (if (truncated) "t" else "c") +
            ":" + root.lastModified()
    }

    @Test
    fun `fs stamp matches frozen reference composition and detects deletion`() {
        val dir = temp.newFolder("parity")
        val sub = java.io.File(dir, "sub")
        assertTrue(sub.mkdir())
        java.io.File(dir, "a.txt").writeText("alpha")
        val victim = java.io.File(sub, "b.txt")
        victim.writeText("beta")
        java.io.File(sub, "c.txt").writeText("gamma")
        val before = ZipCreator.zipFsSourceStamp(dir)
        assertEquals(referenceFsStamp(dir), before)
        // Hapus berkas menggerakkan cap (arah yang belum terkunci #979).
        assertTrue(victim.delete())
        val after = ZipCreator.zipFsSourceStamp(dir)
        assertTrue(before != after)
        assertEquals(referenceFsStamp(dir), after)
    }

    @Test
    fun `fs stamp flags truncation past the entry ceiling`() {
        val dir = temp.newFolder("wide")
        for (i in 1..8200) {
            java.io.File(dir, "f" + i + ".bin").createNewFile()
        }
        val stamp = ZipCreator.zipFsSourceStamp(dir)
        assertTrue(stamp.startsWith("d8192:"))
        assertTrue(stamp.contains(":t:"))
        assertEquals(referenceFsStamp(dir), stamp)
    }

    @Test
    fun `stamp fold is order sensitive and chains like concatenation`() {
        val basis = -3750763034362895579L
        val chained = ZipCreator.zipStampFold(ZipCreator.zipStampFold(basis, "ab"), "c")
        assertEquals(ZipCreator.zipStampFold(basis, "abc"), chained)
        val reversed = ZipCreator.zipStampFold(ZipCreator.zipStampFold(basis, "c"), "ab")
        assertEquals(-728750701741308783L, reversed)
        assertTrue(chained != reversed)
    }
}
