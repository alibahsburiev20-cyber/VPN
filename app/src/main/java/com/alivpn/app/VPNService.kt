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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private var candidates: List<String> = emptyList()
    private var currentNodeIndex = -1
    private var lastConnectedNode: String = ""
    private val platform = object : PlatformInterfaceWrapper(this) {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NotificationHelper.ID, NotificationHelper.build(this, "AliVPN", "Запуск VPN…"))
        if (running.compareAndSet(false, true)) {
            candidates = runCatching {
                val path = intent?.getStringExtra(EXTRA_CANDIDATES_PATH)
                if (path.isNullOrBlank()) emptyList() else java.io.File(path).readLines().filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
            scope.launch { startCore() }
        }
        return START_NOT_STICKY
    }

    private suspend fun startCore() {
        broadcast(AppState.CONNECTING, detail = "Подготовка ядра")
        try {
            val server = CommandServer(this@VPNService, platform)
            server.start()
            commandServer = server
            if (candidates.isEmpty()) error("Нет кандидатов для подключения")
            connectBest(server)
        } catch (t: Throwable) {
            Log.e(TAG, "startCore failed", t)
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    private suspend fun connectBest(server: CommandServer) {
        val max = minOf(candidates.size, 12)
        var lastError = "unknown"
        for (i in 0 until max) {
            if (!scope.isActive) return
            currentNodeIndex = i
            val uri = candidates[i]
            val name = runCatching { UriNodeParser.parse(uri)?.name ?: "Node ${i + 1}" }.getOrDefault("Node ${i + 1}")
            updateNotification("Проверка узла ${i + 1}/$max", name)
            broadcast(AppState.CONNECTING, node = name, detail = "Проверка ${i + 1}/$max")
            try {
                val config = SingBoxConfig.build(uri)
                io.nekohasekai.libbox.Libbox.checkConfig(config)
                server.startOrReloadService(config, OverrideOptions().also { it.autoRedirect = false })
                delay(1_800)
                val ip = fetchExternalIp()
                if (ip.isNotBlank()) {
                    lastConnectedNode = name
                    getSharedPreferences(AppState.PREFS, MODE_PRIVATE).edit().putString(AppState.LAST_URI, uri).apply()
                    broadcast(AppState.CONNECTED, ip = ip, node = name, detail = "Tunnel OK")
                    updateNotification("VPN подключён", "$name • $ip")
                    monitorJob?.cancel()
                    monitorJob = scope.launch { monitorConnection(server) }
                    return
                }
                lastError = "external IP check failed"
                server.closeService()
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "node $i failed: $uri", t)
                runCatching { server.closeService() }
            }
        }
        fail("Не удалось подключить ни один узел: $lastError")
    }

    private suspend fun monitorConnection(server: CommandServer) {
        while (scope.isActive && running.get()) {
            delay(90_000)
            if (!scope.isActive || !running.get()) return
            if (runCatching { fetchExternalIp() }.getOrDefault("").isNotBlank()) continue
            broadcast(AppState.CONNECTING, node = lastConnectedNode, detail = "Переподключение…")
            runCatching { server.closeService() }
            connectBest(server)
            return
        }
    }

    private fun fetchExternalIp(): String {
        val endpoints = listOf("https://api.ipify.org", "https://api64.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com", "https://ipinfo.io/ip")
        for (endpoint in endpoints) {
            try {
                val c = URL(endpoint).openConnection() as HttpURLConnection
                c.connectTimeout = 4_000
                c.readTimeout = 5_000
                c.setRequestProperty("User-Agent", "AliVPN/1.0 Android")
                val result = c.inputStream.bufferedReader().use { it.readText() }.trim()
                c.disconnect()
                if (result.matches(Regex("^[0-9a-fA-F:.]{3,80}$"))) return result
            } catch (_: Throwable) {}
        }
        return ""
    }

    override fun onRevoke() { stopCore("VPN permission revoked"); super.onRevoke() }
    override fun onDestroy() { stopCore("Service destroyed"); super.onDestroy() }
    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun stopCore(reason: String) {
        if (!running.compareAndSet(true, false)) return
        monitorJob?.cancel()
        monitorJob = null
        scope.launch {
            runCatching { commandServer?.closeService() }
            runCatching { commandServer?.close() }
            commandServer = null
            runCatching { tunPfd?.close() }
            tunPfd = null
            DefaultNetworkMonitor.stop()
            getSharedPreferences(AppState.PREFS, MODE_PRIVATE).edit().putString(AppState.STATE, AppState.DISCONNECTED).apply()
            broadcast(AppState.DISCONNECTED, detail = reason)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(message: String) {
        getSharedPreferences(AppState.PREFS, MODE_PRIVATE).edit().putString(AppState.STATE, AppState.FAILED).putString(AppState.IP, "").putString(AppState.NODE, "").apply()
        broadcast(AppState.FAILED, detail = message)
        updateNotification("AliVPN", "Не удалось подключиться")
        running.set(false)
        scope.launch {
            runCatching { commandServer?.closeService() }
            runCatching { commandServer?.close() }
            commandServer = null
            runCatching { tunPfd?.close() }
            tunPfd = null
            DefaultNetworkMonitor.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun broadcast(state: String, ip: String = "", node: String = "", detail: String = "") {
        getSharedPreferences(AppState.PREFS, MODE_PRIVATE).edit().putString(AppState.STATE, state).putString(AppState.IP, ip).putString(AppState.NODE, node).apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).also {
            it.putExtra(EXTRA_STATE, state); it.putExtra(EXTRA_IP, ip); it.putExtra(EXTRA_NODE, node); it.putExtra(EXTRA_DETAIL, detail)
        })
    }

    private fun updateNotification(title: String, text: String) {
        runCatching { getSystemService(android.app.NotificationManager::class.java)?.notify(NotificationHelper.ID, NotificationHelper.build(this, title, text)) }
    }

    fun openTunInternal(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")
        val builder = Builder().setSession("AliVPN").setMtu(options.mtu.coerceIn(1280, 9000))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        val inet4 = options.inet4Address
        while (inet4.hasNext()) { val address = inet4.next(); builder.addAddress(address.address(), address.prefix()) }
        val inet6 = options.inet6Address
        while (inet6.hasNext()) { val address = inet6.next(); builder.addAddress(address.address(), address.prefix()) }

        if (options.autoRoute) {
            val dns = options.dnsServerAddress
            while (dns.hasNext()) builder.addDnsServer(InetAddress.getByName(dns.next()))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val r4 = options.inet4RouteAddress
                var addedV4Route = false
                while (r4.hasNext()) { val route = r4.next(); builder.addRoute(route.address(), route.prefix()); addedV4Route = true }
                if (!addedV4Route) builder.addRoute("0.0.0.0", 0)
                val r6 = options.inet6RouteAddress
                while (r6.hasNext()) { val route = r6.next(); builder.addRoute(route.address(), route.prefix()) }
                val e4 = options.inet4RouteExcludeAddress
                while (e4.hasNext()) { val route = e4.next(); builder.excludeRoute(android.net.IpPrefix(InetAddress.getByName(route.address()), route.prefix())) }
                val e6 = options.inet6RouteExcludeAddress
                while (e6.hasNext()) { val route = e6.next(); builder.excludeRoute(android.net.IpPrefix(InetAddress.getByName(route.address()), route.prefix())) }
            } else {
                val r4 = options.inet4RouteRange
                if (r4.hasNext()) while (r4.hasNext()) { val p = r4.next(); builder.addRoute(p.address(), p.prefix()) } else builder.addRoute("0.0.0.0", 0)
                val r6 = options.inet6RouteRange
                while (r6.hasNext()) { val p = r6.next(); builder.addRoute(p.address(), p.prefix()) }
            }
        }
        val include = options.includePackage
        while (include.hasNext()) runCatching { builder.addAllowedApplication(include.next()) }
        val exclude = options.excludePackage
        while (exclude.hasNext()) runCatching { builder.addDisallowedApplication(exclude.next()) }
        val pfd = builder.establish() ?: error("android: VPN establish failed")
        tunPfd?.close(); tunPfd = pfd
        return pfd.fd
    }

    fun sendLibboxNotification(notification: Notification) {
        val body = notification.body.ifBlank { notification.subtitle }.ifBlank { "AliVPN" }
        updateNotification(notification.title.ifBlank { "AliVPN" }, body)
    }
    fun cancelLibboxNotification(identifier: String, typeID: Int) = Unit
    override fun serviceStop() = stopCore("Core requested stop")
    override fun serviceReload() = Unit
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply { available = false; enabled = false }
    override fun setSystemProxyEnabled(enabled: Boolean) = Unit
    override fun triggerNativeCrash() = error("Native crash not supported")
    override fun writeDebugMessage(message: String) { Log.d(TAG, message) }
    override fun connectSSHAgent(): Int = -1
}
