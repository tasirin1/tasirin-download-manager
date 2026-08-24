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
                    if (entry.name == "safe.txt") {
                        val content = zip.readBytes().toString(StandardCharsets.UTF_8)
                        assertTrue(content.contains("safe"))
                    }
                    zip.closeEntry()
                }
            }
        }

        assertTrue(names.contains("safe.txt"))
        assertFalse(names.any { it.startsWith("link") })
    }
}
