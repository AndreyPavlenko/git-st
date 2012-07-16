package com.googlecode.gitst.fastimport;

/**
 * @author Andrey Pavlenko
 */
public abstract class FileChange implements FastimportCommand {
    private final String _path;

    public FileChange(final String path) {
        _path = path;
    }

    public abstract String getComment();

    public String getPath() {
        return _path;
    }
}
