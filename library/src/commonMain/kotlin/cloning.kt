import kotlin.coroutines.Continuation

internal expect fun <T> Continuation<T>.clone(upTo: Hole<*>, replacement: Hole<*>): Continuation<T>

internal interface CloneableContinuation<T> : Continuation<T> {
  fun clone(upTo: Hole<*>, replacement: Hole<*>): CloneableContinuation<T>
}