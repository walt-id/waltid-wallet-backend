package id.walt.webwallet.backend.wallet

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.nimbusds.openid.connect.sdk.token.OIDCTokens
import id.walt.custodian.Custodian
import id.walt.model.DidMethod
import id.walt.model.DidUrl
import id.walt.model.dif.PresentationDefinition
import id.walt.model.oidc.*
import id.walt.services.context.ContextManager
import id.walt.services.oidc.OIDC4CIService
import id.walt.services.oidc.OIDCUtils
import id.walt.vclib.model.VerifiableCredential
import id.walt.vclib.templates.VcTemplateManager
import id.walt.webwallet.backend.auth.UserInfo
import id.walt.webwallet.backend.config.WalletConfig
import id.walt.webwallet.backend.context.WalletContextManager
import io.javalin.http.BadRequestResponse
import io.javalin.http.InternalServerErrorResponse
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.*

@JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
data class CredentialIssuanceRequest(
    val did: String,
    val issuerId: String,
    val credentialTypes: List<String>,
    val walletRedirectUri: String,
)

@JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
data class CredentialIssuance4PresentationRequest(
    val did: String,
    val issuerId: String,
    val presentationSessionId: String,
    val walletRedirectUri: String,
)

data class CrossDeviceIssuanceInitiationRequest(
    val oidcUri: String
)

@JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
data class CredentialIssuanceSession(
    val id: String,
    val issuerId: String,
    val credentialTypes: List<String>,
    val isPreAuthorized: Boolean,
    val isIssuerInitiated: Boolean,
    val userPinRequired: Boolean,
    @JsonIgnore val nonce: String,
    var did: String? = null,
    var walletRedirectUri: String? = null,
    @JsonIgnore var user: UserInfo? = null,
    @JsonIgnore var tokens: OIDCTokens? = null,
    @JsonIgnore var lastTokenUpdate: Instant? = null,
    @JsonIgnore var tokenNonce: String? = null,
    @JsonIgnore var preAuthzCode: String? = null,
    @JsonIgnore var opState: String? = null,
    var credentials: List<VerifiableCredential>? = null
) {
    companion object {
        fun fromIssuanceRequest(credentialIssuanceRequest: CredentialIssuanceRequest): CredentialIssuanceSession {
            return CredentialIssuanceSession(
                id = UUID.randomUUID().toString(),
                issuerId = credentialIssuanceRequest.issuerId,
                credentialTypes = credentialIssuanceRequest.credentialTypes,
                false, false, false,
                nonce = UUID.randomUUID().toString(),
                did = credentialIssuanceRequest.did,
                walletRedirectUri = credentialIssuanceRequest.walletRedirectUri
            )
        }

        fun fromInitiationRequest(issuanceInitiationRequest: IssuanceInitiationRequest): CredentialIssuanceSession {
            return CredentialIssuanceSession(
                id = UUID.randomUUID().toString(),
                issuerId = issuanceInitiationRequest.issuer_url,
                credentialTypes = issuanceInitiationRequest.credential_types,
                isPreAuthorized = issuanceInitiationRequest.isPreAuthorized,
                isIssuerInitiated = true,
                userPinRequired = issuanceInitiationRequest.user_pin_required,
                nonce = UUID.randomUUID().toString(),
                opState = issuanceInitiationRequest.op_state,
                preAuthzCode = issuanceInitiationRequest.pre_authorized_code
            )
        }
    }
}

object CredentialIssuanceManager {
    val EXPIRATION_TIME = Duration.ofMinutes(5)
    val sessionCache = CacheBuilder.newBuilder().expireAfterAccess(EXPIRATION_TIME.seconds, TimeUnit.SECONDS)
        .build<String, CredentialIssuanceSession>()
    val issuerCache: LoadingCache<String, OIDCProviderWithMetadata> = CacheBuilder.newBuilder().maximumSize(256)
        .build(
            CacheLoader.from { issuerId ->
                (   // find issuer from config
                        WalletConfig.config.issuers[issuerId!!] ?:
                        // else, assume issuerId is a valid issuer url
                        OIDCProvider(issuerId, issuerId)
                        ).let {
                        OIDC4CIService.getWithProviderMetadata(it)
                    }
            }
        )

