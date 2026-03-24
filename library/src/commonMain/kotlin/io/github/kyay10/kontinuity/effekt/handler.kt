package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.*

public interface Handler<E> {
  public val prompt: Prompt<E>
}

@ResetDsl
public suspend inline fun <A, E> Handler<E>.useOnce(crossinline body: suspend (SubContFinal<A, E>) -> E): A =
  prompt.shiftOnce(body)

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

public class HandlerPrompt<E> @PublishedApi internal constructor(override val prompt: Prompt<E>) : Handler<E>