import arrow.core.None
import arrow.core.identity
import arrow.core.raise.OptionRaise
import kotlin.jvm.JvmInline

private object EmptyValue

@JvmInline
public value class Maybe<@Suppress("unused") out T> internal constructor(@PublishedApi internal val underlying: Any?) {
  public val isEmpty: Boolean get() = underlying == EmptyValue
  public val isNotEmpty: Boolean get() = !isEmpty

  @Suppress("UNCHECKED_CAST")
  public inline fun <R> fold(ifEmpty: () -> R, ifNotEmpty: (T) -> R): R =
    if (isEmpty) ifEmpty() else ifNotEmpty(underlying as T)

  context(OptionRaise)
  public fun bind(): T = fold({ raise(None) }, ::identity)
}

public fun <T> just(value: T): Maybe<T> = Maybe(value)
public fun <T> nothing(): Maybe<T> = Maybe(EmptyValue)

context(OptionRaise)
public inline operator fun <T> Maybe<T>.provideDelegate(
  thisRef: Any?, property: Any?
): Maybe<T> = this.also { bind() }

public inline operator fun <T> Maybe<T>.getValue(
  thisRef: Any?, property: Any?
): T = fold({ throw IllegalStateException("Value is empty") }, ::identity)