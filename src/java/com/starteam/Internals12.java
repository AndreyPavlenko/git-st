package com.starteam;

import java.io.IOException;

import System.Exception;

import com.googlecode.gitst.Repo;
import com.starteam.exceptions.CommandAbortedException;

/**
 * @author Andrey Pavlenko
 */
@SuppressWarnings("deprecation")
public class Internals12 {

    public static com.starbase.starteam.Item[] getHistory(final Repo repo,
            final com.starbase.starteam.Item i) {
        final com.starbase.starteam.Server connection = repo
                .getIdleConnection();

        try {
            final com.starteam.Server server = connection.unwrap();
            final com.starteam.Item item = (com.starteam.Item) i.unwrap();
            final Connection c = server.useConnection();
            final CmdGetHistory cmd = new CmdGetHistory(item);
            cmd.exec(c, server.getSession().getID(),
                    server.getViewSession(item.getView()).getID(), item
                            .getType().getClassID());
            return cmd.getHistory();
        } finally {
            repo.releaseConnection(connection);
        }
    }

    private static class CmdGetHistory extends CommandMacro {
        private static final CommandRoute _route = new CommandRoute(
                -2147483648, 524288, 2010, "PROJ_CMD_GET_ITEMS_HISTORY");
        private final com.starteam.Item _item;
        private com.starbase.starteam.Item[] _history;

        public CmdGetHistory(final com.starteam.Item item) {
            _item = item;
        }

        public com.starbase.starteam.Item[] getHistory() {
            return _history;
        }

        public void exec(final Connection c, final com.starteam.util.GUID guid,
                final int paramInt1, final int paramInt2) {
            c.lock();
            try {
                c.usingCommand(super.getClass().getName());
                final Command cmd = prepare(c, guid, paramInt1, paramInt2);
                push(cmd);
                c.execCommand(cmd);
                pop(cmd);
                c.terminateCommand(cmd);
            } catch (final CommandAbortedException ex) {
            } catch (final IOException ex) {
                throw new RuntimeException(ex);
            } finally {
                c.commandNotInUse();
                c.unlock();
            }
        }

        @Override
        public boolean isRetrySupported() {
            return true;
        }

        @Override
        public CommandRoute getCommandRoute() {
            return _route;
        }

        @Override
        protected void push(final Command c) throws IOException {
            c.writeInt(_item.getID());
            c.writeTime(0.0D);

            if ((c.supports("1.38"))) {
                c.writeBoolean(true);
            }
        }

        @Override
        public void pop(final Command c) throws IOException {
            final int count = c.readInt();
            _history = new com.starbase.starteam.Item[count];

            for (int i = 0; i < count; i++) {
                _history[i] = wrap(ItemRevisionEX.read(c));
            }
        }

        private com.starbase.starteam.Item wrap(final ItemRevisionEX r) {
            final com.starteam.Server server = _item.getServer();
            final com.starteam.Type type = _item.getType();
            final com.starteam.util.DateTime time = new com.starteam.util.DateTime(
                    r.m_time);
            final com.starteam.Item h = server.newItem(type, _item.getView(),
                    false, true);

            h.setSnapshotTime(time);
            h.setParentFolder(_item.getParentFolder());
            h.setVMID(_item.getID());
            h.initializeReplicaValue("RevisionNumber", new Integer(
                    r.m_revisionID.getRevisionNumber()));
            h.initializeReplicaValue("ID",
                    new Integer(r.m_revisionID.getObjectID()));
            h.initializeReplicaValue("ModifiedTime", time);
            h.initializeReplicaValue("DotNotation", r.m_dotNotation);
            h.initializeReplicaValue("Comment", r.m_comment);
            h.initializeReplicaValue("ModifiedUserID", new Integer(r.m_userID));
            h.initializeReplicaValue("PathRevision", new Integer(
                    r.m_pathRevision - r.m_revisionID.getRevisionNumber() - 1));
            h.initializeReplicaValue("ViewID", new Integer(r.m_viewID));

            return com.starbase.starteam.Item.wrap(h);
        }
    }
}
