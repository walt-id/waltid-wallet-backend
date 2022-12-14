package id.walt.gateway.dto.trades

import kotlinx.serialization.Serializable

@Serializable
data class TradeData(
    val result: Boolean,
    val message: String? = null,
)
