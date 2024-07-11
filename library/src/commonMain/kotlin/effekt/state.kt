package effekt

import pushState
import get
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

private class StateScopeImpl<R>(prompt: HandlerPrompt<R>) : StateScope, Handler<R> by prompt {
  override suspend fun <T> field(init: T): StateScope.Field<T> = FieldImpl<T>().apply {
    use {
      pushState(init) {
        it(Unit)
      }
    }
  }

  private class FieldImpl<T> : StateScope.Field<T>, State<T> {
    override suspend fun get(): T = (this as State<T>).get()
    override suspend fun set(value: T) = (this as State<T>).set(value)
  }
}