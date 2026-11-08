package com.phantasmic.mitt.go.vpn.util

import java.nio.ByteBuffer


object TLS {

    /**
     * 判断是否是TLS Client Hello
     */
    fun isTLSClientHello(packetData: ByteBuffer): Boolean {
        if (packetData.remaining() < 43) return false
        val position = packetData.position()
        val data = packetData.array()
        if (data[position].toInt() != 0x16 /* handshake */) return false
        if (data[1 + position].toInt() != 0x03) return false
        return if (data[5 + position].toInt() != 0x01) false else data[9 + position].toInt() == 0x03 && data[10 + position] >= 0x00 && data[1 + position] <= 0x03
    }

}