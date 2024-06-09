package effekt

import get
import newReset
import pushState
import set
import shift0

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

public suspend fun <R> region(body: suspend StateScope.() -> R): R = newReset {
  body(StateScopeImpl(this))
}

private class StateScopeImpl<R>(val prompt: Prompt<R>) : StateScope {
  override suspend fun <T> field(init: T): StateScope.Field<T> = FieldImpl<T>().apply {
    prompt.shift0 {
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