package knitty.javaimpl;

import clojure.lang.Keyword;

public interface YarnProvider {
    Yarn yarn(Keyword k);
    Yarn[] ycache();
}
