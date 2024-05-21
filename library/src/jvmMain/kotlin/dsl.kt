import androidx.compose.runtime.Composable
import arrow.AutoCloseScope
import arrow.core.raise.Raise
import arrow.fx.coroutines.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

context(A) internal fun <A> given(): A = this@A

/** MonadFail-style errors */
private class PromptFail<R>(private val prompt: Prompt<R>, private val failValue: R): Raise<Unit> {
  override fun raise(e: Unit): Nothing = prompt.abort(failValue)
}

public suspend fun <R> reset(
  failValue: R,
  body: @Composable context(Raise<Unit>) Prompt<R>.() -> R
): R = resourceScope { lazyResetWithFail(failValue, body = body) }

public suspend fun <R> AutoCloseScope.lazyResetWithFail(
  failValue: R,
  prompt: Prompt<R> = Prompt(),
  body: @Composable context(Raise<Unit>) Prompt<R>.() -> R
): R {
  val raise = PromptFail(prompt, failValue)
  return lazyReset(prompt) {
    body(raise, prompt)
  }
}

public suspend fun <R> listReset(
  body: @Composable context(Raise<Unit>) Prompt<List<R>>.() -> R
): List<R> = reset(emptyList()) {
  listOf(body(given<Raise<Unit>>(), this))
}

context(Prompt<List<R>>)
@Composable
public fun <T, R> List<T>.bind(): T = shiftS { continuation ->
  flatMap { continuation(it) }
}

@OptIn(DelicateCoroutinesApi::class)
public suspend fun <R> flowReset(
  body: @Composable context(Raise<Unit>) Prompt<Flow<R>>.() -> R
): Flow<R> {
  val (flow, release) = resource {
    lazyResetWithFail(emptyFlow()) {
      flowOf(body(given<Raise<Unit>>(), this))
    }
  }.allocated()
  return flow.onCompletion { release(it?.let(ExitCase::ExitCase) ?: ExitCase.Completed) }
}

context(Prompt<Flow<R>>)
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
public fun <T, R> Flow<T>.bind(): T = shiftS(::flatMapConcat)