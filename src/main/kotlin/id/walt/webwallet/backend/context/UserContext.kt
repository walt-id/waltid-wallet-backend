package id.walt.webwallet.backend.context

import id.walt.services.context.Context
import id.walt.services.hkvstore.HKVStoreService
import id.walt.services.keystore.KeyStoreService
import id.walt.services.vcstore.VcStoreService

class UserContext(
    val contextId: String,
    override val keyStore: KeyStoreService,
    override val vcStore: VcStoreService,
    override val hkvStore: HKVStoreService
) : Context {
    override fun toString(): String {
        return contextId
    }
}
