import androidx.compose.runtime.Composable
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.currentRecomposeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@DslMarker
public annotation class ComprehensionDsl

public sealed interface ComprehensionTree

public class Shift<T, R> : RememberObserver, ComprehensionTree {
  private var job: Job? = null

  private val outputBuffer = Channel<Any?>(1)
  internal var state: Maybe<T> = nothing()

  internal suspend fun receiveOutput(): Maybe<R> = rawMaybe(outputBuffer.receive())
  internal fun trySendOutput(value: Maybe<R>) = outputBuffer.trySend(value.rawValue)
  private suspend fun sendOutput(just: Maybe<R>) = outputBuffer.send(just.rawValue)

  context(Reset<R>)
  public suspend operator fun invoke(value: T): R {
    currentShift = this
    state = just(value)
    recomposeScope.invalidate()
    shouldUpdateEffects = false
    clock.isRunning = true
    return receiveOutput().getOrElse { error("Missing value") }
  }

  context(Reset<R>)
  internal fun configure(producer: suspend () -> R) {
    this.job?.cancel()
    val previous = currentShift
    currentShift = this
    state = nothing()
    job = coroutineScope.launch {
      receiveOutput().onJust { error("Missing bind call on a `Maybe` value") }
      val output = producer()
      state = nothing()
      currentShift = previous
      previous.sendOutput(just(output))
    }
  }

  override fun onForgotten() {
    job?.cancel()
  }

  override fun onAbandoned() {}
  override fun onRemembered() {}
}

@ComprehensionDsl
public class Reset<R> internal constructor(
  shift: Shift<*, R>, internal val coroutineScope: CoroutineScope
) : ComprehensionTree {
  internal val clock = GatedFrameClock(coroutineScope)
  internal lateinit var recomposeScope: RecomposeScope
  internal var currentShift: Shift<*, R> = shift

  @PublishedApi
  internal var shouldUpdateEffects: Boolean = false
}

public suspend fun <R> reset(
  body: @Composable context(Reset<R>) () -> Maybe<R>
): R = coroutineScope {
  lazyComprehension(body).await().also { coroutineContext.cancelChildren() }
}

public fun <R> CoroutineScope.lazyComprehension(
  body: @Composable context(Reset<R>) () -> Maybe<R>
): Deferred<R> {
  val shift = Shift<Nothing, R>()
  return with(Reset<R>(shift, given<CoroutineScope>())) {
    launchMolecule({
      clock.isRunning = false
      currentShift.trySendOutput(it).getOrThrow()
    }, clock) {
      recomposeScope = currentRecomposeScope
      body(given<Reset<R>>())
    }
    async { shift.receiveOutput().getOrElse { error("Missing value") } }
  }
}