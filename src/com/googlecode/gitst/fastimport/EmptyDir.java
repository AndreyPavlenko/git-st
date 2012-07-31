package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

import com.googlecode.gitst.Repo;
import com.starbase.starteam.Folder;

/**
 * @author Andrey Pavlenko
 */
public class EmptyDir extends FileChange {
    private final Folder _folder;
    private String _comment;
    private String _path;

    public EmptyDir(final Folder folder) {
        _folder = folder;
    }

    public Folder getFolder() {
        return _folder;
    }

    @Override
    public synchronized String getComment() {
        if (_comment == null) {
            _comment = getFolder().getComment();
        }
        return _comment;
    }

    public synchronized String getPath() {
        if (_path == null) {
            _path = Repo.getPath(getFolder());
        }
        return _path;
    }

    @Override
    public void write(final Repo repo, final PrintStream s) throws IOException,
            InterruptedException {
        final String path = getPath() + "/.gitignore";
        s.print("M 100644 inline ");
        s.print(path);
        s.print('\n');
        s.print("data 0\n");
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public String toString() {
        return "A " + getPath() + ':' + getFolder().getDotNotation();
    }
}
