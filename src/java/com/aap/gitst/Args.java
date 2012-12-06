package com.aap.gitst;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author Andrey Pavlenko
 */
public class Args {
    private final String[] _args;
    private final Map<String, String> _map;
    private final Set<String> _options;

    public Args(final String[] args) {
        _args = args;
        _map = new HashMap<String, String>();
        _options = new HashSet<>();

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) {
                if (i < (args.length - 1)) {
                    if (!args[i + 1].startsWith("-")) {
                        _map.put(args[i], args[++i]);
                    } else {
                        _options.add(args[i]);
                    }
                } else {
                    _options.add(args[i]);
                }
            }
        }
    }

    public String[] getArgs() {
        return _args;
    }

    public String get(final String name) {
        final String value = _map.get(name);

        if (value == null) {
            throw new IllegalArgumentException("Missing required argument: "
                    + name);
        }

        return value;
    }

    public String get(final String name, final String defaultValue) {
        final String value = _map.get(name);
        return (value == null) ? defaultValue : value;
    }

    public int getInt(final String name) {
        final String value = get(name);
        return Integer.parseInt(value);
    }

    public int get(final String name, final int defaultValue) {
        final String value = _map.get(name);
        return (value == null) ? defaultValue : Integer.parseInt(value);
    }

    public boolean hasOption(String name) {
        return _options.contains(name);
    }
}
