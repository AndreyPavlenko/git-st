package com.aap.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

import com.aap.gitst.Repo;
import com.starbase.starteam.File;
import com.starbase.starteam.Item;

/**
 * @author Andrey Pavlenko
 */
public class FileRename extends FileChange {
    private final Item _sourceItem;
    private final Item _destItem;
    private final FileModify _fileModify;
    private final String _sourcePath;
    private final String _destPath;
    private String _comment;

    public FileRename(final Item sourceItem, final Item destItem,
            final String sourcePath, final String destPath) {
        _sourceItem = sourceItem;
        _destItem = destItem;
        _sourcePath = sourcePath;
        _destPath = destPath;

        if (destItem instanceof File) {
            _fileModify = new FileModify(
                    new FileData((File) destItem, destPath), true);
        } else {
            _fileModify = null;
        }
    }

    public Item getSourceItem() {
        return _sourceItem;
    }

    public Item getDestItem() {
        return _destItem;
    }

    public String getSourcePath() {
        return _sourcePath;
    }

    public String getDestPath() {
        return _destPath;
    }

    public FileModify getFileModify() {
        return _fileModify;
    }

    @Override
    public synchronized String getComment() {
        if (_comment == null) {
            _comment = getDestItem().getComment();
        }
        return _comment;
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public void write(final Repo repo, final PrintStream s) throws IOException,
            InterruptedException {
        final FileModify mod = getFileModify();

        if (mod != null) {
            if (repo.getFile(getSourcePath()) == null) {
                // Git performs rename detection.
                new FileDelete(getSourceItem(), getSourcePath()).write(repo, s);
            }

            mod.write(repo, s);
        } else {
            s.print("R ");
            s.print(Repo.quotePath(getSourcePath()));
            s.print(' ');
            s.print(Repo.quotePath(getDestPath()));
            s.print('\n');
        }
    }

    @Override
    public String toString() {
        return "R " + getSourcePath() + ':' + getSourceItem().getDotNotation()
                + " -> " + getDestPath() + ':' + getDestItem().getDotNotation();
    }
}
