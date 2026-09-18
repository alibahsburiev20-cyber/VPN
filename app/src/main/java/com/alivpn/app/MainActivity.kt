package com.alivpn.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    companion object {
        private const val VPN_PREPARE = 500
        private const val NOTIFY_PERMISSION = 501
        private const val CANDIDATE_FILE = AppState.CANDIDATES_FILE
    }

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var ipText: TextView
    private lateinit var nodeText: TextView
    private lateinit var connectButton: Button
    private lateinit var refreshButton: Button
    private lateinit var progress: ProgressBar

    @Volatile private var cancelRequested = false
    private var currentState = AppState.DISCONNECTED
    private var pendingStartPath = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != VPNService.ACTION_STATUS) return
            applyState(
                intent.getStringExtra(VPNService.EXTRA_STATE).orEmpty(),
                intent.getStringExtra(VPNService.EXTRA_IP).orEmpty(),
                intent.getStringExtra(VPNService.EXTRA_NODE).orEmpty(),
                intent.getStringExtra(VPNService.EXTRA_DETAIL).orEmpty(),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFY_PERMISSION)
        }
        val prefs = getSharedPreferences(AppState.PREFS, MODE_PRIVATE)
        applyState(
            prefs.getString(AppState.STATE, AppState.DISCONNECTED).orEmpty(),
            prefs.getString(AppState.IP, "").orEmpty(),
            prefs.getString(AppState.NODE, "").orEmpty(),
            "",
        )
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter(VPNService.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        runCatching { unregisterReceiver(receiver) }
        super.onPause()
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(16, 19, 18)
        window.navigationBarColor = Color.rgb(16, 19, 18)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 36, 24, 24)
            setBackgroundColor(Color.rgb(16, 19, 18))
        }

        val title = TextView(this).apply {
            text = "AliVPN"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        statusText = label(AppState.DISCONNECTED, 22f)
        root.addView(statusText, marginParams(1))
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(-1, 48))
        ipText = label("IP: —", 16f)
        nodeText = label("Node: —", 16f)
        detailText = label("", 14f)
        root.addView(ipText, marginParams(0))
        root.addView(nodeText, marginParams(0))
        root.addView(detailText, marginParams(0))

        connectButton = Button(this).apply {
            text = "CONNECT"
            setTextColor(Color.WHITE)
            setBackgroundResource(com.alivpn.app.R.drawable.bg_button)
            setOnClickListener {
                when (currentState) {
                    AppState.CONNECTED, AppState.CONNECTING -> disconnect()
                    else -> connect()
                }
            }
        }
        root.addView(connectButton, LinearLayout.LayoutParams(-1, 58).also { it.topMargin = 24 })

        refreshButton = Button(this).apply {
            text = "UPDATE NODES"
            setOnClickListener { refreshNodes() }
        }
        root.addView(refreshButton, LinearLayout.LayoutParams(-1, 52).also { it.topMargin = 12 })
        setContentView(root)
    }

    private fun connect() {
        cancelRequested = false
        applyState(AppState.CONNECTING, "", "", "Загрузка публичных конфигураций…")
        Thread {
            try {
                val nodes = PublicConfigRepository().fetchCandidates().toMutableList()
                if (cancelRequested) return@Thread

                val prefs = getSharedPreferences(AppState.PREFS, MODE_PRIVATE)
                val last = prefs.getString(AppState.LAST_URI, "").orEmpty()
                if (last.isNotBlank()) {
                    nodes.sortWith(compareByDescending<NodeCandidate> { it.uri == last })
                } else {
                    nodes.shuffle()
                }

                val limited = nodes.take(12)
                if (limited.isEmpty()) error("Публичный источник не вернул поддерживаемых узлов")
                val path = java.io.File(filesDir, CANDIDATE_FILE)
                path.writeText(limited.joinToString("\n") { it.uri })
                if (cancelRequested) return@Thread

                runOnUiThread {
                    detailText.text = "Найдено узлов: ${nodes.size}. К проверке: ${limited.size}."
                    val prepare = VpnService.prepare(this)
                    if (prepare != null) {
                        pendingStartPath = path.absolutePath
                        startActivityForResult(prepare, VPN_PREPARE)
                    } else {
                        startVpn(path.absolutePath)
                    }
                }
            } catch (t: Throwable) {
                if (cancelRequested) return@Thread
                runOnUiThread {
                    applyState(AppState.FAILED, "", "", "Ошибка: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun refreshNodes() {
        cancelRequested = false
        progress.visibility = View.VISIBLE
        refreshButton.isEnabled = false
        Thread {
            try {
                val nodes = PublicConfigRepository().fetchCandidates()
                runOnUiThread {
                    progress.visibility = View.GONE
                    refreshButton.isEnabled = true
                    detailText.text = "Доступных конфигураций: ${nodes.size}"
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    refreshButton.isEnabled = true
                    detailText.text = "Ошибка обновления: ${t.message ?: t.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun disconnect() {
        cancelRequested = true
        pendingStartPath = ""
        runCatching { stopService(Intent(this, VPNService::class.java)) }
        applyState(AppState.DISCONNECTED, "", "", "Отключение…")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_PREPARE) return
        if (resultCode == RESULT_OK && pendingStartPath.isNotBlank() && !cancelRequested) {
            startVpn(pendingStartPath)
        } else {
            applyState(AppState.DISCONNECTED, "", "", "Разрешение VPN не выдано")
        }
        pendingStartPath = ""
    }

    private fun startVpn(path: String) {
        val intent = Intent(this, VPNService::class.java).apply {
            putExtra("candidates_path", path)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            applyState(AppState.CONNECTING, "", "", "Подключение…")
        } catch (t: Throwable) {
            applyState(AppState.FAILED, "", "", "Не удалось запустить VPN: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun applyState(state: String, ip: String, node: String, detail: String) {
        currentState = state.ifBlank { AppState.DISCONNECTED }
        statusText.text = currentState
        ipText.text = "IP: ${ip.ifBlank { "—" }}"
        nodeText.text = "Node: ${node.ifBlank { "—" }}"
        detailText.text = detail

        val working = currentState == AppState.CONNECTED || currentState == AppState.CONNECTING
        progress.visibility = if (currentState == AppState.CONNECTING) View.VISIBLE else View.GONE
        connectButton.isEnabled = true
        connectButton.text = if (working) "DISCONNECT" else "CONNECT"
        refreshButton.isEnabled = !working
    }

    private fun label(text: String, size: Float): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }

    private fun marginParams(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).also { it.topMargin = top * 12 }
}
