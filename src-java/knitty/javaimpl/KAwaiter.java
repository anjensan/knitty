package knitty.javaimpl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Iterator;

import clojure.lang.AFn;
import manifold.deferred.IDeferred;

public final class KAwaiter {

    private static final class Ls extends KDeferred.AListener {

        private final KAwaiter ka;

        Ls(KAwaiter ka) {
            this.ka = ka;
        }

        @Override
        public void success(Object x) {
            if ((int) CNT.getAndAddAcquire(this.ka, (int) -1) == 1) {
                this.ka.ls.invoke();
            }
        }

        @Override
        public void error(Object e) {
            if ((int) CNT.getAndSetAcquire(this.ka, (int) -1) > 0) {
                this.ka.ls.invoke(e);
            }
        }
    }

    private static final class L0 extends KDeferred.AListener {

        private final AFn ls;

        L0(AFn ls) {
            this.ls = ls;
        }

        @Override
        public void error(Object e) {
            ls.invoke(e);
        }

        @Override
        public void success(Object x) {
            ls.invoke();
        }
    }

    private final AFn ls;
    private int acnt = Integer.MAX_VALUE;

    @SuppressWarnings("FieldMayBeFinal")
    private int ncnt = Integer.MAX_VALUE;

    private static final VarHandle CNT;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            CNT = l.findVarHandle(KAwaiter.class, "ncnt", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private KAwaiter(AFn ls) {
        this.ls = ls;
    }

    public boolean await() {
        return (acnt == Integer.MAX_VALUE) || ((int) CNT.getAndAddRelease(this, -acnt) == acnt);
    }

    private static boolean notFailed(KAwaiter ka) {
        return ka == null || ka.ncnt > 0;
    }

    public static KAwaiter start(AFn ls) {
        return new KAwaiter(ls);
    }


    public void add(KDeferred x1) {
        if (x1.state != 1) {
            if (this.acnt <= 0) {
                throw new IllegalStateException("too much deferreds are awaited");
            }
            this.acnt -= 1;
            x1.listen(new Ls(this));
        }
    }

    public static boolean await1(AFn ls, KDeferred x1) {
        if (x1.state == 1) {
            return true;
        } else {
            x1.listen(new L0(ls));
            return false;
        }
    }

    public static boolean awaitArr(AFn ls, Object... ds) {
        KAwaiter ka = start(ls);
        for (int i = 0; i < ds.length && notFailed(ka); ++i) {
            Object d = ds[i];
            if (d instanceof IDeferred) {
                if (d instanceof KDeferred) {
                    ka.add((KDeferred) d);
                } else {
                    ka.add(KDeferred.wrapDeferred((IDeferred) d));
                }
            }
        }
        return ka.await();
    }

    public static void doUnwrapArr(Object[] ds) {
        for (int i = 0; i < ds.length; ++i) {
            Object d = ds[i];
            if (d instanceof IDeferred) {
                ds[i] = KDeferred.unwrap1(d);
            }
        }
    }

    public static void doWrapArr(Object[] ds) {
        for (int i = 0; i < ds.length; ++i) {
            ds[i] = KDeferred.wrap(ds[i]);
        }
    }

    public static boolean awaitIter(AFn ls, Iterator<?> ds) {
        KAwaiter ka = start(ls);
        while (ds.hasNext() && notFailed(ka)) {
            Object d = ds.next();
            if (d instanceof IDeferred) {
                if (d instanceof KDeferred) {
                    ka.add((KDeferred) d);
                } else {
                    ka.add(KDeferred.wrapDeferred((IDeferred) d));
                }
            }
        }
        return ka.await();
    }

    public static final boolean isSucceeded(KDeferred x1) {
        return x1.state == 1;
    }
    public static final boolean isSucceeded(KDeferred x1, KDeferred x2) {
        return (x1.state & x2.state) == 1;
    }
    public static final boolean isSucceeded(KDeferred x1, KDeferred x2, KDeferred x3) {
        return (x1.state & x2.state & x3.state) == 1;
    }
    public static final boolean isSucceeded(KDeferred x1, KDeferred x2, KDeferred x3, KDeferred x4) {
        return (x1.state & x2.state & x3.state & x4.state) == 1;
    }
    public static final boolean isSucceeded(KDeferred x1, KDeferred x2, KDeferred x3, KDeferred x4, KDeferred x5) {
        return (x1.state & x2.state & x3.state & x4.state & x5.state) == 1;
    }
    public static final boolean isSucceeded(KDeferred x1, KDeferred x2, KDeferred x3, KDeferred x4, KDeferred x5, KDeferred x6) {
        return (x1.state & x2.state & x3.state & x4.state & x5.state & x6.state) == 1;
    }
    public static final boolean isSucceeded(KDeferred x1, KDeferred x2, KDeferred x3, KDeferred x4, KDeferred x5, KDeferred x6, KDeferred x7) {
        return (x1.state & x2.state & x3.state & x4.state & x5.state & x6.state & x7.state) == 1;
    }
    public static final boolean isSucceeded(KDeferred x1, KDeferred x2, KDeferred x3, KDeferred x4, KDeferred x5, KDeferred x6, KDeferred x7, KDeferred x8) {
        return (x1.state & x2.state & x3.state & x4.state & x5.state & x6.state & x7.state & x8.state) == 1;
    }
}