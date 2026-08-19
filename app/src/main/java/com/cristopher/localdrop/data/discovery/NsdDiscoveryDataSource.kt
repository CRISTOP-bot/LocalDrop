package com.cristopher.localdrop.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.cristopher.localdrop.domain.model.DeviceType
import com.cristopher.localdrop.domain.model.LocalDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class NsdDiscoveryDataSource(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val serviceType = "_localdrop._tcp."
    @Volatile private var activeDiscovery: NsdManager.DiscoveryListener? = null
    @Volatile private var registrationListener: NsdManager.RegistrationListener? = null

    fun discover(localId: String): Flow<List<LocalDevice>> = callbackFlow {
        val found = linkedMapOf<String, LocalDevice>()
        fun emitFound() { trySend(found.values.toList()) }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) { found.remove(serviceInfo.serviceName); emitFound() }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceName.startsWith("LocalDrop-")) return
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val attrs = info.attributes
                            val id = attrs["id"]?.toString(Charsets.UTF_8) ?: info.serviceName
                            if (id == localId) return
                            val host = info.host?.hostAddress ?: return
                            val type = DeviceType.entries.firstOrNull { it.name.equals(attrs["type"]?.toString(Charsets.UTF_8), true) } ?: DeviceType.UNKNOWN
                            found[id] = LocalDevice(
                                id = id,
                                name = attrs["name"]?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank) ?: info.serviceName.removePrefix("LocalDrop-"),
                                host = host,
                                port = info.port,
                                type = type,
                                paired = false
                            )
                            emitFound()
                        }
                    })
                } catch (_: Exception) { /* NSD can race with a network change. */ }
            }
        }
        activeDiscovery = listener
        try { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) } catch (error: Exception) { close(error) }
        awaitClose { stopDiscovery(listener) }
    }

    fun register(name: String, port: Int, id: String, type: DeviceType) {
        val info = NsdServiceInfo().apply {
            serviceName = "LocalDrop-${id.take(8)}"
            serviceType = this@NsdDiscoveryDataSource.serviceType
            this.port = port
            setAttribute("name", name.take(64))
            setAttribute("id", id)
            setAttribute("type", type.name)
            setAttribute("v", "1")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) { }
    }

    fun stopDiscovery(listener: NsdManager.DiscoveryListener? = activeDiscovery) {
        if (listener == null) return
        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) { }
        if (activeDiscovery === listener) activeDiscovery = null
    }

    fun unregister() {
        stopDiscovery()
        registrationListener?.let { try { nsd.unregisterService(it) } catch (_: Exception) { } }
        registrationListener = null
    }
}
