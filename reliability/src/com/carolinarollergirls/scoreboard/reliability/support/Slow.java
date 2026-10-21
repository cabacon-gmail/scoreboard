package com.carolinarollergirls.scoreboard.reliability.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class that takes more than about a minute (a whole bout, repeated restarts). A quick run
 * ({@code ant -f reliability/build.xml reliability -Dquick=true}) leaves these out, as if filtered by {@code -Donly};
 * a normal run and {@code verify} include them.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Slow {
    /** Roughly how long the class takes, for the reader. */
    String value() default "";
}
