package com.alivpn.app

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object UriNodeParser {
    fun parse(raw: String): NodeCandidate? {
        val value = raw.trim().removePrefix("\uFEFF")
        if (value.isBlank() || value.startsWith("#") || value.startsWith("//")) return null
        return try {
            when {
                value.startsWith("vless://", true) -> parseVless(value)
                value.startsWith("vmess://", true) -> parseVmess(value)
                value.startsWith("trojan://", true) -> parseTrojan(value)
                value.startsWith("ss://", true) -> parseShadowsocks(value)
                value.startsWith("socks5://", true) -> parseSocks(value)
                value.startsWith("http://", true) -> parseHttp(value)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseVless(value: String): NodeCandidate? {
        val uri = Uri.parse(value)
        val server = uri.host ?: return null
        val port = uri.port
        val uuid = uri.userInfo?.trim().orEmpty()
        if (uuid.isBlank() || port !in 1..65535) return null
        return NodeCandidate(value, displayName(uri, server), "vless", server, port)
    }

    private fun parseTrojan(value: String): NodeCandidate? {
        val uri = Uri.parse(value)
        val server = uri.host ?: return null
        val port = uri.port
        val password = uri.userInfo?.trim().orEmpty()
        if (password.isBlank() || port !in 1..65535) return null
        return NodeCandidate(value, displayName(uri, server), "trojan", server, port)
    }

    private fun parseSocks(value: String): NodeCandidate? {
        val uri = Uri.parse(value)
        val server = uri.host ?: return null
        val port = uri.port
        if (port !in 1..65535) return null
        return NodeCandidate(value, displayName(uri, server), "socks", server, port)
    }

    private fun parseHttp(value: String): NodeCandidate? {
        val uri = Uri.parse(value)
        val server = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 80
        if (port !in 1..65535) return null
        return NodeCandidate(value, displayName(uri, server), "http", server, port)
    }

    private fun parseShadowsocks(value: String): NodeCandidate? {
        var body = value.removePrefix("ss://").substringBefore('#')
        body = body.substringBefore('?').trim()
        var method: String
        var password: String
        var host: String
        var port: Int

        val at = body.lastIndexOf('@')
        if (at >= 0) {
            val userPart = decodeB64OrRaw(body.substring(0, at))
            val hp = body.substring(at + 1)
            val split = splitHostPort(hp) ?: return null
            host = split.first
            port = split.second
            val colon = userPart.indexOf(':')
            if (colon <= 0) return null
            method = userPart.substring(0, colon)
            password = userPart.substring(colon + 1)
        } else {
            val decoded = decodeB64OrRaw(body)
            val splitAt = decoded.lastIndexOf('@')
            if (splitAt <= 0) return null
            val auth = decoded.substring(0, splitAt)
            val hp = decoded.substring(splitAt + 1)
            val split = splitHostPort(hp) ?: return null
            host = split.first
            port = split.second
            val colon = auth.indexOf(':')
            if (colon <= 0) return null
            method = auth.substring(0, colon)
            password = auth.substring(colon + 1)
        }
        if (method.isBlank() || password.isBlank()) return null
        return NodeCandidate(value, value.substringAfter('#', "").ifBlank { host }, "ss", host, port)
    }

    private fun parseVmess(value: String): NodeCandidate? {
        val encoded = value.removePrefix("vmess://").substringBefore('#').trim()
        val decoded = decodeB64OrRaw(encoded)
        val json = JSONObject(decoded)
        val server = json.optString("add").trim().ifBlank { return null }
        val port = json.optInt("port", 0)
        val uuid = json.optString("id").trim()
        if (uuid.isBlank() || port !in 1..65535) return null
        return NodeCandidate(value, json.optString("ps", server), "vmess", server, port)
    }

    private fun splitHostPort(raw: String): Pair<String, Int>? {
        val input = raw.trim()
        if (input.startsWith("[")) {
            val close = input.indexOf(']')
            if (close <= 0 || close + 1 >= input.length || input[close + 1] != ':') return null
            return input.substring(1, close) to input.substring(close + 2).toIntOrNull().orZero()
        }
        val idx = input.lastIndexOf(':')
        if (idx <= 0) return null
        return input.substring(0, idx) to input.substring(idx + 1).toIntOrNull().orZero()
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun decodeB64OrRaw(input: String): String {
        val normalized = input.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try {
            String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            try {
                URLDecoder.decode(input, StandardCharsets.UTF_8.name())
            } catch (_: Throwable) {
                input
            }
        }
    }

    private fun displayName(uri: Uri, fallback: String): String {
        val fragment = uri.fragment?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }?.trim()
        return fragment?.ifBlank { fallback } ?: fallback
    }
}
