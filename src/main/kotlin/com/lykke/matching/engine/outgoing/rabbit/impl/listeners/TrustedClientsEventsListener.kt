package com.lykke.matching.engine.outgoing.rabbit.impl.listeners

import com.lykke.matching.engine.outgoing.messages.v2.events.Event
import com.lykke.matching.engine.outgoing.rabbit.RabbitMqService
import com.lykke.matching.engine.outgoing.rabbit.impl.dispatchers.RabbitEventDispatcher
import com.lykke.matching.engine.outgoing.rabbit.utils.RabbitEventUtils
import com.lykke.matching.engine.utils.config.Config
import com.lykke.utils.AppVersion
import com.rabbitmq.client.BuiltinExchangeType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.concurrent.BlockingDeque
import java.util.concurrent.BlockingQueue
import javax.annotation.PostConstruct

@Component
class TrustedClientsEventsListener {

    @Autowired
    private lateinit var trustedClientsEventsDeque: BlockingDeque<Event<*>>

    @Autowired
    private lateinit var rabbitMqService: RabbitMqService<Event<*>>

    @Autowired
    private lateinit var config: Config

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var applicationEventPublisher: ApplicationEventPublisher

    @PostConstruct
    fun initRabbitMqPublisher() {
        val consumerNameToQueue = HashMap<String, BlockingQueue<Event<*>>>()
        config.me.rabbitMqConfigs.trustedClientsEvents.forEachIndexed { index, rabbitConfig ->
            val trustedClientsEventConsumerQueue = RabbitEventUtils.getTrustedClientsEventConsumerQueue(rabbitConfig.exchange, index)
            val queue = applicationContext.getBean(trustedClientsEventConsumerQueue) as BlockingQueue<Event<*>>

            consumerNameToQueue.put(trustedClientsEventConsumerQueue, queue)

            rabbitMqService.startPublisher(rabbitConfig,
                    trustedClientsEventConsumerQueue,
                    queue,
                    config.me.name,
                    AppVersion.VERSION,
                    BuiltinExchangeType.DIRECT,
                    null)
        }

        RabbitEventDispatcher("TrustedClientEventsDispatcher", trustedClientsEventsDeque, consumerNameToQueue, applicationEventPublisher).start()
    }
}