package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.Reader
import io.github.kyay10.kontinuity.ask
import io.github.kyay10.kontinuity.runReader

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

public suspend fun <R> region(body: suspend StateScope.() -> R): R =
  runReader(TypedMutableMap(), TypedMutableMap::copy) {
    body(StateScopeImpl(this))
  }

private class TypedMutableMap private constructor(private val map: MutableMap<Key<*>, Any?>) {
  constructor() : this(mutableMapOf())
  interface Key<T>
  @Suppress("UNCHECKED_CAST")
  operator fun <T> get(key: Key<T>): T = map.getValue(key) as T
  operator fun <T> set(key: Key<T>, value: T) {
    map[key] = value
  }
  fun copy(): TypedMutableMap = TypedMutableMap(map.toMutableMap())
}

private class StateScopeImpl(private val reader: Reader<TypedMutableMap>) : StateScope {
  override suspend fun <T> field(init: T): StateScope.Field<T> = FieldImpl<T>().also {
    reader.ask()[it] = init
  }

  private inner class FieldImpl<T> : StateScope.Field<T>, TypedMutableMap.Key<T> {
    override suspend fun get(): T = reader.ask()[this]
    override suspend fun set(value: T) {
      reader.ask()[this] = value
    }
  }
}

public interface Stateful<S : Stateful<S>> {
  public fun fork(): S
}

public suspend fun <E, S : Stateful<S>> handleStateful(
  value: S, body: suspend StatefulPrompt<E, S>.() -> E
): E = handleStateful(value, Stateful<S>::fork, body)

public suspend fun <E, S : Stateful<S>> StatefulHandler<E, S>.rehandleStateful(
  value: S, body: suspend () -> E
): E = rehandleStateful(value, Stateful<S>::fork, body)