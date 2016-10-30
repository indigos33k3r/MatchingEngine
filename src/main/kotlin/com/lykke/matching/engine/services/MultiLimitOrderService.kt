package com.lykke.matching.engine.services

import com.lykke.matching.engine.daos.LimitOrder
import com.lykke.matching.engine.daos.TradeInfo
import com.lykke.matching.engine.logging.MetricsLogger
import com.lykke.matching.engine.messages.MessageWrapper
import com.lykke.matching.engine.messages.ProtocolMessages
import com.lykke.matching.engine.order.OrderStatus
import com.lykke.matching.engine.utils.RoundingUtils
import org.apache.log4j.Logger
import java.util.ArrayList
import java.util.Date
import java.util.UUID

class MultiLimitOrderService(val limitOrderService: GenericLimitOrderService): AbsractService<ProtocolMessages.MultiLimitOrder> {

    companion object {
        val LOGGER = Logger.getLogger(MultiLimitOrderService::class.java.name)
        val METRICS_LOGGER = MetricsLogger.getLogger()
    }

    private var messagesCount: Long = 0

    override fun processMessage(messageWrapper: MessageWrapper) {
        val message = parse(messageWrapper.byteArray)
        LOGGER.debug("Got multi limit order id: ${message.uid}, client ${message.clientId}, assetPair: ${message.assetPairId}")

        val orders = ArrayList<LimitOrder>(message.ordersList.size)

        var cancelBuySide = false
        var cancelSellSide = false

        message.ordersList.forEach { currentOrder ->
            orders.add(LimitOrder(UUID.randomUUID().toString(), message.assetPairId, message.clientId, currentOrder.volume,
                    currentOrder.price, OrderStatus.InOrderBook.name, Date(message.timestamp), Date(), null, currentOrder.volume, null))

            if (message.cancelAllPreviousLimitOrders) {
                if (currentOrder.volume > 0) {
                    cancelBuySide = true
                } else {
                    cancelSellSide = true
                }
            }
        }

        val ordersToCancel = ArrayList<LimitOrder>()

        if (message.cancelAllPreviousLimitOrders) {
            if (cancelBuySide) {
                ordersToCancel.addAll(limitOrderService.getAllPreviousOrders(message.clientId, message.assetPairId, true))
            }
            if (cancelSellSide) {
                ordersToCancel.addAll(limitOrderService.getAllPreviousOrders(message.clientId, message.assetPairId, false))
            }
        }

        val orderBook = limitOrderService.getOrderBook(message.assetPairId).copy()

        ordersToCancel.forEach { order ->
            orderBook.removeOrder(order)
        }

        orders.forEach { order ->
            orderBook.addOrder(order)
            limitOrderService.addOrder(order)
            limitOrderService.putTradeInfo(TradeInfo(order.assetPairId, order.isBuySide, order.price, Date()))

            LOGGER.info("Part of multi limit order id: ${message.uid}, client ${order.clientId}, assetPair: ${order.assetPairId}, volume: ${RoundingUtils.roundForPrint(order.volume)}, price: ${RoundingUtils.roundForPrint(order.price)} added to order book")
        }

        limitOrderService.setOrderBook(message.assetPairId, orderBook)
        limitOrderService.cancelLimitOrders(ordersToCancel)
        limitOrderService.addOrders(orders)

        messageWrapper.writeResponse(ProtocolMessages.Response.newBuilder().setUid(message.uid).build())
    }

    private fun parse(array: ByteArray): ProtocolMessages.MultiLimitOrder {
        return ProtocolMessages.MultiLimitOrder.parseFrom(array)
    }
}