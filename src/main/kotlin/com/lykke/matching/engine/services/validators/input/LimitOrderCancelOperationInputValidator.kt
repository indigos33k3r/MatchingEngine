package com.lykke.matching.engine.services.validators.input

import com.lykke.matching.engine.incoming.data.LimitOrderCancelOperationParsedData

interface LimitOrderCancelOperationInputValidator {
    fun performValidation(limitOrderCancelOperationParsedData: LimitOrderCancelOperationParsedData)
}