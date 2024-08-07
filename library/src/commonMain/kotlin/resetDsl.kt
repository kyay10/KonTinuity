public fun interface Cont<in T, out R> {
  public suspend fun resumeWith(value: Result<T>): R
  public suspend operator fun invoke(value: T): R = resumeWith(Result.success(value))
  public suspend fun resumeWithException(exception: Throwable): R = resumeWith(Result.failure(exception))
}

@ResetDsl
public suspend fun <R> Prompt<R>.reset(body: suspend () -> R): R = pushPrompt(body = body)

@ResetDsl
public suspend fun <R> newReset(body: suspend Prompt<R>.() -> R): R = with(Prompt<R>()) { reset { body() } }

public suspend fun <R> topReset(body: suspend Prompt<R>.() -> R): R = runCC { newReset(body) }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubCont(deleteDelimiter = false) { sk -> block { sk.pushSubContWith(it, isDelimiting = true) } }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.control(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubCont(deleteDelimiter = false) { sk -> block { sk.pushSubContWith(it) } }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift0(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubCont { sk -> block { sk.pushSubContWith(it, isDelimiting = true) } }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.control0(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubCont { sk -> block { sk.pushSubContWith(it) } }

@ResetDsl
public fun <R> Prompt<R>.abortWith(value: Result<R>): Nothing = abortWith(deleteDelimiter = false, value)

@ResetDsl
public fun <R> Prompt<R>.abortWith0(value: Result<R>): Nothing = abortWith(deleteDelimiter = true, value)

@ResetDsl
public fun <R> Prompt<R>.abort(value: R): Nothing = abortWith(Result.success(value))

@ResetDsl
public fun <R> Prompt<R>.abort0(value: R): Nothing = abortWith0(Result.success(value))

@ResetDsl
public fun <R> Prompt<R>.abortS(value: suspend () -> R): Nothing = abortS(deleteDelimiter = false, value)

@ResetDsl
public fun <R> Prompt<R>.abortS0(value: suspend () -> R): Nothing = abortS(deleteDelimiter = true, value)