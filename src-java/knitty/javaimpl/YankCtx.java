package knitty.javaimpl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import clojure.lang.AFn;
import clojure.lang.Associative;
import clojure.lang.ExceptionInfo;
import clojure.lang.IEditableCollection;
import clojure.lang.IExceptionInfo;
import clojure.lang.ILookup;
import clojure.lang.IPersistentMap;
import clojure.lang.ITransientAssociative;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentVector;

public final class YankCtx implements ILookup {

    private static final Keyword KNITTY_ERROR        = Keyword.intern("knitty", "error");
    private static final Keyword KNITTY_YANKED_YARNS = Keyword.intern("knitty", "yanked-yarns");
    private static final Keyword KNITTY_FAILED_POY   = Keyword.intern("knitty", "failed-poy");
    private static final Keyword KNITTY_YANKED_POY   = Keyword.intern("knitty", "yanked-poy");
    private static final Keyword KNITTY_YANK_ERROR   = Keyword.intern("knitty", "yank-error?");

    private static final Object NONE = new Object();
    private static final KVCons NIL = new KVCons(null, null, null);
    private static final Keyword KEYFN = Keyword.intern("key");

    private static final int ASHIFT = 4;
    private static final int ASIZE = 1 << ASHIFT;
    private static final int AMASK = ASIZE - 1;

