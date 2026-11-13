package io.github.quentinpn.viewer.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateManifestTest {
    // SHA-256 of "abc"
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun manifest(
        code: String = "6",
        name: String = "\"1.1.0\"",
        url: String = "\"http://git.example.com/a.apk\"",
        sha: String = "\"$abc\"",
        size: String = "3",
    ) = """{"versionCode":$code,"versionName":$name,"notes":"Corrections","url":$url,"sha256":$sha,"size":$size,"extra":1}"""

    @Test
    fun parsesAValidManifest() {
        val m = parseManifest(manifest())
        assertNotNull(m)
        m!!
        assertEquals(6, m.versionCode)
        assertEquals("1.1.0", m.versionName)
        assertEquals("Corrections", m.notes)
        assertEquals(3L, m.size)
    }

    @Test
    fun notesAreOptional() {
        val m = parseManifest("""{"versionCode":6,"versionName":"1.1.0","url":"https://x/a.apk","sha256":"$abc","size":3}""")
        assertEquals("", m!!.notes)
    }

    @Test
    fun malformedManifestsAreRejected() {
        assertNull(parseManifest("not json"))
        assertNull(parseManifest("[]"))
        assertNull(parseManifest("{}"))
        assertNull(parseManifest(manifest(code = "\"six\"")))
        assertNull(parseManifest(manifest(code = "0")))
        assertNull(parseManifest(manifest(name = "\" \"")))
        assertNull(parseManifest(manifest(sha = "\"abc\"")))
        assertNull(parseManifest(manifest(sha = "\"${"z".repeat(64)}\"")))
        assertNull(parseManifest(manifest(size = "0")))
        assertNull(parseManifest(manifest(url = "\"file:///sdcard/a.apk\"")))
        assertNull(parseManifest(manifest(url = "null")))
    }

    @Test
    fun onlyAHigherVersionCodeIsNewer() {
        val m = parseManifest(manifest())!!
        assertTrue(isNewer(m, 5))
        assertFalse(isNewer(m, 6))
        assertFalse(isNewer(m, 7))
    }

    @Test
    fun sha256OfAFileIsChecked() {
        val file = File.createTempFile("update", ".apk").apply { deleteOnExit(); writeText("abc") }
        assertEquals(abc, file.inputStream().use(::sha256Hex))
        assertTrue(matchesManifest(file, 3, abc))
        assertTrue(matchesManifest(file, 3, abc.uppercase()))
        assertFalse(matchesManifest(file, 4, abc))
        assertFalse(matchesManifest(file, 3, abc.replace('b', 'c')))
        assertFalse(matchesManifest(File(file.parentFile, "missing-${System.nanoTime()}"), 3, abc))
    }
}
