package com.googlecode.gitst.fastimport;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

import com.starbase.starteam.File;

/**
 * @author Andrey Pavlenko
 */
public class Data implements FastimportCommand {
    private final File _file;

    public Data(final File file) {
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
        @SuppressWarnings("resource")
        InputStream in = null;

        try {
            f.checkoutTo(tempFile, 0, true, false, false);
            in = new FileInputStream(tempFile);
            s.print("data ");
            s.print(tempFile.length());
            s.print('\n');

            final byte[] buffer = new byte[8192];
            for (int i = in.read(buffer); i != -1; i = in.read(buffer)) {
                s.write(buffer, 0, i);
            }

            s.print('\n');
        } finally {
            if (in != null) {
                in.close();
            }
            tempFile.delete();
        }
    }
}
