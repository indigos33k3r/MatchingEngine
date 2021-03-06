package com.lykke.matching.engine.incoming.preprocessor.impl

import com.lykke.matching.engine.daos.context.CashTransferContext
import com.lykke.matching.engine.database.CashOperationIdDatabaseAccessor
import com.lykke.matching.engine.database.PersistenceManager
import com.lykke.matching.engine.database.common.entity.PersistenceData
import com.lykke.matching.engine.deduplication.ProcessedMessagesCache
import com.lykke.matching.engine.holders.MessageProcessingStatusHolder
import com.lykke.matching.engine.incoming.parsers.data.CashTransferParsedData
import com.lykke.matching.engine.incoming.parsers.impl.CashTransferContextParser
import com.lykke.matching.engine.incoming.preprocessor.MessagePreprocessor
import com.lykke.matching.engine.messages.MessageStatus
import com.lykke.matching.engine.messages.MessageStatus.DUPLICATE
import com.lykke.matching.engine.messages.MessageStatus.RUNTIME
import com.lykke.matching.engine.messages.MessageWrapper
import com.lykke.matching.engine.messages.ProtocolMessages
import com.lykke.matching.engine.services.validators.impl.ValidationException
import com.lykke.matching.engine.services.validators.input.CashTransferOperationInputValidator
import com.lykke.matching.engine.utils.NumberUtils
import com.lykke.matching.engine.utils.order.MessageStatusUtils
import com.lykke.utils.logging.MetricsLogger
import com.lykke.utils.logging.ThrottlingLogger
import org.apache.commons.lang3.StringUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.concurrent.BlockingQueue

@Component
class CashTransferPreprocessor(
        private val cashTransferInputQueue: BlockingQueue<MessageWrapper>,
        private val preProcessedMessageQueue: BlockingQueue<MessageWrapper>,
        private val cashOperationIdDatabaseAccessor: CashOperationIdDatabaseAccessor,
        private val cashTransferPreprocessorPersistenceManager: PersistenceManager,
        private val processedMessagesCache: ProcessedMessagesCache,
        private val messageProcessingStatusHolder: MessageProcessingStatusHolder
): MessagePreprocessor, Thread(CashTransferPreprocessor::class.java.name) {

    companion object {
        val LOGGER = ThrottlingLogger.getLogger(CashTransferPreprocessor::class.java.name)
        val METRICS_LOGGER = MetricsLogger.getLogger()
    }

    @Autowired
    private lateinit var contextParser: CashTransferContextParser

    @Autowired
    private lateinit var cashTransferOperationInputValidator: CashTransferOperationInputValidator

    override fun preProcess(messageWrapper: MessageWrapper) {
        val cashTransferParsedData = contextParser.parse(messageWrapper)
        val cashTransferContext = cashTransferParsedData.messageWrapper.context as CashTransferContext
        if (messageProcessingStatusHolder.isCashTransferDisabled(cashTransferContext.transferOperation.asset)) {
            writeResponse(cashTransferParsedData.messageWrapper, MessageStatus.MESSAGE_PROCESSING_DISABLED)
            return
        }

        if (!validateData(cashTransferParsedData)) {
            return
        }

        if (!isMessageDuplicated(cashTransferParsedData)) {
            preProcessedMessageQueue.put(messageWrapper)
        }
    }

    fun validateData(cashTransferParsedData: CashTransferParsedData): Boolean {
        try {
            cashTransferOperationInputValidator.performValidation(cashTransferParsedData)
        } catch (e: ValidationException) {
            processInvalidData(cashTransferParsedData, e.validationType, e.message)
            return false
        }

        return true
    }

    private fun processInvalidData(cashTransferParsedData: CashTransferParsedData,
                                   validationType: ValidationException.Validation,
                                   message: String) {
        val messageWrapper = cashTransferParsedData.messageWrapper
        val context = messageWrapper.context as CashTransferContext
        LOGGER.info("Input validation failed messageId: ${context.messageId}, details: $message")

        val persistSuccess = cashTransferPreprocessorPersistenceManager.persist(PersistenceData(context.processedMessage))
        if (!persistSuccess) {
            throw Exception("Persistence error")
        }

        try {
            processedMessagesCache.addMessage(context.processedMessage)
            writeErrorResponse(messageWrapper, context, MessageStatusUtils.toMessageStatus(validationType), message)
        } catch (e: Exception) {
            LOGGER.error("Error occurred during processing of invalid cash transfer data, context $context", e)
            METRICS_LOGGER.logError("Error occurred during invalid data processing, ${messageWrapper.type} ${context.messageId}")
        }
    }

    private fun isMessageDuplicated(cashTransferParsedData: CashTransferParsedData): Boolean {
        val parsedMessageWrapper = cashTransferParsedData.messageWrapper
        val context = parsedMessageWrapper.context as CashTransferContext

        if (cashOperationIdDatabaseAccessor.isAlreadyProcessed(parsedMessageWrapper.type.toString(), context.messageId)) {
            writeResponse(parsedMessageWrapper, DUPLICATE)
            LOGGER.info("Message already processed: ${parsedMessageWrapper.type}: ${context.messageId}")
            METRICS_LOGGER.logError("Message already processed: ${parsedMessageWrapper.type}: ${context.messageId}")
            return true
        }
        return false
    }

    override fun writeResponse(messageWrapper: MessageWrapper, status: MessageStatus, message: String?) {
        messageWrapper.writeNewResponse(ProtocolMessages.NewResponse.newBuilder().setStatus(status.type))
    }

    private fun writeErrorResponse(messageWrapper: MessageWrapper,
                                   context: CashTransferContext,
                                   status: MessageStatus,
                                   errorMessage: String = StringUtils.EMPTY) {
        messageWrapper.writeNewResponse(ProtocolMessages.NewResponse.newBuilder()
                .setMatchingEngineId(context.transferOperation.matchingEngineOperationId)
                .setStatus(status.type)
                .setStatusReason(errorMessage))
        LOGGER.info("Cash transfer operation (${context.transferOperation.externalId}) from client ${context.transferOperation.fromClientId} " +
                "to client ${context.transferOperation.toClientId}, asset ${context.transferOperation.asset}," +
                " volume: ${NumberUtils.roundForPrint(context.transferOperation.volume)}: $errorMessage")
    }

    override fun run() {
        while (true) {
            val messageWrapper = cashTransferInputQueue.take()
            try {
                messageWrapper.messagePreProcessorStartTimestamp = System.nanoTime()
                preProcess(messageWrapper)
                messageWrapper.messagePreProcessorEndTimestamp = System.nanoTime()
            } catch (exception: Exception) {
                handlePreprocessingException(exception, messageWrapper)
            }
        }
    }

    private fun handlePreprocessingException(exception: Exception, message: MessageWrapper) {
        try {
            val context = message.context
            LOGGER.error("[${message.sourceIp}]: Got error during message preprocessing: ${exception.message} " +
                    if (context != null) "Error details: $context" else "", exception)

            METRICS_LOGGER.logError("[${message.sourceIp}]: Got error during message preprocessing", exception)
            writeResponse(message, RUNTIME)
        } catch (e: Exception) {
            val errorMessage = "Got error during message preprocessing failure handling"
            e.addSuppressed(exception)
            LOGGER.error(errorMessage, e)
            METRICS_LOGGER.logError(errorMessage, e)
        }
    }
}