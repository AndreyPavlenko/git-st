package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

/**
 * @author Andrey Pavlenko
 */
public class TextData implements FastimportCommand {
    private final String _text;

    public TextData(final String text) {
        _text = text;
    }

    public String getText() {
        return _text;
    }

    @Override
    public void write(final PrintStream s) throws IOException {
        final byte[] data = getText().getBytes("UTF-8");
        s.print("data ");
        s.print(data.length);
        s.print('\n');
        s.write(data);
        s.print('\n');
    }

    @Override
    public String toString() {
        return getText();
    }
}
