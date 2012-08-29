package com.googlecode.gitst;

import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @author Andrey Pavlenko
 */
public abstract class SimpleObjectPool<T> {
    private final Queue<T> _idle = new ConcurrentLinkedQueue<>();
    private final Collection<T> _active = new ConcurrentLinkedQueue<>();

    protected abstract T create();

    protected abstract void destroy(T obj);

    public T get() {
        T obj = _idle.poll();

        if (obj == null) {
            obj = create();
        }

        _active.add(obj);
        return obj;
    }

    public void release(final T obj) {
        for (final Iterator<T> it = _active.iterator(); it.hasNext();) {
            if (it.next() == obj) {
                it.remove();
                _idle.add(obj);
                break;
            }
        }
    }

    public void clear() {
        for (final T obj : _idle) {
            destroy(obj);
        }
        for (final T obj : _active) {
            destroy(obj);
        }
    }
}
