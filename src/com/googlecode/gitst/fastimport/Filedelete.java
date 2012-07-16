package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

/**
 * @author Andrey Pavlenko
 */
public class Filedelete extends FileChange {

    public Filedelete(final String path) {
        super(path);
    }

    @Override
    public void write(final PrintStream s) throws IOException {
        s.print("D ");
        s.print(getPath());
        s.print('\n');
    }

    @Override
    public String getComment() {
        return null;
    }

    @Override
    public String toString() {
        return "D " + getPath();
    }
}
