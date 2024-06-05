import kotlin.coroutines.Continuation
internal expect val Continuation<*>.isCompilerGenerated: Boolean
internal expect val Continuation<*>.completion: Continuation<*>
internal expect fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T>

@Suppress("UNCHECKED_CAST")
internal fun <T, R> Continuation<T>.clone(prompt: Prompt<R>, replacement: Continuation<R>): Continuation<T> =
  when {
    this is Hole<*> && this.prompt == prompt -> replacement as Continuation<T>
    isCompilerGenerated -> copy(completion.clone(prompt, replacement))
    this is CopyableContinuation<T> -> copy(completion.clone(prompt, replacement))
    else -> error("Continuation $this is not cloneable, but $prompt has not been found in the chain.")
  }

internal interface CopyableContinuation<T> : Continuation<T> {
  val completion: Continuation<*>
  fun copy(completion: Continuation<*>): CopyableContinuation<T>
}