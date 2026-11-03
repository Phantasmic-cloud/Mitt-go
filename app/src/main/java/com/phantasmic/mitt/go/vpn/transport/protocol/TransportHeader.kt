package com.phantasmic.mitt.go.vpn.transport.protocol

interface TransportHeader {
    fun getSourcePort(): Int
    fun getDestinationPort(): Int
}