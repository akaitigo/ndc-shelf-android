package com.google.crypto.tink.hybrid.internal

import com.google.crypto.tink.hybrid.HpkeParameters

/**
 * Tink 1.18のRFC 9180 base mode sender context factoryはpackage-privateの
 * ため、同一packageから最小限のbridgeを公開する。HPKEのKEM・KDF・AEADと
 * key schedule・seal/openは全てTinkの実装をそのまま使い、独自実装しない。
 * protocol側の要求（info・AADの分離指定）はTinkの公開HybridEncryptでは
 * 表現できないため、context APIを直接使う。
 */
object NdcShelfHpkeSender {
    fun createSenderContext(
        recipientPublicKey: ByteArray,
        kemId: HpkeParameters.KemId,
        kdfId: HpkeParameters.KdfId,
        aeadId: HpkeParameters.AeadId,
        info: ByteArray,
    ): HpkeContext =
        HpkeContext.createSenderContext(
            recipientPublicKey,
            HpkePrimitiveFactory.createKem(kemId),
            HpkePrimitiveFactory.createKdf(kdfId),
            HpkePrimitiveFactory.createAead(aeadId),
            info,
        )
}
