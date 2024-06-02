public typealias Cont<T, R> = suspend (T) -> R

@ResetDsl
public suspend fun <R> Prompt<R>.reset(body: suspend Prompt<R>.() -> R): R = pushPrompt(body)

@ResetDsl
public suspend fun <R> newReset(body: suspend Prompt<R>.() -> R): R = Prompt<R>().reset(body)

public suspend fun <R> topReset(body: suspend Prompt<R>.() -> R): R =
  runCC { newReset(body) }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubCont(deleteDelimiter = false) { sk -> block { sk.pushDelimSubCont(Result.success(it)) } }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.control(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubCont(deleteDelimiter = false) { sk -> block { sk.pushSubCont(value = Result.success(it)) } }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.shift0(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubCont { sk -> block { sk.pushDelimSubCont(Result.success(it)) } }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.control0(crossinline block: suspend (Cont<T, R>) -> R): T =
  takeSubCont { sk -> block { sk.pushSubCont(value = Result.success(it)) } }

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.peekSubCont(deleteDelimiter: Boolean = true, crossinline block: suspend (SubCont<T, R>) -> T): T =
  takeSubCont(deleteDelimiter = deleteDelimiter) { sk ->
    sk.pushSubContS { block(sk) }
  }

@ResetDsl
public suspend inline fun <R> Prompt<R>.abortWith(value: Result<R>): Nothing =
  abort(pStack(), deleteDelimiter = false, value)

@ResetDsl
public suspend inline fun <R> Prompt<R>.abortWith0(value: Result<R>): Nothing =
  abort(pStack(), deleteDelimiter = true, value)

@ResetDsl
public suspend fun <R> Prompt<R>.abort(value: R): Nothing =
  abort(pStack(), deleteDelimiter = false, Result.success(value))

@ResetDsl
public suspend fun <R> Prompt<R>.abort0(value: R): Nothing =
  abort(pStack(), deleteDelimiter = true, Result.success(value))

@ResetDsl
public suspend fun <R> Prompt<R>.abortS(value: suspend () -> R): Nothing =
  abortS(pStack(), deleteDelimiter = false, value)

@ResetDsl
public suspend fun <R> Prompt<R>.abortS0(value: suspend () -> R): Nothing =
  abortS(pStack(), deleteDelimiter = true, value)