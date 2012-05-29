/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.commands.executors;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.state.HostDirectory;
import com.midokura.midolman.agent.state.HostZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import static com.midokura.midolman.agent.state.HostDirectory.Command;

public class NodeCommandWatcher {

    private final static Logger log = LoggerFactory.getLogger(
        NodeCommandWatcher.class);

    private UUID hostId;
    protected Set<Integer> executedCommands;
    private CommanderWatcher watcher;

    @Inject
    HostZkManager zkManager;

    @Inject
    CommandInterpreter commandInterpreter;

    public void checkCommands(UUID hostId) {
        this.hostId = hostId;
        this.watcher = new CommanderWatcher();
        this.executedCommands = new HashSet<Integer>();
        updateCommands();
    }

    private class CommanderWatcher implements Runnable {
        @Override
        public void run() {
            try {
                log.debug("NodeAgent.Command watcher was notified !");
                updateCommands();
            } catch (Exception e) {
                log.warn("CommanderWatcher.run", e);
            }
        }
    }

    private void updateCommands() {
        log.debug("Checking the current commands");
        Collection<ZkNodeEntry<Integer, HostDirectory.Command>> entryList = null;
        try {
            entryList = zkManager.listCommands(hostId, watcher);
        } catch (StateAccessException e) {
            log.warn("Cannot list the Commands.", e);
            return;
        }

        log.debug("We have {} commands to execute.", entryList.size());
        Map<Integer, HostDirectory.Command> currentCommands = new HashMap<Integer, HostDirectory.Command>();
        for (ZkNodeEntry<Integer, HostDirectory.Command> entry : entryList) {
            currentCommands.put(entry.key, entry.value);
        }

        Set<Integer> newCommands = new HashSet<Integer>(currentCommands.keySet());
        newCommands.removeAll(executedCommands);

        // Any brand new command should be processed.
        for (Integer commandId : newCommands) {
            //TODO(rossella) thread pool?
            executeCommands(currentCommands.get(commandId), commandId);
            // add it to executed
            executedCommands.add(commandId);
        }
    }

    private void executeCommands(final Command cmd, Integer cmdId) {

        List<CommandExecutor> commandExecutors =
            guardedWork(
                cmdId, cmd,
                new Callable<List<CommandExecutor>>() {
                    @Override
                    public List<CommandExecutor> call() throws Exception {
                        return commandInterpreter.interpret(cmd);
                    }
                });

        if (commandExecutors == null)
            return;

        for (final CommandExecutor commandExecutor : commandExecutors) {
            guardedWork(cmdId, cmd, new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    commandExecutor.execute();

                    return null;
                }
            });
        }
    }

    private <T> T guardedWork(Integer cmdId, Command cmd, Callable<T> work) {
        try {
            return work.call();
        } catch (Exception ex) {
            log.warn(
                "Something went wrong in executing command ID: {}, " +
                    "on interface {}",
                new Object[]{cmdId, cmd.getInterfaceName(), ex});

            // try to serialize the errors to zookeeper
            HostDirectory.ErrorLogItem errorLogItem =
                new HostDirectory.ErrorLogItem();

            errorLogItem.setError(ex.getMessage());
            errorLogItem.setCommandId(cmdId);
            errorLogItem.setInterfaceName(cmd.getInterfaceName());

            try {
                zkManager.setCommandErrorLogEntry(hostId, errorLogItem);
            } catch (StateAccessException sae) {
                log.error("Couldn't write the error log for host ID: {}, " +
                              "commandID {}, interface {}",
                          new Object[]{hostId, cmdId, cmd.getInterfaceName(),
                              sae});
            }

            return null;
        }
    }
}
