package com.lykke.matching.engine.holders

import com.lykke.matching.engine.common.events.RefMidPriceDangerousChangeEvent
import com.lykke.matching.engine.daos.AssetPair
import com.lykke.matching.engine.daos.MidPrice
import com.lykke.matching.engine.database.ReadOnlyMidPriceDatabaseAccessor
import com.lykke.matching.engine.utils.NumberUtils
import com.lykke.matching.engine.utils.monitoring.OrderBookMidPriceChecker
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.util.CollectionUtils
import java.math.BigDecimal
import java.util.*

@Component
class MidPriceHolder(@Value("#{Config.me.referenceMidPricePeriod}") private val refreshMidPricePeriod: Long,
                     readOnlyMidPriceDatabaseAccessor: ReadOnlyMidPriceDatabaseAccessor,
                     private val midPriceChecker: OrderBookMidPriceChecker) {

    private val MAX_MID_PRICE_RECALCULATION_COUNT = 1000

    private val midPricesByAssetPairId = HashMap<String, LinkedList<MidPrice>>()
    private val referencePriceByAssetPairId = HashMap<String, BigDecimal>()
    private val prevReferencePriceByAssetPairId = HashMap<String, BigDecimal>()
    private val midPriceTimestampByAssetPairId = HashMap<String, Long>()
    private val readyRefMidPricesAssetPairs = HashSet<String>()

    private var midPriceRecalculationCount = 0

    init {
        readOnlyMidPriceDatabaseAccessor
                .getMidPricesByAssetPairMap()
                .forEach { assetPairId, midPrices -> midPricesByAssetPairId[assetPairId] = LinkedList(midPrices) }

        midPricesByAssetPairId.forEach { key, value ->
            midPriceTimestampByAssetPairId[key] = value.first.timestamp
            performFullRecalculationOfReferenceMidPrice(key)
        }
    }

    fun getReferenceMidPrice(assetPair: AssetPair, operationTime: Date): BigDecimal {
        if (!isMidPriceDataReady(assetPair.assetPairId)) {
            return BigDecimal.ZERO
        }

        val midPriceFirstTimeReady = readyRefMidPricesAssetPairs.add(assetPair.assetPairId)
        removeObsoleteMidPrices(assetPair.assetPairId, getLowerTimeBound(operationTime.time))

        val refMidPrice = referencePriceByAssetPairId[assetPair.assetPairId]

        val result = if (refMidPrice != null && !NumberUtils.equalsIgnoreScale(refMidPrice, BigDecimal.ZERO)) {
            refMidPrice
        } else prevReferencePriceByAssetPairId[assetPair.assetPairId] ?: BigDecimal.ZERO

        val scaledReferenceMidPrice = NumberUtils.setScaleRoundUp(result, assetPair.accuracy)

        if (midPriceFirstTimeReady) {
            midPriceChecker.checkOrderBook(RefMidPriceDangerousChangeEvent(assetPair.assetPairId, scaledReferenceMidPrice, false))
        }

        return scaledReferenceMidPrice
    }

    fun addMidPrice(assetPair: AssetPair, newMidPrice: BigDecimal, operationTime: Date, cancel: Boolean = false) {
        midPriceTimestampByAssetPairId.putIfAbsent(assetPair.assetPairId, operationTime.time)
        removeObsoleteMidPrices(assetPair.assetPairId, getLowerTimeBound(operationTime.time))
        val midPriceDangerous =  isMidPriceChangeDangerous(assetPair.assetPairId, newMidPrice) && cancel

        val midPrices = midPricesByAssetPairId.getOrPut(assetPair.assetPairId) { LinkedList() }

        midPrices.add(MidPrice(assetPair.assetPairId, newMidPrice, operationTime.time))
        recalculateReferenceMidPriceForNewAddedMidPrice(assetPair.assetPairId, newMidPrice)

        if (midPriceDangerous) {
            midPriceChecker.checkOrderBook(RefMidPriceDangerousChangeEvent(assetPair.assetPairId, referencePriceByAssetPairId[assetPair.assetPairId]!!, cancel))
        }
    }

    fun clear() {
        midPriceTimestampByAssetPairId.clear()
        midPricesByAssetPairId.clear()
        referencePriceByAssetPairId.clear()
        prevReferencePriceByAssetPairId.clear()
        readyRefMidPricesAssetPairs.clear()
        midPriceRecalculationCount = 0
    }

    fun isMidPriceChangeDangerous(assetPairId: String, newMidPrice: BigDecimal): Boolean {
        val midPrices = midPricesByAssetPairId[assetPairId]
        if (CollectionUtils.isEmpty(midPrices)) {
            return false
        }

        return midPrices!!.last.midPrice > newMidPrice
    }

    private fun removeObsoleteMidPrices(assetPairId: String, lowerBoundTimeMillisBound: Long) {
        val midPrices = midPricesByAssetPairId.getOrPut(assetPairId) { LinkedList() }

        val initialSize = midPrices.size
        var removedMidPricesSum = BigDecimal.ZERO

        while (midPrices.size > 0 && midPrices.first().timestamp < lowerBoundTimeMillisBound) {
            removedMidPricesSum += midPrices.removeFirst().midPrice
        }

        recalculateReferenceMidPriceAfterRemoval(removedMidPricesSum, initialSize, midPrices.size, assetPairId)
    }

    private fun recalculateReferenceMidPriceForNewAddedMidPrice(assetPairId: String,
                                                                midPrice: BigDecimal) {

        if (midPriceRecalculationCount == MAX_MID_PRICE_RECALCULATION_COUNT) {
            performFullRecalculationOfReferenceMidPrice(assetPairId)
            return
        }

        val currentReferenceMidPrice = referencePriceByAssetPairId[assetPairId] ?: BigDecimal.ZERO
        val previousMidPricesSize = midPricesByAssetPairId[assetPairId]!!.size - 1

        val result = if (NumberUtils.equalsIgnoreScale(currentReferenceMidPrice, BigDecimal.ZERO) || previousMidPricesSize == 0) {
            midPrice
        } else {
            val newMidPriceWithOldMidPricesCount = currentReferenceMidPrice + NumberUtils.divideWithMaxScale(midPrice, BigDecimal.valueOf(previousMidPricesSize.toLong()))
            val newMidPriceCoef = NumberUtils.divideWithMaxScale(BigDecimal.valueOf(previousMidPricesSize.toLong() + 1), BigDecimal.valueOf(previousMidPricesSize.toLong()))
            NumberUtils.divideWithMaxScale(newMidPriceWithOldMidPricesCount, newMidPriceCoef)
        }

        setRefMidPrice(assetPairId, result)
        midPriceRecalculationCount++
    }

    private fun setRefMidPrice(assetPairId: String, newMidPrice: BigDecimal) {
        referencePriceByAssetPairId[assetPairId]?.let {
            prevReferencePriceByAssetPairId[assetPairId] = it
        }

        referencePriceByAssetPairId[assetPairId] = newMidPrice
    }

    private fun recalculateReferenceMidPriceAfterRemoval(removedMidPricesSum: BigDecimal,
                                                         midPricesInitialSize: Int,
                                                         currentSize: Int,
                                                         assetPairId: String) {
        if (currentSize == 0) {
            setRefMidPrice(assetPairId, BigDecimal.ZERO)
            return
        }

        val removedMidPricesCount = midPricesInitialSize - currentSize
        val referencePrice = referencePriceByAssetPairId.getOrPut(assetPairId) { BigDecimal.ZERO }

        if (midPricesInitialSize == 0 || removedMidPricesCount == 0 || NumberUtils.equalsIgnoreScale(referencePrice, BigDecimal.ZERO)) {
            return
        }

        //perform full reference mid price recalculation if we exceeded max mid price recalculations count - to
        // prevent accumulation of calculation error
        if (MAX_MID_PRICE_RECALCULATION_COUNT == midPriceRecalculationCount) {
            performFullRecalculationOfReferenceMidPrice(assetPairId)
            return
        }

        val midPricesInitialSize = BigDecimal.valueOf(midPricesInitialSize.toLong())
        val referencePriceWithoutObsolete = referencePrice - NumberUtils.divideWithMaxScale(removedMidPricesSum, midPricesInitialSize)
        val newMidPriceCoef = NumberUtils.divideWithMaxScale(BigDecimal.valueOf(currentSize.toLong()), midPricesInitialSize)

        val result = NumberUtils.divideWithMaxScale(referencePriceWithoutObsolete, newMidPriceCoef)
        setRefMidPrice(assetPairId, result)
        midPriceRecalculationCount++
    }

    private fun performFullRecalculationOfReferenceMidPrice(assetPairId: String) {
        val midPrices = midPricesByAssetPairId.getOrPut(assetPairId) { LinkedList() }

        midPriceRecalculationCount = 0
        var sum = BigDecimal.ZERO
        val midPricesCount = BigDecimal.valueOf(midPrices.size.toLong())

        if (NumberUtils.equalsIgnoreScale(midPricesCount, BigDecimal.ZERO)) {
            return
        }

        midPrices.forEach { midPrice -> sum += midPrice.midPrice }
        val result = NumberUtils.divideWithMaxScale(sum, midPricesCount)
        setRefMidPrice(assetPairId, result)
    }

    private fun getLowerTimeBound(operationTimeMillis: Long): Long {
        return operationTimeMillis - refreshMidPricePeriod
    }

    private fun isMidPriceDataReady(assetPairId: String): Boolean {
        val timestamp = midPriceTimestampByAssetPairId[assetPairId] ?: return false
        return timestamp + refreshMidPricePeriod <= Date().time
    }
}