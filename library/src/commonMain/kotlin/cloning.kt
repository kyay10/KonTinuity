import kotlin.coroutines.Continuation

@PublishedApi
internal expect fun <T> Continuation<T>.clone(): Continuation<T>