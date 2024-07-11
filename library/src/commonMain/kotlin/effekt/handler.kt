package effekt

import Prompt
import abortS0
import ask
import context
import pushPrompt
import reset
import shift0
import takeSubCont
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmInline

public interface Handler<E> {
  public fun prompt(): HandlerPrompt<E>
  public suspend fun <T> Reader<T>.get(): T = ask()
  public suspend fun <T> Reader<T>.set(value: T): Unit = useWithContext { k, _ ->
    k(Unit, context(value))
  }
}

public interface StatefulHandler<E, S> : Handler<E>, Reader<S>

public suspend fun <A, E, S> StatefulHandler<E, S>.useStateful(body: suspend (suspend (A, S) -> E, S) -> E): A =
  useWithContext { k, ctx ->
    body({ a, s -> k(a, context(s)) }, ctx[this]!!.value)
  }

public suspend fun <A, E> Handler<E>.use(body: suspend (Cont<A, E>) -> E): A = prompt().prompt.shift0(body)
public fun <E> Handler<E>.discard(body: suspend () -> E): Nothing = prompt().prompt.abortS0(body)
public suspend fun <A, E> Handler<E>.useWithContext(body: suspend (suspend (A, CoroutineContext) -> E, CoroutineContext) -> E): A =
  prompt().prompt.takeSubCont { sk ->
    body({ a, ctx -> sk.pushSubContWith(Result.success(a), isDelimiting = true, extraContext = ctx) }, sk.extraContext)
  }

public suspend fun <E, H> handle(
  handler: ((() -> HandlerPrompt<E>) -> H), body: suspend H.() -> E
): E = handle { handler { this }.body() }

public suspend fun <E> handle(body: suspend HandlerPrompt<E>.() -> E): E = with(HandlerPrompt<E>()) {
  handle { body() }
}

public suspend fun <E> Handler<E>.handle(body: suspend () -> E): E = prompt().prompt.reset(body)

public suspend fun <E, H> handleWithContext(
  handler: ((() -> HandlerPrompt<E>) -> H), extraContext: CoroutineContext, body: suspend H.() -> E
): E = handleWithContext(extraContext) { handler { this }.body() }

public suspend fun <E> handleWithContext(extraContext: CoroutineContext, body: suspend HandlerPrompt<E>.() -> E): E =
  with(HandlerPrompt<E>()) {
    handleWithContext(extraContext) { body() }
  }

public suspend fun <E> Handler<E>.handleWithContext(
  extraContext: CoroutineContext, body: suspend () -> E
): E = prompt().prompt.pushPrompt(extraContext, body = body)

public suspend fun <E, H : StatefulHandler<E, S>, S> handleStateful(
  handler: ((() -> HandlerPrompt<E>) -> H), value: S, body: suspend H.() -> E
): E {
  val p = HandlerPrompt<E>()
  val h = handler { p }
  return h.handleStateful(value) { h.body() }
}

public suspend fun <E, S> StatefulHandler<E, S>.handleStateful(
  value: S, body: suspend () -> E
): E = handleWithContext(context(value), body)

@JvmInline
public value class HandlerPrompt<E> private constructor(internal val prompt: Prompt<E>) : Handler<E> {
  public constructor() : this(Prompt())

  override fun prompt(): HandlerPrompt<E> = this
}