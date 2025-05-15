package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.*
import io.github.kyay10.kontinuity.MultishotScope

public interface Handler<E> {
  public val prompt: Prompt<E>
}

public interface StatefulHandler<E, S> : Handler<E> {
  public val reader: Reader<S>
}

context(r: StatefulHandler<*, S>)
public fun <S> get(): S = with(r.reader) { ask() }
context(r: StatefulHandler<*, S>)
public val <S> value: S get() = get()

context(h: Handler<E>)
public suspend inline fun <A, E> MultishotScope.use(noinline body: suspend MultishotScope.(SubCont<A, E>) -> E): A =
  h.prompt.shift(body)

context(h: Handler<E>)
public suspend inline fun <A, E> MultishotScope.useOnce(noinline body: suspend MultishotScope.(SubCont<A, E>) -> E): A =
  h.prompt.shiftOnce(body)

context(h: Handler<R>)
public suspend fun <A, R> MultishotScope.useTailResumptive(body: suspend MultishotScope.(SubCont<A, R>) -> A): A =
  h.prompt.inHandlingContext(body)

context(h: Handler<R>)
public suspend fun <A, R> MultishotScope.useTailResumptiveTwice(body: suspend MultishotScope.(SubCont<A, R>) -> A): A =
  h.prompt.inHandlingContextTwice(body)

context(h: Handler<E>)
public suspend inline fun <A, E> MultishotScope.useWithFinal(noinline body: suspend MultishotScope.(Pair<SubCont<A, E>, SubCont<A, E>>) -> E): A =
  h.prompt.shiftWithFinal(body)

context(h: Handler<E>)
public suspend inline fun <A, E> MultishotScope.useRepushing(noinline body: suspend MultishotScope.(SubCont<A, E>) -> E): A =
  h.prompt.shiftRepushing(body)

context(h: Handler<E>)
public fun <E> MultishotScope.discard(body: suspend MultishotScope.() -> E): Nothing = h.prompt.abortS(body)

context(h: Handler<E>)
public fun <E> MultishotScope.discardWith(value: Result<E>): Nothing = h.prompt.abortWith(value)

context(h: Handler<E>)
public suspend inline fun <E> MultishotScope.discardWithFast(value: Result<E>): Nothing = h.prompt.abortWithFast(value)

public suspend inline fun <E> MultishotScope.handle(crossinline body: suspend context(HandlerPrompt<E>) MultishotScope.() -> E): E = newReset {
  body(HandlerPrompt(given<Prompt<E>>()), this)
}

public suspend inline fun <E, S> MultishotScope.handleStateful(
  value: S, noinline fork: S.() -> S, crossinline body: suspend context(StatefulPrompt<E, S>) MultishotScope.() -> E
): E = runReader(value, fork) {
  handle {
    body(StatefulPrompt(given<HandlerPrompt<E>>(), given<Reader<S>>()), this)
  }
}

// TODO: turn into value class when KT-76583 is fixed
public class HandlerPrompt<E> @PublishedApi internal constructor(override val prompt: Prompt<E>) : Handler<E>

public class StatefulPrompt<E, S> @PublishedApi internal constructor(
  prompt: HandlerPrompt<E>, override val reader: Reader<S>
) : StatefulHandler<E, S>, Handler<E> by prompt

context(a: A)
public fun <A> given(): A = a