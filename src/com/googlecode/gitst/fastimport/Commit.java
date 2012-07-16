package com.googlecode.gitst.fastimport;

import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * @author Andrey Pavlenko
 */
public class Commit implements FastimportCommand {
    private final static SimpleDateFormat DATEFORMAT = new SimpleDateFormat("Z");
    private final CommitId _id;
    private final List<FileChange> _changes;
    private String _branch;
    private String _mark;
    private String _committer;
    private String _fromCommittiSh;
    private String _comment;

    public Commit(final CommitId id) {
        _id = id;
        _changes = new ArrayList<FileChange>();
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

    public List<FileChange> getChanges() {
        return _changes;
    }

    public String getComment() {
        if (_comment == null) {
            final StringBuilder sb = new StringBuilder();
            final Set<String> comments = new HashSet<>();

            for (final FileChange change : _changes) {
                final String comment = change.getComment();

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

    public void addChange(final FileChange cmd) {
        _changes.add(cmd);
    }

    @Override
    public void write(final PrintStream s) throws IOException {
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

        new TextData(getComment()).write(s);

        if (_fromCommittiSh != null) {
            s.print("from ");
            s.print(_fromCommittiSh);
            s.print('\n');
        }

        for (final FileChange cmd : _changes) {
            cmd.write(s);
        }

        s.print('\n');
    }
}
