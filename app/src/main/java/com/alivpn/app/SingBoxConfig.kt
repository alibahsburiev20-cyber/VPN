package com.alivpn.app

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

object SingBoxConfig {
    fun build(uriString: String): String {
        val parsed = UriNodeParser.parse(uriString) ?: error("Unsupported node")
        val uri = when (parsed.type) {
            "vmess" -> null
            else -> Uri.parse(uriString)
        }

        val outbound = when (parsed.type) {
            "vless" -> buildVless(uri!!)
            "trojan" -> buildTrojan(uri!!)
            "ss" -> buildShadowsocks(uriString)
            "vmess" -> buildVmess(uriString)
            "socks" -> buildSocks(uriString)
            "http" -> buildHttp(uri!!)
            else -> error("Unsupported node type")
        }

        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
            .put("mtu", 1400)
            .put("auto_route", true)
            .put("strict_route", true)
            .put("dns_mode", "hijack")

        val dnsServer = JSONObject()
            .put("type", "udp")
            .put("tag", "dns-direct")
            .put("server", "1.1.1.1")
            .put("server_port", 53)

        val dns = JSONObject()
            .put("servers", JSONArray().put(dnsServer))
            .put("final", "dns-direct")
            .put("strategy", "prefer_ipv4")

        val route = JSONObject()
            .put("auto_detect_interface", true)
            .put("final", "proxy")

        val logger = JSONObject().put("disabled", false).put("level", "warn").put("timestamp", true)

        return JSONObject()
            .put("log", logger)
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", JSONArray().put(outbound).put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", route)
            .toString()
    }

    private fun buildVless(uri: Uri): JSONObject {
        val server = requireHost(uri)
        val port = requirePort(uri)
        val user = uri.userInfo.orEmpty()
        require(user.isNotBlank())
        val query = query(uri)
        val out = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", server)
            .put("server_port", port)
            .put("uuid", user)
        query["flow"]?.takeIf { it.isNotBlank() }?.let { out.put("flow", it) }
        applyTlsAndTransport(out, query)
        return out
    }

    private fun buildTrojan(uri: Uri): JSONObject {
        val server = requireHost(uri)
        val port = requirePort(uri)
        val password = uri.userInfo.orEmpty()
        require(password.isNotBlank())
        val query = query(uri)
        val out = JSONObject()
            .put("type", "trojan")
            .put("tag", "proxy")
            .put("server", server)
            .put("server_port", port)
            .put("password", password)
        applyTlsAndTransport(out, query)
        return out
    }

    private fun buildSocks(raw: String): JSONObject {
        val uri = Uri.parse(raw)
        val out = JSONObject()
            .put("type", "socks")
            .put("tag", "proxy")
            .put("server", requireHost(uri))
            .put("server_port", requirePort(uri))
            .put("version", "5")
        val user = uri.userInfo.orEmpty()
        if (user.isNotBlank()) {
            val idx = user.indexOf(':')
            if (idx > 0) {
                out.put("username", user.substring(0, idx))
                out.put("password", user.substring(idx + 1))
            }
        }
        return out
    }

    private fun buildHttp(uri: Uri): JSONObject {
        val out = JSONObject()
            .put("type", "http")
            .put("tag", "proxy")
            .put("server", requireHost(uri))
            .put("server_port", requirePort(uri))
        val user = uri.userInfo.orEmpty()
        if (user.isNotBlank()) {
            val idx = user.indexOf(':')
            if (idx > 0) {
                out.put("username", user.substring(0, idx))
                out.put("password", user.substring(idx + 1))
            }
        }
        return out
    }

    private fun buildShadowsocks(raw: String): JSONObject {
        var body = raw.removePrefix("ss://").substringBefore('#').substringBefore('?')
        var method: String
        var password: String
        var host: String
        var port: Int
        val at = body.lastIndexOf('@')
        if (at >= 0) {
            val auth = decodeB64OrRaw(body.substring(0, at))
            val hp = splitHostPort(body.substring(at + 1)) ?: error("Invalid SS host")
            host = hp.first
            port = hp.second
            val colon = auth.indexOf(':')
            require(colon > 0)
            method = auth.substring(0, colon)
            password = auth.substring(colon + 1)
        } else {
            val decoded = decode(body)
            val splitAt = decoded.lastIndexOf('@')
            require(splitAt > 0)
            val auth = decoded.substring(0, splitAt)
            val hp = splitHostPort(decoded.substring(splitAt + 1)) ?: error("Invalid SS host")
            host = hp.first
            port = hp.second
            val colon = auth.indexOf(':')
            require(colon > 0)
            method = auth.substring(0, colon)
            password = auth.substring(colon + 1)
        }
        return JSONObject()
            .put("type", "shadowsocks")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("method", method)
            .put("password", password)
    }

    private fun buildVmess(raw: String): JSONObject {
        val body = raw.removePrefix("vmess://").substringBefore('#')
        val json = JSONObject(decode(body))
        val out = JSONObject()
            .put("type", "vmess")
            .put("tag", "proxy")
            .put("server", json.getString("add"))
            .put("server_port", json.getInt("port"))
            .put("uuid", json.getString("id"))
            .put("security", json.optString("scy", "auto").ifBlank { "auto" })
            .put("alter_id", json.optInt("aid", 0))
            .put("network", when (json.optString("net", "tcp").lowercase()) { "ws", "grpc", "http", "h2" -> "tcp"; else -> "tcp" })
        val tlsValue = json.optString("tls", "").lowercase()
        val sni = json.optString("sni").ifBlank { json.optString("host") }
        if (tlsValue == "tls" || sni.isNotBlank()) {
            out.put("tls", JSONObject().apply {
                put("enabled", true)
                if (sni.isNotBlank()) put("server_name", sni)
            })
        }
        when (json.optString("net", "tcp").lowercase()) {
            "ws" -> {
                val transport = JSONObject().put("type", "ws")
                json.optString("path").takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
                json.optString("host").takeIf { it.isNotBlank() }?.let { host ->
                    transport.put("headers", JSONObject().put("Host", host))
                }
                out.put("transport", transport)
            }
            "grpc" -> {
                out.put("transport", JSONObject().put("type", "grpc").put("service_name", json.optString("path")))
            }
            "h2", "http" -> {
                val transport = JSONObject().put("type", "http")
                json.optString("path").takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
                json.optString("host").takeIf { it.isNotBlank() }?.let { host -> transport.put("host", JSONArray().put(host)) }
                out.put("transport", transport)
            }
        }
        return out
    }

    private fun applyTlsAndTransport(out: JSONObject, query: Map<String, String>) {
        val security = query["security"].orEmpty().lowercase()
        if (security == "tls" || security == "reality") {
            val tls = JSONObject().put("enabled", true)
            query["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
            query["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                val values = JSONArray()
                alpn.split(',').filter { it.isNotBlank() }.forEach(values::put)
                tls.put("alpn", values)
            }
            query["fp"]?.takeIf { it.isNotBlank() }?.let { fp -> tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp)) }
            if (security == "reality") {
                val pbk = query["pbk"].orEmpty()
                if (pbk.isBlank()) error("Reality public key missing")
                tls.put("reality", JSONObject().put("enabled", true).put("public_key", pbk).put("short_id", query["sid"].orEmpty()))
            }
            if (query["allowInsecure"] == "1" || query["allowInsecure"] == "true") tls.put("insecure", true)
            out.put("tls", tls)
        }
        when (query["type"].orEmpty().lowercase()) {
            "ws" -> {
                val t = JSONObject().put("type", "ws")
                query["path"]?.takeIf { it.isNotBlank() }?.let { t.put("path", it) }
                query["host"]?.takeIf { it.isNotBlank() }?.let { t.put("headers", JSONObject().put("Host", it)) }
                out.put("transport", t)
            }
            "grpc" -> out.put("transport", JSONObject().put("type", "grpc").put("service_name", query["serviceName"].orEmpty()))
            "http", "h2" -> {
                val t = JSONObject().put("type", "http")
                query["path"]?.takeIf { it.isNotBlank() }?.let { t.put("path", it) }
                query["host"]?.takeIf { it.isNotBlank() }?.let { t.put("host", JSONArray().put(it)) }
                out.put("transport", t)
            }
        }
    }

    private fun query(uri: Uri): Map<String, String> = buildMap {
        for (name in uri.queryParameterNames) put(name, uri.getQueryParameter(name).orEmpty())
    }

    private fun requireHost(uri: Uri): String = uri.host?.takeIf { it.isNotBlank() } ?: error("Missing host")
    private fun requirePort(uri: Uri): Int = uri.port.takeIf { it in 1..65535 } ?: error("Invalid port")

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

    private fun decode(input: String): String {
        val normalized = input.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8)
    }

    private fun decodeB64OrRaw(input: String): String {
        return try {
            decode(input)
        } catch (_: Throwable) {
            input
        }
    }
}
