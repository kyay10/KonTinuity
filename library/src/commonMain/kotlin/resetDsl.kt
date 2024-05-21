import androidx.compose.runtime.Composable
import arrow.AutoCloseScope
import arrow.autoCloseScope

public typealias Cont<T, R> = @Composable (T) -> R
public typealias ContS<T, R> = suspend (T) -> R

@ResetDsl
public suspend fun <R> AutoCloseScope.lazyReset(
  prompt: Prompt<R> = Prompt(), body: @Composable Prompt<R>.() -> R
): R = pushPrompt(prompt, body)

@Composable
@ResetDsl
public fun <R> Prompt<R>.reset(body: @Composable Prompt<R>.() -> R): R = pushPrompt(body)

public suspend fun <R> reset(prompt: Prompt<R> = Prompt(), body: @Composable Prompt<R>.() -> R): R =
  autoCloseScope { lazyReset(prompt, body) }

@Composable
public inline fun <T, R> Prompt<R>.shift(crossinline block: @Composable (Cont<T, R>) -> R): T =
  takeSubCont(deleteDelimiter = false) { sk -> block { sk.pushDelimSubCont { it } } }

@Composable
public inline fun <T, R> Prompt<R>.shiftS(crossinline block: suspend (ContS<T, R>) -> R): T =
  takeSubContS(deleteDelimiter = false) { sk -> block { sk.pushDelimSubContS { it } } }

@Composable
public inline fun <T, R> Prompt<R>.control(crossinline block: @Composable (Cont<T, R>) -> R): T =
  takeSubCont(deleteDelimiter = false) { sk -> block { sk.pushSubCont { it } } }

@ResetDsl
public suspend fun <R> AutoCloseScope.lazyReset0(
  prompt: Prompt<R> = Prompt(), body: @Composable Prompt<R>.() -> R
): R = lazyReset(prompt, body)

@ResetDsl
public suspend fun <R> reset0(
  prompt: Prompt<R> = Prompt(), body: @Composable Prompt<R>.() -> R
): R = reset(prompt, body)

@Composable
@ResetDsl
public fun <R> Prompt<R>.reset0(body: @Composable Prompt<R>.() -> R): R = reset(body)

@Composable
public inline fun <T, R> Prompt<R>.shift0(crossinline block: @Composable (Cont<T, R>) -> R): T =
  takeSubCont { sk -> block { sk.pushDelimSubCont { it } } }

@Composable
public inline fun <T, R> Prompt<R>.control0(crossinline block: @Composable (Cont<T, R>) -> R): T =
  takeSubCont { sk -> block { sk.pushSubCont { it } } }