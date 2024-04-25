import arrow.core.identity
import arrow.core.raise.Raise
import arrow.core.raise.fold
import kotlin.jvm.JvmInline

private object EmptyValue

@JvmInline
@PublishedApi
internal value class Maybe<@Suppress("unused") out T> internal constructor(private val underlying: Any?) {
  val isNothing: Boolean get() = this == nothing<T>()
  val rawValue: Any? get() = underlying

  @Suppress("UNCHECKED_CAST")
  inline fun <R> fold(ifEmpty: () -> R, ifNotEmpty: (T) -> R): R =
    if (isNothing) ifEmpty() else ifNotEmpty(rawValue as T)

  context(Raise<Unit>)
  fun bind() = getOrElse { raise(Unit) }
}

@PublishedApi
internal fun <T> just(value: T): Maybe<T> = Maybe(value)
@PublishedApi
internal fun <T> nothing(): Maybe<T> = Maybe(EmptyValue)
internal fun <T> rawMaybe(value: Any?): Maybe<T> = Maybe(value)

@PublishedApi
internal inline fun <T> Maybe<T>.getOrElse(onFailure: () -> T): T = fold(onFailure, ::identity)

internal inline fun <T> maybe(block: Raise<Unit>.() -> T): Maybe<T> =
  fold(block, { nothing() }, ::just)