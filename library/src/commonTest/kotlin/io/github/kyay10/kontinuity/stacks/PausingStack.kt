package io.github.kyay10.kontinuity.stacks

interface PausingBlock<in local> {
  context(_: Locality<local2>)
  suspend operator fun <local2 : local> invoke(pause: suspend context(Locality<local2>) () -> Unit)
}

class PausingStack<local>(private val block: PausingBlock<local>) {
  private inner class WithMounted<mounted : local> {
    private val mount: StackMount<local, LocalFun1<Boolean, Nothing, *>, mounted, local> = StackMount()
    private var continuation: StackContinuation<LocalFun0<Nothing, *>, out mounted, mounted>? =
      mount.new(
        object : AfterFun<LocalFun1<Boolean, Nothing, *>, Unit, local> {
          context(_: Locality<environment>)
          override suspend fun <environment : local> invoke(
            environment: Local<LocalFun1<Boolean, Nothing, *>, environment>,
            o: Unit,
          ) = environment.fix()(true)
        }
      ) {
        block pause@{
          merge(
            object : MergeFun<Unit, mounted> {
              context(_: Locality<local2>, _: Raise<Unit, local2>)
              override suspend fun <local2 : mounted> invoke() =
                mount.suspend(
                  LocalFun0 { raise(Unit) },
                  object :
                    SuspendFun<
                      LocalFun1<Boolean, Nothing, *>,
                      StackContinuation<LocalFun0<Nothing, *>, local2, mounted>,
                      local,
                    > {
                    context(_: Locality<environment>)
                    override suspend fun <environment : local> invoke(
                      environment: Local<LocalFun1<Boolean, Nothing, *>, environment>,
                      c: StackContinuation<LocalFun0<Nothing, *>, local2, mounted>,
                    ): Nothing {
                      continuation = c
                      environment.fix()(false)
                    }
                  },
                )
            }
          )
        }
      }

    context(_: Locality<local2>)
    suspend fun <local2 : local> progress(): Boolean =
      merge(
        object : MergeFun<Boolean, local2> {
          context(_: Locality<local3>, _: Raise<Boolean, local3>)
          override suspend fun <local3 : local2> invoke(): Boolean =
            mount.resume(
              LocalFun1 { raise(it) },
              (continuation ?: return true).also { continuation = null },
              object : ResumeFun<LocalFun0<Nothing, *>, mounted> {
                context(_: Locality<resumption>)
                override suspend fun <resumption : mounted> invoke(
                  resumption: Local<LocalFun0<Nothing, *>, resumption>
                ) = resumption.fix()()
              },
            )
        }
      )
  }

  private val withMounted = WithMounted<local>()

  context(_: Locality<local2>)
  suspend fun <local2 : local> progress(): Boolean = withMounted.progress()
}
