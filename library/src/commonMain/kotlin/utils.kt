import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer

context(A) internal fun <A> given(): A = this@A

@Composable
internal inline fun <R> runCatchingComposable(block: @Composable () -> R): Result<R> {
  val marker = currentComposer.currentMarker
  return tryCatch({ Result.success(block()) }) {
    currentComposer.endToMarker(marker)
    Result.failure(it)
  }
}

// Used to prevent ILLEGAL_TRY_CATCH_AROUND_COMPOSABLE
private inline fun <R> tryCatch(block: () -> R, onError: (Throwable) -> R): R = try {
  block()
} catch (e: Throwable) {
  onError(e)
}