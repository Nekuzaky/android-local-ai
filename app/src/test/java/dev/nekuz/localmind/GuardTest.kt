package dev.nekuz.localmind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AddressGuardTest {
    private fun v(text: String) = InetAddress.getByName(text).address

    @Test
    fun allowsPrivateLoopbackAndLinkLocalV4() {
        listOf(
            "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.255.255",
            "127.0.0.1", "127.255.255.255", "169.254.0.1", "169.254.169.254",
        ).forEach { assertTrue(it, AddressGuard.isAllowed(v(it))) }
    }

    @Test
    fun refusesEverythingElseV4() {
        listOf(
            "8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "169.253.0.1", "169.255.0.1",
            "100.64.0.1", "0.0.0.0", "224.0.0.1", "239.255.255.250", "255.255.255.255", "11.0.0.1", "126.0.0.1", "128.0.0.1", "198.18.0.1",
        ).forEach { assertFalse(it, AddressGuard.isAllowed(v(it))) }
    }

    @Test
    fun v6Ranges() {
        listOf("::1", "fe80::1", "febf::1", "fc00::1", "fd12:3456::1").forEach { assertTrue(it, AddressGuard.isAllowed(v(it))) }
        listOf("::", "::2", "2001:4860:4860::8888", "fec0::1", "ff02::1", "64:ff9b::808:808", "2002:808:808::1", "fe00::1").forEach {
            assertFalse(it, AddressGuard.isAllowed(v(it)))
        }
    }

    @Test
    fun mappedV4IsCheckedAsV4() {
        val mapped = ByteArray(16).also {
            it[10] = 0xFF.toByte()
            it[11] = 0xFF.toByte()
        }
        val private = mapped.copyOf().also {
            it[12] = 192.toByte()
            it[13] = 168.toByte()
            it[15] = 1
        }
        val public = mapped.copyOf().also {
            it[12] = 8
            it[13] = 8
            it[14] = 8
            it[15] = 8
        }
        assertTrue(AddressGuard.isAllowed(private))
        assertFalse(AddressGuard.isAllowed(public))
    }

    @Test
    fun badLengthsAreRefused() {
        assertFalse(AddressGuard.isAllowed(null))
        assertFalse(AddressGuard.isAllowed(ByteArray(0)))
        assertFalse(AddressGuard.isAllowed(ByteArray(3)))
        assertFalse(AddressGuard.isAllowed(ByteArray(5)))
        assertFalse(AddressGuard.isAllowed(ByteArray(15)))
    }

    private fun resolving(vararg addresses: String) = HostResolver { addresses.map { v(it) } }

    @Test
    fun allResolvedAddressesMustBeAllowed() {
        val endpoint = Endpoint("pc.lan", 11434)
        assertTrue(AddressGuard.check(endpoint, resolving("192.168.1.5")) is GuardResult.Allowed)
        assertTrue(AddressGuard.check(endpoint, resolving("192.168.1.5", "fd00::5")) is GuardResult.Allowed)
        assertTrue(AddressGuard.check(endpoint, resolving("192.168.1.5", "8.8.8.8")) is GuardResult.Refused)
        assertTrue(AddressGuard.check(endpoint, resolving("8.8.8.8", "192.168.1.5")) is GuardResult.Refused)
        assertTrue(AddressGuard.check(endpoint, resolving()) is GuardResult.Refused)
    }

    @Test
    fun resolverFailureIsRefusal() {
        val result = AddressGuard.check(Endpoint("pc.lan", 1)) { throw IllegalStateException("boom") }
        assertTrue(result is GuardResult.Refused)
    }

    @Test
    fun connectsToTheCheckedLiteralNotTheName() {
        val allowed = AddressGuard.check(Endpoint("pc.lan", 8188), resolving("192.168.1.5")) as GuardResult.Allowed
        assertEquals("http://192.168.1.5:8188", allowed.baseUrl)
        val v6 = AddressGuard.check(Endpoint("pc.lan", 8188), resolving("fd00::5")) as GuardResult.Allowed
        assertEquals("http://[fd00:0:0:0:0:0:0:5]:8188", v6.baseUrl)
    }
}

class EndpointsTest {
    @Test
    fun acceptsPlainForms() {
        assertEquals(Endpoint("192.168.1.2", 11434), Endpoints.parse("192.168.1.2", 11434))
        assertEquals(Endpoint("192.168.1.2", 99), Endpoints.parse("192.168.1.2:99", 11434))
        assertEquals(Endpoint("192.168.1.2", 99), Endpoints.parse("http://192.168.1.2:99/", 11434))
        assertEquals(Endpoint("localhost", 8188), Endpoints.parse("  localhost:8188  ", 1))
        assertEquals(Endpoint("my-pc.lan", 5), Endpoints.parse("my-pc.lan:5", 1))
        assertEquals(Endpoint("fd00::1", 8), Endpoints.parse("[fd00::1]:8", 1))
        assertEquals(Endpoint("fd00::1", 1), Endpoints.parse("[fd00::1]", 1))
        assertEquals(Endpoint("fe80::1", 1), Endpoints.parse("fe80::1", 1))
    }

