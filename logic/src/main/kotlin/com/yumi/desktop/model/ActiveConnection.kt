package com.yumi.desktop.model

import com.mydrop.vpn.core.model.ProxyNode
import kotlinx.serialization.Serializable

/** The server actually acknowledged by the service, independent of subscription refreshes. */
@Serializable
data class ActiveConnection(
    val serviceNodeId: String? = null,
    val connectedAt: Long = 0,
    val node: ProxyNode? = null,
)
