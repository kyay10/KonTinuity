import arrow.core.identity
import kotlin.jvm.JvmInline

context(A) internal fun <A> given(): A = this@A

// This is not allowed to throw at all, so one must post-process the result with getOrThrow
public inline fun <T> Iterable<T>.forEachSafe(block: (T) -> Unit): Result<Unit> = with(iterator()) {
  whileSafe(this::hasNext) {
    block(next())
  }
}

public inline fun whileSafe(condition: () -> Boolean, block: () -> Unit): Result<Unit> {
  while (condition()) try {
    block()
  } catch (e: Throwable) {
    return Result.failure(e)
  }
  return Result.success(Unit)
}

private object FalseValue
private class Failure(val error: Throwable)

@JvmInline
public value class IfResult<out T> internal constructor(private val value: Any?) {
  public val rawValue: Any? get() = value
  public val isFalse: Boolean get() = this == falseResult<T>()
  public val failure: Throwable? get() = (value as? Failure)?.error

  @Suppress("UNCHECKED_CAST")
  public inline fun <R> fold(
    ifFalse: () -> R, ifFailure: (Throwable) -> R, ifSuccess: (T) -> R
  ): R = when {
    isFalse -> ifFalse()
    else -> failure?.let(ifFailure) ?: ifSuccess(rawValue as T)
  }
  public fun getOrThrow() {
    failure?.let { throw it }
  }
}

public inline fun <T> IfResult<T>.resultOrElse(onFalse: () -> Result<T>): Result<T> =
  fold(onFalse, Result.Companion::failure, Result.Companion::success)

public fun <T> falseResult(): IfResult<T> = IfResult(FalseValue)
public fun <T> trueResult(result: Result<T>): IfResult<T> =
  IfResult(result.fold(::identity, ::Failure))

public inline fun <T> ifSafe(condition: Boolean, block: () -> T): IfResult<T> =
  if (condition) trueResult(runCatching(block))
  else falseResult()

public inline infix fun <T1 : T2, T2> IfResult<T1>.elseSafe(block: () -> T2): Result<T2> =
  resultOrElse { runCatching(block) }

public inline fun <T1 : T2, T2> IfResult<T1>.elseIfSafe(
  condition: Boolean, block: () -> T2
): IfResult<T2> = if (isFalse) {
  ifSafe(condition, block)
} else this
