import kotlin.coroutines.Continuation

@PublishedApi
internal expect fun <T> Continuation<T>.clone(replacementPromptContinuation: Continuation<*>?, prompt: Prompt<*>?): Continuation<T>