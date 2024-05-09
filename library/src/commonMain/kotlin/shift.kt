import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

public typealias Cont<T, R> = (T, Continuation<R>, Reset<*>?) -> Unit

public suspend operator fun <T, R> Cont<T, R>.invoke(value: T): R = suspendCoroutine { cont ->
  this(value, cont, null)
}

@Composable
public fun <T, R> Cont<T, R>.invokeC(value: T): R = suspendComposition { cont ->
  this(value, cont, currentReset)
}

public class ControlOrShiftCont<T, R> @PublishedApi internal constructor(
  private val continuation: Continuation<T>, private val reset: Reset<*>, private val target: Reset<R>
) {
  public fun shift(value: T, cont: Continuation<R>, parent: Reset<*>?) {
    continuation.resume(value)
    reset.awaitResult(target, true, continuation, cont, parent)
  }

  public fun control(value: T, cont: Continuation<R>, parent: Reset<*>?) {
    continuation.resume(value)
    reset.awaitResult(target, false, continuation, cont, parent)
  }
}

// TODO investigate why `@DontMemoize` is needed for capturing lambdas with enableNonSkippingGroupOptimization

@PublishedApi
@Composable
internal inline fun <T, R> Reset<R>.gimmeAllControl(crossinline block: suspend (Continuation<T>) -> R): T =
  suspendComposition { it.configure @DontMemoize { block(it) } }

@PublishedApi
@Composable
internal inline fun <T, R> Reset<R>.gimmeAllControlC(crossinline block: @Composable (Continuation<T>) -> R): T =
  suspendComposition { it.configureC(currentSuspender) @DontMemoize { block(it) } }

@Composable
@ResetDsl
public inline fun <T, R> Reset<R>.shift(crossinline block: suspend (Cont<T, R>) -> R): T =
  controlOrShift { block(it::shift) }

@Composable
@ResetDsl
public fun <T, R> Reset<R>.shiftC(block: @Composable (Cont<T, R>) -> R): T =
  controlOrShiftC { block(it::shift) }

@Suppress("UNCHECKED_CAST")
@Composable
@ResetDsl
public fun <T, R1, R2> Reset<R1>.shiftAndChangeType(
  block: @Composable (Cont<T, R2>) -> R1, rest: @Composable Reset<R2>.(T) -> R2
): Nothing {
  val originalReset: Reset<R1> = this
  (this as Reset<R2>).rest(originalReset.shiftC { k -> block(k as Cont<T, R2>) })
  throw Suspended(this)
}

@Suppress("UNCHECKED_CAST")
@Composable
@ResetDsl
public fun <T, R1> Reset<R1>.controlAndChangeType(
  block: @Composable (Cont<T, Nothing>) -> R1, rest: @Composable (T) -> Nothing
): Nothing = rest(controlC { k -> block(k as Cont<T, Nothing>) })

@Composable
@ResetDsl
public inline fun <T, R> Reset<R>.control(crossinline block: suspend (Cont<T, R>) -> R): T =
  controlOrShift { block(it::control) }

@Composable
@ResetDsl
public inline fun <T, R> Reset<R>.controlC(crossinline block: @Composable (Cont<T, R>) -> R): T =
  controlOrShiftC { block(it::control) }

@Composable
@ResetDsl
public inline fun <T, R> Reset<R>.controlOrShift(crossinline block: suspend (ControlOrShiftCont<T, R>) -> R): T {
  val reset = currentReset
  return gimmeAllControl {
    try {
      block(ControlOrShiftCont(it, reset, this@controlOrShift))
    } catch (e: Suspended) {
      if (e.token === it) {
        suspendCoroutine<Nothing> { }
      } else throw e
    }
  }
}

@Composable
@ResetDsl
public fun <T, R> Reset<R>.controlOrShiftC(block: @Composable (ControlOrShiftCont<T, R>) -> R): T {
  val reset = currentReset
  return gimmeAllControlC {
    val result = runCatching { block(ControlOrShiftCont(it, reset, this@controlOrShiftC)) }
    if ((result.exceptionOrNull() as? Suspended)?.token === it) {
      suspendComposition<Nothing> { }
    } else result.getOrThrow()
  }
}

@Composable
@ResetDsl
public fun <R> Reset<R>.shiftWith(value: R): Nothing = shift { value }

@OptIn(InternalCoroutinesApi::class)
@Composable
@ResetDsl
public fun <T> await(block: suspend () -> T): T = suspendComposition { cont ->
  CoroutineStart.UNDISPATCHED({ block() }, Unit, cont)
}