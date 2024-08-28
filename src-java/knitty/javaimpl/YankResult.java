package knitty.javaimpl;

import java.util.Iterator;

import clojure.lang.AFn;
import clojure.lang.Associative;
import clojure.lang.Delay;
import clojure.lang.IDeref;
import clojure.lang.IEditableCollection;
import clojure.lang.IFn;
import clojure.lang.IMapEntry;
import clojure.lang.IMeta;
import clojure.lang.IObj;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.ITransientAssociative;
import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Reduced;
import clojure.lang.Seqable;
import knitty.javaimpl.YankCtx.KVCons;

public final class YankResult extends YankInputs implements Iterable<Object>, Seqable, IObj, IDeref {

    private static final int ASHIFT = YankCtx.ASHIFT;
    private static final int AMASK = YankCtx.AMASK;

    final YankInputs inputs;
    final KDeferred[][] yrns;
    final YankCtx.KVCons added;
    final KwMapper kwmapper;
    final IPersistentMap meta;

    private final Delay mapDelay = new Delay(new AFn() {
        @Override
        public Object invoke() {
            return toMap0();
        }
    });

    protected YankResult(YankInputs inputs, KDeferred[][] yrns, YankCtx.KVCons added, KwMapper kwmapper) {
        this.inputs = inputs;
        this.yrns = yrns;
        this.added = added;
        this.kwmapper = kwmapper;
        this.meta = (inputs instanceof IMeta) ? ((IMeta) inputs).meta() : null;
    }

    private YankResult(YankInputs inputs, KDeferred[][] yrns, YankCtx.KVCons added, KwMapper kwmapper, IPersistentMap meta) {
        this.inputs = inputs;
        this.yrns = yrns;
        this.added = added;
        this.kwmapper = kwmapper;
        this.meta = meta;
    }

    Object toMap0() {
        KVCons added0 = added;
        if (added0.next == null) {
            return inputs.toAssociative();
        }
        Object result;
        Associative ins = this.inputs.toAssociative();
        if (added0.next.d != null && ins instanceof IEditableCollection) {
            ITransientAssociative t = (ITransientAssociative) ((IEditableCollection) ins).asTransient();
            for (KVCons a = added0; a.d != null; a = a.next) {
                t = t.assoc(a.k, a.d.unwrap());
            }
            result = t.persistent();
        } else {
            Associative t = ins;
            for (KVCons a = added0; a.d != null; a = a.next) {
                t = t.assoc(a.k, a.d.unwrap());
            }
            result = t;
        }
        if (result instanceof IObj) {
            result = ((IObj) result).withMeta(meta);
        }
        return result;
    }

    public Associative toAssociative() {
        return (Associative) mapDelay.deref();
    }

    @Override
    public Object deref() {
        return mapDelay.deref();
    }

    @Override
    public Iterator<Object> iterator() {
        return new Iterator<Object>() {

            final Iterator<?> insIter = (Iterator<?>) RT.iter(inputs);
            volatile YankCtx.KVCons kvcons = added;

            public boolean hasNext() {
                return kvcons.next != null || insIter.hasNext();
            }

            public Object next() {
                if (kvcons.next != null) {
                    IMapEntry e = kvcons;
                    kvcons = kvcons.next;
                    return e;
                }
                return insIter.next();
            }
        };
    }

    @Override
    public ISeq seq() {
        return RT.chunkIteratorSeq(iterator());
    }

    @Override
    public IPersistentMap meta() {
        return meta;
    }

    @Override
    public IObj withMeta(IPersistentMap meta) {
        return new YankResult(inputs, yrns, added, kwmapper, meta);
    }

    @Override
    public Object kvreduce(IFn f, Object a) {
        for (KVCons x = added; x.d != null; x = x.next) {
            a = f.invoke(a, x.k, x.d.unwrap());
            if (a instanceof Reduced) {
                return ((IDeref) a).deref();
            }
        }

        a = inputs.kvreduce(f, a);
        if (a instanceof Reduced) {
            return ((IDeref) a).deref();
        }

        return a;
    }

    @Override
    public Object reduce(IFn f, Object a) {
        for (KVCons x = added; x.d != null; x = x.next) {
            a = f.invoke(a, x);
            if (a instanceof Reduced) {
                return ((IDeref) a).deref();
            }
        }

        a = inputs.reduce(f, a);
        if (a instanceof Reduced) {
            return ((IDeref) a).deref();
        }

        return a;
    }

    @Override
    public Object invoke() {
	    return mapDelay.deref();
    }

    @Override
    public Object get(int i, Keyword k, Object fallback) {
        int i0 = i >> ASHIFT;
        int i1 = i & AMASK;
        KDeferred[] yrns1 = yrns[i0];
        if (yrns1 == null) {
            return inputs.get(i, k, fallback);
        }
        KDeferred r = yrns1[i1];
        return r != null ? r.unwrap() : inputs.get(i, k, fallback);
    }


    @Override
    public Object valAt(Object key, Object notFound) {
        if (key instanceof Keyword) {
            int i = kwmapper.resolveByKeyword((Keyword) key);
            if (i == 0) {
                return null;
            }
            int i0 = i >> ASHIFT;
            int i1 = i & AMASK;
            KDeferred[] yrns1 = yrns[i0];
            if (yrns1 == null) {
                return null;
            }
            return yrns1[i1].unwrap();
        }
        return inputs.valAt(key, notFound);
    }

    @Override
    public Object valAt(Object key) {
        return valAt(key, null);
    }
}
