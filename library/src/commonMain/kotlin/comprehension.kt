import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentRecomposeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
@Suppress("EqualsOrHashCode")
private object Flip {
  override fun equals(other: Any?): Boolean {
    // Not equal to itself
    return Keep == other
  }
}

private data object Keep

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@DslMarker
public annotation class ComprehensionDsl

public class Shift<T, R> {
  private val outputBuffer = Channel<Any?>(1)
  private var state: Maybe<T> = nothing()

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
  @Composable
  internal fun configure(producer: suspend Shift<T, R>.() -> R): Maybe<T> {
    val previousShift = currentShift
    if (state.isEmpty) shouldUpdateEffects = true
    val key: Any = if (shouldUpdateEffects) {
      currentShift = this
      state = nothing()
      Flip
    } else Keep
    if (this === currentShift) shouldUpdateEffects = true

    LaunchedEffect(key) {
      receiveOutput().onJust { error("Missing bind call on a `Maybe` value") }
      val output = producer()
      state = nothing()
      currentShift = previousShift
      previousShift.sendOutput(output)
    }
    return state
  }
}

@ComprehensionDsl
public class Reset<R> internal constructor(
  shift: Shift<*, R>, coroutineScope: CoroutineScope
) {
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