package com.alivpn.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class PublicConfigRepository {
    companion object {
        private val SOURCES = listOf(
            "https://raw.githubusercontent.com/aviamastersgh/vpn-free-russia/main/verified_configs.txt",
            "https://cdn.jsdelivr.net/gh/aviamastersgh/vpn-free-russia@main/verified_configs.txt",
            "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt",
            "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/V2Ray-Config-By-EbraSha.txt",
            "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/vless_configs.txt",
            "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/vmess_configs.txt",
            "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/trojan_configs.txt",
            "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/ss_configs.txt",
        )
    }

    fun fetchCandidates(): List<NodeCandidate> {
        val all = LinkedHashMap<String, NodeCandidate>()
        var lastError: Throwable? = null
        for (source in SOURCES) {
            try {
                val body = decodeSubscriptionIfNeeded(download(source))
                for (line in body.lineSequence()) {
                    val node = UriNodeParser.parse(line) ?: continue
                    val key = canonical(node.uri)
                    if (!all.containsKey(key)) all[key] = node
                    if (all.size >= 200) break
                }
            } catch (t: Throwable) {
                lastError = t
            }
            if (all.size >= 200) break
        }
        if (all.isEmpty()) throw IllegalStateException(lastError?.message ?: "No supported nodes")
        return all.values.toList()
    }

    private fun download(source: String): String {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AliVPN/1.0 Android")
            setRequestProperty("Accept", "text/plain,*/*")
        }
        return connection.useAndRead()
    }

    private fun HttpURLConnection.useAndRead(): String {
        try {
            if (responseCode !in 200..299) error("HTTP $responseCode")
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                return reader.readText().take(4_000_000)
            }
        } finally {
            disconnect()
        }
    }

    private fun decodeSubscriptionIfNeeded(body: String): String {
        val lines = body.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (lines.any { line ->
                line.startsWith("vless://", true) || line.startsWith("vmess://", true) ||
                    line.startsWith("trojan://", true) || line.startsWith("ss://", true) ||
                    line.startsWith("socks5://", true) || line.startsWith("http://", true)
            }) return lines.joinToString("\n")
        return runCatching {
            String(android.util.Base64.decode(body.replace(Regex("\\s"), ""), android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(body)
    }

    private fun canonical(uri: String): String = uri.trim().lowercase(Locale.US).substringBefore('#')
}
