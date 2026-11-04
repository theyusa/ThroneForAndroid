// Based on ccding/go-stun (Apache-2.0) as carried by NekoBoxPlus
package io.nekohasekai.sagernet.utils.stun

import android.net.Network
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Android-specific StunSocketFactory supporting direct physical network socket binding
 * (bypassing the active VPN TUN interface).
 */
class AndroidStunSocketFactory(
    private val network: Network? = null,
) : StunSocketFactory {
    override fun createDatagramSocket(): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        network?.bindSocket(socket)
        socket.bind(InetSocketAddress(0))
        return socket
    }
}
