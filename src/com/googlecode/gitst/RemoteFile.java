package com.googlecode.gitst;

import com.starbase.starteam.File;

/**
 * @author Andrey Pavlenko
 */
public class RemoteFile {
    private final File _file;
    private final int _rev;

    public RemoteFile(final File file) {
        _file = file;
        _rev = file.getRevisionNumber();
    }

    @Override
    public int hashCode() {
        return _file.getID() | (_rev << 24);
    }

    public File get() {
        return _file;
    }

    public int getRevision() {
        return _rev;
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof RemoteFile) {
            final RemoteFile f = (RemoteFile) obj;
            return get().equals(f.get()) && (getRevision() == f.getRevision());
        }
        return false;
    }
}
