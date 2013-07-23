/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.kernel.commons;

import java.util.Collection;
import java.util.List;

/**
 * Provides a simple {@link Collector} which is intended to be subclassed.
 *
 * @author Andreas Haufler (aha@scireum.de)
 * @since 1.0
 */
public abstract class BasicCollector<T> extends Collector<T> {

    @Override
    public abstract void add(T entity);

    @Override
    public void addAll(Collection<? extends T> entities) {
        for (T entity : entities) {
            add(entity);
        }
    }

    @Override
    public List<T> getData() {
        throw new UnsupportedOperationException();
    }

}
