public class Handle<Error, T, R> : Reader<(Error, SubCont<T, R>) -> R> {
  internal val prompt = Prompt<R>()
}

@ResetDsl
public suspend fun <Error, T, R> Handle<Error, T, R>.resetWithHandler(
  handler: (Error, SubCont<T, R>) -> R, body: suspend () -> R
): R = prompt.pushPrompt(context(handler), body)

@ResetDsl
public suspend fun <Error, T, R> Handle<Error, T, R>.fcontrol(error: Error): T {
  val handler = get()
  return prompt.takeSubCont(deleteDelimiter = false) { sk ->
    handler(error, sk)
  }
}