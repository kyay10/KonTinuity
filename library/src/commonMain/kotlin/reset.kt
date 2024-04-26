import androidx.compose.runtime.Composable
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentRecomposeScope
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import arrow.core.raise.Raise
import arrow.core.raise.recover
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.use
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@DslMarker
public annotation class ResetDsl

@ResetDsl
public class Reset<R> internal constructor(
  internal var currentOutput: SendChannel<R>, internal val coroutineScope: CoroutineScope
) {
  internal val clock: GatedFrameClock = GatedFrameClock(coroutineScope)
  internal lateinit var recomposeScope: RecomposeScope
  internal lateinit var raise: Raise<Unit>
  internal val suspensions: Channel<Unit> = Channel(1)

  @PublishedApi
  internal var reachedResumePoint: Boolean = true
}

public suspend fun <R> reset(
  body: @Composable Reset<R>.() -> R
): R = coroutineScope { lazyReset(body).use { it } }

public fun <R> CoroutineScope.lazyReset(
  body: @Composable Reset<R>.() -> R
): Resource<R> {
  val output = Channel<R>(1)
  val job = launch {
    with(Reset(output, this)) {
      launchMolecule(RecompositionMode.ContextClock, {}, clock) {
        recomposeScope = currentRecomposeScope
        recover({
          raise = this
          val res = runCatchingComposable { body() }
          clock.isRunning = false
          currentOutput.trySend(res.getOrThrow()).getOrThrow()
        }) {
          suspensions.trySend(Unit).getOrThrow()
        }
      }
    }
  }
  return resource({ output.receive() }) { _, _ -> job.cancelAndJoin() }
}