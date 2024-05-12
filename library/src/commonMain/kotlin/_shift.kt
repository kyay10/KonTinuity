import androidx.compose.runtime.*
import kotlin.coroutines.resume

public typealias Cont<T, R> = @Composable (T) -> R

// TODO investigate why `@DontMemoize` is needed for capturing lambdas with enableNonSkippingGroupOptimization

@Composable
public inline fun <T, R> _Reset<R>._shift(crossinline block: @Composable (Cont<T, R>) -> R): T {
  val currentSuspender = currentSuspender
  return suspendComposition {
    configure(currentSuspender) @DontMemoize {
      block @DontMemoize { t ->
        suspendComposition @DontMemoize { cont ->
          it.resume(t)
          receiveResult(cont)
        }
      }
    }
  }
}