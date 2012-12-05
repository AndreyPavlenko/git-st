package com.googlecode.gitst;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * @author Andrey Pavlenko
 */
public class Marks extends TreeMap<Integer, String> {
    private static final long serialVersionUID = 555470143340036599L;

    public Marks() {
    }

    public Marks(final File file) throws IOException {
        load(file);
    }

    public Marks(final InputStream stream) throws IOException {
        load(stream);
    }

    public Marks load(final File file) throws IOException {
        return load(new BufferedInputStream(new FileInputStream(file)));
    }

    public Marks load(final InputStream stream) throws IOException {
        try (InputStream in = stream) {
            final StreamReader r = new StreamReader(in);
            for (String s = r.readLine(); s != null; s = r.readLine()) {
                final int colon = s.indexOf(':');
                final int space = s.indexOf(' ');
                final int mark = Integer
                        .parseInt(s.substring(colon + 1, space));
                final String sha = s.substring(space + 1).trim();
                put(mark, sha);
            }
        }

        return this;
    }

    public void store(final File file) throws IOException {
        file.getParentFile().mkdirs();
        store(new BufferedOutputStream(new FileOutputStream(file)));
    }

    public void store(final OutputStream stream) throws IOException {
        final StringBuilder sb = new StringBuilder();
        try (OutputStream out = stream) {
            for (final Map.Entry<Integer, String> e : entrySet()) {
                sb.setLength(0);
                sb.append(':');
                sb.append(e.getKey());
                sb.append(' ');
                sb.append(e.getValue());
                sb.append('\n');
                out.write(sb.toString().getBytes());
            }
        }
    }
}
