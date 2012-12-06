package com.aap.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

import com.aap.gitst.Repo;

/**
 * @author Andrey Pavlenko
 */
public class FileModify extends FileChange {
    private final FileData _fileData;
    private final boolean _isNewFile;
    private String _comment;

    public FileModify(final FileData fileData, final boolean isNewFile) {
        _fileData = fileData;
        _isNewFile = isNewFile;
    }

    public FileData getFileData() {
        return _fileData;
    }

    public boolean isNewFile() {
        return _isNewFile;
    }

    @Override
    public synchronized String getComment() {
        if (_comment == null) {
            _comment = getFileData().getFile().getComment();
        }
        return _comment;
    }

    public String getPath() {
        return getFileData().getPath();
    }

    @Override
    public void write(final Repo repo, final PrintStream s) throws IOException,
            InterruptedException {
        s.print("M 100644 inline ");
        s.print(getPath());
        s.print('\n');
        getFileData().write(repo, s);
    }

    @Override
    public int getPriority() {
        return isNewFile() ? 0 : 1;
    }

    @Override
    public String toString() {
        return (isNewFile() ? "A " : "M ") + getPath() + ':'
                + getFileData().getFile().getDotNotation();
    }
}
