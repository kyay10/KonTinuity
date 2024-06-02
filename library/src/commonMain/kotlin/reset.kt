import kotlinx.coroutines.CancellationException
import kotlin.coroutines.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

public data class SubCont<T, R> internal constructor(
  @PublishedApi internal val ekFragment: Continuation<T>,
  private val prompt: Prompt<R>,
  private val subchain: List<Hole<*>>
) {
  @PublishedApi
  internal fun PStack.push(k: Continuation<R>, isDelimiting: Boolean = false) {
    push(Hole(prompt.takeIf { isDelimiting }, k))
    pushAll(subchain)
  }

  @ResetDsl
  public suspend fun pushSubCont(isDelimiting: Boolean = false, value: Result<T>): R = suspendMultishotCoroutine { k ->
    k.context.pStack.push(k, isDelimiting)
    ekFragment.resumeWith(value)
  }


  @ResetDsl
  public suspend fun pushSubContS(isDelimiting: Boolean = false, value: suspend () -> T): R =
    suspendMultishotCoroutine { k ->
      k.context.pStack.push(k, isDelimiting)
      value.startCoroutine(ekFragment)
    }

  @ResetDsl
  public suspend fun pushDelimSubCont(value: Result<T>): R = pushSubCont(isDelimiting = true, value)

  @ResetDsl
  public suspend fun pushDelimSubContS(value: suspend () -> T): R = pushSubContS(isDelimiting = true, value)
}

@ResetDsl
public suspend fun <R> Prompt<R>.pushPrompt(body: suspend Prompt<R>.() -> R): R = suspendMultishotCoroutine { k ->
  val pStack = k.context.pStack
  pStack.push(Hole(this, k))
  body.startCoroutine(this, pStack)
}

@ResetDsl
public suspend fun <T, R> Prompt<R>.takeSubCont(
  deleteDelimiter: Boolean = true, body: suspend (SubCont<T, R>) -> R
): T = suspendMultishotCoroutine(intercepted = false) { k ->
  with(k.context.pStack) {
    val subchain = buildList { unwind(this@Prompt) }
    // TODO: this seems dodgy
    if (deleteDelimiter) deleteDelimiter()
    body.startCoroutine(SubCont(k, this@Prompt, subchain), this)
  }
}

@PublishedApi
internal fun PStack.preAbort(prompt: Prompt<*>, deleteDelimiter: Boolean) {
  DevNullList.unwind(prompt)
  if (deleteDelimiter) deleteDelimiter()
}

@ResetDsl
@PublishedApi
internal fun <R> Prompt<R>.abort(
  pStack: PStack, deleteDelimiter: Boolean = false, value: Result<R>
): Nothing {
  pStack.preAbort(this, deleteDelimiter)
  (pStack as Continuation<Any?>).resumeWith(value)
  // TODO: remove this and replace with some Raise-based mechanism
  throw AbortException
}

@ResetDsl
@PublishedApi
internal suspend fun <R> Prompt<R>.abortS(
  pStack: PStack, deleteDelimiter: Boolean = false, value: suspend () -> R
): Nothing {
  pStack.preAbort(this, deleteDelimiter)
  value.startCoroutine(pStack)
  suspendCoroutine<Nothing> { }
}

internal data class Hole<R>(val prompt: Prompt<R>?, val continuation: Continuation<R>)

internal class PStack : CoroutineContext.Element, Continuation<Any?> {
  private val pStack: MutableList<Hole<*>> = mutableListOf()

  internal fun peek(): Hole<*> = pStack.lastOrNull() ?: error("No prompt set")
  internal fun pop(): Hole<*> = pStack.removeLastOrNull() ?: error("No prompt set")
  internal fun push(hole: Hole<*>) = pStack.add(hole)
  internal fun pushAll(holes: List<Hole<*>>) = pStack.addAll(holes)
  internal fun deleteDelimiter() = push(Hole(null, pop().continuation))
  internal val <R> Prompt<R>.isSet: Boolean get() = pStack.any { it.prompt === this }

  internal tailrec fun MutableList<in Hole<*>>.unwind(prompt: Prompt<*>) {
    val item = peek()
    if (item.prompt !== prompt) {
      pop()
      add(0, item)
      unwind(prompt)
    }
  }

  override val key: CoroutineContext.Key<PStack> get() = Key

  override val context: CoroutineContext get() = peek().continuation.context

  @Suppress("UNCHECKED_CAST")
  override fun resumeWith(result: Result<Any?>) {
    if (result.exceptionOrNull() == AbortException) return
    (pop().continuation as Continuation<Any?>).resumeWith(result)
  }

  companion object Key : CoroutineContext.Key<PStack>

  fun clear() {
    pStack.clear()
  }
}

@PublishedApi
internal suspend fun pStack(): PStack = coroutineContext.pStack

@PublishedApi
internal val CoroutineContext.pStack get() = this[PStack.Key] ?: error("Not in multi-shot continuation context")

@ResetDsl
public suspend fun <R> Prompt<R>.isSet(): Boolean = with(pStack()) { isSet }

private object DevNullList : AbstractMutableList<Any?>() {
  override val size: Int get() = 0
  override fun get(index: Int): Any? = throw IndexOutOfBoundsException()
  override fun add(index: Int, element: Any?) {
    check(index == 0)
  }

  override fun removeAt(index: Int): Any? = throw IndexOutOfBoundsException()
  override fun set(index: Int, element: Any?): Any? = throw IndexOutOfBoundsException()
}

public class Prompt<R>

private object AbortException : CancellationException("Abort")