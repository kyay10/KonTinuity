import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.currentRecomposeScope
import arrow.core.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.getOrElse
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@DslMarker
public annotation class ComprehensionDsl

public sealed interface ComprehensionTree

internal class ComprehensionState<T> : RememberObserver, ComprehensionTree {
  private var channel: ReceiveChannel<T>? = null

  internal var state: Maybe<T> = nothing()
  internal var changedSinceLastRun: Boolean = false

  internal suspend fun advance() {
    state = channel!!.receiveCatching().fold(::just) { e -> if (e != null) throw e else nothing() }
  }

  internal fun configure(channel: ReceiveChannel<T>) {
    if (state.isNotEmpty) return
    this.channel?.cancel()
    this.channel = channel
  }

  override fun onForgotten() {
    channel?.cancel()
  }

  override fun onAbandoned() {}
  override fun onRemembered() {}
}

private fun <T, R> ChannelResult<T>.fold(onSuccess: (T) -> R, onFailure: (Throwable?) -> R) =
  onSuccess(getOrElse { return onFailure(it) })

@ComprehensionDsl
public class ComprehensionScope internal constructor(
  private val coroutineScope: CoroutineScope
) : ComprehensionTree {
  private val stack = mutableListOf<ComprehensionState<*>>()

  @PublishedApi
  internal var shouldUpdateEffects: Boolean = false
    private set

  internal fun accessed(state: ComprehensionState<*>) {
    if (state.changedSinceLastRun) shouldUpdateEffects = true
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  internal fun <T> ComprehensionState<T>.configure(producer: suspend ProducerScope<T>.() -> Unit) {
    configure(coroutineScope.produce(block = producer))
  }

  /** Returns true if some states still aren't finished */
  internal suspend fun unlockNext(): Boolean {
    shouldUpdateEffects = false
    var allFinishedSoFar = true
    for (state in stack.asReversed()) {
      state.changedSinceLastRun = allFinishedSoFar
      if (allFinishedSoFar) {
        state.changedSinceLastRun = true
        state.advance()
        if (!state.state.isEmpty) {
          allFinishedSoFar = false
        }
      }
    }
    return !allFinishedSoFar
  }

  @Suppress("UNCHECKED_CAST")
  internal inner class ComprehensionApplier : AbstractApplier<ComprehensionTree>(this) {
    override fun insertBottomUp(index: Int, instance: ComprehensionTree) {}

    override fun insertTopDown(index: Int, instance: ComprehensionTree) =
      stack.add(index, instance as ComprehensionState<*>)

    override fun move(from: Int, to: Int, count: Int) =
      (stack as MutableList<ComprehensionTree>).move(from, to, count)

    override fun remove(index: Int, count: Int) =
      (stack as MutableList<ComprehensionTree>).remove(index, count)

    override fun onClear() = stack.clear()
  }
}

public fun <T> listComprehension(
  body: @Composable context(ComprehensionScope) () -> Maybe<T>
): Flow<T> = flow {
  coroutineScope {
    val scope = ComprehensionScope(this)
    val clock = GatedFrameClock(this)
    val outputBuffer = Channel<Any?>(1)
    lateinit var recomposeScope: RecomposeScope

    launchMolecule({
      clock.isRunning = false
      outputBuffer.trySend(it.rawValue).getOrThrow()
    }, clock, scope.ComprehensionApplier()) {
      recomposeScope = currentRecomposeScope
      body(scope)
    }

    rawMaybe<T>(outputBuffer.receive()).onJust { emit(it) }
    while (scope.unlockNext()) {
      recomposeScope.invalidate()
      clock.isRunning = true
      rawMaybe<T>(outputBuffer.receive()).onJust { emit(it) }
    }
    coroutineContext.cancelChildren()
  }
}
