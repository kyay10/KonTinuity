import kotlin.coroutines.Continuation

@PublishedApi
internal actual fun <T> Continuation<T>.clone(): Continuation<T> = TODO()