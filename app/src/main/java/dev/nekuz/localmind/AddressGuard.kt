package dev.nekuz.localmind

import java.net.InetAddress
import java.net.UnknownHostException

data class Endpoint(val host: String, val port: Int) {
    val display: String
        get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

fun interface HostResolver {
    fun resolve(host: String): List<ByteArray>
}

object SystemResolver : HostResolver {
    override fun resolve(host: String): List<ByteArray> = try {
        InetAddress.getAllByName(host).map { it.address }
    } catch (e: UnknownHostException) {
        emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }
}

sealed class GuardResult {
    data class Allowed(val literal: String, val port: Int) : GuardResult() {
        val baseUrl: String
            get() = if (literal.contains(':')) "http://[$literal]:$port" else "http://$literal:$port"
    }

    data class Refused(val reason: String) : GuardResult()
}

object AddressGuard {
    const val NOT_RESOLVED = "This name could not be resolved."
    const val NOT_LOCAL = "This address is not private, loopback or link-local, so LocalMind refuses to send anything to it."
    private const val MAX_ADDRESSES = 32

    fun isAllowed(address: ByteArray?): Boolean = when (address?.size) {
        4 -> isAllowedV4(address)
        16 -> isAllowedV6(address)
        else -> false
    }

    fun check(endpoint: Endpoint, resolver: HostResolver): GuardResult {
        val addresses = try {
            resolver.resolve(endpoint.host)
        } catch (e: RuntimeException) {
            emptyList()
        }
        if (addresses.isEmpty()) return GuardResult.Refused(NOT_RESOLVED)
        if (addresses.size > MAX_ADDRESSES) return GuardResult.Refused(NOT_LOCAL)
        if (!addresses.all { isAllowed(it) }) return GuardResult.Refused(NOT_LOCAL)
        return GuardResult.Allowed(literal(addresses.first()), endpoint.port)
    }

    fun literal(address: ByteArray): String {
        if (address.size == 4) return address.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val groups = (0 until 8).map { index ->
            ((address[index * 2].toInt() and 0xFF) shl 8) or (address[index * 2 + 1].toInt() and 0xFF)
        }
        return groups.joinToString(":") { Integer.toHexString(it) }
    }

    private fun isAllowedV4(a: ByteArray): Boolean {
        val first = a[0].toInt() and 0xFF
        val second = a[1].toInt() and 0xFF
        return when {
            first == 10 -> true
            first == 127 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            first == 169 && second == 254 -> true
            else -> false
        }
    }

    private fun isAllowedV6(a: ByteArray): Boolean {
        val first = a[0].toInt() and 0xFF
        val second = a[1].toInt() and 0xFF
        val leadingZeros = (0 until 10).all { a[it].toInt() == 0 }
        if (leadingZeros && (a[10].toInt() and 0xFF) == 0xFF && (a[11].toInt() and 0xFF) == 0xFF) {
            return isAllowedV4(a.copyOfRange(12, 16))
        }
        if ((0 until 15).all { a[it].toInt() == 0 } && a[15].toInt() == 1) return true
        if (first == 0xFE && second and 0xC0 == 0x80) return true
        if (first and 0xFE == 0xFC) return true
        return false
    }
}

object Endpoints {
    private const val MAX_LENGTH = 300
    private val NAME = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,62})(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}))*")
    private val V6_GROUP = Regex("[0-9A-Fa-f]{1,4}")
    private val PORT = Regex("[0-9]{1,5}")

    fun parse(raw: String?, defaultPort: Int): Endpoint? {
        if (raw == null || raw.length > MAX_LENGTH) return null
        var text = raw.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() || it.code < 0x20 }) return null
        if (text.startsWith("http://", ignoreCase = true)) text = text.substring(7)
        if (text.endsWith("/")) text = text.dropLast(1)
        if (text.isEmpty() || text.any { it in "/\\@?#%" }) return null
        val host: String
        val portText: String?
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0) return null
            host = text.substring(1, close)
            val rest = text.substring(close + 1)
            portText = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
            if (!isV6Shape(host)) return null
        } else {
            val colon = text.indexOf(':')
            if (colon >= 0 && text.indexOf(':', colon + 1) >= 0) {
                if (!isV6Shape(text)) return null
                return Endpoint(text, validPort(null, defaultPort) ?: return null)
            }
            host = if (colon < 0) text else text.substring(0, colon)
            portText = if (colon < 0) null else text.substring(colon + 1)
            if (host.length > 253 || !NAME.matches(host)) return null
        }
        val port = validPort(portText, defaultPort) ?: return null
        return Endpoint(host, port)
    }

    private fun isV6Shape(text: String): Boolean {
        if (text.length !in 2..39 || !text.contains(':')) return false
        val double = text.indexOf("::")
        if (double != text.lastIndexOf("::") || text.contains(":::")) return false
        val groups = text.split(':').filter { it.isNotEmpty() }
        if (groups.any { !V6_GROUP.matches(it) }) return false
        if (double < 0) {
            if (text.startsWith(":") || text.endsWith(":")) return false
            return groups.size == 8
        }
        return groups.size <= 7
    }

    private fun validPort(text: String?, defaultPort: Int): Int? {
        if (text == null) return defaultPort.takeIf { it in 1..65535 }
        if (!PORT.matches(text)) return null
        return text.toInt().takeIf { it in 1..65535 }
    }
}
