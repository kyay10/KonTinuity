package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.*

public interface Handler<in Region, E> {
  public val prompt: Prompt<Region, E>
}

public interface StatefulHandler<in Region, E, S> : Handler<Region, E> {
  public val reader: Reader<S>
}

context(r: StatefulHandler<*, *, S>)
public fun <S> get(): S = r.reader.ask()

context(r: StatefulHandler<*, *, S>)
public val <S> value: S get() = get()

context(h: Handler<Region, E>)
public suspend inline fun <Region, A, E> MultishotScope<Region>.use(noinline body: suspend MultishotScope<Region>.(SubCont<Region, A, E>) -> E): A =
  with(h.prompt) { shift(body) }

context(h: Handler<Region, E>)
public suspend inline fun <Region, A, E> MultishotScope<Region>.useOnce(noinline body: suspend MultishotScope<Region>.(SubCont<Region, A, E>) -> E): A =
  with(h.prompt) { shiftOnce(body) }

context(h: Handler<Region, R>)
public suspend fun <Region, A, R> MultishotScope<Region>.useTailResumptive(body: suspend MultishotScope<Region>.(SubCont<Region, A, R>) -> A): A =
  with(h.prompt) { inHandlingContext(body) }

context(h: Handler<Region, R>)
public suspend fun <Region, A, R> MultishotScope<Region>.useTailResumptiveTwice(body: suspend MultishotScope<Region>.(SubCont<Region, A, R>) -> A): A =
  with(h.prompt) { inHandlingContextTwice(body) }

context(h: Handler<Region, E>)
public suspend inline fun <Region, A, E> MultishotScope<Region>.useWithFinal(noinline body: suspend MultishotScope<Region>.(Pair<SubCont<Region, A, E>, SubCont<Region, A, E>>) -> E): A =
  with(h.prompt) { shiftWithFinal(body) }

context(h: Handler<Region, E>)
public suspend inline fun <Region, A, E> MultishotScope<Region>.useRepushing(noinline body: suspend MultishotScope<Region>.(SubCont<Region, A, E>) -> E): A =
  with(h.prompt) { shiftRepushing(body) }

context(h: Handler<Region, E>)
public fun <Region, E> MultishotScope<Region>.discard(body: suspend MultishotScope<Region>.() -> E): Nothing =
  with(h.prompt) { abortS(body) }

context(h: Handler<Region, E>)
public fun <Region, E> MultishotScope<Region>.discardWith(value: Result<E>): Nothing =
  with(h.prompt) { abortWith(value) }

context(h: Handler<Region, E>)
public suspend inline fun <Region, E> MultishotScope<Region>.discardWithFast(value: Result<E>): Nothing =
  with(h.prompt) { abortWithFast(value) }

public interface HandlerFunction<in Region, R> {
  context(_: HandlerPrompt<Region2, R>)
  public suspend operator fun <Region2 : Region> MultishotScope<Region2>.invoke(): R
}

public interface StatefulFunction<in Region, S, R> {
  context(_: StatefulPrompt<Region2, S, R>)
  public suspend operator fun <Region2 : Region> MultishotScope<Region2>.invoke(): R
}

public suspend inline fun <Region, E> MultishotScope<Region>.handle(body: HandlerFunction<Region, E>): E = newReset(
  object : PromptFunction<Region, E> {
    context(p: Prompt<Region2, E>)
    override suspend fun <Region2 : Region> MultishotScope<Region2>.invoke(): E {
      return with(body) {
        with(HandlerPrompt(p)) {
          invoke()
        }
      }
    }
  })

public suspend inline fun <Region, E, S> MultishotScope<Region>.handleStateful(
  value: S, noinline fork: S.() -> S, body: StatefulFunction<Region, S, E>
): E = runReader(value, fork) {
  handle(object: HandlerFunction<Region, E> {
    context(p: HandlerPrompt<Region2, E>)
    override suspend fun <Region2 : Region> MultishotScope<Region2>.invoke(): E {
      return with(body) {
        with(StatefulPrompt(p, given<Reader<S>>())) {
          invoke()
        }
      }
    }
  })
}

// TODO: turn into value class when KT-76583 is fixed
public class HandlerPrompt<in Region, E> @PublishedApi internal constructor(override val prompt: Prompt<Region, E>) :
  Handler<Region, E>

public class StatefulPrompt<in Region, E, S> @PublishedApi internal constructor(
  prompt: HandlerPrompt<Region, E>, override val reader: Reader<S>
) : StatefulHandler<Region, E, S>, Handler<Region, E> by prompt

context(a: A)
public fun <A> given(): A = a