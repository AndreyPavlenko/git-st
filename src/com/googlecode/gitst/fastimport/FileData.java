package com.googlecode.gitst.fastimport;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

import com.starbase.starteam.File;
import com.starbase.starteam.ItemNotFoundException;

/**
 * @author Andrey Pavlenko
 */
public class FileData implements FastimportCommand {
    private final File _file;

    public FileData(final File file) {
        _file = file;
    }

    public File getFile() {
        return _file;
    }

    @Override
    public void write(final PrintStream s) throws IOException {
        final File f = getFile();
        final java.io.File tempFile = java.io.File.createTempFile(f.getName(),
                ".git-st");

        try {
            f.checkoutTo(tempFile, 0, true, false, false);
            s.print("data ");
            s.print(tempFile.length());
            s.print('\n');

            try (InputStream in = new FileInputStream(tempFile)) {
                final byte[] buffer = new byte[8192];
                for (int i = in.read(buffer); i != -1; i = in.read(buffer)) {
                    s.write(buffer, 0, i);
                }
            }

            s.print('\n');
        } catch (final ItemNotFoundException ex) {
            System.err.println("Failed to checkout file: "
                    + (f.getParentFolderHierarchy() + f.getName()).replace(
                            '\\', '/'));
            ex.printStackTrace();
        } finally {
            tempFile.delete();
        }
    }
}
