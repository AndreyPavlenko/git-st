package com.googlecode.gitst.fastimport;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

import com.starbase.starteam.File;

/**
 * @author Andrey Pavlenko
 */
public class FileData implements FastimportCommand {
    private static final int MAX_FILE_SIZE = 1024 * 1024 * 10;
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
        final long size = f.getSizeEx();
        s.print("data ");

        if (size < MAX_FILE_SIZE) {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream(
                    (int) (size + (size / 20)));
            f.checkoutToStream(baos, 0, false);
            s.print(baos.size());
            s.print('\n');
            baos.writeTo(s);
        } else {
            final java.io.File tempFile = java.io.File.createTempFile(
                    f.getName(), ".git-st");

            try {
                f.checkoutTo(tempFile, 0, true, false, false);
                s.print(tempFile.length());
                s.print('\n');

                try (InputStream in = new FileInputStream(tempFile)) {
                    final byte[] buffer = new byte[8192];
                    for (int i = in.read(buffer); i != -1; i = in.read(buffer)) {
                        s.write(buffer, 0, i);
                    }
                }
            } finally {
                tempFile.delete();
            }
        }

        s.print('\n');
    }
}
