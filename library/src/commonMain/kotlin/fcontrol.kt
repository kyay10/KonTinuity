public class Handle<Error, T, R> : Reader<suspend (Error, SubCont<T, R>) -> R> {
  internal val prompt = Prompt<R>()
}

@ResetDsl
public suspend fun <Error, T, R> Handle<Error, T, R>.resetWithHandler(
  handler: suspend (Error, SubCont<T, R>) -> R, body: suspend () -> R
): R = prompt.pushPrompt(context(handler), body = body)

@ResetDsl
public suspend fun <Error, T, R> Handle<Error, T, R>.fcontrol(error: Error): T {
  val handler = ask()
  return prompt.takeSubCont(deleteDelimiter = false) { sk ->
    handler(error, sk)
  }
}