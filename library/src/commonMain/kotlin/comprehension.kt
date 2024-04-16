import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import arrow.core.None
import arrow.core.Option
import arrow.core.raise.OptionRaise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.jvm.JvmInline

public sealed interface ComprehensionScope {
  public fun <T> ComprehensionLock<T>.register(list: List<T>): OptionalState<T>
  public fun readAll()
}

public class ComprehensionLock<T> : RememberObserver {
  private val waitingMutex = Mutex()

  internal val done: Boolean
    get() = !waitingMutex.isLocked

  private var job: Job? = null
  private val _state = mutableStateOf<Any?>(EmptyValue, neverEqualPolicy())

  internal val state get() = OptionalState<T>(_state)

  private suspend fun awaitNextItem() {
    waitingMutex.lock()
  }

  internal fun unlock() {
    waitingMutex.unlock()
  }

  internal fun CoroutineScope.configure(list: List<T>) {
    job = launch {
      try {
        for (element in list) {
          awaitNextItem()
          _state.value = element
        }
      } finally {
        if (waitingMutex.isLocked) waitingMutex.unlock()
      }
    }
  }

  internal suspend fun dispose() {
    job?.cancelAndJoin()
  }

  override fun onAbandoned() {
    // Nothing to do as [onRemembered] was not called.
  }

  override fun onForgotten() {
    job?.cancel()
  }

  override fun onRemembered() {
    // Do nothing
  }
}

@JvmInline
public value class OptionalState<@Suppress("unused") T>(internal val state: State<Any?>) {
  public fun read() {
    state.value
  }
}

internal object EmptyValue

context(OptionRaise)
public operator fun <T> OptionalState<T>.provideDelegate(
  thisRef: Any?, property: Any?
): State<T> {
  if (state.value == EmptyValue) raise(None)
  @Suppress("UNCHECKED_CAST") return state as State<T>
}

internal class ComprehensionScopeImpl(
  private val coroutineScope: CoroutineScope
) : ComprehensionScope {
  override fun readAll() {
    locks.forEach { it.state.read() }
  }

  private val locks = mutableListOf<ComprehensionLock<*>>()
  private val finished: Boolean
    get() = locks.all { it.done }

  override fun <T> ComprehensionLock<T>.register(list: List<T>): OptionalState<T> {
    if (this !in locks) {
      coroutineScope.configure(list)
      locks.add(this)
    }
    return state
  }

  /**
   * Unlocks the next lock in the list
   *
   * @return `true` if the next lock was unlocked, `false` otherwise.
   */
  internal suspend fun unlockNext(): Boolean = finished.also {
    val index = locks.indexOfLast { !it.done }
    if (index == -1) return@also
    locks[index].unlock()
    for (i in (index + 1)..<locks.size) {
      locks.removeAt(index + 1).dispose()
    }
  }
}

public fun <T> listComprehension(
  body: @Composable context(ComprehensionScope) () -> Option<T>
): Flow<T> = flow {
  coroutineScope {
    val scope = ComprehensionScopeImpl(this)
    val clock = GatedFrameClock(this)
    val outputBuffer = Channel<Option<T>>(1)

    launch(clock, start = CoroutineStart.UNDISPATCHED) {
      launchMolecule(mode = RecompositionMode.ContextClock, body = {
        body(scope)
      }, emitter = {
        clock.isRunning = false
        outputBuffer.trySend(it).getOrThrow()
      })
    }

    do {
      val result = outputBuffer.tryReceive()
      // Per `ReceiveChannel.tryReceive` documentation: isFailure means channel is empty.
      val value = if (result.isFailure) {
        clock.isRunning = true
        outputBuffer.receive()
      } else {
        result.getOrThrow()
      }
      value.onSome { emit(it) }
    } while (!scope.unlockNext())
    coroutineContext.cancelChildren()
  }
}

context(ComprehensionScope)
@Composable
public fun <T> List<T>.bindAsState(): OptionalState<T> {
  val lock = remember { ComprehensionLock<T>() }
  return lock.register(this@bindAsState)
}

context(A) private fun <A> given(): A = this@A

context(ComprehensionScope)
@Composable
public fun <R> effect(block: () -> R): R {
  val scope by rememberUpdatedState(given<ComprehensionScope>())
  return baseEffect {
    scope.readAll()
    block()
  }
}

@Composable
private fun <R> baseEffect(block: () -> R): R = remember { derivedStateOf(block) }.let {
  Snapshot.withoutReadObservation { it.value }
}