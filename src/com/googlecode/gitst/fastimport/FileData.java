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

import com.googlecode.gitst.Repo;
import com.starbase.starteam.CheckoutManager;
import com.starbase.starteam.File;

/**
 * @author Andrey Pavlenko
 */
public class FileData implements FastimportCommand {
    private static final int MAX_FILE_SIZE = 1024 * 1024 * 10;
    private final File _file;
    private String _path;
    private Future<java.io.File> _checkout;

    public FileData(final File file) {
        _file = file;
    }

    public FileData(final File file, final String path) {
        _file = file;
        _path = path;
    }

    public File getFile() {
        return _file;
    }

    public synchronized String getPath() {
        if (_path == null) {
            _path = Repo.getPath(getFile());
        }
        return _path;
    }

    public Future<java.io.File> getCheckout() {
        return _checkout;
    }

    @Override
    public void write(final Repo repo, final PrintStream s) throws IOException,
            InterruptedException {
        s.print("data ");
        final Future<java.io.File> checkout = getCheckout();

        if (checkout != null) {
            try {
                writeFile(checkout.get(), s);
            } catch (final ExecutionException ex) {
                throw new IOException(ex.getCause());
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
                final java.io.File tempFile = repo.createTempFile(Repo
                        .getPath(f));
                f.checkoutTo(tempFile, 0, false, false, false);
                writeFile(tempFile, s);
            }
        }

        s.print('\n');
    }

    public synchronized Future<java.io.File> checkout(final Repo repo,
            final CheckoutManager cmgr, final ExecutorService executor)
            throws IOException {
        if (_checkout == null) {
            _checkout = executor.submit(new Checkout(repo, cmgr, getFile()));
        }
        return _checkout;
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

    private final class Checkout implements Callable<java.io.File> {
        private final Repo _repo;
        private final CheckoutManager _mgr;
        private final File _file;

        public Checkout(final Repo repo, final CheckoutManager mgr,
                final File file) {
            _repo = repo;
            _mgr = mgr;
            _file = file;
        }

        @Override
        public java.io.File call() throws IOException {
            final java.io.File tempFile = _repo.createTempFile(getPath());
            synchronized (_mgr) {
                _mgr.checkoutTo(_file, tempFile);
            }
            return tempFile;
        }
    }
}
