package com.googlecode.gitst.fastexport;

import java.io.IOException;

import com.googlecode.gitst.Repo;
import com.starbase.starteam.File;
import com.starbase.starteam.Folder;
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
                        + Repo.getParentFolderPath(path));
            }
        }

        final String dest = getDestPath();
        i.setComment(commit.getComment());
        i.moveTo(repo.getOrCreateParentFolder(dest));

        if (i instanceof File) {
            ((File) i).rename(Repo.getFileName(dest));
        } else {
            ((Folder) i).setName(Repo.getFileName(dest));
            ((Folder) i).update();
        }
    }

    @Override
    public String toString() {
        return "R " + Repo.quotePath(getPath()) + " -> "
                + Repo.quotePath(getDestPath());
    }
}
