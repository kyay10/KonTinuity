package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.*

public interface Handler<E> {
  public val prompt: Prompt<E>
}

public interface StatefulHandler<E, S> : Handler<E> {
  public val reader: Reader<S>
}

public fun <E, S> StatefulHandler<E, S>.get(): S = reader.ask()
public val <E, S> StatefulHandler<E, S>.value: S get() = get()

context(_: MultishotScope)
public suspend inline fun <A, E> Handler<E>.use(noinline body: suspend context(MultishotScope) (SubCont<A, E>) -> E): A =
  prompt.shift(body)

context(_: MultishotScope)
public suspend inline fun <A, E> Handler<E>.useOnce(noinline body: suspend context(MultishotScope) (SubCont<A, E>) -> E): A =
  prompt.shiftOnce(body)

context(_: MultishotScope)
public suspend inline fun <A, R> Handler<R>.useTailResumptive(noinline body: suspend context(MultishotScope) (SubCont<A, R>) -> A): A =
  prompt.inHandlingContext(body)

context(_: MultishotScope)
public suspend inline fun <A, R> Handler<R>.useTailResumptiveTwice(noinline body: suspend context(MultishotScope) (SubCont<A, R>) -> A): A =
  prompt.inHandlingContextTwice(body)

context(_: MultishotScope)
public suspend inline fun <A, E> Handler<E>.useWithFinal(noinline body: suspend context(MultishotScope) (Pair<SubCont<A, E>, SubCont<A, E>>) -> E): A =
  prompt.shiftWithFinal(body)

context(_: MultishotScope)
public suspend inline fun <A, E> Handler<E>.useRepushing(noinline body: suspend context(MultishotScope) (SubCont<A, E>) -> E): A =
  prompt.shiftRepushing(body)

public fun <E> Handler<E>.discard(body: suspend context(MultishotScope) () -> E): Nothing = prompt.abortS(body)

public fun <E> Handler<E>.discardWith(value: Result<E>): Nothing = prompt.abortWith(value)

context(_: MultishotScope)
public suspend inline fun <E> Handler<E>.discardWithFast(value: Result<E>): Nothing = prompt.abortWithFast(value)

context(_: MultishotScope)
public suspend inline fun <E> handle(crossinline body: suspend context(MultishotScope) HandlerPrompt<E>.() -> E): E =
  newReset {
    body(HandlerPrompt(this))
  }

context(_: MultishotScope)
public suspend inline fun <E, S> handleStateful(
  value: S, noinline fork: S.() -> S, crossinline body: suspend context(MultishotScope) StatefulPrompt<E, S>.() -> E
): E = runReader(value, fork) {
  handle {
    body(StatefulPrompt(this, this@runReader))
  }
}

// TODO: turn into value class when KT-76583 is fixed
public class HandlerPrompt<E> @PublishedApi internal constructor(override val prompt: Prompt<E>) : Handler<E>

public class StatefulPrompt<E, S> @PublishedApi internal constructor(
  prompt: HandlerPrompt<E>, override val reader: Reader<S>
) : StatefulHandler<E, S>, Handler<E> by prompt