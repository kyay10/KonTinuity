package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.*
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf

public interface StateScope {
  public suspend fun <T> field(init: T): Field<T>
  public interface Field<T> {
    public suspend fun get(): T
    public suspend fun set(value: T)
  }
}

context(s: StateScope)
public suspend inline fun <T> field(init: T): StateScope.Field<T> = s.field(init)

public suspend inline fun <T> StateScope.Field<T>.update(f: (T) -> T) {
  set(f(get()))
}

public suspend fun <R> region(body: suspend StateScope.() -> R): R =
  runReader(MutableTypedMap(), MutableTypedMap::copy) {
    body(MutableStateScope(this))
  }

public suspend fun <R> persistentRegion(body: suspend StateScope.() -> R): R = runState(PersistentTypedMap()) {
  body(PersistentStateScope(this))
}

private class MutableTypedMap private constructor(private val map: MutableMap<Key<*>, Any?>) {
  constructor() : this(mutableMapOf())

  interface Key<T>

  @Suppress("UNCHECKED_CAST")
  operator fun <T> get(key: Key<T>): T = map.getValue(key) as T
  operator fun <T> set(key: Key<T>, value: T) {
    map[key] = value
  }

  fun copy(): MutableTypedMap = MutableTypedMap(map.toMutableMap())
}

private class PersistentTypedMap private constructor(private val map: PersistentMap<Key<*>, Any?>) {
  constructor() : this(persistentMapOf())

  interface Key<T>

  @Suppress("UNCHECKED_CAST")
  operator fun <T> get(key: Key<T>): T = map.getValue(key) as T
  fun <T> put(key: Key<T>, value: T) = PersistentTypedMap(map.put(key, value))
}

private class MutableStateScope(private val reader: Reader<MutableTypedMap>) : StateScope {
  override suspend fun <T> field(init: T): StateScope.Field<T> = FieldImpl<T>().also {
    reader.ask()[it] = init
  }

  private inner class FieldImpl<T> : StateScope.Field<T>, MutableTypedMap.Key<T> {
    override suspend fun get(): T = reader.ask()[this]
    override suspend fun set(value: T) {
      reader.ask()[this] = value
    }
  }
}

private class PersistentStateScope(private val state: State<PersistentTypedMap>) : StateScope {
  override suspend fun <T> field(init: T): StateScope.Field<T> = FieldImpl<T>().also { field ->
    state.modify { it.put(field, init) }
  }

  private inner class FieldImpl<T> : StateScope.Field<T>, PersistentTypedMap.Key<T> {
    override suspend fun get(): T = state.get()[this]
    override suspend fun set(value: T) = state.modify { it.put(this, value) }
  }
}

public interface Stateful<S : Stateful<S>> {
  public fun fork(): S
}

public suspend inline fun <E, S : Stateful<S>> handleStateful(
  value: S, crossinline body: suspend StatefulPrompt<E, S>.() -> E
): E = handleStateful(value, Stateful<S>::fork, body)

public suspend inline fun <E, S : Stateful<S>> StatefulHandler<E, S>.rehandleStateful(
  value: S, noinline body: suspend () -> E
): E = rehandleStateful(value, Stateful<S>::fork, body)