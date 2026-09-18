package com.alivpn.app

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class VPNService : VpnService(), CommandServerHandler {
    companion object {
        const val ACTION_STATUS = "com.alivpn.app.STATUS"
        const val EXTRA_STATE = "state"
        const val EXTRA_IP = "ip"
        const val EXTRA_NODE = "node"
        const val EXTRA_DETAIL = "detail"
        private const val TAG = "AliVPN-Service"
        private const val EXTRA_CANDIDATES_PATH = "candidates_path"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandServer: CommandServer? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var monitorJob: Job? = null
    private val running = AtomicBoolean(false)
    private var candidates = emptyList<String>()
    private var lastConnectedNode = ""
    private val platform = object : PlatformInterfaceWrapper(this) {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NotificationHelper.ID, NotificationHelper.build(this, "AliVPN", "Запуск VPN…"))
        if (running.compareAndSet(false, true)) {
            candidates = runCatching {
                intent?.getStringExtra(EXTRA_CANDIDATES_PATH)?.let { java.io.File(it).readLines().filter(String::isNotBlank) }.orEmpty()
            }.getOrDefault(emptyList())
            scope.launch { startCore() }
        }
        return START_NOT_STICKY
    }

    private suspend fun startCore() {
        broadcast(AppState.CONNECTING, detail = "Проверка конфигураций и узлов")
        try {
            if (candidates.isEmpty()) error("Нет узлов для подключения")
            // First reject every malformed/incompatible configuration. No node is
            // presented as connected until its libbox service has started.
            val valid = candidates.filter { uri ->
                runCatching { io.nekohasekai.libbox.Libbox.checkConfig(SingBoxConfig.build(uri)); true }
                    .onFailure { Log.w(TAG, "Invalid node: $uri", it) }.getOrDefault(false)
            }
            if (valid.isEmpty()) error("Все узлы имеют ошибку конфигурации")
            candidates = valid
            val server = commandServer ?: CommandServer(this, platform).also { it.start(); commandServer = it }
            connectBest(server)
        } catch (t: Throwable) {
            Log.e(TAG, "startCore failed", t)
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    private suspend fun connectBest(server: CommandServer) {
        var lastError = "неизвестная ошибка"
        for ((index, uri) in candidates.withIndex()) {
            if (!scope.isActive || !running.get()) return
            val name = runCatching { UriNodeParser.parse(uri)?.name ?: "Node ${index + 1}" }.getOrDefault("Node ${index + 1}")
            broadcast(AppState.CONNECTING, node = name, detail = "Проверка узла ${index + 1}/${candidates.size}")
            updateNotification("Проверка узла ${index + 1}/${candidates.size}", name)
            try {
                val config = SingBoxConfig.build(uri)
                io.nekohasekai.libbox.Libbox.checkConfig(config)
                server.startOrReloadService(config, OverrideOptions().also { it.autoRedirect = false })
                delay(4_000)
                lastConnectedNode = name
                getSharedPreferences(AppState.PREFS, MODE_PRIVATE).edit().putString(AppState.LAST_URI, uri).apply()
                broadcast(AppState.CONNECTED, node = name, detail = "Tunnel запущен")
                updateNotification("VPN подключён", name)
                monitorJob?.cancel()
                monitorJob = scope.launch { monitorConnection(server) }
                scope.launch { publishIp(name) }
                return
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "Node $index failed: $uri", t)
                runCatching { server.closeService() }
                delay(500)
            }
        }
        // Do not stop the service or silently disconnect the user. Keep the
        // foreground notification and let CONNECT retry after showing the error.
        fail("Рабочий узел не найден: $lastError")
    }

    private suspend fun publishIp(node: String) {
        repeat(5) {
            if (!running.get()) return
            fetchExternalIp().takeIf(String::isNotBlank)?.let { ip ->
                broadcast(AppState.CONNECTED, ip, node, "Tunnel OK")
                updateNotification("VPN подключён", "$node • $ip")
                return
            }
            delay(2_000)
        }
    }

    private suspend fun monitorConnection(server: CommandServer) {
        var failures = 0
        while (scope.isActive && running.get()) {
            delay(90_000)
            if (!running.get()) return
            if (fetchExternalIp().isNotBlank()) { failures = 0; continue }
            if (++failures < 3) continue
            broadcast(AppState.CONNECTING, node = lastConnectedNode, detail = "Переподключение…")
            runCatching { server.closeService() }
            failures = 0
            connectBest(server)
            return
        }
    }

    private fun fetchExternalIp(): String {
        val urls = listOf("https://api.ipify.org", "https://api64.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com")
        for (raw in urls) try {
            val c = URL(raw).openConnection() as HttpURLConnection
            c.connectTimeout = 4_000; c.readTimeout = 5_000
            c.setRequestProperty("User-Agent", "AliVPN/1.0 Android")
            val result = c.inputStream.bufferedReader().use { it.readText() }.trim(); c.disconnect()
            if (result.matches(Regex("^[0-9a-fA-F:.]{3,80}$"))) return result
        } catch (_: Throwable) {}
        return ""
    }

    override fun onRevoke() { stopCore("VPN permission revoked"); super.onRevoke() }
    override fun onDestroy() { stopCore("Service destroyed"); super.onDestroy() }
    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun stopCore(reason: String) {
        if (!running.compareAndSet(true, false)) return
        monitorJob?.cancel()
        scope.launch {
            runCatching { commandServer?.closeService() }; runCatching { commandServer?.close() }
            commandServer = null; runCatching { tunPfd?.close() }; tunPfd = null
            DefaultNetworkMonitor.stop()
            broadcast(AppState.DISCONNECTED, detail = reason)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    private fun fail(message: String) {
        // Keep the foreground service alive. This is a recoverable connection
        // error, not a request to disconnect the VPN service or kick the user out.
        running.set(false)
        monitorJob?.cancel(); monitorJob = null
        runCatching { commandServer?.closeService() }
        getSharedPreferences(AppState.PREFS, MODE_PRIVATE).edit()
            .putString(AppState.STATE, AppState.FAILED).putString(AppState.IP, "").putString(AppState.NODE, "").apply()
        broadcast(AppState.FAILED, detail = message)
        updateNotification("AliVPN: ошибка подключения", message)
    }

    private fun broadcast(state: String, ip: String = "", node: String = "", detail: String = "") {
        getSharedPreferences(AppState.PREFS, MODE_PRIVATE).edit().putString(AppState.STATE, state).putString(AppState.IP, ip).putString(AppState.NODE, node).apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).also { it.putExtra(EXTRA_STATE, state); it.putExtra(EXTRA_IP, ip); it.putExtra(EXTRA_NODE, node); it.putExtra(EXTRA_DETAIL, detail) })
    }
    private fun updateNotification(title: String, text: String) = runCatching { getSystemService(android.app.NotificationManager::class.java)?.notify(NotificationHelper.ID, NotificationHelper.build(this, title, text)) }

    fun openTunInternal(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")
        val b = Builder().setSession("AliVPN").setMtu(options.mtu.coerceIn(1280, 9000))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        fun addAddresses(it: io.nekohasekai.libbox.NetworkInterfaceIterator) { while (it.hasNext()) { val a = it.next(); b.addAddress(a.address(), a.prefix()) } }
        addAddresses(options.inet4Address); addAddresses(options.inet6Address)
        if (options.autoRoute) {
            val dns = options.dnsServerAddress
            while (dns.hasNext()) dns.next().takeIf(String::isNotBlank)?.let { b.addDnsServer(InetAddress.getByName(it)) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val r4 = options.inet4RouteAddress; var added = false
                while (r4.hasNext()) { val r = r4.next(); b.addRoute(r.address(), r.prefix()); added = true }
                if (!added) b.addRoute("0.0.0.0", 0)
                val r6 = options.inet6RouteAddress; while (r6.hasNext()) { val r = r6.next(); b.addRoute(r.address(), r.prefix()) }
                val e4 = options.inet4RouteExcludeAddress; while (e4.hasNext()) { val r = e4.next(); b.excludeRoute(android.net.IpPrefix(InetAddress.getByName(r.address()), r.prefix())) }
                val e6 = options.inet6RouteExcludeAddress; while (e6.hasNext()) { val r = e6.next(); b.excludeRoute(android.net.IpPrefix(InetAddress.getByName(r.address()), r.prefix())) }
            } else {
                val r4 = options.inet4RouteRange; while (r4.hasNext()) { val r = r4.next(); b.addRoute(r.address(), r.prefix()) }
                val r6 = options.inet6RouteRange; while (r6.hasNext()) { val r = r6.next(); b.addRoute(r.address(), r.prefix()) }
            }
        }
        val include = options.includePackage; while (include.hasNext()) runCatching { b.addAllowedApplication(include.next()) }
        val exclude = options.excludePackage; while (exclude.hasNext()) runCatching { b.addDisallowedApplication(exclude.next()) }
        val pfd = b.establish() ?: error("android: VPN establish failed"); tunPfd?.close(); tunPfd = pfd; return pfd.fd
    }
    fun sendLibboxNotification(n: Notification) { updateNotification(n.title.ifBlank { "AliVPN" }, n.body.ifBlank { n.subtitle }.ifBlank { "AliVPN" }) }
    fun cancelLibboxNotification(identifier: String, typeID: Int) = Unit
    override fun serviceStop() = stopCore("Core requested stop")
    override fun serviceReload() = Unit
    override fun getSystemProxyStatus() = SystemProxyStatus().apply { available = false; enabled = false }
    override fun setSystemProxyEnabled(enabled: Boolean) = Unit
    override fun triggerNativeCrash() = error("Native crash not supported")
    override fun writeDebugMessage(message: String) { Log.d(TAG, message) }
    override fun connectSSHAgent(): Int = -1
}
