package id.walt.webwallet.backend.quick.setup

import id.walt.crypto.KeyAlgorithm
import id.walt.issuer.backend.IssuerConfig
import id.walt.issuer.backend.IssuerManager
import id.walt.issuer.backend.IssuerTenant
import id.walt.model.DidMethod
import id.walt.multitenancy.TenantType
import id.walt.services.did.DidService
import id.walt.services.key.KeyService
import id.walt.verifier.backend.VerifierConfig
import id.walt.verifier.backend.VerifierManager
import id.walt.verifier.backend.VerifierTenant
import id.walt.verifier.backend.WalletConfiguration
import id.walt.webwallet.backend.context.WalletContextManager
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.*


object QuickSetup {
    fun run(hosts: List<String>) = generateToken(9).let {
        QuickConfig(
            issuer = createConfig("iss-tenant-$it", TenantType.ISSUER),
            verifier = createConfig("vfr-tenant-$it", TenantType.VERIFIER, hosts),
        )
    }

    private fun createConfig(tenantId: String, type: TenantType, hosts: List<String>? = null) = let {
        val (context, path) = when (type) {
            TenantType.ISSUER -> Pair(IssuerManager.getIssuerContext(tenantId), "/issuer-api")
            TenantType.VERIFIER -> Pair(VerifierManager.getService().getVerifierContext(tenantId), "/verifier-api")
        }

        val url = "https://wallet.walt-test.cloud$path"
        // set context
        WalletContextManager.setCurrentContext(context)
        // create key-pair
        val key = KeyService.getService().generate(KeyAlgorithm.EdDSA_Ed25519)
        // create did
        val did = DidService.create(DidMethod.key, key.id)
        // create config
        createTenantConfig(type, "${url.removePrefix("https://wallet")}/$tenantId", did, hosts)
        // build quick config object
        QuickConfig.TenantQuickConfig(
            tenantId = tenantId,
            did = did,
            url = url
        )
    }

    private fun createTenantConfig(type: TenantType, apiUrl: String, did: String? = null, hosts: List<String>?) =
        when (type) {
            TenantType.ISSUER -> createIssuerConfig(apiUrl, did!!)
            TenantType.VERIFIER -> createVerifierConfig(apiUrl, hosts!!)
        }

    private fun createIssuerConfig(apiUrl: String, did: String) = IssuerTenant.setConfig(
        IssuerConfig(
            issuerApiUrl = "https://issuer$apiUrl",
            issuerDid = did,
            wallets = WalletConfiguration.getDefaultWalletConfigurations("https://wallet.walt-test.cloud"),
        )
    )

    private fun createVerifierConfig(apiUrl: String, hosts: List<String>) = VerifierTenant.setConfig(
        VerifierConfig(
            verifierApiUrl = "https://verifier$apiUrl",
            allowedWebhookHosts = hosts,
            wallets = WalletConfiguration.getDefaultWalletConfigurations("https://wallet.walt-test.cloud")
        )
    )

    fun generateToken(size: Int): String {
        val random = SecureRandom()
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

@Serializable
data class QuickConfig(
    val issuer: TenantQuickConfig,
    val verifier: TenantQuickConfig,
) {
    @Serializable
    data class TenantQuickConfig(
        val tenantId: String,
        val did: String,
        val url: String,
    )
}