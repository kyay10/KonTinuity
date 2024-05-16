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
  @PublishedApi
  internal val holes: ArrayDeque<Hole<R>> = ArrayDeque()

  @PublishedApi
  internal tailrec fun MutableList<Hole<R>>.unwindTillMarked(keepDelimiterUponEffect: Boolean) {
    val hole = holes.removeLastOrNull() ?: error("No prompt set")
    if (hole.isDelimiting) {
      holes.addLast(if (keepDelimiterUponEffect) hole else Hole(hole.continuation, false))
    } else {
      add(hole)
      unwindTillMarked(keepDelimiterUponEffect)
    }
  }

  // TODO investigate why `@DontMemoize` is needed for capturing lambdas with enableNonSkippingGroupOptimization
  @Composable
  public inline fun <T, R> Reset<R>.`*F*`(
    keepDelimiterUponEffect: Boolean, isShift: Boolean, crossinline block: @Composable (Cont<T, R>) -> R
  ): T = suspendComposition { f ->
    val holesPrefix = buildList { unwindTillMarked(keepDelimiterUponEffect) }
    startSuspendingComposition(this@`*F*`::resumeWith) @DontMemoize {
      block @DontMemoize { t ->
        suspendComposition @DontMemoize { k ->
          holes.addLast(Hole(k, isShift))
          holes.addAll(holesPrefix)
          f.resume(t)
        }
      }
    }
  }

  override val context: CoroutineContext get() = holes.last().continuation.context

  override fun resumeWith(result: Result<R>): Unit =
    (holes.removeLastOrNull() ?: error("No prompt set")).continuation.resumeWith(result)

  override fun raise(r: R): Nothing {
    resume(r)
    throw Suspended(Unit)
  }

  override fun equals(other: Any?): Boolean = false

  @PublishedApi
  internal class Hole<R>(val continuation: Continuation<R>, val isDelimiting: Boolean)
}