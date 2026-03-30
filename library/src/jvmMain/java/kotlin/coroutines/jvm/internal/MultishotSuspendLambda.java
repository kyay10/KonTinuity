package kotlin.coroutines.jvm.internal;

import io.github.kyay10.kontinuity.internal.MultishotContinuation;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("unused")
public abstract class MultishotSuspendLambda extends SuspendLambda implements MultishotContinuation<Object> {
    public MultishotSuspendLambda(int arity, @Nullable Continuation<Object> completion) {
        super(arity, completion);
    }

    public MultishotSuspendLambda(int arity) {
        super(arity);
    }
}
