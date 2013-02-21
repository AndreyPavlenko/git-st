package com.aap.gitst.fastexport;

import java.io.IOException;

import com.aap.gitst.Repo;
import com.aap.gitst.Utils;
import com.starbase.starteam.Item;

/**
 * @author Andrey Pavlenko
 */
public class FileRename extends FileChange {
    private final String _destPath;

    public FileRename(final String path, final String destPath) {
        super(path);
        _destPath = Repo.unquotePath(destPath);
    }

    public String getDestPath() {
        return _destPath;
    }

    @Override
    public void exec(final Repo repo, final Commit commit) throws IOException,
            FastExportException {
        final String path = getPath();
        Item i = repo.getFile(path);

        if (i == null) {
            i = repo.getFolder(path);

            if (i == null) {
                throw new FastExportException("No such file or direcotry: "
                        + Utils.getParentFolderPath(path));
            }
        }

        repo.rename(i, getDestPath(), commit.getComment());
    }

    @Override
    public String toString() {
        return "R " + Repo.quotePath(getPath()) + " -> "
                + Repo.quotePath(getDestPath());
    }
}
