package com.googlecode.gitst.fastexport;

import java.io.IOException;

import com.googlecode.gitst.Repo;

/**
 * @author Andrey Pavlenko
 */
public abstract class FileChange implements FastExportCommand {
    private final String _path;

    public FileChange(final String path) {
        _path = Repo.unquotePath(path);
    }

    public abstract void exec(final Repo repo, Commit cmt) throws IOException,
            FastExportException;

    public String getPath() {
        return _path;
    }
}
