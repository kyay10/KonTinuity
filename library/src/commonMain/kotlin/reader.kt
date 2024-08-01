public data class ReaderValue<T>(public val value: T) : Stateful<ReaderValue<T>> {
  override fun fork(): ReaderValue<T> = copy()
}

public class Reader<T> {
  private val prompt = StatePrompt<Nothing, ReaderValue<T>>()
  public suspend fun ask(): T = prompt.getState().value
  public suspend fun askOrNull(): T? = prompt.getStateOrNull()?.value

  // TODO maybe a simple unsafe cast might be better? We never takeSubCont here
  public suspend fun <R> pushReader(value: T, body: suspend () -> R): R = newReset {
    prompt.pushPrompt(ReaderValue(value)) { abort(body()) }
  }

  public suspend fun deleteBinding(): Unit = prompt.takeSubCont {
    it.pushSubContWith(Result.success(Unit), isFinal = true)
  }
}

public class StatefulReader<T : Stateful<T>> {
  private val prompt = StatePrompt<Nothing, T>()
  public suspend fun ask(): T = prompt.getState()
  public suspend fun askOrNull(): T? = prompt.getStateOrNull()
  public suspend fun <R> pushReader(value: T, body: suspend () -> R): R = newReset {
    prompt.pushPrompt(value) { abort(body()) }
  }

  public suspend fun deleteBinding(): Unit = prompt.takeSubCont {
    it.pushSubContWith(Result.success(Unit), isFinal = true)
  }
}


public suspend fun <T, R> runReader(value: T, body: suspend Reader<T>.() -> R): R = with(Reader<T>()) {
  pushReader(value) { body() }
}

public suspend fun <T : Stateful<T>, R> runStatefulReader(value: T, body: suspend StatefulReader<T>.() -> R): R =
  with(StatefulReader<T>()) {
    pushReader(value) { body() }
  }