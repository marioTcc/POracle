package com.example.orders

import java.math.BigDecimal

const val DEFAULT_CURRENCY = "EUR"

/**
 * Computes the price of an order.
 */
class OrderService(private val repository: OrderRepository) : PricingService {

    private val discounts = mutableMapOf<String, BigDecimal>()

    override fun priceOf(orderId: String): BigDecimal {
        val order = repository.findById(orderId)
        return order.total - discountFor(order.customer)
    }

    fun discountFor(customer: String): BigDecimal = discounts[customer] ?: BigDecimal.ZERO

    companion object {
        const val MAX_DISCOUNT_PERCENT = 30

        fun create(): OrderService = OrderService(OrderRepository())
    }
}

enum class OrderStatus {
    OPEN,
    SHIPPED
}

object OrderDefaults {
    fun currency(): String = DEFAULT_CURRENCY
}

fun String.toOrderId(): String = "order-" + this
