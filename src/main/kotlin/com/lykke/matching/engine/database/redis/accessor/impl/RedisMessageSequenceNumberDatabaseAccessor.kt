package com.lykke.matching.engine.database.redis.accessor.impl

import com.lykke.matching.engine.database.ReadOnlyMessageSequenceNumberDatabaseAccessor
import com.lykke.matching.engine.database.redis.InitialLoadingRedisHolder
import redis.clients.jedis.Transaction

class RedisMessageSequenceNumberDatabaseAccessor(private val redisHolder: InitialLoadingRedisHolder,
                                                 private val dbIndex: Int) : ReadOnlyMessageSequenceNumberDatabaseAccessor {
    companion object {
        private const val KEY = "MessageSequenceNumber"
    }

    override fun getSequenceNumber(): Long {
        val jedis = redisHolder.initialLoadingRedis()
        jedis.select(dbIndex)
        return jedis[KEY]?.toLong() ?: 0
    }

    fun save(transaction: Transaction, sequenceNumber: Long) {
        transaction.select(dbIndex)
        transaction.set(KEY, sequenceNumber.toString())
    }

}