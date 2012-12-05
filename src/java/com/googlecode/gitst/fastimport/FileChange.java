package com.googlecode.gitst.fastimport;

/**
 * @author Andrey Pavlenko
 */
public abstract class FileChange implements FastimportCommand,
        Comparable<FileChange> {

    public abstract String getComment();

    public abstract int getPriority();

    @Override
    public int compareTo(final FileChange c) {
        final int p1 = getPriority();
        final int p2 = c.getPriority();
        return (p1 < p2) ? -1 : (p1 > p2) ? 1 : 0;
    }
}