    val redirectURI: URI
        get() = URI.create("${WalletConfig.config.walletApiUrl}/siop/finalizeIssuance")

    private fun getPreferredFormat(
        credentialTypeId: String,
        did: String,
        supportedCredentials: Map<String, CredentialMetadata>
    ): String? {
        val preferredByEcosystem = when (DidUrl.from(did).method) {
            DidMethod.iota.name -> "ldp_vc"
            DidMethod.ebsi.name -> "jwt_vc"
            else -> "jwt_vc"
        }
        if (supportedCredentials.containsKey(credentialTypeId)) {
            if (supportedCredentials[credentialTypeId]!!.formats.containsKey(preferredByEcosystem) == false) {
                // ecosystem preference is explicitly not supported, check if ldp_vc or jwt_vc is
                return supportedCredentials[credentialTypeId]!!.formats.keys.firstOrNull { fmt ->
                    setOf(
                        "jwt_vc",
                        "ldp_vc"
                    ).contains(fmt)
                }
            }
        }
        return preferredByEcosystem
    }

    fun executeAuthorizationStep(session: CredentialIssuanceSession): URI {
        val issuer = issuerCache[session.issuerId]

        val supportedCredentials = OIDC4CIService.getSupportedCredentials(issuer)
        val credentialDetails = session.credentialTypes.map {
            CredentialAuthorizationDetails(
                credential_type = it,
                format = getPreferredFormat(it, session.did!!, supportedCredentials)
            )
        }

        return OIDC4CIService.executePushedAuthorizationRequest(
            issuer,
            redirectURI,
            credentialDetails,
            nonce = session.nonce,
            state = session.id,
            wallet_issuer = WalletConfig.config.walletApiUrl,
            user_hint = URI.create(WalletConfig.config.walletUiUrl).authority,
            op_state = session.opState
        ) ?: throw InternalServerErrorResponse("Could not execute pushed authorization request on issuer")
    }

    fun startIssuance(issuanceRequest: CredentialIssuanceRequest, user: UserInfo): URI {

        val session = CredentialIssuanceSession.fromIssuanceRequest(issuanceRequest).apply {
            this.user = user
        }
        return executeAuthorizationStep(session).also {
            putSession(session)
        }
    }

    fun startIssuanceForPresentation(
        issuance4PresentationRequest: CredentialIssuance4PresentationRequest,
        user: UserInfo
    ): URI {
        val presentationSession =
            CredentialPresentationManager.getPresentationSession(issuance4PresentationRequest.presentationSessionId)
                ?: throw BadRequestResponse("No presentation session found for this session id")
        return startIssuance(
            CredentialIssuanceRequest(
                did = issuance4PresentationRequest.did,
                issuerId = issuance4PresentationRequest.issuerId,
                credentialTypes = getIssuerCredentialTypesFor(
                    presentationSession.sessionInfo.presentationDefinition,
                    issuance4PresentationRequest.issuerId
                ),
                walletRedirectUri = issuance4PresentationRequest.walletRedirectUri
            ),
            user
        )
    }

    fun startIssuerInitiatedIssuance(issuanceInitiationRequest: IssuanceInitiationRequest): String {
        val session = CredentialIssuanceSession.fromInitiationRequest(issuanceInitiationRequest)
        putSession(session)
        return session.id
    }

