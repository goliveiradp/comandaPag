package br.com.comandapag.rede

import java.net.Inet4Address
import java.net.NetworkInterface

/** IPs desta maquininha na rede local (para mostrar no modo servidor). */
fun enderecosLocais(): List<String> =
    runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
