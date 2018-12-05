package com.lykke.matching.engine.incoming.listener

import com.lykke.matching.engine.incoming.LoggerNames
import com.lykke.matching.engine.incoming.preprocessor.impl.CashInOutPreprocessor
import com.lykke.matching.engine.messages.MessageWrapper
import com.lykke.utils.logging.ThrottlingLogger
import org.springframework.stereotype.Component
import java.util.concurrent.BlockingQueue

@Component
class CashInOutInputQueueListener(cashInOutInputQueue: BlockingQueue<MessageWrapper>,
                                  cashInOutPreprocessor: CashInOutPreprocessor)
    : InputQueueListener(cashInOutInputQueue, cashInOutPreprocessor, ThrottlingLogger.getLogger(LoggerNames.CASH_IN_OUT))