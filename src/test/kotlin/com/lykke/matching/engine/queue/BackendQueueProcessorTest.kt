package com.lykke.matching.engine.queue

import com.google.gson.Gson
import com.lykke.matching.engine.daos.Asset
import com.lykke.matching.engine.daos.ClientCashOperationPair
import com.lykke.matching.engine.daos.ClientOrderPair
import com.lykke.matching.engine.daos.ClientTradePair
import com.lykke.matching.engine.daos.Orders
import com.lykke.matching.engine.daos.WalletCredentials
import com.lykke.matching.engine.database.TestBackOfficeDatabaseAccessor
import com.lykke.matching.engine.queue.transaction.CashIn
import com.lykke.matching.engine.queue.transaction.CashOut
import com.lykke.matching.engine.queue.transaction.Swap
import com.lykke.matching.engine.queue.transaction.Transaction
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BackendQueueProcessorTest {

    val backOfficeDatabaseAccessor = TestBackOfficeDatabaseAccessor()

    @Before
    fun setUp() {
        backOfficeDatabaseAccessor.clear()
        var credentials = WalletCredentials()
        credentials.multiSig = "TestMultiSig1"
        credentials.privateKey = "TestPrivateKey1"
        backOfficeDatabaseAccessor.credentials.put("Client1", credentials)

        credentials = WalletCredentials()
        credentials.multiSig = "TestMultiSig2"
        credentials.privateKey = "TestPrivateKey2"
        backOfficeDatabaseAccessor.credentials.put("Client2", credentials)

        var asset = Asset()
        asset.blockChainId = "TestUSD"
        backOfficeDatabaseAccessor.assets.put("USD", asset)
        asset = Asset()
        asset.blockChainId = "TestEUR"
        backOfficeDatabaseAccessor.assets.put("EUR", asset)
    }

    @Test
    fun testCashIn() {
        val inQueue = LinkedBlockingQueue<Transaction>()
        val outQueueWriter = TestQueueWriter()
        val processor = BackendQueueProcessor(backOfficeDatabaseAccessor, inQueue, outQueueWriter)

        val cashIn = CashIn(TransactionId = "11", clientId = "Client1", Amount = 500.0, Currency = "USD", cashOperationId = "123")
        processor.processMessage(cashIn)

        val cashInData = Gson().fromJson(outQueueWriter.read().replace("CashIn:", ""), CashIn::class.java)

        assertEquals("TestMultiSig1",cashInData.MultisigAddress)
        assertEquals(500.0,cashInData.Amount)
        assertEquals("TestUSD",cashInData.Currency)

        assertNotNull(backOfficeDatabaseAccessor.transactions.find { it.partitionKey == "TransId" && it.rowKey == cashInData.TransactionId && it.contextData == Gson().toJson(ClientCashOperationPair("Client1", "123")) })
    }

    @Test
    fun testCashOut() {
        val inQueue = LinkedBlockingQueue<Transaction>()
        val outQueueWriter = TestQueueWriter()
        val processor = BackendQueueProcessor(backOfficeDatabaseAccessor, inQueue, outQueueWriter)

        val cashOut = CashOut(TransactionId = "11", clientId = "Client1", Amount = 500.0, Currency = "USD", cashOperationId = "123")
        processor.processMessage(cashOut)

        val cashOutData = Gson().fromJson(outQueueWriter.read().replace("CashOut:", ""), CashOut::class.java)

        assertEquals("TestMultiSig1",cashOutData.MultisigAddress)
        assertEquals("TestPrivateKey1",cashOutData.PrivateKey)
        assertEquals(500.0,cashOutData.Amount)
        assertEquals("TestUSD",cashOutData.Currency)

        assertNotNull(backOfficeDatabaseAccessor.transactions.find { it.partitionKey == "TransId" && it.rowKey == cashOutData.TransactionId && it.contextData == Gson().toJson(ClientCashOperationPair("Client1", "123")) })
    }

    @Test
    fun testSwap() {
        val inQueue = LinkedBlockingQueue<Transaction>()
        val outQueueWriter = TestQueueWriter()
        val processor = BackendQueueProcessor(backOfficeDatabaseAccessor, inQueue, outQueueWriter)

        val swap = Swap(TransactionId = "11", clientId1 = "Client1", Amount1 = 500.0, origAsset1 = "USD",
                        clientId2 = "Client2", Amount2 = 500.0, origAsset2 = "EUR",
                        orders = Orders(ClientOrderPair("Client1", "Order1"), ClientOrderPair("Client1", "Order2"),
                        arrayOf(ClientTradePair("Client1", "uid1"), ClientTradePair("Client1", "uid2"),
                                ClientTradePair("Client2", "uid3"), ClientTradePair("Client2", "uid4"))))
        processor.processMessage(swap)

        val swapData = Gson().fromJson(outQueueWriter.read().replace("Swap:", ""), Swap::class.java)

        assertEquals("TestMultiSig1",swap.MultisigCustomer1)
        assertEquals(500.0,swap.Amount1)
        assertEquals("TestUSD",swap.Asset1)
        assertEquals("TestMultiSig2",swap.MultisigCustomer2)
        assertEquals(500.0,swap.Amount2)
        assertEquals("TestEUR",swap.Asset2)

        assertNotNull(backOfficeDatabaseAccessor.transactions.find { it.partitionKey == "TransId" && it.rowKey == swapData.TransactionId &&
                it.contextData == Gson().toJson(Orders(ClientOrderPair("Client1", "Order1"), ClientOrderPair("Client1", "Order2"),
                        arrayOf(ClientTradePair("Client1", "uid1"), ClientTradePair("Client1", "uid2"),
                        ClientTradePair("Client2", "uid3"), ClientTradePair("Client2", "uid4")))) })
    }
}