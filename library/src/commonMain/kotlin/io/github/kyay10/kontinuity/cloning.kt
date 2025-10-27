package io.github.kyay10.kontinuity

import io.github.kyay10.kontinuity.Frames.Companion.resumeWithImpl
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmField
import kotlin.jvm.JvmInline

public expect class StackTraceElement
public expect interface CoroutineStackFrame {
  public val callerFrame: CoroutineStackFrame?
  public fun getStackTraceElement(): StackTraceElement?
}

@PublishedApi
internal expect val Continuation<*>.completion: Continuation<*>?

@PublishedApi
internal expect fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T>

@PublishedApi
internal expect fun <T> Continuation<T>.invokeSuspend(result: Result<T>): Any?

public sealed class SplitCont<in Start>(@JvmField @PublishedApi internal val realContext: CoroutineContext) :
  CoroutineContext,
  Continuation<Start>, CoroutineStackFrame {
  final override val context: CoroutineContext get() = this

  final override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
    realContext.fold(initial, operation)

  final override fun plus(context: CoroutineContext): CoroutineContext = realContext.plus(context)

  final override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext =
    realContext.minusKey(key)

  final override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
    realContext[key]
}

// TODO use something other than Pair
@PublishedApi
internal fun <Start, P> Frames<Start>.splitAt(p: Prompt<P>): Pair<Frames<P>, Segment<Start, P>> {
  val delimiter = p.cont!!
  return delimiter.rest to Segment<Start, P>(delimiter).also { it.start = this }
}

@PublishedApi
internal data class EmptyCont<Start>(@JvmField val underlying: Continuation<Start>) :
  SplitCont<Start>(underlying.context) {
  override val callerFrame: CoroutineStackFrame? = underlying as? CoroutineStackFrame
  override fun getStackTraceElement(): StackTraceElement? = null
  override fun resumeWith(result: Result<Start>) = underlying.resumeWith(result)
}

@PublishedApi
@JvmInline
internal value class Frames<in Start>(val frames: Continuation<Start>) {
  val rest: SplitCont<*> get() = if (frames is FramesCont<*, *>) frames.rest else frames.context as SplitCont<*>

  fun copy(rest: Segmentable<*, *>): Frames<Start> =
    Frames(FramesCont(frames, rest))

  companion object {
    private inline fun <T> Continuation<T>.invokeSuspend(result: Result<T>, onSuspend: () -> Nothing): Result<Any?> =
      runCatching({ invokeSuspend(result) }, onSuspend)

    @Suppress("UNCHECKED_CAST")
    tailrec fun <Start> Frames<Start>.resumeWithImpl(result: Result<Start>) {
      val frames = frames
      // This loop unrolls recursion in current.resumeWith(param) to make saner and shorter stack traces on resume
      var current = frames as Continuation<Any?>
      var param: Result<Any?> = result
      if (frames is FramesCont<Any?, *>) {
        current = frames.cont
        while (true) {
          var completion =
            current.completion as? Continuation<Any?> ?: error("Not a compiler generated continuation $current")
          when (completion) {
            is FramesCont<Any?, *> -> completion = completion.cont
            is EmptyCont -> error("EmptyCont found as parent for copied frames")
            is Segmentable<Any?, *> -> {
              val rest = frames.rest as Segmentable<Any?, *>
              // top-level completion reached -- invoke and return
              val outcome = current.copy(rest).invokeSuspend(param) { return }
              return rest.underflow().resumeWithImpl(outcome)
            }
          }
          // Optimized by only setting it upon suspension.
          // This is safe only if no one accesses cont in between
          // That seems to be the case due to trampolining.
          // Note to self: if any weird behavior happens, uncomment this line
          //cont = completion
          val outcome = current.copy(frames).invokeSuspend(param) {
            frames.cont = completion
            return
          }
          //releaseIntercepted() // this state machine instance is terminating
          // unrolling recursion via loop
          current = completion
          param = outcome
        }
      } else while (true) {
        // Use normal resumption when faced with a non-compiler-generated continuation
        val completion = current.completion as? Continuation<Any?> ?: return current.resumeWith(param)
        val outcome = current.invokeSuspend(param) { return }
        //releaseIntercepted() // this state machine instance is terminating
        when (completion) {
          is EmptyCont -> return completion.resumeWith(outcome)
          is FramesCont<Any?, *> -> return Frames(completion).resumeWithImpl(outcome)
          is Segmentable<Any?, *> -> return completion.underflow().resumeWithImpl(outcome)

          else -> {
            // unrolling recursion via loop
            current = completion
            param = outcome
          }
        }
      }
    }
  }
}

@PublishedApi
internal class FramesCont<in Start, Last> internal constructor(
  cont: Continuation<Start>,
  @JvmField val rest: Segmentable<Last, *>,
) : Continuation<Start>, CoroutineStackFrame {
  @Suppress("UNCHECKED_CAST")
  @PublishedApi
  internal var cont: Continuation<Any?> = if (cont is FramesCont<Start, *>) cont.cont else cont as Continuation<Any?>
  override val context: CoroutineContext get() = rest

  override val callerFrame: CoroutineStackFrame? get() = cont as? CoroutineStackFrame
  override fun getStackTraceElement(): StackTraceElement? = null
  override fun resumeWith(result: Result<Start>) {
    if (result.exceptionOrNull() !== SuspendedException) Frames(this).resumeWithImpl(result)
  }
}

public class Prompt<Start> @PublishedApi internal constructor() {
  @PublishedApi
  internal var cont: PromptCont<Start>? = null
}

