package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

/**
 * @author Andrey Pavlenko
 */
public class Filemodify extends FileChange {
    private final FileData _fileData;
    private final boolean _isNewFile;

    public Filemodify(final String path, final FileData fileData,
            final boolean isNewFile) {
        super(path);
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
    public String getComment() {
        return getFileData().getFile().getComment();
    }

    @Override
    public void write(final PrintStream s) throws IOException {
        s.print("M 100644 inline ");
        s.print(getPath());
        s.print('\n');
        getFileData().write(s);
    }

    @Override
    public int getPriority() {
        return isNewFile() ? 0 : 1;
    }

    @Override
    public String toString() {
        return (isNewFile() ? "A " : "M ") + getPath();
    }
}