    @Test
    fun refusesHostileForms() {
        listOf(
            null, "", " ", "https://192.168.1.2", "ftp://192.168.1.2", "user@192.168.1.2", "http://user:pass@192.168.1.2",
            "192.168.1.2/path", "192.168.1.2?x=1", "192.168.1.2#x", "192.168.1.2:0", "192.168.1.2:65536", "192.168.1.2:abc",
            "192.168.1.2:-1", "evil.com\\@192.168.1.2", "192.168.1.2 evil.com", "a\nb", "[fe80::1%wlan0]", "fe80::1%25wlan0",
            "[fd00::1]x", "[fd00::1", "[1.2.3.4]", "-bad.lan", "a..b", ".lan", "exa_mple.lan", "x".repeat(400), "http://", "/",
            "file:///etc/passwd", "%31%39%32.168.1.2", "192.168.1.2:80:80", ":::", "1:2:3", "1::2::3", "12345::1", "g::1", "[::ffff:1.2.3.4]:5", "1:2:3:4:5:6:7:8:9",
        ).forEach { assertNull(it.toString(), Endpoints.parse(it, 11434)) }
    }

    @Test
    fun displayRoundTrips() {
        listOf("192.168.1.2:9", "[fd00::1]:9", "pc.lan:9").forEach {
            val endpoint = Endpoints.parse(it, 1)
            assertNotNull(endpoint)
            assertEquals(it, endpoint!!.display)
            assertEquals(endpoint, Endpoints.parse(endpoint.display, 1))
        }
    }
}

class DownloadUrlsTest {
    @Test
    fun catalogueUrlsAreAllowed() {
        Catalogue.models.forEach { assertTrue(it.url, DownloadUrls.isAllowed(it.url)) }
    }

    @Test
    fun knownHostsAreAllowed() {
        listOf(
            "https://huggingface.co/a/b/resolve/c/d.gguf",
            "https://HUGGINGFACE.CO/a",
            "https://hf.co/a",
            "https://cdn-lfs.huggingface.co/x",
            "https://cdn-lfs.hf.co/x",
            "https://us.aws.cdn.hf.co/xet-bridge-us/abc/def?X-Amz=1&b=2",
            "https://cas-bridge.xethub.hf.co/x",
            "https://huggingface.co:443/a",
        ).forEach { assertTrue(it, DownloadUrls.isAllowed(it)) }
    }

    @Test
    fun everythingElseIsRefused() {
        listOf(
            null, "", "http://huggingface.co/a", "ftp://huggingface.co/a", "//huggingface.co/a", "huggingface.co/a",
            "https://huggingface.co.evil.com/a", "https://evilhuggingface.co/a", "https://evilhf.co/a", "https://hf.co.evil.com/a",
            "https://huggingface.co@evil.com/a", "https://user:pw@huggingface.co/a", "https://evil.com/huggingface.co",
            "https://evil.com#huggingface.co", "https://evil.com?x=huggingface.co", "https://huggingface.co:8443/a",
            "https://huggingface.co:80/a", "https://huggingface.co./a", "https://evil.com\\@huggingface.co/a",
            "https://huggingface.co\\.evil.com/a", "https://hugging face.co/a", "https://huggingface.co/a b", "https://huggingface.co/a\n",
            "https://.hf.co/a", "https://xn--huggingfce-e7a.co/a", "https://192.168.1.1/a", "https://[::1]/a", "file:///huggingface.co",
            "javascript:alert(1)", "https://huggingface.co" + "/a".repeat(3000), "https:huggingface.co/a", "https:///huggingface.co/a",
        ).forEach { assertFalse(it.toString(), DownloadUrls.isAllowed(it)) }
    }
}

class CatalogueTest {
    @Test
    fun entriesAreWellFormed() {
        assertTrue(Catalogue.models.size in 2..3)
        assertEquals(Catalogue.models.size, Catalogue.models.map { it.id }.distinct().size)
        for (model in Catalogue.models) {
            assertTrue(model.id, Sha256.isValidHex(model.sha256))
            assertTrue(model.id, model.revision.matches(Regex("[0-9a-f]{40}")))
            assertTrue(model.id, model.sizeBytes in 1L until 2_500_000_000L * 11 / 10)
            assertTrue(model.id, ModelFiles.isSafeName(model.localFile))
            assertEquals("Apache-2.0", model.licence)
        }
    }
}

class ForeignTextTest {
    @Test
    fun cleanRemovesControlAndBidi() {
        assertEquals("abc", ForeignText.clean("a\u0000b‮c"))
        assertEquals("a b", ForeignText.clean("a\n\n  b"))
        assertEquals("ab", ForeignText.clean("a​b"))
        assertEquals("ab", ForeignText.clean("a󠁁b"))
    }

    @Test
    fun capsLength() {
        val out = ForeignText.clean("x".repeat(5000), 10)
        assertEquals("x".repeat(10) + "…", out)
    }

    @Test
    fun multilineKeepsLayout() {
        assertEquals("if (a) {\n\tb()\n}", ForeignText.multiline("if (a) {\r\n\tb()\r\n}"))
        assertEquals("  spaced  x", ForeignText.multiline("  spaced  x"))
    }

    @Test
    fun multilineRemovesHostileCharacters() {
        assertEquals("safe", ForeignText.multiline("s‮a\u0007f⁦e"))
        assertEquals("a\nb", ForeignText.multiline("a \nb"))
    }

    @Test
    fun multilineCapsZalgoAndLength() {
        val zalgo = "a" + "́".repeat(100)
        assertEquals(5, ForeignText.multiline(zalgo).length)
        assertEquals("abc…", ForeignText.multiline("abcdef", 3))
    }

    @Test
    fun nullAndBlank() {
        assertEquals("", ForeignText.clean(null))
        assertEquals("", ForeignText.multiline(null))
        assertEquals("fallback", ForeignText.label("‮", "fallback"))
        assertNull(ForeignText.optional(""))
    }
}
