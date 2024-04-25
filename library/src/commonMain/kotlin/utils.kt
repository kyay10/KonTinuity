import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer

context(A) internal fun <A> given(): A = this@A

@Suppress("EqualsOrHashCode")
private object Flip {
  override fun equals(other: Any?): Boolean {
    // Not equal to itself
    return Keep == other
  }
}

private data object Keep

internal fun updatingKey(shouldUpdate: Boolean): Any = if (shouldUpdate) Flip else Keep

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