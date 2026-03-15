package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.*

public interface Handler<E> {
  public val prompt: Prompt<E>
}

public interface StatefulHandler<E, S> : Handler<E> {
  public val value: S
}

@ResetDsl
public suspend inline fun <A, E> Handler<E>.use(crossinline body: suspend (SubCont<A, E>) -> E): A =
  prompt.shift(body)

@ResetDsl
public suspend inline fun <A, E> Handler<E>.useOnce(crossinline body: suspend (SubCont<A, E>) -> E): A =
  prompt.shiftOnce(body)

@ResetDsl
public suspend inline fun <A, R> Handler<R>.useTailResumptive(crossinline body: suspend (SubCont<A, R>) -> A): A =
  prompt.inHandlingContext(body)

@ResetDsl
public suspend inline fun <A, E> Handler<E>.useWithFinal(crossinline body: suspend (SubCont<A, E>, SubCont<A, E>) -> E): A =
  prompt.shiftWithFinal(body)

@ResetDsl
public fun <E> Handler<E>.discard(body: suspend () -> E): Nothing = prompt.abortS(body)

@ResetDsl
public fun <E> Handler<E>.discardWith(value: Result<E>): Nothing = prompt.abortWith(value)

@ResetDsl
public suspend inline fun <E> Handler<E>.discardWithFast(value: Result<E>): Nothing = prompt.abortWithFast(value)

@ResetDsl
public suspend inline fun <E> handle(crossinline body: suspend HandlerPrompt<E>.() -> E): E = newReset {
  body(HandlerPrompt(this))
}

@ResetDsl
public suspend inline fun <E, S> handleStateful(
  value: S, noinline fork: S.() -> S, crossinline body: suspend StatefulPrompt<E, S>.() -> E
): E = runReader(value, fork) {
  handle {
    body(StatefulPrompt(this, this@runReader))
  }
}

@ResetDsl
public suspend inline fun <E, S : Stateful<S>> handleStateful(
  value: S, crossinline body: suspend StatefulPrompt<E, S>.() -> E
): E = handleStateful(value, Stateful<S>::fork, body)

public class HandlerPrompt<E> @PublishedApi internal constructor(override val prompt: Prompt<E>) : Handler<E>

public class StatefulPrompt<E, S> @PublishedApi internal constructor(
  prompt: HandlerPrompt<E>, private val reader: Reader<S>
) : StatefulHandler<E, S>, Handler<E> by prompt {
  override val value: S get() = reader.value
}