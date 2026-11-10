package fieldradio.profile

import kotlin.test.*

class ProfileTest {
    private val ok = Profile(name = "Procession", streamName = "procession-1")

    @Test fun validProfilePasses() = assertTrue(validate(ok).isEmpty())

    @Test fun streamNameRules() {
        for (bad in listOf("", "Upper", "has space", "a".repeat(33), "ä", "../x"))
            assertTrue(validate(ok.copy(streamName = bad)).isNotEmpty(), bad)
    }

    @Test fun whisperRejectedUntil2() {
        val p = ok.copy(captions = CaptionSettings(true, "fi", SttEngine.WHISPER_BUNDLED))
        assertTrue(validate(p).any { "2.0" in it })
    }

    @Test fun captionsNeedEngine() =
        assertTrue(validate(ok.copy(captions = CaptionSettings(enabled = true))).isNotEmpty())

    @Test fun exportStripsInviteCode() {
        val p = ok.copy(server = ServerSettings("https://c.example", "secret"))
        assertFalse("secret" in exportJson(p))
        assertTrue("secret" in storeJson(p))
    }

    @Test fun roundTripAndEngineNames() {
        val p = ok.copy(captions = CaptionSettings(true, "fi", SttEngine.ANDROID_ONDEVICE))
        val s = storeJson(p)
        assertTrue("android-ondevice" in s)
        assertEquals(p, parseProfile(s))
    }

    @Test fun unsafeSoundPaths() {
        val p = ok.copy(library = listOf(SoundItem("a", "x", "../../etc/passwd")))
        assertTrue(validate(p).any { "unsafe" in it })
    }
}
