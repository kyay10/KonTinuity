@file:OptIn(ExperimentalContracts::class)

package io.github.kyay10.kontinuity

import arrow.core.raise.Raise
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
context(raise: Raise<E>)
fun <E> raise(e: E): Nothing = raise.raise(e)