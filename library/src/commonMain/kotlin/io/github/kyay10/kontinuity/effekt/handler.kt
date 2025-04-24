package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.Prompt
import io.github.kyay10.kontinuity.Reader
import io.github.kyay10.kontinuity.SubCont
import io.github.kyay10.kontinuity.abortS0
import io.github.kyay10.kontinuity.abortWith0
import io.github.kyay10.kontinuity.abortWithFast
import io.github.kyay10.kontinuity.ask
import io.github.kyay10.kontinuity.pushReader
import io.github.kyay10.kontinuity.reset
import io.github.kyay10.kontinuity.takeSubCont
import io.github.kyay10.kontinuity.takeSubContOnce
import io.github.kyay10.kontinuity.takeSubContWithFinal

public interface Handler<E> {
  public val prompt: HandlerPrompt<E>
}

public interface StatefulHandler<E, S> : Handler<E> {
  public val reader: Reader<S>
}

public suspend inline fun <E, S> StatefulHandler<E, S>.get(): S = reader.ask()

public suspend inline fun <A, E> Handler<E>.use(crossinline body: suspend (Cont<A, E>) -> E): A =
  prompt.p.takeSubCont { sk ->
    body(Cont(sk))
  }

public suspend inline fun <A, E> Handler<E>.useOnce(crossinline body: suspend (Cont<A, E>) -> E): A =
  prompt.p.takeSubContOnce { sk ->
    body(Cont(sk))
  }

public suspend inline fun <A, E> Handler<E>.useWithFinal(crossinline body: suspend (Pair<Cont<A, E>, Cont<A, E>>) -> E): A =
  prompt.p.takeSubContWithFinal { sk ->
    body(Cont(sk.first) to Cont(sk.second))
  }

public fun <E> Handler<E>.discard(body: suspend () -> E): Nothing = prompt.p.abortS0(body)

public fun <E> Handler<E>.discardWith(value: Result<E>): Nothing = prompt.p.abortWith0(value)

public suspend inline fun <E> Handler<E>.discardWithFast(value: Result<E>): Nothing = prompt.p.abortWithFast(deleteDelimiter = true, value)

public suspend inline fun <E> handle(crossinline body: suspend HandlerPrompt<E>.() -> E): E = with(HandlerPrompt<E>()) {
  rehandle { body() }
}

// TODO maybe we should remove this? Effekt gets by without it (but their lambdas are restricted)
public suspend inline fun <E> Handler<E>.rehandle(noinline body: suspend () -> E): E = prompt.p.reset(body)

public suspend inline fun <E, S> handleStateful(
  value: S, noinline fork: S.() -> S, crossinline body: suspend StatefulPrompt<E, S>.() -> E
): E = with(StatefulPrompt<E, S>()) {
  rehandleStateful(value, fork) { body() }
}

public suspend inline fun <E, S> StatefulHandler<E, S>.rehandleStateful(
  value: S, noinline fork: S.() -> S, noinline body: suspend () -> E
): E = reader.pushReader(value, fork) {
  rehandle(body)
}
// TODO: turn into value class when KT-76583 is fixed
public class HandlerPrompt<E> private constructor(@PublishedApi internal val p: Prompt<E>) : Handler<E> {
  public constructor() : this(Prompt())

  override val prompt: HandlerPrompt<E> get() = this
}

public class StatefulPrompt<E, S>(
  prompt: HandlerPrompt<E> = HandlerPrompt(), override val reader: Reader<S> = Reader()
) : StatefulHandler<E, S>, Handler<E> by prompt

// TODO: turn into value class when KT-76583 is fixed
public class Cont<in T, out R> @PublishedApi internal constructor(@PublishedApi internal val subCont: SubCont<T, R>) {
  public suspend inline fun resumeWith(value: Result<T>): R =
    subCont.pushSubContWith(value, isDelimiting = true)

  public suspend inline fun locally(noinline value: suspend () -> T): R =
    subCont.pushSubCont(isDelimiting = true, value)

  public suspend inline operator fun invoke(value: T): R =
    resumeWith(Result.success(value))

  public suspend inline fun resumeWithException(exception: Throwable): R =
    resumeWith(Result.failure(exception))
}