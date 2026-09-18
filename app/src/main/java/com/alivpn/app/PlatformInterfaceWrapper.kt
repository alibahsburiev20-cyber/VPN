package com.alivpn.app

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.system.OsConstants
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.InterfaceAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/** Android implementation of the exact libbox 1.14.0 PlatformInterface surface. */
abstract class PlatformInterfaceWrapper(protected val vpn: VPNService) : PlatformInterface {
    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        // Protect libbox's physical-network sockets from being captured by our own TUN.
        vpn.protect(fd)
    }

    override fun openTun(options: TunOptions): Int = vpn.openTunInternal(options)

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("ProcFS owner lookup is required below Android 10")
        }
        val cm = vpn.getSystemService(ConnectivityManager::class.java)
            ?: error("ConnectivityManager unavailable")
        val uid = cm.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("android: connection owner not found")
        val packages = vpn.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(StringArray(packages))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.start(vpn, listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.stop()
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm = vpn.getSystemService(ConnectivityManager::class.java)
            ?: return InterfaceArray(emptyList())
        val physical = buildMap<String, NetworkInterface> {
            val all = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            if (all != null) {
                while (all.hasMoreElements()) {
                    val ni = all.nextElement()
                    put(ni.name, ni)
                }
            }
        }
        val result = ArrayList<io.nekohasekai.libbox.NetworkInterface>()

        for (network in cm.allNetworks) {
            val lp = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val name = lp.interfaceName ?: continue
            val ni = physical[name] ?: continue

            val out = io.nekohasekai.libbox.NetworkInterface().apply {
                this.name = name
                index = ni.index
                mtu = runCatching { ni.mtu }.getOrDefault(1500)
                dnsServer = StringArray(lp.dnsServers.mapNotNull { it.hostAddress })
                gateway = StringArray(lp.routes.mapNotNull { it.gateway?.hostAddress })
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> io.nekohasekai.libbox.Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> io.nekohasekai.libbox.Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> io.nekohasekai.libbox.Libbox.InterfaceTypeEthernet
                    else -> io.nekohasekai.libbox.Libbox.InterfaceTypeOther
                }
                addresses = StringArray(ni.interfaceAddresses.map { it.toPrefix() })

                var computedFlags = 0
                if (ni.isUp) computedFlags = computedFlags or OsConstants.IFF_UP
                if (ni.isLoopback) computedFlags = computedFlags or OsConstants.IFF_LOOPBACK
                if (ni.isPointToPoint) computedFlags = computedFlags or OsConstants.IFF_POINTOPOINT
                if (ni.supportsMulticast()) computedFlags = computedFlags or OsConstants.IFF_MULTICAST
                flags = computedFlags
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            result += out
        }
        return InterfaceArray(result.distinctBy { it.name })
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() = Unit

    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {
        vpn.sendLibboxNotification(notification)
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
        vpn.cancelLibboxNotification(identifier, typeID)
    }

    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun registerMyInterface(name: String) = Unit
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() = error("Platform shell is unavailable")
    override fun openShellSession(
        user: io.nekohasekai.libbox.PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int,
    ): ShellSession = error("Platform shell is unavailable")

    override fun lookupUser(username: String): io.nekohasekai.libbox.PlatformUser =
        error("User lookup unavailable")

    override fun lookupSFTPServer(): String = error("SFTP unavailable")
    override fun readSystemSSHHostKey(): String = error("SSH host key unavailable")
    override fun tailscaleHostname(): String = ""
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: io.nekohasekai.libbox.BridgeOptions): io.nekohasekai.libbox.BridgeSession =
        error("Bridge unavailable")

    class StringArray(values: Collection<String>) : StringIterator {
        private val items = values.toList()
        private var index = 0

        override fun len(): Int = items.size
        override fun hasNext(): Boolean = index < items.size
        override fun next(): String = items[index++]
    }

    private class InterfaceArray(list: List<io.nekohasekai.libbox.NetworkInterface>) : NetworkInterfaceIterator {
        private val iterator = list.iterator()
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): io.nekohasekai.libbox.NetworkInterface = iterator.next()
    }

    private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
        "${address.hostAddress}/$networkPrefixLength"
    } else {
        "${address.hostAddress}/$networkPrefixLength"
    }
}
