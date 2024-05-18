import Reset.Hole
import androidx.compose.runtime.Composable
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.resourceScope
import kotlin.coroutines.suspendCoroutine
import Suspender.Companion.suspender

@ResetDsl
public suspend fun <R> ResourceScope.lazyReset(
  tag: Reset<R> = Reset(), body: @Composable Reset<R>.() -> R
): R = with(suspender()) {
  suspendCoroutine { k ->
    tag.pushHole(Hole(k, true))
    startSuspendingComposition(tag::resumeWith) { body(tag) }
  }
}

@Composable
@ResetDsl
public fun <R> Reset<R>.reset(body: @Composable Reset<R>.() -> R): R = suspendComposition { k ->
  pushHole(Hole(k, true))
  startSuspendingComposition(::resumeWith) { body() }
}

public suspend fun <R> reset(tag: Reset<R> = Reset(), body: @Composable Reset<R>.() -> R): R =
  resourceScope { lazyReset(tag, body) }

@Composable
public inline fun <T, R> Reset<R>.shift(crossinline block: @Composable (Cont<T, R>) -> R): T =
  `*F*`(true, true, block)

@Composable
public inline fun <T, R> Reset<R>.control(crossinline block: @Composable (Cont<T, R>) -> R): T =
  `*F*`(true, false, block)

public fun <R> Reset<R>.abort(value: R): Nothing {
  buildList { unwindTillMarked(true) }
  raise(value)
}

@ResetDsl
public suspend fun <R> ResourceScope.lazyReset0(
  tag: Reset<R> = Reset(), body: @Composable Reset<R>.() -> R
): R = lazyReset(tag, body)

@ResetDsl
public suspend fun <R> reset0(
  tag: Reset<R> = Reset(), body: @Composable Reset<R>.() -> R
): R = reset(tag, body)

@Composable
@ResetDsl
public fun <R> Reset<R>.reset0(body: @Composable Reset<R>.() -> R
): R = reset(body)

@Composable
public inline fun <T, R> Reset<R>.shift0(crossinline block: @Composable (Cont<T, R>) -> R): T =
  `*F*`(false, true, block)

@Composable
public inline fun <T, R> Reset<R>.control0(crossinline block: @Composable (Cont<T, R>) -> R): T =
  `*F*`(false, false, block)

public fun <R> Reset<R>.abort0(value: R): Nothing {
  buildList { unwindTillMarked(false) }
  raise(value)
}