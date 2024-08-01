package effekt

import StatePrompt
import StateSubCont
import abortS
import effekt.StatefulPrompt
import getState
import pushPrompt
import reset
import takeSubCont
import kotlin.jvm.JvmInline

public typealias Handler<E> = StatefulHandler<E, UnitState>

public interface StatefulHandler<E, S : Stateful<S>> {
  public fun prompt(): StatefulPrompt<E, S>
}

public suspend fun <E, S: Stateful<S>> StatefulHandler<E, S>.get(): S = prompt().prompt.getState()

public suspend fun <A, E, S : Stateful<S>> StatefulHandler<E, S>.use(body: suspend (StateCont<A, E, S>) -> E): A =
  prompt().prompt.takeSubCont { sk ->
    body(StateCont(sk))
  }

public fun <E, S : Stateful<S>> StatefulHandler<E, S>.discard(body: suspend (S) -> E): Nothing =
  prompt().prompt.abortS(deleteDelimiter = true, body)

public suspend fun <E, H> handle(
  handler: ((() -> HandlerPrompt<E>) -> H), body: suspend H.() -> E
): E = handle { handler { this }.body() }

public suspend fun <E> handle(body: suspend HandlerPrompt<E>.() -> E): E = with(HandlerPrompt<E>()) {
  handle { body() }
}

public suspend fun <E> Handler<E>.handle(body: suspend () -> E): E = prompt().prompt.reset(body)

public suspend fun <E, H : StatefulHandler<E, S>, S : Stateful<S>> handleStateful(
  handler: ((() -> StatefulPrompt<E, S>) -> H), value: S, body: suspend H.() -> E
): E {
  val p = StatefulPrompt<E, S>()
  val h = handler { p }
  return h.handleStateful(value) { h.body() }
}

public suspend fun <E, S : Stateful<S>> handleStateful(
  value: S, body: suspend StatefulPrompt<E, S>.() -> E
): E = with(StatefulPrompt<E, S>()) {
  handleStateful(value) { body() }
}

public suspend fun <E, S : Stateful<S>> StatefulHandler<E, S>.handleStateful(
  value: S, body: suspend () -> E
): E = prompt().prompt.pushPrompt(value, body)

@JvmInline
public value class StatefulPrompt<E, S : Stateful<S>> private constructor(internal val prompt: StatePrompt<E, S>) :
  StatefulHandler<E, S> {
  public constructor() : this(StatePrompt())

  override fun prompt(): StatefulPrompt<E, S> = this
}

public typealias HandlerPrompt<E> = StatefulPrompt<E, UnitState>

@JvmInline
public value class StateCont<in T, out R, S : Stateful<S>> internal constructor(internal val subCont: StateSubCont<T, R, S>) {
  public val state: S get() = subCont.state
  public suspend fun resumeWith(value: Result<T>, state: S = this.state, isFinal: Boolean = false): R =
    subCont.pushDelimSubContWith(value, state, isFinal)

  public suspend operator fun invoke(value: T, state: S = this.state, isFinal: Boolean = false): R =
    resumeWith(Result.success(value), state, isFinal)

  public suspend fun resumeWithException(exception: Throwable, state: S = this.state, isFinal: Boolean = false): R =
    resumeWith(Result.failure(exception), state, isFinal)
}
public typealias Cont<T, R> = StateCont<T, R, UnitState>