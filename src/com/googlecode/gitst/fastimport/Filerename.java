package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

import com.starbase.starteam.Item;

/**
 * @author Andrey Pavlenko
 */
public class Filerename extends FileChange {
    private final String _sourcePath;
    private final Item _destItem;

    public Filerename(final String sourcePath, final String destPath,
            final Item destItem) {
        super(destPath);
        _sourcePath = sourcePath;
        _destItem = destItem;
    }

    public String getSourcePath() {
        return _sourcePath;
    }

    public String getDestPath() {
        return getPath();
    }

    public Item getDestItem() {
        return _destItem;
    }

    @Override
    public String getComment() {
        return getDestItem().getComment();
    }

    @Override
    public void write(final PrintStream s) throws IOException {
        s.print("R ");
        s.print(getSourcePath());
        s.print(' ');
        s.print(getDestPath());
        s.print('\n');
    }

    @Override
    public String toString() {
        return "R " + getSourcePath() + " -> " + getDestPath();
    }
}
