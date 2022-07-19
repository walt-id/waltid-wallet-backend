package id.walt.verifier.backend

import com.beust.klaxon.JsonObject
import com.google.common.cache.CacheBuilder
import id.walt.model.dif.InputDescriptor
import id.walt.model.dif.PresentationDefinition
import id.walt.model.oidc.SIOPv2Request
import id.walt.model.oidc.VpTokenClaim
import id.walt.services.context.ContextManager
import id.walt.services.did.DidService
import id.walt.services.hkvstore.FileSystemHKVStore
import id.walt.services.hkvstore.FilesystemStoreConfig
import id.walt.services.jwt.JwtService
import id.walt.services.key.KeyService
import id.walt.services.keystore.HKVKeyStoreService
import id.walt.services.vcstore.HKVVcStoreService
import id.walt.WALTID_DATA_ROOT
import id.walt.auditor.*
import id.walt.model.dif.VCSchema
import id.walt.model.oidc.VCClaims
import id.walt.servicematrix.BaseService
import id.walt.servicematrix.ServiceRegistry
import id.walt.services.context.Context
import id.walt.vclib.credentials.VerifiablePresentation
import id.walt.vclib.model.toCredential
import id.walt.webwallet.backend.auth.JWTService
import id.walt.webwallet.backend.auth.UserInfo
import id.walt.webwallet.backend.context.UserContext
import io.javalin.http.BadRequestResponse
import java.net.URI
import java.util.*
import java.util.concurrent.TimeUnit

abstract class VerifierManager: BaseService() {
  val reqCache = CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build<String, SIOPv2Request>()
  val respCache =
    CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build<String, SIOPResponseVerificationResult>()

  abstract val verifierContext: Context

  open fun newRequest(tokenClaim: VpTokenClaim, state: String? = null, redirectCustomUrlQuery: String = ""): SIOPv2Request {
    val nonce = UUID.randomUUID().toString()
    val requestId = state ?: nonce
    val redirectQuery = if(redirectCustomUrlQuery.isEmpty()) "" else "?$redirectCustomUrlQuery"
    val req = SIOPv2Request(
      redirect_uri = "${VerifierConfig.config.verifierApiUrl}/verify$redirectQuery",
      response_mode = "form_post",
      nonce = nonce,
      claims = VCClaims(
        vp_token = tokenClaim
      ),
      state = requestId
    )
    reqCache.put(requestId, req)
    return req
  }

  open fun newRequest(schemaUris: Set<String>, state: String? = null, redirectCustomUrlQuery: String = ""): SIOPv2Request {
    return newRequest(VpTokenClaim(
      presentation_definition = PresentationDefinition(
        id = "1",
        input_descriptors = schemaUris.map { schemaUri ->
          InputDescriptor(
            id = "1",
            schema = VCSchema(uri = schemaUri)
          )
        }.toList()
      )
    ), state, redirectCustomUrlQuery)
  }

  open fun getVerififactionPoliciesFor(req: SIOPv2Request): List<VerificationPolicy> {
    return listOf(
      SignaturePolicy(),
      ChallengePolicy(req.nonce!!, applyToVC = false, applyToVP = true),
      VpTokenClaimPolicy(req.claims.vp_token!!),
      *(VerifierConfig.config.additionalPolicies?.map { p ->
        PolicyRegistry.getPolicyWithJsonArg(p.policy, p.argument?.let { JsonObject(it) })
      }?.toList() ?: listOf()).toTypedArray()
    )
  }

  /*
  - find cached request
  - parse and verify id_token
  - parse and verify vp_token
  -  - compare nonce (verification policy)
  -  - compare token_claim => token_ref => vp (verification policy)
   */
  open fun verifyResponse(state: String, id_token: String, vp_token: String): SIOPResponseVerificationResult {
    val req = reqCache.getIfPresent(state) ?: throw BadRequestResponse("State invalid or expired")
    reqCache.invalidate(state)
    val id_token_claims = JwtService.getService().parseClaims(id_token)!!
    val sub = id_token_claims.get("sub").toString()
    val vp = vp_token.toCredential() as VerifiablePresentation

    var result = SIOPResponseVerificationResult(
      state,
      sub,
      req,
      ContextManager.runWith(verifierContext) {
        if (!KeyService.getService().hasKey(sub))
          DidService.importKey(sub)
        JwtService.getService().verify(id_token)
      },
      ContextManager.runWith(verifierContext) {

        Auditor.getService().verify(
          vp_token, getVerififactionPoliciesFor(req)
        )
      },
      vp_token = vp,
      null
    )

    if (result.isValid) {
      result.auth_token = JWTService.toJWT(UserInfo(result.subject!!))
    }

    respCache.put(result.state, result)

    return result
  }

  open fun getVerificationRedirectionUri(verificationResult: SIOPResponseVerificationResult, uiUrl: String? = VerifierConfig.config.verifierUiUrl): URI {
    if(verificationResult.isValid == true)
      return URI.create("$uiUrl/success/?access_token=${verificationResult.state}")
    else
      return URI.create("$uiUrl/error/?access_token=${verificationResult.state ?: ""}")
  }

  fun getVerificationResult(id: String): SIOPResponseVerificationResult? {
    return respCache.getIfPresent(id).also {
      respCache.invalidate(id)
    }
  }

  override val implementation: BaseService
    get() = serviceImplementation<VerifierManager>()

  companion object {
    fun getService(): VerifierManager = ServiceRegistry.getService()
  }
}

class DefaultVerifierManager : VerifierManager() {
  override val verifierContext = UserContext(
    contextId = "Verifier",
    hkvStore = FileSystemHKVStore(FilesystemStoreConfig("$WALTID_DATA_ROOT/data/verifier")),
    keyStore = HKVKeyStoreService(),
    vcStore = HKVVcStoreService()
  )

}
