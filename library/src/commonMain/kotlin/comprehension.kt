import androidx.compose.runtime.Composable
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentRecomposeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@DslMarker
public annotation class ComprehensionDsl

public sealed interface ComprehensionTree

public class Shift<T, R> : ComprehensionTree {
  private var job: Job? = null
    set(value) {
      field?.cancel()
      field = value
    }

  private val outputBuffer = Channel<Any?>(1)
  internal var state: Maybe<T> = nothing()

  internal suspend fun receiveOutput(): Maybe<R> = rawMaybe(outputBuffer.receive())
  internal fun trySendOutput(value: Maybe<R>) = outputBuffer.trySend(value.rawValue)
  private suspend fun sendOutput(value: R) = outputBuffer.send(value)

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
  internal fun CoroutineScope.configure(producer: suspend () -> R) {
    val previous = currentShift
    currentShift = this@Shift
    state = nothing()
    job = launch {
      receiveOutput().onJust { error("Missing bind call on a `Maybe` value") }
      val output = producer()
      state = nothing()
      currentShift = previous
      previous.sendOutput(output)
    }
  }
}

@ComprehensionDsl
public class Reset<R> internal constructor(
  shift: Shift<*, R>, coroutineScope: CoroutineScope
) : ComprehensionTree {
  internal val clock = GatedFrameClock(coroutineScope)
  internal lateinit var recomposeScope: RecomposeScope
  internal var currentShift: Shift<*, R> = shift

  @PublishedApi
  internal var shouldUpdateEffects: Boolean = false
}

internal fun <R> CoroutineScope.Reset(shift: Shift<*, R>): Reset<R> =
  Reset(shift, given<CoroutineScope>())

public suspend fun <R> reset(
  body: @Composable Reset<R>.() -> Maybe<R>
): R = coroutineScope {
  lazyReset(body).await().also { coroutineContext.cancelChildren() }
}

public fun <R> CoroutineScope.lazyReset(
  body: @Composable Reset<R>.() -> Maybe<R>
): Deferred<R> {
  val shift = Shift<Nothing, R>()
  return with(Reset(shift)) {
    launchMolecule(clock, {
      clock.isRunning = false
      currentShift.trySendOutput(it).getOrThrow()
    }) {
      recomposeScope = currentRecomposeScope
      body()
    }
    async { shift.receiveOutput().getOrElse { error("Missing value") } }
  }
}