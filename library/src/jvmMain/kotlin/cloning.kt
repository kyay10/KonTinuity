import kotlin.coroutines.Continuation

private val UNSAFE = Class.forName("sun.misc.Unsafe")
  .getDeclaredField("theUnsafe")
  .apply { isAccessible = true }
  .get(null) as sun.misc.Unsafe

private val baseContClass = Class.forName("kotlin.coroutines.jvm.internal.BaseContinuationImpl")

private tailrec fun <T> copyDeclaredFields(obj: T, copy: T, clazz: Class<out T>, replacePromptContinuation: Continuation<*>?, prompt: Prompt<*>?) {
  for (field in clazz.declaredFields) {
    if (field.name == "intercepted") continue
    field.isAccessible = true
    val v = field.get(obj)

    field.set(
      copy, if (v === obj) {
        copy
      } else if (v is Continuation<*> && field.name == "completion") {
        v.clone(replacePromptContinuation, prompt)
      } else {
        v
      }
    )
  }
  val superclass = clazz.superclass
  if (superclass != null) copyDeclaredFields(obj, copy, superclass, replacePromptContinuation, prompt)
}

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal actual fun <T> Continuation<T>.clone(replacementPromptContinuation: Continuation<*>?, prompt: Prompt<*>?): Continuation<T> = if (baseContClass.isInstance(this)){
  val clazz = javaClass
  val copy = UNSAFE.allocateInstance(clazz) as Continuation<T>
  copyDeclaredFields(this, copy, clazz, replacementPromptContinuation, prompt)
  copy
} else if (this is Hole<*> && this.prompt === prompt) {
  replacementPromptContinuation as Continuation<T>
} else this