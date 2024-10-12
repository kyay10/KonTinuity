package effekt

import State
import get
import pushState
import set

public interface StateScope {
  public suspend fun <T> field(init: T): Field<T>
  public interface Field<T> {
    public suspend fun get(): T
    public suspend fun set(value: T)
  }
}

public suspend inline fun <T> StateScope.Field<T>.update(f: (T) -> T) {
  set(f(get()))
}

public suspend fun <R> region(body: suspend StateScope.() -> R): R = handle {
  body(StateScopeImpl(this))
}

// TODO use map implementation
private class StateScopeImpl<R>(prompt: HandlerPrompt<R>) : StateScope, Handler<R> by prompt {
  override suspend fun <T> field(init: T): StateScope.Field<T> = FieldImpl<T>(State()).apply {
    use {
      state.pushState(init) {
        it(Unit)
      }
    }
  }

  private data class FieldImpl<T>(val state: State<T>) : StateScope.Field<T> {
    override suspend fun get(): T = state.get()
    override suspend fun set(value: T) = state.set(value)
  }
}

public interface Stateful<S : Stateful<S>> {
  public fun fork(): S
}

public suspend fun <E, H : StatefulHandler<E, S>, S : Stateful<S>> handleStateful(
  handler: ((() -> StatefulPrompt<E, S>) -> H), value: S,
  body: suspend H.() -> E
): E = handleStateful(handler, value, Stateful<S>::fork, body)

public suspend fun <E, S : Stateful<S>> handleStateful(
  value: S, body: suspend StatefulPrompt<E, S>.() -> E
): E = handleStateful(value, Stateful<S>::fork, body)

public suspend fun <E, S : Stateful<S>> StatefulHandler<E, S>.rehandleStateful(
  value: S, body: suspend () -> E
): E = rehandleStateful(value, Stateful<S>::fork, body)