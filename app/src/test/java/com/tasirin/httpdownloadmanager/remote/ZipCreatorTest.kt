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
}
