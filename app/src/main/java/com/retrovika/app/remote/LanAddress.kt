package com.retrovika.app.remote

import java.net.Inet4Address
import java.net.NetworkInterface

/** O endereço deste aparelho na rede local, que vai no QR code e no endereço da tela. */
object LanAddress {
    data class Candidate(val interfaceName: String, val address: String)

    fun find(): String? {
        val candidates = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { nic ->
                    nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { Candidate(nic.name, it.hostAddress.orEmpty()) }
                }
        }.getOrDefault(emptyList())
        return choose(candidates)
    }

    /**
     * Só endereços privados (é a rede de casa, não a da operadora). Wi-Fi primeiro, depois o roteador do
     * próprio celular (ponto de acesso) e cabo/USB. Os dados móveis ficam de fora mesmo com endereço
     * privado: muitas operadoras dão 10.x (CGNAT), que ninguém da casa alcança.
     */
    fun choose(candidates: List<Candidate>): String? =
        candidates
            .filter { isPrivate(it.address) && MOBILE_OR_VPN.none { prefix -> it.interfaceName.startsWith(prefix) } }
            .minByOrNull { c -> PREFERRED.indexOfFirst { c.interfaceName.startsWith(it) }.let { if (it < 0) PREFERRED.size else it } }
            ?.address

    fun isPrivate(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }

    private val PREFERRED = listOf("wlan", "swlan", "ap", "eth", "rndis", "usb")
    /** Dados móveis (Qualcomm, MediaTek, Samsung, Unisoc), o 464xlat deles e VPNs. */
    private val MOBILE_OR_VPN = listOf("rmnet", "ccmni", "pdp", "seth", "v4-", "clat", "tun", "ppp", "ipsec", "dummy")
}
