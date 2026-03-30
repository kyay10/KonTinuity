package kotlin.coroutines.jvm.internal;

import io.github.kyay10.kontinuity.internal.MultishotContinuation;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("unused")
public abstract class MultishotRestrictedSuspendLambda extends RestrictedSuspendLambda implements MultishotContinuation<Object> {
    public MultishotRestrictedSuspendLambda(int arity, @Nullable Continuation<Object> completion) {
        super(arity, completion);
    }

    public MultishotRestrictedSuspendLambda(int arity) {
        super(arity);
    }
}
