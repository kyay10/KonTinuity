package effekt

import Prompt
import abortS0
import pushPrompt
import reset
import shift0
import takeSubCont
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmInline

public interface Handler<E> {
  public fun prompt(): HandlerPrompt<E>
}

public suspend fun <A, E> Handler<E>.use(body: suspend (Cont<A, E>) -> E): A = prompt().prompt.shift0(body)
public fun <A, E> Handler<E>.discard(body: suspend () -> E): A = prompt().prompt.abortS0(body)
public suspend fun <A, E> Handler<E>.useStateful(body: suspend (suspend (A, CoroutineContext) -> E) -> E): A =
  prompt().prompt.takeSubCont { sk ->
    body { a, ctx -> sk.pushSubContWith(Result.success(a), isDelimiting = true, extraContext = ctx) }
  }

public suspend fun <E, H> handle(
  handler: ((() -> HandlerPrompt<E>) -> H), body: suspend H.() -> E
): E = handle { handler { this }.body() }

public suspend fun <E> handle(body: suspend HandlerPrompt<E>.() -> E): E = HandlerPrompt<E>().handle(body)

public suspend fun <E> HandlerPrompt<E>.handle(body: suspend HandlerPrompt<E>.() -> E): E = prompt.reset { body() }

public suspend fun <E, H> handleStateful(
  handler: ((() -> HandlerPrompt<E>) -> H), extraContext: CoroutineContext, body: suspend H.() -> E
): E = handleStateful(extraContext) { handler { this }.body() }

public suspend fun <E> handleStateful(extraContext: CoroutineContext, body: suspend HandlerPrompt<E>.() -> E): E =
  HandlerPrompt<E>().handleStateful(extraContext, body)

public suspend fun <E> HandlerPrompt<E>.handleStateful(
  extraContext: CoroutineContext, body: suspend HandlerPrompt<E>.() -> E
): E = prompt.pushPrompt(extraContext) { body() }

@JvmInline
public value class HandlerPrompt<E> internal constructor(internal val prompt: Prompt<E>) : Handler<E> {
  public constructor() : this(Prompt())

  override fun prompt(): HandlerPrompt<E> = this
}