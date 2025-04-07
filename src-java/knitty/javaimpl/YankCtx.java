package knitty.javaimpl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;

import clojure.lang.AFn;
import clojure.lang.Associative;
import clojure.lang.ExceptionInfo;
import clojure.lang.IExceptionInfo;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;

public final class YankCtx {

    private static final Keyword KNITTY_ERROR        = Keyword.intern("knitty", "error-object");
    private static final Keyword KNITTY_YANKED_YARNS = Keyword.intern("knitty", "yarns");
    private static final Keyword KNITTY_FAILED_POY   = Keyword.intern("knitty", "result");
    private static final Keyword KNITTY_YANKED_POY   = Keyword.intern("knitty", "inputs");
    private static final Keyword KNITTY_YANK_ERROR   = Keyword.intern("knitty", "yank-error?");

    private static final Object NONE = new Object();
    private static final Keyword KEYFN = Keyword.intern("key");

    static final int ASHIFT = 5;
    static final int ASIZE = 1 << ASHIFT;
    static final int AMASK = ASIZE - 1;

    private static final VarHandle AR0 = MethodHandles.arrayElementVarHandle(KDeferred[][].class);
    private static final VarHandle AR1 = MethodHandles.arrayElementVarHandle(KDeferred[].class);
    private static final VarHandle YSC = MethodHandles.arrayElementVarHandle(AFn[].class);
    private static final VarHandle ADDED;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            ADDED = l.findVarHandle(YankCtx.class, "_added", KVCons.class);
        } catch (ReflectiveOperationException var1) {
            throw new ExceptionInInitializerError(var1);
        }
    }

    @SuppressWarnings("unused")
    private volatile KVCons _added = KVCons.NIL;

    private final KDeferred[][] a0;

    private final YankInputs inputs;
    private final AFn[] yarnsCache;
    private final YarnProvider yankerProvider;
    private final KwMapper kwMapper;
    private final boolean loadInputs;

    public final ExecutionPool pool;
    public final Object tracer;
    public final Object token;

    private final class DoYankFn extends AFn {

        private final Iterable<?> yarns;
        private final KDeferred res;

        private DoYankFn(Iterable<?> yarns, KDeferred res) {
            this.yarns = yarns;
            this.res = res;
        }

        @Override
        public Object invoke() {
            try {
                doYank(yarns, res);
            } catch (Throwable t) {
                res.fireError(wrapYankErr(t, yarns));
            }
            return null;
        }
    }

    private final class YankDoneLs extends AFn {

        private final KDeferred res;
        private final Iterable<?> yarns;

        private YankDoneLs(KDeferred res, Iterable<?> yarns) {
            this.res = res;
            this.yarns = yarns;
        }

        @Override
        public Object invoke() {
            if (!res.realized()) {
                res.success(finish(), null);
            }
            return null;
        }

        @Override
        public Object invoke(Object err) {
            if (!res.realized()) {
                res.error(wrapYankErr(err, yarns), null);
            }
            return null;
        }
    }

    static final class KVCons {

        public static final KVCons NIL = new KVCons(null, null, null);

        public final KVCons next;
        public final Keyword k;
        public final KDeferred d;

        public KVCons(KVCons next, Keyword k, KDeferred d) {
            this.next = next;
            this.k = k;
            this.d = d;
        }
    }

    public static YankCtx create(Object inputs, YarnProvider yp, Executor executor, Object tracer, boolean preloadInputs, Object bframe) {
        ExecutionPool pool = ExecutionPool.adapt(executor, bframe);
        YankInputs yinputs;

        if (inputs instanceof YankInputs) {
            yinputs = (YankInputs) inputs;
        } else if (inputs instanceof Associative) {
            yinputs = new YankInputsAssoc((Associative) inputs);
        } else {
            throw new IllegalArgumentException("yank input must implement clojure.lang.Associative");
        }

        YankCtx ctx = new YankCtx(yinputs, yp, pool, tracer, preloadInputs);
        if (preloadInputs) {
            preloadInputs(yinputs, ctx);
        }

        return ctx;
    }

    private static void preloadInputs(YankInputs yinputs, YankCtx ctx) {
        KwMapper kwMapper = KwMapper.getInstance();
        yinputs.kvreduce(new AFn() {
            @Override
            public Object invoke(Object _a, Object k, Object v) {
                if (k instanceof Keyword) {
                    int i = kwMapper.resolveByKeyword((Keyword) k);
                    if (i != -1) {
                        KDeferred d = ctx.pull(i);
                        if (d.retain()) {
                            d.chain(v, ctx.token);
                        }
                    }
                }
                return null;
            }
        }, null);
    }

    private YankCtx(YankInputs inputs, YarnProvider yp, ExecutionPool pool, Object tracer, boolean preloadInputs) {
        this.kwMapper = KwMapper.getInstance();
        this.a0 = new KDeferred[((kwMapper.maxIndex() + ASIZE) >> ASHIFT)][];
        this.inputs = inputs;
        this.yarnsCache = yp.ycache();
        this.yankerProvider = yp;
        this.pool = pool;
        this.tracer = tracer;
        this.token = new Object();
        this.loadInputs = !preloadInputs;
    }

    private Exception wrapYankErr(Object error0, Object yarns) {

        Throwable error;
        if (error0 instanceof Throwable) {
           error = (Throwable) error0;
        } else {
            error = new ExceptionInfo(
                "invalid error object",
                     PersistentArrayMap.EMPTY.assoc(KNITTY_ERROR, error0)
            );
        }

        IPersistentMap exdata;
        if (error instanceof  IExceptionInfo) {
            exdata = ((IExceptionInfo) error).getData();
        } else {
            exdata = PersistentArrayMap.EMPTY;
        }

        exdata = exdata
            .assoc(KNITTY_FAILED_POY, this.finish())
            .assoc(KNITTY_YANKED_POY, inputs.unwrapInputs())
            .assoc(KNITTY_YANKED_YARNS, yarns)
            .assoc(KNITTY_YANK_ERROR, Boolean.TRUE);

        return new ExceptionInfo("failed to yank", exdata, error);
    }


    void doYank(Iterable<?> yarns, KDeferred res) {

        AFn ls = new YankDoneLs(res, yarns);
        KAwaiter ka = KAwaiter.start(ls);

        for (Object x : yarns) {
            Objects.requireNonNull(x, "yarn must be non-null");
            KDeferred r;
            if (x instanceof Keyword) {
                Keyword k = (Keyword) x;
                int i0 = this.kwMapper.resolveByKeyword(k);
                if (i0 == -1) {
                    throw new IllegalArgumentException("unknown yarn " + x);
                }
                r = this.fetch(i0, k);
            } else {
                AFn y = (AFn) x;
                Keyword k = (Keyword) KEYFN.invoke(y.invoke());
                int i0 = this.kwMapper.resolveByKeyword(k);
                if (i0 == -1) {
                    throw new IllegalArgumentException("unknown yarn " + k);
                }
                r = this.fetch(i0, k, y);
            }
            ka.add(r);
        }

        if (ka.await()) {
            res.success(finish(), null);
        }
    }

    public KDeferred yank(Iterable<?> yarns) {
        Objects.requireNonNull(yarns);
        KDeferred res = KDeferred.create();
        this.pool.run(new DoYankFn(yarns, res));
        res.listen0(canceller());
        return res;
    }

    private KDeferred[] pullChunk(int i0) {
        KDeferred[] a11 = new KDeferred[ASIZE];
        KDeferred[] res;
        do {
            if (AR0.weakCompareAndSetPlain(a0, i0, null, a11)) {
                return a11;
            }
        } while ((res = (KDeferred[]) AR0.getOpaque(a0, i0)) == null);
        return res;
    }

    public final KDeferred pull(int i) {
        int i0 = i >> ASHIFT;
        KDeferred[] a1 = (KDeferred[]) AR0.getOpaque(a0, i0);
        if (a1 == null) {
            a1 = this.pullChunk(i0);
        }

        int i1 = i & AMASK;
        KDeferred v = (KDeferred) AR1.getOpaque(a1, i1);
        if (v != null) {
            return v;
        }

        KDeferred d = KDeferred.create(token);
        KDeferred r;
        do {
            if (AR1.weakCompareAndSetPlain(a1, i1, null, d)) {
                return d;
            }
        } while ((r = (KDeferred) AR1.getOpaque(a1, i1)) == null);
        return r;
    }

    public Object token() {
        return token;
    }

    private boolean fetch0(KDeferred d, int i, Keyword k) {

        if (loadInputs) {
            Object x = inputs.get(i, k, NONE);
            if (x != NONE) {
                d.chain(x, token);
                return false;
            }
        }

        KVCons a;
        while ((a = (KVCons) ADDED.getAcquire(this)) != null) {
            if (ADDED.weakCompareAndSetRelease(this, a, new KVCons(a, k, d))) {
                return true;
            }
        }

        d.error(RevokeException.YANK_FINISHED, token);
        return false;
    }

    public final KDeferred fetch(int i, Keyword k, AFn y) {
        KDeferred d = pull(i);
        if (d.retain() && fetch0(d, i, k)) {
            y.invoke(this, d);
        }
        return d;
    }

    public final KDeferred fetch(int i, Keyword k) {
        KDeferred d = pull(i);
        if (d.retain() && fetch0(d, i, k)) {
            AFn y = this.yarn(i);
            y.invoke(this, d);
        }
        return d;
    }

    private AFn yarn(int i) {
        AFn y = (AFn) YSC.getAcquire(yarnsCache, i);
        if (y != null) {
            return y;
        }
        y = yankerProvider.yarn(this.kwMapper.resolveByIndex(i));
        YSC.setRelease(yarnsCache, i, y);
        return y;
    }

    public static void putYarnIntoCache(AFn[] yarnsCache, int idx, AFn yarn) {
        YSC.setRelease(yarnsCache, idx, yarn);
    }

    private KVCons freeze() {
        KVCons a = (KVCons) ADDED.getAndSet(this, null);
        if (a == null) {
            throw new IllegalStateException("yankctx is already frozen");
        }
        return a;
    }

    boolean isFrozen() {
        return ((KVCons) ADDED.getOpaque(this)) == null;
    }

    YankResult finish() {
        KVCons added0 = this.freeze();
        for (KVCons a = added0; a.d != null; a = a.next) {
            if (a.d.retain()) {
                a.d.error(RevokeException.DEFERRED_REVOKED, this.token);
            }
        }
        return new YankResult(inputs, a0, added0, kwMapper);
    }

    KDeferred.AListener canceller() {
        return new KDeferred.AListener() {

            @Override
            public void success(Object _v) {
                if (!isFrozen()) {
                    cancel(null);
                }
            }
            @Override
            public void error(Object e) {
                if (!isFrozen()) {
                    Throwable t;
                    try {
                        t = (Throwable) e;
                    } catch (ClassCastException e1) {
                        t = e1;
                    }
                    cancel(t);
                }
            }
        };
    }

    public void cancel(Throwable cause) {
        CancellationException ex = new RevokeException("yankctx is cancelled");
        if (cause != null) {
            ex.addSuppressed(cause);
        }
        for (KVCons a = this.freeze(); a.d != null; a = a.next) {
            a.d.error(ex, token);
        }
    }
}
