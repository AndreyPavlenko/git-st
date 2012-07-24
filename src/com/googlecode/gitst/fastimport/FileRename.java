package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

import com.googlecode.gitst.Repo;
import com.starbase.starteam.File;
import com.starbase.starteam.Item;

/**
 * @author Andrey Pavlenko
 */
public class FileRename extends FileChange {
    private final Item _sourceItem;
    private final Item _destItem;
    private String _sourcePath;
    private String _destPath;
    private String _comment;

    public FileRename(final Item sourceItem, final Item destItem) {
        _sourceItem = sourceItem;
        _destItem = destItem;
    }

    public FileRename(final Item sourceItem, final Item destItem,
            final String sourcePath, final String destPath) {
        _sourceItem = sourceItem;
        _destItem = destItem;
        _sourcePath = sourcePath;
        _destPath = destPath;
    }

    public Item getSourceItem() {
        return _sourceItem;
    }

    public Item getDestItem() {
        return _destItem;
    }

    public synchronized String getSourcePath() {
        if (_sourcePath == null) {
            _sourcePath = Repo.getPath(getSourceItem());
        }
        return _sourcePath;
    }

    public synchronized String getDestPath() {
        if (_destPath == null) {
            _destPath = Repo.getPath(getDestItem());
        }
        return _destPath;
    }

    @Override
    public synchronized String getComment() {
        if (_comment == null) {
            _comment = getDestItem().getComment();
        }
        return _comment;
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public void write(final Repo repo, final PrintStream s) throws IOException,
            InterruptedException {
        // Git performs rename detection.
        final Item i = getDestItem();
        new FileDelete(getSourceItem()).write(repo, s);

        if (i instanceof File) {
            new FileModify(new FileData((File) i), true).write(repo, s);
        }
    }

    @Override
    public String toString() {
        return "R " + Repo.quotePath(getSourcePath()) + " -> "
                + Repo.quotePath(getDestPath());
    }
}
