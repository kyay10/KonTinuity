package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.getOrElse
import io.github.kyay10.kontinuity.*
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.contracts.contract
import kotlin.reflect.KProperty

public interface StateScope {
  public fun <T> field(init: T): Field<T>
  public fun <T> field(): OptionalField<T>
  public interface Field<T> {
    public var value: T
  }

  public interface OptionalField<T> {
    public fun getOrNone(): Option<T>
    public fun set(value: T)
  }
}

context(s: StateScope)
public fun <T> field(init: T): StateScope.Field<T> = s.field(init)

context(s: StateScope)
public fun <T> field(): StateScope.OptionalField<T> = s.field()

public inline fun <T> StateScope.Field<T>.update(f: (T) -> T) {
  value = f(value)
}

@Suppress("NOTHING_TO_INLINE")
public inline operator fun <T> StateScope.Field<T>.getValue(
  thisRef: Any?,
  property: KProperty<*>
): T = value

@Suppress("NOTHING_TO_INLINE")
public inline operator fun <T> StateScope.Field<T>.setValue(
  thisRef: Any?,
  property: KProperty<*>,
  value: T
) {
  this.value = value
}

@get:Deprecated("", level = DeprecationLevel.HIDDEN)
public var <T> StateScope.OptionalField<T>.value: T
  get() = throw UnsupportedOperationException("Use getOrNone() to access optional field")
  set(value) {
    contract { returns() implies (this@value is StateScope.Field<T>) }
    set(value)
  }

public inline fun <T> StateScope.OptionalField<T>.getOrPut(defaultValue: () -> T): T {
  contract {
    callsInPlace(defaultValue, kotlin.contracts.InvocationKind.AT_MOST_ONCE)
    returns() implies (this@getOrPut is StateScope.Field<T>)
  }
  return getOrNone().getOrElse { defaultValue().also { value = it } }
}

public suspend inline fun <R> region(crossinline body: suspend StateScope.() -> R): R =
  runReader(MutableTypedMap(), MutableTypedMap::copy) {
    body(MutableStateScope(this))
  }

public suspend inline fun <R> persistentRegion(crossinline body: suspend StateScope.() -> R): R =
  runState(PersistentTypedMap()) {
    body(PersistentStateScope(this))
  }

public suspend inline fun <R> persistentFastRegion(crossinline body: suspend StateScope.() -> R): R = runReader(
  MutableTypedMap(persistentHashMapOf<MutableTypedMap.Key<*>, Any?>().builder()), { MutableTypedMap((map as PersistentMap.Builder).build().builder()) }) {
  body(MutableStateScope(this))
}

@PublishedApi
internal class MutableTypedMap(val map: MutableMap<Key<*>, Any?>) {
  constructor() : this(mutableMapOf())

  interface Key<T>

  @Suppress("UNCHECKED_CAST")
  operator fun <T> get(key: Key<T>): T = map.getValue(key) as T

  @Suppress("UNCHECKED_CAST")
  fun <T> getOrNone(key: Key<T>): Option<T> = map.getOrNone(key) as Option<T>

  operator fun <T> set(key: Key<T>, value: T) {
    map[key] = value
  }

  fun copy(): MutableTypedMap = MutableTypedMap(map.toMutableMap())
}

@PublishedApi
internal class PersistentTypedMap private constructor(private val map: PersistentMap<Key<*>, Any?>) {
  constructor() : this(persistentMapOf())

  interface Key<T>

  @Suppress("UNCHECKED_CAST")
  operator fun <T> get(key: Key<T>): T = map.getValue(key) as T

  @Suppress("UNCHECKED_CAST")
  fun <T> getOrNone(key: Key<T>): Option<T> = map.getOrNone(key) as Option<T>
  fun <T> put(key: Key<T>, value: T) = PersistentTypedMap(map.put(key, value))
}

@Suppress("UNCHECKED_CAST")
public fun <K, V> Map<K, V>.getOrNone(key: K): Option<V> {
  val value = get(key)
  if (value == null && !containsKey(key)) {
    return None
  } else {
    @Suppress("UNCHECKED_CAST")
    return Some(value as V)
  }
}

@PublishedApi
internal class MutableStateScope(private val reader: Reader<MutableTypedMap>) : StateScope {
  override fun <T> field(init: T): StateScope.Field<T> = FieldImpl<T>().also {
    reader.value[it] = init
  }

  override fun <T> field(): StateScope.OptionalField<T> = FieldImpl()

  private inner class FieldImpl<T> : StateScope.Field<T>, StateScope.OptionalField<T>, MutableTypedMap.Key<T> {
    override fun getOrNone(): Option<T> = reader.value.getOrNone(this)
    override fun set(value: T) {
      this.value = value
    }

    override var value: T
      get() = reader.value[this]
      set(value) {
        reader.value[this] = value
      }
  }
}

@PublishedApi
internal class PersistentStateScope(private val state: State<PersistentTypedMap>) : StateScope {
  override fun <T> field(init: T): StateScope.Field<T> = FieldImpl<T>().also { field ->
    state.modify { it.put(field, init) }
  }

  override fun <T> field(): StateScope.OptionalField<T> = FieldImpl()

  private inner class FieldImpl<T> : StateScope.Field<T>, StateScope.OptionalField<T>, PersistentTypedMap.Key<T> {
    override fun getOrNone(): Option<T> = state.value.getOrNone(this)

    override fun set(value: T) {
      this.value = value
    }

    override var value: T
      get() = state.value[this]
      set(value) {
        state.modify { it.put(this, value) }
      }
  }
}

public interface Stateful<S : Stateful<S>> {
  public fun fork(): S
}

public suspend inline fun <E, S : Stateful<S>> handleStateful(
  value: S, crossinline body: suspend StatefulPrompt<E, S>.() -> E
): E = handleStateful(value, Stateful<S>::fork, body)