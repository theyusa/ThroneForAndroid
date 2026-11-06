// Based on ccding/go-stun (Apache-2.0) as carried by NekoBoxPlus
package io.nekohasekai.sagernet.utils.stun

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Socket factory abstraction allowing STUN tests to bind to specific physical networks
 * or to mock/loopback sockets in unit tests without android.* dependencies.
 */
interface StunSocketFactory {
    fun createDatagramSocket(): DatagramSocket
    fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
}

/**
 * Default JVM datagram socket factory binding to any local port.
 */
class DefaultStunSocketFactory : StunSocketFactory {
    override fun createDatagramSocket(): DatagramSocket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(0))
    }
}
