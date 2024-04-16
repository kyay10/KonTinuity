import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
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
import arrow.core.Option
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

public sealed interface ComprehensionScope {
  public fun <T> ComprehensionLock<T>.register(list: List<T>): State<T>
  public fun readAll()
}

public class ComprehensionLock<T> : RememberObserver {
  private val waitingMutex = Mutex()

  internal val done: Boolean
    get() = !waitingMutex.isLocked

  private var job: Job? = null
  private val stateOfState = mutableStateOf<MutableState<T>?>(null)
  internal val state get() = stateOfState.value!!

  private suspend fun awaitNextItem() {
    waitingMutex.lock()
  }

  internal fun unlock() {
    waitingMutex.unlock()
  }

  internal fun CoroutineScope.configure(list: List<T>) {
    stateOfState.value = mutableStateOf(list.first(), neverEqualPolicy())
    job = launch {
      var first = true
      try {
        for (element in list) {
          awaitNextItem()
          if (first) {
            first = false
          } else {
            stateOfState.value!!.value = element
          }
        }
      } finally {
        if (waitingMutex.isLocked)
          waitingMutex.unlock()
      }
    }
  }

  internal fun dispose() {
    job?.cancel()
  }

  override fun onAbandoned() {
    // Nothing to do as [onRemembered] was not called.
  }

  override fun onForgotten() {
    dispose()
  }

  override fun onRemembered() {
    // Do nothing
  }
}

internal class ComprehensionScopeImpl(
  private val coroutineScope: CoroutineScope
) : ComprehensionScope {
  override fun readAll() {
    stack.forEach { it.state.value }
  }

  private val stack = mutableListOf<ComprehensionLock<*>>()
  private val finished: Boolean
    get() = stack.all { it.done }

  override fun <T> ComprehensionLock<T>.register(list: List<T>): State<T> {
    if (this !in stack) {
      coroutineScope.configure(list)
      stack.add(this)
    }
    return state
  }

  internal fun unlockNext(): Boolean = finished.also {
    val index = stack.indexOfLast { !it.done }
    if (index == -1) return@also
    val snapshot = Snapshot.takeMutableSnapshot()
    try {
      snapshot.enter {
        stack[index].unlock()
        for (i in (index + 1)..<stack.size) {
          stack.removeAt(index + 1).dispose()
        }
      }
      snapshot.apply().check()
    } finally {
      snapshot.dispose()
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
public fun <T> List<T>.bindAsState(): State<T> {
  val lock = remember { ComprehensionLock<T>() }
  return lock.register(this@bindAsState).also { it.value }
}

context(ComprehensionScope)
@Composable
public fun <T> List<T>.bind(): T = bindAsState().value

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
private fun <R> baseEffect(block: () -> R): R =
  remember { derivedStateOf(block) }.let {
    Snapshot.withoutReadObservation { it.value }
  }