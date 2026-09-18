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
        )
    }

    fun fetchCandidates(): List<NodeCandidate> {
        val all = LinkedHashMap<String, NodeCandidate>()
        var lastError: Throwable? = null
        for (source in SOURCES) {
            try {
                val body = download(source)
                for (line in body.lineSequence()) {
                    val node = UriNodeParser.parse(line) ?: continue
                    val key = canonical(node.uri)
                    if (!all.containsKey(key)) all[key] = node
                    if (all.size >= 100) break
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        if (all.isEmpty()) throw IllegalStateException(lastError?.message ?: "No working source")
        return all.values.toList()
    }

    private fun download(source: String): String {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
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
                return reader.readText().take(2_000_000)
            }
        } finally {
            disconnect()
        }
    }

    private fun canonical(uri: String): String = uri.trim().lowercase(Locale.US).substringBefore('#')
}
