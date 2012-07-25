package com.googlecode.gitst.fastexport;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.googlecode.gitst.Repo;
import com.googlecode.gitst.StreamReader;

/**
 * @author Andrey Pavlenko
 */
public class ExportStreamReader {
    private final Repo _repo;
    private final StreamReader _stream;
    private final Map<String, FastExportCommandReader> _readers;
    private final Map<Integer, Commit> _commits = new TreeMap<>();
    private int _fileChangesCount;

    public ExportStreamReader(final Repo repo, final StreamReader stream) {
        _repo = repo;
        _stream = stream;
        _readers = new HashMap<>();
        _readers.put("reset", new NoopReader());
        _readers.put("commit", new CommitReader());
        _readers.put("mark", new MarkReader());
        _readers.put("author", new ComitterReader());
        _readers.put("committer", new ComitterReader());
        _readers.put("data", new DataReader());
        _readers.put("from", new FromReader());
        _readers.put("M", new FileModifyReader());
        _readers.put("D", new FileDeleteReader());
        _readers.put("R", new FileRenameReader());
        _readers.put("merge", new MergeReader());
    }

    public Repo getRepo() {
        return _repo;
    }

    public StreamReader getStreamReader() {
        return _stream;
    }

    public Map<Integer, Commit> getCommits() {
        return _commits;
    }

    public int getFileChangesCount() {
        return _fileChangesCount;
    }

    public Map<Integer, Commit> readCommits() throws IOException,
            UnsupportedCommandException {
        final StreamReader r = getStreamReader();
        int unmarked = Integer.MIN_VALUE;
        Commit commit = null;
        FastExportCommand prev = null;

        for (String l = r.readLine(); l != null; l = r.readLine()) {
            if ((l = l.trim()).length() > 0) {
                final String command = getCommand(l);
                final FastExportCommandReader cr = _readers.get(command);

                if (cr == null) {
                    throw new UnsupportedCommandException(l);
                } else {
                    final FastExportCommand c = cr.read(r, l, commit, prev);

                    if (c instanceof Commit) {
                        if (commit != null) {
                            Integer mark = commit.getMark();

                            if (mark == null) {
                                mark = unmarked++;
                                commit.setMark(mark);
                            }

                            _commits.put(mark, commit);
                        }

                        commit = (Commit) c;
                        prev = null;
                    } else {
                        if (c instanceof FileChange) {
                            _fileChangesCount++;
                        }

                        prev = c;
                    }
                }
            }
        }
        if (commit != null) {
            Integer mark = commit.getMark();

            if (mark == null) {
                mark = unmarked++;
                commit.setMark(mark);
            }

            _commits.put(mark, commit);
        }

        return _commits;
    }

    private static String getCommand(final String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ' ') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private void addChange(final Commit cmt, final FileChange c) {
        if (!getRepo().isIgnored(c.getPath())) {
            cmt.addChange(c);
        }
    }

    private interface FastExportCommandReader {
        public FastExportCommand read(StreamReader r, String line,
                Commit commit, FastExportCommand prev)
                throws UnsupportedCommandException, IOException;
    }

    private static class CommitReader implements FastExportCommandReader {
        @Override
        public FastExportCommand read(final StreamReader r, final String line,
                final Commit commit, final FastExportCommand prev)
                throws UnsupportedCommandException {
            return new Commit();
        }
    }

    private static class MarkReader implements FastExportCommandReader {
        @Override
        public FastExportCommand read(final StreamReader r, final String line,
                final Commit commit, final FastExportCommand prev)
                throws UnsupportedCommandException {
            final String[] args = r.split(line);
            final int mark = Integer.parseInt(args[1].substring(
                    args[1].indexOf(':') + 1, args[1].length()));
            commit.setMark(mark);
            return null;
        }
    }

    private static class ComitterReader implements FastExportCommandReader {
        @Override
        public FastExportCommand read(final StreamReader r, final String line,
                final Commit commit, final FastExportCommand prev)
                throws UnsupportedCommandException {
            final String[] args = r.split(line);

            if (args[0].equals("committer")) {
                final StringBuilder committer = new StringBuilder();

                for (int i = 1; i < args.length; i++) {
                    if (!args[i].endsWith(">")) {
                        committer.append(args[i]).append(' ');
                    } else {
                        committer.append(args[i]);
                        commit.setCommitter(committer.toString());
                        commit.setDate(args[i + 1], args[i + 2]);
                        break;
                    }
                }
            }
            return null;
        }
    }

    private static class DataReader implements FastExportCommandReader {
        private final Charset cs = Charset.forName("UTF8");

        @Override
        public FastExportCommand read(final StreamReader r, final String line,
                final Commit commit, final FastExportCommand prev)
                throws UnsupportedCommandException, IOException {
            final String[] args = r.split(line);
            final int length = Integer.parseInt(args[1]);
            final byte[] b = new byte[length];
            int count = 0;

            for (int i = r.read(b, count, b.length - count); (i != -1)
                    && (count < b.length); i = r.read(b, count, b.length
                    - count)) {
                count += i;
            }

            commit.setComment(new String(b, cs));
            return null;
        }
    }

    private static class FromReader implements FastExportCommandReader {
        @Override
        public FastExportCommand read(final StreamReader r, final String line,
                final Commit commit, final FastExportCommand prev)
                throws UnsupportedCommandException {
            final String[] args = r.split(line);
            final int from = Integer.parseInt(args[1].substring(
                    args[1].indexOf(':') + 1, args[1].length()));
            commit.setFrom(from);
            return null;
        }
    }

    private class FileModifyReader implements FastExportCommandReader {
        @Override
        public FastExportCommand read(final StreamReader r, final String line,
                final Commit commit, final FastExportCommand prev)
                throws UnsupportedCommandException {
            final String[] args = r.split(line);
            final FileModify c = new FileModify(args[2], line.substring(line
                    .indexOf(args[2]) + args[2].length() + 1));
            addChange(commit, c);
            return c;
        }
    }

    private class FileDeleteReader implements FastExportCommandReader {
        @Override
        public FastExportCommand read(final StreamReader r, final String line,
                final Commit commit, final FastExportCommand prev)
                throws UnsupportedCommandException {
            final FileDelete c = new FileDelete(line.substring(2));
            addChange(commit, c);
            return c;
        }
    }

    private class FileRenameReader implements FastExportCommandReader {
        @Override
        public FastExportCommand read(final StreamReader r, final String line,
                final Commit commit, final FastExportCommand prev)
                throws UnsupportedCommandException {
            final String[] args = r.split(line, true);
            final FileRename c = new FileRename(args[1], args[2]);
            addChange(commit, c);
            return c;
        }
    }

    private class MergeReader implements FastExportCommandReader {
        @Override
        public FastExportCommand read(final StreamReader r, final String line,
                final Commit commit, final FastExportCommand prev)
                throws UnsupportedCommandException {
            getRepo().getLogger().warn("Merge is not yet supported: " + line);
            return null;
        }
    }

    private static class NoopReader implements FastExportCommandReader {
        @Override
        public FastExportCommand read(final StreamReader r, final String line,
                final Commit commit, final FastExportCommand prev)
                throws UnsupportedCommandException {
            return null;
        }
    }
}
