package knitty.javaimpl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Iterator;

import clojure.lang.AFn;
import manifold.deferred.IDeferred;

public final class KAwaiter {

    private static final class Ls extends AListener {

        private final KAwaiter ka;

        Ls(KAwaiter ka) {
            this.ka = ka;
        }

        public void success(Object x) {
            if ((int) CNT.getAndAddAcquire(this.ka, (int) -1) == 1) {
                this.ka.ls.invoke();
            }
        }

        public void error(Object e) {
            if ((int) CNT.getAndSetAcquire(this.ka, (int) -1) > 0) {
                this.ka.ls.invoke(e);
            }
        }
    }

    private static final class Lsv extends AFn {

        private final KAwaiter ka;

        Lsv(KAwaiter ka) {
            this.ka = ka;
        }

        @Override
        public Object invoke(Object x) {
            if ((int) CNT.getAndAddAcquire(this.ka, (int) -1) == 1) {
                this.ka.ls.invoke();
            }
            return null;
        }
    }

    private static final class Lse extends AFn {

        private final KAwaiter ka;

        Lse(KAwaiter ka) {
            this.ka = ka;
        }

        @Override
        public Object invoke(Object x) {
            if ((int) CNT.getAndAddAcquire(this.ka, (int) -1) == 1) {
                this.ka.ls.invoke();
            }
            return null;
        }
    }

    private static final class L0 extends AListener {
        final AFn ls;

        L0(AFn ls) {
            this.ls = ls;
        }

        public void error(Object e) {
            ls.invoke(e);
        }

        public void success(Object x) {
            ls.invoke();
        }
    }

    private final AFn ls;
    private int acnt = Integer.MAX_VALUE;
    private int _cnt = Integer.MAX_VALUE;

    private static final VarHandle CNT;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            CNT = l.findVarHandle(KAwaiter.class, "_cnt", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private KAwaiter(AFn ls) {
        this.ls = ls;
    }

    public boolean await() {
        return (acnt == Integer.MAX_VALUE) || ((int) CNT.getAndAddRelease(KAwaiter.this, -acnt) == acnt);
    }

    private static boolean failed(KAwaiter ka) {
        return ka != null && ((int) CNT.getOpaque(ka)) <= 0;
    }

    public static KAwaiter start(AFn ls) {
        return new KAwaiter(ls);
    }

    private static KAwaiter start(KAwaiter ka, AFn ls) {
        if (ka == null) {
            return new KAwaiter(ls);
        } else if (ka.acnt <= 4) {
            throw new IllegalStateException("too much deferreds are awaited");
        } else {
            return ka;
        }
    }

    private void with0(KDeferred d1) {
        this.acnt -= 1;
        d1.listen(new Ls(this));
    }

    private AFn lsv;
    private AFn lse;

    private void with0(IDeferred d1) {
        this.acnt -= 1;
        if (lsv == null) {
            lsv = new Lsv(this);
            lse = new Lse(this);
        }
        d1.onRealized(lsv, lse);
    }

    private static KAwaiter with(KAwaiter ka, AFn ls, IDeferred x1) {
        if (x1.successValue(x1) != x1) {
            return ka;
        } else {
            ka = start(ka, ls);
            ka.with0(x1);
            return ka;
        }
    }

    public static KAwaiter with(KAwaiter ka, AFn ls, KDeferred x1) {
        int s1 = x1.succeeded;
        if (s1 == 1) {
            return ka;
        } else {
            ka = start(ka, ls);
            ka.with0(x1);
            return ka;
        }
    }

    public static KAwaiter with(KAwaiter ka, AFn ls, KDeferred x1, KDeferred x2) {
        byte s1 = x1.succeeded;
        byte s2 = x2.succeeded;
        if ((s1 & s2) == 0) {
            ka = start(ka, ls);
            if (s1 == 0) ka.with0(x1);
            if (s2 == 0) ka.with0(x2);
        }
        return ka;
    }

    public static KAwaiter with(KAwaiter ka, AFn ls, KDeferred x1, KDeferred x2, KDeferred x3) {
        byte s1 = x1.succeeded;
        byte s2 = x2.succeeded;
        byte s3 = x3.succeeded;
        if ((s1 & s2 & s3) == 0) {
            ka = start(ka, ls);
            if (s1 == 0) ka.with0(x1);
            if (s2 == 0) ka.with0(x2);
            if (s3 == 0) ka.with0(x3);
        }
        return ka;
    }

    public static KAwaiter with(KAwaiter ka, AFn ls, KDeferred x1, KDeferred x2, KDeferred x3, KDeferred x4) {
        byte s1 = x1.succeeded;
        byte s2 = x2.succeeded;
        byte s3 = x3.succeeded;
        byte s4 = x4.succeeded;
        if ((s1 & s2 & s3 & s4) == 0) {
            ka = start(ka, ls);
            if (s1 == 0) ka.with0(x1);
            if (s2 == 0) ka.with0(x2);
            if (s3 == 0) ka.with0(x3);
            if (s4 == 0) ka.with0(x4);
        }
        return ka;
    }

    public static boolean await(KAwaiter ka) {
        return ka == null || ka.await();
    }

    public static boolean await1(AFn ls, KDeferred x1) {
        if (x1.succeeded == 1) {
            return true;
        } else {
            x1.listen(new L0(ls));
            return false;
        }
    }

    public static boolean awaitArr(AFn ls, Object... ds) {
        KAwaiter ka = null;
        for (int i = 0; i < ds.length; ++i) {
            Object d = ds[i];
            if (d instanceof IDeferred) {
                if (d instanceof KDeferred) {
                    ka = with(ka, ls, (KDeferred) d);
                } else {
                    ka = with(ka, ls, (IDeferred) d);
                }
            }
        }
        return await(ka);
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
        KAwaiter ka = null;
        while (ds.hasNext()) {
            Object d = ds.next();
            if (d instanceof IDeferred) {
                if (d instanceof KDeferred) {
                    ka = with(ka, ls, (KDeferred) d);
                } else {
                    ka = with(ka, ls, (IDeferred) d);
                }
            }
        }
        return await(ka);
    }
}