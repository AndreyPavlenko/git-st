package com.aap.gitst.fastimport;

/**
 * @author Andrey Pavlenko
 */
public class CommitId implements Comparable<CommitId> {
    private final int _userId;
    private final long _time;

    public CommitId(final int userId, final long time) {
        _userId = userId;
        _time = time;
    }

    public int getUserId() {
        return _userId;
    }

    public long getTime() {
        return _time;
    }

    @Override
    public int hashCode() {
        return (int) (getTime() >> 32) | (getUserId() << 16);
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof CommitId) {
            final CommitId id = (CommitId) obj;
            return (getTime() == id.getTime())
                    && (getUserId() == id.getUserId());
        }
        return false;
    }

    @Override
    public int compareTo(final CommitId id) {
        final long time1 = getTime();
        final long time2 = id.getTime();

        if (time1 == time2) {
            final int user1 = getUserId();
            final int user2 = id.getUserId();
            return (user1 > user2) ? 1 : (user1 < user2) ? -1 : 0;
        } else if (time1 > time2) {
            return 1;
        } else {
            return -1;
        }
    }
}
