import arrow.core.None
import arrow.core.identity
import arrow.core.raise.OptionRaise
import arrow.core.raise.Raise
import arrow.core.raise.RaiseDSL
import arrow.core.raise.fold
import kotlin.jvm.JvmInline

private object EmptyValue

@JvmInline
public value class Maybe<@Suppress("unused") out T> internal constructor(@PublishedApi internal val underlying: Any?) {
  public val isEmpty: Boolean get() = rawValue == EmptyValue
  public val isNotEmpty: Boolean get() = !isEmpty
  public val rawValue: Any? get() = underlying

  @Suppress("UNCHECKED_CAST")
  public inline fun <R> fold(ifEmpty: () -> R, ifNotEmpty: (T) -> R): R =
    if (isEmpty) ifEmpty() else ifNotEmpty(rawValue as T)

  context(Raise<Unit>)
  public fun bind(): T = fold({ raise(Unit) }, ::identity)

  public inline fun onJust(action: (T) -> Unit) { fold({}, action) }
}

public fun <T> just(value: T): Maybe<T> = Maybe(value)
public fun <T> nothing(): Maybe<T> = Maybe(EmptyValue)
public fun <T> rawMaybe(value: Any?): Maybe<T> = Maybe(value)

context(Raise<Unit>)
public inline operator fun <T> Maybe<T>.provideDelegate(
  thisRef: Any?, property: Any?
): Maybe<T> = this.also { bind() }

public inline operator fun <T> Maybe<T>.getValue(
  thisRef: Any?, property: Any?
): T = fold({ throw IllegalStateException("Value is empty") }, ::identity)

public inline fun <T> maybe(block: Raise<Unit>.() -> T): Maybe<T> =
  fold(block, { nothing() }, ::just)