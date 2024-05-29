import kotlinx.coroutines.CancellationException
import kotlin.coroutines.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

public data class SubCont<T, R> @PublishedApi internal constructor(
  @PublishedApi internal val ekFragment: Continuation<T>,
  private val prompt: Prompt<R>,
  private val subchain: List<Hole<*>>
) {
  @PublishedApi
  internal fun push(k: Continuation<R>, isDelimiting: Boolean = false) {
    pushStack(Hole(prompt.takeIf { isDelimiting }, k))
    pushAllStack(subchain)
  }

  @ResetDsl
  public suspend inline fun pushSubCont(isDelimiting: Boolean = false, value: () -> T): R =
    suspendMultishotCoroutine { k ->
      push(k, isDelimiting)
      ekFragment.resumeWith(runCatching(value))
    }

  @ResetDsl
  public suspend inline fun pushDelimSubCont(value: () -> T): R = pushSubCont(isDelimiting = true, value)
}

@ResetDsl
public suspend inline fun <R> Prompt<R>.pushPrompt(body: Prompt<R>.() -> R): R = suspendMultishotCoroutine { k ->
  pushStack(Hole(this, k))
  resumeWith(runCatching(body))
}

@ResetDsl
@PublishedApi
@Suppress("UNCHECKED_CAST")
internal suspend inline fun <T, R> Prompt<R>.takeSubContHere(
  deleteDelimiter: Boolean = true, body: (SubCont<T, R>) -> Unit
): T = suspendMultishotCoroutine(intercepted = false) { k ->
  val subchain = buildList { unwind(this@Prompt) }
  // TODO: this seems dodgy
  if (deleteDelimiter) deleteDelimiter()
  body(SubCont(k, this, subchain))
}

@ResetDsl
public suspend inline fun <T, R> Prompt<R>.takeSubCont(
  deleteDelimiter: Boolean = true, body: (SubCont<T, R>) -> R
): T = takeSubContHere(deleteDelimiter) {
  resumeWith(runCatching { body(it) })
}

@ResetDsl
@Suppress("UNCHECKED_CAST")
public suspend fun <T, R> Prompt<R>.takeSubContStackSafe(
  deleteDelimiter: Boolean = true, body: suspend (SubCont<T, R>) -> R
): T = takeSubContHere(deleteDelimiter) {
  body.startCoroutine(it, this)
}

@PublishedApi
internal fun Prompt<*>.preAbort(deleteDelimiter: Boolean) {
  DevNullList.unwind(this)
  if (deleteDelimiter) deleteDelimiter()
}

@ResetDsl
public inline fun <R> Prompt<R>.abort(deleteDelimiter: Boolean = false, value: () -> R): Nothing {
  preAbort(deleteDelimiter)
  resumeWith(runCatching(value))
  throw AbortException
}

@ResetDsl
public inline fun <R> Prompt<R>.abort0(value: () -> R): Nothing =
  abort(deleteDelimiter = true, value)

@PublishedApi
internal data class Hole<R>(val prompt: Prompt<R>?, val continuation: Continuation<R>)

private val pStack: MutableList<Hole<*>> = mutableListOf()

private fun peekStack(): Hole<*> = pStack.lastOrNull() ?: error("No prompt set")
private fun popStack(): Hole<*> = pStack.removeLastOrNull() ?: error("No prompt set")

@PublishedApi
internal fun pushStack(hole: Hole<*>) {
  pStack.add(hole)
}

private fun pushAllStack(holes: List<Hole<*>>) = pStack.addAll(holes)

@PublishedApi
internal fun deleteDelimiter(): Unit = pushStack(Hole(null, popStack().continuation))

@ResetDsl
public val <R> Prompt<R>.isSet: Boolean get() = pStack.any { it.prompt === this }

private object DevNullList : AbstractMutableList<Any?>() {
  override val size: Int get() = 0
  override fun get(index: Int): Any? = throw IndexOutOfBoundsException()
  override fun add(index: Int, element: Any?) {
    check(index == 0)
  }

  override fun removeAt(index: Int): Any? = throw IndexOutOfBoundsException()
  override fun set(index: Int, element: Any?): Any? = throw IndexOutOfBoundsException()
}

@PublishedApi
internal tailrec fun MutableList<in Hole<*>>.unwind(prompt: Prompt<*>) {
  val item = peekStack()
  if (item.prompt !== prompt) {
    popStack()
    add(0, item)
    unwind(prompt)
  }
}

public class Prompt<R> : Continuation<R> {
  override val context: CoroutineContext get() = peekStack().continuation.context

  @Suppress("UNCHECKED_CAST")
  override fun resumeWith(result: Result<R>) {
    if (result.exceptionOrNull() == AbortException) return
    val (prompt, continuation) = popStack() as Hole<R>
    check(prompt === this || prompt == null)
    continuation.resumeWith(result)
  }
}

@PublishedApi
internal object AbortException : CancellationException("Abort")