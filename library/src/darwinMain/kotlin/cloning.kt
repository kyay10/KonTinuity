import kotlin.coroutines.Continuation

internal actual fun <T, R> Continuation<T>.compilerGeneratedCloneOrNull(
  prompt: Prompt<R>, replacement: Continuation<R>
): Continuation<T>? = null