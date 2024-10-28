package knitty.javaimpl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import clojure.lang.AFn;
import clojure.lang.ExceptionInfo;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.RT;
import clojure.lang.Util;
import manifold.deferred.IDeferred;
import manifold.deferred.IDeferredListener;
import manifold.deferred.IMutableDeferred;

@SuppressWarnings({"unused", "unchecked", "rawtypes"})
public class KDeferred
        extends AFn
        implements
        clojure.lang.IDeref,
        clojure.lang.IBlockingDeref,
        clojure.lang.IPending,
        clojure.lang.IReference,
        clojure.lang.IFn,
        IDeferred,
        IMutableDeferred,
        CompletionStageMixin
    {

    private final static class ErrBox implements Runnable {

        private static final VarHandle CONSUMED;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                CONSUMED = l.findVarHandle(ErrBox.class, "_consumed", Boolean.TYPE);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        final Object err;
        private volatile boolean _consumed;

        ErrBox(Object err) {
            this.err = err;
        }

        @Override
        public void run() {
            Object e = this.err;
            if (!isConsumed()) {
                this.consume();
                ELD_LEAKED_ERRORS.offer(e);
            }
        }

        public Object consume() {
            CONSUMED.setOpaque(this, true);
            return this.err;
        }

        public boolean isConsumed() {
            return (boolean) CONSUMED.getOpaque(this);
        }
    }

    abstract static class AListener {

        private final static VarHandle NEXT;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                NEXT = l.findVarHandle(AListener.class, "next", AListener.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        public AListener next;

        public abstract void success(Object x);
        public abstract void error(Object e);

        public final boolean casNext(AListener curNext, AListener newNext) {
            return NEXT.compareAndSet(this, curNext, newNext);
        }

        protected AListener() {}
    }

    private static final class Dl extends AListener {

        static final byte ACTIVE = 0;
        static final byte PENDING_CANCEL = 1;
        static final byte CANCELLED = 2;

        static final VarHandle CANCEL;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                CANCEL = l.findVarHandle(Dl.class, "cancel", Byte.TYPE);
            } catch (ReflectiveOperationException var1) {
                throw new ExceptionInInitializerError(var1);
            }
        }

        IDeferredListener ls;
        byte cancel;

        public Dl(IDeferredListener ls) {
            this.ls = ls;
        }

        @Override
        public void success(Object x) {
            if (((byte) CANCEL.getOpaque(this)) == PENDING_CANCEL) {
                CANCEL.setOpaque(this, CANCELLED);
                this.ls = null;
            } else {
                this.ls.onSuccess(x);
            }
        }

        @Override
        public void error(Object e) {
            if (((byte) CANCEL.getOpaque(this)) == PENDING_CANCEL) {
                CANCEL.setOpaque(this, CANCELLED);
                this.ls = null;
            } else {
                this.ls.onError(e);
            }
        }

        public boolean cancelled() {
            return ((byte) CANCEL.getOpaque(this)) == CANCELLED;
        }

        public boolean acquireForCancel() {
            return CANCEL.compareAndSet(this, ACTIVE, PENDING_CANCEL);
        }

        @Override
        public String toString() {
            return super.toString() + "[ls=" + Objects.toString(ls) + "]";
        }
    }

    private static final class Fn extends AListener {

        private final IFn onSucc;
        private final IFn onErr;

        public Fn(IFn onSucc, IFn onErr) {
            this.onSucc = onSucc;
            this.onErr = onErr;
        }

        @Override
        public void success(Object x) {
            if (onSucc != null) {
                this.onSucc.invoke(x);
            }
        }

        @Override
        public void error(Object e) {
            if (onErr != null) {
                this.onErr.invoke(e);
            }
        }

        @Override
        public String toString() {
            return super.toString() + "[onSucc=" + Objects.toString(onSucc) + ", onErr=" + Objects.toString(onErr) + "]";
        }
    }

    private final static class Bind extends AListener {

        private final KDeferred dest;
        private final IFn valFn; // non-null
        private final IFn errFn; // nullable

        private Bind(KDeferred dest, IFn valFn, IFn errFn) {
            this.dest = dest;
            this.valFn = valFn;
            this.errFn = errFn;
        }

        @Override
        public void success(Object x) {
            Object t;
            try {
                t = valFn.invoke(x);
            } catch (Throwable e) {
                dest.fireError(e, null);
                return;
            }
            if (t instanceof IDeferred) {
                dest.chain0((IDeferred) t, null);
            } else {
                dest.fireValue(t, null);
            }
        }

        @Override
        public void error(Object e) {
            if (errFn == null) {
                dest.fireError(e, null);
            } else {
                Object t;
                try {
                    t = errFn.invoke(e);
                } catch (Throwable e1) {
                    e1.addSuppressed(coerceError(e));
                    dest.fireError(e1);
                    return;
                }
                if (t instanceof IDeferred) {
                    dest.chain0((IDeferred) t, null);
                } else {
                    dest.fireValue(t, null);
                }
            }
        }

        @Override
        public String toString() {
            return String.format("#[Bind -> %s]", dest);
        }
    }

    private final static class BindEx extends AListener {

        private final KDeferred dest;
        private final IFn valFn; // non-null
        private final IFn errFn; // nullable
        private final Executor executor;

        private BindEx(KDeferred dest, IFn valFn, IFn errFn, Executor executor) {
            this.dest = dest;
            this.valFn = valFn;
            this.errFn = errFn;
            this.executor = executor;
        }

        @Override
        public void success(Object x) {
            this.executor.execute(() -> {
                Object t;
                try {
                    t = valFn.invoke(x);
                } catch (Throwable e) {
                    dest.fireError(e, null);
                    return;
                }
                if (t instanceof IDeferred) {
                    dest.chain0((IDeferred) t, null);
                } else {
                    dest.fireValue(t, null);
                }
            });
        }

        @Override
        public void error(Object e) {
            this.executor.execute(() -> {
                if (errFn == null) {
                    dest.fireError(e, null);
                } else {
                    Object t;
                    try {
                        t = errFn.invoke(e);
                    } catch (Throwable e1) {
                        e1.addSuppressed(coerceError(e));
                        dest.fireError(e1, null);
                        return;
                    }
                    if (t instanceof IDeferred) {
                        dest.chain0((IDeferred) t, null);
                    } else {
                        dest.fireValue(t, null);
                    }
                }
            });
        }
    }

    private static class Cdl extends AListener {
        private final CountDownLatch cdl;

        Cdl(CountDownLatch cdl) {
            this.cdl = cdl;
        }

        @Override
        public void success(Object x) {
            cdl.countDown();
        }

        @Override
        public void error(Object e) {
            cdl.countDown();
        }
    }

    private final static class Chain extends AListener {

        private final KDeferred kd;
        private final Object token;

        public Chain(KDeferred kd, Object token) {
            this.kd = kd;
            this.token = token;
        }

        @Override
        public void success(Object x) {
            kd.fireValue(x, token);
        }

        @Override
        public void error(Object x) {
            kd.fireError(x, token);
        }

        @Override
        public String toString() {
            return String.format("#[Chain -> %s]", kd);
        }
    }

    private final static class ChainValue extends AFn {

        private final KDeferred kd;
        private final Object token;

        public ChainValue(KDeferred kd, Object token) {
            this.kd = kd;
            this.token = token;
        }

        @Override
        public Object invoke(Object x) {
            kd.fireValue(x, token);
            return null;
        }
    }

    private final static class ChainError extends AFn {

        private final KDeferred kd;
        private final Object token;

        public ChainError(KDeferred kd, Object token) {
            this.kd = kd;
            this.token = token;
        }

        @Override
        public Object invoke(Object x) {
            kd.fireError(x, token);
            return null;
        }
    }

    private final static class Revoke extends AListener {

        private final KDeferred kd;
        private final IFn canceller;
        private final IFn errCallback;

        public Revoke(KDeferred kd, IFn canceller, IFn errCallback) {
            this.kd = kd;
            this.canceller = canceller;
            this.errCallback = errCallback;
        }

        @Override
        public void success(Object x) {
            if (!kd.realized()) {
                canceller.invoke();
            }
        }

        @Override
        public void error(Object x) {
            if (!kd.realized()) {
                canceller.invoke();
            }
            if (errCallback != null) {
                errCallback.invoke(x);
            }
        }
    }

    private final static class Tomb extends AListener {

        @Override
        public void success(Object x) {
            throw new IllegalStateException("incorrect usage of getRaw");
        }

        @Override
        public void error(Object e) {
            throw new IllegalStateException("incorrect usage of getRaw");
        }
    }

    public static final BlockingQueue<Object> ELD_LEAKED_ERRORS =
        new ArrayBlockingQueue<>(128);

    @SuppressWarnings("CallToPrintStackTrace")
    public static final Thread ELD_LOGGER = new Thread(() -> {
        while (!Thread.interrupted()) {
            Object e;
            try {
                e = ELD_LEAKED_ERRORS.take();
            } catch (InterruptedException ex) {
                return;
            }
            try {
                logWarn(e, "unconsumed deferred in error state");
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        }
    }, "knitty-error-leak-logger");

    static {
        ELD_LOGGER.setDaemon(true);
        ELD_LOGGER.start();
    }

    private static final Cleaner ELD_CLEANER =
        Cleaner.create(r -> new Thread(r, "knitty-error-leak-detector"));

    private static final Keyword ERROR_KW = Keyword.intern(null, "error");

    private static final VarHandle TOKEN;
    private static final VarHandle VALUE;
    private static final VarHandle OWNED;
    private static final VarHandle LHEAD;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            TOKEN = l.findVarHandle(KDeferred.class, "_token", Object.class);
            OWNED = l.findVarHandle(KDeferred.class, "_owned", Byte.TYPE);
            VALUE = l.findVarHandle(KDeferred.class, "_value", Object.class);
            LHEAD = l.findVarHandle(KDeferred.class, "_lhead", AListener.class);
        } catch (ReflectiveOperationException var1) {
            throw new ExceptionInInitializerError(var1);
        }
    }

    private static volatile IFn GET_EXECUTOR = new AFn() {
        @Override
        public Object invoke() {
            return clojure.lang.Agent.soloExecutor;
        };
    };

    private static volatile IFn LOG_EXCEPTION = new AFn() {

        @Override
        @SuppressWarnings("CallToPrintStackTrace")
        public Object invoke(Object err, Object msg) {
            if (err instanceof Throwable) {
                ((Throwable) err).printStackTrace();
            }
            return null;
        };
    };

    public static void setExceptionLogFn(IFn f) {
        LOG_EXCEPTION = f;
    }

    public static void setExecutorProviderFn(IFn f) {
        GET_EXECUTOR = f;
    }

    private final static Object MISS_VALUE = new ErrBox(new IllegalStateException("nooo"));
    static {
        Throwable t = (Throwable) ((ErrBox) MISS_VALUE).consume();
        t.setStackTrace(new StackTraceElement[0]);
    }
    private final static AListener LS_TOMB = new Tomb();

    private byte _owned;
    byte succeeded;
    private Object _value;
    private Object _token;
    private AListener _lhead;
    private IPersistentMap meta;

    private void detectLeakedError(ErrBox eb) {
        Object err = eb.err;
        if (!eb.isConsumed() && !(err instanceof CancellationException)) {
            ELD_CLEANER.register(this, eb);
        }
    }

    private KDeferred() {
    }

    public final boolean own() {
        if ((byte) OWNED.getOpaque(this) == 1) {
            return false;
        }
        while (!OWNED.weakCompareAndSetPlain(this, (byte) 0, (byte) 1)) {
            if ((byte) OWNED.getOpaque(this) == (byte) 1) {
                return false;
            }
            Thread.onSpinWait();
        }
        return true;
    }

    public final boolean owned() {
        return (byte) OWNED.getOpaque(this) == 1;
    }

    @Override
    public synchronized IPersistentMap meta() {
        return meta;
    }

    @Override
    public synchronized IPersistentMap alterMeta(IFn alter, ISeq args) {
        this.meta = (IPersistentMap) alter.applyTo(RT.listStar(meta, args));
        return this.meta;
    }

    @Override
    public synchronized IPersistentMap resetMeta(IPersistentMap m) {
        this.meta = m;
        return m;
    }

    @SuppressWarnings("CallToPrintStackTrace")
    static void logWarn(Object err, String msg) {
        try {
            LOG_EXCEPTION.invoke(false, err, msg);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("CallToPrintStackTrace")
    static void logError(Object err, String msg) {
        try {
            LOG_EXCEPTION.invoke(true, err, msg);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private boolean invalidToken() {
        if (TOKEN.getOpaque(this) == null) {
            throw new IllegalStateException("invalid claim token");
        } else {
            return false;
        }
    }

    private AListener tombListeners() {
        return (AListener) LHEAD.getAndSet(this, LS_TOMB);
    }

    private boolean complete(Object x) {
        return VALUE.compareAndExchangeRelease(this, MISS_VALUE, x) == MISS_VALUE;
    }

    @Override
    public void fireValue(Object x) {
        if (TOKEN.getOpaque(this) != null) {
            throw new IllegalStateException("invalid claim token");
        }
        if (complete(x)) {
            fireSuccessListeners(x);
        }
    }

    public void fireValue(Object x, Object token) {
        if (TOKEN.getOpaque(this) != token) {
            throw new IllegalStateException("invalid claim token");
        }
        if (complete(x)) {
            fireSuccessListeners(x);
        }
    }

    @Override
    public void fireError(Object x) {
        if (TOKEN.getOpaque(this) != null) {
            throw new IllegalStateException("invalid claim token");
        }
        ErrBox eb = new ErrBox(x);
        if (complete(eb)) {
            fireErrorListeners(eb);
        }
    }

    public void fireError(Object x, Object token) {
        if (TOKEN.getOpaque(this) != token) {
            throw new IllegalStateException("invalid claim token");
        }
        ErrBox eb = new ErrBox(x);
        if (complete(eb)) {
            fireErrorListeners(eb);
        }
    }

    @Override
    public Object success(Object x) {
        if (TOKEN.getOpaque(this) != null) {
            return invalidToken();
        }
        if (complete(x)) {
            fireSuccessListeners(x);
            return true;
        }
        return false;
    }

    @Override
    public Object success(Object x, Object token) {
        if (TOKEN.getOpaque(this) != token) {
            return invalidToken();
        }
        if (complete(x)) {
            fireSuccessListeners(x);
            return true;
        }
        return false;
    }

    @Override
    public Object error(Object x) {
        if (TOKEN.getOpaque(this) != null) {
            return invalidToken();
        }
        ErrBox eb = new ErrBox(x);
        if (complete(eb)) {
            fireErrorListeners(eb);
            return true;
        }
        return false;
    }

    @Override
    public Object error(Object x, Object token) {
        if (TOKEN.getOpaque(this) != token) {
            return invalidToken();
        }
        ErrBox eb = new ErrBox(x);
        if (complete(eb)) {
            fireErrorListeners(eb);
            return true;
        }
        return false;
    }

    private void fireSuccessListeners(Object x) {
        AListener node = tombListeners();
        this.succeeded = 1;
        for (; node != null; node = node.next) {
            try {
                node.success(x);
            } catch (Throwable e) {
                logError(e, String.format("error in deferred success-handler: %s", node));
            }
        }
    }

    private void fireErrorListeners(ErrBox eb) {
        AListener node = tombListeners();
        if (node == null) {
            detectLeakedError(eb);
        } else {
            Object x = eb.consume();
            for (; node != null; node = node.next) {
                try {
                    node.error(x);
                } catch (Throwable e) {
                    logError(e, String.format("error in deferred success-handler: %s", node));
                }
            }
        }
    }

    public boolean listen0(IFn onSuc, IFn onErr) {
        AListener head = (AListener) LHEAD.getOpaque(this);
        return head != LS_TOMB && listen0(head, new Fn(onSuc, onErr));
    }

    public boolean listen0(IDeferredListener ls) {
        AListener head = (AListener) LHEAD.getOpaque(this);
        return head != LS_TOMB && listen0(head, new Dl(ls));
    }

    boolean listen0(AListener ls) {
        AListener head = (AListener) LHEAD.getOpaque(this);
        return head != LS_TOMB && listen0(head, ls);
    }

    private boolean listen0(AListener head, AListener ls) {
        AListener x;
        ls.next = head;
        while ((x = (AListener) LHEAD.compareAndExchangeRelease(this, head, ls)) != head) {
            if (x == LS_TOMB) {
                return false;
            }
            head = x;
            ls.next = x;
            Thread.onSpinWait();
        }
        return true;
    }

    public void listen(IFn onSuc, IFn onErr) {
        if (this.listen0(onSuc, onErr)) {
            return;
        }
        Object v = this.getRaw();
        if (v instanceof ErrBox) {
            ErrBox eb = (ErrBox) v;
            onErr.invoke(eb.consume());
        } else {
            onSuc.invoke(v);
        }
    }

    public void listen(IDeferredListener ls) {
        if (this.listen0(ls)) {
            return;
        }
        Object v = this.getRaw();
        if (v instanceof ErrBox) {
            ErrBox eb = (ErrBox) v;
            ls.onError(eb.consume());
        } else {
            ls.onSuccess(v);
        }
    }

    void listen(AListener ls) {
        if (this.listen0(ls)) {
            return;
        }
        Object v = this.getRaw();
        if (v instanceof ErrBox) {
            ErrBox eb = (ErrBox) v;
            ls.error(eb.consume());
        } else {
            ls.success(v);
        }
    }

    @Override
    public Object addListener(Object ls0) {
        IDeferredListener ls = (IDeferredListener) ls0;
        if (this.listen0(ls)) {
            return true;
        }

        Object v = this.getRaw();
        if (v instanceof ErrBox) {
            ErrBox eb = (ErrBox) v;
            ls.onError(eb.consume());
        } else {
            ls.onSuccess(v);
        }

        return false;
    }

    @Override
    public Object onRealized(Object onSucc, Object onErrr) {
        IFn onSuc = (IFn) onSucc;
        IFn onErr = (IFn) onErrr;
        Object v = this.getRaw();
        if (v == MISS_VALUE) {
            if (this.listen0(new Fn(onSuc, onErr))) {
                return true;
            }
            v = this.getRaw();
        }
        if (v instanceof ErrBox) {
            ErrBox eb = (ErrBox) v;
            onErr.invoke(eb.consume());
        } else {
            onSuc.invoke(v);
        }
        return false;
    }

    private boolean isCancelledListener(AListener als) {
        return !((als instanceof Dl) && ((Dl) als).ls == null);
    }

    private Dl findAndCancelListener(AListener head, Object ls) {
        while (head != null) {
            if (head instanceof Dl) {
                Dl ahead = (Dl) head;
                if (ahead.ls == ls && ahead.acquireForCancel()) {
                    return ahead;
                }
            }
            head = head.next;
        }
        return null;
    }

    private void cleanCancelledListeners(AListener head) {
        // TODO: full scan on all listeners - optimize.
        while (head != null) {
            AListener next = head.next;
            if (next == null) {
                return;
            }
            if (isCancelledListener(next)) {
                if (!head.casNext(next, next.next)) {
                    // some other cleanup is active - break
                    return;
                }
            }
            head = next;
        }
    }

    @Override
    public Object cancelListener(Object listener) {
        AListener ahead = (AListener) LHEAD.getAcquire(this);
        if (ahead == LS_TOMB) {
            return false;
        }
        Dl als = findAndCancelListener(ahead, listener);
        if (als == null) {
            return false;
        }
        ahead = (AListener) LHEAD.getAcquire(this);
        if (ahead == LS_TOMB && !als.cancelled()) {
            return false;
        }
        this.cleanCancelledListeners(ahead);
        return true;
    }

    public boolean reclaim(Object curToken, Object newToken) {
        return TOKEN.compareAndExchange(this, curToken, newToken) == curToken;
    }

    @Override
    public Object claim() {
        if (TOKEN.getOpaque(this) == null) {
            Object t = new Object();
            return reclaim(null, t) ? t : null;
        }
        return null;
    }

    public boolean claim(Object token) {
        return reclaim(null, token);
    }

    @Override
    public Object executor() {
        return null;
    }

    @Override
    public boolean realized() {
        return this.succeeded == 1 || VALUE.getOpaque(this) != MISS_VALUE;
    }

    @Override
    public boolean isRealized() {
        return this.succeeded == 1 || VALUE.getOpaque(this) != MISS_VALUE;
    }

    @Override
    public Object successValue(Object fallback) {
        Object v = this.getRaw();
        return (v == MISS_VALUE || (v instanceof ErrBox)) ? fallback : v;
    }

    @Override
    public Object errorValue(Object fallback) {
        Object v = this.getRaw();
        return (v != MISS_VALUE && (v instanceof ErrBox)) ? ((ErrBox) v).consume() : fallback;
    }

    static Throwable coerceError(Object err) {
        if (err instanceof Throwable) {
            return (Throwable) err;
        } else {
            return new ExceptionInfo(
                "invalid error object",
                    PersistentArrayMap.EMPTY.assoc(ERROR_KW, err)
            );
        }
    }

    private <T extends Throwable> Object throwErr(ErrBox eb) throws T {
        throw ((T) coerceError(eb.consume()));
    }

    public Object unwrap() {
        Object v = this.getRaw();
        return (v == MISS_VALUE || (v instanceof ErrBox)) ? this : v;
    }

    public final Object getRaw() {
        return VALUE.getAcquire(this);
    }

    public final Object get() {
        Object v = this.getRaw();
        return (v instanceof ErrBox) ? throwErr((ErrBox) v) : v;
    }

    public final Object get(IFn onErr) {
        Object v = this.getRaw();
        if (v instanceof ErrBox) {
            return onErr.invoke(((ErrBox) v).consume());
        }
        return v;
    }

    private CountDownLatch acquireCountdDownLatch() {
        CountDownLatch cdl = new CountDownLatch(1);
        this.listen(new Cdl(cdl));
        return cdl;
    }

    @Override
    public Object invoke() {
        return this.get();
    }

    @Override
    public Object invoke(Object x) {
        return ((Boolean) this.success(x)) ? this : null;
    }

    @Override
    public Object deref(long ms, Object timeoutValue) {
        if (this.realized()) {
            return get();
        }
        if (ms <= 0) {
            return timeoutValue;
        }
        try {
            acquireCountdDownLatch().await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw Util.sneakyThrow(e);
        }
        if (this.realized()) {
            return get();
        }
        return timeoutValue;
    }

    @Override
    public Object deref() {
        if (this.realized()) {
            return get();
        }
        try {
            acquireCountdDownLatch().await();
        } catch (InterruptedException e) {
            throw Util.sneakyThrow(e);
        }
        return this.get();
    }

    public void chain0(IDeferred x, Object token) {
        if (x instanceof KDeferred) {
            KDeferred kd = (KDeferred) x;
            kd.listen(new Chain(this, token));
        } else {
            Objects.requireNonNull(x);
            ((IDeferred) x).onRealized(new ChainValue(this, token), new ChainError(this, token));
        }
    }

    public void chain(Object x, Object token) {
        if (x instanceof IDeferred) {
            IDeferred xx = (IDeferred) x;
            x = xx.successValue(MISS_VALUE);
            if (x == MISS_VALUE) {
                this.chain0(xx, token);
                return;
            }
        }
        this.fireValue(x, token);
    }

    public void chain(Object x) {
        if (x instanceof IDeferred) {
            IDeferred xx = (IDeferred) x;
            x = xx.successValue(MISS_VALUE);
            if (x == MISS_VALUE) {
                this.chain0(xx, null);
                return;
            }
        }
        this.fireValue(x);
    }

    public KDeferred bind(IFn valFn, IFn errFn, Executor executor) {
        if (executor == null) {
            return this.bind(valFn, errFn);
        }
        KDeferred dest = create();
        this.listen(new BindEx(dest, valFn, errFn, executor));
        return dest;
    }

    public KDeferred bind(IFn valFn) {
        Object v = this.getRaw();
        if (v == MISS_VALUE) {
            KDeferred dest = create();
            if (this.listen0(new Bind(dest, valFn, null))) {
                return dest;
            }
            v = this.getRaw();
        }
        if (v instanceof ErrBox) {
            return this;
        }
        try {
            return wrap(valFn.invoke(v));
        } catch (Throwable e) {
            return wrapErr(e);
        }
    }

    public KDeferred bind(IFn valFn, IFn errFn) {
        Object v = this.getRaw();
        if (v == MISS_VALUE) {
            KDeferred dest = create();
            if (this.listen0(new Bind(dest, valFn, errFn))) {
                return dest;
            }
            v = this.getRaw();
        }
        if (v instanceof ErrBox) {
            if (errFn == null) {
                return this;
            } else {
                valFn = errFn;
                ErrBox eb = (ErrBox) v;
                v = eb.consume();
            }
        }
        try {
            KDeferred r = wrap(valFn.invoke(v));
            return r;
        } catch (Throwable e) {
            return wrapErr(e);
        }
    }

    public static KDeferred bind(Object x, IFn valFn) {
        if (x instanceof KDeferred) {
            KDeferred kd = (KDeferred) x;
            if (kd.succeeded == 1) {
                x = kd.getRaw();
            } else {
                return kd.bind(valFn);
            }
        } else if (x instanceof IDeferred) {
            return wrapDeferred((IDeferred) x).bind(valFn);
        }
        try {
            return wrap(valFn.invoke(x));
        } catch (Throwable e) {
            return wrapErr(e);
        }
    }

    public static KDeferred bind(Object x, IFn valFn, IFn errFn) {
        if (x instanceof KDeferred) {
            KDeferred kd = (KDeferred) x;
            if (kd.succeeded == 1) {
                x = kd.getRaw();
            } else {
                return kd.bind(valFn, errFn);
            }
        } else if (x instanceof IDeferred) {
            return wrap(x).bind(valFn, errFn);
        }
        try {
            return wrap(valFn.invoke(x));
        } catch (Throwable e) {
            return wrapErr(e);
        }
    }

    public static KDeferred bind(Object x, IFn valFn, IFn errFn, Executor ex) {
        return wrap(x).bind(valFn, errFn, ex);
    }

    public static KDeferred create() {
        KDeferred d = new KDeferred();
        VALUE.setRelease(d, MISS_VALUE);
        return d;
    }

    public static KDeferred create(Object token) {
        KDeferred d = new KDeferred();
        TOKEN.setOpaque(d, token);
        VALUE.setRelease(d, MISS_VALUE);
        return d;
    }

    public static KDeferred wrapErr(Object e) {
        ErrBox eb = new ErrBox(e);
        KDeferred d = new KDeferred();
        LHEAD.setOpaque(d, LS_TOMB);
        VALUE.setVolatile(d, eb);
        d.detectLeakedError(eb);
        return d;
    }

    public static KDeferred wrapVal(Object x) {
        KDeferred d = new KDeferred();
        LHEAD.setOpaque(d, LS_TOMB);
        VALUE.setVolatile(d, x);
        d.succeeded = 1;
        return d;
    }

    private static KDeferred wrapDeferred(IDeferred x) {
        Object xx = x.successValue(MISS_VALUE);
        if (xx == MISS_VALUE) {
            KDeferred d = create();
            d.chain0(x, null);
            return d;
        } else {
            return wrapVal(xx);
        }
    }

    public static KDeferred wrap(Object x) {
        if (x instanceof KDeferred) {
            return (KDeferred) x;
        } if (x instanceof IDeferred) {
            return wrapDeferred((IDeferred) x);
        } else {
            return wrapVal(x);
        }
    }

    public static KDeferred revoke(IDeferred d, IFn canceller, IFn errCallback) {
        KDeferred dd = wrap(d);
        if (dd.realized()) {
            return dd;
        } else {
            KDeferred kd = create();
            kd.listen0(new Revoke(dd, canceller, errCallback));
            kd.chain(dd, null);
            return kd;
        }
    }

    public static Object unwrap(Object x) {
        while (x instanceof IDeferred) {
            IDeferred d = (IDeferred) x;
            x = d.successValue(MISS_VALUE);
            if (x == MISS_VALUE) {
                return d;
            }
        }
        return x;
    }

    public static Object unwrap1(Object x) {
        return (x instanceof IDeferred) ? ((IDeferred) x).successValue(x) : x;
    }

    public static boolean isDeferred(Object x) {
        return x instanceof IDeferred;
    }

    public static void connect(Object from, IMutableDeferred to) {
        if (to instanceof KDeferred) {
            KDeferred kd = (KDeferred) to;
            kd.chain(from, null);
        } else {
            KDeferred t = create();
            t.chain(from, null);
            t.listen(new AListener() {
                @Override
                public void success(Object x) {
                    to.success(x);
                }
                @Override
                public void error(Object e) {
                    to.error(e);
                }
            });
        }
    }

    // == support CompletionStageMixin == //

    @Override
    public CompletionStageMixin coerce(CompletionStage s) {
        if (s instanceof KDeferred) {
            return (KDeferred) s;
        }
        KDeferred d = create();
        Objects.requireNonNull(s);
        s.handle((x, e) -> {
            if (e == null) {
                d.fireValue(x, null);
            } else {
                d.fireError(e, null);
            }
            return null;
        });
        return d;
    }

    @Override
    public CompletionStageMixin dup() {
        return create();
    }

    private void consume0(Consumer xc, Consumer ec) {
        this.listen(new AListener() {
            @Override
            public void success(Object x) {
                xc.accept(x);
            }
            @Override
            public void error(Object e) {
                ec.accept(e);
            }
        });
    }

    private void consume0(Consumer xc, Consumer ec, Executor ex) {
        this.listen(new AListener() {
            @Override
            public void success(Object x) {
                ExecutionPool.adapt(ex).fork(() -> xc.accept(x));
            }
            @Override
            public void error(Object e) {
                ExecutionPool.adapt(ex).fork(() -> ec.accept(e));
            }
        });
    }

    @Override
    public void consume(Consumer xc, Consumer ec, Executor ex) {
        if (ex == null) {
            this.consume0(xc, ec);
        } else {
            this.consume0(xc, ec, ex);
        }
    }

    @Override
    public Executor defaultExecutor() {
        return (Executor) KDeferred.GET_EXECUTOR.invoke();
    }
}