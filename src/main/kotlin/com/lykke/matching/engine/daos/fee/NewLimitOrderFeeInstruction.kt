package com.lykke.matching.engine.daos.fee

import com.lykke.matching.engine.daos.FeeSizeType
import com.lykke.matching.engine.daos.FeeType
import com.lykke.matching.engine.messages.ProtocolMessages
import org.nustaq.serialization.annotations.Version

class NewLimitOrderFeeInstruction(
        type: FeeType,
        takerSizeType: FeeSizeType?,
        takerSize: Double?,
        val makerSizeType: FeeSizeType?,
        val makerSize: Double?,
        sourceClientId: String?,
        targetClientId: String?,
        assetIds: List<String>,
        @Version(1)
        val makerFeeModificator: Double?
) : NewFeeInstruction(type, takerSizeType, takerSize, sourceClientId, targetClientId, assetIds) {

    companion object {
        fun create(fees: List<ProtocolMessages.LimitOrderFee>): List<NewLimitOrderFeeInstruction> {
            return fees.map { create(it) }
        }

        fun create(fee: ProtocolMessages.LimitOrderFee): NewLimitOrderFeeInstruction {
            val feeType = FeeType.getByExternalId(fee.type)
            var takerSizeType: FeeSizeType? = if (fee.hasTakerSize()) FeeSizeType.getByExternalId(fee.takerSizeType) else null
            var makerSizeType: FeeSizeType? = if (fee.hasMakerSizeType()) FeeSizeType.getByExternalId(fee.makerSizeType) else null
            if (feeType != FeeType.NO_FEE) {
                if (takerSizeType == null) {
                    takerSizeType = FeeSizeType.PERCENTAGE
                }
                if (makerSizeType == null) {
                    makerSizeType = FeeSizeType.PERCENTAGE
                }
            }
            return NewLimitOrderFeeInstruction(
                    feeType,
                    takerSizeType,
                    if (fee.hasTakerSize()) fee.takerSize else null,
                    makerSizeType,
                    if (fee.hasMakerSize()) fee.makerSize else null,
                    if (fee.hasSourceClientId()) fee.sourceClientId else null,
                    if (fee.hasTargetClientId()) fee.targetClientId else null,
                    fee.assetIdList.toList(),
                    if (fee.hasMakerFeeModificator() && fee.makerFeeModificator != 0.0) fee.makerFeeModificator else null)
        }
    }

    override fun toString(): String {
        return "NewLimitOrderFeeInstruction(type=$type" +
                (if (sizeType != null) ", takerSizeType=$sizeType" else "") +
                (if (size != null) ", takerSize=$size" else "") +
                (if (makerSizeType != null) ", makerSizeType=$makerSizeType" else "") +
                (if (makerSize != null) ", makerSize=$makerSize" else "") +
                (if (makerFeeModificator != null) ", makerFeeModificator=$makerFeeModificator" else "") +
                (if (assetIds.isNotEmpty() == true) ", assetIds=$assetIds" else "") +
                (if (sourceClientId?.isNotEmpty() == true) ", sourceClientId=$sourceClientId" else "") +
                "${if (targetClientId?.isNotEmpty() == true) ", targetClientId=$targetClientId" else ""})"
    }

}