    fun continueIssuerInitiatedIssuance(
        sessionId: String,
        did: String,
        user: UserInfo,
        userPin: String?
    ): CredentialIssuanceSession {
        val session = sessionCache.getIfPresent(sessionId) ?: throw BadRequestResponse("Session invalid or not found")
        if (!session.isIssuerInitiated) throw BadRequestResponse("Session is not issuer initiated")
        session.did = did
        session.user = user
        putSession(session)
        if (session.isPreAuthorized) {
            return finalizeIssuance(sessionId, session.preAuthzCode!!, userPin)
        }
        return session
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    fun finalizeIssuance(id: String, code: String, userPin: String? = null): CredentialIssuanceSession {
        val session = sessionCache.getIfPresent(id) ?: throw BadRequestResponse("Session invalid or not found")
        val issuer = issuerCache[session.issuerId]
        val user = session.user ?: throw BadRequestResponse("Session has not been confirmed by user")
        val did = session.did ?: throw BadRequestResponse("No DID assigned to session")

        val tokenResponse =
            OIDC4CIService.getAccessToken(issuer, code, redirectURI.toString(), session.isPreAuthorized, userPin)
        if (!tokenResponse.indicatesSuccess()) {
            return session
        }
        session.tokens = tokenResponse.toSuccessResponse().oidcTokens
        session.lastTokenUpdate = Instant.now()
        tokenResponse.customParameters["c_nonce"]?.toString()?.also {
            session.tokenNonce = it
        }

        val supportedCredentials = OIDC4CIService.getSupportedCredentials(issuer)

        ContextManager.runWith(WalletContextManager.getUserContext(user)) {
            session.credentials = session.credentialTypes.map { typeId ->
                OIDC4CIService.getCredential(
                    issuer,
                    session.tokens!!.accessToken,
                    typeId,
                    OIDC4CIService.generateDidProof(issuer, did, session.tokenNonce ?: ""),
                    format = getPreferredFormat(typeId, did, supportedCredentials)
                )
            }.filterNotNull().map { it }

            session.credentials?.forEach {
                it.id = it.id ?: UUID.randomUUID().toString()
                Custodian.getService().storeCredential(it.id!!, it)
            }
        }
        putSession(session)
        return session
    }

    fun getSession(id: String): CredentialIssuanceSession? {
        return sessionCache.getIfPresent(id)
    }

    fun putSession(session: CredentialIssuanceSession) {
        sessionCache.put(session.id, session)
    }

    fun getIssuerWithMetadata(issuerId: String): OIDCProviderWithMetadata {
        return issuerCache[issuerId]
    }

    fun findIssuersFor(presentationDefinition: PresentationDefinition): List<OIDCProvider> {
        val matchingTemplates = findMatchingVCTemplates(presentationDefinition)

        return WalletConfig.config.issuers.keys.map { issuerCache[it] }.filter { issuer ->
            val supportedTypeLists = OIDC4CIService.getSupportedCredentials(issuer).values
                .flatMap { credentialMetadata -> credentialMetadata.formats.values }
                .map { fmt -> fmt.types }
            matchingTemplates.map { it.type }.all { reqTypeList ->
                supportedTypeLists.any { typeList ->
                    reqTypeList.size == typeList.size &&
                            reqTypeList.zip(typeList).all { (x, y) -> x == y }
                }
            }
        }.map {
            OIDCProvider(it.id, it.url, it.description) // strip secrets
        }
    }

    private fun findMatchingVCTemplates(presentationDefinition: PresentationDefinition): List<VerifiableCredential> {
        return VcTemplateManager.getTemplateList()
            .map { VcTemplateManager.loadTemplate(it) }
            .filter { tmpl ->
                presentationDefinition.input_descriptors.any { inputDescriptor ->
                    OIDCUtils.matchesInputDescriptor(tmpl, inputDescriptor)
                }
            }
    }

    private fun getIssuerCredentialTypesFor(presentationDefinition: PresentationDefinition, issuerId: String): List<String> {
        val issuer = issuerCache[issuerId]
        val reqTypeLists = findMatchingVCTemplates(presentationDefinition).map { it.type }
        val supportedCredentials = OIDC4CIService.getSupportedCredentials(issuer)
        return supportedCredentials.filter { entry ->
            entry.value.formats.values.map { it.types }.any { typeList ->
                reqTypeLists.any { reqTypeList ->
                    reqTypeList.size == typeList.size &&
                            reqTypeList.zip(typeList).all { (x, y) -> x == y }
                }
            }
        }.map { it.key }
    }
}
