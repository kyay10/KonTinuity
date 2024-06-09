package effekt

import Prompt
import abortS0
import reset
import shift0
import kotlin.jvm.JvmInline

public interface Handler<R, E> {
  public fun prompt(): ObscurePrompt<E>
  public suspend fun unit(value: R): E
  public suspend fun <A> use(body: suspend (Cont<A, E>) -> E): A = prompt().prompt.shift0(body)
  public fun <A> useAbort(body: suspend () -> E): A = prompt().prompt.abortS0(body)
}

public suspend fun <R, E, H : Handler<R, E>> handle(
  handler: ((() -> ObscurePrompt<E>) -> H), body: suspend H.() -> R
): E {
  val prompt = ObscurePrompt(Prompt<E>())
  val handler = handler { prompt }
  return handler.handle(body)
}

public suspend fun <R, E, H : Handler<R, E>> H.handle(body: suspend H.() -> R): E {
  return prompt().prompt.reset {
    val res = body()
    prompt().prompt.abortS0 { unit(res) }
  }
}

@JvmInline
public value class ObscurePrompt<E> internal constructor(internal val prompt: Prompt<E>)