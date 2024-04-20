import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Launch a coroutine into this [CoroutineScope] which will continually recompose `body`
 * in the optional [context] to invoke [emitter] with each returned [T] value.
 *
 * [launchMolecule]'s [emitter] is always free-running and will not respect backpressure.
 *
 * The coroutine context is inherited from the [CoroutineScope].
 * Additional context elements can be specified with [context] argument.
 */
internal fun <T> CoroutineScope.launchMolecule(
  emitter: (value: T) -> Unit,
  context: CoroutineContext = EmptyCoroutineContext,
  body: @Composable () -> T,
) {
  val recomposer = Recomposer(coroutineContext + context)
  val composition = Composition(UnitApplier, recomposer)
  var snapshotHandle: ObserverHandle? = null
  launch(context, start = UNDISPATCHED) {
    try {
      recomposer.runRecomposeAndApplyChanges()
    } catch (e: CancellationException) {
      composition.dispose()
      snapshotHandle?.dispose()
    }
  }

  var applyScheduled = false
  snapshotHandle = Snapshot.registerGlobalWriteObserver {
    if (!applyScheduled) {
      applyScheduled = true
      launch(context) {
        applyScheduled = false
        Snapshot.sendApplyNotifications()
      }
    }
  }

  composition.setContent {
    emitter(body())
  }
}

private object UnitApplier : AbstractApplier<Unit>(Unit) {
  override fun insertBottomUp(index: Int, instance: Unit) {}
  override fun insertTopDown(index: Int, instance: Unit) {}
  override fun move(from: Int, to: Int, count: Int) {}
  override fun remove(index: Int, count: Int) {}
  override fun onClear() {}
}
