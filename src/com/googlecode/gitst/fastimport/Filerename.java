package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;

import com.starbase.starteam.File;
import com.starbase.starteam.Item;

/**
 * @author Andrey Pavlenko
 */
public class Filerename extends FileChange {
    private final String _sourcePath;
    private final Item _destItem;

    public Filerename(final String sourcePath, final String destPath,
            final Item destItem) {
        super(destPath);
        _sourcePath = sourcePath;
        _destItem = destItem;
    }

    public String getSourcePath() {
        return _sourcePath;
    }

    public String getDestPath() {
        return getPath();
    }

    public Item getDestItem() {
        return _destItem;
    }

    @Override
    public String getComment() {
        return getDestItem().getComment();
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public void write(final PrintStream s) throws IOException {
        final Item i = getDestItem();

        if (i instanceof File) {
            // Rename fails for new files, so using delete/create instead.
            new Filedelete(getSourcePath()).write(s);
            new Filemodify(getDestPath(), new FileData((File) i), true)
                    .write(s);
        } else {
            s.print("R ");
            s.print(getSourcePath());
            s.print(' ');
            s.print(getDestPath());
            s.print('\n');
        }
    }

    @Override
    public String toString() {
        return "R " + getSourcePath() + " -> " + getDestPath();
    }
}
