package com.lykke.matching.engine.outgoing.rabbit.impl.listeners

import com.lykke.matching.engine.database.azure.AzureMessageLogDatabaseAccessor
import com.lykke.matching.engine.logging.DatabaseLogger
import com.lykke.matching.engine.outgoing.messages.v2.events.Event
import com.lykke.matching.engine.outgoing.rabbit.RabbitMqService
import com.lykke.matching.engine.outgoing.rabbit.impl.dispatchers.RabbitEventDispatcher
import com.lykke.matching.engine.outgoing.rabbit.utils.RabbitEventUtils
import com.lykke.matching.engine.utils.config.Config
import com.lykke.utils.AppVersion
import com.rabbitmq.client.BuiltinExchangeType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.concurrent.BlockingDeque
import java.util.concurrent.BlockingQueue
import javax.annotation.PostConstruct

@Component
class ClientsEventListener {

    @Autowired
    private lateinit var clientsEventsQueue: BlockingDeque<Event<*>>

    @Autowired
    private lateinit var rabbitMqService: RabbitMqService<Event<*>>

    @Autowired
    private lateinit var config: Config

    @Autowired
    private lateinit var applicationEventPublisher: ApplicationEventPublisher

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Value("\${azure.logs.blob.container}")
    private lateinit var logBlobName: String

    @Value("\${azure.logs.clients.events.table}")
    private lateinit var logTable: String

    @PostConstruct
    fun initRabbitMqPublisher() {
        val consumerNameToQueue = HashMap<String, BlockingQueue<Event<*>>>()
        config.me.rabbitMqConfigs.events.forEachIndexed { index, rabbitConfig ->
            val clientsEventConsumerQueue = RabbitEventUtils.getClientEventConsumerQueueName(rabbitConfig.exchange, index)
            val queue = applicationContext.getBean(clientsEventConsumerQueue) as BlockingQueue<Event<*>>

            consumerNameToQueue.put(clientsEventConsumerQueue, queue)
            rabbitMqService.startPublisher(rabbitConfig, clientsEventConsumerQueue, queue,
                    config.me.name,
                    AppVersion.VERSION,
                    BuiltinExchangeType.DIRECT,
                    DatabaseLogger(
                            AzureMessageLogDatabaseAccessor(config.me.db.messageLogConnString,
                                    "$logTable$index", "$logBlobName$index")))
        }
        RabbitEventDispatcher("ClientEventsDispatcher", clientsEventsQueue, consumerNameToQueue, applicationEventPublisher).start()
    }
}