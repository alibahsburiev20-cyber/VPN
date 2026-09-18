package com.alivpn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.net.NetworkInterface

object DefaultNetworkMonitor {
    private const val TAG = "AliVPN-Network"
    @Volatile private var listener: InterfaceUpdateListener? = null
    @Volatile private var connectivity: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(context: Context, newListener: InterfaceUpdateListener) {
        listener = newListener
        connectivity = context.getSystemService(ConnectivityManager::class.java)
        val cm = connectivity ?: return
        stopInternal(cm)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report(cm, network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = report(cm, network)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = report(cm, network)
            override fun onLost(network: Network) {
                main.post { listener?.updateDefaultInterface("", -1, false, false) }
            }
        }
        callback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (t: Throwable) {
            Log.e(TAG, "registerDefaultNetworkCallback", t)
        }
    }

    fun stop() {
        connectivity?.let(::stopInternal)
        connectivity = null
        listener = null
    }

    private fun stopInternal(cm: ConnectivityManager) {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    private fun report(cm: ConnectivityManager, network: Network) {
        val lp = cm.getLinkProperties(network) ?: return
        val name = lp.interfaceName ?: return
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val caps = cm.getNetworkCapabilities(network)
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        main.post { listener?.updateDefaultInterface(name, index, metered, false) }
    }
}
