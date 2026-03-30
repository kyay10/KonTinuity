package kotlin.coroutines.jvm.internal;

import io.github.kyay10.kontinuity.internal.MultishotContinuation;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("unused")
public abstract class MultishotRestrictedContinuationImpl extends RestrictedContinuationImpl implements MultishotContinuation<Object> {
    public MultishotRestrictedContinuationImpl(@Nullable Continuation<Object> completion) {
        super(completion);
    }
}
