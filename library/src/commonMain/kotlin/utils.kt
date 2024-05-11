import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import kotlin.coroutines.Continuation

@Composable
@PublishedApi
internal inline fun <R> runCatchingComposable(block: @Composable () -> R): Result<R> {
  val marker = currentComposer.currentMarker
  return tryCatch({ Result.success(block()) }) {
    currentComposer.endToMarker(marker)
    Result.failure(it)
  }
}

// Used to prevent ILLEGAL_TRY_CATCH_AROUND_COMPOSABLE
@PublishedApi
internal inline fun <R> tryCatch(block: () -> R, onError: (Throwable) -> R): R = try {
  block()
} catch (e: Throwable) {
  onError(e)
}

internal inline fun <R> Continuation<R>.onResume(crossinline action: (Result<R>) -> Unit): Continuation<R> =
  Continuation(context) {
    action(it)
    resumeWith(it)
  }