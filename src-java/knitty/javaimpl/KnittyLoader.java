package knitty.javaimpl;

import java.io.IOException;
import java.net.URL;

import clojure.java.api.Clojure;
import clojure.lang.DynamicClassLoader;
import clojure.lang.IFn;

public class KnittyLoader extends DynamicClassLoader {

    private KnittyLoader() {
        super(KnittyLoader.class.getClassLoader());
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("knitty.javaimpl.")) {
            URL r = this.getResource(name.replace(".", "/") + ".class");
            if (r != null) {
                // Load Knitty java class as clj dynamic classes
                try {
                    byte[] b = r.openStream().readAllBytes();
                    return this.defineClass(name, b, this);
                } catch (IOException e) {
                    throw new ClassNotFoundException("unable to load class", e);
                }
            }
        }
        return super.loadClass(name, resolve);
    }

    public static void touch() {
        // Do nothing.
    }

    static {
        // load manifold.deferred (compile 'definterface')
        IFn require = Clojure.var("clojure.core/require");
        IFn symbol = Clojure.var("clojure.core/symbol");
        require.invoke(symbol.invoke("manifold.deferred"));

        try (KnittyLoader cl = new KnittyLoader()) {
            cl.loadClass("knitty.javaimpl.Yarn");
            cl.loadClass("knitty.javaimpl.YarnProvider");
            cl.loadClass("knitty.javaimpl.RevokeException");
            cl.loadClass("knitty.javaimpl.KDeferred");
            cl.loadClass("knitty.javaimpl.KAwaiter");
            cl.loadClass("knitty.javaimpl.KwMapper");
            cl.loadClass("knitty.javaimpl.YankInputs");
            cl.loadClass("knitty.javaimpl.YankInputsAssoc");
            cl.loadClass("knitty.javaimpl.YankCtx");
            cl.loadClass("knitty.javaimpl.YankResult");
        } catch (ClassNotFoundException | IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
