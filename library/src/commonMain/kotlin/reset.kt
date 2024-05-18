import androidx.compose.runtime.*
import arrow.core.raise.Raise
import kotlin.coroutines.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

public typealias Cont<T, R> = @Composable (T) -> R

@Suppress("EqualsOrHashCode")
@Stable
public class Reset<R>() : Continuation<R>, Raise<R> {
  private val holes: ArrayDeque<Hole<R>> = ArrayDeque()

  @PublishedApi
  internal fun pushHole(hole: Hole<R>) {
    holes.addFirst(hole)
  }

  @PublishedApi
  internal fun popHole(): Hole<R> = holes.removeFirstOrNull() ?: error("No prompt set")

  @PublishedApi
  internal fun pushHolesPrefix(holes: List<Hole<R>>) {
    this.holes.addAll(0, holes)
  }

  @PublishedApi
  internal tailrec fun MutableList<Hole<R>>.unwindTillMarked(keepDelimiterUponEffect: Boolean) {
    val hole = popHole()
    if (hole.isDelimiting) {
      pushHole(if (keepDelimiterUponEffect) hole else Hole(hole.continuation, false))
    } else {
      add(hole)
      unwindTillMarked(keepDelimiterUponEffect)
    }
  }

  // TODO investigate why `@DontMemoize` is needed for capturing lambdas with enableNonSkippingGroupOptimization
  @Composable
  public inline fun <T> `*F*`(
    keepDelimiterUponEffect: Boolean, isShift: Boolean, crossinline block: @Composable (Cont<T, R>) -> R
  ): T = suspendComposition { f ->
    val holesPrefix = buildList { unwindTillMarked(keepDelimiterUponEffect) }.asReversed()
    startSuspendingComposition(::resumeWith) @DontMemoize {
      block @DontMemoize { t ->
        suspendComposition @DontMemoize { k ->
          pushHole(Hole(k, isShift))
          pushHolesPrefix(holesPrefix)
          f.resume(t)
        }
      }
    }
  }

  override val context: CoroutineContext get() = holes.last().continuation.context

  override fun resumeWith(result: Result<R>): Unit =
    popHole().continuation.resumeWith(result)

  override fun raise(r: R): Nothing {
    resume(r)
    throw Suspended(Unit)
  }

  override fun equals(other: Any?): Boolean = false

  @PublishedApi
  internal class Hole<R>(val continuation: Continuation<R>, val isDelimiting: Boolean)
}