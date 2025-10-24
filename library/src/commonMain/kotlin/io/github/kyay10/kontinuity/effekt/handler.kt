package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.*

public interface Handler<E, in IR, OR> {
  public val prompt: Prompt<E, IR, OR>
}

public interface StatefulHandler<E, S, in IR, OR> : Handler<E, IR, OR> {
  public val reader: Reader<S>
}

public fun <E, S> StatefulHandler<E, S, *, *>.get(): S = reader.ask()
public val <E, S> StatefulHandler<E, S, *, *>.value: S get() = get()

context(_: MultishotScope<IR>)
public suspend inline fun <A, E, IR, OR> Handler<E, IR, OR>.use(noinline body: suspend context(MultishotScope<OR>) (SubCont<A, E, OR>) -> E): A =
  prompt.shift(body)

context(_: MultishotScope<IR>)
public suspend inline fun <A, R, IR, OR> Handler<R, IR, OR>.useOnce(noinline body: suspend context(MultishotScope<OR>) (SubCont<A, R, OR>) -> R): A =
  prompt.shiftOnce(body)

context(_: MultishotScope<IR>)
public suspend inline fun <A, R, IR, OR> Handler<R, IR, OR>.useTailResumptive(noinline body: suspend context(MultishotScope<OR>) (SubCont<A, R, OR>) -> A): A =
  prompt.inHandlingContext(body)

context(_: MultishotScope<IR>)
public suspend inline fun <A, R, IR, OR> Handler<R, IR, OR>.useTailResumptiveTwice(noinline body: suspend context(MultishotScope<OR>) (SubCont<A, R, OR>) -> A): A =
  prompt.inHandlingContextTwice(body)

context(_: MultishotScope<IR>)
public suspend inline fun <A, R, IR, OR> Handler<R, IR, OR>.useWithFinal(noinline body: suspend context(MultishotScope<OR>) (Pair<SubCont<A, R, OR>, SubCont<A, R, OR>>) -> R): A =
  prompt.shiftWithFinal(body)

context(_: MultishotScope<IR>)
public suspend inline fun <A, R, IR, OR> Handler<R, IR, OR>.useRepushing(noinline body: suspend context(MultishotScope<OR>) (SubCont<A, R, OR>) -> R): A =
  prompt.shiftRepushing(body)

public fun <E, OR> Handler<E, *, OR>.discard(body: suspend context(MultishotScope<OR>) () -> E): Nothing =
  prompt.abortS(body)

public fun <E> Handler<E, *, *>.discardWith(value: Result<E>): Nothing = prompt.abortWith(value)

context(_: MultishotScope<IR>)
public suspend inline fun <E, IR> Handler<E, IR, *>.discardWithFast(value: Result<E>): Nothing =
  prompt.abortWithFast(value)

context(_: MultishotScope<Region>)
public suspend inline fun <E, Region> handle(crossinline body: suspend context(NewScope<Region>) HandlerPrompt<E, NewRegion, Region>.() -> E): E =
  newReset {
    body(HandlerPrompt(this))
  }

context(_: MultishotScope<Region>)
public suspend inline fun <E, S, Region> handleStateful(
  value: S,
  noinline fork: S.() -> S,
  crossinline body: suspend context(NewScope<Region>) StatefulPrompt<E, S, NewRegion, Region>.() -> E
): E = runReader(value, fork) {
  handle {
    body(StatefulPrompt(this, this@runReader))
  }
}

// TODO: turn into value class when KT-76583 is fixed
public class HandlerPrompt<E, in IR, OR> @PublishedApi internal constructor(override val prompt: Prompt<E, IR, OR>) :
  Handler<E, IR, OR>

public class StatefulPrompt<E, S, in IR, OR> @PublishedApi internal constructor(
  prompt: HandlerPrompt<E, IR, OR>, override val reader: Reader<S>
) : StatefulHandler<E, S, IR, OR>, Handler<E, IR, OR> by prompt