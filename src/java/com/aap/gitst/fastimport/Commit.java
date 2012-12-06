package com.aap.gitst.fastimport;

import static com.aap.gitst.Repo.LS;

import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.aap.gitst.Repo;

/**
 * @author Andrey Pavlenko
 */
public class Commit implements FastimportCommand {
    private final CommitId _id;
    private final Queue<FileChange> _changes;
    private String _branch;
    private String _mark;
    private String _committer;
    private String _from;
    private String _comment;

    public Commit(final CommitId id) {
        _id = id;
        _changes = new ConcurrentLinkedQueue<>();
    }

    public CommitId getId() {
        return _id;
    }

    public String getBranch() {
        return _branch;
    }

    public String getMark() {
        return _mark;
    }

    public String getCommitter() {
        return _committer;
    }

    public String getFrom() {
        return _from;
    }

    public Queue<FileChange> getChanges() {
        return _changes;
    }

    public synchronized String getComment() {
        if (_comment == null) {
            final StringBuilder sb = new StringBuilder();
            final Set<String> comments = new HashSet<>();

            for (final FileChange c : getChanges()) {
                final String comment = c.getComment();

                if (comment != null) {
                    comments.add(comment);
                }
            }
            for (final Iterator<String> it = comments.iterator(); it.hasNext();) {
                sb.append(it.next());

                if (it.hasNext()) {
                    sb.append('\n');
                }
            }

            if (sb.length() == 0) {
                _comment = "No comments";
            } else {
                _comment = sb.toString().trim();
            }
        }

        return _comment;
    }

    public void setBranch(final String branch) {
        _branch = branch;
    }

    public void setMark(final String mark) {
        _mark = mark;
    }

    public void setCommitter(final String committer) {
        _committer = committer;
    }

    public void setFrom(final String from) {
        _from = from;
    }

    public void addChange(final FileChange c) {
        getChanges().add(c);
    }

    @Override
    public void write(final Repo repo, final PrintStream s) throws IOException,
            InterruptedException {
        s.print("commit ");
        s.print(getBranch());
        s.print('\n');

        if (_mark != null) {
            s.print("mark ");
            s.print(_mark);
            s.print('\n');
        }
        if (_committer != null) {
            final long time = getId().getTime();
            s.print("committer ");
            s.print(_committer);
            s.print(' ');
            s.print(time / 1000);
            s.print(' ');
            s.print(new SimpleDateFormat("Z").format(time));
            s.print('\n');
        }

        new TextData(toGitStComment(repo)).write(repo, s);

        if (_from != null) {
            s.print("from ");
            s.print(_from);
            s.print('\n');
        }

        for (final FileChange c : getChanges()) {
            c.write(repo, s);
        }

        s.print('\n');
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        final String comment = getComment();
        sb.append(new SimpleDateFormat(Repo.DATE_FORMAT).format(getId()
                .getTime()));
        sb.append(' ');
        sb.append(getCommitter()).append(LS);
        sb.append(comment);
        sb.append(LS);

        for (final FileChange c : getChanges()) {
            sb.append(LS).append(c);
        }

        return sb.toString();
    }

    private String toGitStComment(final Repo repo) {
        final StringBuilder sb = new StringBuilder();
        final Set<String> changes = new TreeSet<>();

        for (final FileChange c : getChanges()) {
            changes.add(c.toString());
        }
        for (final Iterator<String> it = changes.iterator(); it.hasNext();) {
            sb.append(it.next());

            if (it.hasNext()) {
                sb.append('\n');
            }
        }

        return repo.getCommentFormat()
                .format(new Object[] { getComment(), sb });
    }
}
