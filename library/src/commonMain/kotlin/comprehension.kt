import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.StateObject
import androidx.compose.runtime.snapshots.StateRecord
import androidx.compose.runtime.snapshots.withCurrent
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

internal sealed interface ComprehensionScope {
  val isRunning: Boolean
  fun <T> ComprehensionLock<T>.register(ts: List<T>): State<T>
}

internal class ComprehensionLock<T> {
  private val waitingMutex = Mutex()

  val done: Boolean
    get() = !waitingMutex.isLocked

  var list: List<T>? = null
  var job: Job? = null
  val stateOfState = mutableStateOf<MutableState<T>?>(null)
  val state = derivedStateOf { stateOfState.value?.value ?: null as T }

  suspend fun awaitNextItem() {
    waitingMutex.lock()
  }

  fun unlock() {
    waitingMutex.unlock()
  }

  fun signalFinished() {
    waitingMutex.unlock()
  }

  fun CoroutineScope.configure(ts: List<T>) {
    list = ts
    stateOfState.value = mutableStateOf(ts.first(), neverEqualPolicy())
    job = launch {
      var first = true
      for (element in list!!) {
        awaitNextItem()
        if (first) {
          first = false
        } else {
          stateOfState.value!!.value = element
        }
      }
      signalFinished()
    }
  }

  fun dispose() {
    job?.cancel()
  }
}

internal class ComprehensionScopeImpl(
  private val coroutineScope: CoroutineScope, private val clock: GatedFrameClock
) : ComprehensionScope {
  private val stack = mutableListOf<ComprehensionLock<*>>()
  val finished: Boolean
    get() = stack.all { it.done }

  override val isRunning: Boolean
    get() = clock.isRunning

  override fun <T> ComprehensionLock<T>.register(ts: List<T>): State<T> {
    if (this !in stack) {
      coroutineScope.configure(ts)
      stack.add(this)
    }
    return state
  }

  fun unlockNext(): Boolean = finished.also {
    val index = stack.indexOfLast { !it.done }
    if (index == -1) return@also
    val snapshot = Snapshot.takeMutableSnapshot()
    try {
      snapshot.enter {
        stack[index].unlock()
        for (i in (index + 1)..<stack.size) {
          stack.removeAt(index + 1).reset()
        }
      }
      snapshot.apply().check()
    } finally {
      snapshot.dispose()
    }
  }

  private fun <T> ComprehensionLock<T>.reset() {
    dispose()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || this::class != other::class) return false

    other as ComprehensionScopeImpl

    return stack == other.stack
  }

  override fun hashCode(): Int {
    return stack.hashCode()
  }

}

internal inline fun <T> listComprehension(
  crossinline body: @Composable context(ComprehensionScope) () -> Option<T>
): Flow<T> = flow {
  coroutineScope {
    val clock = GatedFrameClock(this)
    val scope = ComprehensionScopeImpl(this, clock)
    val outputBuffer = Channel<Option<T>>(1)

    launch(clock, start = CoroutineStart.UNDISPATCHED) {
      launchMolecule(mode = RecompositionMode.ContextClock, body = {
        body(scope)
      }, emitter = {
        clock.isRunning = false
        outputBuffer.trySend(it).getOrThrow()
      })
    }
    var finished: Boolean
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
      finished = scope.unlockNext()
    } while (!finished)
    coroutineContext.cancelChildren()
  }
}

context(ComprehensionScope)
@Composable
internal fun <T> List<T>.bind(): State<T> {
  val lock = remember { ComprehensionLock<T>() }
  DisposableEffect(Unit) {
    onDispose {
      lock.dispose()
    }
  }
  return lock.register(this@bind)
}

context(ComprehensionScope)
@Composable
internal fun <T> List<T>.bindHere(): T = bind().value

context(A) private fun <A> given(): A = this@A

private val StateObject.currentRecord: StateRecord
  get() = firstStateRecord.withCurrent { it }

@Composable
public fun <R> effect(block: () -> R): R {
  var stateToRecord by remember { mutableStateOf<Map<StateObject, StateRecord>?>(null) }
  var result by remember { mutableStateOf<R?>(null) }
  if (stateToRecord?.any { (state, record) -> state.currentRecord != record } != false) {
    stateToRecord = buildMap {
      Snapshot.observe(readObserver = {
        put(
          it as StateObject,
          it.currentRecord
        )
      }) {
        result = block()
      }
    }
  }
  return result!!
}