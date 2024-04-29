import androidx.compose.runtime.*
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import arrow.fx.coroutines.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@DslMarker
public annotation class ResetDsl

@ResetDsl
public class Reset<R> internal constructor(
  output: Continuation<R>, internal val coroutineScope: CoroutineScope, body: @Composable Reset<R>.() -> R
) {
  private var resumeJob: Job? = null
  internal var currentContinuation: Continuation<R> = output
    private set
  private var resumeToken: Any? = null

  private val clock: GatedFrameClock = GatedFrameClock(coroutineScope)
  private lateinit var recomposeScope: RecomposeScope

  @PublishedApi
  internal var reachedResumePoint: Boolean = true
    private set

  init {
    coroutineScope.launchMolecule(RecompositionMode.ContextClock, { res ->
      clock.isRunning = false
      res.fold(currentContinuation::resume) {
        if (it is Suspended && it.reset == this@Reset) {
          resumeJob!!.start()
        } else {
          currentContinuation.resumeWithException(it)
        }
      }
    }, clock) {
      recomposeScope = currentRecomposeScope
      runCatchingComposable { body() }
    }
  }

  internal suspend fun resumeAt(token: Any) = suspendCoroutine { continuation ->
    recomposeScope.invalidate()
    reachedResumePoint = false
    currentContinuation = continuation
    resumeToken = token
    clock.isRunning = true
  }

  internal fun suspendComposition(job: Job): Nothing {
    resumeJob = job
    throw Suspended(this)
  }

  internal fun reachedResumeToken(token: Any) {
    if (resumeToken == token) {
      reachedResumePoint = true
    }
  }
}

public suspend fun <R> reset(
  body: @Composable Reset<R>.() -> R
): R = resourceScope { lazyReset(body) }

public suspend fun <R> ResourceScope.lazyReset(
  body: @Composable Reset<R>.() -> R
): R {
  val job = Job(coroutineContext[Job])
  val scope = CoroutineScope(coroutineContext + job)
  onRelease { job.cancelAndJoin() }
  return suspendCoroutine { cont ->
    Reset(cont, scope, body)
  }
}

private class Suspended(val reset: Reset<*>) : CancellationException("Composable was suspended")