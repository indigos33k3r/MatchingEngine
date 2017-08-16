package com.lykke.matching.engine.messages

enum class MessageStatus(val type: Int){
    OK(0),
    LOW_BALANCE(401),
    ALREADY_PROCESSED(402),
    UNKNOWN_ASSET(410),
    NO_LIQUIDITY(411),
    NOT_ENOUGH_FUNDS(412),
    DUST(413),
    RESERVED_VOLUME_HIGHER_THAN_BALANCE(414),
    LIMIT_ORDER_NOT_FOUND(415),
    BALANCE_LOWER_THAN_RESERVED(416),
    RUNTIME(500)
}