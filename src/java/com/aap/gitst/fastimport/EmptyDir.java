package com.aap.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

import com.aap.gitst.Repo;
import com.starbase.starteam.Folder;

/**
 * @author Andrey Pavlenko
 */
public class EmptyDir extends FileChange {
    private final Folder _folder;
    private final String _path;
    private String _comment;

    public EmptyDir(final Folder folder, final String path) {
        _folder = folder;
        _path = path;
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

    public String getPath() {
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
