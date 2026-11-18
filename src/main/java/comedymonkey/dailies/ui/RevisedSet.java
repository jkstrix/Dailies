package comedymonkey.dailies.ui;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A set in the order things were put in it, which counts every change made to it. Whoever wants to know whether it
 * has changed since they last looked keeps the count it had then: a sum of its members' hashes, as a set's own hash
 * is, can come out the same for two different sets. No game here, so it can be tested on its own.
 */
final class RevisedSet<E> extends AbstractSet<E> {

    private final Set<E> members = new LinkedHashSet<>();
    private long revision = 0;

    /** Goes up with every change; the same number means the same members */
    long revision() {
        return this.revision;
    }

    @Override
    public int size() {
        return this.members.size();
    }

    @Override
    public boolean contains(Object o) {
        return this.members.contains(o);
    }

    @Override
    public boolean add(E e) {
        if (this.members.add(e)) {
            this.revision++;
            return true;
        }
        return false;
    }

    @Override
    public boolean remove(Object o) {
        if (this.members.remove(o)) {
            this.revision++;
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        if (!this.members.isEmpty()) {
            this.members.clear();
            this.revision++;
        }
    }

    @Override
    public Iterator<E> iterator() {
        Iterator<E> inner = this.members.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return inner.hasNext();
            }

            @Override
            public E next() {
                return inner.next();
            }

            @Override
            public void remove() {
                inner.remove();
                RevisedSet.this.revision++;
            }
        };
    }

}
