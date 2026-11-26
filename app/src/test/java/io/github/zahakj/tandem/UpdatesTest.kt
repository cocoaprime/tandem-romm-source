package io.github.zahakj.tandem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The updater's pure parts (Updates.kt): comparing versions, reading GitHub's release and SHA256SUMS, the notes. */
class UpdatesTest {
    private val hash = "3f".repeat(32)

    private fun release(
        tag: String = "v0.4.1",
        draft: Boolean = false,
        pre: Boolean = false,
        apk: String = "tandem-0.4.1.apk",
        repo: String = Releases.REPOSITORY,
    ) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$pre,"body":"## New\n- Updates","assets":[
          {"name":"$apk","browser_download_url":"https://github.com/$repo/releases/download/$tag/$apk"},
          {"name":"SHA256SUMS","browser_download_url":"https://github.com/$repo/releases/download/$tag/SHA256SUMS"}
        ]}
    """.trimIndent()

    @Test fun versionsCompareByNumberNotText() {
        assertTrue(Releases.compare("0.10.0", "0.9.0") > 0)
        assertTrue(Releases.compare("v0.4.1", "0.4.0") > 0)
        assertTrue(Releases.compare("0.4.0", "0.4.1") < 0)
        assertTrue(Releases.compare("1.0.0", "0.99.99") > 0)
    }

    @Test fun missingNumbersCountAsZero() {
        assertEquals(0, Releases.compare("0.4", "0.4.0"))
        assertEquals(0, Releases.compare("v0.4.0", "0.4.0"))
        assertTrue(Releases.compare("0.4.0.1", "0.4") > 0)
    }

    @Test fun aPreReleaseComesBeforeItsRelease() {
        assertTrue(Releases.compare("0.5.0-rc1", "0.5.0") < 0)
        assertTrue(Releases.compare("0.5.0-rc1", "0.4.9") > 0)
        assertTrue(Releases.compare("0.5.0-rc2", "0.5.0-rc1") > 0)
        // Build metadata doesn't count.
        assertEquals(0, Releases.compare("0.5.0+abc", "0.5.0"))
    }

    @Test fun releaseReadsVersionNotesAndFiles() {
        val r = Releases.latest("[${release()}]", "0.4.0")!!
        assertEquals("0.4.1", r.version)
        assertEquals("tandem-0.4.1.apk", r.apkName)
        assertTrue(r.apkUrl.endsWith("/v0.4.1/tandem-0.4.1.apk"))
        assertTrue(r.sumsUrl.endsWith("/v0.4.1/SHA256SUMS"))
        assertEquals("## New\n- Updates", r.notes)
    }

    @Test fun stableReleaseAcceptsTheRoMMApkName() {
        val stable = release("romm-0.6.1", apk = "Tandem-RoMM.apk")
        val result = Releases.latest("[$stable]", "0.6.1-preview.2")!!
        assertEquals("0.6.1", result.version)
        assertEquals("Tandem-RoMM.apk", result.apkName)
        assertEquals(
            "https://github.com/${Releases.REPOSITORY}/releases/download/romm-0.6.1/Tandem-RoMM.apk",
            result.apkUrl,
        )
        assertEquals(hash, Releases.sumFor("$hash  Tandem-RoMM.apk", result.apkName))
    }

    @Test fun published09PreviewCanAdvanceToStableWithoutOfferingARollback() {
        val preview = release("romm-0.9.0-preview.1", pre = true, apk = "Tandem-RoMM_Preview.apk")
        val stable = release("romm-0.9.0", apk = "Tandem-RoMM.apk")
        assertEquals("0.9.0-preview.1", Releases.latest("[$preview]", "0.8.0")!!.version)
        val next = Releases.latest("[$preview,$stable]", "0.9.0-preview.1")!!
        assertEquals("0.9.0", next.version)
        assertEquals("Tandem-RoMM.apk", next.apkName)
        assertEquals(hash, Releases.sumFor("$hash  Tandem-RoMM.apk", next.apkName))
        assertNull(Releases.latest("[$preview]", "0.9.0-preview.1"))
        assertNull(Releases.latest("[$preview,$stable]", "0.9.0"))
    }

    @Test fun selectsNewestPreviewByVersionFromTheReleaseList() {
        val first = release("romm-0.6.0-preview.9", pre = true, apk = "Tandem-RoMM_Preview.apk")
        val second = release("romm-0.6.0-preview.10", pre = true, apk = "Tandem-RoMM_Preview.apk")
        val result = Releases.latest("[$first,$second]", "0.6.0-preview.1")!!
        assertEquals("0.6.0-preview.10", result.version)
        assertEquals("Tandem-RoMM_Preview.apk", result.apkName)
        assertNull(Releases.latest("[$first,$second]", "0.6.0"))
        val stable = release("v0.6.0", apk = "tandem-0.6.0.apk")
        assertEquals("0.6.0", Releases.latest("[$first,$stable,$second]", "0.5.9")!!.version)
    }

    @Test fun rejectsDraftsIncompleteAssetsAndOtherRepositories() {
        val candidates = listOf(
            release(draft = true),
            release(apk = "tandem-0.4.0.apk"),
            release(repo = "ZahakJ/tandem"),
            release(tag = "v0.4.1/other-tag"),
            """{"tag_name":"v0.4.1","assets":[]}""",
        )
        assertNull(Releases.latest(candidates.joinToString(",", "[", "]"), "0.4.0"))
    }

    @Test fun invalidResponseDoesNotMeanUpToDate() {
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            Releases.latest("""{"message":"Not Found"}""", "0.4.0")
        }
    }

    @Test fun sumsFindTheApkLine() {
        val sums = "${"aa".repeat(32)}  tandem-0.4.0.apk\n$hash  tandem-0.4.1.apk\n"
        assertEquals(hash, Releases.sumFor(sums, "tandem-0.4.1.apk"))
        // Binary-mode lines (`*name`), upper case, Windows line ends.
        assertEquals(hash, Releases.sumFor("${hash.uppercase()} *tandem-0.4.1.apk\r\n", "tandem-0.4.1.apk"))
    }

    @Test fun sumsWithoutTheFileOrWithABadHashAreNull() {
        assertNull(Releases.sumFor("$hash  tandem-0.4.0.apk", "tandem-0.4.1.apk"))
        assertNull(Releases.sumFor("abc123  tandem-0.4.1.apk", "tandem-0.4.1.apk"))
        assertNull(Releases.sumFor("$hash  tandem-0.4.1.apk.sig", "tandem-0.4.1.apk"))
        assertNull(Releases.sumFor("", "tandem-0.4.1.apk"))
    }

    @Test fun notesBecomePlainText() {
        val md = "## What's new\r\n\r\n- **Updates** from [GitHub](https://github.com)\n* `pm install` with Shizuku\n\n\n\nThanks!"
        assertEquals("What's new\n\n• Updates from GitHub\n• pm install with Shizuku\n\nThanks!", Releases.plainNotes(md))
    }

    @Test fun releaseReadsScreenshotsSizeAndDate() {
        val text = """
            {"tag_name":"v0.9.0","published_at":"2026-11-26T15:21:10Z","body":"","assets":[
              {"name":"tandem-0.9.0.apk","size":25165824,"browser_download_url":"https://github.com/cocoaprime/tandem-romm-source/releases/download/v0.9.0/tandem-0.9.0.apk"},
              {"name":"SHA256SUMS","browser_download_url":"https://github.com/cocoaprime/tandem-romm-source/releases/download/v0.9.0/SHA256SUMS"},
              {"name":"whatsnew-2-stage.png","browser_download_url":"https://github.com/cocoaprime/tandem-romm-source/releases/download/v0.9.0/whatsnew-2-stage.png"},
              {"name":"whatsnew-1-lift.webp","browser_download_url":"https://github.com/cocoaprime/tandem-romm-source/releases/download/v0.9.0/whatsnew-1-lift.webp"},
              {"name":"whatsnew-0-private.png","browser_download_url":"https://elsewhere.example.test/private.png"},
              {"name":"whatsnew-notes.txt","browser_download_url":"https://github.com/cocoaprime/tandem-romm-source/releases/download/v0.9.0/whatsnew-notes.txt"},
              {"name":"screenshot.webp","browser_download_url":"https://github.com/cocoaprime/tandem-romm-source/releases/download/v0.9.0/screenshot.webp"}
            ]}
        """.trimIndent()
        val r = Releases.latest("[$text]", "0.8.0")!!
        assertEquals(listOf("whatsnew-1-lift.webp", "whatsnew-2-stage.png"), r.images.map { it.substringAfterLast('/') })
        assertEquals(25165824L, r.apkSize)
        assertEquals("2026-11-26T15:21:10Z", r.published)
        // A release kept by an older Tandem (without these) still reads.
        val old = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(Release.serializer(), """{"version":"0.8.1","notes":"","apkName":"a","apkUrl":"b","sumsUrl":"c"}""")
        assertTrue(old.images.isEmpty())
        assertEquals(0L, old.apkSize)
    }

    @Test fun notesSplitIntoPartsByBoldLinesAndHeadings() {
        val md = """
            **Editing the grid**
            - Y on a tile lifts it in place with Open and Edit. Hold Y to pick it up and move it; X marks more.
            - Upload your own icon, hero or logo, **animated GIF** and WebP included.

            **Layouts**
            - Start › Layout tries every layout live on your own home: L1/R1 to switch, A to keep, B to cancel.

            ## Also:
            * Thock: a new sound pack with deep, muted keyboard thocks.
            * A light buzz on every D-pad move (Settings › Controls › Buttons).
        """.trimIndent()
        val s = Releases.sections(md)
        assertEquals(listOf("Editing the grid", "Layouts", "Also"), s.map { it.title })
        assertEquals(2, s[0].items.size)
        assertEquals("Upload your own icon, hero or logo, animated GIF and WebP included.", s[0].items[1])
        assertEquals(listOf("Thock: a new sound pack with deep, muted keyboard thocks.", "A light buzz on every D-pad move (Settings › Controls › Buttons)."), s[2].items)
    }

    @Test fun notesKeepLeadingTextAndContinuationsAndDropTheRest() {
        val md = "A big one.\r\n\r\n<!-- hidden\nnote -->\n![shot](https://x/y.png)\n---\n" +
            "### Fixes\n1. The `dock` no longer\n   jumps after [a rescan](https://x).\n2) _Faster_ start\n\n**Empty**\n\nThanks!"
        val s = Releases.sections(md)
        assertEquals("", s[0].title)
        assertEquals(listOf("A big one."), s[0].items)
        assertEquals("Fixes", s[1].title)
        assertEquals(listOf("The dock no longer jumps after a rescan.", "Faster start"), s[1].items)
        // A bold line with nothing under it but a paragraph: the paragraph is its point.
        assertEquals("Empty", s[2].title)
        assertEquals(listOf("Thanks!"), s[2].items)
        assertEquals(3, s.size)
        assertTrue(Releases.sections("").isEmpty())
        assertTrue(Releases.sections("**Only a title**").isEmpty())
    }

    @Test fun signerMustMatchOrBeInTheRotationHistory() {
        assertTrue(Releases.sameSigner(setOf("a"), setOf("a"), setOf("a")))
        assertTrue(Releases.sameSigner(setOf("a"), setOf("b"), setOf("a", "b")))
        assertFalse(Releases.sameSigner(setOf("debug"), setOf("release"), setOf("release")))
        assertFalse(Releases.sameSigner(emptySet(), emptySet(), emptySet()))
    }
}
