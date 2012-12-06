package com.aap.gitst.fastexport;

import java.io.IOException;

import com.aap.gitst.Repo;
import com.starbase.starteam.File;

/**
 * @author Andrey Pavlenko
 */
public class FileModify extends FileChange {
    private final String _dataref;
    private File _file;
    private java.io.File _localFile;

    public FileModify(final String dataref, final String path) {
        super(path);
        _dataref = dataref;
    }

    public String getDataref() {
        return _dataref;
    }

    public synchronized File getFile(final Repo repo) {
        if (_file == null) {
            final String path = getPath();
            _file = repo.getOrCreateFile(path);
        }
        return _file;
    }

    public synchronized java.io.File getLocalFile(final Repo repo)
            throws IOException {
        if ((_localFile == null) || !_localFile.exists()) {
            _localFile = repo.getGit().catFile(getDataref(),
                    repo.createTempFile(getPath()));;
        }
        return _localFile;
    }

    @Override
    public void exec(final Repo repo, final Commit commit) throws IOException,
            FastExportException {
        throw new UnsupportedOperationException();
    }

    @Override
    public synchronized String toString() {
        return (((_file != null) && _file.isNew()) ? "A " : "M ") + getPath();
    }
}
