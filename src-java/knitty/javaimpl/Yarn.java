package knitty.javaimpl;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;

public interface  Yarn {
    void call(YankCtx ctx, KDeferred dest);
    Keyword getKey();
    IPersistentMap getInfo();
}
