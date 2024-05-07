import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer

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