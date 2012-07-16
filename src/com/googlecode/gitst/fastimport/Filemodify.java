package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

/**
 * @author Andrey Pavlenko
 */
public class Filemodify extends FileChange {
    private final FileData _fileData;

    public Filemodify(final String path, final FileData fileData) {
        super(path);
        _fileData = fileData;
    }

    public FileData getFileData() {
        return _fileData;
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
    public String toString() {
        return "M " + getPath();
    }
}
