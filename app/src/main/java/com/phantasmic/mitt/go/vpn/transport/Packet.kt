package com.phantasmic.mitt.go.vpn.transport

import com.phantasmic.mitt.go.vpn.transport.protocol.IP4Header
import com.phantasmic.mitt.go.vpn.transport.protocol.TransportHeader

class Packet(var ipHeader: IP4Header, var transportHeader: TransportHeader, var buffer: ByteArray) {
}