import androidx.compose.runtime.*
import kotlin.coroutines.resume

public typealias Cont<T, R> = @Composable (T) -> R

// TODO investigate why `@DontMemoize` is needed for capturing lambdas with enableNonSkippingGroupOptimization

@Composable
public inline fun <T, R> _Reset<R>._shiftC(crossinline block: @Composable (Cont<T, R>) -> R): T = suspendComposition { f ->
  startSuspendingComposition(this@_shiftC::resumeWith) @DontMemoize {
    block @DontMemoize { t ->
      suspendComposition @DontMemoize { k ->
        receiveResult(k)
        f.resume(t)
      }
    }
  }
}