package com.aap.gitst;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;

/**
 * @author Andrey Pavlenko
 */
public class StreamReader {
    private final InputStream _stream;
    private final Charset _charset;
    private final byte[] _buf = new byte[8192];
    private final ArrayList<String> _list = new ArrayList<>();

    public StreamReader(final InputStream stream) {
        _stream = stream;
        _charset = Charset.forName("UTF8");
    }

    public InputStream getStream() {
        return _stream;
    }

    public String readLine() throws IOException {
        int i = 0;
        int b = read();

        for (; b != -1; b = read()) {
            if (b == '\n') {
                break;
            }

            _buf[i++] = (byte) b;
        }

        return (i == 0) && (b == -1) ? null : new String(_buf, 0, i, _charset);
    }

    public int read() throws IOException {
        return getStream().read();
    }

    public int read(final byte[] b) throws IOException {
        return getStream().read(b);
    }

    public int read(final byte[] b, final int off, final int len)
            throws IOException {
        return getStream().read(b, off, len);
    }

    public void skip(final long nbytes) throws IOException {
        getStream().skip(nbytes);
    }

    public String[] split(final String line) {
        int i = 0;
        int prev = 0;
        _list.clear();

        for (; i < line.length(); i++) {
            if (line.charAt(i) == ' ') {
                _list.add(line.substring(prev, i));
                prev = i + 1;
            }
        }
        if (prev != line.length()) {
            _list.add(line.substring(prev, i));
        }

        return _list.toArray(new String[_list.size()]);
    }

    public String[] split(final String line, final boolean handleQuotes) {
        if (!handleQuotes) {
            return split(line);
        } else {
            int i = 0;
            int prev = 0;
            boolean quoted = false;
            _list.clear();

            for (; i < line.length(); i++) {
                final char c = line.charAt(i);

                switch (c) {
                case ' ':
                    if (!quoted) {
                        _list.add(line.substring(prev, i));
                        prev = i + 1;
                    }

                    break;
                case '"':
                    if (quoted) {
                        quoted = false;
                    } else {
                        quoted = true;
                    }

                    break;
                }
            }
            if (prev != line.length()) {
                _list.add(line.substring(prev, i));
            }

            return _list.toArray(new String[_list.size()]);
        }
    }
}
