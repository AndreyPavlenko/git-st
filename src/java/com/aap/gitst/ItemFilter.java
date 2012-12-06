package com.aap.gitst;

import java.util.Collection;
import java.util.Iterator;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;

import com.starbase.starteam.Item;

/**
 * @author Andrey Pavlenko
 */
public class ItemFilter {
    private final Collection<Filter> _filters = new CopyOnWriteArrayList<>();

    public ItemFilter() {
    }

    public ItemFilter(final String filter) {
        if (filter != null) {
            for (final StringTokenizer st = new StringTokenizer(filter, "|"); st
                    .hasMoreTokens();) {
                final StringTokenizer f = new StringTokenizer(st.nextToken(),
                        " ");
                final int user = Integer.parseInt(f.nextToken());
                final double start = Double.parseDouble(f.nextToken());
                final double end = Double.parseDouble(f.nextToken());
                _filters.add(new Filter(user, start, end));
            }
        }
    }

    public void add(final int user, final double startDate, final double endDate) {
        _filters.add(new Filter(user, startDate, endDate));
    }

    public boolean apply(final Item i) {
        return apply(i.getModifiedBy(), i.getModifiedTime().getDoubleValue());
    }

    public boolean apply(final int user, final double date) {
        for (final Filter f : _filters) {
            if (f.apply(user, date)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();

        for (final Iterator<Filter> it = _filters.iterator(); it.hasNext();) {
            final Filter f = it.next();
            sb.append(f._user).append(' ').append(f._start).append(' ')
                    .append(f._end);

            if (it.hasNext()) {
                sb.append('|');
            }
        }

        return sb.toString();
    }

    private static final class Filter {
        final int _user;
        final double _start;
        final double _end;

        public Filter(final int user, final double start, final double end) {
            _user = user;
            _start = start;
            _end = end;
        }

        public boolean apply(final int user, final double date) {
            return (user == _user) && (date >= _start) && (date <= _end);
        }
    }
}
