import kotlin.coroutines.Continuation

internal actual fun <T> Continuation<T>.clone(): Continuation<T> = TODO()