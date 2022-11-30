package id.walt.gateway.providers.metaco.restapi.transaction.model

import kotlinx.serialization.Serializable

@Serializable
data class AccountReference(
    val domainId: String,
    val id: String
)