    private static final VarHandle AR0 = MethodHandles.arrayElementVarHandle(KDeferred[][].class);
    private static final VarHandle AR1 = MethodHandles.arrayElementVarHandle(KDeferred[].class);
    private static final VarHandle YSC = MethodHandles.arrayElementVarHandle(AFn[].class);
    private static final VarHandle ADDED;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            ADDED = l.findVarHandle(YankCtx.class, "added", KVCons.class);
        } catch (ReflectiveOperationException var1) {
            throw new ExceptionInInitializerError(var1);
        }
    }

    private volatile KVCons added = NIL;
    private final KDeferred[][] a0;

    private final Associative inputs;
    private final AFn[] yarnsCache;
    private final YarnProvider yankerProvider;
    private final KwMapper kwMapper;

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
                res.error(wrapYankErr(t, yarns));
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
                res.success(freezePoy(), null);
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

    private final class DoYank1Fn extends AFn {

        private final Object x;
        private final KDeferred res;

        private DoYank1Fn(Object x, KDeferred res) {
            this.x = x;
            this.res = res;
        }

        @Override
        public Object invoke() {
            try {
                doYank1(x, res);
            } catch (Throwable t) {
                res.error(wrapYankErr(t, PersistentVector.create(x)));
            }
            return null;
        }
    }

    private final class Yank1DoneLs extends AListener {

        private final Object yarn;
        private final KDeferred res;

        private Yank1DoneLs(Object yarn, KDeferred res) {
            this.yarn = yarn;
            this.res = res;
        }

        public void success(Object x) {
            freezeVoid();
            res.success(x, null);
        }

        public void error(Object e) {
            if (!res.realized()) {
                res.error(wrapYankErr(e, PersistentVector.create(yarn)), null);
            }
        }
    }

    private static class KVCons {

        public final KVCons next;
        public final Keyword k;
        public final KDeferred d;

        public KVCons(KVCons next, Keyword k, KDeferred d) {
            this.next = next;
            this.k = k;
            this.d = d;
        }
    }

    public YankCtx(Associative inputs, YarnProvider yp, ExecutionPool pool, Object tracer) {
        this.kwMapper = KwMapper.getInstance();
        this.a0 = new KDeferred[((kwMapper.maxIndex() + AMASK) >> ASHIFT)][];
        this.inputs = inputs;
        this.yarnsCache = yp.ycache();
        this.yankerProvider = yp;
        this.pool = pool;
        this.tracer = tracer;
        this.token = new Object();
    }

    private KDeferred[] createChunk(int i0) {
        KDeferred[] a11 = new KDeferred[ASIZE];
        if (AR0.compareAndSet(a0, i0, null, a11)) {
            return a11;
        } else {
            return (KDeferred[]) AR0.getVolatile(a0, i0);
        }
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
            .assoc(KNITTY_FAILED_POY, this.freezePoy())
            .assoc(KNITTY_YANKED_POY, inputs)
            .assoc(KNITTY_YANKED_YARNS, yarns)
            .assoc(KNITTY_YANK_ERROR, Boolean.TRUE);

        return new ExceptionInfo("failed to yank", exdata, error);
    }


    void doYank(Iterable<?> yarns, KDeferred res) {

        int di = 0;
        KDeferred[] ds = null;

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
            if (r.state != KDeferred.STATE_SUCC) {
                if (ds == null) {
                    ds = new KDeferred[8];
                } else if (di == ds.length) {
                    ds = Arrays.copyOf(ds, ds.length * 2);
                }
                ds[di++] = r;
            }
        }

        AFn ls = new YankDoneLs(res, yarns);

        if (KAwaiter.awaitArr(ls, ds, di)) {
            res.success(freezePoy(), null);
        }
    }

    public KDeferred yank(Iterable<?> yarns) {
        Objects.requireNonNull(yarns);
        KDeferred res = KDeferred.create();
        this.pool.run(new DoYankFn(yarns, res));
        res.listen0(canceller());
        return res;
    }

    void doYank1(Object x, KDeferred res) {

        KDeferred d;
        Objects.requireNonNull(x, "yarn must be non-null");

        if (x instanceof Keyword) {
            Keyword k = (Keyword) x;
            int i0 = this.kwMapper.resolveByKeyword(k);
            if (i0 == -1) {
                throw new IllegalArgumentException("unknown yarn " + x);
            } else {
                d = this.fetch(i0, k);
            }
        } else {
            AFn y = (AFn) x;
            Keyword k = (Keyword) KEYFN.invoke(y.invoke());
            int i0 = this.kwMapper.resolveByKeyword(k);
            if (i0 == -1) {
                throw new IllegalArgumentException("unknown yarn " + k);
            } else {
                d = this.fetch(i0, k, y);
            }
        }

        AListener ls = new Yank1DoneLs(x, res);
        d.listen(ls);
    }

    public KDeferred yank1(Object x) {
        Objects.requireNonNull(x);
        KDeferred res = new KDeferred();
        this.pool.run(new DoYank1Fn(x, res));
        res.listen0(canceller());
        return res;
    }

    public Object valAt(Object key) {
        return this.valAt(key, null);
    }

    public Object valAt(Object key, Object fallback) {
        if ((key instanceof Keyword)) {
            Keyword k = (Keyword) key;
            if (k.getNamespace() != null) {
                int i = this.kwMapper.resolveByKeyword(k, false);
                if (i != -1) {
                    int i0 = i >> ASHIFT;
                    int i1 = i & AMASK;
                    KDeferred[] a1 = (KDeferred[]) AR0.getVolatile(a0, i0);
                    if (a1 != null) {
                        KDeferred v = (KDeferred) AR1.getVolatile(a1, i1);
                        if (v != null) {
                            return v.unwrap();
                        }
                    }
                }
            }
        }
        return inputs.valAt(key, fallback);
    }

    public final KDeferred pull(int i) {
        int i0 = i >> ASHIFT;
        KDeferred[] a1 = (KDeferred[]) AR0.getVolatile(a0, i0);
        if (a1 == null) {
            a1 = this.createChunk(i0);
        }

        int i1 = i & AMASK;
        KDeferred v = (KDeferred) AR1.getVolatile(a1, i1);
        if (v != null) {
            return v;
        }

        KDeferred d = new KDeferred(token);
        if (AR1.compareAndSet(a1, i1, null, d)) {
            return d;
        } else {
            return (KDeferred) AR1.getVolatile(a1, i1);
        }
    }

    public Object token() {
        return token;
    }

    private boolean fetch0(KDeferred d, Keyword k) {

        Object x = inputs.valAt(k, NONE);
        if (x != NONE) {
            d.chain(x, token);
            return false;
        }

        KVCons a = added;
        while (a != null) {
            KVCons b = (KVCons) ADDED.compareAndExchange(this, a, new KVCons(a, k, d));
            if (a == b) {
                return true;
            }
            a = b;
        }

        d.error(RevokeException.YANK_FINISHED, token);
        return false;
    }

    public final KDeferred fetch(int i, Keyword k, AFn y) {
        KDeferred d = pull(i);
        if (d.own() && fetch0(d, k)) {
            y.invoke(this, d);
        }
        return d;
    }

    public final KDeferred fetch(int i, Keyword k) {
        KDeferred d = pull(i);
        if (d.own() && fetch0(d, k)) {
            AFn y = this.yarn(i);
            y.invoke(this, d);
        }
        return d;
    }

    private AFn yarn(int i) {
        AFn y = (AFn) YSC.getVolatile(yarnsCache, i);
        if (y != null) {
            return y;
        }
        y = yankerProvider.yarn(this.kwMapper.resolveByIndex(i));
        YSC.setVolatile(yarnsCache, i, y);
        return y;
    }

    private KVCons freeze() {
        KVCons a = (KVCons) ADDED.getAndSet(this, null);
        if (a == null) {
            throw new IllegalStateException("yankctx is already frozen");
        }
        return a;
    }

    public boolean isFrozen() {
        return added == null;
    }

    public void freezeVoid() {
        for (KVCons a = this.freeze(); a.d != null; a = a.next) {
            if (a.d.free && a.d.own()) {
                a.d.error(RevokeException.DEFERRED_REVOKED, this.token);
            }
        }
    }

    public Associative freezePoy() {
        KVCons added0 = this.freeze();
        if (added0.next == null) {
            return inputs;
        }
        if (added0.next.d != null && inputs instanceof IEditableCollection) {
            ITransientAssociative t = (ITransientAssociative) ((IEditableCollection) inputs).asTransient();
            for (KVCons a = added0; a.d != null; a = a.next) {
                if (a.d.free && a.d.own()) {
                    a.d.error(RevokeException.DEFERRED_REVOKED, this.token);
                } else {
                    t = t.assoc(a.k, a.d.unwrap());
                }
            }
            return (Associative) t.persistent();
        } else {
            Associative t = inputs;
            for (KVCons a = added0; a.d != null; a = a.next) {
                if (a.d.free && a.d.own()) {
                    a.d.error(RevokeException.DEFERRED_REVOKED, this.token);
                } else {
                    t = t.assoc(a.k, a.d.unwrap());
                }
            }
            return t;
        }
    }

    AListener canceller() {
        return new AListener() {

            public void success(Object _v) {
                if (added != null) {
                    cancel(null);
                }
            }
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
        CancellationException ex = new CancellationException("yankctx is cancelled");
        if (cause != null) {
            ex.initCause(ex);
        }
        for (KVCons a = this.freeze(); a.d != null; a = a.next) {
            a.d.error(ex, token);
            a.d.consumeError();
        }
    }
}
