package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.*

@ResetDsl
public suspend inline fun <A, E> Handler<E>.use(crossinline body: suspend (SubCont<A, E>) -> E): A =
  prompt.shift(body)

@ResetDsl
public suspend inline fun <A, R> Handler<R>.useTailResumptive(crossinline body: suspend (SubCont<A, R>) -> A): A =
  prompt.inHandlingContext(body)

@ResetDsl
public suspend inline fun <A, E> Handler<E>.useWithFinal(crossinline body: suspend (SubCont<A, E>, SubContFinal<A, E>) -> E): A =
  prompt.shiftWithFinal(body)