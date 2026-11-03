package com.phantasmic.mitt.go.vpn.socket

import com.phantasmic.mitt.go.vpn.Connection

interface CloseableConnection {
    /**
     * 关闭连接
     */
    fun closeConnection(connection: Connection)
}