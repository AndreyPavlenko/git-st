package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.googlecode.gitst.Repo;

/**
 * @author Andrey Pavlenko
 */
public class Commit implements FastimportCommand {
    private final static SimpleDateFormat DATEFORMAT = new SimpleDateFormat("Z");
    private final CommitId _id;
    private final Queue<FileChange> _changes;
    private String _branch;
    private String _mark;
    private String _committer;
    private String _fromCommittiSh;
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

    public String getFromCommittiSh() {
        return _fromCommittiSh;
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

            _comment = sb.toString().trim();

            if (_comment.length() == 0) {
                _comment = "No comments";
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

    public void setFromCommittiSh(final String fromCommittiSh) {
        _fromCommittiSh = fromCommittiSh;
    }

    public void addChange(final FileChange c) {
        getChanges().add(c);
    }

    @Override
    public void write(final Repo repo, final PrintStream s) throws IOException,
            InterruptedException {
        s.print("commit refs/heads/");
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
            s.print(DATEFORMAT.format(time));
            s.print('\n');
        }

        new TextData(getComment()).write(repo, s);

        if (_fromCommittiSh != null) {
            s.print("from ");
            s.print(_fromCommittiSh);
            s.print('\n');
        }

        for (final FileChange c : getChanges()) {
            c.write(repo, s);
        }

        s.print('\n');
    }
}
