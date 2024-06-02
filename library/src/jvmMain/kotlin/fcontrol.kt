@file:Suppress("SUBTYPING_BETWEEN_CONTEXT_RECEIVERS")

public data class ResumableError<Error, T, R>(val error: Error, val continuation: SubCont<T, R>)

context(Prompt<ResumableError<Error, T, R>>, Prompt<R>)
@ResetDsl
public suspend fun <Error, T, R> resetWithHandler(
  handler: (Error, SubCont<T, R>) -> R, body: suspend () -> R
): R = reset<R> {
  val (error, continuation) = reset<ResumableError<Error, T, R>> {
    val result = body()
    abort(result)
  }
  handler(error, continuation)
}

context(Prompt<ResumableError<Error, T, R>>, Prompt<R>)
@ResetDsl
public suspend fun <Error, T, R> fcontrol(error: Error): T = peekSubCont<_, R>(deleteDelimiter = false) { sk ->
  abort<ResumableError<Error, T, R>>(ResumableError(error, sk))
}