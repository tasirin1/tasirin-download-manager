package com.tasirin.httpdownloadmanager.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUrlTest {

    @Test
    fun `host github asli - true`() {
        assertTrue(isGitHubUrl("https://github.com/a/b.apk"))
        assertTrue(isGitHubUrl("https://raw.githubusercontent.com/a/b"))
        assertTrue(isGitHubUrl("https://release-assets.githubusercontent.com/x"))
        assertTrue(isGitHubUrl("https://user.github.io/file.zip"))
    }

    @Test
    fun `github hanya di path atau query - false`() {
        // Substring "github.com" di luar host tak boleh memicu mirror proxy.
        assertFalse(isGitHubUrl("https://evil.com/?x=github.com"))
        assertFalse(isGitHubUrl("https://evil.com/github.com/a.apk"))
        assertFalse(isGitHubUrl("https://notgithub.com/a.apk"))
        assertFalse(isGitHubUrl("https://github.com.evil.com/a.apk"))
    }

    @Test
    fun `skema non-https - false`() {
        assertFalse(isGitHubUrl("http://github.com/a.apk"))
        assertFalse(isGitHubUrl("ftp://github.com/a.apk"))
        assertFalse(isGitHubUrl(""))
    }
}
