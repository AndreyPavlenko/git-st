package com.aap.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

import com.aap.gitst.Repo;
import com.starbase.starteam.File;

/**
 * @author Andrey Pavlenko
 */
public class FileModify extends FileChange {
    private final FileData _fileData;
    private final boolean _isNewFile;
    private final String _comment;

    public FileModify(final FileData fileData, final boolean isNewFile) {
        _fileData = fileData;
        _isNewFile = isNewFile;
        _comment = fileData.getFile().getComment();
    }

    public FileData getFileData() {
        return _fileData;
    }

    public boolean isNewFile() {
        return _isNewFile;
    }

    @Override
    public String getComment() {
        return _comment;
    }

    public String getPath() {
        return getFileData().getPath();
    }

    @Override
    public void write(final Repo repo, final PrintStream s) throws IOException,
            InterruptedException {
        final FileData data = getFileData();

        if (isExecutable(data.getFile())) {
            s.print("M 100755 inline ");
        } else {
            s.print("M 100644 inline ");
        }

        s.print(getPath());
        s.print('\n');
        data.write(repo, s);
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

    private static boolean isExecutable(final File f) {
        final Object prop = f.get(f.getPropertyNames().FILE_EXECUTABLE);
        return (prop instanceof Number) && (((Number) prop).intValue() == 1);
    }
}
