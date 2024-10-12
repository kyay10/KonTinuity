package effekt

import Prompt
import Reader
import abortS0
import abortWith0
import abortWithFast
import ask
import pushReader
import reset
import takeSubCont
import takeSubContOnce
import takeSubContWithFinal
import kotlin.jvm.JvmInline

public interface Handler<E> {
  public fun prompt(): HandlerPrompt<E>
}

public interface StatefulHandler<E, S> : Handler<E> {
  public val reader: Reader<S>
}

public suspend fun <E, S> StatefulHandler<E, S>.get(): S = reader.ask()

public suspend inline fun <A, E> Handler<E>.use(crossinline body: suspend (Cont<A, E>) -> E): A =
  prompt().prompt.takeSubCont { sk ->
    body(Cont(sk))
  }

public suspend inline fun <A, E> Handler<E>.useOnce(crossinline body: suspend (Cont<A, E>) -> E): A =
  prompt().prompt.takeSubContOnce { sk ->
    body(Cont(sk))
  }

public suspend inline fun <A, E> Handler<E>.useWithFinal(crossinline body: suspend (Pair<Cont<A, E>, Cont<A, E>>) -> E): A =
  prompt().prompt.takeSubContWithFinal { sk ->
    body(Cont(sk.first) to Cont(sk.second))
  }

public fun <E> Handler<E>.discard(body: suspend () -> E): Nothing = prompt().prompt.abortS0(body)

public fun <E> Handler<E>.discardWith(value: Result<E>): Nothing = prompt().prompt.abortWith0(value)

public suspend fun <E> Handler<E>.discardWithFast(value: Result<E>): Nothing = prompt().prompt.abortWithFast(deleteDelimiter = true, value)

public suspend fun <E> handle(body: suspend HandlerPrompt<E>.() -> E): E = with(HandlerPrompt<E>()) {
  rehandle { body() }
}

// TODO maybe we should remove this? Effekt gets by without it (but their lambdas are restricted)
public suspend fun <E> Handler<E>.rehandle(body: suspend () -> E): E = prompt().prompt.reset(body)

public suspend fun <E, S> handleStateful(
  value: S, fork: S.() -> S, body: suspend StatefulPrompt<E, S>.() -> E
): E = with(StatefulPrompt<E, S>()) {
  rehandleStateful(value, fork) { body() }
}

public suspend fun <E, S> StatefulHandler<E, S>.rehandleStateful(
  value: S, fork: S.() -> S,
  body: suspend () -> E
): E = reader.pushReader(value, fork) {
  prompt().prompt.reset(body)
}

@JvmInline
public value class HandlerPrompt<E> private constructor(@PublishedApi internal val prompt: Prompt<E>) : Handler<E> {
  public constructor() : this(Prompt())

  override fun prompt(): HandlerPrompt<E> = this
}

public class StatefulPrompt<E, S>(
  private val prompt: HandlerPrompt<E> = HandlerPrompt(), override val reader: Reader<S> = Reader()
) : StatefulHandler<E, S>, Handler<E> by prompt

@JvmInline
public value class Cont<in T, out R> @PublishedApi internal constructor(internal val subCont: SubCont<T, R>) {
  public suspend fun resumeWith(value: Result<T>, shouldClear: Boolean = false): R =
    subCont.pushSubContWith(value, isDelimiting = true, shouldClear)

  public suspend operator fun invoke(value: T, shouldClear: Boolean = false): R =
    resumeWith(Result.success(value), shouldClear)

  public suspend fun resumeWithException(exception: Throwable, shouldClear: Boolean = false): R =
    resumeWith(Result.failure(exception), shouldClear)

  public fun copy(): Cont<T, R> = Cont(subCont.copy())
  public fun clear() { subCont.clear() }
}