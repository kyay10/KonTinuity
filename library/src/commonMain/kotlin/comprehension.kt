import androidx.compose.runtime.Composable
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.currentRecomposeScope
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import arrow.core.raise.Raise
import arrow.core.raise.recover
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@DslMarker
public annotation class ComprehensionDsl

public class Shift<T, R> internal constructor(private val reset: Reset<R>) : RememberObserver {
  private val output = Channel<R>(1)

  @Suppress("UNCHECKED_CAST")
  private var state: T = null as T

  private var job: Job? = null

  public suspend operator fun invoke(value: T): R = with(reset) {
    state = value
    recomposeScope.invalidate()
    reachedResumePoint = false
    currentOutput = output
    clock.isRunning = true
    return output.receive()
  }

  internal fun configure(producer: suspend Shift<T, R>.() -> R): T = with(reset) {
    if (reachedResumePoint) {
      val previousOutputBuffer = currentOutput
      job?.cancel()
      job = coroutineScope.launch {
        suspensions.receive()
        previousOutputBuffer.send(producer())
      }
      raise.raise(Unit)
    }
    if (currentOutput == output) reachedResumePoint = true
    state
  }

  override fun onAbandoned() {
    job?.cancel()
  }

  override fun onForgotten() {
    job?.cancel()
  }

  override fun onRemembered() {
  }
}

@ComprehensionDsl
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