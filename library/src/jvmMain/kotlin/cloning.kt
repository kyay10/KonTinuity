import kotlin.coroutines.Continuation

private val UNSAFE = Class.forName("sun.misc.Unsafe")
  .getDeclaredField("theUnsafe")
  .apply { isAccessible = true }
  .get(null) as sun.misc.Unsafe

private val baseContClass = Class.forName("kotlin.coroutines.jvm.internal.BaseContinuationImpl")

private tailrec fun <T> copyDeclaredFields(obj: T, copy: T, clazz: Class<out T>) {
  for (field in clazz.declaredFields) {
    if (field.name == "intercepted") continue
    field.isAccessible = true
    val v = field.get(obj)

    field.set(
      copy, if (v === obj) {
        copy
      } else if (v is Continuation<*> && field.name == "completion") {
        v.clone()
      } else {
        v
      }
    )
  }
  val superclass = clazz.superclass
  if (superclass != null) copyDeclaredFields(obj, copy, superclass)
}

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal actual fun <T> Continuation<T>.clone(): Continuation<T> = if (baseContClass.isInstance(this)){
  val clazz = javaClass
  val copy = UNSAFE.allocateInstance(clazz) as Continuation<T>
  copyDeclaredFields(this, copy, clazz)
  copy
} else this