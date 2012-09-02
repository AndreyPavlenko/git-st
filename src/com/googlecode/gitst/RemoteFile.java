package com.googlecode.gitst;

import com.starbase.starteam.File;

/**
 * @author Andrey Pavlenko
 */
public class RemoteFile {
    private final File _file;
    private final String _version;

    public RemoteFile(final File file) {
        _file = file;
        _version = file.getDotNotation();
    }

    @Override
    public int hashCode() {
        return _file.getID() | _version.hashCode();
    }

    public File get() {
        return _file;
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof RemoteFile) {
            final RemoteFile f = (RemoteFile) obj;
            return get().equals(f.get()) && (_version.equals(f._version));
        }
        return false;
    }
}
