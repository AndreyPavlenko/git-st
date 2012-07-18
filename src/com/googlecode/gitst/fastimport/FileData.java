package com.googlecode.gitst.fastimport;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import com.starbase.starteam.CheckoutManager;
import com.starbase.starteam.File;

/**
 * @author Andrey Pavlenko
 */
public class FileData implements FastimportCommand {
    private static final int MAX_FILE_SIZE = 1024 * 1024 * 10;
    private final File _file;
    private Future<java.io.File> _checkout;

    public FileData(final File file) {
        _file = file;
    }

    public File getFile() {
        return _file;
    }

    @Override
    public void write(final PrintStream s) throws IOException {
        s.print("data ");

        if (_checkout != null) {
            try {
                final java.io.File tempFile = _checkout.get();
                writeFile(tempFile, s);
            } catch (final InterruptedException ex) {
                throw new RuntimeException(ex);
            } catch (final ExecutionException ex) {
                throw new RuntimeException(ex.getCause());
            }
        } else {
            final File f = getFile();
            final long size = f.getSizeEx();

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
                writeFile(tempFile, s);
            }
        }

        s.print('\n');
    }

    public void checkout(final CheckoutManager mgr,
            final ExecutorService executor) {
        _checkout = executor.submit(new Checkout(mgr, getFile()));
    }

    private static void writeFile(final java.io.File file, final PrintStream s)
            throws IOException {
        s.print(file.length());
        s.print('\n');

        try (InputStream in = new FileInputStream(file)) {
            final byte[] buffer = new byte[8192];
            for (int i = in.read(buffer); i != -1; i = in.read(buffer)) {
                s.write(buffer, 0, i);
            }
        } finally {
            file.delete();
        }
    }

    private static final class Checkout implements Callable<java.io.File> {
        private final CheckoutManager _mgr;
        private final File _file;

        public Checkout(final CheckoutManager mgr, final File file) {
            _mgr = mgr;
            _file = file;
        }

        @Override
        public java.io.File call() throws Exception {
            final java.io.File tempFile = java.io.File.createTempFile(
                    _file.getName(), ".git-st");
            tempFile.deleteOnExit();
            _mgr.checkoutTo(_file, tempFile);
            return tempFile;
        }
    }
}
