package knitty.javaimpl;

import java.util.Objects;
import java.util.concurrent.Executor;

import clojure.lang.IFn;
import clojure.lang.Var;
import manifold.deferred.IDeferredListener;

abstract class AListener {

    AListener next;
    final Object frame;

    public abstract void success(Object x);
    public abstract void error(Object e);

    AListener() {
        this.frame = Var.cloneThreadBindingFrame();
    }

    protected final void resetFrame() {
        Var.resetThreadBindingFrame(frame);
    }

    public static AListener viaExecutor(AListener ls, Executor executor) {
        return executor == null ? ls : new Ex(ls, executor);
    }

    static final class Dl extends AListener {

        private final IDeferredListener ls;

        Dl(IDeferredListener ls) {
            this.ls = ls;
        }

        public void success(Object x) {
            this.ls.onSuccess(x);
        }

        public void error(Object e) {
            this.ls.onError(e);
        }

        public String toString() {
            return super.toString() + "[ls=" + Objects.toString(ls) + "]";
        }
    }

    static final class Fn extends AListener {

        private final IFn onSucc;
        private final IFn onErr;

        Fn(IFn onSucc, IFn onErr) {
            this.onSucc = onSucc;
            this.onErr = onErr;
        }

        public void success(Object x) {
            if (onSucc != null) {
                this.onSucc.invoke(x);
            }
        }

        public void error(Object e) {
            if (onErr != null) {
                this.onErr.invoke(e);
            }
        }

        public String toString() {
            return super.toString() + "[onSucc=" + Objects.toString(onSucc) + ", onErr=" + Objects.toString(onErr) + "]";
        }
    }

    static final class Ex extends AListener {

        private final AListener ls;
        private final Executor executor;

        Ex(AListener ls, Executor executor) {
            this.ls = ls;
            this.executor = executor;
        }

        public void success(Object x) {
            executor.execute(() -> { this.resetFrame(); this.ls.success(x); });
        }

        public void error(Object e) {
            executor.execute(() -> { this.resetFrame(); this.ls.error(e); });
        }

        public String toString() {
            return super.toString() + "[ls=" + Objects.toString(ls) + ", executor=" + Objects.toString(executor) + "]";
        }
    }
}
