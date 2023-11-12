package ag.knitty;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Obj;
import clojure.lang.RT;
import clojure.lang.Util;

import manifold.deferred.IDeferred;
import manifold.deferred.IDeferredListener;
import manifold.deferred.IMutableDeferred;

public final class KaDeferred
    implements
    clojure.lang.IDeref,
    clojure.lang.IBlockingDeref,
    clojure.lang.IPending,
    clojure.lang.IReference,
    IDeferred,
    IDeferredListener,
    IMutableDeferred {

  private static class FnListener implements IDeferredListener {
    private final IFn onSucc;
    private final IFn onErr;

    FnListener(IFn onSucc, IFn onErr) {
      this.onSucc = onSucc;
      this.onErr = onErr;
    }

    public Object onSuccess(Object x) {
      return this.onSucc.invoke(x);
    }

    public Object onError(Object e) {
      return this.onErr.invoke(e);
    }
  }

  private static class CountDownListener implements IDeferredListener {
    final CountDownLatch cdl;

    CountDownListener(CountDownLatch cdl) {
      this.cdl = cdl;
    }

    public Object onSuccess(Object x) {
      cdl.countDown();
      return null;
    }

    public Object onError(Object e) {
      cdl.countDown();
      return null;
    }
  }

  private static class AwaiterListener implements IDeferredListener {

    private final Object[] da;
    private final IDeferredListener ls;
    private int i;
    private IDeferred cd;

    public AwaiterListener(Object[] da, IDeferredListener ls) {
      this.da = da;
      this.ls = ls;
    }

    class SuccCallback extends AFn {
      public Object invoke(Object x) {
        return AwaiterListener.this.onSuccess(x);
      }
    }

    class FailCallback extends AFn {
      public Object invoke(Object x) {
        return AwaiterListener.this.onError(x);
      }
    }

    public Object onError(Object e) {
      return ls.onError(e);
    }

    public Object onSuccess(Object x) {
      Object d = this.cd;

      while (true) {

        while (d instanceof IDeferred) {
          IDeferred dd = (IDeferred) d;
          Object ndd = dd.successValue(dd);
          if (dd == ndd) {
            this.cd = dd;
            if (dd instanceof IMutableDeferred) {
              ((IMutableDeferred) d).addListener(this);
            } else {
              dd.onRealized(new SuccCallback(), new FailCallback());
            }
            return null;
          } else {
            d = ndd;
          }
        }

        if (i == da.length) break;
        d = this.da[i++];
      }

      try {
        ls.onSuccess(null);
      } catch (Throwable e) {
        LOG_EXCEPTION.invoke(e);
      }
      return null;
    }

  }

  private static final int STATE_INIT = 0;
  private static final int STATE_TRNS = 1;
  private static final int STATE_SUCC = 2;
  private static final int STATE_ERRR = 4;
  private static final int STATE_READY_MASK = STATE_SUCC | STATE_ERRR;

  private static final int KALIST_INIT_CAPACITY = 5;

  private static final VarHandle STATE;
  private static final VarHandle LSC;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      STATE = l.findVarHandle(KaDeferred.class, "state", Integer.TYPE);
      LSC = l.findVarHandle(KaDeferred.class, "lsc", KaList.class);
    } catch (ReflectiveOperationException var1) {
      throw new ExceptionInInitializerError(var1);
    }
  }

  private static volatile IFn LOG_EXCEPTION = new AFn() {
    public Object invoke(Object ex) {
      ((Throwable) ex).printStackTrace();
      return null;
    }
  };

  public static void setExceptionLogFn(IFn f) {
    LOG_EXCEPTION = f;
  }

  private volatile KaList<IDeferredListener> lsc;
  private volatile int state;
  private Object value; // sync by 'state'
  private IPersistentMap meta; // sychronized
  private volatile IMutableDeferred revokee;
  private volatile IFn errorMod;

  public synchronized IPersistentMap meta() {
    return meta;
  }

  public synchronized IPersistentMap alterMeta(IFn alter, ISeq args) {
    this.meta = (IPersistentMap) alter.applyTo(RT.listStar(meta, args));
    return this.meta;
  }

  public synchronized IPersistentMap resetMeta(IPersistentMap m) {
    this.meta = m;
    return m;
  }

  public Object success(Object x) {

    if (!STATE.compareAndSet(this, STATE_INIT, STATE_TRNS)) {
      return null;
    }
    this.value = x;
    if (!STATE.compareAndSet(this, STATE_TRNS, STATE_SUCC)) {
      return null;
    }

    IMutableDeferred r = this.revokee;
    if (r != null) {
      this.revokee = null;
      try {
        r.success(x);
      } catch (Throwable e) {
        LOG_EXCEPTION.invoke(e);
      }
    }

    KaList<IDeferredListener> lsc = this.lsc;
    if (lsc != null) {
      this.lsc = null;
      for (IDeferredListener ls : lsc)
        try {
          ls.onSuccess(value);
        } catch (Throwable e) {
          LOG_EXCEPTION.invoke(e);
        }
    }

    return null;
  }

  public Object error(Object x) {

    if (!STATE.compareAndSet(this, STATE_INIT, STATE_TRNS)) {
      return null;
    }
    this.value = x;
    if (!STATE.compareAndSet(this, STATE_TRNS, STATE_ERRR)) {
      return null;
    }

    IFn aerr = this.errorMod;
    if (aerr != null) {
      x = aerr.invoke(x);
    }

    IMutableDeferred r = this.revokee;
    if (r != null) {
      this.revokee = null;
      try {
        r.error(x);
      } catch (Throwable e) {
        LOG_EXCEPTION.invoke(e);
      }
    }

    KaList<IDeferredListener> lsc = this.lsc;
    if (lsc != null) {
      this.lsc = null;
      for (IDeferredListener ls : lsc)
        try {
          ls.onError(value);
        } catch (Throwable e) {
          LOG_EXCEPTION.invoke(e);
        }
    }

    return null;
  }

  public Object addListener(Object lss) {

    IDeferredListener ls = (IDeferredListener) lss;

    while (true) {
      switch (state) {
        case STATE_SUCC: return ls.onSuccess(value);
        case STATE_ERRR: return ls.onError(value);
        case STATE_TRNS: Thread.onSpinWait();
        case STATE_INIT:
          KaList<IDeferredListener> lsc = this.lsc;
          if (lsc == null) {
            lsc = new KaList<IDeferredListener>(KALIST_INIT_CAPACITY);
            KaList<IDeferredListener> oldLsc = (KaList<IDeferredListener>) LSC.compareAndExchange(this, null, lsc);
            lsc = oldLsc == null ? lsc : oldLsc;
            if (state != STATE_INIT) continue;
          }
          if (lsc.push(ls)) return null; else continue;
        default:
          throw new IllegalStateException();
      }
    }
  }

  public void setRevokee(Object x) {
    if (x instanceof IMutableDeferred) {
      IMutableDeferred r = (IMutableDeferred) x;
      this.revokee = r;
      while (true) {
        switch (state) {
          case STATE_SUCC: r.success(value); return;
          case STATE_ERRR: r.error(value); return;
          case STATE_TRNS: Thread.onSpinWait(); continue;
          case STATE_INIT: return;
          default: throw new IllegalStateException();
        }
      }
    }
  }

  public void setErrorMod(Object x) {
    this.errorMod = (IFn) x;
  }

  public Object onRealized(Object onSucc, Object onErr) {
    return this.addListener(new FnListener((IFn) onSucc, (IFn) onErr));
  }

  public Object cancelListener(Object listener) {
    throw new UnsupportedOperationException("cancelling of listeners is not supported");
  }

  public Object claim() {
    return null;
  }

  public Object error(Object x, Object token) {
    throw new UnsupportedOperationException("claiming is not supported");
  }

  public Object success(Object x, Object token) {
    throw new UnsupportedOperationException("claiming is not supported");
  }

  public Object onSuccess(Object x) {
    return this.success(x);
  }

  public Object onError(Object e) {
    return this.error(e);
  }

  public Object executor() {
    return null;
  }

  public boolean realized() {
    return (state & STATE_READY_MASK) != 0;
  }

  public boolean isRealized() {
    return (state & STATE_READY_MASK) != 0;
  }

  public Object successValue(Object fallback) {
    return state == STATE_SUCC ? value : fallback;
  }

  public Object errorValue(Object fallback) {
    return state == STATE_ERRR ? value : fallback;
  }

  public Object deref(long ms, Object timeoutValue) {

    switch (state) {
      case STATE_SUCC: return value;
      case STATE_ERRR: throw Util.sneakyThrow((Throwable) value);
    }

    try {
      acquireCountdDownLatch().await(ms, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      throw Util.sneakyThrow(e);
    }

    switch (state) {
      case STATE_SUCC: return value;
      case STATE_ERRR: throw Util.sneakyThrow((Throwable) value);
    }

    return timeoutValue;
  }

  public Object deref() {

    switch (state) {
      case STATE_SUCC: return value;
      case STATE_ERRR: throw Util.sneakyThrow((Throwable) value);
    }

    try {
      acquireCountdDownLatch().await();
    } catch (InterruptedException e) {
      throw Util.sneakyThrow(e);
    }

    switch (state) {
      case STATE_SUCC: return value;
      case STATE_ERRR: throw Util.sneakyThrow((Throwable) value);
    }

    throw new IllegalStateException();
  }

  private CountDownLatch acquireCountdDownLatch() {
    CountDownLatch cdl = new CountDownLatch(1);
    this.addListener(new CountDownListener(cdl));
    return cdl;
  }

  public static void awaitAll(IDeferredListener ls, Object... ds) {
    AwaiterListener al = new AwaiterListener(ds, ls);
    al.onSuccess(null);
  }
}