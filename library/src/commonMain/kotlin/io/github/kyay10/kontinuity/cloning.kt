package io.github.kyay10.kontinuity

import io.github.kyay10.kontinuity.Frames.Copied.Companion.resumeWithImpl
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmField
import kotlin.jvm.JvmInline

public expect class StackTraceElement
public expect interface CoroutineStackFrame {
  public val callerFrame: CoroutineStackFrame?
  public fun getStackTraceElement(): StackTraceElement?
}

internal expect val Continuation<*>.completion: Continuation<*>?
internal expect fun <T> Continuation<T>.copy(completion: Continuation<*>, context: CoroutineContext): Continuation<T>
internal expect fun <T> Continuation<T>.invokeSuspend(result: Result<T>): Any?

public sealed class SplitCont<in Start>(@JvmField @PublishedApi internal val realContext: CoroutineContext) :
  CoroutineContext, Continuation<Start>, CoroutineStackFrame {
  final override val context: CoroutineContext get() = this

  protected abstract fun invalidateSingle(): SplitCont<*>?
  protected abstract fun revalidateSingle(): SplitCont<*>?

  final override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
    realContext.fold(initial, operation)

  final override fun plus(context: CoroutineContext): CoroutineContext = realContext.plus(context)

  final override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext =
    realContext.minusKey(key)

  final override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
    realContext[key]

  internal companion object {
    tailrec fun <T> SplitCont<T>.revalidate() {
      revalidateSingle()?.revalidate()
    }

    tailrec fun <T> SplitCont<T>.invalidate() {
      invalidateSingle()?.invalidate()
    }
  }
}

internal inline fun <Start> SplitCont<Start>.asSegmentableOrError(): Segmentable<Start, *> =
  this as Segmentable<Start, *> // eventually add a message here that this means a reentrant resumption happened

@PublishedApi
internal inline fun <Start, P, R> Frames<Start>.splitAt(
  p: Prompt<P>,
  thisRest: SplitCont<*>,
  block: (Frames<P>, SplitCont<*>, Segmentable.Segment<Start, P>) -> R
): R {
  val delimiter = p.cont!!
  return block(delimiter.rest, delimiter.restRest, Segmentable.Segment(delimiter, this, thisRest))
}

internal data class EmptyCont<Start>(@JvmField val underlying: Continuation<Start>) :
  SplitCont<Start>(underlying.context) {
  override fun revalidateSingle() = error("EmptyCont found in segment")
  override fun invalidateSingle() = error("Reentrant resumptions are not supported")

  override val callerFrame: CoroutineStackFrame? = underlying as? CoroutineStackFrame
  override fun getStackTraceElement(): StackTraceElement? = null
  override fun resumeWith(result: Result<Start>) = underlying.resumeWith(result)
}

