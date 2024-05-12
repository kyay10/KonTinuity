import androidx.compose.runtime.Composable
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.resourceScope

public typealias Reset<R> = _Reset<H<R>>

@ResetDsl
public suspend fun <R> ResourceScope.lazyReset(
  body: @Composable Reset<R>.() -> R
): R = lazyGreset({ hrStop(it) }, body)

@ResetDsl
public suspend fun <R> reset(
  body: @Composable Reset<R>.() -> R
): R = resourceScope { lazyReset(body) }

@Composable
@ResetDsl
public fun <R> nestedReset(
  tag: Reset<R>? = null, body: @Composable Reset<R>.() -> R
): R = nestedGreset({ hrStop(it) }, tag, body)

@Composable
public inline fun <T, R> Reset<R>.shift(crossinline block: @Composable (Cont<T, R>) -> R): T =
  gshift({ hsStop(it) }) { nestedReset(this) { block(it) } } // TODO weird that we have to wrap here instead at call site

@Composable
public inline fun <T, R> Reset<R>.control(crossinline block: @Composable (Cont<T, R>) -> R): T =
  gshift({ hsProp(it) }) { nestedReset(this) { block(it) } } // TODO same as above

@ResetDsl
public suspend fun <R> ResourceScope.lazyReset0(
  body: @Composable Reset<R>.() -> R
): R = lazyGreset({ hrProp(it) }, body)

@ResetDsl
public suspend fun <R> reset0(
  body: @Composable Reset<R>.() -> R
): R = resourceScope { lazyReset0(body) }

@Composable
@ResetDsl
public fun <R> nestedReset0(
  tag: Reset<R>? = null, body: @Composable Reset<R>.() -> R
): R = nestedGreset({ hrProp(it) }, tag, body)

@Composable
public inline fun <T, R> Reset<R>.shift0(crossinline block: @Composable (Cont<T, R>) -> R): T =
  gshift({ hsStop(it) }) { block(it) }

@Composable
public inline fun <T, R> Reset<R>.control0(crossinline block: @Composable (Cont<T, R>) -> R): T =
  gshift({ hsProp(it) }) { block(it) }
