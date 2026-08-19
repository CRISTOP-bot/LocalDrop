package com.cristopher.localdrop.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.cristopher.localdrop.utils.isPrivateIpv4

fun localIpv4Address(context: Context): String? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val active = manager.activeNetwork
    val networks = buildList {
        active?.let(::add)
        manager.allNetworks.forEach { if (it != active) add(it) }
    }
    fun addressFor(network: android.net.Network): String? {
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val priority = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
            else -> 2
        }
        if (priority > 1) return null
        return manager.getLinkProperties(network)?.linkAddresses.orEmpty().asSequence()
            .mapNotNull { it.address.hostAddress }
            .firstOrNull(::isPrivateIpv4)
    }
    val activeCapabilities = active?.let(manager::getNetworkCapabilities)
    if (activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return addressFor(active)
    return networks.asSequence().mapNotNull(::addressFor).firstOrNull()
}