@PublishedApi
@JvmInline
internal value class Frames<in Start>(val frames: Continuation<Start>) {
  fun copy(rest: Segmentable<*, *>) = Frames(Copied(frames, rest))
  fun resumeWith(result: Result<Start>) = resumeWithImpl(result)

  private class Copied<in Start, Last>(
    cont: Continuation<Start>,
    @JvmField val rest: Segmentable<Last, *>,
  ) : Continuation<Start>, CoroutineStackFrame {
    @Suppress("UNCHECKED_CAST")
    private var cont: Continuation<Any?> = if (cont is Copied<Start, *>) cont.cont else cont as Continuation<Any?>
    override val context: CoroutineContext get() = rest

    override val callerFrame: CoroutineStackFrame? get() = cont as? CoroutineStackFrame
    override fun getStackTraceElement(): StackTraceElement? = null
    override fun resumeWith(result: Result<Start>) {
      if (result.exceptionOrNull() !== SuspendedException) Frames(this).resumeWithImpl(result)
    }

    companion object {
      private inline fun <T> Continuation<T>.invokeSuspend(result: Result<T>, onSuspend: () -> Nothing): Result<Any?> =
        runCatching({ invokeSuspend(result) }, onSuspend)

      @Suppress("UNCHECKED_CAST")
      tailrec fun <Start> Frames<Start>.resumeWithImpl(result: Result<Start>) {
        val frames = frames
        // This loop unrolls recursion in current.resumeWith(param) to make saner and shorter stack traces on resume
        var current = frames as Continuation<Any?>
        var param: Result<Any?> = result
        if (frames is Copied<Any?, *>) {
          val framesRest = frames.rest as Segmentable<Any?, *>
          current = frames.cont
          while (true) {
            var completion =
              current.completion as? Continuation<Any?> ?: error("Not a compiler generated continuation $current")
            when (completion) {
              is Copied<Any?, *> -> completion = completion.cont
              is EmptyCont -> error("EmptyCont found as parent for copied frames")
              is Segmentable<Any?, *> -> {
                // top-level completion reached -- invoke and return
                val outcome = current.copy(framesRest, framesRest).invokeSuspend(param) { return }
                return framesRest.underflow().resumeWithImpl(outcome)
              }
            }
            // Optimized by only setting it upon suspension.
            // This is safe only if no one accesses cont in between
            // That seems to be the case due to trampolining.
            // Note to self: if any weird behavior happens, uncomment this line
            //frames.cont = completion
            val outcome = current.copy(frames, framesRest).invokeSuspend(param) {
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
            is Copied<Any?, *> -> return Frames(completion).resumeWithImpl(outcome)
            is Segmentable<Any?, *> -> return completion.underflow().resumeWithImpl(outcome)
          }
          // unrolling recursion via loop
          current = completion
          param = outcome
        }
      }
    }
  }
}

public class Prompt<Start> @PublishedApi internal constructor() {
  @PublishedApi
  @JvmField
  internal var cont: Cont<Start>? = null

  public class Cont<Start> private constructor(
    private val prompt: Prompt<Start>,
    context: CoroutineContext,
    _rest: Continuation<Start>?,
    _restRest: SplitCont<*>?,
    unit: Unit = Unit,
  ) : Segmentable<Start, Start>(context, _rest, _restRest) {
    @PublishedApi
    internal constructor(
      prompt: Prompt<Start>,
      context: CoroutineContext,
      rest: Frames<Start>,
      restRest: SplitCont<*>
    ) : this(prompt, context, _rest = rest.frames, _restRest = restRest)

    init {
      // I believe this is safe to do. Reason being:
      // Constructor is called when creating a new prompt, and when copying
      // when creating new prompt, obviously there's no other to invalidate
      // when copying, we're going thru the entire old continuation anyway, so we're automatically invalidating it
      prompt.cont = this
    }

    override fun copy(context: CoroutineContext) = Cont(prompt, context, null, null)
    override fun invalidateSingle(): SplitCont<*>? {
      val prompt = prompt
      if (prompt.cont === this) {
        prompt.cont = null
        return this@Cont.restRest
      }
      return null
    }

    override fun revalidateSingle(): SplitCont<*>? {
      val prompt = prompt
      val promptsCont = prompt.cont
      if (promptsCont !== this) {
        promptsCont?.invalidate()
        prompt.cont = this
        return this@Cont.restRest
      }
      return null
    }

    @PublishedApi
    override fun underflow(): Frames<Start> = this@Cont.rest.also { prompt.cont = null }
  }
}

public class Reader<S> @PublishedApi internal constructor(private val fork: S.() -> S) {
  private var cont: Cont<S, *>? = null
  public fun ask(): S = cont!!.ask()

  public class Cont<S, Start> private constructor(
    private val reader: Reader<S>,
    context: CoroutineContext,
    private var state: S,
    private var forkOnFirstRead: Boolean = false,
    _rest: Continuation<Start>?,
    _restRest: SplitCont<*>?,
  ) : Segmentable<Start, Start>(context, _rest, _restRest) {
    @PublishedApi
    internal constructor(
      reader: Reader<S>,
      context: CoroutineContext,
      state: S,
      rest: Frames<Start>,
      restRest: SplitCont<*>
    ) : this(reader, context, state, forkOnFirstRead = false, rest.frames, restRest)

    init {
      // See note in Cont `init`
      reader.cont = this
    }

    override fun copy(context: CoroutineContext) =
      Cont<_, Start>(reader, context, state, forkOnFirstRead = true, null, null)

    override fun invalidateSingle(): SplitCont<*>? {
      val reader = reader
      if (reader.cont === this) {
        reader.cont = null
        return restRest
      }
      return null
    }

    override fun revalidateSingle(): SplitCont<*>? {
      val reader = reader
      val readersCont = reader.cont
      if (readersCont !== this) {
        readersCont?.invalidate()
        reader.cont = this
        return restRest
      }
      return null
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
}

@PublishedApi
internal class UnderCont<RealStart, Start> private constructor(
  private val captured: Segment<RealStart, Start>,
  context: CoroutineContext,
  private val shouldCopy: Boolean = false,
  _rest: Continuation<Start>?,
  _restRest: SplitCont<*>?,
) : Segmentable<RealStart, Start>(context, _rest, _restRest) {
  @PublishedApi
  internal constructor(
    captured: Segment<RealStart, Start>,
    context: CoroutineContext,
    rest: Frames<Start>,
    restRest: SplitCont<*>,
    shouldCopy: Boolean = false,
  ) : this(captured, context, shouldCopy, rest.frames, restRest)

  override fun copy(context: CoroutineContext) = UnderCont(captured, context, shouldCopy = true, null, null)
  override fun invalidateSingle() = restRest
  override fun revalidateSingle() = restRest
  override fun underflow() = captured.copyIf(shouldCopy, realContext).prependTo(rest, restRest)
}

public sealed class Segmentable<in Start, Rest>(
  context: CoroutineContext,
  @PublishedApi @JvmField internal var _rest: Continuation<Rest>?,
  @PublishedApi @JvmField internal var _restRest: SplitCont<*>?,
) : SplitCont<Start>(context) {
  @PublishedApi
  internal inline val rest: Frames<Rest>
    get() = Frames(_rest ?: error("Segmentable used before being linked into Frames"))

  internal inline fun setRest(rest: Frames<Rest>, restRest: SplitCont<*>) {
    _rest = rest.frames
    _restRest = restRest
  }

  @PublishedApi
  internal inline val restRest: SplitCont<*> get() = _restRest!!

  final override val callerFrame: CoroutineStackFrame? get() = rest.frames as? CoroutineStackFrame
  final override fun getStackTraceElement(): StackTraceElement? = null
  internal abstract fun copy(context: CoroutineContext): Segmentable<Start, Rest>
  internal abstract fun underflow(): Frames<Start>

  final override fun resumeWith(result: Result<Start>) {
    if (result.exceptionOrNull() !== SuspendedException) underflow().resumeWith(result)
  }

  internal companion object {
    @Suppress("UNCHECKED_CAST")
    tailrec fun <Start, P, End, SuperEnd> Segmentable<*, Start>.copyFrames(
      delimiter: Prompt.Cont<P>,
      newDelimiter: Prompt.Cont<P>,
      into: Segmentable<*, Start>,
      context: CoroutineContext,
      restRest: Segmentable<End, SuperEnd> = this.restRest.asSegmentableOrError() as Segmentable<End, SuperEnd>,
    ) {
      if (restRest === delimiter) { // End == P
        newDelimiter as Prompt.Cont<End>
        into.setRest(rest.copy(newDelimiter), newDelimiter)
        return
      }
      val cont = restRest.copy(context)
      into.setRest(rest.copy(cont), cont)
      return restRest.copyFrames<_, _, Any?, Any?>(delimiter, newDelimiter, into = cont, context)
    }
  }

  // Expects that delimiter loops back to itself
  @JvmInline
  @PublishedApi
  internal value class Segment<Start, End>(private val delimiter: Prompt.Cont<End>) {
    constructor(delimiter: Prompt.Cont<End>, start: Frames<Start>, startRest: SplitCont<*>) : this(delimiter) {
      @Suppress("UNCHECKED_CAST")
      this.delimiter as Prompt.Cont<Start>
      this.delimiter.setRest(start, startRest)
    }

    fun copyIf(shouldCopy: Boolean, context: CoroutineContext) = if (shouldCopy) delimiter.copy(context).apply {
      delimiter.copyFrames<_, _, Any?, Any?>(delimiter, this, this, context)
    }.let(::Segment) else this

    @Suppress("UNCHECKED_CAST")
    fun prependTo(stack: Frames<End>, stackRest: SplitCont<*>) = (delimiter.rest as Frames<Start>).also {
      delimiter.revalidate()
      delimiter.setRest(stack, stackRest)
    }
  }
}

@PublishedApi
internal inline fun <T, R> collectStack(continuation: Continuation<T>, block: (Frames<T>, SplitCont<*>) -> R): R =
  block(Frames(continuation), continuation.context as SplitCont<*>)