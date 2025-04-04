@file:OptIn(ExperimentalContracts::class)

import arrow.core.raise.SingletonRaise
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

context(t: T) fun <T> given(): T = t

context(raise: SingletonRaise<*>)
fun ensure(condition: Boolean) {
  contract {
    returns() implies condition
  }
  raise.ensure(condition)
}

context(raise: SingletonRaise<*>)
fun raise(): Nothing = raise.raise()