import kotlin.coroutines.Continuation

internal expect fun <T, R> Continuation<T>.compilerGeneratedCloneOrNull(
  prompt: Prompt<R>, replacement: Continuation<R>
): Continuation<T>?

@Suppress("UNCHECKED_CAST")
internal fun <T, R> Continuation<T>.clone(prompt: Prompt<R>, replacement: Continuation<R>): Continuation<T> =
  compilerGeneratedCloneOrNull(prompt, replacement) ?: when {
    this is Hole<*> && this.prompt == prompt -> replacement as Continuation<T>
    this is CloneableContinuation<T> -> clone(prompt, replacement)
    else -> error("Continuation $this is not cloneable, but $prompt has not been found in the chain.")
  }

internal interface CloneableContinuation<T> : Continuation<T> {
  fun <R> clone(prompt: Prompt<R>, replacement: Continuation<R>): CloneableContinuation<T>
}