package io.github.kyay10.kontinuity

import arrow.core.*
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.jvm.JvmName
import kotlin.reflect.KProperty

public interface Region {
  public fun <T> field(): OptionalField<T>
  public interface NonEmptyField
  public sealed interface Field<T> : NonEmptyField, OptionalField<T> {
    public var value: T
    override fun set(value: T) {
      this.value = value
    }
  }

  public sealed interface OptionalField<T> {
    public fun getOrNone(): Option<T>
    public fun set(value: T)
  }
}

context(s: Region)
public fun <T> field(init: T): Region.Field<T> {
  val field = s.field<T>()
  field.value = init
  return field.affirm()
}

context(s: Region)
public fun <T> field(): Region.OptionalField<T> = s.field()

public inline fun <T> Region.Field<T>.update(f: (T) -> T) {
  value = f(value)
}

@Suppress("NOTHING_TO_INLINE")
public inline operator fun <T> Region.Field<T>.getValue(
  thisRef: Any?,
  property: KProperty<*>
): T = value

@Suppress("NOTHING_TO_INLINE")
public inline operator fun <T> Region.Field<T>.setValue(
  thisRef: Any?,
  property: KProperty<*>,
  value: T
) {
  this.value = value
}

@get:Deprecated("", level = DeprecationLevel.HIDDEN)
@get:JvmName("getValueUnsafe")
@set:JvmName("setOptionalValue")
public var <T> Region.OptionalField<T>.value: T
  get() = throw UnsupportedOperationException("Use getOrNone() to access optional field")
  set(value) {
    contract { returns() implies (this@value is Region.NonEmptyField) }
    set(value)
  }

@Suppress("UNCHECKED_CAST")
private fun <F, T> F.affirm(): Region.Field<T> where F : Region.NonEmptyField, F : Region.OptionalField<T> =
  this as Region.Field<T>

public var <F, T> F.value: T where F : Region.NonEmptyField, F : Region.OptionalField<T>
  get() = affirm().value
  set(value) = set(value)

public inline fun <T> Region.OptionalField<T>.getOrPut(defaultValue: () -> T): T {
  contract {
    callsInPlace(defaultValue, InvocationKind.AT_MOST_ONCE)
    returns() implies (this@getOrPut is Region.Field<T>)
  }
  return getOrNone().getOrElse { defaultValue().also { value = it } }
}

public suspend inline fun <R> region(crossinline body: suspend Region.() -> R): R = runMapBuilder {
  body(MapRegion(this))
}

public suspend inline fun <R> listRegion(crossinline body: suspend Region.() -> R): R = runListBuilder {
  body(ListRegion(this))
}

@PublishedApi
internal class MapRegion(private val map: MutableMap<Region.Field<*>, Any?>) : Region {
  override fun <T> field(): Region.OptionalField<T> = FieldImpl()
  private var index = 0

  @Suppress("UNCHECKED_CAST", "EqualsOrHashCode")
  private inner class FieldImpl<T> : Region.Field<T> {
    private val id = index++
    override fun getOrNone(): Option<T> = map.getOrNone(this) as Option<T>

    override var value: T
      get() = map.getValue(this) as T
      set(value) {
        map[this] = value
      }

    override fun hashCode(): Int = id
  }
}

// Very fast, but NOT time-travel safe...
@PublishedApi
internal class ListRegion(private val list: MutableList<Any?>) : Region {
  override fun <T> field(): Region.OptionalField<T> = FieldImpl()

  private data object EmptyValue

  private inner class FieldImpl<T> : Region.Field<T> {
    private val id = list.size

    init {
      list.add(EmptyValue)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getOrNone(): Option<T> = list[id].let { if (it === EmptyValue) None else Some(it as T) }

    @Suppress("UNCHECKED_CAST")
    override var value: T
      get() = list[id] as T
      set(value) {
        list[id] = value
      }
  }
}