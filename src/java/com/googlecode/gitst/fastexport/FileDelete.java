package com.googlecode.gitst.fastexport;

import java.io.IOException;

import com.googlecode.gitst.Repo;
import com.starbase.starteam.Item;

/**
 * @author Andrey Pavlenko
 */
public class FileDelete extends FileChange {

    public FileDelete(final String path) {
        super(path);
    }

    @Override
    public String toString() {
        return "D " + getPath();
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

        i.setComment(commit.getComment());
        i.remove();
    }
}
