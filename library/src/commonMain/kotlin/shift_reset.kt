import androidx.compose.runtime.Composable
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.resourceScope

public typealias Reset<R> = _Reset<H<R>>

@ResetDsl
public suspend fun <R> ResourceScope.lazyReset(
  tag: Reset<R> = Reset(), body: @Composable Reset<R>.() -> R
): R = lazyGreset({ hrStop(it) }, tag, body)

@ResetDsl
public suspend fun <R> reset(
  tag: Reset<R> = Reset(), body: @Composable Reset<R>.() -> R
): R = resourceScope { lazyReset(tag, body) }

@Composable
@ResetDsl
public fun <R> nestedReset(
  tag: Reset<R> = Reset(), body: @Composable Reset<R>.() -> R
): R = nestedGreset({ hrStop(it) }, tag, body)

@Composable
public inline fun <T, R> Reset<R>.shift(crossinline block: @Composable (Cont<T, R>) -> R): T =
  gshift({ hsStop(it) }) { block(it) }

@Composable
public inline fun <T, R> Reset<R>.control(crossinline block: @Composable (Cont<T, R>) -> R): T =
  gshift({ hsProp(it) }) { block(it) }

@ResetDsl
public suspend fun <R> ResourceScope.lazyReset0(
  tag: Reset<R> = Reset(), body: @Composable Reset<R>.() -> R
): R = lazyGreset({ hrProp(it) }, tag, body)

@ResetDsl
public suspend fun <R> reset0(
  tag: Reset<R> = Reset(), body: @Composable Reset<R>.() -> R
): R = resourceScope { lazyReset0(tag, body) }

@Composable
@ResetDsl
public fun <R> nestedReset0(
  tag: Reset<R> = Reset(), body: @Composable Reset<R>.() -> R
): R = nestedGreset({ hrProp(it) }, tag, body)

@Composable
public inline fun <T, R> Reset<R>.shift0(crossinline block: @Composable (Cont<T, R>) -> R): T =
  gshift({ hsStop(it) }) { block(it) }

@Composable
public inline fun <T, R> Reset<R>.control0(crossinline block: @Composable (Cont<T, R>) -> R): T =
  gshift({ hsProp(it) }) { block(it) }
