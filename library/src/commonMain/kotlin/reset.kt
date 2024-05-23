import androidx.compose.runtime.*
import arrow.AutoCloseScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@DslMarker
public annotation class ResetDsl

public data class SubCont<T, R> internal constructor(
  @PublishedApi internal val ekFragment: Continuation<T>, private val prompt: Prompt<R>, private val subchain: List<Hole<*>>
) {
  @PublishedApi
  internal fun push(k: Continuation<R>, isDelimiting: Boolean = false) {
    pushStack(Hole(prompt.takeIf { isDelimiting }, k))
    pushAllStack(subchain)
  }

  @PublishedApi
  internal inline operator fun invoke(value: () -> T, k: Continuation<R>, isDelimiting: Boolean = false) {
    push(k, isDelimiting)
    ekFragment.resumeWith(runCatching(value))
  }
}

public suspend fun <R> AutoCloseScope.pushPrompt(prompt: Prompt<R>, body: @Composable Prompt<R>.() -> R): R =
  suspendCoroutine { k ->
    pushStack(Hole(prompt, k))
    recomposer(k.context).startSuspendingComposition(prompt::resumeWith) { body(prompt) }
  }

@Composable
public fun <R> Prompt<R>.pushPrompt(body: @Composable Prompt<R>.() -> R): R = suspendComposition { k ->
  pushStack(Hole(this@pushPrompt, k))
  startSuspendingComposition(::resumeWith) { body() }
}

private fun deleteDelimiter() = pushStack(Hole(null, popStack().continuation))

@Composable
private fun <T, R> Prompt<R>.takeSubContHere(
  deleteDelimiter: Boolean = true, body: Recomposer.(SubCont<T, R>) -> Unit
): T = suspendComposition { k ->
  val subchain = buildList { unwind(this@takeSubContHere) }
  // TODO: this seems dodgy
  if (deleteDelimiter) deleteDelimiter()
  body(SubCont(k, this@takeSubContHere, subchain))
}

@Composable
public fun <T, R> Prompt<R>.takeSubCont(deleteDelimiter: Boolean = true, body: @Composable (SubCont<T, R>) -> R): T =
  takeSubContHere(deleteDelimiter) @DontMemoize { startSuspendingComposition(::resumeWith) { body(it) } }

@OptIn(InternalCoroutinesApi::class)
@Composable
public fun <T, R> Prompt<R>.takeSubContS(deleteDelimiter: Boolean = true, body: suspend (SubCont<T, R>) -> R): T =
  takeSubContHere(deleteDelimiter) @DontMemoize {
    body.startCoroutineUnintercepted(it, this@takeSubContS)
  }

private fun <R, T> (suspend R.() -> T).startCoroutineUnintercepted(receiver: R, completion: Continuation<T>) {
  try {
    val res = startCoroutineUninterceptedOrReturn(receiver, completion)
    if (res != COROUTINE_SUSPENDED) completion.resume(res as T)
  } catch (e: Throwable) {
    completion.resumeWithException(e)
  }
}

public fun <R> Prompt<R>.abort(value: R, deleteDelimiter: Boolean = false): Nothing {
  unwindAbort(this)
  if (deleteDelimiter) deleteDelimiter()
  resume(value)
  throw AbortException
}

@Composable
public inline fun <T, R> SubCont<T, R>.pushSubCont(crossinline value: () -> T): R = suspendComposition { k ->
  invoke(value, k, isDelimiting = false)
}

@Composable
public inline fun <T, R> SubCont<T, R>.pushDelimSubCont(crossinline value: () -> T): R = suspendComposition { k ->
  invoke(value, k, isDelimiting = true)
}

public suspend inline fun <T, R> SubCont<T, R>.pushDelimSubContS(crossinline value: () -> T): R = suspendCoroutine { k ->
  invoke(value, k, isDelimiting = true)
}

private val pStack: MutableList<Hole<*>> = mutableListOf()

private fun peekStack(): Hole<*> = pStack.lastOrNull() ?: error("No prompt set")
private fun popStack(): Hole<*> = pStack.removeLastOrNull() ?: error("No prompt set")
private fun pushStack(hole: Hole<*>) = pStack.add(hole)
private fun pushAllStack(holes: List<Hole<*>>) = pStack.addAll(holes)
public val <R> Prompt<R>.isSet: Boolean get() = pStack.any { it.prompt === this }

private tailrec fun MutableList<Hole<*>>.unwind(prompt: Prompt<*>) {
  val item = peekStack()
  if (item.prompt !== prompt) {
    popStack()
    add(0, item)
    unwind(prompt)
  }
}

private tailrec fun unwindAbort(prompt: Prompt<*>) {
  val item = peekStack()
  if (item.prompt !== prompt) {
    popStack()
    unwindAbort(prompt)
  }
}

internal data class Hole<R>(val prompt: Prompt<R>?, val continuation: Continuation<R>)

@Suppress("EqualsOrHashCode")
@Stable
public class Prompt<R> : Continuation<R> {
  override fun equals(other: Any?): Boolean = false

  override val context: CoroutineContext get() = peekStack().continuation.context

  @Suppress("UNCHECKED_CAST")
  override fun resumeWith(result: Result<R>) {
    if (result.exceptionOrNull() == AbortException) return
    val (prompt, continuation) = popStack() as Hole<R>
    check(prompt === this || prompt == null)
    continuation.resumeWith(result)
  }
}

private object AbortException : CancellationException("Abort")