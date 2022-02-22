package id.walt.issuer.backend

import com.nimbusds.oauth2.sdk.AuthorizationRequest
import id.walt.model.oidc.CredentialClaim

data class IssuanceSession (
val id: String,
val credentialClaims: List<CredentialClaim>,
val authRequest: AuthorizationRequest,
val nonce: String,
var issuables: Issuables?,
var did: String? = null
    )
