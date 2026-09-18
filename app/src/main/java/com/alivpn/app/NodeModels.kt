package com.alivpn.app

data class NodeCandidate(
    val uri: String,
    val name: String,
    val type: String,
    val server: String,
    val port: Int,
)

object AppState {
    const val PREFS = "alivpn_state"
    const val STATE = "state"
    const val IP = "ip"
    const val NODE = "node"
    const val LAST_URI = "last_uri"
    const val CANDIDATES_FILE = "candidates.txt"

    const val CONNECTING = "CONNECTING"
    const val CONNECTED = "CONNECTED"
    const val DISCONNECTED = "DISCONNECTED"
    const val FAILED = "FAILED"
}