public class PromptCont<Start> @PublishedApi internal constructor(
  @JvmField @PublishedApi internal val prompt: Prompt<Start>,
  context: CoroutineContext,
) : Segmentable<Start, Start>(context) {
  override fun copy(context: CoroutineContext) = PromptCont(prompt, context)
  override fun invalidateSingle(): Boolean {
    val prompt = prompt
    if (prompt.cont === this) {
      prompt.cont = null
      return true
    }
    return false
  }

  override fun revalidateSingle(): Boolean {
    val prompt = prompt
    val promptsCont = prompt.cont
    if (promptsCont !== this) {
      promptsCont?.invalidate()
      prompt.cont = this
      return true
    }
    return false
  }

  @PublishedApi
  override fun underflow(): Frames<Start> = rest.also { prompt.cont = null }
}

public class Reader<S> @PublishedApi internal constructor(@PublishedApi @JvmField internal val fork: S.() -> S) {
  @PublishedApi
  internal var cont: ReaderT<S, *>? = null
  public fun ask(): S = cont!!.ask()
}

public class ReaderT<S, Start> @PublishedApi internal constructor(
  @PublishedApi internal val reader: Reader<S>,
  context: CoroutineContext,
  @PublishedApi @JvmField internal var state: S,
  private var forkOnFirstRead: Boolean = false,
) : Segmentable<Start, Start>(context) {
  override fun copy(context: CoroutineContext) = ReaderT<_, Start>(reader, context, state, forkOnFirstRead = true)
  override fun invalidateSingle(): Boolean {
    val reader = reader
    return if (reader.cont === this) {
      reader.cont = null
      true
    } else false
  }

  override fun revalidateSingle(): Boolean {
    val reader = reader
    val readersCont = reader.cont
    if (readersCont !== this) {
      readersCont?.invalidate()
      reader.cont = this
      return true
    }
    return false
  }

  override fun underflow() = rest.also { reader.cont = null }

  internal fun ask(): S {
    if (forkOnFirstRead) {
      forkOnFirstRead = false
      state = reader.fork(state)
    }
    return state
  }
}

@PublishedApi
internal class UnderCont<RealStart, Start>(
  val captured: Segment<RealStart, Start>,
  context: CoroutineContext,
  val shouldCopy: Boolean = false
) : Segmentable<RealStart, Start>(context) {
  override fun copy(context: CoroutineContext) = UnderCont(captured, context, shouldCopy = true)
  override fun invalidateSingle() = true
  override fun revalidateSingle() = true
  override fun underflow() = captured.copyIf(shouldCopy, realContext) prependTo rest
}

internal infix fun <Start, End> Segment<Start, End>.prependTo(stack: Frames<End>) = start.also {
  delimiter.revalidate()
  delimiter.rest = stack
}

// Expects that delimiter loops back to itself
@JvmInline
@PublishedApi
internal value class Segment<Start, End>(
  @JvmField val delimiter: PromptCont<End>,
) {
  @Suppress("UNCHECKED_CAST")
  var start
    get() = delimiter.rest as Frames<Start>
    set(value) {
      delimiter.rest = value as Frames<End>
    }

  fun copyIf(shouldCopy: Boolean, context: CoroutineContext) = if (shouldCopy) delimiter.copy(context).apply {
    delimiter.rest.copy<_, _, Any?, Any?>(delimiter, this, this, context)
  }.let(::Segment) else this
}

@Suppress("UNCHECKED_CAST")
private tailrec fun <Start, P, End, SuperEnd> Frames<Start>.copy(
  delimiter: PromptCont<P>,
  newDelimiter: PromptCont<P>,
  into: Segmentable<*, Start>,
  context: CoroutineContext,
  rest: Segmentable<End, SuperEnd> = (this.rest as? Segmentable<*, *>
    ?: error("EmptyCont found when copying: ${this.rest}")) as Segmentable<End, SuperEnd>,
) {
  if (rest === delimiter) { // End == P
    newDelimiter as PromptCont<End>
    into.rest = copy(newDelimiter)
    return
  }
  val cont = rest.copy(context)
  into.rest = copy(cont)
  return rest.rest.copy<_, _, Any?, Any?>(delimiter, newDelimiter, into = cont, context)
}

private tailrec fun <T> SplitCont<T>.revalidate(): Unit = when (this) {
  is EmptyCont<T> -> error("EmptyCont found in segment")
  is Segmentable<T, *> if revalidateSingle() -> rest.rest.revalidate()
  is Segmentable<T, *> -> {}
}

private tailrec fun <T> SplitCont<T>.invalidate(): Unit = when (this) {
  is EmptyCont -> error("Reentrant resumptions are not supported")
  is Segmentable<T, *> if invalidateSingle() -> rest.rest.invalidate()
  is Segmentable<T, *> -> {}
}

public sealed class Segmentable<in Start, Rest>(context: CoroutineContext) : SplitCont<Start>(context) {
  @PublishedApi
  internal var _rest: Continuation<Rest>? = null
  @PublishedApi
  internal inline var rest: Frames<Rest>
    get() = Frames(_rest ?: error("Segmentable used before being linked into Frames"))
    set(value) { _rest = value.frames }
  final override val callerFrame: CoroutineStackFrame? get() = rest.frames as? CoroutineStackFrame
  final override fun getStackTraceElement(): StackTraceElement? = null
  internal abstract fun copy(context: CoroutineContext): Segmentable<Start, Rest>
  internal abstract fun invalidateSingle(): Boolean
  internal abstract fun revalidateSingle(): Boolean
  internal abstract fun underflow(): Frames<Start>

  final override fun resumeWith(result: Result<Start>) {
    if (result.exceptionOrNull() !== SuspendedException) underflow().resumeWithImpl(result)
  }
}

@PublishedApi
internal fun <R> collectStack(continuation: Continuation<R>): Frames<R> = Frames(continuation)