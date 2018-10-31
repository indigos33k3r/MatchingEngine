package com.lykke.matching.engine.config

import com.lykke.matching.engine.daos.LkkTrade
import com.lykke.matching.engine.database.PersistenceManager
import com.lykke.matching.engine.database.cache.ApplicationSettingsCache
import com.lykke.matching.engine.fee.FeeProcessor
import com.lykke.matching.engine.holders.AssetsHolder
import com.lykke.matching.engine.holders.BalancesHolder
import com.lykke.matching.engine.holders.MessageSequenceNumberHolder
import com.lykke.matching.engine.matching.MatchingEngine
import com.lykke.matching.engine.order.ExecutionConfirmationService
import com.lykke.matching.engine.order.ExecutionEventSender
import com.lykke.matching.engine.order.ExecutionPersistenceService
import com.lykke.matching.engine.order.cancel.GenericLimitOrdersCancellerFactory
import com.lykke.matching.engine.order.process.GenericLimitOrdersProcessor
import com.lykke.matching.engine.order.process.LimitOrderProcessor
import com.lykke.matching.engine.order.process.PreviousLimitOrdersProcessor
import com.lykke.matching.engine.order.process.StopLimitOrderProcessor
import com.lykke.matching.engine.order.process.StopOrderBookProcessor
import com.lykke.matching.engine.order.process.common.MatchingResultHandlingHelper
import com.lykke.matching.engine.order.transaction.ExecutionContextFactory
import com.lykke.matching.engine.outgoing.messages.LimitOrdersReport
import com.lykke.matching.engine.outgoing.messages.MarketOrderWithTrades
import com.lykke.matching.engine.outgoing.messages.OrderBook
import com.lykke.matching.engine.services.GenericLimitOrderService
import com.lykke.matching.engine.services.GenericStopLimitOrderService
import com.lykke.matching.engine.services.MessageSender
import com.lykke.matching.engine.services.validators.business.LimitOrderBusinessValidator
import com.lykke.matching.engine.services.validators.business.StopOrderBusinessValidator
import com.lykke.matching.engine.services.validators.input.LimitOrderInputValidator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.BlockingQueue

@Configuration
open class TestExecutionContext {

    @Bean
    open fun matchingEngine(genericLimitOrderService: GenericLimitOrderService,
                            balancesHolder: BalancesHolder,
                            feeProcessor: FeeProcessor): MatchingEngine {
        return MatchingEngine(genericLimitOrderService,
                feeProcessor)
    }

    @Bean
    open fun executionContextFactory(balancesHolder: BalancesHolder,
                                     genericLimitOrderService: GenericLimitOrderService,
                                     genericStopLimitOrderService: GenericStopLimitOrderService,
                                     assetsHolder: AssetsHolder): ExecutionContextFactory {
        return ExecutionContextFactory(balancesHolder,
                genericLimitOrderService,
                genericStopLimitOrderService,
                assetsHolder)
    }

    @Bean
    open fun executionPersistenceService(persistenceManager: PersistenceManager): ExecutionPersistenceService {
        return ExecutionPersistenceService(persistenceManager)
    }

    @Bean
    open fun executionEventSender(messageSequenceNumberHolder: MessageSequenceNumberHolder,
                                  messageSender: MessageSender,
                                  clientLimitOrdersQueue: BlockingQueue<LimitOrdersReport>,
                                  trustedClientsLimitOrdersQueue: BlockingQueue<LimitOrdersReport>,
                                  rabbitSwapQueue: BlockingQueue<MarketOrderWithTrades>,
                                  lkkTradesQueue: BlockingQueue<List<LkkTrade>>,
                                  genericLimitOrderService: GenericLimitOrderService,
                                  orderBookQueue: BlockingQueue<OrderBook>,
                                  rabbitOrderBookQueue: BlockingQueue<OrderBook>): ExecutionEventSender {
        return ExecutionEventSender(messageSequenceNumberHolder,
                messageSender,
                clientLimitOrdersQueue,
                trustedClientsLimitOrdersQueue,
                rabbitSwapQueue,
                lkkTradesQueue,
                genericLimitOrderService,
                orderBookQueue,
                rabbitOrderBookQueue)
    }

    @Bean
    open fun executionConfirmationService(executionPersistenceService: ExecutionPersistenceService,
                                          executionEventSender: ExecutionEventSender): ExecutionConfirmationService {
        return ExecutionConfirmationService(executionPersistenceService, executionEventSender)
    }

    @Bean
    open fun limitOrderProcessor(limitOrderInputValidator: LimitOrderInputValidator,
                                 limitOrderBusinessValidator: LimitOrderBusinessValidator,
                                 applicationSettingsCache: ApplicationSettingsCache,
                                 matchingEngine: MatchingEngine,
                                 matchingResultHandlingHelper: MatchingResultHandlingHelper): LimitOrderProcessor {
        return LimitOrderProcessor(limitOrderInputValidator,
                limitOrderBusinessValidator,
                applicationSettingsCache,
                matchingEngine,
                matchingResultHandlingHelper)
    }

    @Bean
    open fun stopLimitOrdersProcessor(limitOrderInputValidator: LimitOrderInputValidator,
                                      stopOrderBusinessValidator: StopOrderBusinessValidator,
                                      applicationSettingsCache: ApplicationSettingsCache,
                                      limitOrderProcessor: LimitOrderProcessor): StopLimitOrderProcessor {
        return StopLimitOrderProcessor(limitOrderInputValidator,
                stopOrderBusinessValidator,
                applicationSettingsCache,
                limitOrderProcessor)
    }

    @Bean
    open fun genericLimitOrdersProcessor(limitOrderProcessor: LimitOrderProcessor,
                                         stopLimitOrdersProcessor: StopLimitOrderProcessor): GenericLimitOrdersProcessor {
        return GenericLimitOrdersProcessor(limitOrderProcessor, stopLimitOrdersProcessor)
    }

    @Bean
    open fun stopOrderBookProcessor(limitOrderProcessor: LimitOrderProcessor,
                                    applicationSettingsCache: ApplicationSettingsCache): StopOrderBookProcessor {
        return StopOrderBookProcessor(limitOrderProcessor, applicationSettingsCache)
    }

    @Bean fun matchingResultHandlingHelper(applicationSettingsCache: ApplicationSettingsCache): MatchingResultHandlingHelper {
        return MatchingResultHandlingHelper(applicationSettingsCache)
    }

    @Bean
    open fun previousLimitOrdersProcessor(genericLimitOrderService: GenericLimitOrderService,
                                          genericStopLimitOrderService: GenericStopLimitOrderService,
                                          genericLimitOrdersCancellerFactory: GenericLimitOrdersCancellerFactory): PreviousLimitOrdersProcessor {
        return PreviousLimitOrdersProcessor(genericLimitOrderService, genericStopLimitOrderService, genericLimitOrdersCancellerFactory)
    }
}