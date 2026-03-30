package kotlin.coroutines.jvm.internal;

import io.github.kyay10.kontinuity.internal.MultishotContinuation;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("unused")
public abstract class MultishotContinuationImpl extends ContinuationImpl implements MultishotContinuation<Object> {
    public MultishotContinuationImpl(@Nullable Continuation<Object> completion, @Nullable CoroutineContext _context) {
        super(completion, _context);
    }

    public MultishotContinuationImpl(@Nullable Continuation<Object> completion) {
        super(completion);
    }
}
