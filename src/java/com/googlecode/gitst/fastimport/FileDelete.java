package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

import com.googlecode.gitst.Repo;
import com.starbase.starteam.Item;

/**
 * @author Andrey Pavlenko
 */
public class FileDelete extends FileChange {
    private final Item _item;
    private final String _path;

    public FileDelete(final Item item, final String path) {
        _item = item;
        _path = path;
    }

    public Item getItem() {
        return _item;
    }

    public String getPath() {
        return _path;
    }

    @Override
    public String getComment() {
        return null;
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public void write(final Repo repo, final PrintStream s) throws IOException {
        s.print("D ");
        s.print(getPath());
        s.print('\n');
    }

    @Override
    public String toString() {
        return "D " + getPath() + ':' + getItem().getDotNotation();
    }
}
