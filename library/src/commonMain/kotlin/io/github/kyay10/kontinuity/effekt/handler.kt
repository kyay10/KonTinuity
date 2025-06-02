package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.*

public abstract class Handler<E, IR : OR, OR>(public val prompt: PromptCont<E, IR, OR>) :
  DelegatingMultishotScope<IR>(prompt)

public abstract class StatefulHandler<E, S, IR : OR, OR>(prompt: PromptCont<E, IR, OR>) : Handler<E, IR, OR>(prompt) {
  public abstract val reader: Reader<S>
}

context(r: StatefulHandler<*, S, *, *>)
public fun <S> get(): S = with(r.reader) { ask() }

context(r: StatefulHandler<*, S, *, *>)
public val <S> value: S get() = get()

context(h: Handler<E, IR, OR>)
public suspend inline fun <A, E, IR : OR, OR> MultishotScope<IR>.use(noinline body: suspend MultishotScope<OR>.(SubCont<A, E, OR>) -> E): A =
  context(h.prompt) { shift(body) }

context(h: Handler<E, IR, OR>)
public suspend inline fun <A, E, IR : OR, OR> MultishotScope<IR>.useOnce(noinline body: suspend MultishotScope<OR>.(SubCont<A, E, OR>) -> E): A =
  context(h.prompt) { shiftOnce(body) }

context(h: Handler<R, IR, OR>)
public suspend fun <A, R, IR : OR, OR> MultishotScope<IR>.useTailResumptive(body: suspend MultishotScope<OR>.(SubCont<A, R, OR>) -> A): A =
  context(h.prompt) { inHandlingContext(body) }

context(h: Handler<R, IR, OR>)
public suspend fun <A, R, IR : OR, OR> MultishotScope<IR>.useTailResumptiveTwice(body: suspend MultishotScope<OR>.(SubCont<A, R, OR>) -> A): A =
  context(h.prompt) { inHandlingContextTwice(body) }

context(h: Handler<E, IR, OR>)
public suspend inline fun <A, E, IR : OR, OR> MultishotScope<IR>.useWithFinal(noinline body: suspend MultishotScope<OR>.(Pair<SubCont<A, E, OR>, SubCont<A, E, OR>>) -> E): A =
  context(h.prompt) { shiftWithFinal(body) }

context(h: Handler<E, IR, OR>)
public suspend inline fun <A, E, IR : OR, OR> MultishotScope<IR>.useRepushing(noinline body: suspend MultishotScope<OR>.(SubCont<A, E, OR>) -> E): A =
  context(h.prompt) { shiftRepushing(body) }

context(h: Handler<E, *, OR>)
public fun <E, OR> discard(body: suspend MultishotScope<OR>.() -> E): Nothing = context(h.prompt) { abortS(body) }

context(h: Handler<E, *, *>)
public fun <E> discardWith(value: Result<E>): Nothing = context(h.prompt) { abortWith(value) }

context(h: Handler<E, IR, *>)
public suspend inline fun <E, IR> MultishotScope<IR>.discardWithFast(value: Result<E>): Nothing =
  context(h.prompt) { abortWithFast(value) }

public suspend inline fun <E, Region> MultishotScope<Region>.handle(crossinline body: suspend HandlerPrompt<E, *, Region>.() -> E): E =
  newReset {
    body(HandlerPrompt(this))
}

public suspend inline fun <E, S, Region> MultishotScope<Region>.handleStateful(
  value: S, noinline fork: S.() -> S, crossinline body: suspend StatefulPrompt<E, S, *, Region>.() -> E
): E = runReader(value, fork) {
  handle {
    body(StatefulPrompt(this, given<Reader<S>>()))
  }
}

// TODO: turn into value class when KT-76583 is fixed
public class HandlerPrompt<E, IR : OR, OR> @PublishedApi internal constructor(prompt: PromptCont<E, IR, OR>) :
  Handler<E, IR, OR>(prompt)

public class StatefulPrompt<E, S, IR : OR, OR> @PublishedApi internal constructor(
  prompt: HandlerPrompt<E, IR, OR>, override val reader: Reader<S>
) : StatefulHandler<E, S, IR, OR>(prompt.prompt)

context(a: A)
public fun <A> given(): A = a